package com.cid0980.connectwin.realtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.cid0980.connectwin.BridgeState
import com.cid0980.connectwin.MainActivity

/**
 * High-frame-rate screen capture via MediaProjection (one-time user consent).
 *
 * AccessibilityService.takeScreenshot is rate-limited (~3 fps). MediaProjection +
 * VirtualDisplay + ImageReader delivers frames at the display's update cadence with
 * no per-frame popup, which is what makes fast game perception possible. The latest
 * frame is kept in [frameStore]; on-device reflexes and binary streaming read it
 * without any network round-trip.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.cid0980.connectwin.capture.START"
        const val ACTION_STOP = "com.cid0980.connectwin.capture.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_MAX_WIDTH = "max_width"
        const val EXTRA_FPS = "fps"

        private const val CHANNEL_ID = "connect_win_capture"
        private const val NOTIF_ID = 8766

        @Volatile var instance: ScreenCaptureService? = null; private set
        val frameStore = FrameStore()

        fun isRunning(): Boolean = instance != null

        fun start(ctx: Context, resultCode: Int, data: Intent, maxWidth: Int, fps: Int) {
            val i = Intent(ctx, ScreenCaptureService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_MAX_WIDTH, maxWidth)
                .putExtra(EXTRA_FPS, fps)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ScreenCaptureService::class.java).setAction(ACTION_STOP))
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var maxWidth = 720
    private var fps = 30
    private var lastFrameAt = 0L
    private var displayListener: DisplayManager.DisplayListener? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopCapture()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        maxWidth = intent?.getIntExtra(EXTRA_MAX_WIDTH, 720) ?: 720
        fps = intent?.getIntExtra(EXTRA_FPS, 30) ?: 30

        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        if (resultCode == 0 || data == null) {
            BridgeState.log("Capture: missing MediaProjection consent")
            stopSelf()
            return START_NOT_STICKY
        }

        startCapture(resultCode, data)
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        stopCapture()
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data)
        if (proj == null) {
            BridgeState.log("Capture: getMediaProjection returned null")
            stopSelf()
            return
        }
        projection = proj
        proj.registerCallback(projectionCallback, null)

        thread = HandlerThread("cw-capture").apply { start() }
        handler = Handler(thread!!.looper)

        createVirtualDisplay()
        registerRotationListener()
        instance = this
        BridgeState.captureWanted = true
        BridgeState.log("Capture: MediaProjection live (max_width=$maxWidth, fps=$fps)")
    }

    private fun realSize(): Point {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val p = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(p)
        return p
    }

    private fun createVirtualDisplay() {
        val proj = projection ?: return
        val real = realSize()
        val scale = if (maxWidth in 1 until real.x) maxWidth.toFloat() / real.x else 1f
        val w = (real.x * scale).toInt().coerceAtLeast(2)
        val h = (real.y * scale).toInt().coerceAtLeast(2)
        val dpi = (resources.displayMetrics.densityDpi * scale).toInt().coerceAtLeast(1)

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 4) // 2 -> 4: headroom so a slow consumer can't starve the producer
        reader.setOnImageAvailableListener({ ir ->
            // FIX (frame-rate bug): the fps throttle MUST NOT return from inside the
            // try block. Doing so skipped the `finally { img.close() }`, leaking an
            // ImageReader buffer per dropped frame. Once all slots (maxImages=2) were
            // held, acquireLatestImage() returned null forever, so frameStore stopped
            // publishing (~1.3 fps measured on-device instead of the configured 30).
            // Now we always acquire and always close; throttling is a no-op decision
            // that still releases the buffer.
            val img = try {
                ir.acquireLatestImage()
            } catch (t: Throwable) {
                null
            } ?: return@setOnImageAvailableListener
            // `run` gives the block a name we can return from, which is what the
            // original bug needed: `try` in Kotlin is not a lambda, so you cannot
            // `return@try` to skip work while still running `finally`. Escaping the
            // throttle must not skip img.close().
            try {
                run throttled@{
                    val now = SystemClock.elapsedRealtime()
                    val minInterval = if (fps > 0) 1000L / fps else 0L
                    if (now - lastFrameAt < minInterval) return@throttled
                    lastFrameAt = now
                    val bmp = imageToBitmap(img) ?: return@throttled
                    frameStore.publish(bmp)
                    val publishedAt = SystemClock.elapsedRealtime()
                    // Instrumentation: capture->publish cost, and resolution of any
                    // pending dispatch->seen measurements against this new frame.
                    LatencyStats.onPublished(now, publishedAt)
                    if (LatencyStats.pendingCount() > 0) {
                        LatencyStats.signature(bmp)?.let { sig ->
                            LatencyStats.onFrameSignature(
                                sig, bmp.width, bmp.height, publishedAt
                            )
                        }
                    }
                    ReflexEngine.onFrame()
                }
            } catch (t: Throwable) {
                BridgeState.log("Capture frame error: ${t.javaClass.simpleName}")
            } finally {
                img.close()
            }
        }, handler)
        imageReader = reader

        virtualDisplay = proj.createVirtualDisplay(
            "cw-capture", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
    }

    private fun imageToBitmap(img: android.media.Image): Bitmap? {
        val plane = img.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = img.width
        val height = img.height
        val paddedWidth = rowStride / pixelStride
        return try {
            val bmp = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            bmp.copyPixelsFromBuffer(buffer)
            if (paddedWidth != width) {
                val cropped = Bitmap.createBitmap(bmp, 0, 0, width, height)
                bmp.recycle()
                cropped
            } else bmp
        } catch (t: Throwable) {
            null
        }
    }

    private fun registerRotationListener() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                handler?.post {
                    virtualDisplay?.release()
                    imageReader?.close()
                    createVirtualDisplay()
                }
            }
        }
        dm.registerDisplayListener(listener, handler)
        displayListener = listener
    }

    private fun stopCapture() {
        instance = null
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        virtualDisplay = null
        imageReader = null
        projection = null
        displayListener?.let {
            runCatching { (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(it) }
        }
        displayListener = null
        thread?.quitSafely()
        thread = null
        handler = null
        frameStore.clear()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Connect-win screen capture", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        val openPi = PendingIntent.getActivity(
            this, 10, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 11, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Connect-win fast capture active")
            .setContentText("Streaming screen for realtime control")
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(0, "STOP", stopPi)
            .build()
    }
}

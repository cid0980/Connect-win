package com.cid0980.connectwin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class BridgeService : Service() {

    companion object {
        const val ACTION_START = "com.cid0980.connectwin.ACTION_START"
        const val ACTION_STOP = "com.cid0980.connectwin.ACTION_STOP"
        const val ACTION_COPY_ARENA = "com.cid0980.connectwin.ACTION_COPY_ARENA"
        const val ACTION_RESTART_TUNNEL = "com.cid0980.connectwin.ACTION_RESTART_TUNNEL"

        private const val CHANNEL_ID = "connect_win_bridge_channel"
        private const val NOTIF_ID = 8765

        fun start(ctx: Context) {
            val i = Intent(ctx, BridgeService::class.java).setAction(ACTION_START)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, BridgeService::class.java).setAction(ACTION_STOP)
            ctx.startService(i)
        }

        fun buildArenaSnippet(ctx: Context): String {
            val url = BridgeState.snapshot.value.tunnelUrl ?: "http://127.0.0.1:${BridgeState.HTTP_PORT}"
            val token = BridgeState.getOrCreateToken(ctx)
            return "Mobile Bridge (Connect-win)\nBRIDGE_URL=$url\nBRIDGE_TOKEN=$token"
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var httpServer: BridgeHttpServer? = null
    private var tunnelManager: TunnelManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        BridgeState.init(this)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                BridgeState.setWasRunning(this, false)
                shutdownBridge()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_COPY_ARENA -> {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Connect-win", buildArenaSnippet(this)))
                Toast.makeText(this, "Copied URL + Token for Arena!", Toast.LENGTH_SHORT).show()
                return START_STICKY
            }
            ACTION_RESTART_TUNNEL -> {
                tunnelManager?.start()
                return START_STICKY
            }
        }

        val notif = buildNotification(BridgeState.snapshot.value.tunnelUrl)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        startBridgeIfNeeded()
        return START_STICKY
    }

    private fun startBridgeIfNeeded() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ConnectWin::BridgeKeepAlive").apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        if (httpServer == null) {
            val srv = BridgeHttpServer(applicationContext, BridgeState.HTTP_PORT)
            srv.start(30_000, false)
            httpServer = srv
            BridgeState.log("HTTP server listening on 127.0.0.1:${BridgeState.HTTP_PORT}")
        }

        if (tunnelManager == null) {
            tunnelManager = TunnelManager(applicationContext, serviceScope) { newUrl ->
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, buildNotification(newUrl))
            }.also { it.start() }
        }

        BridgeState.setWasRunning(this, true)
        BridgeState.update {
            it.copy(
                running = true,
                httpListening = true,
                startedAtMs = if (it.startedAtMs > 0L) it.startedAtMs else System.currentTimeMillis()
            )
        }
    }

    private fun shutdownBridge() {
        BridgeState.log("Bridge stopping")
        runCatching { tunnelManager?.stop() }
        tunnelManager = null
        runCatching { httpServer?.stop() }
        httpServer = null
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
        BridgeState.update {
            BridgeSnapshot(running = false, httpListening = false, tunnelStatus = "Stopped")
        }
    }

    override fun onDestroy() {
        shutdownBridge()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Connect-win Active Bridge",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows live Connect-win tunnel URL and one-tap kill switch"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(url: String?): Notification {
        val openAppPi = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val copyPi = PendingIntent.getService(
            this,
            2,
            Intent(this, BridgeService::class.java).setAction(ACTION_COPY_ARENA),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this,
            3,
            Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val subtitle = url ?: "Starting Cloudflare tunnel..."
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Connect-win Bridge Active")
            .setContentText(subtitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(subtitle))
            .setOngoing(true)
            .setContentIntent(openAppPi)
            .addAction(0, "Copy URL+Token", copyPi)
            .addAction(0, "STOP", stopPi)
            .build()
    }
}

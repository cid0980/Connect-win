package com.cid0980.connectwin

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Point
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ImageReader
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.util.Base64
import android.view.KeyEvent
import android.view.WindowManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object PhoneController {

    private val APP_ALIASES = mapOf(
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "telegram" to listOf("org.telegram.messenger", "org.thunderdog.challegram"),
        "chrome" to listOf("com.android.chrome", "com.chrome.beta", "org.chromium.chrome"),
        "firefox" to listOf("org.mozilla.firefox", "org.mozilla.fenix"),
        "youtube" to listOf("com.google.android.youtube", "app.revanced.android.youtube"),
        "instagram" to listOf("com.instagram.android"),
        "gmail" to listOf("com.google.android.gm"),
        "maps" to listOf("com.google.android.apps.maps"),
        "settings" to listOf("com.android.settings"),
        "camera" to listOf("com.google.android.GoogleCamera", "com.android.camera", "com.android.camera2", "com.sec.android.app.camera"),
        "files" to listOf("com.google.android.documentsui", "com.android.documentsui"),
        "playstore" to listOf("com.android.vending"),
        "phone" to listOf("com.google.android.dialer", "com.android.dialer"),
        "messages" to listOf("com.google.android.apps.messaging", "com.android.mms"),
        "spotify" to listOf("com.spotify.music")
    )

    // ==================================================================
    // PERMISSIONS & SYSTEM VITALS
    // ==================================================================
    fun hasPerm(ctx: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    fun isAccessibilityEnabled(ctx: Context): Boolean {
        if (BridgeAccessibilityService.instance != null) return true
        val expected = "${ctx.packageName}/${BridgeAccessibilityService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    fun isNotificationListenerEnabled(ctx: Context): Boolean =
        BridgeNotificationService.instance != null ||
            NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun permissionsStatus(ctx: Context): JSONObject {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val postNotif = if (Build.VERSION.SDK_INT >= 33) {
            hasPerm(ctx, Manifest.permission.POST_NOTIFICATIONS)
        } else true

        // Distinguish "the setting is on" from "the service is actually bound".
        // Observed on-device: after a package update Android kept the accessibility
        // setting ON but the service instance was gone, so /status reported
        // accessibility: true while every /gui/* call returned 503. A capability
        // report that can be true while the capability is dead is worse than no
        // report, because an agent plans around it.
        val a11ySetting = isAccessibilityEnabled(ctx)
        val a11yLive = BridgeAccessibilityService.instance != null
        return JSONObject().apply {
            put("accessibility", a11ySetting && a11yLive)
            put("accessibility_setting", a11ySetting)
            put("accessibility_live", a11yLive)
            if (a11ySetting && !a11yLive) {
                put(
                    "accessibility_warning",
                    "Accessibility is enabled in Settings but the service is not bound — " +
                        "all input (tap/swipe/key) will fail. Toggle Connect-win off and on " +
                        "in Settings -> Accessibility, or reboot. This can happen after an " +
                        "app update."
                )
            }
            put("notification_listener", isNotificationListenerEnabled(ctx))
            put("all_files", Environment.isExternalStorageManager())
            put("battery_unrestricted", pm.isIgnoringBatteryOptimizations(ctx.packageName))
            put("overlay", Settings.canDrawOverlays(ctx))
            put("write_settings", Settings.System.canWrite(ctx))
            put("install_packages", ctx.packageManager.canRequestPackageInstalls())
            put("post_notifications", postNotif)
            put("location", hasPerm(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || hasPerm(ctx, Manifest.permission.ACCESS_COARSE_LOCATION))
            put("camera", hasPerm(ctx, Manifest.permission.CAMERA))
            put("sms", hasPerm(ctx, Manifest.permission.READ_SMS) && hasPerm(ctx, Manifest.permission.SEND_SMS))
            put("contacts", hasPerm(ctx, Manifest.permission.READ_CONTACTS))
            put("phone", hasPerm(ctx, Manifest.permission.CALL_PHONE) && hasPerm(ctx, Manifest.permission.READ_CALL_LOG))
            put("microphone", hasPerm(ctx, Manifest.permission.RECORD_AUDIO))
        }
    }

    fun deviceStatus(ctx: Context): JSONObject {
        val bm = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bm?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bm?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val battPct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val status = bm?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val netType = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }

        val stat = StatFs(Environment.getDataDirectory().path)
        val freeGb = (stat.availableBytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
        val totalGb = (stat.totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0))

        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val displaySize = Point()
        @Suppress("DEPRECATION")
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealSize(displaySize)

        return JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("android_version", Build.VERSION.RELEASE)
            put("sdk_int", Build.VERSION.SDK_INT)
            put("battery_pct", battPct)
            put("charging", charging)
            put("network", netType)
            put("screen_on", pm.isInteractive)
            put("device_locked", km.isKeyguardLocked)
            put("screen", JSONArray(listOf(displaySize.x, displaySize.y)))
            put("foreground_package", BridgeAccessibilityService.currentPackage)
            put("foreground_activity", BridgeAccessibilityService.currentActivity)
            put("ram_avail_gb", "%.1f".format(Locale.US, memInfo.availMem / 1e9))
            put("ram_total_gb", "%.1f".format(Locale.US, memInfo.totalMem / 1e9))
            put("storage_free_gb", "%.1f".format(Locale.US, freeGb))
            put("storage_total_gb", "%.1f".format(Locale.US, totalGb))
            put("media_volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            put("media_max_volume", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            put("ringer_mode", when (audio.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            })
        }
    }

    // ==================================================================
    // STORAGE & FILE SYSTEM (/sdcard + app files)
    // ==================================================================
    fun resolvePath(ctx: Context, rawPath: String?): File {
        val sdcard = Environment.getExternalStorageDirectory()
        val p = (rawPath ?: "~").trim()
        return when {
            p == "~" || p.isEmpty() -> if (Environment.isExternalStorageManager()) sdcard else ctx.filesDir
            p.startsWith("~/") -> {
                val base = if (Environment.isExternalStorageManager()) sdcard else ctx.filesDir
                File(base, p.removePrefix("~/"))
            }
            p == "/sdcard" -> sdcard
            p.startsWith("/sdcard/") -> File(sdcard, p.removePrefix("/sdcard/"))
            else -> File(p)
        }
    }

    fun listDirectory(ctx: Context, rawPath: String?, maxEntries: Int = 300): JSONObject {
        val dir = resolvePath(ctx, rawPath)
        if (!dir.exists()) return JSONObject().put("ok", false).put("error", "not_found: ${dir.absolutePath}")
        if (!dir.isDirectory) return JSONObject().put("ok", false).put("error", "not_a_directory: ${dir.absolutePath}")

        val children = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: return JSONObject().put("ok", false).put("error", "permission_denied: grant All Files Access")

        val entries = JSONArray()
        val lines = StringBuilder("# Contents of ${dir.absolutePath}\n")
        children.take(maxEntries).forEach { f ->
            entries.put(JSONObject().apply {
                put("name", f.name)
                put("dir", f.isDirectory)
                put("size", if (f.isFile) f.length() else 0L)
                put("mtime", f.lastModified())
            })
            if (f.isDirectory) lines.append("[dir]  ${f.name}/\n")
            else lines.append("       ${f.name}  (${f.length()} bytes)\n")
        }
        if (children.size > maxEntries) {
            lines.append("... and ${children.size - maxEntries} more\n")
        }
        return JSONObject().apply {
            put("ok", true)
            put("path", dir.absolutePath)
            put("total", children.size)
            put("entries", entries)
            put("result", lines.toString())
        }
    }

    fun readFileText(ctx: Context, rawPath: String, maxChars: Int = 30_000): JSONObject {
        val f = resolvePath(ctx, rawPath)
        if (!f.exists() || !f.isFile) {
            return JSONObject().put("ok", false).put("error", "file_not_found: ${f.absolutePath}")
        }
        val text = f.readText(Charsets.UTF_8)
        val clipped = if (text.length > maxChars) {
            text.take(maxChars) + "\n\n... [truncated ${text.length - maxChars} chars]"
        } else text
        return JSONObject().apply {
            put("ok", true)
            put("path", f.absolutePath)
            put("size", f.length())
            put("result", clipped)
        }
    }

    fun writeFile(ctx: Context, rawPath: String, content: String?, b64: String?, append: Boolean = false): JSONObject {
        val f = resolvePath(ctx, rawPath)
        f.parentFile?.mkdirs()
        if (!b64.isNullOrEmpty()) {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            if (append && f.exists()) f.appendBytes(bytes) else f.writeBytes(bytes)
        } else {
            val txt = content ?: ""
            if (append && f.exists()) f.appendText(txt) else f.writeText(txt)
        }
        return JSONObject().apply {
            put("ok", true)
            put("saved_to", f.absolutePath)
            put("bytes", f.length())
        }
    }

    fun deleteFile(ctx: Context, rawPath: String): JSONObject {
        val f = resolvePath(ctx, rawPath)
        if (!f.exists()) return JSONObject().put("ok", false).put("error", "not_found")
        val ok = f.deleteRecursively()
        return JSONObject().put("ok", ok).put("deleted", f.absolutePath)
    }

    // ==================================================================
    // APPS, INTENTS, DEEP LINKS & APK INSTALLER
    // ==================================================================
    fun listApps(ctx: Context, query: String? = null, userOnly: Boolean = false): JSONObject {
        val pm = ctx.packageManager
        val pkgs = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val q = query?.trim()?.lowercase()
        val arr = JSONArray()

        pkgs.mapNotNull { app ->
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (userOnly && isSystem) return@mapNotNull null
            val launchable = pm.getLaunchIntentForPackage(app.packageName) != null
            if (!launchable && userOnly) return@mapNotNull null
            val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(app.packageName)
            if (!q.isNullOrEmpty() && !label.lowercase().contains(q) && !app.packageName.lowercase().contains(q)) {
                return@mapNotNull null
            }
            Triple(label, app.packageName, launchable)
        }.sortedBy { it.first.lowercase() }.take(250).forEach { (label, pkg, launchable) ->
            arr.put(JSONObject().apply {
                put("label", label)
                put("package", pkg)
                put("launchable", launchable)
            })
        }

        return JSONObject().put("count", arr.length()).put("apps", arr)
    }

    fun openTarget(ctx: Context, target: String): JSONObject {
        val t = target.trim()
        val pm = ctx.packageManager

        // 1. URL / Deep link (https://, whatsapp://, tel:, mailto:, upi://, etc.)
        if (t.contains("://") || t.startsWith("tel:") || t.startsWith("mailto:") || t.startsWith("sms:")) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(t)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            return JSONObject().put("ok", true).put("opened_uri", t)
        }

        // 2. Alias lookup ("whatsapp", "settings", "chrome")
        val candidates = mutableListOf<String>()
        APP_ALIASES[t.lowercase()]?.let { candidates.addAll(it) }
        candidates.add(t)

        for (pkg in candidates) {
            val launch = pm.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                ctx.startActivity(launch)
                return JSONObject().put("ok", true).put("opened_package", pkg)
            }
        }

        // 3. Fuzzy search by visible app label ("Zomato", "PhonePe", "Calculator")
        val allApps = pm.getInstalledApplications(0)
        val fuzzy = allApps.firstOrNull { app ->
            val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault("")
            label.equals(t, ignoreCase = true) && pm.getLaunchIntentForPackage(app.packageName) != null
        } ?: allApps.firstOrNull { app ->
            val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault("")
            label.contains(t, ignoreCase = true) && pm.getLaunchIntentForPackage(app.packageName) != null
        }

        if (fuzzy != null) {
            val launch = pm.getLaunchIntentForPackage(fuzzy.packageName)!!
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ctx.startActivity(launch)
            return JSONObject().apply {
                put("ok", true)
                put("opened_package", fuzzy.packageName)
                put("label", pm.getApplicationLabel(fuzzy).toString())
            }
        }

        return JSONObject().put("ok", false).put("error", "App or URI not found: $t")
    }

    fun sendCustomIntent(ctx: Context, body: JSONObject): JSONObject {
        val action = body.optString("action", Intent.ACTION_VIEW)
        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val data = body.optString("data", "")
            val type = body.optString("type", "")
            if (data.isNotEmpty() && type.isNotEmpty()) setDataAndType(Uri.parse(data), type)
            else if (data.isNotEmpty()) setData(Uri.parse(data))
            else if (type.isNotEmpty()) setType(type)

            val pkg = body.optString("package", "")
            val cls = body.optString("class", "")
            if (pkg.isNotEmpty() && cls.isNotEmpty()) component = ComponentName(pkg, cls)
            else if (pkg.isNotEmpty()) setPackage(pkg)

            val extras = body.optJSONObject("extras")
            if (extras != null) {
                for (k in extras.keys()) {
                    when (val v = extras.get(k)) {
                        is Boolean -> putExtra(k, v)
                        is Int -> putExtra(k, v)
                        is Long -> putExtra(k, v)
                        is Double -> putExtra(k, v)
                        else -> putExtra(k, v.toString())
                    }
                }
            }
        }
        ctx.startActivity(intent)
        return JSONObject().put("ok", true).put("sent_action", action)
    }

    fun installApk(ctx: Context, rawPath: String): JSONObject {
        val f = resolvePath(ctx, rawPath)
        if (!f.exists()) return JSONObject().put("ok", false).put("error", "apk_not_found: ${f.absolutePath}")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(intent)
        return JSONObject().apply {
            put("ok", true)
            put("prompted_install", f.absolutePath)
            put("hint", "Use /ui/click {\"text\":\"Install\"} or {\"text\":\"Update\"} to confirm dialog")
        }
    }

    // ==================================================================
    // CLIPBOARD, WAKE, TORCH, VOLUME, BRIGHTNESS, MEDIA, TTS, VIBRATE
    // ==================================================================
    fun getClipboard(ctx: Context): JSONObject {
        val ref = AtomicReference("")
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            runCatching {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val item = cm.primaryClip?.getItemAt(0)
                ref.set(item?.coerceToText(ctx)?.toString() ?: "")
            }
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        return JSONObject().put("text", ref.get())
    }

    fun setClipboard(ctx: Context, text: String): JSONObject {
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            runCatching {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("connect-win", text))
            }
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        return JSONObject().put("ok", true).put("copied_chars", text.length)
    }

    @Suppress("DEPRECATION")
    fun wakeScreen(ctx: Context, swipeUp: Boolean = true): JSONObject {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "ConnectWin:ScreenWake"
            )
            wl.acquire(5000L)
            Thread.sleep(350)
        }
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (swipeUp && km.isKeyguardLocked) {
            BridgeAccessibilityService.instance?.let { svc ->
                val (w, h) = svc.screenSize()
                svc.swipe(w / 2, (h * 0.82).toInt(), w / 2, (h * 0.22).toInt(), 260L)
            }
        }
        return JSONObject().apply {
            put("ok", true)
            put("screen_on", pm.isInteractive)
            put("still_locked", km.isKeyguardLocked)
        }
    }

    fun setTorch(ctx: Context, on: Boolean): JSONObject {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return JSONObject().put("ok", false).put("error", "no_flash_unit")
        cm.setTorchMode(camId, on)
        return JSONObject().put("ok", true).put("torch", on)
    }

    fun setVolume(ctx: Context, streamName: String, level: Int?, pct: Int?): JSONObject {
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = when (streamName.lowercase()) {
            "ring", "ringer" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification", "notif" -> AudioManager.STREAM_NOTIFICATION
            "call", "voice" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val max = audio.getStreamMaxVolume(stream)
        val target = when {
            level != null -> level.coerceIn(0, max)
            pct != null -> ((pct.coerceIn(0, 100) * max) / 100.0).toInt()
            else -> audio.getStreamVolume(stream)
        }
        audio.setStreamVolume(stream, target, 0)
        return JSONObject().apply {
            put("ok", true)
            put("stream", streamName)
            put("volume", audio.getStreamVolume(stream))
            put("max", max)
        }
    }

    fun setBrightness(ctx: Context, value: Int): JSONObject {
        if (!Settings.System.canWrite(ctx)) {
            return JSONObject().put("ok", false).put("error", "Grant Modify System Settings first")
        }
        val clamped = value.coerceIn(0, 255)
        Settings.System.putInt(
            ctx.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, clamped)
        return JSONObject().put("ok", true).put("brightness", clamped)
    }

    fun mediaControl(ctx: Context, command: String): JSONObject {
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val keyCode = when (command.lowercase()) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev", "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return JSONObject().put("ok", true).put("media_key", command)
    }

    fun vibrate(ctx: Context, durationMs: Long = 250L): JSONObject {
        val vib: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vib.vibrate(VibrationEffect.createOneShot(durationMs.coerceIn(20L, 5000L), VibrationEffect.DEFAULT_AMPLITUDE))
        return JSONObject().put("ok", true).put("vibrated_ms", durationMs)
    }

    @Volatile
    private var ttsEngine: TextToSpeech? = null

    fun speakTts(ctx: Context, text: String): JSONObject {
        val initLatch = CountDownLatch(1)
        if (ttsEngine == null) {
            Handler(Looper.getMainLooper()).post {
                ttsEngine = TextToSpeech(ctx.applicationContext) {
                    initLatch.countDown()
                }
            }
            initLatch.await(4, TimeUnit.SECONDS)
        }
        ttsEngine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        return JSONObject().put("ok", true).put("speaking", text.take(80))
    }

    // ==================================================================
    // GPS LOCATION & HEADLESS CAMERA2 CAPTURE
    // ==================================================================
    @SuppressLint("MissingPermission")
    fun getLocation(ctx: Context, freshTimeoutMs: Long = 4000L): JSONObject {
        if (!hasPerm(ctx, Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPerm(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            return JSONObject().put("ok", false).put("error", "Location permission not granted")
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        var best: Location? = null
        for (p in providers) {
            runCatching {
                val loc = lm.getLastKnownLocation(p)
                if (loc != null && (best == null || loc.time > best!!.time)) {
                    best = loc
                }
            }
        }

        // Request a quick fresh update if last known is older than 2 minutes or missing
        val now = System.currentTimeMillis()
        if ((best == null || now - best!!.time > 120_000L) && freshTimeoutMs > 0) {
            val latch = CountDownLatch(1)
            val freshRef = AtomicReference<Location?>(null)
            val thread = HandlerThread("LocSingle").apply { start() }
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    freshRef.set(location)
                    latch.countDown()
                }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            try {
                val activeProv = when {
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    else -> null
                }
                if (activeProv != null) {
                    lm.requestLocationUpdates(activeProv, 0L, 0f, listener, thread.looper)
                    latch.await(freshTimeoutMs.coerceIn(500L, 12_000L), TimeUnit.MILLISECONDS)
                    lm.removeUpdates(listener)
                }
            } catch (_: Throwable) {
            } finally {
                thread.quitSafely()
            }
            freshRef.get()?.let { best = it }
        }

        val loc = best ?: return JSONObject().put("ok", false).put("error", "No GPS/Network location fix available (is Location enabled?)")
        return JSONObject().apply {
            put("ok", true)
            put("lat", loc.latitude)
            put("lon", loc.longitude)
            put("accuracy_m", loc.accuracy)
            put("altitude", loc.altitude)
            put("speed_mps", loc.speed)
            put("provider", loc.provider ?: "unknown")
            put("age_s", (System.currentTimeMillis() - loc.time) / 1000)
            put("maps_url", "https://maps.google.com/?q=${loc.latitude},${loc.longitude}")
        }
    }

    @SuppressLint("MissingPermission")
    fun capturePhoto(
        ctx: Context,
        facing: String = "back",
        savePath: String? = null
    ): JSONObject {
        if (!hasPerm(ctx, Manifest.permission.CAMERA)) {
            return JSONObject().put("ok", false).put("error", "Camera permission not granted")
        }
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val wantLens = if (facing.equals("front", ignoreCase = true)) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        val camId = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == wantLens
        } ?: cm.cameraIdList.firstOrNull()
            ?: return JSONObject().put("ok", false).put("error", "No camera found")

        val chars = cm.getCameraCharacteristics(camId)
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)
        val chosenSize = sizes?.firstOrNull { it.width in 960..1920 }
            ?: sizes?.minByOrNull { kotlin.math.abs(it.width - 1280) }
        val w = chosenSize?.width ?: 1280
        val h = chosenSize?.height ?: 720

        val bgThread = HandlerThread("Cam2Capture").apply { start() }
        val handler = Handler(bgThread.looper)
        val reader = ImageReader.newInstance(w, h, ImageFormat.JPEG, 2)
        val jpegRef = AtomicReference<ByteArray?>(null)
        val errRef = AtomicReference<String?>(null)
        val doneLatch = CountDownLatch(1)
        var camDevice: CameraDevice? = null

        reader.setOnImageAvailableListener({ ir ->
            val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                jpegRef.set(bytes)
            } finally {
                img.close()
                doneLatch.countDown()
            }
        }, handler)

        try {
            cm.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    camDevice = camera
                    try {
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(
                            listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                            addTarget(reader.surface)
                                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                            set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                                            set(CaptureRequest.JPEG_QUALITY, 82.toByte())
                                        }
                                        session.capture(req.build(), null, handler)
                                    } catch (e: Exception) {
                                        errRef.set(e.message)
                                        doneLatch.countDown()
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    errRef.set("Camera session config failed")
                                    doneLatch.countDown()
                                }
                            },
                            handler
                        )
                    } catch (e: Exception) {
                        errRef.set(e.message)
                        doneLatch.countDown()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    errRef.set("Camera disconnected")
                    doneLatch.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    errRef.set("Camera error code $error (camera in use or background restricted)")
                    doneLatch.countDown()
                }
            }, handler)

            doneLatch.await(8, TimeUnit.SECONDS)
        } catch (e: Exception) {
            errRef.set(e.message)
        } finally {
            runCatching { camDevice?.close() }
            runCatching { reader.close() }
            bgThread.quitSafely()
        }

        val bytes = jpegRef.get() ?: return JSONObject().apply {
            put("ok", false)
            put("error", errRef.get() ?: "Timed out capturing photo")
        }

        var savedTo: String? = null
        if (!savePath.isNullOrBlank()) {
            val f = resolvePath(ctx, savePath)
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
            savedTo = f.absolutePath
        }

        return JSONObject().apply {
            put("ok", true)
            put("mime", "image/jpeg")
            put("bytes", bytes.size)
            put("width", w)
            put("height", h)
            if (savedTo != null) put("saved_to", savedTo)
            put("b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
    }

    // ==================================================================
    // SMS, CONTACTS & CALLS
    // ==================================================================
    fun readSms(ctx: Context, limit: Int = 20, query: String? = null): JSONObject {
        if (!hasPerm(ctx, Manifest.permission.READ_SMS)) {
            return JSONObject().put("ok", false).put("error", "READ_SMS permission not granted")
        }
        val arr = JSONArray()
        val cursor = ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        )
        cursor?.use { c ->
            val q = query?.trim()?.lowercase()
            while (c.moveToNext() && arr.length() < limit.coerceIn(1, 100)) {
                val addr = c.getString(1) ?: ""
                val body = c.getString(2) ?: ""
                val date = c.getLong(3)
                val type = c.getInt(4)
                if (!q.isNullOrEmpty() && !addr.lowercase().contains(q) && !body.lowercase().contains(q)) {
                    continue
                }
                arr.put(JSONObject().apply {
                    put("address", addr)
                    put("body", body)
                    put("date", date)
                    put("box", if (type == Telephony.Sms.MESSAGE_TYPE_SENT) "sent" else "inbox")
                })
            }
        }
        return JSONObject().put("ok", true).put("count", arr.length()).put("messages", arr)
    }

    fun sendSms(ctx: Context, to: String, message: String): JSONObject {
        if (!hasPerm(ctx, Manifest.permission.SEND_SMS)) {
            return JSONObject().put("ok", false).put("error", "SEND_SMS permission not granted")
        }
        val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        val parts = smsManager.divideMessage(message)
        if (parts.size > 1) {
            smsManager.sendMultipartTextMessage(to, null, parts, null, null)
        } else {
            smsManager.sendTextMessage(to, null, message, null, null)
        }
        return JSONObject().put("ok", true).put("to", to).put("parts", parts.size)
    }

    fun searchContacts(ctx: Context, query: String? = null, limit: Int = 50): JSONObject {
        if (!hasPerm(ctx, Manifest.permission.READ_CONTACTS)) {
            return JSONObject().put("ok", false).put("error", "READ_CONTACTS permission not granted")
        }
        val arr = JSONArray()
        val q = query?.trim()?.lowercase()
        val cursor = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )
        val seen = HashSet<String>()
        cursor?.use { c ->
            while (c.moveToNext() && arr.length() < limit.coerceIn(1, 200)) {
                val name = c.getString(0) ?: ""
                val num = c.getString(1) ?: ""
                if (!q.isNullOrEmpty() && !name.lowercase().contains(q) && !num.lowercase().contains(q)) {
                    continue
                }
                val key = "$name|$num"
                if (seen.add(key)) {
                    arr.put(JSONObject().put("name", name).put("number", num))
                }
            }
        }
        return JSONObject().put("ok", true).put("count", arr.length()).put("contacts", arr)
    }

    fun readCallLog(ctx: Context, limit: Int = 25): JSONObject {
        if (!hasPerm(ctx, Manifest.permission.READ_CALL_LOG)) {
            return JSONObject().put("ok", false).put("error", "READ_CALL_LOG permission not granted")
        }
        val arr = JSONArray()
        val cursor = ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null,
            null,
            "${CallLog.Calls.DATE} DESC"
        )
        cursor?.use { c ->
            while (c.moveToNext() && arr.length() < limit.coerceIn(1, 100)) {
                val typeStr = when (c.getInt(2)) {
                    CallLog.Calls.INCOMING_TYPE -> "incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                    CallLog.Calls.MISSED_TYPE -> "missed"
                    CallLog.Calls.REJECTED_TYPE -> "rejected"
                    else -> "other"
                }
                arr.put(JSONObject().apply {
                    put("number", c.getString(0) ?: "")
                    put("name", c.getString(1) ?: "")
                    put("type", typeStr)
                    put("date", c.getLong(3))
                    put("duration_s", c.getLong(4))
                })
            }
        }
        return JSONObject().put("ok", true).put("count", arr.length()).put("calls", arr)
    }

    fun makeCall(ctx: Context, number: String, directCall: Boolean = true): JSONObject {
        val canDirect = directCall && hasPerm(ctx, Manifest.permission.CALL_PHONE)
        val action = if (canDirect) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.parse("tel:${Uri.encode(number)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        return JSONObject().put("ok", true).put("mode", if (canDirect) "call" else "dial").put("number", number)
    }

    // ==================================================================
    // SHELL COMMANDS & BACKGROUND JOBS (WITH BLAST-RADIUS GUARD)
    // ==================================================================
    data class BgJob(
        val id: String,
        val cmd: String,
        val process: Process,
        val logFile: File,
        val startedAt: Long
    )

    private val jobs = ConcurrentHashMap<String, BgJob>()
    private val GUARD_CRITICAL = listOf("cloudflared", "libcloudflared", "connectwin")

    fun checkBlastGuard(cmd: String): String? {
        val low = cmd.lowercase()
        if (!Regex("\\b(pkill|killall|kill|am\\s+force-stop)\\b").containsMatchIn(low)) return null
        val hits = GUARD_CRITICAL.filter { low.contains(it) }
        if (hits.isEmpty()) return null
        return "REFUSED: command would kill critical Connect-win process (${hits.joinToString()}). Pass force=true to override."
    }

    fun runCommand(ctx: Context, command: String, cwd: String? = null, timeoutSec: Long = 35L): JSONObject {
        val dir = resolvePath(ctx, cwd)
        val workDir = if (dir.exists() && dir.isDirectory) dir else ctx.filesDir
        val pb = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(workDir)
            .redirectErrorStream(true)
        val proc = pb.start()
        val finished = proc.waitFor(timeoutSec.coerceIn(1L, 120L), TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return JSONObject().put("ok", false).put("error", "Timed out after ${timeoutSec}s — use /jobs/start for long commands")
        }
        val out = proc.inputStream.bufferedReader().readText().take(25_000)
        val code = proc.exitValue()
        return JSONObject().apply {
            put("ok", code == 0)
            put("exit_code", code)
            put("result", out + "\n(exit code: $code)")
            // FIX (honesty): this shell runs as the app's own uid (u0_aXXX), NOT as
            // `shell`. Anything requiring shell identity (pm, am, cmd, settings, logcat)
            // fails with a SecurityException while still exiting 0, so agents saw
            // "ok": true and a success-shaped payload. Surface the identity and flag a
            // denied command so callers can stop trusting the exit code blindly.
            put("uid", android.os.Process.myUid())
            put("privileged", false)
            if (out.contains("SecurityException") || out.contains("Permission denial")) {
                put("ok", false)
                put("denied", true)
                put(
                    "hint",
                    "Denied: bridge shell runs as the app uid, not `shell`. " +
                        "Use the dedicated endpoints (/apps, /status, /files/*) instead of pm/am/cmd."
                )
            }
        }
    }

    fun startJob(ctx: Context, command: String, cwd: String? = null): JSONObject {
        val dir = resolvePath(ctx, cwd)
        val workDir = if (dir.exists() && dir.isDirectory) dir else ctx.filesDir
        val jid = UUID.randomUUID().toString().replace("-", "").take(8)
        val jobsDir = File(ctx.cacheDir, "jobs").also { it.mkdirs() }
        val logFile = File(jobsDir, "$jid.log")
        val pb = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(workDir)
            .redirectErrorStream(true)
            .redirectOutput(logFile)
        val proc = pb.start()
        jobs[jid] = BgJob(jid, command, proc, logFile, System.currentTimeMillis())
        return JSONObject().apply {
            put("job_id", jid)
            put("hint", "poll GET /jobs/$jid?offset=0")
        }
    }

    fun listJobs(): JSONObject {
        val arr = JSONArray()
        jobs.values.sortedByDescending { it.startedAt }.forEach { j ->
            val alive = j.process.isAlive
            arr.put(JSONObject().apply {
                put("job_id", j.id)
                put("cmd", j.cmd)
                put("running", alive)
                put("exit_code", if (alive) JSONObject.NULL else j.process.exitValue())
            })
        }
        return JSONObject().put("jobs", arr)
    }

    fun pollJob(jid: String, offset: Long = 0L): JSONObject {
        val j = jobs[jid] ?: return JSONObject().put("ok", false).put("error", "unknown_job_id")
        val size = if (j.logFile.exists()) j.logFile.length() else 0L
        var chunk = ""
        if (size > offset) {
            RandomAccessFile(j.logFile, "r").use { raf ->
                raf.seek(offset.coerceAtLeast(0L))
                val toRead = (size - offset).coerceAtMost(64_000L).toInt()
                val buf = ByteArray(toRead)
                val n = raf.read(buf)
                if (n > 0) chunk = String(buf, 0, n, Charsets.UTF_8)
            }
        }
        val alive = j.process.isAlive
        return JSONObject().apply {
            put("job_id", j.id)
            put("running", alive)
            put("exit_code", if (alive) JSONObject.NULL else j.process.exitValue())
            put("output_since_offset", chunk)
            put("next_offset", size)
        }
    }

    fun killJob(jid: String): JSONObject {
        val j = jobs[jid] ?: return JSONObject().put("ok", false).put("error", "unknown_job_id")
        j.process.destroyForcibly()
        return JSONObject().put("killed", jid)
    }
}

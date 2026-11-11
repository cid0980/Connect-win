package com.cid0980.connectwin

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Best effort only: watches Chrome's *visible* Arena agent composer, not network traffic. */
class ArenaCompletionMonitor(private val service: BridgeAccessibilityService) {
    companion object {
        private const val PREFS = "arena_completion"
        private const val KEY_ENABLED = "enabled"
        private const val CHANNEL = "arena_completion_v1"
        fun enabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }
        @Volatile private var lastDirectNoticeMs = 0L
        fun notify(context: Context, test: Boolean = false, direct: Boolean = false): Boolean {
            if (!test && !enabled(context)) return false
            if (!test && !direct && lastDirectNoticeMs != 0L && SystemClock.elapsedRealtime() - lastDirectNoticeMs < 30_000L) return false
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!manager.areNotificationsEnabled()) return false
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Arena completion", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Sound when a visible Arena agent response appears to finish"
            })
            if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(if (test) "Connect-win notification test" else "Arena response finished")
                .setContentText(if (test) "If you hear a sound, notifications are working." else if (direct) "The agent reports its work is complete. Open Arena for details." else "The response may have finished or stopped. Return to Arena to check.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            return runCatching {
                manager.notify(1704, notification)
                if (direct) lastDirectNoticeMs = SystemClock.elapsedRealtime()
                true
            }.getOrDefault(false)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val transition = ArenaCompletionState()
    private val poll = object : Runnable {
        override fun run() {
            try { inspect() } catch (_: Exception) { reset() }
            handler.postDelayed(this, 1200)
        }
    }
    fun start() { handler.removeCallbacks(poll); handler.post(poll) }
    fun stop() { handler.removeCallbacks(poll); reset() }
    private fun reset() { transition.reset() }

    private fun inspect() {
        if (!enabled(service)) { reset(); return }
        // Restrict to the active Chrome application window; do not monitor other apps or background tabs.
        val root = service.rootInActiveWindow ?: run { reset(); return }
        if (root.packageName?.toString() != "com.android.chrome") { reset(); return }
        var url: String? = null
        var generating = false
        var composer = false
        var visited = 0
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 60 || visited++ > 3000) return
            val id = node.viewIdResourceName ?: ""
            if (id == "com.android.chrome:id/url_bar") url = node.text?.toString()
            if (node.className?.toString()?.endsWith("Button") == true) {
                when (node.contentDescription?.toString()) {
                    "Stop generating" -> generating = true
                    "Add files and connections" -> composer = true
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        val raw = url ?: run { reset(); return }
        val uri = runCatching { Uri.parse(if (raw.contains("://")) raw else "https://$raw") }.getOrNull()
        if (uri?.host?.lowercase() != "arena.ai" || uri.path?.startsWith("/agent/") != true || !composer) { reset(); return }
        val page = uri.buildUpon().clearQuery().fragment(null).build().toString()
        if (transition.sample(page, generating, SystemClock.elapsedRealtime())) notify(service)
    }
}

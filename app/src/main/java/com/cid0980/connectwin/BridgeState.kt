package com.cid0980.connectwin

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

data class BridgeSnapshot(
    val running: Boolean = false,
    val httpListening: Boolean = false,
    val tunnelStatus: String = "Stopped",
    val tunnelUrl: String? = null,
    val lastError: String? = null,
    val startedAtMs: Long = 0L,
    val requestCount: Long = 0L
)

object BridgeState {
    const val VERSION = "1.0.0"
    const val HTTP_PORT = 8765
    private const val PREFS_NAME = "connect_win_prefs"
    private const val KEY_TOKEN = "bridge_token"
    private const val KEY_NAMED_TUNNEL_TOKEN = "named_tunnel_token"
    private const val KEY_CUSTOM_HOSTNAME = "custom_hostname"
    private const val KEY_TUNNEL_PROTOCOL = "tunnel_protocol"
    private const val KEY_AUTO_BOOT = "auto_start_boot"
    private const val KEY_WAS_RUNNING = "was_running"

    private val _snapshot = MutableStateFlow(BridgeSnapshot())
    val snapshot: StateFlow<BridgeSnapshot> = _snapshot

    private val _auditLines = MutableStateFlow<List<String>>(emptyList())
    val auditLines: StateFlow<List<String>> = _auditLines

    private val auditRing = ConcurrentLinkedDeque<String>()
    private var auditFile: File? = null

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        val dir = File(ctx.applicationContext.filesDir, "bridge")
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, "audit.log")
        auditFile = f
        if (auditRing.isEmpty() && f.exists()) {
            runCatching {
                f.readLines().takeLast(80).forEach { auditRing.addLast(it) }
                _auditLines.value = auditRing.toList()
            }
        }
        getOrCreateToken(ctx)
    }

    fun getOrCreateToken(ctx: Context): String {
        val p = prefs(ctx)
        val existing = p.getString(KEY_TOKEN, null)
        if (!existing.isNullOrBlank()) return existing
        return rotateToken(ctx)
    }

    fun rotateToken(ctx: Context): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        val tok = bytes.joinToString("") { "%02x".format(it) }
        prefs(ctx).edit().putString(KEY_TOKEN, tok).apply()
        log("TOKEN rotated")
        return tok
    }

    fun verifyToken(ctx: Context, candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        val expected = getOrCreateToken(ctx)
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            candidate.trim().toByteArray(Charsets.UTF_8)
        )
    }

    fun getNamedTunnelToken(ctx: Context): String =
        prefs(ctx).getString(KEY_NAMED_TUNNEL_TOKEN, "")?.trim() ?: ""

    fun setNamedTunnelToken(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY_NAMED_TUNNEL_TOKEN, value.trim()).apply()
    }

    fun getCustomHostname(ctx: Context): String =
        prefs(ctx).getString(KEY_CUSTOM_HOSTNAME, "")?.trim() ?: ""

    fun setCustomHostname(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY_CUSTOM_HOSTNAME, value.trim()).apply()
    }

    fun getTunnelProtocol(ctx: Context): String =
        prefs(ctx).getString(KEY_TUNNEL_PROTOCOL, "http2") ?: "http2"

    fun setTunnelProtocol(ctx: Context, proto: String) {
        prefs(ctx).edit().putString(KEY_TUNNEL_PROTOCOL, proto).apply()
    }

    fun isAutoBoot(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTO_BOOT, false)

    fun setAutoBoot(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_BOOT, enabled).apply()
    }

    fun wasRunning(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_WAS_RUNNING, false)

    fun setWasRunning(ctx: Context, running: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_WAS_RUNNING, running).apply()
    }

    fun update(transform: (BridgeSnapshot) -> BridgeSnapshot) {
        _snapshot.value = transform(_snapshot.value)
    }

    fun incrementRequests() {
        _snapshot.value = _snapshot.value.copy(requestCount = _snapshot.value.requestCount + 1)
    }

    fun log(entry: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "$ts  $entry"
        auditRing.addLast(line)
        while (auditRing.size > 200) {
            auditRing.pollFirst()
        }
        _auditLines.value = auditRing.toList()
        runCatching {
            val f = auditFile ?: return@runCatching
            if (f.exists() && f.length() > 512_000) {
                val tail = f.readLines().takeLast(200).joinToString("\n") + "\n"
                f.writeText(tail)
            }
            f.appendText(line + "\n")
        }
    }

    fun recentAudit(limit: Int = 60): List<String> =
        auditRing.toList().takeLast(limit.coerceIn(1, 200))
}

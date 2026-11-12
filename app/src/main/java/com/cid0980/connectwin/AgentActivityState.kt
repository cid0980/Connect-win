package com.cid0980.connectwin

/**
 * Honest, expiring agent-activity state for the persistent bridge notification.
 *
 * The bridge cannot know whether a remote AI is "thinking"; it only knows what the
 * agent explicitly reports and what authenticated requests arrive. A `working`
 * heartbeat therefore expires, so a crashed/stopped agent cannot leave a permanent
 * "working" notification behind.
 */
class AgentActivityTracker(private val defaultTtlMs: Long = 90_000L) {

    data class Snapshot(
        val state: String,
        val message: String,
        val explicitState: String,
        val lastRequestAgeMs: Long?,
        val lastActionAgeMs: Long?,
        val heartbeatAgeMs: Long?,
        val expiresInSeconds: Long?,
        val requestCount: Long,
        val actionCount: Long
    )

    private var explicitState: String = "idle"
    private var message: String = ""
    private var heartbeatAtMs: Long = -1L
    private var ttlMs: Long = defaultTtlMs
    private var lastRequestAtMs: Long = -1L
    private var lastActionAtMs: Long = -1L
    private var requestCount: Long = 0L
    private var actionCount: Long = 0L

    @Synchronized
    fun setAgent(state: String, message: String, nowMs: Long, ttlMs: Long? = null): Snapshot {
        val normalized = state.trim().lowercase()
        require(normalized in setOf("working", "paused", "idle", "done")) {
            "state must be working, paused, idle or done"
        }
        explicitState = normalized
        this.message = sanitize(message)
        heartbeatAtMs = nowMs
        this.ttlMs = (ttlMs ?: defaultTtlMs).coerceIn(5_000L, 300_000L)
        return snapshot(nowMs)
    }

    @Synchronized
    fun observeRequest(path: String, method: String, nowMs: Long) {
        requestCount++
        lastRequestAtMs = nowMs
        if (isAction(path, method)) {
            actionCount++
            lastActionAtMs = nowMs
        }
    }

    @Synchronized
    fun snapshot(nowMs: Long): Snapshot {
        val heartbeatAge = if (heartbeatAtMs < 0L) null else (nowMs - heartbeatAtMs).coerceAtLeast(0L)
        val expired = explicitState == "working" && heartbeatAge != null && heartbeatAge > ttlMs
        val effective = if (expired) "idle" else explicitState
        val effectiveMessage = when {
            expired -> "Agent heartbeat expired — bridge is still connected"
            message.isNotBlank() -> message
            effective == "working" -> "Working on your request"
            effective == "paused" -> "Paused by agent"
            effective == "done" -> "Finished"
            else -> "Waiting for the next request"
        }
        val expiresIn = if (effective == "working" && heartbeatAge != null) {
            ((ttlMs - heartbeatAge).coerceAtLeast(0L) + 999L) / 1000L
        } else null
        return Snapshot(
            state = effective,
            message = effectiveMessage,
            explicitState = explicitState,
            lastRequestAgeMs = age(lastRequestAtMs, nowMs),
            lastActionAgeMs = age(lastActionAtMs, nowMs),
            heartbeatAgeMs = heartbeatAge,
            expiresInSeconds = expiresIn,
            requestCount = requestCount,
            actionCount = actionCount
        )
    }

    private fun age(atMs: Long, nowMs: Long): Long? =
        if (atMs < 0L) null else (nowMs - atMs).coerceAtLeast(0L)

    private fun sanitize(raw: String): String =
        raw.filter { it >= ' ' || it == '\t' || it == '\n' || it == '\r' }.trim().take(180)

    private fun isAction(path: String, method: String): Boolean {
        val m = method.uppercase()
        if (m != "POST" && m != "PUT" && m != "PATCH" && m != "DELETE") return false
        if (path == "/agent/status" || path == "/shot" || path == "/gui/screenshot") return false
        return path.startsWith("/gui/") ||
            path.startsWith("/ui/click") || path.startsWith("/ui/set-text") ||
            path.startsWith("/apps/launch") || path.startsWith("/apps/intent") || path.startsWith("/apps/install") ||
            path.startsWith("/phone/") || path.startsWith("/files/") || path.startsWith("/reflex/")
    }
}

object AgentActivity {
    private val tracker = AgentActivityTracker()

    fun setAgent(state: String, message: String, nowMs: Long, ttlMs: Long? = null): AgentActivityTracker.Snapshot =
        tracker.setAgent(state, message, nowMs, ttlMs)

    fun observeRequest(path: String, method: String, nowMs: Long) =
        tracker.observeRequest(path, method, nowMs)

    fun snapshot(nowMs: Long): AgentActivityTracker.Snapshot = tracker.snapshot(nowMs)
}

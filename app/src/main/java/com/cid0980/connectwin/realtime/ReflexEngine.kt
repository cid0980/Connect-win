package com.cid0980.connectwin.realtime

import android.graphics.Bitmap
import android.graphics.Color
import com.cid0980.connectwin.BridgeAccessibilityService
import com.cid0980.connectwin.BridgeState
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * On-device stimulus -> response rules. Evaluated against the latest captured frame
 * (see [ScreenCaptureService]) so a reaction happens in the same process at frame
 * rate, with no AI or network round-trip. This is what enables "auto-fight" /
 * auto-task loops: the agent configures rules once, then the phone reacts locally.
 *
 * Coordinates are normalized (0..1) by default so rules survive resolution changes.
 * Conditions read frame pixels; actions are injected in physical screen pixels.
 */
object ReflexEngine {

    private const val MAX_RULES = 64

    private class Rule(
        val id: String,
        val condition: JSONObject,
        val action: JSONObject,
        val cooldownMs: Long,
        val normalized: Boolean,
        val maxFires: Long,
        // Genshin-class input is hold-and-release, not tap. Sumeru puzzles, glider
        // deployment and charged attacks are all "press while condition holds, release
        // when it stops". A tap-only engine cannot express that, and a down-action with
        // no matching release strands a pointer: the character keeps running into a wall
        // with no way to know why. So a rule may declare on_release, and every forced
        // release is counted so a stuck pointer is visible rather than mysterious.
        val onRelease: JSONObject?,
        // Hard ceiling on how long a press may be held before the watchdog releases it.
        // Default 0 = no watchdog, but any rule that presses should set it: an agent that
        // disconnects mid-hold must not leave the phone with a finger stuck down.
        val maxHoldMs: Long,
        @Volatile var primedAtMs: Long = 0L,
        @Volatile var enabled: Boolean,
        @Volatile var lastFiredMs: Long = 0L,
        val fires: AtomicLong = AtomicLong(0),
        // Coalescing: while one action for this rule is still queued/running we do
        // not queue another, so a noisy rule cannot flood the executor and add lag.
        val inFlight: AtomicBoolean = AtomicBoolean(false)
    )

    private val rules = LinkedHashMap<String, Rule>()
    private val lock = Any()
    @Volatile var enabled: Boolean = false; private set
    // Bounded queue + abort policy = hard backpressure instead of unbounded lag.
    // Per-rule coalescing (inFlight) keeps queued tasks <= number of rules, so the
    // queue rarely fills; if it ever does, execute() throws and we clear inFlight.
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(64),
        { r -> Thread(r, "cw-reflex").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val evalCount = AtomicLong(0)
    private val fireCount = AtomicLong(0)
    private val droppedCount = AtomicLong(0)
    private val releaseCount = AtomicLong(0)
    private val watchdogReleaseCount = AtomicLong(0)
    private val overlapsAvoided = AtomicLong(0)

    // ------------------------------------------------------------------
    // Rule management
    // ------------------------------------------------------------------
    fun addRule(json: JSONObject): JSONObject {
        val id = json.optString("id", "").ifEmpty { "r" + System.currentTimeMillis().toString(36) }
        val condition = json.optJSONObject("condition")
            ?: return JSONObject().put("ok", false).put("error", "missing condition")
        val action = json.optJSONObject("action")
            ?: return JSONObject().put("ok", false).put("error", "missing action")
        synchronized(lock) {
            if (rules.size >= MAX_RULES && !rules.containsKey(id)) {
                return JSONObject().put("ok", false).put("error", "too many rules (max $MAX_RULES)")
            }
            rules[id] = Rule(
                id = id,
                condition = condition,
                action = action,
                cooldownMs = json.optLong("cooldown_ms", 250L).coerceIn(0L, 60_000L),
                normalized = json.optBoolean("normalized", true),
                maxFires = json.optLong("max_fires", 0L),
                onRelease = json.optJSONObject("on_release"),
                maxHoldMs = json.optLong("max_hold_ms", 0L).coerceIn(0L, 120_000L),
                enabled = json.optBoolean("enabled", true)
            )
        }
        BridgeState.log("Reflex rule added: $id")
        return JSONObject().put("ok", true).put("id", id)
    }

    fun removeRule(id: String): Boolean = synchronized(lock) { rules.remove(id) != null }

    fun setRuleEnabled(id: String, on: Boolean): Boolean = synchronized(lock) {
        rules[id]?.let { it.enabled = on; true } ?: false
    }

    fun clear() = synchronized(lock) { rules.clear() }

    fun setEnabled(on: Boolean) {
        enabled = on
        BridgeState.log("Reflex engine ${if (on) "enabled" else "disabled"}")
    }

    fun listJson(): JSONObject {
        val arr = JSONArray()
        synchronized(lock) {
            for (r in rules.values) {
                arr.put(JSONObject().apply {
                    put("id", r.id)
                    put("enabled", r.enabled)
                    put("condition", r.condition)
                    put("action", r.action)
                    put("cooldown_ms", r.cooldownMs)
                    put("normalized", r.normalized)
                    put("fires", r.fires.get())
                    put("armed", r.primedAtMs > 0L)
                    put("held_ms", if (r.primedAtMs > 0L) System.currentTimeMillis() - r.primedAtMs else 0L)
                    put("has_on_release", r.onRelease != null)
                    put("max_hold_ms", r.maxHoldMs)
                })
            }
        }
        return JSONObject().apply {
            put("ok", true)
            put("enabled", enabled)
            put("evaluations", evalCount.get())
            put("fires", fireCount.get())
            put("coalesced_drops", droppedCount.get())
            // Safety telemetry. A stuck pointer is one of the few failures in this system
            // that can leave the phone in a state the agent cannot see from a screenshot,
            // so the counts are always visible rather than opt-in.
            put("releases", releaseCount.get())
            put("watchdog_releases", watchdogReleaseCount.get())
            put("armed", synchronized(lock) { rules.values.count { it.primedAtMs > 0L } })
            put("rules", arr)
        }
    }

    // ------------------------------------------------------------------
    // Per-frame evaluation (called on the capture thread; keep it cheap)
    // ------------------------------------------------------------------
    fun onFrame() {
        if (!enabled) return
        val snapshot = synchronized(lock) { rules.values.filter { it.enabled } }
        if (snapshot.isEmpty()) return
        val store = ScreenCaptureService.frameStore
        val now = System.currentTimeMillis()
        for (r in snapshot) {
            if (now - r.lastFiredMs < r.cooldownMs) continue
            if (r.maxFires > 0 && r.fires.get() >= r.maxFires) continue
            val matched = try {
                store.use { bmp -> evalCondition(bmp, r) } ?: false
            } catch (t: Throwable) {
                false
            }
            evalCount.incrementAndGet()

            // Watchdog: a press that outlives max_hold_ms is force-released even if the
            // release condition never becomes true (scene change, disconnect, a rule that
            // was gated on a pixel that scrolled away). Without this, "press while boss is
            // charging" can leave the screen held down forever when the boss dies.
            if (r.maxHoldMs > 0 && r.primedAtMs > 0 && now - r.primedAtMs >= r.maxHoldMs) {
                enqueue(r, releaseAllFor(r), isWatchdog = true)
                r.primedAtMs = 0L
                continue
            }

            if (matched) {
                // A rule with on_release latches as armed until its condition stops
                // matching. While armed we test the RELEASE condition on every frame,
                // which is what turns a single tap into hold-and-release.
                if (r.onRelease != null) {
                    if (r.primedAtMs == 0L) {
                        if (!r.inFlight.compareAndSet(false, true)) {
                            droppedCount.incrementAndGet()
                            continue
                        }
                        r.primedAtMs = now
                        r.lastFiredMs = now
                        r.fires.incrementAndGet()
                        fireCount.incrementAndGet()
                        enqueueNow(r, r.action) { r.inFlight.set(false) }
                    }
                    continue
                }
                // Coalesce: skip if a previous action for this rule is still pending.
                if (!r.inFlight.compareAndSet(false, true)) {
                    droppedCount.incrementAndGet()
                    continue
                }
                r.lastFiredMs = now
                r.fires.incrementAndGet()
                fireCount.incrementAndGet()
                enqueueNow(r, r.action) { r.inFlight.set(false) }
                continue
            }

            // Not matching. If this rule is armed (a press is outstanding) the stimulus
            // has ended, so release. Checking the explicit on_release condition too lets
            // a rule release on a *different* pixel than the one it pressed on.
            if (r.primedAtMs > 0L) {
                val releaseNow = try {
                    val rel = r.onRelease
                    if (rel == null) true
                    else {
                        // Only consult on_release if the rule author asked for it; by
                        // default "the press condition stopped holding" means release.
                        evalConditionObj(bmpForRelease(), r, rel)
                    }
                } catch (t: Throwable) {
                    true
                }
                if (releaseNow) {
                    enqueue(r, releaseAllFor(r), isWatchdog = false)
                    r.primedAtMs = 0L
                }
            }
        }
    }

    /** Snapshot of the frame for release evaluation; keeps the lock short. */
    private fun bmpForRelease(): Bitmap? = ScreenCaptureService.frameStore.copy()

    private fun enqueue(r: Rule, action: JSONObject, isWatchdog: Boolean) {
        val releaseAll = action.optBoolean("_release_all", false)
        try {
            executor.execute {
                try {
                    if (releaseAll) releaseAllPointers() else runAction(action, r.normalized)
                    if (isWatchdog) watchdogReleaseCount.incrementAndGet()
                    else releaseCount.incrementAndGet()
                } catch (t: Throwable) {
                    BridgeState.log("Reflex release error: ${t.javaClass.simpleName}")
                } finally {
                    r.inFlight.set(false)
                }
            }
        } catch (t: Throwable) {
            r.inFlight.set(false)
            droppedCount.incrementAndGet()
            // Executor rejected the release: do not silently lose it, because a lost
            // release leaves a finger down. Try once on the calling thread.
            runCatching {
                if (releaseAll) releaseAllPointers() else runAction(action, r.normalized)
            }
        }
    }

    private fun enqueueNow(r: Rule, action: JSONObject, onDone: () -> Unit) {
        try {
            executor.execute {
                try {
                    runAction(action, r.normalized)
                } finally {
                    onDone()
                }
            }
        } catch (t: Throwable) {
            onDone()
            droppedCount.incrementAndGet()
        }
    }

    private fun releaseAllFor(r: Rule): JSONObject {
        val rel = r.onRelease
        if (rel != null) return rel
        // Default release: lift every pointer this engine may have pressed.
        return JSONObject().put("type", "release_all").put("_release_all", true)
    }

    /** Lift all pointers through the accessibility service. */
    fun releaseAllPointers() {
        val svc = BridgeAccessibilityService.instance ?: return
        try {
            svc.touchCancelAll()
        } catch (t: Throwable) {
            BridgeState.log("releaseAllPointers failed: ${t.javaClass.simpleName}")
        }
    }

    private fun evalConditionObj(bmp: Bitmap?, r: Rule, cond: JSONObject): Boolean {
        if (bmp == null) return true
        val fake = Rule(
            id = r.id, condition = cond, action = r.action, cooldownMs = 0L,
            normalized = r.normalized, maxFires = 0L, onRelease = null,
            maxHoldMs = 0L, enabled = true
        )
        return evalCondition(bmp, fake)
    }

    private fun evalCondition(bmp: Bitmap, r: Rule): Boolean {
        return when (r.condition.optString("type", "pixel_color")) {
            "pixel_color" -> evalPixel(bmp, r)
            "region_color" -> evalRegion(bmp, r)
            else -> false
        }
    }

    private fun evalPixel(bmp: Bitmap, r: Rule): Boolean {
        val c = r.condition
        val target = parseColor(c.opt("color")) ?: return false
        val tol = c.optInt("tolerance", 24).coerceIn(0, 255)
        val px = coord(c.optDouble("x", 0.5), r.normalized, bmp.width)
        val py = coord(c.optDouble("y", 0.5), r.normalized, bmp.height)
        val pixel = bmp.getPixel(px, py)
        return colorNear(pixel, target, tol)
    }

    private fun evalRegion(bmp: Bitmap, r: Rule): Boolean {
        val c = r.condition
        val target = parseColor(c.opt("color")) ?: return false
        val tol = c.optInt("tolerance", 24).coerceIn(0, 255)
        val minRatio = c.optDouble("min_ratio", 0.5)
        val region = c.optJSONArray("region") ?: return false
        if (region.length() != 4) return false
        val x0 = coord(region.optDouble(0), r.normalized, bmp.width)
        val y0 = coord(region.optDouble(1), r.normalized, bmp.height)
        val x1 = coord(region.optDouble(0) + region.optDouble(2), r.normalized, bmp.width)
        val y1 = coord(region.optDouble(1) + region.optDouble(3), r.normalized, bmp.height)
        val steps = 10
        var hits = 0; var total = 0
        for (i in 0..steps) {
            for (j in 0..steps) {
                val x = (x0 + (x1 - x0) * i / steps).coerceIn(0, bmp.width - 1)
                val y = (y0 + (y1 - y0) * j / steps).coerceIn(0, bmp.height - 1)
                if (colorNear(bmp.getPixel(x, y), target, tol)) hits++
                total++
            }
        }
        return total > 0 && hits.toDouble() / total >= minRatio
    }

    // ------------------------------------------------------------------
    // Action execution (off the capture thread)
    // ------------------------------------------------------------------
    private fun runAction(action: JSONObject, normalized: Boolean) {
        val svc = BridgeAccessibilityService.instance ?: return
        try {
            val (sw, sh) = svc.screenSize()
            when (action.optString("type", "tap")) {
                "release_all" -> svc.touchCancelAll()
                "release" -> svc.touchUp(action.optInt("pointer", 0))
                "tap" -> {
                    val x = coord(action.optDouble("x", 0.5), normalized, sw)
                    val y = coord(action.optDouble("y", 0.5), normalized, sh)
                    svc.tap(x, y, action.optLong("duration_ms", 55L))
                }
                "key" -> svc.triggerGlobalAction(action.optString("action", "back"))
                "swipe" -> {
                    val x1 = coord(action.optDouble("x1", 0.5), normalized, sw)
                    val y1 = coord(action.optDouble("y1", 0.7), normalized, sh)
                    val x2 = coord(action.optDouble("x2", 0.5), normalized, sw)
                    val y2 = coord(action.optDouble("y2", 0.3), normalized, sh)
                    svc.swipe(x1, y1, x2, y2, action.optLong("duration_ms", 250L))
                }
                "touch" -> {
                    val p = action.optInt("pointer", 0)
                    val x = coord(action.optDouble("x", 0.5), normalized, sw)
                    val y = coord(action.optDouble("y", 0.5), normalized, sh)
                    when (action.optString("op", "tap")) {
                        "down" -> svc.touchDown(p, x, y)
                        "move" -> svc.touchMove(p, x, y)
                        "up" -> svc.touchUp(p)
                        else -> svc.touchPress(p, x, y, action.optLong("hold_ms", 80L))
                    }
                }
            }
        } catch (t: Throwable) {
            BridgeState.log("Reflex action error: ${t.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    private fun coord(v: Double, normalized: Boolean, extent: Int): Int {
        val px = if (normalized) v * extent else v
        return px.toInt().coerceIn(0, extent - 1)
    }

    private fun parseColor(v: Any?): Int? = when (v) {
        is String -> runCatching { Color.parseColor(if (v.startsWith("#")) v else "#$v") }.getOrNull()
        is JSONArray -> if (v.length() >= 3) Color.rgb(v.optInt(0), v.optInt(1), v.optInt(2)) else null
        is Int -> v
        else -> null
    }

    private fun colorNear(pixel: Int, target: Int, tol: Int): Boolean {
        val dr = kotlin.math.abs(Color.red(pixel) - Color.red(target))
        val dg = kotlin.math.abs(Color.green(pixel) - Color.green(target))
        val db = kotlin.math.abs(Color.blue(pixel) - Color.blue(target))
        return dr <= tol && dg <= tol && db <= tol
    }
}

package com.cid0980.connectwin.realtime

import android.graphics.Bitmap
import android.graphics.Color
import com.cid0980.connectwin.BridgeAccessibilityService
import com.cid0980.connectwin.BridgeState
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
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
        @Volatile var enabled: Boolean,
        @Volatile var lastFiredMs: Long = 0L,
        val fires: AtomicLong = AtomicLong(0)
    )

    private val rules = LinkedHashMap<String, Rule>()
    private val lock = Any()
    @Volatile var enabled: Boolean = false; private set
    private val executor = Executors.newSingleThreadExecutor()
    private val evalCount = AtomicLong(0)
    private val fireCount = AtomicLong(0)

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
                })
            }
        }
        return JSONObject().apply {
            put("ok", true)
            put("enabled", enabled)
            put("evaluations", evalCount.get())
            put("fires", fireCount.get())
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
            if (matched) {
                r.lastFiredMs = now
                r.fires.incrementAndGet()
                fireCount.incrementAndGet()
                val action = r.action
                executor.execute { runAction(action, r.normalized) }
            }
        }
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

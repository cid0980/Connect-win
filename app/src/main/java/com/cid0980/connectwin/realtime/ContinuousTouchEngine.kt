package com.cid0980.connectwin.realtime

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.cid0980.connectwin.BridgeState
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Human-like continuous multi-touch.
 *
 * Instead of discrete tap/tap/tap, each pointer is a *held* finger whose stroke is
 * extended every tick with [GestureDescription.StrokeDescription.continueStroke].
 * Move commands only update a target; a single background loop coalesces them and
 * keeps all active fingers down in one synchronized gesture. This gives smooth
 * drag / aim / joystick control at the display's gesture cadence rather than a
 * sequence of independent taps.
 *
 * All state is confined to one HandlerThread; public methods post to it.
 */
class ContinuousTouchEngine(private val service: AccessibilityService) {

    companion object {
        const val MAX_POINTERS = 5
        private const val DEFAULT_TICK_MS = 16L
        private const val MIN_TICK_MS = 8L
        private const val MAX_TICK_MS = 60L
    }

    private class Pointer(
        var x: Float,
        var y: Float,
        var tx: Float,
        var ty: Float,
        var releasing: Boolean = false,
        var prev: GestureDescription.StrokeDescription? = null,
        var next: GestureDescription.StrokeDescription? = null,
        val downAt: Long = SystemClock.uptimeMillis(),
        // Monotonic per-pointer generation so a delayed "press" release cannot
        // lift a newer finger that reused the same pointer id.
        var generation: Long = 0L
    )

    private val thread = HandlerThread("cw-touch").apply { start() }
    private val handler = Handler(thread.looper)
    private val pointers = LinkedHashMap<Int, Pointer>()

    @Volatile private var tickMs = DEFAULT_TICK_MS
    @Volatile private var running = false
    private var dispatching = false
    private var lastCompletedAt = 0L
    private val tickCount = AtomicLong(0)
    private val moveCount = AtomicLong(0)
    private var generationCounter = 0L

    // Screen size is queried from WindowManager; cache it so a 60 Hz move stream
    // does not make a binder call per event. Refreshed on down and periodically.
    private var cachedW = 0
    private var cachedH = 0
    private var screenRefreshedAt = 0L

    // ------------------------------------------------------------------
    // Public API (thread-safe: posts onto the engine thread)
    // ------------------------------------------------------------------
    fun down(pointer: Int, x: Int, y: Int) {
        handler.post {
            if (pointer !in 0 until MAX_POINTERS) return@post
            if (pointers.size >= MAX_POINTERS && !pointers.containsKey(pointer)) return@post
            refreshScreen(force = true)
            val (sx, sy) = clamp(x, y)
            val p = Pointer(sx, sy, sx, sy)
            p.generation = ++generationCounter
            pointers[pointer] = p
            ensureLoop()
        }
    }

    fun move(pointer: Int, x: Int, y: Int) {
        handler.post {
            val p = pointers[pointer] ?: return@post
            val (sx, sy) = clamp(x, y)
            p.tx = sx; p.ty = sy
            moveCount.incrementAndGet()
            ensureLoop()
        }
    }

    fun up(pointer: Int) {
        handler.post {
            val p = pointers[pointer] ?: return@post
            p.releasing = true
            ensureLoop()
        }
    }

    /** Press, hold for [holdMs], then release — a human-like tap/press. */
    fun press(pointer: Int, x: Int, y: Int, holdMs: Long) {
        handler.post {
            if (pointer !in 0 until MAX_POINTERS) return@post
            if (pointers.size >= MAX_POINTERS && !pointers.containsKey(pointer)) return@post
            refreshScreen(force = true)
            val (sx, sy) = clamp(x, y)
            val p = Pointer(sx, sy, sx, sy)
            p.generation = ++generationCounter
            pointers[pointer] = p
            val gen = p.generation
            ensureLoop()
            handler.postDelayed({
                // Only lift if this is still the same finger we pressed.
                val cur = pointers[pointer]
                if (cur != null && cur.generation == gen) {
                    cur.releasing = true
                    ensureLoop()
                }
            }, holdMs.coerceIn(20L, 10_000L))
        }
    }

    fun cancelAll() {
        handler.post {
            for (p in pointers.values) p.releasing = true
            ensureLoop()
        }
    }

    fun setTickMs(ms: Long) {
        tickMs = ms.coerceIn(MIN_TICK_MS, MAX_TICK_MS)
    }

    fun stateJson(): JSONObject {
        val arr = JSONArray()
        // Snapshot on the engine thread for consistency.
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post {
            for ((id, p) in pointers) {
                arr.put(JSONObject().apply {
                    put("pointer", id)
                    put("x", p.x.toInt()); put("y", p.y.toInt())
                    put("tx", p.tx.toInt()); put("ty", p.ty.toInt())
                    put("releasing", p.releasing)
                    put("held_ms", SystemClock.uptimeMillis() - p.downAt)
                })
            }
            latch.countDown()
        }
        latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        return JSONObject().apply {
            put("ok", true)
            put("running", running)
            put("tick_ms", tickMs)
            put("ticks", tickCount.get())
            put("moves", moveCount.get())
            put("pointers", arr)
        }
    }

    fun shutdown() {
        handler.post {
            pointers.clear()
            running = false
        }
        thread.quitSafely()
    }

    // ------------------------------------------------------------------
    // Engine loop
    // ------------------------------------------------------------------
    private fun refreshScreen(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && cachedW > 0 && now - screenRefreshedAt < 1000L) return
        val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as android.view.WindowManager
        val p = android.graphics.Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(p)
        cachedW = p.x; cachedH = p.y
        screenRefreshedAt = now
    }

    private fun clamp(x: Int, y: Int): Pair<Float, Float> {
        refreshScreen()
        val w = if (cachedW > 0) cachedW else 1080
        val h = if (cachedH > 0) cachedH else 2400
        return Pair(
            x.toFloat().coerceIn(0f, (w - 1).toFloat()),
            y.toFloat().coerceIn(0f, (h - 1).toFloat())
        )
    }

    private fun ensureLoop() {
        if (pointers.isEmpty()) { running = false; return }
        if (!running) {
            running = true
            dispatchTick()
        }
    }

    private fun dispatchTick() {
        if (dispatching) return
        if (pointers.isEmpty()) { running = false; return }

        val dur = tickMs
        val builder = GestureDescription.Builder()
        var any = false
        for (p in pointers.values) {
            val path = Path().apply {
                moveTo(p.x, p.y)
                // A tiny path even when stationary keeps the finger "down".
                lineTo(p.tx, p.ty)
            }
            val willContinue = !p.releasing
            val stroke = try {
                val prev = p.prev
                if (prev == null) {
                    GestureDescription.StrokeDescription(path, 0L, dur, willContinue)
                } else {
                    prev.continueStroke(path, 0L, dur, willContinue)
                }
            } catch (t: Throwable) {
                // If continuation is rejected, fall back to a fresh stroke.
                GestureDescription.StrokeDescription(path, 0L, dur, willContinue)
            }
            p.next = stroke
            builder.addStroke(stroke)
            any = true
        }
        if (!any) { running = false; return }

        dispatching = true
        val ok = service.dispatchGesture(builder.build(), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                handler.post { onTickDone(true) }
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                handler.post { onTickDone(false) }
            }
        }, handler)

        if (!ok) {
            dispatching = false
            // Could not dispatch (service busy). Retry shortly without advancing.
            handler.postDelayed({ dispatchTick() }, 8L)
        }
    }

    private fun onTickDone(completed: Boolean) {
        dispatching = false
        lastCompletedAt = SystemClock.uptimeMillis()
        tickCount.incrementAndGet()

        if (!completed) {
            // Gesture cancelled by the system/app: drop all fingers to avoid a stuck touch.
            pointers.clear()
            running = false
            return
        }

        val it = pointers.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val p = e.value
            if (p.releasing) {
                it.remove()
            } else {
                p.prev = p.next
                p.x = p.tx; p.y = p.ty
            }
        }

        if (pointers.isEmpty()) {
            running = false
            return
        }
        // Immediately continue to keep fingers held; cadence ~= tickMs + dispatch overhead.
        dispatchTick()
    }
}

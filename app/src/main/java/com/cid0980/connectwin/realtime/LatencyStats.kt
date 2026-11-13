package com.cid0980.connectwin.realtime

import android.os.SystemClock

/**
 * Latency instrumentation for the realtime stack.
 *
 * The problem this solves: until now nothing in the bridge could answer
 * "how long after a gesture does the screen change?". `frame_age_ms` tells you how
 * old the newest frame is, but not whether an input had any effect, and not where
 * the time went between capture and reaction. Every claim about whether the bridge
 * is fast enough for a real-time game was therefore a guess.
 *
 * Four timestamps are recorded per interaction:
 *
 *   capture    ImageReader delivered a frame we chose to publish
 *   publish    FrameStore.publish() returned (frame now visible to consumers)
 *   dispatch   dispatchGesture() accepted a stroke
 *   seen       first frame after dispatch whose region actually differs from the
 *              frame that was current when the gesture was dispatched
 *
 * From these we derive the three numbers that matter:
 *
 *   capture->publish   capture thread cost (bitmap conversion + lock)
 *   publish->dispatch  agent reaction cost
 *   dispatch->seen     INPUT LATENCY — the number that decides whether a
 *                      real-time game is playable at all
 *
 * All state is guarded by a single lock; the capture thread only pays a lock +
 * a couple of field writes per frame, and only when a measurement is in flight.
 */
object LatencyStats {

    /** How many samples to keep per series. Enough for a stable p95, cheap in memory. */
    private const val CAPACITY = 512

    /** Published-frame cadence samples (capture -> publish). */
    private val captureToPublish = Ring(CAPACITY)

    /** Input latency samples (dispatch -> seen), keyed by the dispatching pointer. */
    private val inputLatency = Ring(CAPACITY)

    /** Frames that arrived while an input was pending and were compared against the
     *  baseline. Lets us report "we looked N frames and saw nothing change". */
    private var comparedFrames = 0L
    private var timedOutInputs = 0L

    private val lock = Any()

    private var lastCaptureMs = 0L

    /** A pending input measurement: what the screen looked like when we dispatched. */
    private class Pending(
        val what: String,
        val dispatchMs: Long,
        val baseline: IntArray,
        val width: Int,
        val height: Int
    )

    private val pending = ArrayList<Pending>(4)

    // ------------------------------------------------------------------
    // Frame path (called on the capture thread)
    // ------------------------------------------------------------------

    /** Called immediately after FrameStore.publish(). [captureMs] is when the Image was acquired. */
    fun onPublished(captureMs: Long, publishMs: Long) {
        val dt = publishMs - captureMs
        synchronized(lock) { captureToPublish.add(dt) }
        lastCaptureMs = publishMs
    }

    /**
     * Called after a frame is published, with a cheap downscaled signature of it.
     * Resolves any pending input measurements whose screen has visibly changed.
     *
     * [sig] must be a small fixed-size luminance grid (see [signature]).
     */
    fun onFrameSignature(sig: IntArray, width: Int, height: Int, nowMs: Long) {
        val toResolve: List<Pending>
        synchronized(lock) {
            if (pending.isEmpty()) return
            toResolve = pending.toList()
        }
        val changed = ArrayList<Pending>(toResolve.size)
        for (p in toResolve) {
            if (p.width != width || p.height != height) {
                // Frame geometry changed (rotation / display switch): the baseline is
                // meaningless. Resolve as "unknown" rather than reporting a bogus time.
                changed.add(p)
                continue
            }
            if (diffRatio(p.baseline, sig) > CHANGE_THRESHOLD) changed.add(p)
        }
        if (changed.isEmpty()) {
            synchronized(lock) { comparedFrames++ }
            return
        }
        synchronized(lock) {
            comparedFrames++
            for (p in changed) {
                if (pending.remove(p)) inputLatency.add(nowMs - p.dispatchMs)
            }
        }
    }

    /**
     * Register that a gesture of [what] is being dispatched right now. Returns a token
     * to pass to [cancelInput] if the gesture was rejected (so we do not leave a
     * measurement hanging forever).
     */
    fun onDispatch(what: String, baseline: IntArray?, width: Int, height: Int, nowMs: Long): Boolean {
        if (baseline == null) return false
        synchronized(lock) {
            if (pending.size >= 4) pending.removeAt(0) // never grow unbounded
            pending.add(Pending(what, nowMs, baseline, width, height))
        }
        return true
    }

    fun cancelDispatch() {
        synchronized(lock) {
            if (pending.isNotEmpty()) {
                pending.removeAt(pending.size - 1)
                timedOutInputs++
            }
        }
    }

    /** Drop measurements older than [maxAgeMs]; they are never going to resolve. */
    fun expire(nowMs: Long, maxAgeMs: Long) {
        synchronized(lock) {
            val it = pending.iterator()
            while (it.hasNext()) {
                if (nowMs - it.next().dispatchMs > maxAgeMs) {
                    it.remove()
                    timedOutInputs++
                }
            }
        }
    }

    fun pendingCount(): Int = synchronized(lock) { pending.size }

    // ------------------------------------------------------------------
    // Reporting
    // ------------------------------------------------------------------

    fun reset() {
        synchronized(lock) {
            captureToPublish.clear()
            inputLatency.clear()
            pending.clear()
            comparedFrames = 0L
            timedOutInputs = 0L
        }
    }

    /**
     * @param captureToPublish capture->publish series, ms
     * @param inputLatency dispatch->seen series, ms
     */
    class Report(
        val captureToPublish: Series,
        val inputLatency: Series,
        val comparedFrames: Long,
        val timedOutInputs: Long,
        val pending: Int
    )

    class Series(
        val count: Int,
        val p50: Long,
        val p95: Long,
        val max: Long,
        val min: Long
    ) {
        fun toJson() = org.json.JSONObject().apply {
            put("count", count)
            if (count > 0) {
                put("p50", p50); put("p95", p95); put("max", max); put("min", min)
            }
        }
    }

    fun report(): Report = synchronized(lock) {
        Report(
            captureToPublish.summarise(),
            inputLatency.summarise(),
            comparedFrames,
            timedOutInputs,
            pending.size
        )
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Cheap change signature: downscale [bmp] to a [GRID]x[GRID] luminance grid.
     *
     * Deliberately a hand-rolled box average rather than Bitmap.createScaledBitmap +
     * getPixels: this runs on the capture thread for every frame while a measurement
     * is in flight, and a fresh scaled Bitmap per frame is both an allocation and a
     * recycle trap. Sampling is strided so cost is GRID*GRID dot products regardless
     * of display size.
     *
     * @return luminance grid, or null if the bitmap is unusable.
     */
    const val GRID = 32

    fun signature(bmp: android.graphics.Bitmap): IntArray? {
        val w = bmp.width
        val h = bmp.height
        if (w < GRID || h < GRID) return null
        val out = IntArray(GRID * GRID)
        val stepX = w / GRID
        val stepY = h / GRID
        if (stepX < 1 || stepY < 1) return null
        try {
            for (gy in 0 until GRID) {
                val y = gy * stepY + stepY / 2
                for (gx in 0 until GRID) {
                    val x = gx * stepX + stepX / 2
                    val px = bmp.getPixel(x, y)
                    // integer luma, no floating point: 0.299/0.587/0.114 scaled by 1000
                    out[gy * GRID + gx] =
                        (((px shr 16) and 0xFF) * 299 + ((px shr 8) and 0xFF) * 587 + (px and 0xFF) * 114) / 1000
                }
            }
        } catch (t: Throwable) {
            return null
        }
        return out
    }

    /** A frame counts as "changed" when more than this fraction of the grid moved. */
    private const val CHANGE_THRESHOLD = 0.02   // 2%

    /** Pixel delta that counts as a change at all (ignores JPEG/compose noise). */
    private const val PIXEL_EPSILON = 18

    private fun diffRatio(a: IntArray, b: IntArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var changed = 0
        for (i in a.indices) {
            if (kotlin.math.abs(a[i] - b[i]) > PIXEL_EPSILON) changed++
        }
        return changed.toDouble() / a.size
    }

    private class Ring(private val capacity: Int) {
        private val data = LongArray(capacity)
        private var size = 0
        private var head = 0

        fun add(v: Long) {
            data[head] = v
            head = (head + 1) % capacity
            if (size < capacity) size++
        }

        fun clear() { size = 0; head = 0 }

        fun summarise(): Series {
            if (size == 0) return Series(0, 0, 0, 0, 0)
            val copy = LongArray(size)
            for (i in 0 until size) copy[i] = data[i]
            copy.sort()
            return Series(
                count = size,
                p50 = copy[(size * 50) / 100],
                p95 = copy[((size * 95) / 100).coerceAtMost(size - 1)],
                max = copy[size - 1],
                min = copy[0]
            )
        }
    }
}

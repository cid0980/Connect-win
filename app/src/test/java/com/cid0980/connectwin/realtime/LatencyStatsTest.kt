package com.cid0980.connectwin.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the latency instrumentation maths.
 *
 * These are deliberately pure-JVM: no Bitmap, no SystemClock, no Android framework.
 * The interesting logic is percentile selection and change detection — both are easy
 * to get subtly wrong (off-by-one at p95, or a threshold that fires on JPEG noise),
 * and both are what every "is the bridge fast enough" claim rests on.
 */
class LatencyStatsTest {

    // ------------------------------------------------------------------
    // Ring / percentile behaviour
    // ------------------------------------------------------------------

    @Test
    fun `empty series reports zero count and no percentiles`() {
        LatencyStats.reset()
        val rep = LatencyStats.report()
        assertEquals(0, rep.captureToPublish.count)
        assertEquals(0, rep.inputLatency.count)
    }

    @Test
    fun `capture to publish percentiles are ordered and bounded`() {
        LatencyStats.reset()
        // 1..100 ms
        for (i in 1..100) LatencyStats.onPublished(0L, i.toLong())
        val s = LatencyStats.report().captureToPublish
        assertEquals(100, s.count)
        assertEquals(1L, s.min)
        assertEquals(100L, s.max)
        assertTrue("p50=${s.p50}", s.p50 in 45L..55L)
        assertTrue("p95=${s.p95}", s.p95 in 90L..99L)
        assertTrue("min<=p50", s.min <= s.p50)
        assertTrue("p50<=p95", s.p50 <= s.p95)
        assertTrue("p95<=max", s.p95 <= s.max)
    }

    @Test
    fun `ring keeps only the most recent samples`() {
        LatencyStats.reset()
        // 600 samples into a 512-capacity ring, all fast except the first 88.
        for (i in 1..88) LatencyStats.onPublished(0L, 9_000L)  // should age out
        for (i in 1..512) LatencyStats.onPublished(0L, 10L)    // the real workload
        val s = LatencyStats.report().captureToPublish
        assertEquals(512, s.count)
        assertEquals("evicted outliers must not skew max", 10L, s.max)
    }

    @Test
    fun `negative intervals are recorded but do not corrupt ordering`() {
        LatencyStats.reset()
        LatencyStats.onPublished(0L, 50L)
        LatencyStats.onPublished(0L, 60L)
        val s = LatencyStats.report().captureToPublish
        assertEquals(2, s.count)
        assertTrue(s.min <= s.p50 && s.p50 <= s.p95 && s.p95 <= s.max)
    }

    // ------------------------------------------------------------------
    // Dispatch -> seen
    // ------------------------------------------------------------------

    @Test
    fun `identical frame does not resolve a pending input`() {
        LatencyStats.reset()
        val grid = IntArray(LatencyStats.GRID * LatencyStats.GRID) { 100 }
        assertTrue(LatencyStats.onDispatch("click", grid, 32, 32, 1_000L))
        LatencyStats.onFrameSignature(grid, 32, 32, 1_050L)
        assertEquals("no change must not claim latency", 0, LatencyStats.report().inputLatency.count)
        assertEquals(1, LatencyStats.pendingCount())
    }

    @Test
    fun `a real screen change resolves the pending input`() {
        LatencyStats.reset()
        val n = LatencyStats.GRID * LatencyStats.GRID
        val before = IntArray(n) { 100 }
        val after = IntArray(n) { 200 }   // whole screen changed
        assertTrue(LatencyStats.onDispatch("click", before, 32, 32, 1_000L))
        LatencyStats.onFrameSignature(after, 32, 32, 1_120L)
        val s = LatencyStats.report().inputLatency
        assertEquals(1, s.count)
        assertEquals(120L, s.p50)
        assertEquals(0, LatencyStats.pendingCount())
    }

    @Test
    fun `below-threshold noise does not count as a change`() {
        LatencyStats.reset()
        val n = LatencyStats.GRID * LatencyStats.GRID
        val before = IntArray(n) { 100 }
        val after = before.copyOf()
        // Disturb exactly 1% of cells (< the 2% threshold): sub-pixel compose noise.
        val perturbed = (n / 100).coerceAtLeast(1)
        for (i in 0 until perturbed) after[i] = 200
        LatencyStats.onDispatch("click", before, 32, 32, 1_000L)
        LatencyStats.onFrameSignature(after, 32, 32, 1_100L)
        assertEquals(0, LatencyStats.report().inputLatency.count)
    }

    @Test
    fun `above-threshold change counts`() {
        LatencyStats.reset()
        val n = LatencyStats.GRID * LatencyStats.GRID
        val before = IntArray(n) { 100 }
        val after = before.copyOf()
        val perturbed = (n * 5) / 100          // 5% > 2%
        for (i in 0 until perturbed) after[i] = 200
        LatencyStats.onDispatch("click", before, 32, 32, 1_000L)
        LatencyStats.onFrameSignature(after, 32, 32, 1_080L)
        assertEquals(80L, LatencyStats.report().inputLatency.p50)
    }

    @Test
    fun `geometry change resolves as unknown rather than bogus latency`() {
        LatencyStats.reset()
        val n = LatencyStats.GRID * LatencyStats.GRID
        LatencyStats.onDispatch("click", IntArray(n) { 100 }, 32, 32, 1_000L)
        // Rotation: different dimensions, contents incomparable.
        LatencyStats.onFrameSignature(IntArray(n) { 100 }, 16, 64, 1_200L)
        assertEquals(1, LatencyStats.report().inputLatency.count)
    }

    @Test
    fun `cancelled dispatch does not leave a hanging measurement`() {
        LatencyStats.reset()
        val n = LatencyStats.GRID * LatencyStats.GRID
        LatencyStats.onDispatch("click", IntArray(n) { 100 }, 32, 32, 1_000L)
        assertEquals(1, LatencyStats.pendingCount())
        LatencyStats.cancelDispatch()
        assertEquals(0, LatencyStats.pendingCount())
        assertEquals(0, LatencyStats.report().inputLatency.count)
    }

    @Test
    fun `unresolved inputs expire instead of reporting stale successes`() {
        LatencyStats.reset()
        val n = LatencyStats.GRID * LatencyStats.GRID
        LatencyStats.onDispatch("click", IntArray(n) { 100 }, 32, 32, 1_000L)
        LatencyStats.expire(nowMs = 5_000L, maxAgeMs = 2_000L)
        assertEquals(0, LatencyStats.pendingCount())
        assertEquals(0, LatencyStats.report().inputLatency.count)
        assertEquals(1L, LatencyStats.report().timedOutInputs)
    }

    @Test
    fun `pending list stays bounded under a flood of dispatches`() {
        LatencyStats.reset()
        val n = LatencyStats.GRID * LatencyStats.GRID
        repeat(50) { LatencyStats.onDispatch("click", IntArray(n) { 100 }, 32, 32, it.toLong()) }
        assertTrue("pending=${LatencyStats.pendingCount()}", LatencyStats.pendingCount() <= 4)
    }

    @Test
    fun `reset clears both series and counters`() {
        LatencyStats.reset()
        LatencyStats.onPublished(0L, 42L)
        val n = LatencyStats.GRID * LatencyStats.GRID
        LatencyStats.onDispatch("click", IntArray(n) { 100 }, 32, 32, 0L)
        LatencyStats.reset()
        val rep = LatencyStats.report()
        assertEquals(0, rep.captureToPublish.count)
        assertEquals(0, rep.inputLatency.count)
        assertEquals(0, rep.pending)
        assertEquals(0L, rep.comparedFrames)
    }
}

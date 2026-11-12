package com.cid0980.connectwin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentActivityStateTest {

    @Test
    fun workingHeartbeatExpiresToIdle() {
        val t = AgentActivityTracker(defaultTtlMs = 1_000L)
        t.setAgent("working", "Reading screenshot", nowMs = 1_000L, ttlMs = 1_000L)
        assertEquals("working", t.snapshot(1_999L).state)
        assertEquals("working", t.snapshot(2_000L).state)
        val expired = t.snapshot(2_001L)
        assertEquals("idle", expired.state)
        assertEquals("working", expired.explicitState)
        assertTrue(expired.message.contains("expired"))
        assertNull(expired.expiresInSeconds)
    }

    @Test
    fun pausedAndDoneDoNotExpire() {
        val t = AgentActivityTracker(defaultTtlMs = 1_000L)
        t.setAgent("paused", "Waiting for user", nowMs = 0L)
        assertEquals("paused", t.snapshot(60_000L).state)
        t.setAgent("done", "Shipped", nowMs = 60_000L)
        assertEquals("done", t.snapshot(120_000L).state)
    }

    @Test
    fun actionsAndRequestsAreTrackedSeparately() {
        val t = AgentActivityTracker()
        t.observeRequest("/screenshot", "GET", nowMs = 100L)
        t.observeRequest("/gui/click", "POST", nowMs = 200L)
        t.observeRequest("/agent/status", "POST", nowMs = 300L)
        val s = t.snapshot(500L)
        assertEquals(3L, s.requestCount)
        assertEquals(1L, s.actionCount)
        assertEquals(300L, s.lastRequestAgeMs!!)
        assertEquals(300L, s.lastActionAgeMs!!)
    }

    @Test
    fun agesAreNullBeforeFirstEvent() {
        val s = AgentActivityTracker().snapshot(10_000L)
        assertEquals("idle", s.state)
        assertNull(s.lastRequestAgeMs)
        assertNull(s.lastActionAgeMs)
        assertNull(s.heartbeatAgeMs)
    }

    @Test
    fun messageIsSanitizedAndBounded() {
        val t = AgentActivityTracker()
        val s = t.setAgent("working", "  hello\u0000world  " + "x".repeat(300), nowMs = 0L)
        assertTrue(!s.message.contains("\u0000"))
        assertTrue(s.message.length <= 180)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidStateRejected() {
        AgentActivityTracker().setAgent("dancing", "", nowMs = 0L)
    }

    @Test
    fun ttlIsClamped() {
        val t = AgentActivityTracker(defaultTtlMs = 1_000L)
        t.setAgent("working", "x", nowMs = 0L, ttlMs = 1L)
        assertEquals("working", t.snapshot(4_999L).state)
        assertEquals("idle", t.snapshot(5_001L).state)
    }
}

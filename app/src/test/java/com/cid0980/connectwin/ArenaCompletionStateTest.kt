package com.cid0980.connectwin

import org.junit.Assert.*
import org.junit.Test

class ArenaCompletionStateTest {
    @Test fun firesOnceAfterStableIdle() {
        val s = ArenaCompletionState()
        assertFalse(s.sample("a", true, 100))
        assertFalse(s.sample("a", false, 1000))
        assertFalse(s.sample("a", false, 3999))
        assertTrue(s.sample("a", false, 4000))
        assertFalse(s.sample("a", false, 10000))
    }
    @Test fun transientIdleAndPageSwitchDoNotNotify() {
        val s = ArenaCompletionState()
        s.sample("a", true, 100)
        s.sample("a", false, 200)
        s.sample("a", true, 2000)
        assertFalse(s.sample("a", false, 3500))
        assertFalse(s.sample("b", false, 8000))
        assertFalse(s.sample("a", false, 12000))
        s.sample("a", true, 13000)
        s.sample(null, false, 14000)
        assertFalse(s.sample("a", false, 18000))
    }
}

package com.chun.pibridge

import org.junit.Assert.*
import org.junit.Test

class StateTransitionsTest {
    @Test
    fun duplicateSnapshotAndStateEventNotifyOnce() {
        val t = StateTransitions()
        t.baseline("p", "idle")
        assertTrue(t.changed("p", "blocked"))
        assertFalse(t.changed("p", "blocked"))
        assertFalse(t.changed("p", "working"))
        assertTrue(t.changed("p", "done"))
        assertFalse(t.changed("p", "done"))
    }

    @Test
    fun initialSnapshotDoesNotNotify() {
        val t = StateTransitions()
        assertFalse(t.changed("p", "blocked"))
        t.clear()
        assertFalse(t.changed("p", "done"))
    }
}

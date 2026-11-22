package com.chun.pibridge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionUiReducerTest {
    private fun metadata(state: String = "idle", seq: Long = 0, instance: String = "a", sid: String = "s") =
        JSONObject().put("sessionKey", "p").put("bridge", true).put("sessionId", sid).put("instanceId", instance)
            .put("state", "working").put("execution", JSONObject().put("state", state).put("seq", seq))
    private fun event(seq: Long, kind: String, instance: String = "a", sid: String = "s", state: String? = null, aborted: Boolean = false) =
        JSONObject().put("sessionId", sid).put("instanceId", instance).put("seq", seq).put("kind", kind)
            .put("payload", JSONObject().put("aborted", aborted)).apply {
                if (state != null) put("execution", JSONObject().put("state", state).put("seq", seq))
            }
    private fun initial() = SessionUiReducer.metadata(session = metadata())

    @Test fun sdkSnapshotOverridesHerdrWorking() {
        val s = initial()
        assertEquals("idle", s.phase)
        assertFalse(s.canAbort)
        assertTrue(s.authoritative)
    }
    @Test fun completionAndAbortVisibilityChangeTogether() {
        val running = SessionUiReducer.event(initial(), event(1, "agent_start"))
        assertTrue(running.working); assertTrue(running.canAbort)
        val done = SessionUiReducer.event(running, event(2, "agent_settled"))
        assertEquals("done", done.phase); assertFalse(done.working); assertFalse(done.canAbort)
    }
    @Test fun providerEndIsNotFinalSettlement() {
        val running = SessionUiReducer.event(initial(), event(1, "agent_start"))
        val end = SessionUiReducer.event(running, event(2, "agent_end"))
        assertTrue(end.canAbort)
    }
    @Test fun explicitAbortIsNotCompletion() {
        val stopped = SessionUiReducer.event(initial(), event(3, "agent_settled", aborted = true))
        assertEquals("aborted", stopped.phase); assertFalse(stopped.canAbort)
    }
    @Test fun errorSnapshotIsNotCompletion() {
        val error = SessionUiReducer.event(initial(), event(1, "agent_settled", state = "error"))
        assertEquals("error", error.phase); assertFalse(error.canAbort)
    }
    @Test fun blockedAndResumeDuringRun() {
        var s = SessionUiReducer.event(initial(), event(1, "agent_start"))
        s = SessionUiReducer.event(s, event(2, "ui_prompt_start"))
        assertTrue(s.blocked); assertFalse(s.canAbort)
        s = SessionUiReducer.event(s, event(3, "ui_prompt_end"))
        assertTrue(s.canAbort); assertFalse(s.blocked)
    }
    @Test fun idleDialogDoesNotCreatePhantomRunOnLegacyBridge() {
        val blocked = SessionUiReducer.event(initial(), event(1, "ui_prompt_start"))
        val idle = SessionUiReducer.event(blocked, event(2, "ui_prompt_end"))
        assertEquals("idle", idle.phase); assertFalse(idle.canAbort)
    }
    @Test fun nestedDialogsRestoreOriginalPhase() {
        var s = SessionUiReducer.event(initial(), event(1, "ui_prompt_start"))
        s = SessionUiReducer.event(s, event(2, "ui_prompt_start"))
        s = SessionUiReducer.event(s, event(3, "ui_prompt_end"))
        assertTrue(s.blocked)
        s = SessionUiReducer.event(s, event(4, "ui_prompt_end"))
        assertEquals("idle", s.phase)
    }
    @Test fun settlementDuringDialogCannotBeResurrectedByLatePromptEnd() {
        var s = SessionUiReducer.event(initial(), event(1, "ui_prompt_start"))
        s = SessionUiReducer.event(s, event(2, "agent_settled", aborted = true))
        s = SessionUiReducer.event(s, event(3, "ui_prompt_end"))
        assertEquals("aborted", s.phase)
    }
    @Test fun runStartingBehindModalDoesNotHideDialogOnLegacyBridge() {
        var s = SessionUiReducer.event(initial(), event(1, "ui_prompt_start"))
        s = SessionUiReducer.event(s, event(2, "agent_start"))
        assertTrue(s.blocked); assertFalse(s.canAbort)
        s = SessionUiReducer.event(s, event(3, "ui_prompt_end"))
        assertTrue(s.canAbort)
    }
    @Test fun snapshotRepairsMissingPromptEndAndClearsLegacyDialogStack() {
        val blocked = SessionUiReducer.event(initial(), event(1, "ui_prompt_start"))
        val healed = SessionUiReducer.metadata(blocked, metadata("idle", 3))
        assertTrue(healed.dialogs.isEmpty()); assertFalse(healed.canAbort)
        assertTrue(SessionUiReducer.event(healed, event(4, "agent_start")).canAbort)
    }
    @Test fun duplicateAndOutOfOrderEventsAreIgnored() {
        val done = SessionUiReducer.event(initial(), event(10, "agent_settled"))
        for (seq in 0L..10L) assertEquals(done, SessionUiReducer.event(done, event(seq, "agent_start")))
    }
    @Test fun staleSnapshotCannotResurrectWorking() {
        val done = SessionUiReducer.event(initial(), event(10, "agent_settled"))
        assertEquals(done, SessionUiReducer.metadata(done, metadata("working", 9)))
    }
    @Test fun newerSnapshotRepairsMissingSettlement() {
        val running = SessionUiReducer.event(initial(), event(1, "agent_start"))
        val done = SessionUiReducer.metadata(running, metadata("done", 5))
        assertEquals("done", done.phase); assertFalse(done.canAbort)
    }
    @Test fun genericEventCarriesFullExecutionSnapshotAndRepairsGap() {
        val s = SessionUiReducer.event(initial(), event(99, "history_sync", state = "done"))
        assertEquals("done", s.phase); assertEquals(99, s.seq)
    }
    @Test fun herdrCannotOverrideAuthoritativeStateInEitherDirection() {
        val idle = initial()
        assertEquals(idle, SessionUiReducer.fallback(idle, "working"))
        val running = SessionUiReducer.event(idle, event(1, "agent_start"))
        assertEquals(running, SessionUiReducer.fallback(running, "done"))
    }
    @Test fun legacyMetadataAndEventsStillWork() {
        val old = metadata().apply { remove("execution") }
        val baseline = SessionUiReducer.metadata(session = old)
        assertTrue(baseline.canAbort)
        val done = SessionUiReducer.event(baseline, event(1, "agent_settled"))
        assertEquals("done", SessionUiReducer.metadata(done, old).phase)
    }
    @Test fun legacyIdleSnapshotReconcilesCachedWorking() {
        val s = SessionUiReducer.snapshot(SessionUiState(), metadata().apply {
            remove("execution"); put("idle", true)
        }, 7)
        assertEquals("idle", s.phase); assertEquals(7, s.seq); assertFalse(s.canAbort)
    }
    @Test fun legacyBusySnapshotReconcilesCachedIdle() {
        val s = SessionUiReducer.snapshot(SessionUiState(), metadata().apply {
            remove("execution"); put("idle", false)
        }, 7)
        assertTrue(s.canAbort)
    }
    @Test fun reloadAcceptsLowSequenceWithNewInstance() {
        val old = SessionUiReducer.event(initial(), event(100, "agent_start"))
        val fresh = SessionUiReducer.metadata(old, metadata("idle", 0, "b"))
        assertEquals("b", fresh.instanceId); assertEquals(0, fresh.seq); assertFalse(fresh.canAbort)
        assertTrue("s|a" in fresh.retired)
    }
    @Test fun oldRuntimeEventCannotOverwriteReloadedRuntime() {
        val fresh = SessionUiReducer.metadata(initial(), metadata("idle", 0, "b"))
        assertEquals(fresh, SessionUiReducer.event(fresh, event(999, "agent_start")))
    }
    @Test fun oldRuntimeSnapshotCannotOverwriteReloadedRuntime() {
        val fresh = SessionUiReducer.metadata(initial(), metadata("idle", 0, "b"))
        assertEquals(fresh, SessionUiReducer.metadata(fresh, metadata("working", 999)))
    }
    @Test fun newConversationOnSamePaneGetsIndependentWatermark() {
        val old = SessionUiReducer.event(initial(), event(100, "agent_start"))
        val fresh = SessionUiReducer.metadata(old, metadata("idle", 0, "b", "next"))
        assertEquals("next", fresh.sessionId); assertFalse(fresh.canAbort)
        assertEquals(fresh, SessionUiReducer.event(fresh, event(101, "agent_start")))
    }
    @Test fun newInstanceEventBeforeSnapshotEstablishesIdentity() {
        val next = SessionUiReducer.event(initial(), event(1, "agent_start", "b", state = "working"))
        assertEquals("b", next.instanceId); assertTrue(next.canAbort)
        assertEquals(next, SessionUiReducer.metadata(next, metadata("idle", 0, "b")))
    }
    @Test fun missingLegacyIdentityDoesNotEraseKnownIdentity() {
        val p = event(1, "agent_start").apply { remove("instanceId"); remove("sessionId") }
        val s = SessionUiReducer.event(initial(), p)
        assertEquals("a", s.instanceId); assertEquals("s", s.sessionId)
    }
    @Test fun closingChatViewPreservesEveryKnownPhaseAndAvailability() {
        for (phase in listOf("idle", "working", "blocked", "done", "aborted", "error")) {
            val known = SessionUiReducer.metadata(session = metadata(phase, 5))
            val closed = SessionUiReducer.viewClosed(known)
            assertEquals(known, closed)
            assertEquals(phase, closed.phase); assertTrue(closed.available)
        }
    }
    @Test fun closedLegacyViewKeepsLastStatusButAcceptsFutureHerdrUpdates() {
        val legacy = metadata("idle").apply { remove("execution") }
        val done = SessionUiReducer.event(SessionUiReducer.metadata(session = legacy), event(1, "agent_settled"))
        val closed = SessionUiReducer.viewClosed(done)
        assertEquals("done", closed.phase); assertTrue(closed.available); assertFalse(closed.authoritative)
        assertEquals("working", SessionUiReducer.fallback(closed, "working").phase)
    }
    @Test fun legacySnapshotDoesNotPretendItHasSequencedSnapshotCapability() {
        val s = SessionUiReducer.snapshot(SessionUiState(), metadata().apply {
            remove("execution"); put("idle", true)
        }, 10)
        assertFalse(s.sequencedSnapshots)
        assertFalse(SessionUiReducer.viewClosed(s).authoritative)
    }
    @Test fun retiredLegacySnapshotCannotDowngradeCurrentSnapshotCapability() {
        val fresh = SessionUiReducer.metadata(initial(), metadata("done", 0, "b"))
        assertTrue(fresh.sequencedSnapshots)
        val old = metadata("working", 999).apply { remove("execution") }
        assertEquals(fresh, SessionUiReducer.snapshot(fresh, old, 999))
    }
    @Test fun lostBridgeDisablesAbortAndSnapshotRestoresState() {
        val running = SessionUiReducer.event(initial(), event(1, "agent_start"))
        val missing = SessionUiReducer.unavailable(running)
        assertFalse(missing.canAbort); assertEquals("unknown", missing.phase)
        assertTrue(SessionUiReducer.metadata(missing, metadata("working", 2)).canAbort)
    }
    @Test fun retiredResetIdentityIsRejectedBeforeMarkingCurrentUnavailable() {
        val fresh = SessionUiReducer.metadata(initial(), metadata("done", 0, "b"))
        assertFalse(SessionUiReducer.accepts(fresh, JSONObject().put("sessionId", "s").put("instanceId", "a")))
        assertTrue(SessionUiReducer.accepts(fresh, JSONObject().put("sessionId", "s").put("instanceId", "b")))
    }
    @Test fun malformedWatermarksCannotInventWorkingState() {
        for (seq in listOf(null, "100", -1, 1.5, true)) {
            val p = metadata("working").apply { getJSONObject("execution").put("seq", seq) }
            val s = SessionUiReducer.metadata(session = p)
            assertFalse(s.canAbort); assertEquals("unknown", s.phase)
        }
    }
    @Test fun malformedWatermarkCannotOverwriteKnownGoodState() {
        val done = SessionUiReducer.event(initial(), event(10, "agent_settled"))
        val bad = metadata("working").apply { getJSONObject("execution").remove("seq") }
        assertEquals(done, SessionUiReducer.metadata(done, bad))
    }
    @Test fun malformedStateIsUnknownNotWorking() {
        for (state in listOf("", "null", "garbage")) {
            val s = SessionUiReducer.metadata(session = metadata(state))
            assertEquals("unknown", s.phase); assertFalse(s.canAbort)
        }
    }
    @Test fun disconnectedCacheCannotEnableAbort() {
        val b = BridgeState(connected = false, sessions = listOf(metadata("working", 50)))
        assertFalse(b.sessionUi("p").canAbort); assertEquals("unknown", b.sessionUi("p").phase)
    }
    @Test fun removedPaneDropsItsExecutionState() {
        val b = BridgeState(connected = true).withSessionList(listOf(metadata("working", 2)))
        assertTrue(b.sessionUi("p").canAbort)
        val removed = b.withSessionList(emptyList())
        assertTrue(removed.sessionStates.isEmpty()); assertFalse(removed.sessionUi("p").canAbort)
    }
    @Test fun phasesAreIsolatedBetweenPanesAndInputJsonIsNotMutated() {
        val p = metadata("done", 2)
        val q = metadata("working", 4, "other").put("sessionKey", "q")
        val b = BridgeState(connected = true).withSessionList(listOf(p, q))
        assertEquals("working", p.optString("state"))
        assertFalse(b.sessionUi("p").canAbort); assertTrue(b.sessionUi("q").canAbort)
        val next = b.withSessionUi("p", SessionUiReducer.event(b.sessionUi("p"), event(3, "agent_start")))
        assertTrue(next.sessionUi("p").canAbort); assertEquals(b.sessionUi("q"), next.sessionUi("q"))
        assertEquals("done", b.sessions[0].optString("state"))
    }
    @Test fun canonicalListAndActionNeverDisagreeAcrossAdversarialOrders() {
        // 100 deterministic permutations: delayed discovery, duplicate events and old generations.
        repeat(100) { seed ->
            var s = initial()
            val messages = (1L..30L).map { event(it, if (it % 2L == 0L) "agent_settled" else "agent_start") }
                .shuffled(kotlin.random.Random(seed))
            for (p in messages) {
                s = SessionUiReducer.event(s, p)
                s = SessionUiReducer.metadata(s, metadata("working", 0))
                s = SessionUiReducer.fallback(s, "working")
                val b = BridgeState(connected = true, sessions = listOf(metadata())).withSessionUi("p", s)
                assertEquals(b.sessionUi("p").phase, b.sessions.single().optString("state"))
                assertEquals(s.phase == "working", b.sessionUi("p").canAbort)
            }
            assertEquals("done", s.phase); assertEquals(30, s.seq); assertFalse(s.canAbort)
        }
    }
}

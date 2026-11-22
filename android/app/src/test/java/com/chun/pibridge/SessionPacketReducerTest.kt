package com.chun.pibridge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionPacketReducerTest {
    private fun initial(executionSeq: Long = 0, chatSeq: Long = 0) = BridgeState(connected = true, selected = "p",
        sessions = listOf(JSONObject().put("sessionKey", "p").put("bridge", true)),
        sessionStates = mapOf("p" to SessionUiState("s", "a", "done", executionSeq, true, true)),
        chats = mapOf("p" to Conversation(seq = chatSeq, sessionId = "s", instanceId = "a")))
    private fun packet(seq: Long, kind: String = "message_start", instance: String = "a") = JSONObject()
        .put("sessionId", "s").put("instanceId", instance).put("seq", seq).put("kind", kind)
        .put("payload", JSONObject().put("message", JSONObject().put("role", "assistant")
            .put("timestamp", 123).put("content", "result")))
    @Test fun discoveryAheadOfChatDoesNotDropMessages() {
        val result = initial(10, 8).reduceSessionPacket("p", packet(9))
        assertFalse(result.resync)
        assertEquals("done", result.state.sessionUi("p").phase)
        assertEquals(10, result.state.sessionUi("p").seq)
        assertEquals("result", result.state.chats["p"]!!.entries.single().getJSONObject("message").textContent())
        assertEquals(9, result.state.chats["p"]!!.seq)
    }
    @Test fun duplicateChatPacketIsIdempotentEvenWhenExecutionAlreadyAhead() {
        val first = initial(10, 8).reduceSessionPacket("p", packet(9)).state
        val next = first.reduceSessionPacket("p", packet(9)).state
        assertEquals(first.chats["p"], next.chats["p"])
        assertEquals(1, next.chats["p"]!!.entries.size)
    }
    @Test fun gapAppliesExecutionImmediatelyAndRequestsHistoryRepair() {
        val result = initial(0, 3).reduceSessionPacket("p", packet(10, "agent_start"))
        assertTrue(result.resync); assertTrue(result.state.sessionUi("p").canAbort)
        assertEquals(3, result.state.chats["p"]!!.seq)
    }
    @Test fun newRuntimeDoesNotApplyLowSequenceToOldHistory() {
        val result = initial(100, 100).reduceSessionPacket("p", packet(1, "agent_start", "b"))
        assertTrue(result.resync)
        assertEquals("b", result.state.sessionUi("p").instanceId)
        assertTrue(result.state.sessionUi("p").canAbort)
        assertEquals("a", result.state.chats["p"]!!.instanceId)
    }
    @Test fun retiredRuntimeCannotModifyChatOrExecution() {
        val initial = initial().withSessionUi("p", SessionUiState("s", "b", "done", 0, true, true, setOf("s|a")))
        val result = initial.reduceSessionPacket("p", packet(999, "agent_start"))
        assertSame(initial, result.state); assertFalse(result.resync)
    }
    @Test fun closedPaneCannotBeResurrectedByLatePackets() {
        val closed = initial().withSessionList(emptyList())
        assertSame(closed, closed.reduceSessionPacket("p", packet(5)).state)
    }
    @Test fun lateLegacyPacketAfterLeavingCannotReclaimPermanentAuthority() {
        val left = initial().copy(selected = "")
        val late = left.reduceSessionPacket("p", packet(1, "agent_start")).state
        assertEquals("working", late.sessionUi("p").phase)
        assertTrue(late.sessionUi("p").available)
        assertFalse(late.sessionUi("p").authoritative)
        assertEquals("done", SessionUiReducer.fallback(late.sessionUi("p"), "done").phase)
    }
    @Test fun unselectedSequencedBridgeKeepsSdkAuthorityAndStatus() {
        val state = initial().copy(selected = "").withSessionUi("p",
            initial().sessionUi("p").copy(sequencedSnapshots = true))
        val next = state.reduceSessionPacket("p", packet(1, "agent_settled")).state
        assertEquals("done", next.sessionUi("p").phase)
        assertTrue(next.sessionUi("p").authoritative)
        assertEquals(next.sessionUi("p"), SessionUiReducer.fallback(next.sessionUi("p"), "working"))
    }
    @Test fun legacyCachedWatermarkRequestsFullRebase() {
        val initial = initial().copy(chats = mapOf("p" to Conversation(seq = 100)))
        val result = initial.reduceSessionPacket("p", packet(1, "agent_start"))
        assertTrue(result.resync); assertTrue(result.state.sessionUi("p").canAbort)
    }
}

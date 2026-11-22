package com.chun.pibridge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReducerTest {
    private fun packet(seq: Long, kind: String, p: JSONObject) =
        JSONObject().put("seq", seq).put("kind", kind).put("payload", p)

    private fun msg(role: String, text: String, ts: Long) =
        JSONObject()
            .put("role", role)
            .put("timestamp", ts)
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))

    @Test
    fun indexedDeltasAndThinkingAreAccumulated() {
        val initial = msg("assistant", "a", 12)
        val old =
            Conversation(
                entries = listOf(JSONObject().put("entryId", "live:12").put("message", initial))
            )
        val update = JSONObject().put("type", "text_delta").put("contentIndex", 0).put("delta", "b")
        val merged = accumulateStream(old, msg("assistant", "ab", 12), update)
        assertEquals("ab", merged.textContent())
        val thought =
            JSONObject().put("type", "thinking_delta").put("contentIndex", 1).put("delta", "考える")
        val next = accumulateStream(old, msg("assistant", "a", 12), thought)
        assertEquals("考える", next.getJSONArray("content").getJSONObject(1).getString("thinking"))
        val end = JSONObject().put("type", "text_end").put("contentIndex", 0).put("content", "確定")
        assertEquals("確定", accumulateStream(old, initial, end).textContent())
    }

    @Test
    fun streamingReplacedByFinalAndDurableEntry() {
        var c = Conversation()
        c =
            Reducer.event(
                c,
                packet(1, "message_start", JSONObject().put("message", msg("assistant", "", 1))),
            )
        c =
            Reducer.event(
                c,
                packet(
                    2,
                    "message_update",
                    JSONObject().put("message", msg("assistant", "hello\u2028world", 1)),
                ),
            )
        assertEquals(1, c.entries.size)
        assertTrue(c.entries[0].getBoolean("streaming"))
        c =
            Reducer.event(
                c,
                packet(3, "message_end", JSONObject().put("message", msg("assistant", "final", 1))),
            )
        assertEquals("final", c.entries[0].getJSONObject("message").textContent())
        assertFalse(c.entries[0].getBoolean("streaming"))
        val h =
            JSONObject()
                .put(
                    "entries",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("entryId", "entry1")
                                .put("message", msg("assistant", "final", 1))
                        ),
                )
                .put("leafId", "entry1")
                .put("seq", 3)
        c = Reducer.event(c, packet(4, "history_sync", h))
        assertEquals(1, c.entries.size)
        assertEquals("entry1", c.entries[0].getString("entryId"))
        assertEquals("entry1", c.leaf)
        assertEquals(4, c.seq)
    }

    @Test
    fun duplicateSequenceIgnored() {
        val c = Conversation(seq = 5)
        assertEquals(c, Reducer.event(c, packet(5, "agent_start", JSONObject())))
    }

    @Test
    fun pendingReconciledByClientId() {
        val c = Conversation(pending = listOf(JSONObject().put("clientMsgId", "abc")))
        val p =
            JSONObject()
                .put("message", msg("user", "mobile", 2))
                .put("origin", "mobile")
                .put("clientMsgId", "abc")
        val n = Reducer.event(c, packet(1, "message_start", p))
        assertTrue(n.pending.isEmpty())
        assertEquals("mobile", n.entries[0].getString("origin"))
    }

    @Test
    fun historyResetDropsOldBranch() {
        val c = Conversation(entries = listOf(JSONObject().put("entryId", "old")))
        val h =
            JSONObject()
                .put("reset", true)
                .put("entries", JSONArray().put(JSONObject().put("entryId", "new")))
                .put("leafId", "new")
        val n = Reducer.history(c, h)
        assertEquals(listOf("new"), n.entries.map { it.optString("entryId") })
    }

    @Test
    fun lifecycleStateIsNotStoredInConversationOrCache() {
        val c = Reducer.event(Conversation(), packet(1, "agent_start", JSONObject()))
        val ended = Reducer.event(c, packet(2, "agent_settled", JSONObject()))
        assertFalse(JSONObject(ended.json()).has("state"))
        val legacy = JSONObject(ended.json()).put("state", "working").toString()
        assertEquals(ended.json(), Conversation.from(legacy).json())
    }

    @Test
    fun toolUpdatesReplaceCard() {
        var c = Conversation()
        for (i in 1..3) c =
            Reducer.event(
                c,
                packet(
                    i.toLong(),
                    if (i == 3) "tool_execution_end" else "tool_execution_update",
                    JSONObject().put("toolCallId", "call").put("toolName", "bash"),
                ),
            )
        assertEquals(1, c.entries.size)
        assertEquals("tool_execution_end", c.entries[0].getString("toolEvent"))
    }

    @Test
    fun toolEndPreservesArgumentsAndDuration() {
        val start =
            JSONObject().put("toolCallId", "call").put("args", JSONObject().put("command", "true"))
        val c = Reducer.event(Conversation(), packet(1, "tool_execution_start", start))
        val ended =
            Reducer.event(
                c,
                packet(
                    2,
                    "tool_execution_end",
                    JSONObject().put("toolCallId", "call").put("durationMs", 123),
                ),
            )
        assertEquals("true", ended.entries.single().getJSONObject("args").getString("command"))
        assertEquals(123, ended.entries.single().getInt("durationMs"))
    }

    @Test
    fun lateHistoryDoesNotMoveEventWatermarkBackwards() {
        val c = Conversation(seq = 10, entries = listOf(JSONObject().put("entryId", "live")))
        val h = JSONObject().put("seq", 5).put("reset", true)
            .put("entries", JSONArray().put(JSONObject().put("entryId", "past")))
        val n = Reducer.history(c, h)
        assertEquals(10, n.seq)
        assertEquals(setOf("live", "past"), n.entries.map { it.optString("entryId") }.toSet())
    }

    @Test
    fun lateSnapshotCannotReplaceNewerMessageOrLeaf() {
        val c = Conversation(seq = 10, leaf = "latest", entries = listOf(
            JSONObject().put("entryId", "latest").put("message", msg("assistant", "new content", 1))))
        val h = JSONObject().put("seq", 5).put("leafId", "stale").put("reset", true)
            .put("entries", JSONArray().put(JSONObject().put("entryId", "stale")
                .put("message", msg("assistant", "old content", 1))))
        val n = Reducer.history(c, h)
        assertEquals("latest", n.leaf); assertEquals(10, n.seq)
        assertEquals("new content", n.entries.single().getJSONObject("message").textContent())
    }

    @Test
    fun newRuntimeResetsConversationWatermarkWithoutDroppingPending() {
        val pending = JSONObject().put("clientMsgId", "mobile")
        val c = Conversation(seq = 500, sessionId = "s", instanceId = "old", pending = listOf(pending),
            entries = listOf(JSONObject().put("entryId", "old-branch")))
        val h = JSONObject().put("seq", 0).put("reset", true).put("info", JSONObject().put("sessionId", "s").put("instanceId", "new"))
            .put("entries", JSONArray().put(JSONObject().put("entryId", "current")))
        val n = Reducer.history(c, h)
        assertEquals(0, n.seq); assertEquals("new", n.instanceId)
        assertEquals(listOf("current"), n.entries.map { it.optString("entryId") })
        assertEquals(listOf(pending), n.pending)
    }

    @Test
    fun runtimeChangeDuringIncrementalRequestKeepsSameSessionHistory() {
        val c = Conversation(seq = 500, sessionId = "s", instanceId = "old",
            entries = listOf(JSONObject().put("entryId", "cached")), leaf = "cached")
        val h = JSONObject().put("seq", 0).put("reset", false)
            .put("info", JSONObject().put("sessionId", "s").put("instanceId", "new"))
            .put("entries", JSONArray()).put("leafId", "cached")
        val n = Reducer.history(c, h)
        assertEquals(0, n.seq); assertEquals("new", n.instanceId)
        assertEquals(listOf("cached"), n.entries.map { it.optString("entryId") })
    }

    @Test
    fun sameRuntimeRetainsWatermarkAndHistoryCanReconcilePending() {
        val c = Conversation(seq = 10, sessionId = "s", instanceId = "a",
            pending = listOf(JSONObject().put("clientMsgId", "mobile")))
        val h = JSONObject().put("seq", 11).put("info", JSONObject().put("sessionId", "s").put("instanceId", "a"))
            .put("entries", JSONArray().put(JSONObject().put("entryId", "e").put("clientMsgId", "mobile")))
        val n = Reducer.history(c, h)
        assertEquals(11, n.seq); assertTrue(n.pending.isEmpty())
        assertEquals("s", n.sessionId); assertEquals("a", n.instanceId)
    }

    @Test
    fun legacyCacheStateCannotEnableAbortOrOverrideDone() {
        val legacy = Conversation.from("{\"entries\":[],\"state\":\"working\",\"seq\":99}")
        val session = JSONObject().put("sessionKey", "p").put("bridge", true).put("state", "done")
        val state = BridgeState(connected = true, sessions = listOf(session), chats = mapOf("p" to legacy))
        assertEquals("done", state.sessionUi("p").phase)
        assertFalse(state.sessionUi("p").canAbort)
    }

    @Test
    fun cacheRoundtrip() {
        val c =
            Conversation(
                entries = listOf(JSONObject().put("entryId", "e").put("text", "日本語\u2029")),
                leaf = "e",
                seq = 9,
                sessionId = "s",
                instanceId = "runtime",
            )
        val n = Conversation.from(c.json())
        assertEquals(c.json(), n.json())
    }
}

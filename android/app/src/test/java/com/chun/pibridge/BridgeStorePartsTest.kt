package com.chun.pibridge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Pure pieces extracted from BridgeStore: reconnect ordering, session deltas and pending drafts. */
class BridgeStorePartsTest {
    private fun session(key: String, name: String = key) = JSONObject().put("sessionKey", key).put("sessionName", name)
    private fun array(vararg items: JSONObject) = JSONArray().also { a -> items.forEach(a::put) }

    @Test fun singleCandidateBacksOffExponentiallyAndCapsAtSixtySeconds() {
        val policy = ReconnectPolicy(1)
        assertEquals(listOf(1, 2, 4, 8, 16, 32, 60, 60, 60), List(9) { policy.failed(established = false) })
        assertEquals(0, policy.candidate)
    }

    @Test fun multipleCandidatesTryTheNextOneImmediatelyBeforeBackingOff() {
        val policy = ReconnectPolicy(3)
        assertEquals(0, policy.failed(false)); assertEquals(1, policy.candidate)
        assertEquals(0, policy.failed(false)); assertEquals(2, policy.candidate)
        assertEquals(1, policy.failed(false)); assertEquals(0, policy.candidate)
        assertEquals(0, policy.failed(false)); assertEquals(1, policy.candidate)
    }

    @Test fun establishedConnectionRestartsFromTheFirstCandidateWithFullBackoff() {
        val policy = ReconnectPolicy(2)
        assertEquals(0, policy.failed(false)); assertEquals(1, policy.failed(false))
        assertEquals(0, policy.failed(false)); assertEquals(2, policy.failed(false))
        policy.connected()
        assertEquals(0, policy.retry)
        assertEquals(1, policy.failed(true)); assertEquals(0, policy.candidate)
    }

    @Test fun deltaReplacesInPlaceAppendsAndRemovesWhileKeepingOrder() {
        val list = listOf(session("a"), session("b"), session("c"))
        val merged = list.mergeSessionDelta(
            added = array(session("d")),
            updated = array(session("b").put("sessionName", "B2")),
            removed = array().put("c"),
        )
        assertEquals(listOf("a", "b", "d"), merged.keys.toList())
        assertEquals("B2", merged["b"]!!.optString("sessionName"))
    }

    @Test fun deltaWithMissingArraysKeepsTheListUnchanged() {
        val list = listOf(session("a"), session("b"))
        assertEquals(listOf("a", "b"), list.mergeSessionDelta(null, null, null).keys.toList())
    }

    @Test fun removingAnUnknownKeyIsIgnoredAndRemovingAllEmptiesTheList() {
        val one = listOf(session("a"))
        assertEquals(listOf("a"), one.mergeSessionDelta(null, null, array().put("zz")).keys.toList())
        assertTrue(one.mergeSessionDelta(null, null, array().put("a")).isEmpty())
    }

    @Test fun pendingStatusOnlyChangesTheMatchingDraftAndDoesNotMutateTheOriginal() {
        val kept = JSONObject().put("clientMsgId", "k").put("status", "送信中")
        val target = JSONObject().put("clientMsgId", "t").put("status", "送信中")
        val chat = Conversation(entries = listOf(session("e")), pending = listOf(kept, target))
        val updated = chat.withPendingStatus("t", "受付済み")
        assertEquals("送信中", updated.pending[0].optString("status"))
        assertEquals("受付済み", updated.pending[1].optString("status"))
        assertEquals("送信中", target.optString("status"))
        assertSame(chat.entries, updated.entries)
        assertEquals(chat.leaf, updated.leaf)
    }
}

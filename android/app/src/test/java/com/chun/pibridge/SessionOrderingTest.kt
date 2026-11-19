package com.chun.pibridge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionOrderingTest {
    private fun session(id: String, workspace: String, name: String, time: Long = 0,
                        state: String = "idle", bridge: Boolean = true) =
        JSONObject().put("sessionKey", id).put("workspace", workspace)
            .put("sessionName", name).put("lastActivityAt", time)
            .put("state", state).put("bridge", bridge)

    @Test
    fun defaultGroupsWorkspacesAndPrioritizesBlockedThenChatThenName() {
        val entries = listOf(session("b", "B", "A"), session("z", "A", "Z", state = "blocked"),
            session("shell", "A", "A", bridge = false), session("a", "A", "A"))
        val groups = sessionGroups(entries, SessionSort.DEFAULT)
        assertEquals(listOf("A", "B"), groups.map { it.workspace })
        assertEquals(listOf("z", "a", "shell"), groups.first().sessions.map { it.optString("sessionKey") })
    }

    @Test
    fun recentOrderCrossesWorkspacesWithoutHeadersOrBlockedPriority() {
        val entries = listOf(session("old", "A", "A", 10, state = "blocked"),
            session("new", "B", "B", 30), session("middle", "A", "C", 20))
        val groups = sessionGroups(entries, SessionSort.RECENT)
        assertEquals(1, groups.size)
        assertNull(groups.single().workspace)
        assertEquals(listOf("new", "middle", "old"), groups.single().sessions.map { it.optString("sessionKey") })
    }

    @Test
    fun locallyOpenedSessionUsesNewerOfOpenAndPcActivity() {
        val entries = listOf(session("opened", "A", "A", 10), session("pc", "B", "B", 50))
        val ordered = sessionGroups(entries, SessionSort.RECENT) {
            if (it.optString("sessionKey") == "opened") 100 else 0
        }.single().sessions
        assertEquals("opened", ordered.first().optString("sessionKey"))
        assertEquals("pc", sessionGroups(entries, SessionSort.RECENT).single().sessions.first().optString("sessionKey"))
    }

    @Test
    fun unknownTimesStayAtBottomAndTiesAreDeterministic() {
        val entries = listOf(session("z", "A", "same", -1), session("recent", "B", "Z", 5),
            session("a", "A", "same", 0))
        val ordered = sessionGroups(entries, SessionSort.RECENT).single().sessions
        assertEquals(listOf("recent", "a", "z"), ordered.map { it.optString("sessionKey") })
    }

    @Test
    fun nullNamesFallBackToTabWithoutLiteralNull() {
        val entry = JSONObject().put("sessionName", JSONObject.NULL).put("tab", "tab name")
        assertEquals("tab name", sessionTitle(entry))
        assertEquals("セッション", sessionTitle(JSONObject()))
    }

    @Test
    fun usageIsScopedToProfileAndActualSessionIdentity() {
        val entry = session("pane", "A", "name").put("sessionId", "first")
        val first = sessionUsageKey("pc1", entry)
        assertNotEquals(first, sessionUsageKey("pc2", entry))
        entry.put("sessionId", "second")
        assertNotEquals(first, sessionUsageKey("pc1", entry))
        entry.remove("sessionId")
        assertTrue(sessionUsageKey("pc1", entry).contains("pane"))
    }
}

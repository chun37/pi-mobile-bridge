package com.chun.pibridge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionTerminationTest {
    private fun session() = JSONObject("""{"sessionKey":"w1:p1","sessionId":"s1","instanceId":"i1","tabId":"w1:t1","sessionName":"Test","bridge":true}""")
    @Test fun capturesIdentityAndOnlySendsBoundedTarget() {
        val target = SessionTerminationTarget.from("pc1", session())!!
        assertTrue(target.matches(session()))
        assertEquals("Test", target.title)
        assertEquals(setOf("sessionKey", "expectedSessionId", "expectedInstanceId", "expectedTabId"), target.params().keys().asSequence().toSet())
        assertEquals("s1", target.params().getString("expectedSessionId"))
    }
    @Test fun rejectsUnknownAndMalformedIdentities() {
        for (field in listOf("sessionKey", "sessionId", "instanceId", "tabId")) {
            for (value in listOf(JSONObject.NULL, "", "  ", 42)) {
                assertNull(SessionTerminationTarget.from("pc1", session().put(field, value)))
            }
        }
        assertNull(SessionTerminationTarget.from("", session()))
        assertNull(SessionTerminationTarget.from("pc1", session().put("bridge", false)))
    }
    @Test fun rejectsNewSessionReloadMovedPaneOrDisconnectedBridge() {
        val target = SessionTerminationTarget.from("pc1", session())!!
        for (field in listOf("sessionKey", "sessionId", "instanceId", "tabId"))
            assertFalse(target.matches(session().put(field, "new")))
        assertFalse(target.matches(session().put("bridge", false)))
        assertTrue(target.matches(session().put("lastResponse", "new text").put("state", "working")))
    }
}

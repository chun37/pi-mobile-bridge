package com.chun.pibridge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionNotificationTest {
    @Test fun prefersExplicitSessionNameToTabAndPane() {
        val session = JSONObject("""{"sessionName":"通知の修正","tab":"Pi random","sessionKey":"wV:p25","paneId":"wV:p25"}""")
        assertEquals("通知の修正", notificationSessionTitle(session))
    }
    @Test fun unnamedSessionUsesHumanReadableTab() {
        val session = JSONObject("""{"sessionName":" ","tab":"開発","sessionKey":"wV:p25"}""")
        assertEquals("開発", notificationSessionTitle(session))
    }
    @Test fun missingOrMalformedNamesNeverFallBackToPane() {
        for (session in listOf(null, JSONObject(), JSONObject("""{"sessionName":null,"tab":42,"sessionKey":"wV:p25"}"""),
            JSONObject("""{"sessionName":{},"tab":"wV:p25","sessionKey":"wV:p25"}"""),
            JSONObject("""{"tab":"wV:p25","paneId":"wV:p25"}""")))
            assertEquals("名前未設定のセッション", notificationSessionTitle(session))
    }
    @Test fun explicitNameCanContainUnicodeAndColons() {
        assertEquals("調査: 通知🔔", notificationSessionTitle(JSONObject().put("sessionName", " 調査: 通知🔔 ")))
    }
    @Test fun resolvesTheNotifyingSessionNotTheSelectedOrFirstSession() {
        val sessions = listOf(JSONObject().put("sessionKey", "other").put("sessionName", "別の会話"),
            JSONObject().put("sessionKey", "target").put("sessionName", "対象の会話"))
        assertEquals("対象の会話", notificationSessionTitle(sessionForNotification(sessions, "target")))
        assertEquals("名前未設定のセッション", notificationSessionTitle(sessionForNotification(sessions, "gone")))
    }
    @Test fun nextNotificationUsesRenamedMetadataRatherThanCachedTitle() {
        val session = JSONObject().put("sessionKey", "target").put("sessionName", "変更前")
        assertEquals("変更前", notificationSessionTitle(sessionForNotification(listOf(session), "target")))
        val renamed = JSONObject(session.toString()).put("sessionName", "変更後")
        assertEquals("変更後", notificationSessionTitle(sessionForNotification(listOf(renamed), "target")))
    }
}

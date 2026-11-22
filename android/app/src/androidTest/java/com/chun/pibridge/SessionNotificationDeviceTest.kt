package com.chun.pibridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SessionNotificationDeviceTest {
    private fun verify(name: String?, tab: String?, state: String, expected: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue("Notification permission must already be enabled", manager.areNotificationsEnabled())
        val channel = "notification-name-test-${UUID.randomUUID()}"
        val tag = channel
        val id = 108
        manager.createNotificationChannel(NotificationChannel(channel, "通知名の試験", NotificationManager.IMPORTANCE_LOW))
        try {
            val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val session = JSONObject().put("sessionKey", "wV:p-test-not-owned").put("paneId", "wV:p-test-not-owned")
                .put("sessionName", name ?: JSONObject.NULL).put("tab", tab ?: JSONObject.NULL)
            val notification = agentNotification(context, session, state, intent, channel)
            manager.notify(tag, id, notification)
            val deadline = System.currentTimeMillis() + 3000
            var posted: Notification? = null
            while (posted == null && System.currentTimeMillis() < deadline) {
                posted = manager.activeNotifications.firstOrNull { it.tag == tag && it.id == id }?.notification
                if (posted == null) Thread.sleep(50)
            }
            assertNotNull("Owned test notification was not posted", posted)
            assertEquals(expected, posted!!.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals(if (state == "blocked") "ターミナルで応答してください" else "応答が完了しました", posted!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertFalse(posted!!.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("wV:p-test"))
            assertNotNull(posted!!.contentIntent)
            assertTrue(posted!!.flags and Notification.FLAG_AUTO_CANCEL != 0)
        } finally {
            manager.cancel(tag, id)
            manager.deleteNotificationChannel(channel)
        }
    }
    @Test fun completedNotificationUsesSessionName() = verify("通知の修正🔔", "Pi random", "done", "通知の修正🔔")
    @Test fun blockedNotificationUsesSessionNameAndPreservesActionText() = verify("調査中の会話", "別のタブ名", "blocked", "調査中の会話")
    @Test fun unnamedSessionUsesTabNotPane() = verify(null, "開発タブ", "done", "開発タブ")
    @Test fun missingMetadataHasReadableFallback() = verify(null, null, "done", "名前未設定のセッション")
}

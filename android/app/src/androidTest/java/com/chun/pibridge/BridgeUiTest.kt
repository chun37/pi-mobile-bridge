package com.chun.pibridge

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BridgeUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val store
        get() = BridgeStore.get(ui.activity)

    private val pane
        get() =
            InstrumentationRegistry.getArguments().getString("pane")
                ?: error("Pass an owned fixture pane")

    private fun waitUntil(timeout: Long = 20000, p: () -> Boolean) =
        ui.waitUntil(timeoutMillis = timeout, p)

    private fun screenshot(name: String) {
        ui.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(ui.activity.filesDir, name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test
    fun blockedStateProducesLocalNotification() {
        ui.runOnUiThread {
            ui.activity.startForegroundService(
                Intent(ui.activity, BridgeService::class.java)
                    .putExtra("profileId", "verification-pc")
            )
        }
        waitUntil { store.state.value.connected }
        ui.runOnUiThread { store.select(pane) }
        waitUntil { store.state.value.chats[pane]?.leaf?.isNotEmpty() == true }
        runBlocking {
            store.request(
                "terminal.input",
                JSONObject()
                    .put("paneId", pane)
                    .put("text", "/bridge-test-confirm")
                    .put("keys", org.json.JSONArray(listOf("enter"))),
            )
        }
        waitUntil {
            store.state.value.sessions.any {
                it.optString("sessionKey") == pane && it.optString("state") == "blocked"
            }
        }
        val nm = ui.activity.getSystemService(android.app.NotificationManager::class.java)
        waitUntil {
            nm.activeNotifications.any {
                it.id == pane.hashCode() && it.notification.channelId == "agent"
            }
        }
        runBlocking {
            store.request(
                "terminal.keys",
                JSONObject().put("paneId", pane).put("keys", org.json.JSONArray(listOf("enter"))),
            )
        }
        waitUntil { !store.state.value.sessionUi(pane).blocked }
        ui.runOnUiThread {
            ui.activity.stopService(Intent(ui.activity, BridgeService::class.java))
            store.disconnect()
        }
    }

    @Test
    fun realUiSendTerminalAndOfflineCache() {
        val sentText = "UI_DEVICE_SEND task-file ${System.currentTimeMillis()}"
        val pcText = "UI_TERMINAL_PC ${System.currentTimeMillis()}"
        ui.runOnUiThread {
            ui.activity.startForegroundService(
                Intent(ui.activity, BridgeService::class.java)
                    .putExtra("profileId", "verification-pc")
            )
        }
        waitUntil {
            store.state.value.connected &&
                store.state.value.sessions.any { it.optString("sessionKey") == pane }
        }
        if (store.state.value.selected.isNotEmpty())
            ui.onNodeWithTag("backToSessions").performClick()
        ui.onNodeWithTag("sessions").performScrollToNode(hasTestTag("session:$pane"))
        screenshot("sessions-render.png")
        ui.onNodeWithTag("session:$pane").performClick()
        waitUntil { store.state.value.chats[pane]?.entries?.isNotEmpty() == true }
        ui.onNodeWithTag("messageInput").performTextInput(sentText)
        ui.onNodeWithTag("send").performClick()
        waitUntil {
            store.state.value.chats[pane]?.entries?.any {
                it.optJSONObject("message")?.optString("role") == "assistant" &&
                    it.optJSONObject("message")?.textContent()?.contains(sentText) == true &&
                    !it.optBoolean("streaming")
            } == true
        }
        assertTrue(
            store.state.value.chats[pane]!!.entries.any {
                it.optString("origin") == "mobile" &&
                    it.optJSONObject("message")?.textContent() == sentText
            }
        )
        screenshot("chat-render.png")
        ui.onNodeWithTag("chat").performScrollToNode(hasText("ツール: write", substring = true))
        ui.onAllNodesWithText("ツール: write", substring = true).onFirst().performClick()
        screenshot("tool-render.png")
        ui.onNodeWithText("ターミナル", useUnmergedTree = true).performClick()
        waitUntil { store.state.value.terminal.contains(sentText) }
        screenshot("terminal-render.png")
        ui.onNodeWithTag("terminalInput").performTextInput(pcText)
        ui.onNodeWithText("Enter付きで入力").performClick()
        waitUntil {
            store.state.value.chats[pane]?.entries?.any {
                it.optString("origin") == "pc" &&
                    it.optJSONObject("message")?.textContent() == pcText
            } == true
        }
        ui.onNodeWithText("チャット", useUnmergedTree = true).performClick()
        ui.runOnUiThread { store.disconnect() }
        ui.onNodeWithTag("messageInput").assertExists()
        assertTrue(store.state.value.chats[pane]!!.entries.isNotEmpty())
        java.io
            .File(ui.activity.filesDir, "ui-state-proof.json")
            .writeText(store.state.value.chats[pane]!!.json())
    }

    @Test
    fun reconnectRetainsHistoryAndLoadsOfflineCache() {
        ui.runOnUiThread {
            store.connect(Profiles(ui.activity).all().first { it.id == "verification-pc" })
        }
        waitUntil { store.state.value.connected }
        ui.runOnUiThread { store.select(pane) }
        waitUntil { store.state.value.chats[pane]?.leaf?.isNotEmpty() == true }
        val before = store.state.value.chats[pane]!!.entries.size
        Thread.sleep(1200)
        val field = BridgeStore::class.java.getDeclaredField("client").apply { isAccessible = true }
        val old = field.get(store) as BridgeClient
        old.close() // Fault injection closes a real SSH channel; production has no debug RPC.
        waitUntil(30000) { store.state.value.connected && field.get(store) !== old }
        waitUntil { (store.state.value.chats[pane]?.entries?.size ?: 0) >= before }
        val rows = store.state.value.chats[pane]!!.entries
        assertEquals(rows.size, rows.map { it.optString("entryId") }.distinct().size)
        ui.runOnUiThread {
            store.connect(
                Profiles(ui.activity).all().first { it.id == "verification-pc" }.copy(port = 1)
            )
        }
        waitUntil { (store.state.value.chats[pane]?.entries?.size ?: 0) >= before }
        assertFalse(store.state.value.connected)
        ui.runOnUiThread { store.disconnect() }
    }

    @Test
    fun busyDeliveryAbortAndImages() = runBlocking {
        val profile = Profiles(ui.activity).all().first { it.id == "verification-pc" }
        val c = BridgeClient()
        c.connect(profile, Identity(ui.activity).privatePem())
        val events = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        c.events = { events.add(it) }
        val reader = launch(Dispatchers.IO) { runCatching { c.readLoop() } }
        try {
            c.request("session.open", JSONObject().put("sessionKey", pane))
            fun params(text: String, delivery: String = "followUp") =
                JSONObject()
                    .put("sessionKey", pane)
                    .put("text", text)
                    .put("clientMsgId", java.util.UUID.randomUUID().toString())
                    .put("deliverAs", delivery)
            c.request("session.send", params("slow DELIVERY_START " + "abcdefghij".repeat(12)))
            withTimeout(10000) {
                while (events.none { it.optString("kind") == "message_update" }) delay(50)
            }
            val steer = c.request("session.send", params("STEER_DEVICE_MESSAGE", "steer"))
            assertEquals("queued", steer.getString("status"))
            val follow = c.request("session.send", params("FOLLOWUP_DEVICE_MESSAGE"))
            assertEquals("queued", follow.getString("status"))
            withTimeout(60000) {
                while (events.none { it.optString("kind") == "agent_settled" }) delay(100)
            }
            val history = c.request("session.open", JSONObject().put("sessionKey", pane))
            val users =
                history.getJSONArray("entries").objects().filter {
                    it.optJSONObject("message")?.optString("role") == "user"
                }
            assertTrue(
                users.any {
                    it.optJSONObject("message")?.textContent() == "STEER_DEVICE_MESSAGE" &&
                        it.optString("origin") == "mobile"
                }
            )
            assertTrue(
                users.any {
                    it.optJSONObject("message")?.textContent() == "FOLLOWUP_DEVICE_MESSAGE" &&
                        it.optString("origin") == "mobile"
                }
            )
            events.clear()
            val bitmap =
                android.graphics.Bitmap.createBitmap(
                        32,
                        32,
                        android.graphics.Bitmap.Config.ARGB_8888,
                    )
                    .apply { eraseColor(android.graphics.Color.GREEN) }
            val encoded =
                java.io
                    .ByteArrayOutputStream()
                    .also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    .toByteArray()
            val image = android.util.Base64.encodeToString(encoded, android.util.Base64.NO_WRAP)
            c.request(
                "session.send",
                params("IMAGE_DEVICE_MESSAGE")
                    .put(
                        "images",
                        org.json
                            .JSONArray()
                            .put(JSONObject().put("mimeType", "image/png").put("data", image)),
                    ),
            )
            withTimeout(15000) {
                while (events.none { it.optString("kind") == "agent_settled" }) delay(50)
            }
            val imHistory = c.request("session.open", JSONObject().put("sessionKey", pane))
            val imageMessage =
                imHistory
                    .getJSONArray("entries")
                    .objects()
                    .last { it.optJSONObject("message")?.optString("role") == "user" }
                    .getJSONObject("message")
            assertTrue(
                imageMessage.optJSONArray("content").objects().any {
                    it.optString("type") == "image" &&
                        it.optString("mimeType").startsWith("image/") &&
                        it.optString("data").isNotEmpty()
                }
            )
            events.clear()
            c.request(
                "session.send",
                params("slow ABORT_DEVICE_MESSAGE " + "abcdefghijkl".repeat(20)),
            )
            withTimeout(10000) {
                while (events.none { it.optString("kind") == "message_update" }) delay(50)
            }
            assertTrue(
                c.request("session.abort", JSONObject().put("sessionKey", pane))
                    .getBoolean("aborted")
            )
            withTimeout(15000) {
                while (events.none { it.optString("kind") == "agent_settled" }) delay(50)
            }
            assertTrue(
                events
                    .last { it.optString("kind") == "agent_settled" }
                    .getJSONObject("payload")
                    .getBoolean("aborted")
            )
        } finally {
            c.close()
            reader.cancelAndJoin()
        }
    }
}

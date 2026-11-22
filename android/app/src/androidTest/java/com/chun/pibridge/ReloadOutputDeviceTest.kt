package com.chun.pibridge

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReloadOutputDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun mobileReloadDisplaysRealPcResultWithoutAddingChatMessages() {
        val args = InstrumentationRegistry.getArguments()
        val pane = args.getString("fixturePane") ?: error("Pass the owned local fixturePane")
        val sid = args.getString("fixtureSessionId") ?: error("Pass the exact fixtureSessionId")
        val profileId = args.getString("profileId") ?: error("Pass an owned profileId")
        val store = BridgeStore.get(ui.activity)
        val profile = store.profiles.all().first { it.id == profileId }
        ui.runOnUiThread { if (!store.isConnectingTo(profile)) store.connect(profile) }
        ui.waitUntil(30000) { store.state.value.connected && store.state.value.sessions.any { it.optString("sessionKey") == pane } }
        val session = store.state.value.sessions.first { it.optString("sessionKey") == pane }
        assertEquals(sid, session.optString("sessionId"))
        assertEquals("bridge-fixture", session.optString("model"))
        assertEquals("bridge-reload-verification", session.optString("sessionName"))
        ui.runOnUiThread { store.select(pane) }
        ui.waitUntil(15000) { store.state.value.selected == pane && store.state.value.slashCatalog != null }
        val before = store.state.value.chats[pane]?.entries.orEmpty().count { it.optJSONObject("message")?.optString("role") == "user" }
        val nativeInput = args.getString("nativeInput") == "true"
        if (nativeInput) {
            // Owned fixture only: exercise the same terminal entry as PC /reload,
            // rather than the bridge's SDK command dispatch.
            runBlocking { store.request("terminal.input", JSONObject().put("paneId", pane)
                .put("text", "/reload").put("keys", JSONArray().put("enter"))) }
        } else {
            ui.onNodeWithTag("messageInput").performTextInput("/reload")
            ui.onNodeWithTag("send").performClick()
        }
        ui.waitUntil(25000) { store.state.value.reloadOutputs[pane]?.phase == "complete" }
        val result = store.state.value.reloadOutputs[pane]!!
        assertEquals(sid, result.sessionId)
        assertTrue(result.text.contains("Reloaded keybindings,"))
        assertTrue(result.text.contains("[Extensions]"))
        assertTrue(result.text.contains("[Context]"))
        ui.onNodeWithTag("reloadOutput").assertIsDisplayed()
        ui.onNodeWithText("再読み込み完了").assertIsDisplayed()
        val after = store.state.value.chats[pane]?.entries.orEmpty().count { it.optJSONObject("message")?.optString("role") == "user" }
        assertEquals(before, after)
        File(ui.activity.filesDir, if (nativeInput) "reload-native-proof.json" else "reload-live-proof.json").writeText(JSONObject()
            .put("sessionId", sid).put("phase", result.phase).put("text", result.text)
            .put("nativeInput", nativeInput).put("userMessagesBefore", before).put("userMessagesAfter", after).toString())
        ui.onNodeWithText("閉じる", useUnmergedTree = true).performClick()
        ui.onNodeWithTag("reloadOutput").assertDoesNotExist()
        ui.runOnUiThread { store.select("") }
    }
}

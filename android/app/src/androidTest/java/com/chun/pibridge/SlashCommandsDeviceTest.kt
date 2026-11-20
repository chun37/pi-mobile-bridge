package com.chun.pibridge

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Only accepts an explicitly selected isolated local-model fixture, never a user conversation. */
@RunWith(AndroidJUnit4::class)
class SlashCommandsDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun actualFixtureCommandAndModelCompletionWithLiveUsage() {
        val args = InstrumentationRegistry.getArguments()
        val pane = args.getString("fixturePane") ?: error("Pass the owned local fixturePane")
        val sessionId = args.getString("fixtureSessionId") ?: error("Pass the exact fixtureSessionId")
        val profileId = args.getString("profileId") ?: error("Pass the owned profileId")
        val store = BridgeStore.get(ui.activity)
        val profile = store.profiles.all().first { it.id == profileId }
        ui.runOnUiThread { if (!store.isConnectingTo(profile)) store.connect(profile) }
        ui.waitUntil(30000) { store.state.value.connected && store.state.value.sessions.any { it.optString("sessionKey") == pane } }
        val session = store.state.value.sessions.first { it.optString("sessionKey") == pane }
        assertEquals(sessionId, session.optString("sessionId"))
        assertEquals("bridge-fixture", session.optString("model"))
        assertEquals("bridge-slash-verification", session.optString("sessionName"))
        assertTrue(supportsSlash(session))
        ui.runOnUiThread { store.select(pane) }
        ui.waitUntil(15000) { store.state.value.chats[pane]?.entries?.isNotEmpty() == true }
        ui.onNodeWithTag("contextUsage").assertIsDisplayed().assertTextEquals(contextPercent(session))
        val countBefore = store.state.value.chats[pane]!!.entries.count { it.optJSONObject("message")?.optString("role") == "user" }

        ui.onNodeWithTag("messageInput").performTextInput("/bridge-test-sl")
        ui.waitUntil(15000) { ui.onAllNodesWithTag("slash:bridge-test-slash").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("slash:bridge-test-slash").assertIsDisplayed().performClick()
        ui.onNodeWithTag("messageInput").assertTextContains("/bridge-test-slash ")
        ui.onNodeWithTag("messageInput").performTextInput("android-args")
        ui.onNodeWithTag("send").performClick()
        ui.waitUntil(15000) { store.state.value.sessions.any {
            it.optString("sessionKey") == pane && it.optString("sessionName") == "bridge-slash-command-ok" } }
        assertTrue(store.state.value.chats[pane]!!.pending.isEmpty())

        ui.onNodeWithTag("messageInput").performTextInput("/model ")
        ui.onNodeWithTag("slash:bridge-fixture/bridge-fixture").assertIsDisplayed().performClick()
        ui.onNodeWithTag("messageInput").assertTextContains("/model bridge-fixture/bridge-fixture ")
        ui.onNodeWithTag("send").performClick()
        ui.waitUntil(15000) { store.state.value.commandNotice.contains("モデルを") }
        val countAfter = store.state.value.chats[pane]!!.entries.count { it.optJSONObject("message")?.optString("role") == "user" }
        assertEquals(countBefore, countAfter)
        File(ui.activity.filesDir, "slash-live-proof.json").writeText(JSONObject()
            .put("fixturePane", pane).put("sessionId", sessionId).put("userMessagesBefore", countBefore)
            .put("userMessagesAfter", countAfter).put("percent", contextPercent(session)).toString())
        ui.runOnUiThread { store.select("") }
    }
}

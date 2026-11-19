package com.chun.pibridge

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Creates one fresh Pi tab, never sends a model prompt, never resets an existing session. */
@RunWith(AndroidJUnit4::class)
class NewSessionDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun plusCreatesNewTabAndOpensItsBridge() {
        val args = InstrumentationRegistry.getArguments()
        val profileId = args.getString("profileId") ?: error("Pass an owned profileId")
        val workspaceId = args.getString("workspaceId") ?: error("Pass an explicit workspaceId")
        val store = BridgeStore.get(ui.activity)
        ui.runOnUiThread {
            store.connect(store.profiles.all().first { it.id == profileId })
            store.select("")
        }
        ui.waitUntil(30000) { store.state.value.connected && store.state.value.sessions.isNotEmpty() }
        val before = store.state.value.sessions.map { it.optString("sessionKey") }.toSet()
        ui.onNodeWithTag("newSession").assertIsDisplayed().performClick()
        ui.waitUntil(15000) {
            ui.onAllNodesWithTag("workspace:$workspaceId").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithTag("workspace:$workspaceId").performClick()
        ui.onNodeWithTag("createSessionConfirm").performClick()
        ui.waitUntil(75000) { store.state.value.selected.isNotEmpty() }
        val pane = store.state.value.selected
        val created = store.state.value.sessions.first { it.optString("sessionKey") == pane }
        // Write cleanup handles before assertions, without any conversation text.
        java.io.File(ui.activity.filesDir, "new-session-proof.json").writeText(
            JSONObject().put("sessionKey", pane).put("tabId", created.optString("tabId"))
                .put("workspaceId", created.optString("workspaceId"))
                .put("bridge", created.optBoolean("bridge"))
                .put("model", created.optString("model")).toString()
        )
        assertFalse(before.contains(pane))
        assertEquals(workspaceId, created.optString("workspaceId"))
        assertTrue(created.optString("tabId").isNotEmpty())
        assertTrue(created.optBoolean("bridge"))
        assertNotEquals("bridge-fixture", created.optString("model"))
        assertTrue(store.state.value.sessions.map { it.optString("sessionKey") }.containsAll(before))
        ui.onNodeWithTag("messageInput").assertExists()
        ui.onNodeWithTag("newSession").assertDoesNotExist()
        ui.runOnUiThread { store.select("") }
        ui.onNodeWithTag("newSession").assertExists()
    }
}

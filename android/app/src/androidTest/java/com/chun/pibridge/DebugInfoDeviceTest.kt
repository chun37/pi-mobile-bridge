package com.chun.pibridge

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses a new owned Pi, no model prompts, no reload of existing conversations. */
@RunWith(AndroidJUnit4::class)
class DebugInfoDeviceTest {
    @get:Rule val ui = createComposeRule()
    @Test fun chatLongPressDisplaysRunningPeerVersionsAndNormalTapStillOpensTerminal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val profile = args.getString("profileId") ?: error("Pass an explicit profileId")
        val workspace = args.getString("workspaceId") ?: error("Pass an explicit workspaceId")
        val store = BridgeStore.get(context)
        ui.setContent { MaterialTheme {
            val state by store.state.collectAsState()
            Column(Modifier.fillMaxSize().padding(top = 48.dp)) {
                if (state.selected.isNotEmpty()) ChatScreen(store, state)
            }
        } }
        ui.runOnIdle { store.connect(store.profiles.all().first { it.id == profile }); store.select("") }
        ui.waitUntil(30000) { store.state.value.connected && supportsSessionDebug(store.state.value.connectionInfo) }
        val before = store.state.value.sessions.map { it.optString("sessionKey") }.toSet()
        var proof: JSONObject? = null
        var owned: SessionTerminationTarget? = null
        var failure: Throwable? = null
        val proofFile = File(context.filesDir, "debug-info-device-proof.json")
        ui.runOnIdle { store.scope.launch {
            try {
                store.createSession(workspace, UUID.randomUUID().toString())
                val session = store.state.value.sessions.first { it.optString("sessionKey") == store.state.value.selected }
                owned = SessionTerminationTarget.from(profile, session)!!
                proofFile.writeText(JSONObject().put("createdPane", owned!!.sessionKey).put("createdTab", owned!!.tabId).toString())
                assertFalse(before.contains(owned!!.sessionKey))
                proof = store.sessionDebug(owned!!.sessionKey, owned!!.sessionId, owned!!.instanceId)
            } catch (e: Throwable) { failure = e }
        } }
        ui.waitUntil(80000) { proof != null || failure != null }
        failure?.let { throw it }
        try {
            val runtime = proof!!.getJSONObject("bridge").getJSONObject("runtime")
            assertTrue(runtime.getString("piVersion").matches(Regex("\\d+\\.\\d+\\.\\d+.*")))
            assertTrue(runtime.getString("nodeVersion").startsWith("v"))
            assertEquals("0.3.2", proof!!.getJSONObject("bridge").getString("bridgeVersion"))
            assertEquals("0.2.0", proof!!.getJSONObject("relay").getString("version"))
            ui.onNodeWithTag("messageInput").assertIsDisplayed()
            ui.onNodeWithTag("terminalToggle").performTouchInput { longClick() }
            ui.onNodeWithTag("debugScreen").assertIsDisplayed()
            ui.waitUntil(15000) { ui.onAllNodesWithTag("debugLoading").fetchSemanticsNodes().isEmpty() }
            ui.onNodeWithTag("debugRow:選択中のPi（実行中）:Pi").performScrollTo()
                .assertTextEquals("Pi: ${runtime.getString("piVersion")}")
            ui.onNodeWithTag("debugClose").performClick()
            ui.onNodeWithTag("messageInput").assertIsDisplayed()
            ui.onNodeWithTag("terminalToggle").performClick()
            ui.onNodeWithTag("terminalInput").assertIsDisplayed()
            ui.onNodeWithTag("terminalToggle").performClick()
            ui.onNodeWithTag("messageInput").assertIsDisplayed()
            val userMessages = store.state.value.chats[owned!!.sessionKey]?.entries?.count {
                it.optJSONObject("message")?.optString("role") == "user"
            } ?: 0
            assertEquals(0, userMessages)
            proofFile.writeText(proof!!.put("createdPane", owned!!.sessionKey).put("createdTab", owned!!.tabId)
                .put("longPressVerified", true).put("normalTapVerified", true).put("userMessages", userMessages).toString())
        } finally {
            var cleanupDone = false
            ui.runOnIdle { store.scope.launch {
                try { store.terminateSession(owned!!)
                } catch (e: Throwable) { failure = e
                } finally { store.select(""); cleanupDone = true }
            } }
            ui.waitUntil(20000) { cleanupDone }
            failure?.let { throw it }
        }
    }
}

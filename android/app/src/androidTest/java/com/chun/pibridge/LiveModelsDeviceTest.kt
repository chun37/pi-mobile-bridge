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

/** Creates and closes only its own Pi. Selects a configured GLM; never sends a model prompt. */
@RunWith(AndroidJUnit4::class)
class LiveModelsDeviceTest {
    @get:Rule val ui = createComposeRule()
    @Test fun actualChatRefreshesAndSelectsConfiguredGlmWithoutReloadOrPrompt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val profile = args.getString("profileId") ?: error("Pass explicit profileId")
        val workspace = args.getString("workspaceId") ?: error("Pass explicit workspaceId")
        val store = BridgeStore.get(context)
        ui.setContent { MaterialTheme {
            val state by store.state.collectAsState()
            Column(Modifier.fillMaxSize().padding(top = 48.dp)) {
                if (state.selected.isNotEmpty()) ChatScreen(store, state)
            }
        } }
        ui.runOnIdle { store.connect(store.profiles.all().first { it.id == profile }); store.select("") }
        ui.waitUntil(30000) { store.state.value.connected }
        val existing = store.state.value.sessions.map { it.optString("sessionKey") }.toSet()
        var owned: SessionTerminationTarget? = null
        var failure: Throwable? = null
        var ready = false
        val proofFile = File(context.filesDir, "live-models-device-proof.json")
        try {
            ui.runOnIdle { store.scope.launch {
                try {
                    store.createSession(workspace, UUID.randomUUID().toString())
                    val session = store.state.value.sessions.first { it.optString("sessionKey") == store.state.value.selected }
                    owned = SessionTerminationTarget.from(profile, session)!!
                    proofFile.writeText(JSONObject().put("createdPane",owned!!.sessionKey).put("createdTab",owned!!.tabId).toString())
                    assertFalse(existing.contains(owned!!.sessionKey))
                } catch (e: Throwable) { failure = e } finally { ready = true }
            } }
            ui.waitUntil(80000) { ready }; failure?.let { throw it }
            val target = owned!!
            ui.waitUntil(20000) { !store.state.value.slashLoading && store.state.value.slashCatalog != null }
            val candidates = store.state.value.slashCatalog!!.getJSONArray("models")
            val glm = (0 until candidates.length()).map { candidates.getJSONObject(it) }.firstOrNull {
                it.optString("value").startsWith("zai") && it.optString("value").contains("glm",ignoreCase=true)
            } ?: error("No configured z.ai GLM candidate; do not infer credential validity")
            val value = glm.getString("value")
            ui.onNodeWithTag("messageInput").performTextInput("/model glm")
            ui.waitUntil(20000) { !store.state.value.slashLoading && ui.onAllNodesWithTag("slash:$value").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("slashRefresh").assertIsDisplayed().performClick()
            ui.waitUntil(20000) { !store.state.value.slashLoading && ui.onAllNodesWithTag("slash:$value").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("slash:$value").performScrollTo().performClick()
            ui.onNodeWithTag("messageInput").assertTextContains("/model $value ")
            ui.onNodeWithTag("send").performClick()
            ui.waitUntil(20000) { store.state.value.commandNotice.contains("モデルを") &&
                store.state.value.sessions.any { it.optString("sessionKey") == target.sessionKey && it.optString("model") == value.substringAfter('/') } }
            val after = store.state.value.sessions.first { it.optString("sessionKey") == target.sessionKey }
            assertEquals(target.sessionId,after.optString("sessionId")); assertEquals(target.instanceId,after.optString("instanceId"))
            assertEquals(value.substringAfter('/'),after.optString("model"))
            val messages = store.state.value.chats[target.sessionKey]?.entries?.count { it.optJSONObject("message")?.optString("role") == "user" } ?: 0
            assertEquals(0,messages)
            proofFile.writeText(JSONObject().put("createdPane",target.sessionKey).put("createdTab",target.tabId)
                .put("sessionId",target.sessionId).put("instanceId",target.instanceId).put("selectedModel",value)
                .put("explicitRefreshVerified",true).put("reloads",0).put("userMessages",messages).toString())
        } finally {
            var cleaned = false
            ui.runOnIdle { store.scope.launch {
                try { owned?.let { store.terminateSession(it) } }
                catch (e: Throwable) { failure = e }
                finally { store.select(""); cleaned = true }
            } }
            ui.waitUntil(20000) { cleaned }
            failure?.let { throw it }
        }
    }
}

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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Owns a newly created Pi; never closes a supplied or pre-existing pane. No model prompt. */
@RunWith(AndroidJUnit4::class)
class SessionTerminationDeviceTest {
    @get:Rule val ui = createComposeRule()
    @Test fun listLongPressCancelThenConfirmClosesOnlyNewPi() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val profileId = args.getString("profileId") ?: error("Pass an explicit profileId")
        val workspace = args.getString("workspaceId") ?: error("Pass an explicit workspaceId")
        val context = instrumentation.targetContext
        val store = BridgeStore.get(context)
        ui.setContent { MaterialTheme {
            val state by store.state.collectAsState()
            Column(Modifier.fillMaxSize().padding(top = 48.dp)) { SessionList(store, state) }
        } }
        ui.runOnIdle { store.connect(store.profiles.all().first { it.id == profileId }); store.select("") }
        ui.waitUntil(30000) { store.state.value.connected && store.state.value.canTerminateSession && store.state.value.sessions.isNotEmpty() }
        val before = store.state.value.sessions.map { it.optString("sessionKey") }.toSet()
        var failure: Throwable? = null
        var created: JSONObject? = null
        ui.runOnIdle { store.scope.launch {
            try {
                store.createSession(workspace, UUID.randomUUID().toString())
                created = store.state.value.sessions.first { it.optString("sessionKey") == store.state.value.selected }
                File(context.filesDir, "session-termination-device-proof.json").writeText(
                    JSONObject().put("created", JSONObject().put("sessionKey", created!!.optString("sessionKey"))
                        .put("tabId", created!!.optString("tabId"))).toString())
                store.select("")
            } catch (e: Throwable) { failure = e }
        } }
        ui.waitUntil(80000) { created != null || failure != null }
        failure?.let { throw it }
        val target = created!!
        val pane = target.optString("sessionKey")
        assertFalse(before.contains(pane))
        ui.onNodeWithTag("sessionSearch").performTextInput(target.optString("tab"))
        ui.onNodeWithTag("session:$pane").assertIsDisplayed().performTouchInput { longClick() }
        ui.onNodeWithTag("terminateSessionCancel").performClick()
        ui.runOnIdle { assertTrue(store.state.value.sessions.any { it.optString("sessionKey") == pane }) }
        ui.onNodeWithTag("session:$pane").performTouchInput { longClick() }
        ui.onNodeWithTag("terminateSessionConfirm").assertIsEnabled().performClick()
        ui.waitUntil(20000) { store.state.value.sessions.none { it.optString("sessionKey") == pane } }
        ui.onNodeWithTag("session:$pane").assertDoesNotExist()
        assertTrue(store.state.value.sessions.map { it.optString("sessionKey") }.containsAll(before))
        File(context.filesDir, "session-termination-device-proof.json").writeText(
            JSONObject().put("sessionKey", pane).put("tabId", target.optString("tabId"))
                .put("cancelPreserved", true).put("closed", true).put("otherSessionsRetained", true)
                .put("remaining", JSONArray(store.state.value.sessions.map { it.optString("sessionKey") })).toString())
    }
}

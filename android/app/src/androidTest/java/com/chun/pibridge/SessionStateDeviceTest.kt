package com.chun.pibridge

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Only an explicitly identified, disposable local-model fixture may receive these inputs. */
@RunWith(AndroidJUnit4::class)
class SessionStateDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun sdkDialogsRunAbortCompletionAndReconnectRemainConsistent() {
        val args = InstrumentationRegistry.getArguments()
        val pane = requireNotNull(args.getString("fixturePane"))
        val sid = requireNotNull(args.getString("fixtureSessionId"))
        val profileId = requireNotNull(args.getString("profileId"))
        val store = BridgeStore.get(ui.activity)
        val profile = store.profiles.all().single { it.id == profileId }
        ui.runOnUiThread { if (!store.state.value.connected) store.connect(profile) }
        ui.waitUntil(20000) { store.state.value.connected && store.state.value.sessions.any { it.optString("sessionKey") == pane } }
        val fixture = store.state.value.sessions.single { it.optString("sessionKey") == pane }
        assertEquals(sid, fixture.optString("sessionId"))
        assertEquals("bridge-fixture", fixture.optString("model"))
        assertEquals("bridge-reactive-verification", fixture.optString("sessionName"))
        assertNotNull(fixture.optJSONObject("execution"))
        ui.runOnUiThread { store.select(pane) }
        ui.waitUntil(10000) { store.state.value.chats[pane]?.instanceId?.isNotEmpty() == true }
        // select() deliberately displays cache before its asynchronous live snapshot.
        // Establish the baseline from an awaited live open, never from that cache.
        runBlocking { withContext(Dispatchers.Main) { store.open(pane, true) } }
        fun phase() = store.state.value.sessionUi(pane).phase
        val originalPhase = phase()
        assertTrue(originalPhase in setOf("idle", "done", "aborted", "error"))
        val originalMessages = store.state.value.chats[pane]!!.entries.count { it.optJSONObject("message")?.optString("role") == "user" }
        // A blocking extension dialog must restore the previous non-running state.
        runBlocking { store.request("terminal.input", JSONObject().put("paneId", pane)
            .put("text", "/bridge-test-confirm").put("keys", JSONArray().put("enter"))) }
        ui.waitUntil(10000) { phase() == "blocked" }
        assertFalse(store.state.value.sessionUi(pane).canAbort)
        runBlocking { store.request("terminal.keys", JSONObject().put("paneId", pane).put("keys", JSONArray().put("enter"))) }
        ui.waitUntil(10000) { phase() == originalPhase }
        ui.onNodeWithTag("abort").assertDoesNotExist()
        // Local deterministic provider only; never a normal user conversation.
        ui.runOnUiThread { assertTrue(store.send(pane, "slow reactive verification", emptyList(), "followUp")) }
        ui.waitUntil(15000) { phase() == "working" }
        ui.onNodeWithTag("abort").assertIsEnabled()
        ui.runOnUiThread { store.abort(pane) }
        ui.waitUntil(15000) { phase() == "aborted" }
        ui.onNodeWithTag("abort").assertDoesNotExist()
        ui.runOnUiThread { assertTrue(store.send(pane, "reactive completion verification", emptyList(), "followUp")) }
        ui.waitUntil(15000) { phase() == "done" }
        assertEquals("done", store.state.value.sessions.single { it.optString("sessionKey") == pane }.optString("state"))
        ui.onNodeWithTag("abort").assertDoesNotExist()
        ui.onNodeWithTag("backToSessions").performClick()
        ui.waitUntil(10000) { store.state.value.selected.isEmpty() }
        // Observe past the asynchronous session.close ACK, not just the click frame.
        val stableUntil = android.os.SystemClock.elapsedRealtime() + 1000
        ui.waitUntil(2000) {
            assertEquals("done", phase())
            android.os.SystemClock.elapsedRealtime() >= stableUntil
        }
        ui.onNodeWithTag("sessions").performScrollToNode(hasTestTag("session:$pane"))
        ui.onNodeWithTag("session:$pane").assertTextContains("完了", substring = true).performClick()
        ui.waitUntil(10000) { store.state.value.selected == pane }
        assertEquals("done", phase())
        runBlocking { withContext(Dispatchers.Main) { store.refresh(); store.open(pane, true) } }
        assertEquals("done", phase()); assertFalse(store.state.value.sessionUi(pane).canAbort)
        ui.runOnUiThread { store.disconnect() }
        assertEquals("unknown", phase()); assertFalse(store.state.value.sessionUi(pane).canAbort)
        ui.runOnUiThread { store.connect(profile) }
        ui.waitUntil(20000) { store.state.value.connected && phase() == "done" }
        ui.onNodeWithTag("abort").assertDoesNotExist()
        val messages = runBlocking { store.request("session.open", JSONObject().put("sessionKey", pane)) }
            .optJSONArray("entries").objects().count { it.optJSONObject("message")?.optString("role") == "user" }
        assertEquals(originalMessages + 2, messages)
        assertEquals(sid, store.state.value.sessionUi(pane).sessionId)
        val previous = store.state.value.sessionUi(pane)
        runBlocking { store.request("terminal.input", JSONObject().put("paneId", pane)
            .put("text", "/reload").put("keys", JSONArray().put("enter"))) }
        ui.waitUntil(20000) {
            val current = store.state.value.sessionUi(pane)
            current.instanceId != previous.instanceId && current.phase == "idle" &&
                store.state.value.chats[pane]?.instanceId == current.instanceId
        }
        assertTrue(store.state.value.sessionUi(pane).seq < previous.seq)
        assertFalse(store.state.value.sessionUi(pane).canAbort)
        ui.onNodeWithTag("abort").assertDoesNotExist()
        val afterReload = store.state.value.chats[pane]!!.entries.count { it.optJSONObject("message")?.optString("role") == "user" }
        assertEquals(messages, afterReload)
    }
}

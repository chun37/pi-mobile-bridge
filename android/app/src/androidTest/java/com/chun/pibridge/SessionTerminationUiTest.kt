package com.chun.pibridge

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionTerminationUiTest {
    @get:Rule val ui = createComposeRule()
    private fun session() = JSONObject("""{"sessionKey":"w1:p1","sessionId":"s1","instanceId":"i1","tabId":"w1:t1","sessionName":"Close test","bridge":true}""")
    private fun show(connected: Boolean = true, supported: Boolean = true, terminate: suspend (SessionTerminationTarget) -> Unit = {}, click: () -> Unit = {}) {
        ui.setContent { MaterialTheme { Box(Modifier.padding(top = 48.dp)) {
            SessionItemActions(session(), "pc1", connected, supported, terminate, click) { Text("Close test") }
        } } }
    }
    private fun longPress() = ui.onNodeWithTag("session:w1:p1").performTouchInput { longClick() }
    @Test fun tapOpensChatButLongPressOnlyAsksAndCancelDoesNothing() {
        var clicks = 0; var closes = 0
        show(terminate = { closes++ }, click = { clicks++ })
        ui.onNodeWithTag("session:w1:p1").performClick()
        ui.runOnIdle { assertEquals(1, clicks) }
        longPress()
        ui.onNodeWithTag("terminateSessionDialog").assertIsDisplayed()
        ui.runOnIdle { assertEquals(1, clicks); assertEquals(0, closes) }
        ui.onNodeWithTag("terminateSessionCancel").performClick()
        ui.onNodeWithTag("terminateSessionDialog").assertDoesNotExist()
        ui.runOnIdle { assertEquals(0, closes) }
    }
    @Test fun confirmUsesCapturedIdentityAndClosesDialog() {
        var captured: SessionTerminationTarget? = null
        show(terminate = { captured = it })
        longPress(); ui.onNodeWithTag("terminateSessionConfirm").performClick()
        ui.waitUntil { captured != null }
        ui.onNodeWithTag("terminateSessionDialog").assertDoesNotExist()
        ui.runOnIdle { assertEquals("s1", captured!!.sessionId); assertEquals("w1:t1", captured!!.tabId) }
    }
    @Test fun offlineCannotConfirm() {
        show(connected = false); longPress()
        ui.onNodeWithTag("terminateSessionConfirm").assertIsNotEnabled()
    }
    @Test fun legacyRelayExplainsReconnectAndCannotConfirm() {
        show(supported = false); longPress()
        ui.onNodeWithTag("terminateSessionConfirm").assertIsNotEnabled()
        ui.onNodeWithText("PC側の更新後に、接続を切断して再接続してください。").assertIsDisplayed()
    }
    @Test fun changedSessionCannotBeClosedUsingOldConfirmation() {
        var current by mutableStateOf(session())
        ui.setContent { MaterialTheme { Box(Modifier.padding(top = 48.dp)) {
            SessionItemActions(current, "pc1", true, true, {}, {}) { Text("Close test") }
        } } }
        longPress()
        ui.runOnIdle { current = JSONObject(current.toString()).put("sessionId", "replacement") }
        ui.onNodeWithTag("terminateSessionConfirm").assertIsNotEnabled()
    }
    @Test fun busyDisablesDoubleConfirmAndDismiss() {
        val release = CompletableDeferred<Unit>(); var closes = 0
        show(terminate = { closes++; release.await() })
        longPress(); ui.onNodeWithTag("terminateSessionConfirm").performClick()
        ui.onNodeWithTag("terminateSessionConfirm").assertIsNotEnabled()
        ui.onNodeWithTag("terminateSessionCancel").assertIsNotEnabled()
        ui.runOnIdle { assertEquals(1, closes); release.complete(Unit) }
        ui.onNodeWithTag("terminateSessionDialog").assertDoesNotExist()
    }
    @Test fun failureNeverAutomaticallyRetriesOrAllowsSecondConfirm() {
        var closes = 0
        show(terminate = { closes++; error("timeout") })
        longPress(); ui.onNodeWithTag("terminateSessionConfirm").performClick()
        ui.onNodeWithTag("terminateSessionConfirm").assertIsNotEnabled()
        ui.onNodeWithText("timeout", substring = true).assertIsDisplayed()
        ui.runOnIdle { assertEquals(1, closes) }
        ui.onNodeWithTag("terminateSessionCancel").performClick()
    }
    @Test fun switchingProfileDismissesOldConfirmation() {
        var pc by mutableStateOf("pc1")
        ui.setContent { MaterialTheme { Box(Modifier.padding(top = 48.dp)) {
            SessionItemActions(session(), pc, true, true, {}, {}) { Text("Close test") }
        } } }
        longPress()
        ui.runOnIdle { pc = "pc2" }
        ui.onNodeWithTag("terminateSessionDialog").assertDoesNotExist()
    }
}

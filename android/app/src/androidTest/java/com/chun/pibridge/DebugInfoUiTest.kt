package com.chun.pibridge

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
class DebugInfoUiTest {
    @get:Rule val ui = createComposeRule()
    private fun session() = JSONObject("""{"sessionKey":"p1","sessionId":"s1","instanceId":"i1","model":"test-model"}""")
    private fun hello() = JSONObject("""{"protocol":1,"relayVersion":"0.2.0","capabilities":["sessionDebug"]}""")
    private fun snapshot() = JSONObject("""{"relay":{"version":"0.2.0","pid":42},"herdr":{"version":"0.9.5","protocol":22},"bridge":{"bridgeVersion":"0.3.2","runtime":{"piVersion":"1.6.0","nodeVersion":"v25"}},"session":{"paneId":"p1","sessionId":"s1","instanceId":"i1","model":"test-model","thinkingLevel":"high"}}""")
    private fun show(connected: Boolean = true, supported: Boolean = true, toggle: () -> Unit = {}, load: suspend (String,String,String) -> JSONObject = { _,_,_ -> snapshot() }) {
        ui.setContent { MaterialTheme { Box(Modifier.padding(top = 48.dp)) {
            TerminalDebugButton(false, "pc1", connected, "192.0.2.1", if (supported) hello() else JSONObject(), session(), toggle, load)
        } } }
    }
    private fun open() = ui.onNodeWithTag("terminalToggle").performTouchInput { longClick() }
    @Test fun normalTapTogglesTerminalWithoutLoadingDebug() {
        var toggles = 0; var loads = 0
        show(toggle = { toggles++ }, load = { _,_,_ -> loads++; snapshot() })
        ui.onNodeWithTag("terminalToggle").performClick()
        ui.runOnIdle { assertEquals(1, toggles); assertEquals(0, loads) }
        ui.onNodeWithTag("debugScreen").assertDoesNotExist()
    }
    @Test fun longPressShowsVersionsWithoutTogglingAndCanClose() {
        var toggles = 0; var captured = ""
        show(toggle = { toggles++ }, load = { key,sid,instance -> captured = "$key/$sid/$instance"; snapshot() })
        open()
        ui.onNodeWithTag("debugScreen").assertIsDisplayed()
        ui.runOnIdle { assertEquals(0, toggles); assertEquals("p1/s1/i1", captured) }
        ui.onNodeWithTag("debugRow:選択中のPi（実行中）:Pi").performScrollTo().assertTextEquals("Pi: 1.6.0")
        ui.onNodeWithTag("debugRow:選択中のPi（実行中）:Node.js").performScrollTo().assertTextEquals("Node.js: v25")
        ui.onNodeWithTag("debugClose").performClick()
        ui.onNodeWithTag("debugScreen").assertDoesNotExist()
    }
    @Test fun refreshIsExplicitAndNeverPolls() {
        var loads = 0
        show(load = { _,_,_ -> loads++; snapshot() })
        open(); ui.runOnIdle { assertEquals(1, loads) }
        ui.waitForIdle(); ui.runOnIdle { assertEquals(1, loads) }
        ui.onNodeWithTag("debugRefresh").performClick()
        ui.runOnIdle { assertEquals(2, loads) }
    }
    @Test fun legacyRelayExplainsReconnectAndDoesNotRequestUnsupportedApi() {
        var loads = 0
        show(supported = false, load = { _,_,_ -> loads++; snapshot() })
        open()
        ui.onNodeWithTag("debugNotice").assertTextContains("再接続", substring = true)
        ui.onNodeWithTag("debugRefresh").assertIsNotEnabled()
        ui.runOnIdle { assertEquals(0, loads) }
        ui.onNodeWithTag("debugRow:選択中のPi（実行中）:Pi").performScrollTo().assertTextEquals("Pi: 未取得")
    }
    @Test fun offlineShowsConnectionTimeDataWithoutNetworkRequests() {
        var loads = 0
        show(connected = false, load = { _,_,_ -> loads++; snapshot() })
        open()
        ui.onNodeWithTag("debugNotice").assertTextContains("未接続", substring = true)
        ui.onNodeWithTag("debugRefresh").assertIsNotEnabled()
        ui.runOnIdle { assertEquals(0, loads) }
    }
    @Test fun failedReadShowsErrorAndManualRefreshCanRecover() {
        var loads = 0
        show(load = { _,_,_ -> loads++; if (loads == 1) error("read-timeout") else snapshot() })
        open()
        ui.onNodeWithTag("debugNotice").assertTextEquals("read-timeout")
        ui.onNodeWithTag("debugRefresh").performClick()
        ui.onNodeWithTag("debugNotice").assertDoesNotExist()
        ui.runOnIdle { assertEquals(2, loads) }
    }
    @Test fun switchingProfileDismissesDialogAndDropsLateResult() {
        val result = CompletableDeferred<JSONObject>()
        var profile by mutableStateOf("pc1")
        ui.setContent { MaterialTheme { Box(Modifier.padding(top = 48.dp)) {
            TerminalDebugButton(false, profile, true, "192.0.2.1", hello(), session(), {}, { _,_,_ -> result.await() })
        } } }
        open(); ui.onNodeWithTag("debugLoading").assertExists()
        ui.runOnIdle { profile = "pc2" }
        ui.onNodeWithTag("debugScreen").assertDoesNotExist()
        ui.runOnIdle { result.complete(snapshot()) }
        ui.onNodeWithTag("debugScreen").assertDoesNotExist()
    }
    @Test fun reloadedSessionDismissesItsOldDebugSnapshot() {
        var target by mutableStateOf(session())
        ui.setContent { MaterialTheme { Box(Modifier.padding(top = 48.dp)) {
            TerminalDebugButton(false, "pc1", true, "192.0.2.1", hello(), target, {}, { _,_,_ -> snapshot() })
        } } }
        open(); ui.onNodeWithTag("debugScreen").assertIsDisplayed()
        ui.runOnIdle { target = JSONObject(target.toString()).put("instanceId", "reloaded") }
        ui.onNodeWithTag("debugScreen").assertDoesNotExist()
    }
    @Test fun closingDialogDuringReadDoesNotReopenIt() {
        val result = CompletableDeferred<JSONObject>()
        show(load = { _,_,_ -> result.await() })
        open(); ui.onNodeWithTag("debugLoading").assertExists()
        ui.onNodeWithTag("debugClose").performClick()
        ui.runOnIdle { result.complete(snapshot()) }
        ui.onNodeWithTag("debugScreen").assertDoesNotExist()
    }
}

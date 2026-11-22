package com.chun.pibridge

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionStateUiTest {
    @get:Rule val ui = createComposeRule()
    private var execution by mutableStateOf(SessionUiState(sessionId = "s", instanceId = "a", phase = "idle", available = true))
    private var delivery by mutableStateOf("followUp")
    private var aborts = 0
    private fun render() = ui.setContent {
        MaterialTheme { Column(Modifier.fillMaxWidth().padding(top = 48.dp)) {
            StateBadge(execution.phase)
            RunActions(execution, delivery, { delivery = it }, { aborts++ })
        } }
    }
    private fun event(seq: Long, kind: String, aborted: Boolean = false) = JSONObject()
        .put("sessionId", "s").put("instanceId", "a").put("seq", seq).put("kind", kind)
        .put("payload", JSONObject().put("aborted", aborted))
    private fun send(seq: Long, kind: String, aborted: Boolean = false) = ui.runOnIdle {
        execution = SessionUiReducer.event(execution, event(seq, kind, aborted))
    }
    @Test fun completedBadgeAndAbortButtonCannotCoexist() {
        render(); send(1, "agent_start")
        ui.onNodeWithText("実行中").assertIsDisplayed()
        ui.onNodeWithTag("abort").assertIsEnabled()
        send(2, "agent_settled")
        ui.onNodeWithText("完了").assertIsDisplayed()
        ui.onNodeWithTag("abort").assertDoesNotExist()
        ui.onNodeWithTag("followUp").assertDoesNotExist()
        ui.onNodeWithTag("steer").assertDoesNotExist()
    }
    @Test fun lateStartAndHerdrWorkingDoNotBringStopBack() {
        render(); send(5, "agent_settled"); send(2, "agent_start")
        ui.runOnIdle { execution = SessionUiReducer.fallback(execution, "working") }
        ui.onNodeWithText("完了").assertIsDisplayed()
        ui.onNodeWithTag("abort").assertDoesNotExist()
    }
    @Test fun blockingIdleDialogReturnsToIdleNotWorking() {
        render(); send(1, "ui_prompt_start")
        ui.onNodeWithText("応答待ち").assertIsDisplayed()
        ui.onNodeWithTag("abort").assertDoesNotExist()
        send(2, "ui_prompt_end")
        ui.onNodeWithText("待機中").assertIsDisplayed()
        ui.onNodeWithTag("abort").assertDoesNotExist()
    }
    @Test fun interruptionIsExplicitAndNotAnActionButton() {
        render(); send(1, "agent_start"); send(2, "agent_settled", true)
        ui.onNodeWithText("中断済み").assertIsDisplayed()
        ui.onNodeWithText("完了").assertDoesNotExist()
        ui.onNodeWithTag("abort").assertDoesNotExist()
    }
    @Test fun disconnectRemovesAbortAndOldWorkingLabel() {
        render(); send(1, "agent_start")
        ui.runOnIdle { execution = SessionUiReducer.unavailable(execution) }
        ui.onNodeWithText("状態不明").assertIsDisplayed()
        ui.onNodeWithText("実行中").assertDoesNotExist()
        ui.onNodeWithTag("abort").assertDoesNotExist()
    }
    @Test fun actionsWorkOnlyDuringRunAndKeepDeliveryChoice() {
        render(); send(1, "agent_start")
        ui.onNodeWithTag("steer").performClick().assertIsSelected()
        ui.onNodeWithTag("abort").performClick()
        ui.runOnIdle { assertEquals("steer", delivery); assertEquals(1, aborts) }
        send(2, "agent_settled")
        send(3, "agent_start")
        ui.onNodeWithTag("steer").assertIsSelected()
        ui.onNodeWithTag("abort").assertIsEnabled()
    }
}

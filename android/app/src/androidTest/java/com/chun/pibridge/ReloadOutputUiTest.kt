package com.chun.pibridge

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReloadOutputUiTest {
    @get:Rule val ui = createComposeRule()
    @Test fun originalPcOutputIsVisibleAndActionsOnlyChangeDisplay() {
        var output by mutableStateOf<ReloadOutput?>(ReloadOutput("test", "one", "complete",
            "[Skills]\n  fixture\n\nReloaded keybindings, extensions, skills, prompts, themes, and context files"))
        var terminal = false
        ui.setContent { MaterialTheme { Box(Modifier.padding(top = 48.dp)) {
            output?.let { ReloadOutputCard(it, { terminal = true }, { output = null }) }
        } } }
        ui.onNodeWithText("再読み込み完了").assertIsDisplayed()
        ui.onNodeWithTag("reloadOutputText").assertTextContains("[Skills]", substring = true)
        ui.onNodeWithTag("reloadOutputText").assertTextContains("Reloaded keybindings", substring = true)
        ui.onNodeWithText("ターミナルを見る").performClick()
        ui.runOnIdle { assertTrue(terminal) }
        ui.onNodeWithText("閉じる").performClick()
        ui.onNodeWithTag("reloadOutput").assertDoesNotExist()
    }
    @Test fun missingOrFailedOutputNeverShowsSuccess() {
        var output by mutableStateOf(ReloadOutput("test", "one", "loading"))
        ui.setContent { MaterialTheme { Box(Modifier.padding(top = 48.dp)) {
            ReloadOutputCard(output, {}, {})
        } } }
        ui.onNodeWithText("再読み込みの表示を確認中…").assertIsDisplayed()
        ui.onNodeWithText("再読み込み完了").assertDoesNotExist()
        ui.runOnIdle { output = output.copy(phase = "unavailable") }
        ui.onNodeWithText("再読み込みを検出（完了表示は未確認）").assertIsDisplayed()
        ui.onNodeWithText("再読み込み完了").assertDoesNotExist()
        ui.runOnIdle { output = output.copy(phase = "failed", text = "Reload failed: test") }
        ui.onNodeWithText("再読み込み失敗").assertIsDisplayed()
        ui.onNodeWithTag("reloadOutputText").assertTextEquals("Reload failed: test")
    }
}

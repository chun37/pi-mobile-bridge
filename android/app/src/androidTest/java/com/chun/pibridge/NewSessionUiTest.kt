package com.chun.pibridge

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
class NewSessionUiTest {
    @get:Rule val ui = createComposeRule()
    private val workspaces = listOf(JSONObject().put("id", "w1").put("label", "First"),
        JSONObject().put("id", "w2").put("label", "Second"))

    @Test
    fun plusRequiresConfirmationAndUsesChosenWorkspace() {
        var chosen = ""
        var request = ""
        var calls = 0
        ui.setContent {
            MaterialTheme {
                Box(Modifier.padding(top = 48.dp)) {
                    NewSessionAction(true, { workspaces }, { workspace, id ->
                        chosen = workspace; request = id; calls++
                    })
                }
            }
        }
        ui.onNodeWithTag("newSession").performClick()
        ui.waitUntil { ui.onAllNodesWithTag("workspace:w2").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, calls)
        ui.onNodeWithTag("workspace:w2").performClick()
        ui.onNodeWithTag("createSessionConfirm").performClick()
        ui.waitUntil { calls == 1 }
        assertEquals("w2", chosen)
        assertEquals(request, java.util.UUID.fromString(request).toString())
        ui.onNodeWithTag("createSessionConfirm").assertDoesNotExist()
    }

    @Test
    fun retryUsesSameRequestInsteadOfCreatingAnotherTab() {
        val ids = mutableListOf<String>()
        ui.setContent {
            MaterialTheme {
                Box(Modifier.padding(top = 48.dp)) {
                    NewSessionAction(true, { workspaces }, { _, id ->
                        ids.add(id)
                        if (ids.size == 1) throw java.io.IOException("timeout")
                    })
                }
            }
        }
        ui.onNodeWithTag("newSession").performClick()
        ui.waitUntil { ui.onAllNodesWithTag("workspace:w1").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("createSessionConfirm").performClick()
        ui.onNodeWithText("作成結果を再確認").assertExists()
        ui.onNodeWithTag("createSessionConfirm").performClick()
        ui.waitUntil { ids.size == 2 }
        assertEquals(ids[0], ids[1])
    }

    @Test
    fun disconnectedPlusDoesNotLoadOrCreate() {
        var calls = 0
        ui.setContent {
            MaterialTheme {
                NewSessionAction(false, { calls++; workspaces }, { _, _ -> calls++ })
            }
        }
        ui.onNodeWithTag("newSession").assertIsNotEnabled().performClick()
        ui.onNodeWithTag("createSessionConfirm").assertDoesNotExist()
        assertEquals(0, calls)
    }
}

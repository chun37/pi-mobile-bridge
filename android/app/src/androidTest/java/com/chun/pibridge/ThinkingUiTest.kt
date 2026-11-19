package com.chun.pibridge

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThinkingUiTest {
    @get:Rule val ui = createComposeRule()

    private fun entry(thought: Any?) =
        JSONObject().put(
            "message",
            JSONObject().put("role", "assistant").put(
                "content",
                JSONArray().put(
                    JSONObject().put("type", "thinking").put("thinking", thought)
                ).put(JSONObject().put("type", "text").put("text", "応答本文")),
            ),
        )

    @Composable
    private fun Fixture(thought: Any?, visible: Boolean = true) {
        MaterialTheme {
            // The test host draws edge-to-edge; keep native TextViews below the status bar.
            Box(Modifier.padding(top = 48.dp)) {
                MessageCard(entry(thought), emptyList(), visible)
            }
        }
    }

    private fun assertTextVisible(text: String, visible: Boolean) {
        ui.waitForIdle()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val matches = if (visible) device.wait(Until.hasObject(By.text(text)), 3000)
            else !device.wait(Until.gone(By.text(text)), 3000)
        assertEquals(visible, matches)
    }

    private fun assertNoThinkingButtons() {
        ui.onNodeWithText("思考").assertDoesNotExist()
        ui.onNodeWithText("思考を表示").assertDoesNotExist()
        ui.onNodeWithText("思考を閉じる").assertDoesNotExist()
    }

    @Test
    fun emptyWhitespaceMissingAndNullThinkingHaveNoSection() {
        val thought = mutableStateOf<Any?>("")
        ui.setContent { Fixture(thought.value) }
        for (value in listOf("", " \n\t", null, JSONObject.NULL)) {
            ui.runOnIdle { thought.value = value }
            ui.onNodeWithText("思考").assertDoesNotExist()
            assertNoThinkingButtons()
            assertTextVisible("応答本文", true)
        }
    }

    @Test
    fun nonemptyThinkingIsVisibleWithoutClicking() {
        ui.setContent { Fixture("確認用の思考") }
        ui.onNodeWithText("思考").assertDoesNotExist()
        assertTextVisible("確認用の思考", true)
        assertTextVisible("応答本文", true)
        assertNoThinkingButtons()
    }

    @Test
    fun thinkingVisibilityCanBeTurnedOffAndOn() {
        val visible = mutableStateOf(true)
        ui.setContent { Fixture("本文あり", visible.value) }
        assertTextVisible("本文あり", true)
        ui.runOnIdle { visible.value = false }
        assertTextVisible("本文あり", false)
        ui.onNodeWithText("思考").assertDoesNotExist()
        assertTextVisible("応答本文", true)
        ui.runOnIdle { visible.value = true }
        assertTextVisible("本文あり", true)
        assertNoThinkingButtons()
    }

    @Test
    fun hiddenMessagesDoNotLeaveExtraChatSpacing() {
        val first = entry(null).put("entryId", "first")
        val empty = entry("").put("entryId", "empty")
        empty.getJSONObject("message").put("content", JSONArray())
        val result = entry(null).put("entryId", "result")
        result.getJSONObject("message").put("role", "toolResult")
        val last = entry(null).put("entryId", "last")
        val entries = listOf(first, empty, result, last)
        ui.setContent {
            MaterialTheme {
                LazyColumn(
                    Modifier.padding(top = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(visibleChatEntries(entries, true)) { row ->
                        Box(Modifier.testTag("row:${row.optString("entryId")}")) {
                            MessageCard(row, entries)
                        }
                    }
                }
            }
        }
        ui.onNodeWithTag("row:empty").assertDoesNotExist()
        ui.onNodeWithTag("row:result").assertDoesNotExist()
        val firstBounds = ui.onNodeWithTag("row:first").fetchSemanticsNode().boundsInRoot
        val lastBounds = ui.onNodeWithTag("row:last").fetchSemanticsNode().boundsInRoot
        assertEquals(with(ui.density) { 12.dp.toPx() }, lastBounds.top - firstBounds.bottom, 0.5f)
    }

    @Test
    fun streamingThinkingAppearsOnlyWhenTextArrives() {
        val thought = mutableStateOf("")
        ui.setContent { Fixture(thought.value) }
        ui.onNodeWithText("思考").assertDoesNotExist()
        ui.runOnIdle { thought.value = "届いた本文" }
        assertTextVisible("届いた本文", true)
        ui.onNodeWithText("思考").assertDoesNotExist()
        ui.runOnIdle { thought.value = "" }
        assertTextVisible("届いた本文", false)
        ui.onNodeWithText("思考").assertDoesNotExist()
        assertNoThinkingButtons()
    }
}

package com.chun.pibridge

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DisplaySettingsUiTest {
    @get:Rule val ui = createComposeRule()

    private fun status(text: String) = ui.onNodeWithTag("toolStatus:$text", useUnmergedTree = true)

    @Test
    fun thinkingSwitchPersistsAndDoesNotChangeHosts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profiles = Profiles(context)
        val original = profiles.showThinking
        val hosts = profiles.all().map { it.json().toString() }
        try {
            profiles.showThinking = true
            ui.setContent {
                MaterialTheme {
                    SettingsScreen(BridgeStore.get(context), profiles.all(), {}, {}, {})
                }
            }
            ui.onNodeWithTag("showThinking").performScrollTo().assertIsOn().performClick()
            ui.onNodeWithTag("showThinking").assertIsOff()
            assertFalse(Profiles(context).showThinking)
            ui.onNodeWithTag("showThinking").performClick().assertIsOn()
            assertTrue(Profiles(context).showThinking)
            assertEquals(hosts, Profiles(context).all().map { it.json().toString() })
        } finally {
            profiles.showThinking = original
        }
    }

    private fun tool(event: String, error: Boolean = false): JSONObject {
        val value = JSONObject().put("toolEvent", event).put("toolName", "probe")
            .put("toolCallId", "probe-id").put("isError", error)
        if (event == "tool_execution_end") {
            value.put("result", JSONObject().put("content", JSONArray().put(
                JSONObject().put("type", "text").put("text", "確認用の結果")
            )))
        }
        return value
    }

    @Test
    fun standaloneToolsDistinguishRunningSuccessAndFailure() {
        ui.setContent {
            MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                Column {
                    MessageCard(tool("tool_execution_start"), emptyList())
                    MessageCard(tool("tool_execution_end"), emptyList())
                    MessageCard(tool("tool_execution_end", true), emptyList())
                }
            }
        }
        status("実行中").assertExists()
        status("成功").assertExists()
        status("失敗").assertExists()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "tool-status-colors.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test
    fun linkedToolCallsUseFinalResultAndNotPartialResultAsSuccess() {
        val call = JSONObject().put("message", JSONObject().put("role", "assistant")
            .put("content", JSONArray().put(JSONObject().put("type", "toolCall")
                .put("id", "probe-id").put("name", "probe"))))
        val event = androidx.compose.runtime.mutableStateOf(tool("tool_execution_update"))
        ui.setContent {
            MaterialTheme { MessageCard(call, listOf(event.value)) }
        }
        status("実行中").assertExists()
        status("成功").assertDoesNotExist()
        ui.runOnIdle { event.value = tool("tool_execution_end") }
        status("成功").assertExists()
        ui.runOnIdle { event.value = tool("tool_execution_end", true) }
        status("失敗").assertExists()
        val result = JSONObject().put("message", JSONObject().put("role", "toolResult")
            .put("toolCallId", "probe-id").put("isError", false).put("content", JSONArray()))
        ui.runOnIdle { event.value = JSONObject(result.toString()).put("streaming", true) }
        status("実行中").assertExists()
        status("成功").assertDoesNotExist()
        ui.runOnIdle { event.value = result }
        status("成功").assertExists()
    }
}

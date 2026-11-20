package com.chun.pibridge

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Reads the operator-specified existing conversation; no model prompt or new session. */
@RunWith(AndroidJUnit4::class)
class ImageHistoryDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun actualReadImageFromPcHistoryCanBePreviewed() {
        val args = InstrumentationRegistry.getArguments()
        val profileId = args.getString("profileId") ?: error("Pass an owned profileId")
        val pane = args.getString("pane") ?: error("Pass an owned pane")
        val path = args.getString("imagePath") ?: error("Pass the expected read image path")
        val store = BridgeStore.get(ui.activity)
        val profile = store.profiles.all().first { it.id == profileId }
        ui.runOnUiThread { if (!store.isConnectingTo(profile)) store.connect(profile) }
        ui.waitUntil(30000) { store.state.value.connected }
        ui.runOnUiThread { store.select(pane) }
        var call: JSONObject? = null
        var result: JSONObject? = null
        ui.waitUntil(30000) {
            val entries = store.state.value.chats[pane]?.entries.orEmpty()
            call = entries.asReversed().firstNotNullOfOrNull { entry ->
                entry.optJSONObject("message")?.takeIf { it.optString("role") == "assistant" }
                    ?.optJSONArray("content").objects().lastOrNull { part ->
                        part.optString("type") == "toolCall" &&
                            part.optJSONObject("arguments")?.optString("path") == path
                    }
            }
            result = entries.lastOrNull { it.optJSONObject("message")?.optString("toolCallId") == call?.optString("id") }
                ?.optJSONObject("message")
            imageParts(result).isNotEmpty()
        }
        val parts = imageParts(result)
        val asset = decodeImageAsset(parts.first())
        assertTrue(asset.width > 0 && asset.height > 0)
        val isolatedCall = JSONObject().put("message", JSONObject().put("role", "assistant")
            .put("content", JSONArray().put(call)))
        val isolatedResult = JSONObject().put("message", result)
        ui.runOnUiThread { ui.activity.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalImageDownload provides ui.activity::saveImage) {
                    Box(Modifier.padding(top = 48.dp)) {
                        MessageCard(isolatedCall, listOf(isolatedCall, isolatedResult), true)
                    }
                }
            }
        } }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("imageThumbnail").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("imageThumbnail").assertIsDisplayed().performClick()
        ui.onNodeWithText("画像プレビュー").assertIsDisplayed()
        ui.onNodeWithTag("zoomIn").performClick()
        ui.onNodeWithTag("imageZoom").assertTextEquals("200%")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .takeScreenshot(File(ui.activity.filesDir, "images-live.png"))
        ui.onNodeWithContentDescription("プレビューを閉じる").performClick()
        File(ui.activity.filesDir, "images-live-proof.json").writeText(JSONObject()
            .put("mimeType", asset.mimeType).put("bytes", asset.bytes.size)
            .put("width", asset.width).put("height", asset.height).toString())
        ui.runOnUiThread { store.select("") }
        ui.activityRule.scenario.recreate()
    }
}

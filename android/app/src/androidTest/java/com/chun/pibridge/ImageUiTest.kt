package com.chun.pibridge

import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

fun fixtureImagePart(): JSONObject {
    val bitmap = Bitmap.createBitmap(96, 48, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(android.graphics.Color.rgb(20, 200, 160))
    val bytes = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
    bitmap.recycle()
    return JSONObject().put("type", "image").put("mimeType", "image/png")
        .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
}

@RunWith(AndroidJUnit4::class)
class ImageUiTest {
    @get:Rule val ui = createComposeRule()
    private fun assistantCall() = JSONObject().put("message", JSONObject().put("role", "assistant")
        .put("content", JSONArray().put(JSONObject().put("type", "toolCall").put("id", "read-one")
            .put("name", "read").put("arguments", JSONObject().put("path", "/fixture.png")))))
    private fun toolResult(image: JSONObject) = JSONObject().put("message", JSONObject()
        .put("role", "toolResult").put("toolCallId", "read-one")
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Read image file [image/png]"))
            .put(image)))

    @Test fun historyToolImageIsVisibleWhileToolIsCollapsedAndCanZoomAndSaveOriginal() {
        val part = fixtureImagePart()
        var saved: ImageAsset? = null
        val call = assistantCall()
        ui.setContent { MaterialTheme {
            CompositionLocalProvider(LocalImageDownload provides { saved = it }) {
                Box(Modifier.padding(top = 48.dp)) { MessageCard(call, listOf(call, toolResult(part)), true) }
            }
        } }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("imageThumbnail").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("toolStatus:成功", useUnmergedTree = true).assertExists()
        ui.onNodeWithTag("imageThumbnail").performClick()
        ui.onNodeWithText("画像プレビュー").assertIsDisplayed()
        ui.onNodeWithTag("zoomIn").performClick()
        ui.onNodeWithTag("imageZoom").assertTextEquals("200%")
        ui.onNodeWithText("リセット").performClick()
        ui.onNodeWithTag("imageZoom").assertTextEquals("100%")
        ui.onNodeWithTag("saveFullImage").performClick()
        assertArrayEquals(Base64.decode(part.getString("data"), Base64.DEFAULT), saved!!.bytes)
        assertEquals("image/png", saved!!.mimeType)
        ui.onNodeWithContentDescription("プレビューを閉じる").performClick()
        ui.onNodeWithText("画像プレビュー").assertDoesNotExist()
    }

    @Test fun liveToolEndImageIsVisibleWithoutAHistoryResult() {
        val entry = JSONObject().put("toolEvent", "tool_execution_end").put("toolName", "read")
            .put("result", JSONObject().put("content", JSONArray().put(fixtureImagePart())))
        ui.setContent { MaterialTheme { Box(Modifier.padding(top = 48.dp)) { MessageCard(entry, listOf(entry), true) } } }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("imageThumbnail").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("imageThumbnail").assertIsDisplayed()
    }

    @Test fun invalidDataAndMimeMismatchFailWithoutLoadingAnythingElse() {
        val part = fixtureImagePart()
        assertTrue(runCatching { decodeImageAsset(part.put("mimeType", "image/jpeg")) }.isFailure)
        assertTrue(runCatching { decodeImageAsset(JSONObject().put("data", "https://example.test/file.png")) }.isFailure)
        assertTrue(runCatching { decodeImageAsset(JSONObject().put("data", "a".repeat(6 * 1024 * 1024 + 1))) }.isFailure)
    }

    @Test fun userImageAlsoHasPreviewAndTheSameOriginalExport() {
        val part = fixtureImagePart()
        var saved: ImageAsset? = null
        ui.setContent { MaterialTheme {
            CompositionLocalProvider(LocalImageDownload provides { saved = it }) { ImagePreview(part) }
        } }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("imageThumbnail").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("saveImage").performClick()
        assertArrayEquals(Base64.decode(part.getString("data"), Base64.DEFAULT), saved!!.bytes)
        assertEquals(96, saved!!.width)
        assertEquals(48, saved!!.height)
    }
}

package com.chun.pibridge

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

internal fun attachmentFixture(color: Int = Color.BLUE): JSONObject {
    val bitmap = Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
    bitmap.recycle()
    return JSONObject().put("mimeType","image/png").put("data",Base64.encodeToString(bytes,Base64.NO_WRAP))
}

@RunWith(AndroidJUnit4::class)
class AttachmentStripUiTest {
    @get:Rule val ui = createComposeRule()
    private fun image(id: String) = DraftImage(attachmentFixture(), id)
    @Test fun thumbnailsRunLeftToRightWithRemoveAtTopRight() {
        val images = listOf(image("a"),image("b"),image("c"))
        ui.setContent { MaterialTheme { AttachmentStrip(images, {}) } }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("attachmentPreview:a").fetchSemanticsNodes().isNotEmpty() }
        val a = ui.onNodeWithTag("attachment:a").fetchSemanticsNode().boundsInRoot
        val b = ui.onNodeWithTag("attachment:b").fetchSemanticsNode().boundsInRoot
        assertTrue(a.left < b.left)
        val remove = ui.onNodeWithTag("attachmentRemove:a").fetchSemanticsNode().boundsInRoot
        assertTrue(remove.center.x > a.center.x); assertTrue(remove.center.y < a.center.y)
        ui.onNodeWithTag("attachmentCount").assertTextEquals("画像 3")
    }
    @Test fun removesOnlyTheMiddleThumbnail() {
        var images by mutableStateOf(listOf(image("a"), image("b"), image("c")))
        ui.setContent { MaterialTheme { AttachmentStrip(images, { images = removeDraftImage(images,it) }) } }
        ui.onNodeWithTag("attachmentRemove:b").performClick()
        ui.onNodeWithTag("attachment:b").assertDoesNotExist()
        ui.onNodeWithTag("attachment:a").assertExists(); ui.onNodeWithTag("attachment:c").assertExists()
        ui.runOnIdle { assertEquals(listOf("a","c"), images.map { it.id }) }
    }
    @Test fun duplicatePhotosCanBeRemovedIndependently() {
        val photo = attachmentFixture()
        var images by mutableStateOf(listOf(DraftImage(photo,"a"), DraftImage(photo,"b")))
        ui.setContent { MaterialTheme { AttachmentStrip(images, { images = removeDraftImage(images,it) }) } }
        ui.onNodeWithTag("attachmentRemove:a").performClick()
        ui.onNodeWithTag("attachment:b").assertExists()
        ui.onNodeWithTag("attachmentCount").assertTextEquals("画像 1")
    }
    @Test fun removingTheLastPhotoHidesTheStrip() {
        var images by mutableStateOf(listOf(image("a")))
        ui.setContent { MaterialTheme { AttachmentStrip(images, { images = removeDraftImage(images,it) }) } }
        ui.onNodeWithTag("attachmentRemove:a").performClick()
        ui.onNodeWithTag("attachmentStrip").assertDoesNotExist()
        ui.onNodeWithTag("attachmentCount").assertDoesNotExist()
    }
    @Test fun overflowingStripScrollsToNewAttachmentsAndBack() {
        var images by mutableStateOf(listOf(image("a")))
        ui.setContent { MaterialTheme { AttachmentStrip(images, {}, Modifier.width(180.dp)) } }
        ui.runOnIdle { images = images + listOf(image("b"),image("c"),image("d")) }
        ui.onNodeWithTag("attachmentRemove:d").assertIsDisplayed()
        ui.onNodeWithTag("attachmentStrip").performScrollToNode(hasTestTag("attachmentRemove:a"))
        ui.onNodeWithTag("attachmentRemove:a").assertIsDisplayed()
    }
    @Test fun invalidPreviewIsStillRemovable() {
        var images by mutableStateOf(listOf(DraftImage(JSONObject().put("data","broken"), "a")))
        ui.setContent { MaterialTheme { AttachmentStrip(images, { images = removeDraftImage(images,it) }) } }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("attachmentPreviewError:a").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("attachmentRemove:a").performClick()
        ui.onNodeWithTag("attachmentStrip").assertDoesNotExist()
    }
}

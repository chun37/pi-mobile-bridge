package com.chun.pibridge

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
import android.util.Base64
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real ChatScreen + real ContentResolver, with a fixture-only picker. Never sends a message. */
@RunWith(AndroidJUnit4::class)
class ChatAttachmentDeviceTest {
    @get:Rule val ui = createComposeRule()
    @Test fun pickingPhotosShowsThumbnailsAndIndividualRemovalPreservesTheDraft() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val photo = attachmentFixture()
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "pi-bridge-attachment-test-${UUID.randomUUID()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PiBridgeTests")
            put(MediaStore.Images.Media.IS_PENDING, 1) // Owned fixture stays invisible in the user's gallery.
        })!!
        try {
            resolver.openOutputStream(uri)!!.use { it.write(Base64.decode(photo.getString("data"),Base64.NO_WRAP)) }
            var launches = 0
            val registry = object : ActivityResultRegistry() {
                override fun <I,O> onLaunch(requestCode: Int, contract: ActivityResultContract<I,O>, input: I, options: ActivityOptionsCompat?) {
                    assertTrue(contract is ActivityResultContracts.PickVisualMedia)
                    launches++
                    dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(uri))
                }
            }
            val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
            val state = BridgeState(selected = "attachment-test", sessions = listOf(JSONObject()
                .put("sessionKey","attachment-test").put("sessionName","添付UI試験").put("bridge",true)))
            ui.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                MaterialTheme { Column(Modifier.fillMaxSize()) { ChatScreen(BridgeStore.get(context),state) } }
            } }
            ui.onNodeWithTag("messageInput").performTextInput("本文は残す")
            val removeMatcher = SemanticsMatcher("attachment remove button") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("attachmentRemove:") == true
            }
            for (count in 1..3) {
                ui.onNodeWithTag("attachImage").performClick()
                ui.waitUntil(10000) { ui.onAllNodes(removeMatcher).fetchSemanticsNodes().size == count }
            }
            ui.onNodeWithTag("attachmentCount").assertDoesNotExist()
            val buttons = ui.onAllNodes(removeMatcher).fetchSemanticsNodes().sortedBy { it.boundsInRoot.left }
            assertEquals(3,buttons.size)
            val tags = buttons.map { it.config[SemanticsProperties.TestTag] }
            assertEquals(3,tags.toSet().size) // Same photo picked repeatedly still has independent identities.
            ui.waitUntil(10000) { tags.all { tag -> ui.onAllNodesWithTag(tag.replace("attachmentRemove:","attachmentPreview:")).fetchSemanticsNodes().isNotEmpty() } }
            File(context.filesDir,"attachment-compose-proof.png").outputStream().use {
                ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
            ui.onNodeWithTag(tags[1]).performClick()
            ui.onAllNodes(removeMatcher).assertCountEquals(2)
            ui.onNodeWithTag(tags[0]).assertExists(); ui.onNodeWithTag(tags[2]).assertExists()
            ui.onNodeWithTag("messageInput").assertTextContains("本文は残す")
            ui.onNodeWithTag("send").assertIsNotEnabled()
            ui.onNodeWithTag(tags[0]).performClick(); ui.onNodeWithTag(tags[2]).performClick()
            ui.onNodeWithTag("attachmentStrip").assertDoesNotExist()
            ui.onNodeWithTag("messageInput").assertTextContains("本文は残す")
            ui.runOnIdle { assertEquals(3, launches) }
            File(context.filesDir,"attachment-device-proof.json").writeText(JSONObject()
                .put("picked",launches).put("removedIndividually",true).put("draftTextPreserved",true)
                .put("sentMessages",0).toString())
        } finally { resolver.delete(uri,null,null) }
    }
}

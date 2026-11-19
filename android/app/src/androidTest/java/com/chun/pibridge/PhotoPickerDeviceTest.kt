package com.chun.pibridge

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoPickerDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun pickActualPhotoSendAndRender() {
        val context = ui.activity
        val resolver = context.contentResolver
        val created = System.currentTimeMillis()
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DATE_TAKEN, created)
                put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    "pibridge-verification-${System.currentTimeMillis()}.png",
                )
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PiBridgeVerification")
            }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        try {
            val bitmap =
                Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.GREEN)
                }
            resolver.openOutputStream(uri)!!.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            val store = BridgeStore.get(context)
            val pane =
                InstrumentationRegistry.getArguments().getString("pane")
                    ?: error("Pass an owned fixture pane")
            ui.runOnUiThread {
                store.connect(store.profiles.all().first { it.id == "verification-pc" })
            }
            ui.waitUntil(20000) { store.state.value.connected }
            ui.runOnUiThread { store.select(pane) }
            ui.waitUntil(20000) { store.state.value.chats[pane]?.leaf?.isNotEmpty() == true }
            ui.onNodeWithTag("attachImage").performClick()
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val photo =
                device.wait(
                    Until.findObject(
                        By.descStartsWith(
                            java.text
                                .SimpleDateFormat("yyyy/M/d H:mm", java.util.Locale.JAPAN)
                                .format(java.util.Date(created))
                        )
                    ),
                    10000,
                )
            device.dumpWindowHierarchy(java.io.File(context.filesDir, "photo-picker-hierarchy.xml"))
            assertNotNull("Photo Picker has no thumbnail", photo)
            photo.click()
            device
                .wait(Until.findObject(By.pkg("com.google.android.photopicker").text("完了")), 3000)
                ?.click()
            ui.waitUntil(15000) {
                runCatching { ui.onAllNodesWithText("画像 1").fetchSemanticsNodes().isNotEmpty() }
                    .getOrDefault(false)
            }
            val message = "PHOTO_PICKER_DEVICE_MESSAGE $created"
            ui.onNodeWithTag("messageInput").performTextInput(message)
            ui.onNodeWithTag("send").performClick()
            ui.waitUntil(20000) {
                store.state.value.chats[pane]?.entries?.any { e ->
                    e.optJSONObject("message")?.let {
                        it.optString("role") == "assistant" &&
                            it.textContent().contains(message) &&
                            !e.optBoolean("streaming")
                    } == true
                } == true
            }
            assertTrue(
                store.state.value.chats[pane]!!.entries.any { e ->
                    e.optJSONObject("message")?.let { m ->
                        m.textContent() == message &&
                            m.optJSONArray("content").objects().any {
                                it.optString("type") == "image"
                            }
                    } == true
                }
            )
            ui.waitForIdle()
            val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(context.filesDir, "image-render.png").outputStream().use {
                screen.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screen.recycle()
            ui.runOnUiThread { store.disconnect() }
        } finally {
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .dumpWindowHierarchy(java.io.File(context.filesDir, "photo-picker-after-click.xml"))
            resolver.delete(uri, null, null)
        }
    }
}

package com.chun.pibridge

import android.widget.EditText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageDownloadDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun pendingExportSurvivesActivityRecreationAndCancelAllowsAnotherSave() {
        val asset = decodeImageAsset(fixtureImagePart())
        val owner = androidx.lifecycle.ViewModelProvider(ui.activity)[ImageDownloads::class.java]
        ui.runOnUiThread { assertTrue(owner.begin(asset)) }
        ui.activityRule.scenario.recreate()
        val restored = androidx.lifecycle.ViewModelProvider(ui.activity)[ImageDownloads::class.java]
        assertSame(owner, restored)
        ui.runOnUiThread {
            assertFalse(restored.begin(asset))
            restored.complete(null)
            assertTrue(restored.begin(asset))
            restored.complete(null)
        }
    }

    @Test fun originalImageCanBeSavedUsingAndroidDocumentPicker() {
        val asset = decodeImageAsset(fixtureImagePart())
        val filename = "pi-bridge-test-" + UUID.randomUUID().toString() + ".png"
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ui.runOnUiThread { ui.activity.saveImage(asset) }
        val edit = device.wait(Until.findObject(By.clazz(EditText::class.java)), 10000)
        assertNotNull("Android save dialog must be shown", edit)
        edit.text = filename
        val save = device.findObject(By.text("保存")) ?: device.findObject(By.text("Save"))
        assertNotNull("Android save button must be shown", save)
        save.click()
        val owner = androidx.lifecycle.ViewModelProvider(ui.activity)[ImageDownloads::class.java]
        try {
            runBlocking { withTimeout(10000) {
                while (owner.saveState in listOf("pending", "writing")) delay(100)
            } }
        } catch (e: TimeoutCancellationException) {
            device.dumpWindowHierarchy(File(ui.activity.filesDir, "image-save-failure.xml"))
            error("Export did not finish; state=${owner.saveState}")
        }
        assertEquals("saved", owner.saveState)
        val uri = requireNotNull(owner.lastSavedUri)
        try {
            val saved = ui.activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertArrayEquals(asset.bytes, saved)
            val sha = MessageDigest.getInstance("SHA-256").digest(saved).joinToString("") { "%02x".format(it) }
            File(ui.activity.filesDir, "image-save-proof.json").writeText(JSONObject()
                .put("filename", filename).put("sha256", sha).put("bytes", saved.size).toString())
        } finally {
            android.provider.DocumentsContract.deleteDocument(ui.activity.contentResolver, uri)
        }
    }
}

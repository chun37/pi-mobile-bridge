package com.chun.pibridge

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionOrderingDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun openedSessionBecomesRecentWithoutChangingThePcConversation() {
        val args = InstrumentationRegistry.getArguments()
        val profile = args.getString("profileId") ?: error("Pass an owned profileId")
        val pane = args.getString("pane") ?: error("Pass an explicit existing pane to read")
        val store = BridgeStore.get(ui.activity)
        val original = store.profiles.sessionSort
        try {
            ui.runOnUiThread {
                store.connect(store.profiles.all().first { it.id == profile })
                store.select("")
            }
            ui.waitUntil(30000) {
                store.state.value.connected && store.state.value.sessions.any {
                    it.optString("sessionKey") == pane && it.optBoolean("bridge")
                }
            }
            val target = store.state.value.sessions.first { it.optString("sessionKey") == pane }
            assertTrue(target.optLong("lastActivityAt") > 0L)
            val before = store.profiles.lastOpened(profile, target)
            ui.onNodeWithTag("chatOnly").assertDoesNotExist()
            ui.onNodeWithTag("sessionSort:DEFAULT").performClick()
            ui.onNodeWithTag("sessions").performScrollToNode(hasTestTag("session:$pane"))
            ui.onNodeWithTag("session:$pane").performClick()
            ui.waitUntil(20000) { store.state.value.selected == pane }
            assertTrue(store.profiles.lastOpened(profile, target) > before)
            ui.onNodeWithTag("backToSessions").performClick()
            ui.onNodeWithTag("sessionSort:RECENT").performClick()
            ui.onAllNodes(SemanticsMatcher("workspace header") {
                it.config.contains(SemanticsProperties.TestTag) &&
                    it.config[SemanticsProperties.TestTag].startsWith("workspaceHeader:")
            }).assertCountEquals(0)
            ui.onNodeWithTag("session:$pane").assertIsDisplayed()
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(ui.activity.filesDir, "recent-sessions.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            java.io.File(ui.activity.filesDir, "session-order-proof.json").writeText(
                JSONObject().put("pane", pane).put("lastActivityAt", target.optLong("lastActivityAt"))
                    .put("openedAt", store.profiles.lastOpened(profile, target))
                    .put("sort", Profiles(ui.activity).sessionSort.name).toString()
            )
        } finally {
            ui.runOnUiThread { store.select(""); store.profiles.sessionSort = original }
            ui.onNodeWithTag("sessionSort:${original.name}").performClick()
        }
    }
}

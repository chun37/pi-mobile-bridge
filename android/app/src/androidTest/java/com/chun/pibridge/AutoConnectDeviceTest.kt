package com.chun.pibridge

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run in a fresh app process with an existing trusted PC and autoConnect enabled. */
@RunWith(AndroidJUnit4::class)
class AutoConnectDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun launchConnectsWithoutTappingAndDoesNotUndoManualDisconnect() {
        val store = BridgeStore.get(ui.activity)
        assertTrue(store.profiles.autoConnect)
        ui.waitUntil(30000) { store.state.value.connected &&
            store.connectionTimings.containsKey("sessions_ready") }
        val attempt = store.connectionTimings
        val profile = store.state.value.profileId
        assertEquals(store.profiles.lastProfileId, profile)
        val configured = store.profiles.all().first { it.id == profile }
        assertTrue(store.isConnectingTo(configured))
        // A repeated foreground-service start must not tear down a working connection.
        ui.runOnUiThread {
            ui.activity.startForegroundService(android.content.Intent(ui.activity, BridgeService::class.java)
                .putExtra("profileId", profile))
        }
        ui.waitForIdle()
        assertSame(attempt, store.connectionTimings)
        ui.onNodeWithContentDescription("切断").performClick()
        ui.waitUntil(5000) { !store.state.value.connected && !store.connectionActive }
        ui.activityRule.scenario.recreate()
        ui.waitForIdle()
        assertFalse(store.connectionActive)
        assertFalse(store.state.value.connected)
    }
}

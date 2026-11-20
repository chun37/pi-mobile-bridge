package com.chun.pibridge

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutoConnectUiTest {
    @get:Rule val ui = createComposeRule()

    @Test fun startupConnectionPreferencePersists() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BridgeStore.get(context)
        val original = store.profiles.autoConnect
        try {
            store.profiles.autoConnect = true
            ui.setContent {
                MaterialTheme { SettingsScreen(store, store.profiles.all(), {}, {}, {}) }
            }
            ui.onNodeWithTag("autoConnect").performScrollTo().assertIsOn().performClick()
            ui.onNodeWithTag("autoConnect").assertIsOff()
            assertFalse(Profiles(context).autoConnect)
            ui.onNodeWithTag("autoConnect").performClick().assertIsOn()
            assertTrue(Profiles(context).autoConnect)
        } finally { store.profiles.autoConnect = original }
    }
}

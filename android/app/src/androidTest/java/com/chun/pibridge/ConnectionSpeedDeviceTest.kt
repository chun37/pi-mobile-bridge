package com.chun.pibridge

import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionSpeedDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun measureConnectionAndFreshListWithoutSendingPrompts() {
        val args = InstrumentationRegistry.getArguments()
        val profileId = args.getString("profileId") ?: error("Pass an owned profileId")
        val store = BridgeStore.get(ui.activity)
        val measurements = org.json.JSONArray()
        repeat(2) { attempt ->
            val start = SystemClock.elapsedRealtime()
            ui.runOnUiThread {
                store.select("")
                store.connect(store.profiles.all().first { it.id == profileId })
            }
            ui.waitUntil(60000) { store.state.value.connected }
            val connected = SystemClock.elapsedRealtime() - start
            runBlocking { store.refresh() }
            ui.waitUntil(60000) { store.connectionTimings.containsKey("sessions_ready") }
            measurements.put(org.json.JSONObject().put("attempt", attempt)
                .put("connectedMs", connected).put("freshListMs", SystemClock.elapsedRealtime() - start)
                .put("phases", org.json.JSONObject(store.connectionTimings)))
        }
        java.io.File(ui.activity.filesDir, "connection-speed.json").writeText(measurements.toString(2))
    }
}

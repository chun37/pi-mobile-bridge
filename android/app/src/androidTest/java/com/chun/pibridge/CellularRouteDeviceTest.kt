package com.chun.pibridge

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CellularRouteDeviceTest {
    @Test
    fun automaticRouteAndConversationOverCellular() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("cellular") == "true") {
            "Only run after verifying cellular VPN underlay"
        }
        val pane = args.getString("pane") ?: error("Pass an owned fixture pane")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = BridgeStore.get(context)
        val profile = store.profiles.all().first { it.id == "verification-pc" }.automatic()
        withContext(Dispatchers.Main) { store.connect(profile) }
        withTimeout(60000) {
            while (
                !store.state.value.connected ||
                    store.state.value.sessions.none { it.optString("sessionKey") == pane }
            ) delay(50)
        }
        assertEquals(
            "bridge-fixture",
            store.state.value.sessions
                .first { it.optString("sessionKey") == pane }
                .optString("model"),
        )
        withContext(Dispatchers.Main) { store.select(pane) }
        val token = "CELLULAR_AUTO_ROUTE_${System.currentTimeMillis()}"
        withContext(Dispatchers.Main) { store.send(pane, token, emptyList(), "followUp") }
        withTimeout(20000) {
            while (
                store.state.value.chats[pane]?.entries?.none {
                    it.optJSONObject("message")?.optString("role") == "assistant" &&
                        it.optJSONObject("message")?.textContent()?.contains(token) == true &&
                        !it.optBoolean("streaming")
                } != false
            ) delay(50)
        }
        assertEquals(profile.id, store.state.value.profileId)
        assertEquals(profile.pin, store.profiles.all().first { it.id == profile.id }.pin)
        InstrumentationRegistry.getInstrumentation()
            .sendStatus(
                0,
                android.os.Bundle().apply {
                    putString(
                        "stream",
                        "CELLULAR_AUTO_ROUTE_OK endpoint=${store.state.value.endpoint} token=$token\n",
                    )
                },
            )
        withContext(Dispatchers.Main) { store.disconnect() }
    }
}

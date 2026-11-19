package com.chun.pibridge

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EndpointUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val store
        get() = BridgeStore.get(ui.activity)

    private val pane
        get() =
            InstrumentationRegistry.getArguments().getString("pane")
                ?: error("Pass an owned bridge-fixture pane")

    private val lanHost
        get() = InstrumentationRegistry.getArguments().getString("lanHost")
            ?: error("Pass -e lanHost <LAN address>")
    private val tailscaleHost
        get() = InstrumentationRegistry.getArguments().getString("tailscaleHost")
            ?: error("Pass -e tailscaleHost <VPN address>")

    private fun waitFor(p: () -> Boolean) {
        try {
            ui.waitUntil(timeoutMillis = 30000, p)
        } catch (e: Throwable) {
            val s = store.state.value
            println(
                "ENDPOINT_DIAGNOSTIC status=${s.status} connected=${s.connected} endpoint=${s.endpoint} selected=${s.selected} error=${s.error} chats=${s.chats.keys}"
            )
            capture("v2-failure.png")
            throw e
        }
    }

    private fun capture(name: String) {
        ui.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(ui.activity.filesDir, name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun assertFixture() {
        assertEquals(
            "bridge-fixture",
            store.state.value.sessions
                .first { it.optString("sessionKey") == pane }
                .optString("model"),
        )
    }

    @Test
    fun onePcTwoAddressesShareHistoryAndHostPin() {
        val original = store.profiles.all().first { it.id == "verification-pc" }
        val count = store.profiles.all().size
        ui.onNodeWithContentDescription("設定").performClick()
        ui.onNodeWithTag("edit:${original.id}").performClick()
        ui.onNodeWithTag("profileName").performScrollTo().performTextReplacement("検証PC")
        ui.onNodeWithTag("host").performScrollTo().performTextReplacement(lanHost)
        if (original.hosts.size < 2) ui.onNodeWithTag("addAddress").performScrollTo().performClick()
        ui.onNodeWithTag("address:1").performScrollTo().performTextReplacement(tailscaleHost)
        ui.onNodeWithTag("saveProfile").performScrollTo().performClick()
        val saved = store.profiles.all().first { it.id == original.id }
        assertEquals(listOf(lanHost, tailscaleHost), saved.hosts)
        assertEquals(original.pin, saved.pin)
        assertEquals(count, store.profiles.all().size)
        capture("v2-settings.png")
        ui.onNodeWithText("接続").performScrollTo().performClick()
        waitFor {
            store.state.value.connected &&
                store.state.value.sessions.any { it.optString("sessionKey") == pane }
        }
        assertFixture()
        if (store.state.value.selected.isNotEmpty())
            ui.onNodeWithTag("backToSessions").performClick()
        ui.onNodeWithTag("sessions").performScrollToNode(hasTestTag("session:$pane"))
        capture("v2-sessions.png")
        ui.onNodeWithTag("session:$pane").performClick()
        waitFor { store.state.value.chats[pane]?.entries?.isNotEmpty() == true }
        val ids = store.state.value.chats[pane]!!.entries.map { it.optString("entryId") }.toSet()
        val lastId = store.state.value.chats[pane]!!.entries.last().getString("entryId")
        ui.onNodeWithTag("entry:$lastId").assertIsDisplayed()
        for (host in listOf(lanHost, tailscaleHost, lanHost)) {
            ui.onNodeWithTag("routePicker").performClick()
            ui.onNodeWithTag("route:$host").performClick()
            waitFor {
                store.state.value.connected &&
                    store.state.value.endpoint == host &&
                    store.state.value.selected == pane &&
                    store.state.value.chats[pane]?.entries?.isNotEmpty() == true
            }
            assertEquals(original.id, store.state.value.profileId)
            assertEquals(original.pin, store.profiles.all().first { it.id == original.id }.pin)
            assertTrue(
                store.state.value.chats[pane]!!
                    .entries
                    .map { it.optString("entryId") }
                    .toSet()
                    .containsAll(ids)
            )
            capture(if (host.startsWith("192.")) "v2-lan-chat.png" else "v2-tailscale-chat.png")
        }
        ui.onNodeWithTag("routePicker").performClick()
        ui.onNodeWithTag("route:auto:${original.id}").performClick()
        waitFor {
            store.state.value.connected &&
                store.state.value.endpoint == lanHost &&
                store.state.value.selected == pane
        }
        assertTrue(store.profiles.all().first { it.id == original.id }.autoSelect)
        capture("v2-auto-lan.png")
        ui.runOnUiThread {
            ui.activity.stopService(Intent(ui.activity, BridgeService::class.java))
            store.disconnect()
        }
    }

    @Test
    fun automaticLanPriorityAndFallbackPreserveSelection() {
        val p = store.profiles.all().first { it.id == "verification-pc" }.automatic()
        ui.runOnUiThread { store.connect(p) }
        waitFor {
            store.state.value.connected &&
                store.state.value.endpoint == lanHost &&
                store.state.value.sessions.any { it.optString("sessionKey") == pane }
        }
        assertFixture()
        ui.runOnUiThread { store.select(pane) }
        waitFor { store.state.value.chats[pane]?.entries?.isNotEmpty() == true }
        // An unavailable phone-loopback SSH endpoint replaces LAN for this in-memory test only.
        ui.runOnUiThread {
            store.connect(
                p.copy(host = "127.0.0.1", addresses = listOf("127.0.0.1", tailscaleHost))
            )
        }
        waitFor {
            store.state.value.connected &&
                store.state.value.endpoint == tailscaleHost &&
                store.state.value.selected == pane &&
                store.state.value.chats[pane]?.entries?.isNotEmpty() == true
        }
        capture("v2-auto-fallback.png")
        ui.runOnUiThread { store.connect(p) }
        waitFor {
            store.state.value.connected &&
                store.state.value.endpoint == lanHost &&
                store.state.value.selected == pane
        }
        assertEquals(p.pin, store.profiles.all().first { it.id == p.id }.pin)
    }

    @Test
    fun queueControlsOnlyAppearDuringExecution() {
        ui.runOnUiThread {
            store.connect(store.profiles.all().first { it.id == "verification-pc" })
        }
        waitFor {
            store.state.value.connected &&
                store.state.value.sessions.any { it.optString("sessionKey") == pane }
        }
        assertFixture()
        ui.runOnUiThread { store.select(pane) }
        waitFor { store.state.value.chats[pane]?.entries?.isNotEmpty() == true }
        ui.onNodeWithTag("steer").assertDoesNotExist()
        ui.onNodeWithTag("messageInput")
            .performTextInput("slow UI_BUSY_COMPOSER " + "abcdefghij".repeat(20))
        ui.onNodeWithTag("send").performClick()
        waitFor { store.state.value.chats[pane]?.state == "working" }
        ui.onNodeWithTag("steer").performClick().assertIsSelected()
        ui.onNodeWithTag("followUp").assertIsDisplayed()
        capture("v2-busy-composer.png")
        ui.onNodeWithText("中断").performClick()
        waitFor {
            store.state.value.chats[pane]?.state == "idle" &&
                store.state.value.sessions
                    .first { it.optString("sessionKey") == pane }
                    .optString("state") != "working"
        }
        ui.onNodeWithTag("steer").assertDoesNotExist()
        capture("v2-idle-composer.png")
        ui.runOnUiThread { store.disconnect() }
    }
}

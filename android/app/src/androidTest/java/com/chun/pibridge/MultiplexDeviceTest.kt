package com.chun.pibridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MultiplexDeviceTest {
    @Test
    fun twoSessionsAndSelectiveSubscription() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val one = args.getString("pane") ?: error("Pass an owned fixture pane")
        val two = args.getString("pane2") ?: error("Pass a second owned fixture pane")
        val c = BridgeClient()
        c.connect(
            Profiles(context).all().first { it.id == "verification-pc" },
            Identity(context).privatePem(),
        )
        val events = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        c.events = { events.add(it) }
        val reader = launch(Dispatchers.IO) { runCatching { c.readLoop() } }
        suspend fun send(pane: String, text: String) {
            c.request(
                "session.send",
                JSONObject()
                    .put("sessionKey", pane)
                    .put("text", text)
                    .put("clientMsgId", java.util.UUID.randomUUID().toString()),
            )
        }
        try {
            for (pane in listOf(one, two)) c.request(
                "session.open",
                JSONObject().put("sessionKey", pane),
            )
            send(one, "MULTIPLEX_ONE")
            send(two, "MULTIPLEX_TWO")
            withTimeout(20000) {
                while (
                    listOf(one, two).any { p ->
                        events.none {
                            it.optString("sessionKey") == p &&
                                it.optString("kind") == "agent_settled"
                        }
                    }
                ) delay(100)
            }
            assertTrue(
                events.any {
                    it.optString("sessionKey") == one &&
                        it.optJSONObject("payload")?.optJSONObject("message")?.textContent() ==
                            "MULTIPLEX_ONE"
                }
            )
            assertTrue(
                events.any {
                    it.optString("sessionKey") == two &&
                        it.optJSONObject("payload")?.optJSONObject("message")?.textContent() ==
                            "MULTIPLEX_TWO"
                }
            )
            c.request("session.close", JSONObject().put("sessionKey", one))
            events.clear()
            send(one, "UNSUBSCRIBED_DETAIL_MUST_NOT_FLOW")
            send(two, "SUBSCRIBED_DETAIL_MUST_FLOW")
            withTimeout(20000) {
                while (
                    events.none {
                        it.optString("sessionKey") == two && it.optString("kind") == "agent_settled"
                    }
                ) delay(100)
            }
            assertFalse(
                events.any {
                    it.optString("event") == "session.event" && it.optString("sessionKey") == one
                }
            )
        } finally {
            c.close()
            reader.cancelAndJoin()
        }
    }
}

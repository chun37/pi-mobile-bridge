package com.chun.pibridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LifecycleDeviceTest {
    @Test
    fun draftDialogNewResumeFork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pane =
            InstrumentationRegistry.getArguments().getString("pane")
                ?: error("Pass an owned fixture pane")
        val profile = Profiles(context).all().first { it.id == "verification-pc" }
        val c = BridgeClient()
        c.connect(profile, Identity(context).privatePem())
        val events = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        c.events = { events.add(it) }
        val reader = launch(Dispatchers.IO) { runCatching { c.readLoop() } }
        suspend fun command(text: String) {
            c.request(
                "terminal.input",
                JSONObject()
                    .put("paneId", pane)
                    .put("text", text)
                    .put("keys", JSONArray(listOf("enter"))),
            )
        }
        suspend fun session(): JSONObject =
            c.request("sessions.list").getJSONArray("sessions").objects().first {
                it.optString("sessionKey") == pane
            }
        suspend fun until(timeout: Long = 20000, p: suspend () -> Boolean) {
            withTimeout(timeout) { while (!p()) delay(100) }
        }
        try {
            c.request("session.open", JSONObject().put("sessionKey", pane))
            c.request(
                "terminal.input",
                JSONObject().put("paneId", pane).put("text", "UNSENT_PC_DRAFT"),
            )
            c.request(
                "session.send",
                JSONObject()
                    .put("sessionKey", pane)
                    .put("text", "DRAFT_PRESERVATION_TEST")
                    .put("clientMsgId", java.util.UUID.randomUUID().toString()),
            )
            until { events.any { it.optString("kind") == "agent_settled" } }
            val screen =
                c.request(
                    "terminal.read",
                    JSONObject().put("paneId", pane).put("source", "visible"),
                )
            assertTrue("PC draft was erased", screen.toString().contains("UNSENT_PC_DRAFT"))
            c.request(
                "terminal.keys",
                JSONObject().put("paneId", pane).put("keys", JSONArray(listOf("ctrl+u"))),
            )
            events.clear()
            command("/bridge-test-confirm")
            until { events.any { it.optString("kind") == "ui_prompt_start" } }
            until { session().optString("state") == "blocked" }
            val dialog =
                c.request(
                    "terminal.read",
                    JSONObject().put("paneId", pane).put("source", "visible"),
                )
            assertTrue(dialog.toString().contains("Bridge test confirmation"))
            c.request(
                "terminal.keys",
                JSONObject().put("paneId", pane).put("keys", JSONArray(listOf("enter"))),
            )
            until { events.any { it.optString("kind") == "ui_prompt_end" } }
            val original = session().getString("sessionId")
            command("/new")
            until {
                val s = session()
                s.optBoolean("bridge") && s.optString("sessionId") != original
            }
            assertTrue(
                events.any {
                    it.optString("event") == "session.reset" && it.optString("reason") == "new"
                }
            )
            val newSession = session().getString("sessionId")
            val fresh =
                c.request(
                    "session.open",
                    JSONObject().put("sessionKey", pane).put("since", "missing"),
                )
            assertTrue(fresh.getBoolean("reset"))
            assertFalse(fresh.toString().contains("PC_BASELINE_TEST"))
            command("/bridge-test-resume")
            until { session().optString("sessionId") == original }
            assertTrue(
                events.any {
                    it.optString("event") == "session.reset" && it.optString("reason") == "resume"
                }
            )
            val resumed = c.request("session.open", JSONObject().put("sessionKey", pane))
            assertTrue(resumed.toString().contains("PC_BASELINE_TEST"))
            command("/bridge-test-fork")
            until {
                val s = session()
                s.optBoolean("bridge") &&
                    s.optString("sessionId") != original &&
                    s.optString("sessionId") != newSession
            }
            assertTrue(
                events.any {
                    it.optString("event") == "session.reset" && it.optString("reason") == "fork"
                }
            )
            c.request("session.open", JSONObject().put("sessionKey", pane))
            command("/bridge-test-resume")
            until { session().optString("sessionId") == original }
            java.io
                .File(context.filesDir, "lifecycle-proof.json")
                .writeText(
                    JSONObject()
                        .put("original", original)
                        .put("new", newSession)
                        .put("draftPreserved", true)
                        .put("dialogAnswered", true)
                        .put("forkAndResume", true)
                        .toString()
                )
        } finally {
            c.close()
            reader.cancelAndJoin()
        }
    }
}

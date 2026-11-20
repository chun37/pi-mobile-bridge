package com.chun.pibridge

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineLazyCacheDeviceTest {
    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun await(condition: () -> Boolean) = runBlocking {
        withTimeout(10000) { while (!condition()) delay(25) }
    }

    @Test fun historyLoadsOnDemandEvenWhenPcCannotBeReached() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BridgeStore.get(context)
        val db = Room.databaseBuilder(context, CacheDb::class.java, "bridge.db").build()
        val id = "offline-test-" + java.util.UUID.randomUUID()
        val previous = store.profiles.lastProfileId
        val interruptedFixture = previous.takeIf {
            it.startsWith("offline-test-") && store.profiles.all().none { p -> p.id == it }
        }
        val restoreProfile = if (interruptedFixture == null) previous else
            store.profiles.all().firstOrNull()?.id.orEmpty()
        val profile = Profile(id = id, name = "offline fixture", host = "127.0.0.1", port = 1, user = "test")
        val pane = "offline-pane"
        val index = JSONArray().put(JSONObject().put("sessionKey", pane).put("bridge", true))
        val chat = Conversation(entries = listOf(JSONObject().put("entryId", "fixture")
            .put("message", JSONObject().put("role", "assistant").put("content", "cached answer"))))
        try {
            runBlocking {
                db.cache().put(CacheRow(id, "sessions", index.toString()))
                db.cache().put(CacheRow(id, pane, chat.json()))
            }
            onMain { store.connect(profile) }
            await { store.state.value.sessions.any { it.optString("sessionKey") == pane } }
            assertFalse(store.state.value.chats.containsKey(pane))
            onMain { store.select(pane) }
            await { store.state.value.chats.containsKey(pane) }
            assertEquals("cached answer", store.state.value.chats[pane]!!.entries.single()
                .getJSONObject("message").textContent())
            assertFalse(store.state.value.connected)
        } finally {
            onMain {
                store.disconnect()
                store.select("")
                store.profiles.lastProfileId = restoreProfile
            }
            runBlocking { withContext(Dispatchers.IO) {
                for (fixture in listOfNotNull(id, interruptedFixture)) {
                    db.openHelper.writableDatabase.execSQL("DELETE FROM cache WHERE host = ?", arrayOf(fixture))
                }
            } }
            db.close()
        }
    }
}

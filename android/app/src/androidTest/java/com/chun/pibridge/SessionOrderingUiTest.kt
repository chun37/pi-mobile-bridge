package com.chun.pibridge

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionOrderingUiTest {
    @get:Rule val ui = createComposeRule()

    private fun session(id: String, workspace: String, time: Long, bridge: Boolean = true) =
        JSONObject().put("sessionKey", id).put("workspace", workspace)
            .put("sessionName", id).put("lastActivityAt", time).put("bridge", bridge)
            .put("cwd", "/test").put("state", "idle")

    @Test
    fun switchesBetweenGroupedDefaultAndFlatRecentAndPersistsChoice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BridgeStore.get(context)
        val original = store.profiles.sessionSort
        val state = BridgeState(profileId = "sort-fixture", connected = true, sessions = listOf(
            session("first", "Workspace A", 100), session("last", "Workspace A", 0, false),
            session("newest", "Workspace B", 300),
        ))
        try {
            store.profiles.sessionSort = SessionSort.DEFAULT
            ui.setContent {
                MaterialTheme {
                    Column(Modifier.fillMaxSize().padding(top = 48.dp)) { SessionList(store, state) }
                }
            }
            ui.onNodeWithTag("chatOnly").assertDoesNotExist()
            ui.onNodeWithText("チャットのみ").assertDoesNotExist()
            ui.onNodeWithTag("workspaceHeader:Workspace A").assertExists()
            ui.onNodeWithTag("session:last").assertDoesNotExist()
            ui.onNodeWithTag("sessionSort:RECENT").performClick()
            ui.onNodeWithTag("sessionSort:RECENT").assertIsSelected()
            assertEquals(SessionSort.RECENT, Profiles(context).sessionSort)
            ui.onNodeWithTag("workspaceHeader:Workspace A").assertDoesNotExist()
            ui.onNodeWithTag("workspaceHeader:Workspace B").assertDoesNotExist()
            ui.onNodeWithTag("session:last").assertDoesNotExist()
            val newest = ui.onNodeWithTag("session:newest").fetchSemanticsNode().boundsInRoot
            val first = ui.onNodeWithTag("session:first").fetchSemanticsNode().boundsInRoot
            assertTrue(newest.top < first.top)
            ui.onNodeWithTag("sessionSort:DEFAULT").performClick()
            ui.onNodeWithTag("workspaceHeader:Workspace A").assertExists()
            assertEquals(SessionSort.DEFAULT, Profiles(context).sessionSort)
        } finally { store.profiles.sessionSort = original }
    }

    @Test
    fun usageTimestampsPersistWithoutCrossingProfilesOrSessions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profiles = Profiles(context)
        val id = "sort-test-" + java.util.UUID.randomUUID()
        val session = session("pane", "Workspace", 0).put("sessionId", "s1")
        val preferenceKey = "opened:" + sessionUsageKey(id, session)
        try {
            profiles.recordOpened(id, session, 12345)
            assertEquals(12345L, Profiles(context).lastOpened(id, session))
            assertEquals(0L, profiles.lastOpened(id + "other", session))
            session.put("sessionId", "s2")
            assertEquals(0L, profiles.lastOpened(id, session))
        } finally {
            context.getSharedPreferences("profiles", android.content.Context.MODE_PRIVATE)
                .edit().remove(preferenceKey).commit()
        }
    }
}

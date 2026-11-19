package com.chun.pibridge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatPresentationTest {
    private fun part(type: String, value: String = "") =
        JSONObject().put("type", type).put(type, value)

    private fun entry(id: String, vararg parts: JSONObject, role: String = "assistant") =
        JSONObject().put("entryId", id).put("message", JSONObject()
            .put("role", role).put("content", JSONArray(parts.toList())))

    @Test
    fun emptyAndWhitespaceMessagesDoNotCreateRows() {
        val entries = listOf(entry("empty"), entry("text", part("text")),
            entry("space", part("text", " \n")), entry("think", part("thinking")))
        assertTrue(visibleChatEntries(entries, true).isEmpty())
        val plain = JSONObject().put("message", JSONObject().put("content", ""))
        assertFalse(isChatEntryVisible(plain, listOf(plain), true))
    }

    @Test
    fun thinkingSettingHidesOnlyThinkingAndPreservesAnswer() {
        val thinking = entry("thinking", part("thinking", "思考本文"))
        val mixed = entry("mixed", part("thinking", "思考本文"), part("text", "回答"))
        val entries = listOf(thinking, mixed)
        assertEquals(listOf(thinking, mixed), visibleChatEntries(entries, true))
        assertEquals(listOf(mixed), visibleChatEntries(entries, false))
    }

    @Test
    fun linkedToolEventsAndResultsDoNotCreateSpacerRows() {
        val call = entry("call", JSONObject().put("type", "toolCall").put("id", "t1"))
        val event = JSONObject().put("entryId", "event").put("toolEvent", "tool_execution_end")
            .put("toolCallId", "t1")
        val result = entry("result", part("text", "結果"), role = "toolResult")
        val after = entry("after", part("text", "回答"))
        val entries = listOf(entry("empty"), call, event, result, after)
        assertEquals(listOf(call, after), visibleChatEntries(entries, true))
        assertEquals(5, entries.size)
    }

    @Test
    fun standaloneToolsImagesAndNotesRemainVisible() {
        val tool = JSONObject().put("toolEvent", "tool_execution_start").put("toolCallId", "t1")
        val image = entry("image", part("image"), role = "user")
        val note = JSONObject().put("note", "model_select").put("text", "model")
        val entries = listOf(tool, image, note)
        assertEquals(entries, visibleChatEntries(entries, false))
    }

    @Test
    fun unrelatedContentIdsDoNotHideStandaloneTools() {
        val tool = JSONObject().put("toolEvent", "tool_execution_start").put("toolCallId", "t1")
        val text = entry("text", part("text", "回答").put("id", "t1"))
        assertTrue(isChatEntryVisible(tool, listOf(tool, text), true))
    }
}

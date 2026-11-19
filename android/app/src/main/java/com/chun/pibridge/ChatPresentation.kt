package com.chun.pibridge

import org.json.JSONObject

/** Keep hidden entries in history for tool matching, but out of the spaced chat list. */
fun visibleChatEntries(entries: List<JSONObject>, showThinking: Boolean): List<JSONObject> =
    entries.filter { isChatEntryVisible(it, entries, showThinking) }

fun isChatEntryVisible(
    entry: JSONObject,
    entries: List<JSONObject>,
    showThinking: Boolean,
): Boolean {
    val message = entry.optJSONObject("message")
    if (message?.optString("role") == "toolResult") return false
    if (entry.has("toolEvent")) {
        val id = entry.optString("toolCallId")
        return !entries.any { other ->
            other.optJSONObject("message")?.optJSONArray("content").objects().any {
                it.optString("type") == "toolCall" && it.optString("id") == id
            }
        }
    }
    if (entry.has("note")) return true
    return when (val content = message?.opt("content")) {
        is String -> content.isNotBlank()
        is org.json.JSONArray -> content.objects().any { part ->
            when (part.optString("type")) {
                "text" -> (part.opt("text") as? String)?.isNotBlank() == true
                "thinking" -> showThinking &&
                    (part.opt("thinking") as? String)?.isNotBlank() == true
                "image", "toolCall" -> true
                else -> false
            }
        }
        else -> false
    }
}

package com.chun.pibridge

import org.json.JSONArray
import org.json.JSONObject

/** Accumulate deltas by contentIndex; *_end replaces the block with its final value. */
fun accumulateStream(old: Conversation, snapshot: JSONObject, event: JSONObject?): JSONObject {
    if (event == null) return snapshot
    val type = event.optString("type")
    if (type in listOf("done", "error", "start")) return snapshot
    val index = event.optInt("contentIndex", -1)
    if (index !in 0..4095) return snapshot
    val previous =
        old.entries
            .lastOrNull {
                it.optJSONObject("message")?.let { m ->
                    m.optString("role") == "assistant" &&
                        m.optLong("timestamp") == snapshot.optLong("timestamp")
                } == true
            }
            ?.optJSONObject("message")
    val content = JSONArray(previous?.optJSONArray("content")?.toString() ?: "[]")
    while (content.length() <= index) content.put(JSONObject())
    val block = content.optJSONObject(index) ?: JSONObject()
    val partial = snapshot.optJSONArray("content")?.optJSONObject(index)
    val updated =
        when (type) {
            "text_start" -> JSONObject().put("type", "text").put("text", "")
            "text_delta" ->
                JSONObject(block.toString())
                    .put("type", "text")
                    .put("text", block.optString("text") + event.optString("delta"))
            "text_end" -> JSONObject().put("type", "text").put("text", event.optString("content"))
            "thinking_start" -> JSONObject().put("type", "thinking").put("thinking", "")
            "thinking_delta" ->
                JSONObject(block.toString())
                    .put("type", "thinking")
                    .put("thinking", block.optString("thinking") + event.optString("delta"))
            "thinking_end" ->
                JSONObject().put("type", "thinking").put("thinking", event.optString("content"))
            "toolcall_start" -> JSONObject(partial?.toString() ?: "{}").put("type", "toolCall")
            "toolcall_delta" ->
                JSONObject(partial?.toString() ?: block.toString())
                    .put(
                        "argumentsText",
                        block.optString("argumentsText") + event.optString("delta"),
                    )
            "toolcall_end" -> event.optJSONObject("toolCall") ?: partial ?: block
            else -> return snapshot
        }
    content.put(index, updated)
    return JSONObject(snapshot.toString()).put("content", content)
}

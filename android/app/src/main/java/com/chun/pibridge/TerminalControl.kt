package com.chun.pibridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Terminal pane I/O over the relay. The caller owns the client and the displayed text. */
internal class TerminalControl(
    private val scope: CoroutineScope,
    private val request: suspend (String, JSONObject) -> JSONObject,
    private val error: (Throwable) -> Unit,
    private val show: (String) -> Unit,
) {
    suspend fun read(key: String) {
        val p = request("terminal.read", JSONObject().put("paneId", key).put("source", "visible"))
        show(p.optJSONObject("read")?.optString("text") ?: p.optString("text", p.toString(2)))
    }

    fun keys(key: String, keys: List<String>) {
        scope.launch {
            runCatching {
                    request(
                        "terminal.keys",
                        JSONObject().put("paneId", key).put("keys", JSONArray(keys)),
                    )
                }
                .onFailure { error(it) }
        }
    }

    fun input(key: String, text: String) {
        scope.launch {
            runCatching {
                    request(
                        "terminal.input",
                        JSONObject()
                            .put("paneId", key)
                            .put("text", text)
                            .put("keys", JSONArray(listOf("enter"))),
                    )
                }
                .onFailure { error(it) }
        }
    }
}

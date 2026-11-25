package com.chun.pibridge

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Bounded, read-only capture of the PC-side /reload output for one selected session.
 * The caller supplies [current] so a stale capture never overwrites a newer observation.
 */
internal class ReloadOutputCapture(private val scope: CoroutineScope) {
    fun capture(
        key: String,
        client: BridgeClient,
        sessionId: String,
        token: String = UUID.randomUUID().toString(),
        current: () -> Boolean,
        show: (phase: String, text: String) -> Unit,
    ) {
        show("loading", "")
        scope.launch {
            var lastText = ""
            repeat(6) {
                if (!current()) return@launch
                try {
                    val result = client.request("session.reloadOutput", JSONObject().put("sessionKey", key)
                        .put("expectedSessionId", sessionId), timeoutMs = 1500)
                    if (!current()) return@launch
                    if (result.optString("sessionId") != sessionId) return@launch
                    lastText = result.optString("text")
                    val phase = result.optString("phase")
                    if (phase == "complete" || phase == "failed") {
                        show(phase, lastText)
                        return@launch
                    }
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) { /* Retry only this bounded, read-only observation. */ }
                delay(250)
            }
            if (current()) show("unavailable", lastText)
        }
    }
}

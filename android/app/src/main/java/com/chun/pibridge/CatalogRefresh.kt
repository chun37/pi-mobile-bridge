package com.chun.pibridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Refresh when the picker is reopened, not on every model-prefix keystroke. */
fun slashCatalogScope(text: String): String? = when {
    !text.startsWith("/") -> null
    text == "/model" || text.startsWith("/model ") -> "model"
    else -> "commands"
}

data class CatalogTarget(val client: Any, val profile: String, val generation: Long, val key: String,
    val sessionId: String?, val instanceId: String?)
class CatalogRefreshGate {
    class Ticket(val target: CatalogTarget)
    enum class Finish { STALE, DONE, FOLLOW_UP }
    private var active: Ticket? = null
    private var queued = false
    fun begin(target: CatalogTarget): Ticket? {
        if (active?.target == target) { queued = true; return null }
        return Ticket(target).also { active = it; queued = false }
    }
    fun owns(ticket: Ticket) = active === ticket
    fun finish(ticket: Ticket): Finish {
        if (!owns(ticket)) return Finish.STALE
        val repeat = queued
        active = null; queued = false
        return if (repeat) Finish.FOLLOW_UP else Finish.DONE
    }
}

/** Fetches the slash command catalog for one session through [gate]. The caller owns the UI
 * state and receives ordered [Update]s; [current] guards every step against a changed selection,
 * profile, client or session identity.
 */
internal class CommandCatalog(private val scope: CoroutineScope, private val gate: CatalogRefreshGate) {
    sealed interface Update {
        data object Unsupported : Update
        data object Loading : Update
        data object Failed : Update
        data object Finished : Update
        data class Loaded(val catalog: JSONObject) : Update
    }

    fun refresh(
        client: BridgeClient,
        target: CatalogTarget,
        supported: Boolean,
        current: () -> Boolean,
        update: (Update) -> Unit,
        again: () -> Unit,
    ) {
        if (!current()) return
        if (!supported) {
            update(Update.Unsupported)
            return
        }
        val ticket = gate.begin(target) ?: return
        update(Update.Loading)
        scope.launch {
            try {
                val params = JSONObject().put("sessionKey", target.key)
                    .putOpt("expectedSessionId", target.sessionId?.takeIf { it.isNotBlank() })
                    .putOpt("expectedInstanceId", target.instanceId?.takeIf { it.isNotBlank() })
                val catalog = client.request("session.commands", params)
                if (gate.owns(ticket) && current()) update(Update.Loaded(catalog))
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                if (gate.owns(ticket) && current()) update(Update.Failed)
            } finally {
                val finish = gate.finish(ticket)
                if (finish != CatalogRefreshGate.Finish.STALE && current()) {
                    update(Update.Finished)
                    if (finish == CatalogRefreshGate.Finish.FOLLOW_UP) again()
                }
            }
        }
    }
}

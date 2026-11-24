package com.chun.pibridge

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

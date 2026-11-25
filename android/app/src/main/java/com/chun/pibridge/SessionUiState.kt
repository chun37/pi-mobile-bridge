package com.chun.pibridge

import org.json.JSONArray
import org.json.JSONObject

/** The only execution state consumed by UI, command guards, sorting and notifications. */
data class SessionUiState(
    val sessionId: String = "",
    val instanceId: String = "",
    val phase: String = "unknown",
    val seq: Long = -1,
    val authoritative: Boolean = false,
    val available: Boolean = false,
    val retired: Set<String> = emptySet(),
    val dialogs: List<String> = emptyList(),
    val sequencedSnapshots: Boolean = false,
) {
    val working get() = available && phase == "working"
    val blocked get() = available && phase == "blocked"
    val canAbort get() = working
    val identity get() = "$sessionId|$instanceId"
}

object SessionUiReducer {
    private val phases = setOf("idle", "working", "blocked", "done", "aborted", "error", "unknown")
    private fun phase(value: String) = value.takeIf { it in phases } ?: "unknown"
    private fun string(json: JSONObject, key: String) = json.opt(key) as? String ?: ""

    private fun identify(old: SessionUiState, sid: String, instance: String): SessionUiState? {
        val nextSid = sid.ifEmpty { old.sessionId }
        val nextInstance = instance.ifEmpty { if (nextSid == old.sessionId) old.instanceId else "" }
        val next = "$nextSid|$nextInstance"
        if (next in old.retired) return null
        if (next == old.identity) return old
        val retired = if (old.identity == "|") old.retired else old.retired + old.identity
        return SessionUiState(nextSid, nextInstance, retired = retired.toList().takeLast(32).toSet())
    }

    fun metadata(old: SessionUiState = SessionUiState(), session: JSONObject): SessionUiState {
        if (!session.optBoolean("bridge")) return old.copy(phase = phase(session.optString("state")),
            available = false, authoritative = false)
        val current = identify(old, string(session, "sessionId"), string(session, "instanceId")) ?: return old
        val execution = session.optJSONObject("execution")
        if (execution != null) {
            val number = execution.opt("seq") as? Number ?: return current
            val seq = number.toLong()
            if (seq < 0 || number.toDouble() != seq.toDouble() || seq < current.seq) return current
            val state = phase(execution.optString("state"))
            return current.copy(phase = state, seq = seq, authoritative = true, available = true, sequencedSnapshots = true,
                dialogs = if (state == "blocked") current.dialogs else emptyList())
        }
        // Older bridges have no sequenced snapshot. Live Pi events remain authoritative.
        return if (current.authoritative) current.copy(available = true)
            else current.copy(phase = phase(session.optString("state")), available = true)
    }

    fun fallback(old: SessionUiState, state: String): SessionUiState =
        if (old.authoritative && old.available) old else old.copy(phase = phase(state))

    fun snapshot(old: SessionUiState, info: JSONObject, seq: Long): SessionUiState {
        if (!accepts(old, info)) return old
        val session = JSONObject(info.toString()).put("bridge", true)
        if (info.optJSONObject("execution") == null) {
            // Legacy snapshot: SDK idle is preferable to a cached Conversation.state.
            session.put("execution", JSONObject().put("seq", seq).put("state",
                if (info.optBoolean("idle", true)) "idle" else "working"))
        }
        return metadata(old, session).copy(sequencedSnapshots = info.optJSONObject("execution") != null)
    }

    /** Leaving a view ends its subscription, not the agent or the bridge connection. */
    fun viewClosed(old: SessionUiState): SessionUiState =
        if (old.sequencedSnapshots) old else old.copy(authoritative = false)

    fun accepts(old: SessionUiState, packet: JSONObject): Boolean =
        identify(old, string(packet, "sessionId"), string(packet, "instanceId")) != null

    fun event(old: SessionUiState, packet: JSONObject): SessionUiState {
        val current = identify(old, string(packet, "sessionId"), string(packet, "instanceId")) ?: return old
        val seq = packet.optLong("seq", -1)
        if (seq <= current.seq) return current
        val payload = packet.optJSONObject("payload") ?: JSONObject()
        val settled = if (payload.optBoolean("aborted")) "aborted" else "done"
        val state = packet.optJSONObject("execution")?.optString("state") ?: when (packet.optString("kind")) {
            "agent_start" -> if (current.dialogs.isEmpty()) "working" else "blocked"
            "agent_settled" -> if (current.dialogs.isEmpty()) settled else "blocked"
            "ui_prompt_start" -> "blocked"
            "ui_prompt_end" -> current.dialogs.lastOrNull() ?: current.phase
            else -> current.phase
        }
        val dialogs = when (packet.optString("kind")) {
            "ui_prompt_start" -> current.dialogs + current.phase
            "ui_prompt_end" -> current.dialogs.dropLast(1)
            "agent_start", "agent_settled" -> if (current.dialogs.isEmpty()) emptyList()
                else listOf(if (packet.optString("kind") == "agent_start") "working" else settled) + current.dialogs.drop(1)
            else -> current.dialogs
        }
        return current.copy(phase = phase(state), seq = seq, authoritative = true, available = true, dialogs = if (packet.optJSONObject("execution") != null && state != "blocked") emptyList() else dialogs,
            sequencedSnapshots = current.sequencedSnapshots || packet.optJSONObject("execution") != null)
    }

    fun unavailable(old: SessionUiState) = old.copy(phase = "unknown", available = false, authoritative = false)
}

fun BridgeState.sessionUi(key: String): SessionUiState {
    val value = sessionStates[key]
        ?: sessions.firstOrNull { it.optString("sessionKey") == key }?.let { SessionUiReducer.metadata(session = it) }
        ?: SessionUiState()
    return if (connected) value else SessionUiReducer.unavailable(value)
}

/** Apply identity and execution state atomically with the session list. Never mutate input JSON. */
fun BridgeState.withSessionList(list: List<JSONObject>): BridgeState {
    val next = list.associate { s ->
        val key = s.optString("sessionKey")
        key to SessionUiReducer.metadata(sessionStates[key] ?: SessionUiState(), s)
    }
    return copy(sessions = list.map { s -> JSONObject(s.toString()).put("state", next[s.optString("sessionKey")]!!.phase) },
        sessionStates = next)
}

fun BridgeState.withSessionUi(key: String, value: SessionUiState): BridgeState = copy(
    sessionStates = sessionStates + (key to value),
    sessions = sessions.map { if (it.optString("sessionKey") == key) JSONObject(it.toString()).put("state", value.phase) else it },
)

/** Merge a sessions.changed delta into the current list, keyed by sessionKey and in order.
 * Updated sessions replace in place, added ones append and removed keys disappear. */
fun List<JSONObject>.mergeSessionDelta(
    added: JSONArray?,
    updated: JSONArray?,
    removed: JSONArray?,
): Map<String, JSONObject> {
    val merged = associateBy { it.optString("sessionKey") }.toMutableMap()
    if (removed != null) for (i in 0 until removed.length()) merged.remove(removed.optString(i))
    for (s in added.objects() + updated.objects()) merged[s.optString("sessionKey")] = s
    return merged
}

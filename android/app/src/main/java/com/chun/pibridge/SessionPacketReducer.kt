package com.chun.pibridge

import org.json.JSONObject

data class SessionPacketResult(val state: BridgeState, val resync: Boolean = false)

/** Discovery may advance execution's watermark before chat packets arrive. Do not drop chat data. */
fun BridgeState.reduceSessionPacket(key: String, packet: JSONObject): SessionPacketResult {
    if (sessions.none { it.optString("sessionKey") == key }) return SessionPacketResult(this)
    val execution = sessionUi(key)
    if (!SessionUiReducer.accepts(execution, packet)) return SessionPacketResult(this)
    val updated = SessionUiReducer.event(execution, packet)
    val next = withSessionUi(key, if (selected == key) updated else SessionUiReducer.viewClosed(updated))
    val chat = chats[key] ?: Conversation()
    val sid = packet.opt("sessionId") as? String ?: chat.sessionId
    val instance = packet.opt("instanceId") as? String ?: chat.instanceId
    val changed = (chat.sessionId.isNotEmpty() && sid != chat.sessionId) ||
        (chat.instanceId.isNotEmpty() && instance != chat.instanceId) ||
        (chat.seq > 0 && chat.instanceId.isEmpty() && instance.isNotEmpty())
    val gap = chat.seq > 0 && packet.optLong("seq") > chat.seq + 1
    if (changed || gap) return SessionPacketResult(next, resync = true)
    val scoped = chat.copy(sessionId = sid, instanceId = instance)
    return SessionPacketResult(next.copy(chats = next.chats + (key to Reducer.event(scoped, packet))))
}

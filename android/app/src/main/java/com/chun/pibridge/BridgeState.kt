package com.chun.pibridge

import org.json.JSONObject

/** Snapshot of the connection, session list, chats and per-session UI state exposed to Compose. */
data class BridgeState(
    val status: String = "未接続",
    val connected: Boolean = false,
    val profileId: String = "",
    val endpoint: String = "",
    val sessions: List<JSONObject> = emptyList(),
    val chats: Map<String, Conversation> = emptyMap(),
    val selected: String = "",
    val terminal: String = "",
    val error: String = "",
    val offeredPin: String = "",
    val slashCatalog: JSONObject? = null,
    val slashLoading: Boolean = false,
    val slashError: String = "",
    val commandNotice: String = "",
    val reloadOutputs: Map<String, ReloadOutput> = emptyMap(),
    val sessionStates: Map<String, SessionUiState> = emptyMap(),
    val canTerminateSession: Boolean = false,
    val connectionInfo: JSONObject? = null,
)

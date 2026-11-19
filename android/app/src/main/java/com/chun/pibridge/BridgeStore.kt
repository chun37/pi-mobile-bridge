package com.chun.pibridge

import android.content.Context
import androidx.room.Room
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

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
)

class BridgeStore private constructor(val context: Context) {
    val profiles = Profiles(context)
    val identity = Identity(context)
    private val db = Room.databaseBuilder(context, CacheDb::class.java, "bridge.db").build()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(BridgeState())
    val state: StateFlow<BridgeState> = mutable
    private var client: BridgeClient? = null
    private var connection: Job? = null
    private val cacheJobs = mutableMapOf<String, Job>()
    private val transitions = StateTransitions()

    private fun observeState(key: String, state: String) {
        if (transitions.changed(key, state)) notifyState(key, state)
    }

    var notifyState: (String, String) -> Unit = { _, _ -> }

    fun clearError() {
        mutable.value = mutable.value.copy(error = "")
    }

    fun error(e: Throwable) {
        mutable.value = mutable.value.copy(error = e.message ?: e.javaClass.simpleName)
    }

    fun connect(profile: Profile) {
        val selected = mutable.value.selected.takeIf { mutable.value.profileId == profile.id } ?: ""
        val candidates = profile.connectionHosts
        require(candidates.isNotEmpty()) { "接続先を登録してください" }
        disconnect()
        transitions.clear()
        mutable.value =
            BridgeState(
                status = "接続中",
                profileId = profile.id,
                selected = selected,
                endpoint = candidates.first(),
            )
        connection =
            scope.launch {
                val cached = withContext(Dispatchers.IO) { db.cache().all(profile.id) }
                val chats = mutableMapOf<String, Conversation>()
                var sessions = emptyList<JSONObject>()
                for (row in cached) runCatching {
                    if (row.key == "sessions") sessions = JSONArray(row.json).objects()
                    else chats[row.key] = Conversation.from(row.json)
                }
                mutable.value = mutable.value.copy(chats = chats, sessions = sessions)
                var retry = 0
                var candidate = 0
                while (isActive) {
                    val endpoint = candidates[candidate]
                    var established = false
                    mutable.value =
                        mutable.value.copy(
                            endpoint = endpoint,
                            status = "${addressLabel(endpoint)}に接続中",
                            connected = false,
                        )
                    val c = BridgeClient()
                    client = c
                    c.events = { p ->
                        scope.launch {
                            if (client === c) runCatching { onEvent(p) }.onFailure { error(it) }
                        }
                    }
                    try {
                        val pem = withContext(Dispatchers.IO) { identity.privatePem() }
                        c.connect(
                            profile.copy(host = endpoint),
                            pem,
                            connectTimeoutMs =
                                if (profile.autoSelect && candidates.size > 1) 3000 else 10000,
                        )
                        coroutineScope {
                            val reader = async(Dispatchers.IO) { c.readLoop() }
                            try {
                                val hello =
                                    c.request(
                                        "hello",
                                        JSONObject()
                                            .put("protocol", 1)
                                            .put("client", "android/${BuildConfig.VERSION_NAME}"),
                                    )
                                check(hello.optInt("protocol") == 1) {
                                    "Unsupported relay protocol"
                                }
                                established = true
                                mutable.value =
                                    mutable.value.copy(
                                        status = "接続済み",
                                        connected = true,
                                        error = "",
                                        offeredPin = "",
                                    )
                                retry = 0
                                refresh()
                                val selected = mutable.value.selected
                                if (selected.isNotEmpty()) open(selected)
                                val ping = launch {
                                    while (isActive) {
                                        delay(30000)
                                        c.request("ping")
                                    }
                                }
                                try {
                                    reader.await()
                                } finally {
                                    ping.cancel()
                                }
                            } finally {
                                // Closing the socket must precede waiting for a cancelled blocking
                                // reader.
                                withContext(NonCancellable + Dispatchers.IO) { c.close() }
                                reader.cancel()
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: UntrustedHost) {
                        mutable.value =
                            mutable.value.copy(
                                status = "ホスト鍵の確認待ち",
                                connected = false,
                                offeredPin = e.fingerprint,
                            )
                        return@launch
                    } catch (e: HostKeyChanged) {
                        mutable.value =
                            mutable.value.copy(
                                status = "接続拒否",
                                connected = false,
                                error = e.message ?: "",
                            )
                        return@launch
                    } catch (e: Exception) {
                        if (!established && candidate + 1 < candidates.size) {
                            candidate++
                            mutable.value = mutable.value.copy(connected = false, error = "")
                        } else {
                            candidate = 0
                            retry++
                            val seconds = minOf(60, 1 shl minOf(retry - 1, 6))
                            mutable.value =
                                mutable.value.copy(
                                    status = "${seconds}秒後に再接続",
                                    connected = false,
                                    error = "${addressLabel(endpoint)}: ${e.message ?: "切断"}",
                                )
                            delay(seconds * 1000L)
                        }
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) { c.close() }
                    }
                }
            }
    }

    fun disconnect() {
        connection?.cancel()
        connection = null
        val c = client
        client = null
        scope.launch(Dispatchers.IO) { c?.close() }
        mutable.value = mutable.value.copy(connected = false, status = "未接続")
    }

    suspend fun request(method: String, p: JSONObject = JSONObject()): JSONObject =
        (client ?: error("未接続")).request(method, p)

    suspend fun refresh() {
        val list = request("sessions.list").optJSONArray("sessions").objects()
        for (s in list) observeState(s.optString("sessionKey"), s.optString("state"))
        mutable.value = mutable.value.copy(sessions = list)
        withContext(Dispatchers.IO) {
            db.cache()
                .put(CacheRow(mutable.value.profileId, "sessions", JSONArray(list).toString()))
        }
    }

    fun select(key: String) {
        val old = mutable.value.selected
        mutable.value = mutable.value.copy(selected = key, terminal = "")
        scope.launch {
            runCatching {
                    if (old.isNotEmpty() && old != key)
                        request("session.close", JSONObject().put("sessionKey", old))
                    if (mutable.value.connected) open(key)
                }
                .onFailure { error(it) }
        }
    }

    suspend fun open(key: String, full: Boolean = false) {
        val session = mutable.value.sessions.find { it.optString("sessionKey") == key } ?: return
        if (!session.optBoolean("bridge")) return
        val old = mutable.value.chats[key] ?: Conversation()
        val p = JSONObject().put("sessionKey", key)
        if (!full && old.leaf.isNotEmpty()) p.put("since", old.leaf)
        val h = request("session.open", p)
        updateChat(key, Reducer.history(if (full) Conversation(pending = old.pending) else old, h))
    }

    private fun updateChat(key: String, chat: Conversation) {
        mutable.value = mutable.value.copy(chats = mutable.value.chats + (key to chat))
        cacheJobs[key]?.cancel()
        val host = mutable.value.profileId
        cacheJobs[key] =
            scope.launch {
                delay(700)
                withContext(Dispatchers.IO) { db.cache().put(CacheRow(host, key, chat.json())) }
            }
    }

    private suspend fun onEvent(p: JSONObject) {
        val key = p.optString("sessionKey")
        when (p.optString("event")) {
            "sessions.changed" -> {
                val before =
                    mutable.value.sessions.associateBy { it.optString("sessionKey") }.toMutableMap()
                val removed = p.optJSONArray("removed")
                if (removed != null)
                    for (i in 0 until removed.length()) before.remove(removed.optString(i))
                for (s in p.optJSONArray("added").objects() + p.optJSONArray("updated").objects()) {
                    before[s.optString("sessionKey")] = s
                    observeState(s.optString("sessionKey"), s.optString("state"))
                }
                val selected = mutable.value.selected
                val previous =
                    mutable.value.sessions.find { it.optString("sessionKey") == selected }
                mutable.value = mutable.value.copy(sessions = before.values.toList())
                val current = before[selected]
                if (
                    selected.isNotEmpty() &&
                        current?.optBoolean("bridge") == true &&
                        (previous?.optBoolean("bridge") != true ||
                            previous?.optString("sessionId") != current.optString("sessionId"))
                )
                    open(selected, true)
            }
            "agent.state" -> {
                val newState = p.optString("state")
                mutable.value =
                    mutable.value.copy(
                        sessions =
                            mutable.value.sessions.map {
                                if (it.optString("sessionKey") == key)
                                    JSONObject(it.toString()).put("state", newState)
                                else it
                            }
                    )
                observeState(key, newState)
            }
            "session.event" -> {
                val old = mutable.value.chats[key] ?: Conversation()
                val seq = p.optLong("seq")
                if (old.seq > 0 && seq > old.seq + 1) {
                    open(key, true)
                    return
                }
                updateChat(key, Reducer.event(old, p))
                if (p.optString("kind") in listOf("session_tree", "session_compact"))
                    open(key, true)
            }
            "session.reset" -> if (key == mutable.value.selected) open(key, true)
            "resync" -> {
                refresh()
                val selected = mutable.value.selected
                if (selected.isNotEmpty() && (key.isEmpty() || key == selected))
                    open(selected, true)
            }
        }
    }

    fun send(key: String, text: String, images: List<JSONObject>, delivery: String) {
        val id = UUID.randomUUID().toString()
        val old = mutable.value.chats[key] ?: Conversation()
        val draft = JSONObject().put("clientMsgId", id).put("text", text).put("status", "送信中")
        updateChat(key, old.copy(pending = old.pending + draft))
        scope.launch {
            try {
                request(
                    "session.send",
                    JSONObject()
                        .put("sessionKey", key)
                        .put("text", text)
                        .put("images", JSONArray(images))
                        .put("deliverAs", delivery)
                        .put("clientMsgId", id),
                )
                val now = mutable.value.chats[key] ?: old
                updateChat(
                    key,
                    now.copy(
                        pending =
                            now.pending.map {
                                if (it.optString("clientMsgId") == id)
                                    JSONObject(it.toString()).put("status", "受付済み")
                                else it
                            }
                    ),
                )
            } catch (e: Exception) {
                error(e)
                val now = mutable.value.chats[key] ?: old
                updateChat(
                    key,
                    now.copy(
                        pending =
                            now.pending.map {
                                if (it.optString("clientMsgId") == id)
                                    JSONObject(it.toString()).put("status", "未確認: 自動再送しません")
                                else it
                            }
                    ),
                )
            }
        }
    }

    fun abort(key: String) {
        scope.launch {
            runCatching { request("session.abort", JSONObject().put("sessionKey", key)) }
                .onFailure { error(it) }
        }
    }

    suspend fun terminalRead(key: String) {
        val p = request("terminal.read", JSONObject().put("paneId", key).put("source", "visible"))
        mutable.value =
            mutable.value.copy(
                terminal =
                    p.optJSONObject("read")?.optString("text") ?: p.optString("text", p.toString(2))
            )
    }

    fun terminalKeys(key: String, keys: List<String>) {
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

    fun terminalInput(key: String, text: String) {
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

    companion object {
        @Volatile private var instance: BridgeStore? = null

        fun get(context: Context): BridgeStore =
            instance
                ?: synchronized(this) {
                    instance ?: BridgeStore(context.applicationContext).also { instance = it }
                }
    }
}

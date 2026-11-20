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
    val slashCatalog: JSONObject? = null,
    val slashLoading: Boolean = false,
    val slashError: String = "",
    val commandNotice: String = "",
)

class BridgeStore private constructor(val context: Context) {
    val profiles = Profiles(context)
    val identity = Identity(context)
    private val db = Room.databaseBuilder(context, CacheDb::class.java, "bridge.db").build()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(BridgeState())
    val state: StateFlow<BridgeState> = mutable
    private var client: BridgeClient? = null
    private var commandGeneration = 0L
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

    @Volatile private var connectionGeneration = 0L
    @Volatile var connectionTimings: Map<String, Long> = emptyMap()
        private set

    private var startupConnectionClaimed = false
    private var connectingProfile: Profile? = null

    fun isConnectingTo(profile: Profile): Boolean = connectionActive && connectingProfile == profile

    val connectionActive: Boolean
        get() = connection?.isActive == true

    fun claimStartupProfile(): Profile? {
        if (startupConnectionClaimed) return null
        startupConnectionClaimed = true
        if (!profiles.autoConnect || connectionActive) return null
        return startupProfile(profiles.all(), profiles.lastProfileId)
    }

    fun connect(profile: Profile) {
        if (profiles.lastProfileId != profile.id) profiles.lastProfileId = profile.id
        val previous = mutable.value
        val sameProfile = previous.profileId == profile.id
        val generation = ++connectionGeneration
        val trace = ConnectionTrace { if (connectionGeneration == generation) connectionTimings = it }
        trace.mark("start")
        val selected = mutable.value.selected.takeIf { mutable.value.profileId == profile.id } ?: ""
        val candidates = profile.connectionHosts
        require(candidates.isNotEmpty()) { "接続先を登録してください" }
        disconnect()
        connectingProfile = profile
        transitions.clear()
        mutable.value =
            BridgeState(
                status = "接続中",
                profileId = profile.id,
                selected = selected,
                endpoint = candidates.first(),
                sessions = if (sameProfile) previous.sessions else emptyList(),
                chats = if (sameProfile) previous.chats else emptyMap(),
            )
        connection =
            scope.launch {
                // Only the small session index gates SSH. Full conversations are loaded on demand.
                val sessions = withContext(Dispatchers.IO) {
                    try {
                        db.cache().row(profile.id, "sessions")?.let { JSONArray(it.json).objects() }
                            ?: emptyList()
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) { emptyList() }
                }
                trace.mark("cache_read")
                if (mutable.value.sessions.isEmpty()) {
                    mutable.value = mutable.value.copy(sessions = sessions)
                }
                trace.mark("cache_parsed")
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
                    val c = BridgeClient(trace::mark)
                    trace.mark("client_ready")
                    client = c
                    c.events = { p ->
                        scope.launch {
                            if (client === c) runCatching { onEvent(p) }.onFailure { error(it) }
                        }
                    }
                    try {
                        val pem = withContext(Dispatchers.IO) { identity.privatePem() }
                        trace.mark("key_ready")
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
                                trace.mark("hello")
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
                                trace.mark("sessions_ready")
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

    suspend fun createSession(workspaceId: String, requestId: String) {
        val c = client ?: error("未接続")
        check(mutable.value.connected) { "PCに接続してください" }
        val created = c.request(
            "session.create",
            JSONObject().put("workspaceId", workspaceId).put("requestId", requestId),
            timeoutMs = 60000,
        )
        val pane = created.getString("sessionKey")
        var found = false
        for (attempt in 0 until 30) {
            check(client === c && mutable.value.connected) {
                "接続が変わりました。新しいタブの作成結果を再確認してください"
            }
            refresh()
            val session = mutable.value.sessions.find { it.optString("sessionKey") == pane }
            found = session != null
            if (session?.optBoolean("bridge") == true ||
                (found && !created.optBoolean("started"))) break
            delay(500)
        }
        check(found) { "作成したタブが一覧にありません。PC側のタブを確認してください" }
        select(pane)
        if (created.has("startupError")) {
            error(IllegalStateException("タブは作成済みです。Piの起動を確認してください: " +
                created.optString("startupError")))
        }
    }

    fun select(key: String) {
        if (key.isNotEmpty()) {
            mutable.value.sessions.find { it.optString("sessionKey") == key }?.let {
                profiles.recordOpened(mutable.value.profileId, it)
            }
        }
        val old = mutable.value.selected
        commandGeneration++
        mutable.value = mutable.value.copy(selected = key, terminal = "", slashCatalog = null,
            slashLoading = false, slashError = "", commandNotice = "")
        scope.launch {
            runCatching {
                    if (key.isNotEmpty()) loadCachedChat(key)
                    if (old.isNotEmpty() && old != key && mutable.value.connected)
                        request("session.close", JSONObject().put("sessionKey", old))
                    if (mutable.value.connected) {
                        open(key)
                        if (key.isNotEmpty()) refreshCommands(key)
                    }
                }
                .onFailure { error(it) }
        }
    }

    private suspend fun loadCachedChat(key: String) {
        if (key.isEmpty() || mutable.value.chats.containsKey(key)) return
        val profile = mutable.value.profileId
        val cached = withContext(Dispatchers.IO) {
            try { db.cache().row(profile, key)?.let { Conversation.from(it.json) }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { null }
        } ?: return
        if (mutable.value.profileId == profile && mutable.value.selected == key &&
            !mutable.value.chats.containsKey(key)) {
            mutable.value = mutable.value.copy(chats = mutable.value.chats + (key to cached))
        }
    }

    suspend fun open(key: String, full: Boolean = false) {
        if (full && mutable.value.selected == key) {
            commandGeneration++
            mutable.value = mutable.value.copy(slashCatalog = null, slashLoading = false, slashError = "")
        }
        val activeClient = client ?: return
        val profile = mutable.value.profileId
        loadCachedChat(key)
        if (client !== activeClient || mutable.value.profileId != profile ||
            mutable.value.selected != key || !mutable.value.connected) return
        val session = mutable.value.sessions.find { it.optString("sessionKey") == key } ?: return
        if (!session.optBoolean("bridge")) return
        val old = mutable.value.chats[key] ?: Conversation()
        val p = JSONObject().put("sessionKey", key)
        if (!full && old.leaf.isNotEmpty()) p.put("since", old.leaf)
        val h = activeClient.request("session.open", p)
        if (client !== activeClient || mutable.value.profileId != profile ||
            mutable.value.selected != key) return
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

    fun refreshCommands(key: String) {
        val activeClient = client ?: return
        val profile = mutable.value.profileId
        val generation = commandGeneration
        val session = mutable.value.sessions.find { it.optString("sessionKey") == key }
        val sessionId = session?.optString("sessionId")
        fun current() = commandGeneration == generation && client === activeClient && mutable.value.profileId == profile &&
            mutable.value.selected == key && mutable.value.sessions.find {
                it.optString("sessionKey") == key }?.optString("sessionId") == sessionId
        if (!current() || mutable.value.slashLoading) return
        if (!supportsSlash(session)) {
            mutable.value = mutable.value.copy(slashError = "PCのこのPiで /reload を1回実行してください")
            return
        }
        mutable.value = mutable.value.copy(slashLoading = true, slashError = "")
        scope.launch {
            try {
                val catalog = activeClient.request("session.commands", JSONObject().put("sessionKey", key))
                if (current()) mutable.value = mutable.value.copy(slashCatalog = catalog, slashLoading = false)
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                if (current()) mutable.value = mutable.value.copy(slashLoading = false,
                    slashError = "候補を取得できません。再接続して再試行してください")
            }
        }
    }

    fun send(key: String, text: String, images: List<JSONObject>, delivery: String): Boolean {
        val sendingClient = client ?: return false
        val sendingProfile = mutable.value.profileId
        val session = mutable.value.sessions.find { it.optString("sessionKey") == key }
        val command = text.startsWith("/")
        if (command) {
            val reason = when {
                !supportsSlash(session) -> "PCのこのPiで /reload を1回実行してください"
                images.isNotEmpty() -> "コマンドに画像は添付できません"
                mutable.value.chats[key]?.state == "working" || session?.optString("state") == "working" ->
                    "コマンドは実行が完了してから送信してください"
                else -> null
            }
            if (reason != null) { error(IllegalStateException(reason)); return false }
        }
        val id = UUID.randomUUID().toString()
        val old = mutable.value.chats[key] ?: Conversation()
        val draft = JSONObject().put("clientMsgId", id).put("text", text).put("status", "送信中")
        if (!command) updateChat(key, old.copy(pending = old.pending + draft))
        else mutable.value = mutable.value.copy(commandNotice = "コマンドを送信中…")
        scope.launch {
            try {
                val response = sendingClient.request(
                    "session.send",
                    JSONObject()
                        .put("sessionKey", key)
                        .put("text", text)
                        .put("images", JSONArray(images))
                        .put("deliverAs", delivery)
                        .put("clientMsgId", id)
                        .put("expectedSessionId", session?.opt("sessionId") as? String),
                )
                if (client !== sendingClient || mutable.value.profileId != sendingProfile) return@launch
                if (command) {
                    if (mutable.value.selected == key) mutable.value = mutable.value.copy(
                        commandNotice = response.optString("message", "コマンドを受け付けました"))
                    refresh()
                    return@launch
                }
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
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (client !== sendingClient || mutable.value.profileId != sendingProfile) return@launch
                error(e)
                if (command) {
                    if (mutable.value.selected == key) mutable.value = mutable.value.copy(
                        commandNotice = "コマンドの完了を確認できません。自動では再送しません")
                    return@launch
                }
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
        return true
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

package com.chun.pibridge

import android.content.Context
import androidx.room.*
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "cache", primaryKeys = ["host", "key"])
data class CacheRow(val host: String, val key: String, val json: String)

@Dao
interface CacheDao {
    @Query("SELECT `key` FROM cache WHERE host = :host")
    suspend fun keys(host: String): List<String>

    @Query("SELECT length(CAST(json AS BLOB)) FROM cache WHERE host = :host AND `key` = :key")
    suspend fun jsonLength(host: String, key: String): Int?

    @Query("SELECT substr(CAST(json AS BLOB), :offset, :count) FROM cache WHERE host = :host AND `key` = :key")
    suspend fun jsonChunk(host: String, key: String, offset: Int, count: Int): ByteArray?

    // Bound CursorWindow cells and avoid repeatedly scanning UTF-8 code points.
    // Join byte chunks before decoding, preserving characters split across chunks.
    @Transaction
    suspend fun all(host: String): List<CacheRow> = keys(host).mapNotNull { key -> row(host, key) }

    @Transaction
    suspend fun row(host: String, key: String): CacheRow? {
        val length = jsonLength(host, key) ?: return null
        val json = java.io.ByteArrayOutputStream()
        var offset = 1
        while (offset <= length) {
            json.write(jsonChunk(host, key, offset, 262144) ?: error("Missing cache chunk"))
            offset += 262144
        }
        return CacheRow(host, key, json.toString(Charsets.UTF_8.name()))
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: CacheRow)
}

@Database(entities = [CacheRow::class], version = 1, exportSchema = false)
abstract class CacheDb : RoomDatabase() {
    abstract fun cache(): CacheDao
}

data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    val pin: String = "",
    val addresses: List<String> = emptyList(),
    val autoSelect: Boolean = true,
) {
    val hosts: List<String>
        get() = (addresses + host).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    val connectionHosts: List<String>
        get() = if (autoSelect) hosts.sortedBy(::addressPriority) else listOf(host)

    fun automatic(): Profile = copy(autoSelect = true)

    fun at(address: String): Profile {
        require(address in hosts) { "登録されていない接続先です" }
        return copy(host = address, addresses = hosts, autoSelect = false)
    }

    fun json() =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("host", host)
            .put("port", port)
            .put("user", user)
            .put("pin", pin)
            .put("addresses", JSONArray(hosts))
            .put("autoSelect", autoSelect)

    companion object {
        fun from(p: JSONObject) =
            Profile(
                p.getString("id"),
                p.getString("name"),
                p.getString("host"),
                p.optInt("port", 22),
                p.getString("user"),
                p.optString("pin"),
                p.optJSONArray("addresses")?.let { a ->
                    (0 until a.length()).map { a.getString(it) }
                } ?: emptyList(),
                p.optBoolean("autoSelect", true),
            )
    }
}

class Profiles(context: Context) {
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    fun all(): List<Profile> {
        val a = JSONArray(prefs.getString("hosts", "[]"))
        return (0 until a.length()).map { Profile.from(a.getJSONObject(it)) }
    }

    fun save(p: Profile) {
        val existing = all()
        val a =
            if (existing.any { it.id == p.id }) existing.map { if (it.id == p.id) p else it }
            else existing + p
        check(prefs.edit().putString("hosts", JSONArray(a.map { it.json() }).toString()).commit())
    }

    fun remove(id: String) {
        check(
            prefs
                .edit()
                .putString(
                    "hosts",
                    JSONArray(all().filter { it.id != id }.map { it.json() }).toString(),
                )
                .commit()
        )
    }

    var autoConnect: Boolean
        get() = prefs.getBoolean("autoConnect", true)
        set(v) { check(prefs.edit().putBoolean("autoConnect", v).commit()) }

    var lastProfileId: String
        get() = prefs.getString("lastProfileId", "").orEmpty()
        set(v) { check(prefs.edit().putString("lastProfileId", v).commit()) }

    var sessionSort: SessionSort
        get() = runCatching {
            SessionSort.valueOf(prefs.getString("sessionSort", "DEFAULT") ?: "DEFAULT")
        }.getOrDefault(SessionSort.DEFAULT)
        set(v) { check(prefs.edit().putString("sessionSort", v.name).commit()) }

    fun lastOpened(profileId: String, session: JSONObject): Long =
        prefs.getLong("opened:" + sessionUsageKey(profileId, session), 0L)

    fun recordOpened(profileId: String, session: JSONObject, now: Long = System.currentTimeMillis()) {
        check(prefs.edit().putLong("opened:" + sessionUsageKey(profileId, session), now).commit())
    }

    var showThinking: Boolean
        get() = prefs.getBoolean("showThinking", true)
        set(v) {
            check(prefs.edit().putBoolean("showThinking", v).commit())
        }

    var biometric: Boolean
        get() = prefs.getBoolean("biometric", false)
        set(v) {
            check(prefs.edit().putBoolean("biometric", v).commit())
        }
}

data class Conversation(
    val entries: List<JSONObject> = emptyList(),
    val leaf: String = "",
    val seq: Long = 0,
    val pending: List<JSONObject> = emptyList(),
    val sessionId: String = "",
    val instanceId: String = "",
) {
    fun json() =
        JSONObject()
            .put("entries", JSONArray(entries))
            .put("leaf", leaf)
            .put("seq", seq)
            .put("sessionId", sessionId)
            .put("instanceId", instanceId)
            .toString()

    companion object {
        fun from(s: String): Conversation {
            val p = JSONObject(s)
            return Conversation(
                p.optJSONArray("entries").objects(),
                p.optString("leaf"),
                p.optLong("seq"),
                sessionId = p.optString("sessionId", ""),
                instanceId = p.optString("instanceId", ""),
            )
        }
    }
}

fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONObject.textContent(): String {
    val c = opt("content")
    return if (c is String) c
    else
        (c as? JSONArray)
            .objects()
            .filter { it.optString("type") == "text" }
            .joinToString("\n") { it.optString("text") }
}

object Reducer {
    fun history(previous: Conversation, p: JSONObject): Conversation {
        val info = p.optJSONObject("info")
        val sid = info?.opt("sessionId") as? String ?: previous.sessionId
        val instance = info?.opt("instanceId") as? String ?: previous.instanceId
        val changed = sid != previous.sessionId || instance != previous.instanceId
        val old = if (changed) Conversation(
            entries = if (sid == previous.sessionId) previous.entries else emptyList(),
            leaf = if (sid == previous.sessionId) previous.leaf else "",
            pending = previous.pending, sessionId = sid, instanceId = instance,
        ) else previous
        val late = p.optLong("seq") < old.seq
        val received = p.optJSONArray("entries").objects()
        val base = if (late) received else old.entries
        val preferred = if (late) old.entries else received
        val entries =
            if (p.optBoolean("reset") && !late) received
            else
                (base.filterNot { current ->
                        preferred.any { fresh ->
                            current.optString("entryId") == fresh.optString("entryId") ||
                                (current.optJSONObject("message")?.let { cm ->
                                    fresh.optJSONObject("message")?.let { fm ->
                                        cm.optLong("timestamp") == fm.optLong("timestamp") &&
                                            cm.optString("role") == fm.optString("role") &&
                                            cm.optString("toolCallId") == fm.optString("toolCallId")
                                    }
                                } == true)
                        }
                    } + preferred)
                    .distinctBy { it.optString("entryId") }
        return old.copy(
            entries = entries,
            leaf = if (late) old.leaf else p.optString("leafId"),
            seq = maxOf(old.seq, p.optLong("seq")),
            pending =
                old.pending.filter { q ->
                    entries.none { it.optString("clientMsgId") == q.optString("clientMsgId") }
                },
        )
    }

    fun event(old: Conversation, packet: JSONObject): Conversation {
        val seq = packet.optLong("seq")
        if (seq <= old.seq) return old
        val kind = packet.optString("kind")
        val p = packet.optJSONObject("payload") ?: JSONObject()
        var result = old.copy(seq = seq)
        when (kind) {
            "history_sync" -> result = history(old, p).copy(seq = seq)
            "message_start",
            "message_update",
            "message_end" -> {
                val snapshot = p.optJSONObject("message") ?: return result
                val m =
                    if (kind == "message_update")
                        accumulateStream(old, snapshot, p.optJSONObject("assistantMessageEvent"))
                    else snapshot
                if (m.optString("role") == "system") return result
                val ts = m.optLong("timestamp")
                val live = "live:$ts:${m.optString("role")}:${m.optString("toolCallId")}"
                val entry =
                    JSONObject()
                        .put("entryId", live)
                        .put("message", m)
                        .put("origin", p.optString("origin", "pc"))
                        .put("clientMsgId", p.optString("clientMsgId"))
                        .put("streaming", kind != "message_end")
                val list =
                    result.entries.filterNot {
                        it.optJSONObject("message")?.let { v ->
                            v.optLong("timestamp") == ts &&
                                v.optString("role") == m.optString("role") &&
                                v.optString("toolCallId") == m.optString("toolCallId")
                        } == true
                    } + entry
                result =
                    result.copy(
                        entries = list,
                        pending =
                            result.pending.filterNot {
                                it.optString("clientMsgId") == p.optString("clientMsgId")
                            },
                    )
                // Final session entries carry durable entry IDs; replace live placeholders by
                // timestamp.
                p.optJSONObject("history")?.let { h ->
                    val fresh = h.optJSONArray("entries").objects()
                    val keep =
                        result.entries.filterNot { current ->
                            fresh.any { f ->
                                f.optJSONObject("message")?.let { fm ->
                                    current.optJSONObject("message")?.let { cm ->
                                        fm.optLong("timestamp") == cm.optLong("timestamp") &&
                                            fm.optString("role") == cm.optString("role") &&
                                            fm.optString("toolCallId") == cm.optString("toolCallId")
                                    }
                                } == true || f.optString("entryId") == current.optString("entryId")
                            }
                        }
                    result =
                        result.copy(
                            entries = keep + fresh,
                            leaf = h.optString("leafId", result.leaf),
                        )
                }
            }
            "tool_execution_start",
            "tool_execution_update",
            "tool_execution_end" -> {
                val id = "tool:${p.optString("toolCallId")}"
                val prior = result.entries.lastOrNull { it.optString("entryId") == id }
                val card = JSONObject(prior?.toString() ?: "{}")
                p.keys().forEach { key -> card.put(key, p.get(key)) }
                card.put("entryId", id).put("toolEvent", kind)
                result =
                    result.copy(
                        entries = result.entries.filterNot { it.optString("entryId") == id } + card
                    )
            }
            "session_compact",
            "model_select" ->
                result =
                    result.copy(
                        entries =
                            result.entries +
                                JSONObject()
                                    .put("entryId", "note:$seq")
                                    .put("note", kind)
                                    .put(
                                        "text",
                                        p.optJSONObject("model")?.optString("id") ?: "履歴を圧縮しました",
                                    )
                    )
        }
        return result
    }
}

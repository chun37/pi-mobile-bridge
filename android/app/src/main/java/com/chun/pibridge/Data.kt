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
    @Query("SELECT * FROM cache WHERE host = :host") suspend fun all(host: String): List<CacheRow>

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
    val state: String = "idle",
    val pending: List<JSONObject> = emptyList(),
) {
    fun json() =
        JSONObject()
            .put("entries", JSONArray(entries))
            .put("leaf", leaf)
            .put("seq", seq)
            .put("state", state)
            .toString()

    companion object {
        fun from(s: String): Conversation {
            val p = JSONObject(s)
            return Conversation(
                p.optJSONArray("entries").objects(),
                p.optString("leaf"),
                p.optLong("seq"),
                p.optString("state", "idle"),
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
    fun history(old: Conversation, p: JSONObject): Conversation {
        val received = p.optJSONArray("entries").objects()
        val entries =
            if (p.optBoolean("reset")) received
            else
                (old.entries.filterNot { current ->
                        received.any { fresh ->
                            current.optString("entryId") == fresh.optString("entryId") ||
                                (current.optJSONObject("message")?.let { cm ->
                                    fresh.optJSONObject("message")?.let { fm ->
                                        cm.optLong("timestamp") == fm.optLong("timestamp") &&
                                            cm.optString("role") == fm.optString("role") &&
                                            cm.optString("toolCallId") == fm.optString("toolCallId")
                                    }
                                } == true)
                        }
                    } + received)
                    .distinctBy { it.optString("entryId") }
        return old.copy(
            entries = entries,
            leaf = p.optString("leafId"),
            seq = p.optLong("seq"),
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
            "history_sync" -> result = history(result, p).copy(seq = seq)
            "agent_start" -> result = result.copy(state = "working")
            "agent_settled" -> result = result.copy(state = "idle")
            "ui_prompt_start" -> result = result.copy(state = "blocked")
            "ui_prompt_end" -> result = result.copy(state = "working")
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

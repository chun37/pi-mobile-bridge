package com.chun.pibridge

import org.json.JSONArray
import org.json.JSONObject

data class DebugSection(val title: String, val rows: List<Pair<String, String>>)

private fun debugValue(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "未取得"
    is String -> value.trim().take(1024).ifBlank { "未取得" }
    is Number, is Boolean -> value.toString()
    else -> "未取得"
}

fun supportsSessionDebug(hello: JSONObject?): Boolean = hello?.optJSONArray("capabilities")?.let { caps ->
    (0 until caps.length()).any { caps.optString(it) == "sessionDebug" }
} == true

/** Never retain arbitrary hello fields (tokens, paths or future extension data). */
fun debugConnectionInfo(hello: JSONObject): JSONObject = JSONObject().apply {
    for (key in listOf("protocol", "relayVersion", "herdrVersion", "herdrProtocol")) {
        val value = hello.opt(key)
        if (value is String || value is Number || value is Boolean) put(key, value)
    }
    val caps = hello.optJSONArray("capabilities")
    put("capabilities", JSONArray((0 until (caps?.length() ?: 0)).mapNotNull {
        (caps?.opt(it) as? String)?.take(128)
    }.take(32)))
}

fun debugSections(
    appVersion: String, androidVersion: String, connected: Boolean, endpoint: String,
    hello: JSONObject?, session: JSONObject?, snapshot: JSONObject?,
): List<DebugSection> {
    val relay = snapshot?.optJSONObject("relay")
    val herdr = snapshot?.optJSONObject("herdr")
    val bridge = snapshot?.optJSONObject("bridge")
    val runtime = bridge?.optJSONObject("runtime")
    val remoteSession = snapshot?.optJSONObject("session")
    fun row(label: String, value: Any?) = label to debugValue(value)
    return listOf(
        DebugSection("Androidアプリ", listOf(row("アプリ", appVersion), row("Android", androidVersion))),
        DebugSection("接続", listOf(row("状態", if (connected) "接続中" else "未接続（接続時の情報）"),
            row("接続先", endpoint), row("Bridgeプロトコル", hello?.opt("protocol")), row("取得日時", snapshot?.opt("collectedAt")))),
        DebugSection("PC relay（実行中）", listOf(
            row("relay", relay?.opt("version") ?: hello?.opt("relayVersion")), row("PID", relay?.opt("pid")),
            row("Go", relay?.opt("goVersion")), row("OS / CPU", relay?.let { "${debugValue(it.opt("os"))} / ${debugValue(it.opt("arch"))}" }),
            row("ホスト名", relay?.opt("hostname")), row("ビルドコミット", relay?.opt("revision")),
            row("未コミット変更を含むビルド", relay?.opt("modified")), row("起動日時", relay?.opt("startedAt")),
            row("実行ファイルは置換・削除済み", relay?.opt("executableReplaced")))),
        DebugSection("Herdrサーバー", listOf(row("Herdr", herdr?.opt("version") ?: hello?.opt("herdrVersion")),
            row("Herdrプロトコル", herdr?.opt("protocol") ?: hello?.opt("herdrProtocol")))),
        DebugSection("選択中のPi（実行中）", listOf(row("Pi", runtime?.opt("piVersion")),
            row("Bridge拡張", bridge?.opt("bridgeVersion")), row("Node.js", runtime?.opt("nodeVersion")),
            row("OS / CPU", runtime?.let { "${debugValue(it.opt("platform"))} / ${debugValue(it.opt("arch"))}" }),
            row("PID", bridge?.opt("pid")), row("モデル", remoteSession?.opt("model") ?: session?.opt("model")),
            row("thinking", remoteSession?.opt("thinkingLevel") ?: session?.opt("thinkingLevel")))),
        DebugSection("セッション識別情報", listOf(row("pane", remoteSession?.opt("paneId") ?: session?.opt("sessionKey")),
            row("セッションID", remoteSession?.opt("sessionId") ?: session?.opt("sessionId")),
            row("拡張インスタンスID", remoteSession?.opt("instanceId") ?: session?.opt("instanceId")))),
    )
}

fun debugReport(sections: List<DebugSection>): String = sections.joinToString("\n\n") { section ->
    section.title + "\n" + section.rows.joinToString("\n") { (label, value) -> "$label: $value" }
}

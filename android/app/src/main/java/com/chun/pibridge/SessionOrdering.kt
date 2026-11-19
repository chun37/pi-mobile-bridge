package com.chun.pibridge

import org.json.JSONObject

enum class SessionSort { DEFAULT, RECENT }

data class SessionGroup(val workspace: String?, val sessions: List<JSONObject>)

fun sessionTitle(session: JSONObject): String =
    listOf("sessionName", "tab", "paneId", "sessionKey")
        .mapNotNull { session.opt(it) as? String }
        .firstOrNull { it.isNotBlank() } ?: "セッション"

fun sessionUsageKey(profileId: String, session: JSONObject): String =
    org.json.JSONArray(listOf(profileId,
        (session.opt("sessionId") as? String)?.takeIf { it.isNotBlank() }
            ?: session.optString("sessionKey"))).toString()

fun sessionGroups(
    sessions: List<JSONObject>,
    sort: SessionSort,
    lastOpened: (JSONObject) -> Long = { 0L },
): List<SessionGroup> {
    val byName = compareBy<JSONObject> { sessionTitle(it) }.thenBy { it.optString("sessionKey") }
    if (sort == SessionSort.RECENT) {
        return listOf(SessionGroup(null, sessions.sortedWith(
            compareByDescending<JSONObject> {
                maxOf(lastOpened(it), it.optLong("lastActivityAt", 0L), 0L)
            }.then(byName)
        )))
    }
    return sessions.groupBy { (it.opt("workspace") as? String).orEmpty() }.toSortedMap()
        .map { (workspace, group) ->
            SessionGroup(workspace, group.sortedWith(
                compareByDescending<JSONObject> { it.optString("state") == "blocked" }
                    .thenByDescending { it.optBoolean("bridge") }.then(byName)
            ))
        }
}

package com.chun.pibridge

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** Session list grouped by workspace with search, sort and terminate actions. */

@Composable
fun ColumnScope.SessionList(store: BridgeStore, state: BridgeState) {
    var sort by remember { mutableStateOf(store.profiles.sessionSort) }
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(sort) { listState.scrollToItem(0) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("セッション", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(SessionSort.DEFAULT to "デフォルト", SessionSort.RECENT to "最近使った順")
                .forEach { (value, label) ->
                    FilterChip(
                        selected = sort == value,
                        onClick = {
                            store.profiles.sessionSort = value
                            sort = value
                        },
                        label = { Text(label) },
                        modifier = Modifier.testTag("sessionSort:${value.name}"),
                    )
                }
        }
    }
    OutlinedTextField(
        query,
        { query = it },
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("sessionSearch"),
        placeholder = { Text("名前・フォルダーで検索") },
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, null) },
        shape = RoundedCornerShape(14.dp),
    )
    val filtered =
        state.sessions.map { session -> JSONObject(session.toString()).put("state", state.sessionUi(session.optString("sessionKey")).phase) }.filter {
            it.optBoolean("bridge") &&
                (query.isBlank() ||
                    listOf("sessionName", "tab", "cwd", "workspace").any { field ->
                        it.optString(field).contains(query, true)
                    })
        }
    val groups = sessionGroups(filtered, sort) { store.profiles.lastOpened(state.profileId, it) }
    if (filtered.isEmpty())
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.ChatBubbleOutline,
                null,
                Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (state.connected) "セッションが見つかりません" else "PCに接続して作業を続ける",
                Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "接続先と検索条件を確認してください。",
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    else
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("sessions"),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            groups.forEach { (workspace, sessions) ->
                    if (workspace != null) item(key = "workspace:$workspace") {
                        Text(
                            workspace,
                            Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp)
                                .testTag("workspaceHeader:$workspace"),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(
                        sessions,
                        key = { it.optString("sessionKey") },
                    ) { s ->
                        val c = state.chats[s.optString("sessionKey")]
                        val last =
                            c?.entries
                                ?.lastOrNull {
                                    it.optJSONObject("message")?.optString("role") == "assistant"
                                }
                                ?.optJSONObject("message")
                                ?.textContent()
                                ?.lineSequence()
                                ?.firstOrNull() ?: s.optString("lastResponse")
                        SessionItemActions(
                            session = s,
                            profileId = state.profileId,
                            connected = state.connected,
                            supported = state.canTerminateSession,
                            terminate = store::terminateSession,
                            onClick = { store.select(s.optString("sessionKey")) },
                        ) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        sessionTitle(s),
                                        Modifier.weight(1f).padding(end = 8.dp),
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    StateBadge(state.sessionUi(s.optString("sessionKey")).phase)
                                }
                                if (last.isNotBlank())
                                    Text(
                                        last,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (s.optBoolean("bridge")) Icons.Default.FolderOpen
                                        else Icons.Default.Terminal,
                                        null,
                                        Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        s.optString("cwd").replace(Regex("^/home/[^/]+/"), "~/"),
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (
                                        c != null &&
                                            s.optString("lastEntryId").isNotEmpty() &&
                                            c.leaf != s.optString("lastEntryId")
                                    )
                                        Text(
                                            "未読",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                }
                            }
                        }
                    }
                }
        }
}

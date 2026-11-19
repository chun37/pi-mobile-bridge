package com.chun.pibridge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The only mutation is an explicit confirmation, with the same ID used on retry. */
@Composable
fun NewSessionAction(
    connected: Boolean,
    loadWorkspaces: suspend () -> List<JSONObject>,
    create: suspend (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var showing by remember { mutableStateOf(false) }
    var workspaces by remember { mutableStateOf(emptyList<JSONObject>()) }
    var selected by remember { mutableStateOf("") }
    var requestId by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    fun load() {
        loading = true
        scope.launch {
            try {
                workspaces = loadWorkspaces()
                selected = workspaces.firstOrNull()?.optString("id") ?: ""
                if (workspaces.isEmpty()) error = "作成先のワークスペースがありません"
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { error = e.message ?: "一覧を取得できませんでした"
            } finally { loading = false }
        }
    }

    FloatingActionButton(
        onClick = {
            if (connected && !showing) {
                showing = true
                requestId = UUID.randomUUID().toString()
                workspaces = emptyList()
                selected = ""
                error = ""
                attempted = false
                load()
            }
        },
        modifier = modifier.testTag("newSession").semantics { if (!connected) disabled() },
        containerColor = if (connected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
    ) { Icon(Icons.Default.Add, contentDescription = "新しいセッション") }

    if (showing) AlertDialog(
        onDismissRequest = { if (!creating) showing = false },
        title = { Text("新しいセッション") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("作成先を選んでください。新しいタブでPiを起動します。")
                if (loading || creating) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (creating) "新しいタブで起動中…" else "ワークスペースを取得中…")
                }
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    workspaces.forEach { ws ->
                        val id = ws.optString("id")
                        Row(
                            Modifier.fillMaxWidth().testTag("workspace:$id")
                                .clickable(enabled = !creating && !attempted) { selected = id }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected == id, onClick = null)
                            Text(ws.optString("label").ifBlank { id })
                        }
                    }
                }
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                if (!loading && workspaces.isEmpty()) TextButton(onClick = { error = ""; load() }) {
                    Text("再読込")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = connected && !loading && !creating && selected.isNotEmpty(),
                modifier = Modifier.testTag("createSessionConfirm"),
                onClick = {
                    creating = true
                    attempted = true
                    error = ""
                    scope.launch {
                        try {
                            create(selected, requestId)
                            showing = false
                        } catch (e: CancellationException) { throw e
                        } catch (e: Exception) {
                            error = (e.message ?: "作成結果を確認できませんでした") +
                                "\n再確認は同じリクエストで行います。結果不明のまま新規作成を繰り返さないでください。"
                        } finally { creating = false }
                    }
                },
            ) { Text(if (attempted && !creating) "作成結果を再確認" else "新しいタブで開始") }
        },
        dismissButton = {
            TextButton(enabled = !creating, onClick = { showing = false }) { Text("キャンセル") }
        },
    )
}

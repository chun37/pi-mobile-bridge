package com.chun.pibridge

import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalDebugButton(
    terminal: Boolean,
    profileId: String,
    connected: Boolean,
    endpoint: String,
    hello: JSONObject?,
    session: JSONObject?,
    onToggle: () -> Unit,
    load: suspend (String, String, String) -> JSONObject,
) {
    val pane = session?.optString("sessionKey").orEmpty()
    val sid = session?.optString("sessionId").orEmpty()
    val instance = session?.optString("instanceId").orEmpty()
    key(profileId, pane, sid, instance, endpoint) {
        var showing by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(false) }
        var snapshot by remember { mutableStateOf<JSONObject?>(null) }
        var notice by remember { mutableStateOf("") }
        var refresh by remember { mutableIntStateOf(0) }
        LaunchedEffect(showing, connected, hello, refresh) {
            if (!showing) return@LaunchedEffect
            snapshot = null
            notice = ""
            loading = false
            when {
                !connected -> notice = "未接続です。接続時に取得した情報だけを表示します。"
                !supportsSessionDebug(hello) -> notice = "このrelayは詳細情報に未対応です。PC側の更新後に接続を切断して再接続してください。"
                sid.isBlank() || instance.isBlank() -> notice = "対象の識別情報が未取得です。チャットを開き直してください。"
                else -> {
                    loading = true
                    try {
                        val result = load(pane, sid, instance)
                        currentCoroutineContext().ensureActive()
                        snapshot = result
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) { notice = e.message ?: "デバッグ情報を取得できませんでした"
                    } finally { loading = false }
                }
            }
        }
        Surface(
            modifier = Modifier.testTag("terminalToggle").combinedClickable(
                onClick = onToggle, onLongClickLabel = "接続先のデバッグ情報", onLongClick = { showing = true }),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (terminal) Icons.Default.ChatBubbleOutline else Icons.Default.Terminal, null, Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (terminal) "チャット" else "ターミナル", style = MaterialTheme.typography.labelLarge)
            }
        }
        if (showing) {
            val sections = debugSections(
                "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})",
                "${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}", connected, endpoint, hello, session, snapshot,
                BuildConfig.VERSION_NAME, BuildConfig.BRIDGE_REVISION, BuildConfig.BRIDGE_SOURCE_STATE,
            )
            val clipboard = LocalClipboardManager.current
            Dialog(onDismissRequest = { showing = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(Modifier.fillMaxSize().testTag("debugScreen")) {
                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("接続先のデバッグ情報", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            IconButton(onClick = { showing = false }, Modifier.testTag("debugClose")) { Icon(Icons.Default.Close, "閉じる") }
                        }
                        Row(Modifier.padding(horizontal = 12.dp)) {
                            TextButton(onClick = { refresh++ }, enabled = connected && supportsSessionDebug(hello) && !loading,
                                modifier = Modifier.testTag("debugRefresh")) { Text("再取得") }
                            TextButton(onClick = { clipboard.setText(AnnotatedString(debugReport(sections))) },
                                modifier = Modifier.testTag("debugCopy")) { Text("コピー") }
                        }
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("debugLoading"))
                        SelectionContainer(Modifier.weight(1f)) {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (notice.isNotBlank()) Text(notice, Modifier.testTag("debugNotice"), color = MaterialTheme.colorScheme.error)
                                Text("実行中のプロセスが返した情報です。未対応の値は「未取得」と表示します。共有前に接続先やセッションIDを確認してください。",
                                    style = MaterialTheme.typography.bodySmall)
                                if (snapshot?.optJSONObject("relay")?.optBoolean("executableReplaced") == true)
                                    Text("relayの実行ファイルが置き換わっています。PC接続を切断して再接続すると新しいプロセスに切り替わります。",
                                        color = MaterialTheme.colorScheme.error)
                                if (snapshot != null && snapshot?.optJSONObject("bridge")?.optJSONObject("runtime")?.optString("piVersion").isNullOrBlank())
                                    Text("Piの詳細バージョンは未取得です。必要なら対象Piの処理終了後に /reload してください。",
                                        style = MaterialTheme.typography.bodySmall)
                                sections.forEach { section ->
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(section.title, style = MaterialTheme.typography.titleSmall)
                                        section.rows.forEach { (label, value) ->
                                            Text("$label: $value", Modifier.testTag("debugRow:${section.title}:$label"),
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

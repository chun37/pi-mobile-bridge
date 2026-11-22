package com.chun.pibridge

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

data class SessionTerminationTarget(
    val profileId: String, val sessionKey: String, val sessionId: String,
    val instanceId: String, val tabId: String, val title: String,
) {
    fun matches(session: JSONObject): Boolean =
        session.optString("sessionKey") == sessionKey && session.optString("sessionId") == sessionId &&
            session.optString("instanceId") == instanceId && session.optString("tabId") == tabId &&
            session.optBoolean("bridge")

    fun params(): JSONObject = JSONObject().put("sessionKey", sessionKey)
        .put("expectedSessionId", sessionId).put("expectedInstanceId", instanceId).put("expectedTabId", tabId)

    companion object {
        fun from(profileId: String, session: JSONObject): SessionTerminationTarget? {
            val values = listOf("sessionKey", "sessionId", "instanceId", "tabId").map { session.opt(it) as? String }
            if (profileId.isBlank() || !session.optBoolean("bridge") || values.any { it.isNullOrBlank() }) return null
            return SessionTerminationTarget(profileId, values[0]!!, values[1]!!, values[2]!!, values[3]!!, sessionTitle(session))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionItemActions(
    session: JSONObject,
    profileId: String,
    connected: Boolean,
    supported: Boolean,
    terminate: suspend (SessionTerminationTarget) -> Unit,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showing by remember(profileId) { mutableStateOf(false) }
    var target by remember(profileId) { mutableStateOf<SessionTerminationTarget?>(null) }
    var busy by remember(profileId) { mutableStateOf(false) }
    var attempted by remember(profileId) { mutableStateOf(false) }
    var error by remember(profileId) { mutableStateOf("") }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("session:${session.optString("sessionKey")}")
            .combinedClickable(onClick = onClick, onLongClickLabel = "セッションを閉じる", onLongClick = {
                if (!showing) {
                    target = SessionTerminationTarget.from(profileId, session)
                    attempted = false
                    error = ""
                    showing = true
                }
            }),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f),
        content = content,
    )
    if (showing) {
        val valid = target?.let { it.profileId == profileId && it.matches(session) } == true
        AlertDialog(
            modifier = Modifier.testTag("terminateSessionDialog"),
            onDismissRequest = { if (!busy) showing = false },
            title = { Text("セッションを閉じますか？") },
            text = { Column {
                Text(target?.title ?: sessionTitle(session))
                target?.let { Text("pane: ${it.sessionKey}\ntab: ${it.tabId}", style = MaterialTheme.typography.bodySmall) }
                Text("Piと対応するpaneを終了します。タブにほかのpaneがなければタブも閉じます。")
                Text("実行中の処理は中断され、未送信の入力は失われます。保存済みの会話履歴やファイルは削除しません。")
                if (!connected) Text("PCに接続してください。")
                else if (!supported) Text("PC側の更新後に、接続を切断して再接続してください。")
                else if (!valid) Text("対象が変わりました。一覧から確認し直してください。")
                if (busy) { LinearProgressIndicator(); Text("終了中…") }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(
                modifier = Modifier.testTag("terminateSessionConfirm"),
                enabled = connected && supported && valid && !busy && !attempted,
                onClick = {
                    val confirmed = target ?: return@TextButton
                    busy = true
                    attempted = true
                    scope.launch {
                        try { terminate(confirmed); showing = false
                        } catch (e: CancellationException) { throw e
                        } catch (e: Exception) {
                            error = (e.message ?: "終了結果を確認できませんでした") +
                                "\n自動では再実行しません。ダイアログを閉じて一覧を確認してください。"
                        } finally { busy = false }
                    }
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("閉じる") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { showing = false },
                modifier = Modifier.testTag("terminateSessionCancel")) { Text(if (attempted) "一覧に戻る" else "キャンセル") } },
        )
    }
}

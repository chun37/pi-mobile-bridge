package com.chun.pibridge

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Display-only, in-memory PC output. Not a user message or a model input. */
data class ReloadOutput(val sessionId: String, val requestId: String, val phase: String, val text: String = "")

@Composable
fun ReloadOutputCard(output: ReloadOutput, onTerminal: () -> Unit, onDismiss: () -> Unit) {
    Surface(tonalElevation = 2.dp, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("reloadOutput")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(when (output.phase) {
                "complete" -> "再読み込み完了"
                "failed" -> "再読み込み失敗"
                "loading" -> "再読み込みの表示を確認中…"
                else -> "再読み込みを検出（完了表示は未確認）"
            }, style = MaterialTheme.typography.titleSmall,
                color = if (output.phase == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (output.text.isNotBlank()) SelectionContainer {
                Text(output.text, Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()).testTag("reloadOutputText"),
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("PCの表示範囲から取得した結果です。会話やモデルの入力には追加しません。",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                TextButton(onClick = onTerminal) { Text("ターミナルを見る") }
                TextButton(onClick = onDismiss) { Text("閉じる") }
            }
        }
    }
}

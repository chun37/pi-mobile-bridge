package com.chun.pibridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Execution phase label and badge shared by the session list and chat. */

fun stateLabel(state: String) =
    when (state) {
        "working" -> "実行中"
        "blocked" -> "応答待ち"
        "done" -> "完了"
        "aborted" -> "中断済み"
        "error" -> "エラー"
        "idle" -> "待機中"
        "unknown" -> "状態不明"
        else -> "ターミナル"
    }

@Composable
fun StateBadge(state: String) {
    val color =
        when (state) {
            "working" -> Color(0xff6cdec8)
            "blocked" -> Color(0xffffcf7b)
            "done" -> Color(0xff9ab4ff)
            "error" -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Surface(color = color.copy(alpha = .12f), shape = RoundedCornerShape(8.dp)) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(5.dp).background(color, RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(6.dp))
            Text(stateLabel(state), color = color, style = MaterialTheme.typography.labelSmall)
        }
    }
}

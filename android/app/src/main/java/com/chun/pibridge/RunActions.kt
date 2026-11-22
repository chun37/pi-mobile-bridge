package com.chun.pibridge

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun RunActions(execution: SessionUiState, delivery: String, onDelivery: (String) -> Unit, onAbort: () -> Unit) {
    if (!execution.working) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = delivery == "followUp", onClick = { onDelivery("followUp") },
            label = { Text("次に送る") }, modifier = Modifier.testTag("followUp"))
        FilterChip(selected = delivery == "steer", onClick = { onDelivery("steer") },
            label = { Text("割り込む") }, modifier = Modifier.testTag("steer"))
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onAbort, enabled = execution.canAbort, modifier = Modifier.testTag("abort")) {
            Icon(Icons.Default.Stop, null, Modifier.size(16.dp))
            Text("中断")
        }
    }
}

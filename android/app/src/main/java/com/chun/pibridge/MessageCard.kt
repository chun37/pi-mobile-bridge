package com.chun.pibridge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

/** Markdown text, chat message bubbles and tool call cards. */

@Composable
fun Markdown(text: String, color: Color = Color(0xffe4eaf2)) {
    AndroidView(
        factory = { MarkdownMessageView(it) },
        update = { it.bind(text, color.toArgb()) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun MessageCard(entry: JSONObject, entries: List<JSONObject>, showThinking: Boolean = true) {
    val m = entry.optJSONObject("message")
    val role = m?.optString("role") ?: ""
    if (!isChatEntryVisible(entry, entries, showThinking)) return
    if (entry.has("note")) {
        Text(
            "${entry.optString("note")}: ${entry.optString("text")}",
            Modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Box(
        Modifier.fillMaxWidth(),
        contentAlignment = if (role == "user") Alignment.TopEnd else Alignment.TopStart,
    ) {
        Surface(
            Modifier.then(
                if (role == "user") Modifier.widthIn(max = 340.dp) else Modifier.fillMaxWidth()
            ),
            shape = RoundedCornerShape(18.dp),
            color =
                if (role == "user") MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent,
        ) {
            Column(
                Modifier.padding(if (role == "user") 14.dp else 2.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (role == "user" && entry.optString("origin") == "pc")
                    Text(
                        "PCから",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                if (entry.has("toolEvent"))
                    ToolCard(
                        entry.optString("toolName"),
                        entry.optJSONObject("args")?.toString() ?: "",
                        entry.optJSONObject("result")?.textContent()
                            ?: entry.optJSONObject("partialResult")?.textContent()
                            ?: "実行中",
                        entry.optBoolean("isError"),
                        entry.takeIf { it.has("durationMs") }?.optLong("durationMs"),
                        completed = entry.optString("toolEvent") == "tool_execution_end",
                        images = toolImageParts(null, entry),
                    )
                else if (m != null) {
                    if (m.opt("content") is String) Markdown(m.optString("content"))
                    else
                        m.optJSONArray("content").objects().forEach { part ->
                            when (part.optString("type")) {
                                "text" -> {
                                    val text = part.optString("text")
                                    if (text.isNotBlank()) Markdown(text)
                                }
                                "thinking" -> {
                                    val thought = part.opt("thinking") as? String
                                    if (showThinking && !thought.isNullOrBlank()) {
                                        Markdown(thought, MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                "image" -> ImagePreview(part)
                                "toolCall" -> {
                                    val result =
                                        entries
                                            .lastOrNull {
                                                !it.optBoolean("streaming") &&
                                                    it.optJSONObject("message")
                                                        ?.optString("toolCallId") ==
                                                        part.optString("id")
                                            }
                                            ?.optJSONObject("message")
                                    val live =
                                        entries.lastOrNull {
                                            it.optString("toolCallId") == part.optString("id")
                                        }
                                    ToolCard(
                                        part.optString("name"),
                                        part.optJSONObject("arguments")?.toString() ?: "",
                                        result?.textContent()
                                            ?: live?.optJSONObject("result")?.textContent()
                                            ?: live?.optJSONObject("partialResult")?.textContent()
                                            ?: "実行中",
                                        result?.optBoolean("isError")
                                            ?: live?.optBoolean("isError")
                                            ?: false,
                                        result
                                            ?.takeIf { it.has("durationMs") }
                                            ?.optLong("durationMs")
                                            ?: live
                                                ?.takeIf { it.has("durationMs") }
                                                ?.optLong("durationMs"),
                                        completed = result != null ||
                                            live?.optString("toolEvent") == "tool_execution_end",
                                        images = toolImageParts(result, live),
                                    )
                                }
                            }
                        }
                }
            }
        }
    }
}

@Composable
fun ToolCard(
    name: String,
    args: String,
    output: String,
    error: Boolean,
    durationMs: Long? = null,
    completed: Boolean = false,
    images: List<JSONObject> = emptyList(),
) {
    val status = if (!completed) "実行中" else if (error) "失敗" else "成功"
    val statusColor = when {
        !completed -> MaterialTheme.colorScheme.onSurfaceVariant
        error -> MaterialTheme.colorScheme.error
        else -> Color(0xff66bb6a)
    }
    var expanded by remember { mutableStateOf(false) }
    val argument = runCatching { JSONObject(args) }.getOrNull()
    val summary =
        argument?.optString("command")?.ifBlank { null }
            ?: argument?.optString("path")?.ifBlank { null }
            ?: args
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (completed) statusColor.copy(alpha = .14f)
            else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (!completed) Icons.Default.Terminal
                    else if (error) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                    null,
                    Modifier.size(18.dp),
                    tint = statusColor,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "ツール: $name",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    status,
                    Modifier.padding(horizontal = 8.dp).testTag("toolStatus:$status"),
                    style = MaterialTheme.typography.labelLarge,
                    color = statusColor,
                )
                durationMs?.let {
                    Text(
                        "$it ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp
                    else Icons.Default.KeyboardArrowDown,
                    null,
                )
            }
            if (summary.isNotBlank())
                Text(
                    if (expanded) argument?.toString(2) ?: args else summary,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            Text(
                if (expanded) output else output.lineSequence().take(3).joinToString("\n"),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            images.forEach { ImagePreview(it) }
        }
    }
}

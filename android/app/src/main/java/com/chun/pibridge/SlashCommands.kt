package com.chun.pibridge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.json.JSONObject

fun supportsSlash(session: JSONObject?): Boolean = session?.optJSONArray("capabilities")?.let { a ->
    (0 until a.length()).any { a.optString(it) == "slashCommands" }
} ?: false

fun contextPercent(session: JSONObject?): String {
    val percent = session?.optJSONObject("contextUsage")?.optDouble("percent", Double.NaN) ?: Double.NaN
    return if (percent.isFinite() && percent >= 0) String.format(Locale.ROOT, "%.1f%%", percent) else "--%"
}

fun modelThinkingLabel(session: JSONObject?): String {
    val model = (session?.opt("model") as? String)?.ifBlank { null } ?: return "同じPCセッション"
    val level = (session.opt("thinkingLevel") as? String)?.trim()?.ifBlank { null }
    return if (level == null) model else "$model ($level)"
}

@Composable
fun ModelContextLabel(session: JSONObject?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(modelThinkingLabel(session),
            Modifier.testTag("modelThinkingLabel").weight(1f, fill = false), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val percent = contextPercent(session)
        Text(percent, Modifier.testTag("contextUsage").semantics { contentDescription = "コンテキスト使用率 $percent" },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

data class SlashQuery(val kind: String, val prefix: String, val start: Int)
data class SlashOption(val value: String, val label: String, val description: String, val start: Int)

fun slashQuery(input: TextFieldValue): SlashQuery? {
    if (!input.selection.collapsed) return null
    val before = input.text.substring(0, input.selection.start)
    Regex("^/([^\\s/]*)$").matchEntire(before)?.let { return SlashQuery("command", it.groupValues[1], 1) }
    Regex("^/(model|thinking) +([^\\s]*)$").matchEntire(before)?.let {
        return SlashQuery(it.groupValues[1], it.groupValues[2], it.groups[2]!!.range.first)
    }
    return null
}

fun slashOptions(input: TextFieldValue, catalog: JSONObject?): List<SlashOption> {
    val query = slashQuery(input) ?: return emptyList()
    val candidates = when (query.kind) {
        "command" -> catalog?.optJSONArray("commands").objects()
            .sortedBy { if (it.optString("source") == "builtin") 0 else 1 }
            .map { SlashOption(it.optString("name"), "/${it.optString("name")}", it.optString("description"), query.start) }
        "model" -> {
            if (catalog?.optJSONArray("commands").objects().none {
                it.optString("name") == "model" && it.optString("source") == "builtin" }) return emptyList()
            catalog?.optJSONArray("models").objects().map {
                SlashOption(it.optString("value"), it.optString("value"), it.optString("name"), query.start)
            }
        }
        "thinking" -> {
            if (catalog?.optJSONArray("commands").objects().none {
                it.optString("name") == "thinking" && it.optString("source") == "builtin" }) return emptyList()
            val levels = catalog?.optJSONArray("thinkingLevels")
            (0 until (levels?.length() ?: 0)).map {
                val value = levels!!.optString(it)
                SlashOption(value, value, "思考レベル", query.start)
            }
        }
        else -> emptyList()
    }
    return candidates.filter { it.value.isNotEmpty() && it.value.startsWith(query.prefix, ignoreCase = true) }
        .distinctBy { it.value }
}

fun completeSlash(input: TextFieldValue, option: SlashOption): TextFieldValue {
    if (slashQuery(input)?.start != option.start || option.start !in 0..input.text.length) return input
    var end = option.start
    while (end < input.text.length && !input.text[end].isWhitespace()) end++
    val tail = input.text.substring(end)
    val separator = if (tail.isEmpty()) " " else ""
    val result = input.text.substring(0, option.start) + option.value + separator + tail
    val cursor = (option.start + option.value.length + 1).coerceAtMost(result.length)
    return TextFieldValue(result, TextRange(cursor))
}

@Composable
fun SlashSuggestions(input: TextFieldValue, catalog: JSONObject?, loading: Boolean, error: String,
    onSelect: (SlashOption) -> Unit) {
    if (slashQuery(input) == null) return
    val options = slashOptions(input, catalog)
    Surface(tonalElevation = 4.dp, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("slashSuggestions")) {
        when {
            loading -> Text("コマンド候補を取得中…", Modifier.padding(12.dp))
            error.isNotEmpty() -> Text(error, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            options.isEmpty() -> Text("一致する候補がありません", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            else -> LazyColumn(Modifier.heightIn(max = 200.dp)) {
                items(options, key = { it.value }) { option ->
                    Column(Modifier.fillMaxWidth().clickable { onSelect(option) }
                        .testTag("slash:${option.value}").padding(horizontal = 12.dp, vertical = 7.dp)) {
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                        if (option.description.isNotBlank()) Text(option.description,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

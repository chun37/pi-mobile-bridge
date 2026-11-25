package com.chun.pibridge

import java.io.ByteArrayOutputStream
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject

/** Chat and terminal view for the selected session, including drafts and attachments. */

@Composable
fun ColumnScope.ChatScreen(store: BridgeStore, state: BridgeState) {
    val key = state.selected
    val session = state.sessions.find { it.optString("sessionKey") == key }
    val chat = state.chats[key] ?: Conversation()
    var terminal by remember(key) { mutableStateOf(session?.optBoolean("bridge") != true) }
    var text by remember(key) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    var terminalText by remember(key) { mutableStateOf("") }
    var delivery by remember { mutableStateOf("followUp") }
    var images by remember(key) { mutableStateOf(emptyList<DraftImage>()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val execution = state.sessionUi(key)
    val working = execution.working
    val catalogScope = slashCatalogScope(text.text)
    LaunchedEffect(key, state.connected, session?.optString("sessionId"), session?.optString("instanceId"), supportsSlash(session), catalogScope) {
        if (state.connected && catalogScope != null) store.refreshCommands(key)
    }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null)
                scope.launch {
                    runCatching {
                            val im =
                                withContext(Dispatchers.IO) {
                                    val mime =
                                        context.contentResolver.getType(uri) ?: error("画像形式が不明です")
                                    require(
                                        mime in
                                            listOf(
                                                "image/png",
                                                "image/jpeg",
                                                "image/webp",
                                                "image/gif",
                                            )
                                    )
                                    val bytes =
                                        context.contentResolver.openInputStream(uri)!!.use { input
                                            ->
                                            val out = ByteArrayOutputStream()
                                            val chunk = ByteArray(8192)
                                            while (out.size() <= 4 * 1024 * 1024) {
                                                val count = input.read(chunk)
                                                if (count < 0) break
                                                out.write(chunk, 0, count)
                                            }
                                            out.toByteArray()
                                        }
                                    require(bytes.size <= 4 * 1024 * 1024) { "画像は4MiB以下にしてください" }
                                    JSONObject()
                                        .put("mimeType", mime)
                                        .put(
                                            "data",
                                            android.util.Base64.encodeToString(
                                                bytes,
                                                android.util.Base64.NO_WRAP,
                                            ),
                                        )
                                }
                            require(images.size < 4) { "画像は4枚までです" }
                            images = images + DraftImage(im)
                        }
                        .onFailure { store.error(it) }
                }
        }
    LaunchedEffect(terminal, key, state.connected) {
        while (terminal && state.connected) {
            runCatching { store.terminalRead(key) }.onFailure { store.error(it) }
            delay(1000)
        }
    }
    BackHandler(terminal) {
        if (session?.optBoolean("bridge") == true) terminal = false else store.select("")
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { store.select("") }, modifier = Modifier.testTag("backToSessions")) {
            Icon(Icons.Default.ArrowBack, "一覧へ戻る")
        }
        Column(Modifier.weight(1f)) {
            Text(
                session?.optString("sessionName")?.ifBlank { key } ?: key,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ModelContextLabel(session)
        }
        TerminalDebugButton(
            terminal = terminal, profileId = state.profileId, connected = state.connected,
            endpoint = state.endpoint, hello = state.connectionInfo, session = session,
            onToggle = { terminal = !terminal }, load = store::sessionDebug,
        )
    }
    if (terminal) {
        Surface(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            color = Color(0xff090d12),
            shape = RoundedCornerShape(12.dp),
        ) {
            SelectionContainer {
                Text(
                    state.terminal,
                    Modifier.fillMaxSize()
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                    softWrap = false,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                    "Enter" to "enter",
                    "Esc" to "esc",
                    "↑" to "up",
                    "↓" to "down",
                    "Ctrl+C" to "ctrl+c",
                )
                .forEach { (label, value) ->
                    OutlinedButton(
                        onClick = { store.terminalKeys(key, listOf(value)) },
                        enabled = state.connected,
                        contentPadding = PaddingValues(horizontal = 14.dp),
                    ) {
                        Text(label)
                    }
                }
        }
        OutlinedTextField(
            terminalText,
            { terminalText = it },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("terminalInput"),
            placeholder = { Text("ターミナルへの入力") },
            shape = RoundedCornerShape(12.dp),
        )
        Button(
            onClick = {
                store.terminalInput(key, terminalText)
                terminalText = ""
                keyboard?.hide()
            },
            enabled = state.connected && terminalText.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text("Enter付きで入力")
        }
    } else {
        val showThinking = store.profiles.showThinking
        val visibleEntries = visibleChatEntries(chat.entries, showThinking)
        val listState = rememberLazyListState()
        var positioned by remember(key) { mutableStateOf(false) }
        LaunchedEffect(key, visibleEntries.size, visibleEntries.lastOrNull()?.toString()) {
            val lastVisible =
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: Int.MAX_VALUE
            if (
                visibleEntries.isNotEmpty() &&
                    (!positioned ||
                        (!listState.isScrollInProgress &&
                            lastVisible >= visibleEntries.lastIndex - 2))
            ) {
                listState.scrollToItem(visibleEntries.lastIndex)
                positioned = true
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("chat"),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(visibleEntries, key = { it.optString("entryId") }) { entry ->
                Box(Modifier.fillMaxWidth().testTag("entry:${entry.optString("entryId")}")) {
                    MessageCard(entry, chat.entries, showThinking = showThinking)
                }
            }
            items(chat.pending) { p ->
                Text(
                    "${p.optString("text")} · ${p.optString("status")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (execution.blocked)
            FilledTonalButton(
                onClick = { terminal = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Icon(Icons.Default.Terminal, null)
                Spacer(Modifier.width(8.dp))
                Text("ターミナルで応答")
            }
        state.reloadOutputs[key]?.takeIf { it.sessionId == session?.optString("sessionId") }?.let { output ->
            ReloadOutputCard(output, onTerminal = { terminal = true }, onDismiss = { store.dismissReloadOutput(key) })
        }
        Surface(tonalElevation = 3.dp) {
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RunActions(execution, delivery, { delivery = it }, { store.abort(key) })
                AttachmentStrip(images, onRemove = { id -> images = removeDraftImage(images, id) })
                if (state.commandNotice.isNotBlank()) Text(state.commandNotice,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("commandNotice"))
                SlashSuggestions(text, state.slashCatalog, state.slashLoading, state.slashError,
                    onRefresh = { store.refreshCommands(key) }) {
                    text = completeSlash(text, it)
                }
                if (working && text.text.startsWith("/")) Text("実行の完了後にコマンドを送信できます",
                    style = MaterialTheme.typography.bodySmall)
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(
                        onClick = {
                            picker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        modifier = Modifier.testTag("attachImage"),
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, "画像を添付")
                    }
                    OutlinedTextField(
                        text,
                        { text = it },
                        Modifier.weight(1f).testTag("messageInput"),
                        placeholder = { Text("メッセージを入力…") },
                        maxLines = 5,
                        shape = RoundedCornerShape(20.dp),
                    )
                    FilledIconButton(
                        onClick = {
                            if (store.send(key, text.text, draftImagePayload(images), delivery)) {
                                text = androidx.compose.ui.text.input.TextFieldValue("")
                                images = emptyList()
                                keyboard?.hide()
                            }
                        },
                        enabled = state.connected && (text.text.isNotBlank() || images.isNotEmpty()) &&
                            (!text.text.startsWith("/") || (!working && images.isEmpty() && supportsSlash(session))),
                        modifier = Modifier.size(52.dp).testTag("send"),
                    ) {
                        Icon(Icons.Default.Send, "送信")
                    }
                }
            }
        }
    }
}

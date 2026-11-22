package com.chun.pibridge

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.noties.markwon.Markwon
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import org.json.JSONObject

private val BridgeColors =
    darkColorScheme(
        primary = Color(0xff6cdec8),
        onPrimary = Color(0xff002c26),
        primaryContainer = Color(0xff17463f),
        onPrimaryContainer = Color(0xffd2fff4),
        background = Color(0xff0e141b),
        surface = Color(0xff0e141b),
        surfaceVariant = Color(0xff1c2632),
        onSurface = Color(0xffe4eaf2),
        onSurfaceVariant = Color(0xff9eacbd),
        outline = Color(0xff394657),
        error = Color(0xffffb4b4),
    )

class MainActivity : FragmentActivity() {
    private var unlocked by mutableStateOf(false)
    private val imageDownloads by lazy {
        androidx.lifecycle.ViewModelProvider(this)[ImageDownloads::class.java]
    }
    private val imageSaveLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        imageDownloads.complete(if (it.resultCode == RESULT_OK) it.data?.data else null)
    }

    internal fun saveImage(asset: ImageAsset) {
        if (!imageDownloads.begin(asset)) return
        try {
            imageSaveLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType(asset.mimeType)
                .putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                    android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download"))
                .putExtra(Intent.EXTRA_TITLE, asset.filename))
        } catch (_: Exception) { imageDownloads.launchFailed() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33)
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            CompositionLocalProvider(LocalImageDownload provides ::saveImage) {
                MaterialTheme(colorScheme = BridgeColors) {
                    Surface(Modifier.fillMaxSize()) {
                        if (unlocked) BridgeApp()
                        else
                            Column(
                                Modifier.fillMaxSize().padding(24.dp),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text("Pi Bridge", style = MaterialTheme.typography.headlineMedium)
                                Text("生体認証でPCへの接続を許可してください", Modifier.padding(vertical = 16.dp))
                                Button(onClick = { authenticate() }) { Text("生体認証で開く") }
                            }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!Profiles(this).biometric) unlocked = true else authenticate()
    }

    override fun onStop() {
        super.onStop()
        if (Profiles(this).biometric) unlocked = false
    }

    private fun authenticate() {
        BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult
                    ) {
                        unlocked = true
                    }
                },
            )
            .authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Pi Bridge を開く")
                    .setSubtitle("PCへの接続を許可")
                    .setNegativeButtonText("キャンセル")
                    .build()
            )
    }
}

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

@Composable
fun BridgeApp() {
    val context = LocalContext.current
    val store = remember { BridgeStore.get(context) }
    val state by store.state.collectAsState()
    var profiles by remember { mutableStateOf(store.profiles.all()) }
    var chosen by remember {
        mutableStateOf(startupProfile(profiles, store.profiles.lastProfileId)?.id ?: "")
    }
    var settings by remember { mutableStateOf(profiles.isEmpty()) }
    val active =
        profiles.find { it.id == (state.profileId.ifEmpty { chosen }) }
            ?: profiles.find { it.id == chosen }
            ?: profiles.firstOrNull()
    fun connect(p: Profile) {
        chosen = p.id
        ContextCompat.startForegroundService(
            context,
            Intent(context, BridgeService::class.java).putExtra("profileId", p.id),
        )
    }
    LaunchedEffect(store) { store.claimStartupProfile()?.let { connect(it) } }
    BackHandler(settings || state.selected.isNotEmpty()) {
        if (settings) settings = false else store.select("")
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        if (settings) {
            Row(
                Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { settings = false }) { Icon(Icons.Default.ArrowBack, "戻る") }
                Text("設定", style = MaterialTheme.typography.titleLarge)
            }
        } else if (state.selected.isEmpty()) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Pi Bridge", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "PCの作業を、どこからでも",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "設定") }
            }
        }
        if (!settings) {
            ConnectionCard(
                active,
                profiles,
                state,
                onConnect = { connect(it) },
                onAutomatic = { p ->
                    val updated = p.automatic()
                    store.profiles.save(updated)
                    profiles = store.profiles.all()
                    connect(updated)
                },
                onRoute = { p, host ->
                    val updated = p.at(host)
                    store.profiles.save(updated)
                    profiles = store.profiles.all()
                    connect(updated)
                },
                onDisconnect = {
                    context.stopService(Intent(context, BridgeService::class.java))
                    store.disconnect()
                },
                onSettings = { settings = true },
            )
        }
        if (state.error.isNotEmpty())
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    Modifier.padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        state.error,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = { store.clearError() }) {
                        Icon(Icons.Default.Close, "エラーを閉じる")
                    }
                }
            }
        if (state.offeredPin.isNotEmpty())
            AlertDialog(
                onDismissRequest = {},
                title = { Text("PCのホスト鍵を確認") },
                text = {
                    SelectionContainer {
                        Text(
                            "${state.endpoint}\n\n${state.offeredPin}\n\nPC上のssh-keygenで確認した値と照合してください。登録した全アドレスで、この鍵を検証します。"
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            active?.let {
                                val updated = it.copy(pin = state.offeredPin)
                                store.profiles.save(updated)
                                profiles = store.profiles.all()
                                connect(updated)
                            }
                        }
                    ) {
                        Text("照合して保存")
                    }
                },
                dismissButton = { TextButton(onClick = { store.disconnect() }) { Text("拒否") } },
            )
        if (settings)
            Box(Modifier.weight(1f)) {
                SettingsScreen(
                    store,
                    profiles,
                    onSave = {
                        store.profiles.save(it)
                        profiles = store.profiles.all()
                        chosen = it.id
                    },
                    onRemove = {
                        if (state.profileId == it) {
                            context.stopService(Intent(context, BridgeService::class.java))
                            store.disconnect()
                        }
                        store.profiles.remove(it)
                        profiles = store.profiles.all()
                    },
                    onConnect = { id ->
                        settings = false
                        profiles.find { it.id == id }?.let { connect(it) }
                    },
                )
            }
        else if (state.selected.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize()) { SessionList(store, state) }
                NewSessionAction(
                    connected = state.connected,
                    loadWorkspaces = {
                        store.request("workspaces.list").optJSONArray("workspaces").objects()
                    },
                    create = { workspace, requestId -> store.createSession(workspace, requestId) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                )
            }
        } else ChatScreen(store, state)
    }
}

@Composable
private fun ConnectionCard(
    active: Profile?,
    profiles: List<Profile>,
    state: BridgeState,
    onConnect: (Profile) -> Unit,
    onRoute: (Profile, String) -> Unit,
    onAutomatic: (Profile) -> Unit,
    onDisconnect: () -> Unit,
    onSettings: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .65f),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Computer,
                null,
                Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    active?.name ?: "PCを登録",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "${state.status} · ${if (state.connected) state.endpoint else active?.host.orEmpty()}",
                    Modifier.testTag("connectionStatus"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (state.connected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                TextButton(
                    onClick = { if (active == null) onSettings() else menu = true },
                    modifier = Modifier.testTag("routePicker"),
                ) {
                    Text(
                        active?.let {
                            if (it.autoSelect)
                                "自動 · ${addressLabel(state.endpoint.ifEmpty{it.connectionHosts.first()})}"
                            else addressLabel(it.host)
                        } ?: "設定"
                    )
                    Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    profiles.forEach { p ->
                        Text(
                            p.name,
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("自動（LAN優先）")
                                    Text(
                                        "LAN → Tailscale",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            },
                            onClick = {
                                menu = false
                                onAutomatic(p)
                            },
                            leadingIcon = {
                                Icon(
                                    if (p.id == active?.id && p.autoSelect) Icons.Default.Check
                                    else Icons.Default.Sync,
                                    null,
                                )
                            },
                            modifier = Modifier.testTag("route:auto:${p.id}"),
                        )
                        p.hosts.forEach { host ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(addressLabel(host))
                                        Text(
                                            host,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    menu = false
                                    onRoute(p, host)
                                },
                                leadingIcon = {
                                    Icon(
                                        if (
                                            !p.autoSelect &&
                                                host == active?.host &&
                                                p.id == active.id
                                        )
                                            Icons.Default.Check
                                        else Icons.Default.Dns,
                                        null,
                                    )
                                },
                                modifier = Modifier.testTag("route:$host"),
                            )
                        }
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("接続設定を編集") },
                        onClick = {
                            menu = false
                            onSettings()
                        },
                        leadingIcon = { Icon(Icons.Default.Settings, null) },
                    )
                }
            }
            if (state.connected)
                IconButton(onClick = onDisconnect) { Icon(Icons.Default.LinkOff, "切断") }
            else
                FilledTonalButton(
                    onClick = { active?.let(onConnect) },
                    enabled = active != null,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text("接続")
                }
        }
    }
}

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
                        Surface(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .testTag("session:${s.optString("sessionKey")}"),
                            onClick = { store.select(s.optString("sessionKey")) },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f),
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

@Composable
fun SettingsScreen(
    store: BridgeStore,
    profiles: List<Profile>,
    onSave: (Profile) -> Unit,
    onRemove: (String) -> Unit,
    onConnect: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(profiles.isEmpty()) }
    var name by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var automatic by remember { mutableStateOf(true) }
    val hosts = remember { mutableStateListOf("") }
    var remove by remember { mutableStateOf<Profile?>(null) }
    var security by remember { mutableStateOf(false) }
    var biometric by remember { mutableStateOf(store.profiles.biometric) }
    var showThinking by remember { mutableStateOf(store.profiles.showThinking) }
    var autoConnect by remember { mutableStateOf(store.profiles.autoConnect) }
    fun edit(p: Profile?) {
        editing = p?.id
        creating = p == null
        name = p?.name ?: ""
        user = p?.user ?: ""
        port = p?.port?.toString() ?: "22"
        automatic = p?.autoSelect ?: true
        hosts.clear()
        hosts.addAll(p?.hosts ?: listOf(""))
    }
    if (remove != null)
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text("PCの登録を解除しますか？") },
            text = { Text("${remove?.name} の接続設定を外します。PCのSSH鍵や会話ファイルは削除しません。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        remove?.let { onRemove(it.id) }
                        remove = null
                    }
                ) {
                    Text("登録解除")
                }
            },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("キャンセル") } },
        )
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("settings"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (creating || editing != null) {
            Text(if (creating) "PCを追加" else "PCの接続設定", style = MaterialTheme.typography.titleLarge)
            Text(
                "同じPCのLANとTailscaleをまとめて登録します。会話とホスト鍵は共有されます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                name,
                { name = it },
                Modifier.fillMaxWidth().testTag("profileName"),
                label = { Text("PCの名前") },
                placeholder = { Text("例：自宅のPC") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    user,
                    { user = it },
                    Modifier.weight(1f).testTag("user"),
                    label = { Text("SSHユーザー") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
                OutlinedTextField(
                    port,
                    { port = it },
                    Modifier.width(100.dp).testTag("port"),
                    label = { Text("ポート") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("LANを優先して自動接続")
                    Text(
                        "LANが使えない場合だけTailscaleを試します",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(automatic, { automatic = it }, modifier = Modifier.testTag("autoSelect"))
            }
            Text("接続アドレス", style = MaterialTheme.typography.titleSmall)
            hosts.indices.forEach { i ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        hosts[i],
                        { hosts[i] = it },
                        Modifier.weight(1f).testTag(if (i == 0) "host" else "address:$i"),
                        label = {
                            Text(
                                if (hosts[i].isBlank()) "IPアドレス / ホスト名" else addressLabel(hosts[i])
                            )
                        },
                        placeholder = { Text(if (i == 0) "192.168.1.100" else "100.x.x.x") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                    if (hosts.size > 1)
                        IconButton(onClick = { hosts.removeAt(i) }) {
                            Icon(Icons.Default.Close, "アドレスを削除")
                        }
                }
            }
            TextButton(onClick = { hosts.add("") }, modifier = Modifier.testTag("addAddress")) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(6.dp))
                Text("接続先を追加")
            }
            profiles
                .find { it.id == editing }
                ?.takeIf { it.pin.isNotEmpty() }
                ?.let {
                    Text(
                        "ホスト鍵は固定済みです。追加したアドレスにも同じ鍵を要求します。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        creating = false
                        editing = null
                    }
                ) {
                    Text("キャンセル")
                }
                Button(
                    onClick = {
                        val addresses =
                            hosts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                        val n = port.toIntOrNull()
                        if (
                            name.isBlank() ||
                                user.isBlank() ||
                                addresses.isEmpty() ||
                                addresses.any { a -> a.any { it.isWhitespace() } || '/' in a } ||
                                n == null ||
                                n !in 1..65535
                        )
                            store.error(
                                IllegalArgumentException("PC名、ユーザー、接続アドレス、1〜65535のポートを入力してください")
                            )
                        else {
                            val old = profiles.find { it.id == editing }
                            onSave(
                                Profile(
                                    id = editing ?: java.util.UUID.randomUUID().toString(),
                                    name = name.trim(),
                                    host =
                                        old?.host?.takeIf { it in addresses } ?: addresses.first(),
                                    user = user.trim(),
                                    port = n,
                                    pin = old?.pin ?: "",
                                    addresses = addresses,
                                    autoSelect = automatic,
                                )
                            )
                            creating = false
                            editing = null
                            store.clearError()
                        }
                    },
                    Modifier.weight(1f).testTag("saveProfile"),
                ) {
                    Text("保存")
                }
            }
        } else {
            Text("登録したPC", style = MaterialTheme.typography.titleMedium)
            profiles.forEach { p ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Computer, null)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                p.name,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            IconButton(
                                onClick = { edit(p) },
                                modifier = Modifier.testTag("edit:${p.id}"),
                            ) {
                                Icon(Icons.Default.Edit, "編集")
                            }
                        }
                        p.hosts.forEach { host ->
                            Row {
                                Text(
                                    addressLabel(host),
                                    Modifier.width(76.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(host, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { onConnect(p.id) }) { Text("接続") }
                            TextButton(onClick = { remove = p }) {
                                Text("登録解除", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { edit(null) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("PCを追加")
            }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("起動時にPCへ接続")
                Text(
                    "次回起動時、最後に使ったPCへ自動接続します",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = autoConnect,
                onCheckedChange = {
                    store.profiles.autoConnect = it
                    autoConnect = it
                },
                modifier = Modifier.testTag("autoConnect"),
            )
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("思考を表示")
                Text(
                    "モデルから届いた思考を会話内に表示します",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = showThinking,
                onCheckedChange = {
                    store.profiles.showThinking = it
                    showThinking = it
                },
                modifier = Modifier.testTag("showThinking"),
            )
        }
        HorizontalDivider()
        TextButton(onClick = { security = !security }) {
            Icon(Icons.Default.Key, null)
            Spacer(Modifier.width(8.dp))
            Text("SSH公開鍵とセキュリティ")
            Icon(
                if (security) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                null,
            )
        }
        if (security) {
            Text(
                "公開鍵をPCのauthorized_keysに登録します。秘密鍵は端末から出ません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    scope.launch {
                        runCatching {
                                val key = withContext(Dispatchers.IO) { store.identity.publicKey() }
                                context
                                    .getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(
                                        ClipData.newPlainText("Pi Bridge public key", key)
                                    )
                            }
                            .onFailure { store.error(it) }
                    }
                }
            ) {
                Icon(Icons.Default.ContentCopy, null)
                Spacer(Modifier.width(8.dp))
                Text("SSH公開鍵をコピー")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("起動時に生体認証")
                    Text(
                        "PCへの操作を端末の認証で保護",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    biometric,
                    {
                        if (
                            !it ||
                                androidx.biometric.BiometricManager.from(context)
                                    .canAuthenticate(
                                        androidx.biometric.BiometricManager.Authenticators
                                            .BIOMETRIC_WEAK
                                    ) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
                        ) {
                            biometric = it
                            store.profiles.biometric = it
                        } else store.error(IllegalStateException("端末の設定で生体認証を登録してください"))
                    },
                )
            }
        }
        Text(
            "外出先ではTailscaleを接続してから、接続先をTailscaleに切り替えてください。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun ColumnScope.ChatScreen(store: BridgeStore, state: BridgeState) {
    val key = state.selected
    val session = state.sessions.find { it.optString("sessionKey") == key }
    val chat = state.chats[key] ?: Conversation()
    var terminal by remember(key) { mutableStateOf(session?.optBoolean("bridge") != true) }
    var text by remember(key) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    var terminalText by remember(key) { mutableStateOf("") }
    var delivery by remember { mutableStateOf("followUp") }
    var images by remember(key) { mutableStateOf(emptyList<JSONObject>()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val execution = state.sessionUi(key)
    val working = execution.working
    LaunchedEffect(key, state.connected, session?.optString("sessionId"), supportsSlash(session), text.text.startsWith("/"), state.slashCatalog) {
        if (state.connected && text.text.startsWith("/") && state.slashCatalog == null) store.refreshCommands(key)
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
                            images = images + im
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
        TextButton(onClick = { terminal = !terminal }) {
            Icon(
                if (terminal) Icons.Default.ChatBubbleOutline else Icons.Default.Terminal,
                null,
                Modifier.size(18.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(if (terminal) "チャット" else "ターミナル")
        }
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
                if (images.isNotEmpty())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Image,
                            null,
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "画像 ${images.size}",
                            Modifier.padding(horizontal = 8.dp).testTag("attachmentCount"),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        TextButton(onClick = { images = emptyList() }) { Text("添付解除") }
                    }
                if (state.commandNotice.isNotBlank()) Text(state.commandNotice,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("commandNotice"))
                SlashSuggestions(text, state.slashCatalog, state.slashLoading, state.slashError) {
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
                            if (store.send(key, text.text, images, delivery)) {
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

@Composable
fun Markdown(text: String, color: Color = Color(0xffe4eaf2)) {
    val context = LocalContext.current
    val markwon = remember { Markwon.create(context) }
    AndroidView(
        factory = {
            TextView(it).apply {
                setTextColor(color.toArgb())
                setTextIsSelectable(true)
                textSize = 15f
                setLineSpacing(4f, 1.08f)
            }
        },
        update = {
            it.setTextColor(color.toArgb())
            markwon.setMarkdown(it, text)
        },
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

package com.chun.pibridge

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

/** PC profiles, startup connection, thinking display, SSH key and biometric settings. */

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

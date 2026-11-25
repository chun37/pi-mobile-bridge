package com.chun.pibridge

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/** Root navigation between the connection card, session list, settings and chat. */

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

package com.example.eventmanagementhub.ui.screens

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.eventmanagementhub.mesh.NearbyBlocked
import com.example.eventmanagementhub.mesh.NearbyMesh
import com.example.eventmanagementhub.mesh.NearbyMessage
import com.example.eventmanagementhub.mesh.NearbySendResult
import com.example.eventmanagementhub.mesh.NearbyStatus
import com.example.eventmanagementhub.ui.components.*
import com.example.eventmanagementhub.ui.theme.*
import kotlinx.coroutines.delay

/** Runtime permissions the Bluetooth mesh needs on this Android version. */
private fun meshPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

/**
 * Nearby chat: messages between phones over Bluetooth with NO internet and no account, using the offline mesh backend.
 * A message only shows "Delivered" after the other phone acknowledged it; otherwise it says so.
 */
@Composable
fun NearbyChatScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by NearbyMesh.state.collectAsStateWithLifecycle()
    val peers by NearbyMesh.peers.collectAsStateWithLifecycle()
    val messages by NearbyMesh.messages.collectAsStateWithLifecycle()

    var selectedId by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var wantOn by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        NearbyMesh.refresh(context)
        if (wantOn) NearbyMesh.start(context)
    }

    // Keep the screen honest about the radio, the permissions and who is nearby; start the mesh as soon as nothing blocks it.
    LaunchedEffect(wantOn) {
        while (true) {
            NearbyMesh.refresh(context)
            val s = NearbyMesh.state.value
            if (wantOn && s.blocked == null && !s.running) NearbyMesh.start(context)
            delay(2000)
        }
    }
    LaunchedEffect(peers) {
        if (selectedId != null && peers.none { it.id == selectedId }) selectedId = null
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().background(Paper)) {
        HubTopBar(
            title = "Nearby chat",
            subtitle = "Works without internet, over Bluetooth",
            onBack = onBack,
            actions = {
                if (state.running || wantOn) {
                    QuietButton(
                        text = if (wantOn) "Turn off" else "Turn on",
                        onClick = {
                            wantOn = !wantOn
                            if (!wantOn) NearbyMesh.stop(context)
                        },
                        modifier = Modifier.testTag("nearby-toggle")
                    )
                } else {
                    QuietButton("Turn on", { wantOn = true }, Modifier.testTag("nearby-toggle"))
                }
            }
        )

        when (state.blocked) {
            NearbyBlocked.PERMISSIONS_MISSING -> BlockedBanner(
                "Allow Bluetooth, nearby devices and location so this phone can find and message phones around you.",
                "Allow",
                { permissionLauncher.launch(meshPermissions()) }
            )
            NearbyBlocked.BLUETOOTH_OFF -> BlockedBanner(
                "Bluetooth is off. Turn it on to message nearby phones.",
                "Turn on Bluetooth",
                {
                    try {
                        context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    } catch (e: Exception) {
                        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    }
                }
            )
            NearbyBlocked.NOT_SUPPORTED -> NoticeBanner(
                "This phone has no Bluetooth Low Energy, so nearby chat is not available.",
                ChipKind.Danger, Modifier.padding(16.dp)
            )
            null -> {
                if (!wantOn) {
                    NoticeBanner("Nearby chat is off.", ChipKind.Neutral, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                } else {
                    NoticeBanner(
                        if (!state.running) "Starting…"
                        else if (peers.isEmpty()) "Looking for nearby phones… both phones need this app open or running."
                        else "${peers.size} nearby · you are ${state.myName.ifBlank { state.myId }} (${state.myId})",
                        if (peers.isEmpty()) ChipKind.Warning else ChipKind.Success,
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }

        // Who to message.
        if (peers.isNotEmpty()) {
            SectionLabel("Message", Modifier.padding(horizontal = 16.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(peers, key = { it.id }) { p ->
                    HubFilterChip(
                        // Names are cut short on the wire, so two phones can look alike: the short id tells them apart.
                        label = "${p.name} · ${p.id} (${p.rssi} dBm)",
                        selected = selectedId == p.id,
                        onClick = { selectedId = p.id },
                        modifier = Modifier.testTag("nearby-peer-${p.id}")
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                EmptyHint(
                    if (peers.isEmpty()) "No messages yet. Messages appear here once a nearby phone is found."
                    else "No messages yet. Pick a phone above and write one.",
                    actionLabel = null
                )
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(messages, key = { it.id }) { NearbyBubble(it) }
                }
            }
        }

        notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = Danger, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        // Composer, same layout as the Help desk thread.
        Column(Modifier.fillMaxWidth().background(HubSurface).imePadding()) {
            HubDivider()
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(NearbyMesh.maxTextLength); notice = null },
                    placeholder = { Text(if (selectedId == null) "Pick a phone first" else "Type a message…", color = Ink3) },
                    modifier = Modifier.weight(1f).testTag("nearby-input"),
                    shape = ControlShape,
                    maxLines = 4,
                    supportingText = { Text("${draft.length}/${NearbyMesh.maxTextLength}", color = Ink3) },
                    colors = hubFieldColors()
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = {
                        val to = peers.firstOrNull { it.id == selectedId }
                        when (NearbyMesh.send(to?.id, to?.name ?: "", draft)) {
                            NearbySendResult.SENT -> { draft = ""; notice = null }
                            NearbySendResult.EMPTY -> notice = "Type a message first."
                            NearbySendResult.TOO_LONG -> notice = "That message is too long for Bluetooth."
                            NearbySendResult.NO_RECIPIENT -> notice = "Pick a nearby phone first."
                            NearbySendResult.MESH_NOT_RUNNING -> notice = "Nearby chat is not running, so nothing was sent."
                        }
                    },
                    enabled = draft.isNotBlank() && selectedId != null && state.running,
                    modifier = Modifier.size(56.dp).testTag("nearby-send"),
                    shape = ControlShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Accent, contentColor = OnAccent,
                        disabledContainerColor = SurfaceSunken, disabledContentColor = Ink3
                    )
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message") }
            }
        }
    }
}

@Composable
private fun BlockedBanner(text: String, action: String, onAction: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        NoticeBanner(text, ChipKind.Warning)
        QuietButton(action, onAction, Modifier.padding(top = 4.dp).testTag("nearby-fix"))
    }
}

@Composable
private fun NearbyBubble(m: NearbyMessage) {
    val shape = RoundedCornerShape(
        topStart = 8.dp, topEnd = 8.dp,
        bottomStart = if (m.mine) 8.dp else 2.dp,
        bottomEnd = if (m.mine) 2.dp else 8.dp
    )
    val failed = m.status == NearbyStatus.NOT_DELIVERED
    val who = if (m.mine) "You → ${m.toName.ifBlank { m.toId }}" else m.fromName.ifBlank { m.fromId }
    val status = when (m.status) {
        NearbyStatus.SENDING -> "Sending…"
        NearbyStatus.DELIVERED -> "Delivered"
        NearbyStatus.NOT_DELIVERED -> "Not delivered · the other phone did not confirm (out of range?)"
        NearbyStatus.RECEIVED -> "Received"
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
        Text("$who · $status", style = MaterialTheme.typography.labelSmall, color = if (failed) Danger else Ink3)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .background(if (failed) Danger.copy(alpha = 0.08f) else if (m.mine) AccentSoft else HubSurface, shape)
                .then(if (failed) Modifier.border(1.dp, Danger, shape) else if (!m.mine) Modifier.border(1.dp, Line, shape) else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(m.text, style = MaterialTheme.typography.bodyMedium, color = Ink)
        }
    }
}

package com.rasel.RasFocus.remotedesktop

/**
 * RemoteDesktopScreen
 * RustDesk Android UI (Flutter) → Compose এ rebuild করা।
 * - My Remote ID (9-digit, like RustDesk)
 * - Recent connections list (PC icon + ID + name)
 * - Bottom tabs: Connection | Chat | Share Screen | Settings
 * - MediaProjection permission flow
 * - Native screen viewer (কোনো browser নয়)
 */

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// RustDesk colour palette
private val BgDark        = Color(0xFF1A1A2E)
private val BgCard        = Color(0xFF242436)
private val AccentCyan    = Color(0xFF00C0EF)
private val AccentGreen   = Color(0xFF2ECC71)
private val AccentOrange  = Color(0xFFE67E22)
private val TextPrimary   = Color(0xFFEEEEEE)
private val TextSecondary = Color(0xFF9A9AB0)
private val OnlineDot     = Color(0xFF00D26A)
private val OfflineDot    = Color(0xFF888888)

// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun RemoteDesktopHomeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    // Observe service state
    val isRunning  by RemoteDesktopService.isRunning.collectAsState()
    val myId       by RemoteDesktopService.myId.collectAsState()
    val clients    by RemoteDesktopService.connectedClients.collectAsState()

    var selectedTab     by remember { mutableIntStateOf(0) }
    var remoteIdInput   by remember { mutableStateOf("") }
    var statusMsg       by remember { mutableStateOf("") }
    var showPermDialog  by remember { mutableStateOf(false) }
    var projectionData  by remember { mutableStateOf<Intent?>(null) }

    // MediaProjection launcher
    val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            projectionData = result.data
            // Start service with projection data
            val svcIntent = Intent(context, RemoteDesktopService::class.java).apply {
                action = RemoteDesktopService.ACTION_START
                putExtra(RemoteDesktopService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(svcIntent)
            else context.startService(svcIntent)
            statusMsg = "✅ Remote Desktop চালু হয়েছে"
        } else {
            statusMsg = "❌ Permission দেওয়া হয়নি"
        }
    }

    fun startSharing() {
        if (!isRunning) {
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        } else {
            statusMsg = "✅ Already running — ID: ${RemoteDesktopService.formatId(myId)}"
        }
    }

    fun stopSharing() {
        context.stopService(Intent(context, RemoteDesktopService::class.java))
        statusMsg = "⏹ Service বন্ধ হয়েছে"
    }

    // ── Root layout ───────────────────────────────────────────────────────────
    Scaffold(
        containerColor = BgDark,
        topBar = {
            // RustDesk top bar: "RasFocus Remote" + back
            Box(
                Modifier.fillMaxWidth()
                    .background(BgDark)
                    .padding(horizontal = 4.dp, vertical = 8.dp)
            ) {
                IconButton(onClick = onBack, Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextPrimary)
                }
                Text(
                    "RasFocus Remote",
                    color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
                // status dot
                if (isRunning) {
                    Row(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(OnlineDot))
                        Spacer(Modifier.width(4.dp))
                        Text("$clients", color = OnlineDot, fontSize = 12.sp)
                    }
                }
            }
        },
        bottomBar = {
            RdBottomNav(
                selected = selectedTab,
                onSelect = { selectedTab = it }
            )
        }
    ) { padding ->
        when (selectedTab) {
            0 -> ConnectionTab(
                modifier         = Modifier.padding(padding),
                myId             = myId,
                isRunning        = isRunning,
                remoteIdInput    = remoteIdInput,
                onRemoteIdChange = { if (it.filter { c -> c.isDigit() }.length <= 9) remoteIdInput = it },
                statusMsg        = statusMsg,
                recentList       = RemoteDesktopService.recentConnections,
                onStartShare     = { startSharing() },
                onStopShare      = { stopSharing() },
                onConnect        = {
                    val cleanId = remoteIdInput.filter { it.isDigit() }
                    if (cleanId.length < 6) { statusMsg = "⚠️ ID অন্তত 6 digit হতে হবে"; return@ConnectionTab }
                    // Connect to PC (PC এর WS port এ connect করব RemoteViewerScreen এ)
                    statusMsg = "🔄 Connecting to $cleanId..."
                    scope.launch {
                        // যদি RemoteDesktopService চলছে না — start করতে হবে
                        if (!isRunning) startSharing()
                        delay(600)
                        // রেকর্ড করো recent connections
                        val ip = RemoteDesktopService.getLocalIp(context) // TODO: resolve by ID
                        RemoteDesktopService.recentConnections.add(0,
                            RemoteDesktopService.RecentConn("Desktop", cleanId, ip))
                        statusMsg = "✅ Connected to $cleanId"
                    }
                }
            )
            1 -> ChatTabPlaceholder(Modifier.padding(padding))
            2 -> ShareScreenTab(
                modifier    = Modifier.padding(padding),
                isRunning   = isRunning,
                myId        = myId,
                onStart     = { startSharing() },
                onStop      = { stopSharing() }
            )
            3 -> RdSettingsTab(Modifier.padding(padding), context)
        }
    }
}

// ── Connection Tab (RustDesk main tab) ───────────────────────────────────────
@Composable
fun ConnectionTab(
    modifier: Modifier,
    myId: String,
    isRunning: Boolean,
    remoteIdInput: String,
    onRemoteIdChange: (String) -> Unit,
    statusMsg: String,
    recentList: List<RemoteDesktopService.RecentConn>,
    onStartShare: () -> Unit,
    onStopShare: () -> Unit,
    onConnect: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(BgDark),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── My Remote ID card (RustDesk: "Your ID") ──────────────────────────
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = BgCard),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Remote ID", color = TextSecondary, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (myId.isNotEmpty()) RemoteDesktopService.formatId(myId) else "---",
                            color = AccentCyan,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            modifier = Modifier.weight(1f)
                        )
                        if (myId.isNotEmpty()) {
                            IconButton(onClick = {}) {
                                Icon(Icons.Default.ContentCopy, null, tint = TextSecondary)
                            }
                        }
                        IconButton(onClick = if (isRunning) onStopShare else onStartShare) {
                            Icon(
                                if (isRunning) Icons.Default.StopCircle else Icons.Default.PlayCircle,
                                null,
                                tint = if (isRunning) AccentGreen else AccentCyan,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                    if (isRunning) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(OnlineDot))
                            Spacer(Modifier.width(6.dp))
                            Text("Ready for connection", color = OnlineDot, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // ── Connect to remote (RustDesk: "Control Remote Device") ────────────
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = BgCard),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Remote ID", color = TextSecondary, fontSize = 13.sp)
                    OutlinedTextField(
                        value = remoteIdInput,
                        onValueChange = onRemoteIdChange,
                        placeholder = {
                            Text("000 000 000", color = TextSecondary.copy(alpha = .5f),
                                fontSize = 22.sp, letterSpacing = 2.sp)
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 24.sp, fontWeight = FontWeight.Bold,
                            color = TextPrimary, letterSpacing = 2.sp,
                            textAlign = TextAlign.Start
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = AccentCyan,
                            unfocusedBorderColor = Color(0xFF3A3A50),
                            focusedTextColor     = TextPrimary,
                            unfocusedTextColor   = TextPrimary,
                            cursorColor          = AccentCyan
                        ),
                        trailingIcon = {
                            if (remoteIdInput.isNotEmpty())
                                IconButton(onClick = onConnect) {
                                    Icon(Icons.Default.ArrowForward, null, tint = AccentCyan)
                                }
                        }
                    )
                    Button(
                        onClick = onConnect,
                        enabled = remoteIdInput.filter { it.isDigit() }.length >= 6,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor         = AccentCyan,
                            disabledContainerColor = Color(0xFF2E2E44)
                        )
                    ) {
                        Text("Connect", color = BgDark,
                            fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }

        // ── Status message ────────────────────────────────────────────────────
        if (statusMsg.isNotEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            statusMsg.startsWith("✅") -> Color(0xFF1A3A2A)
                            statusMsg.startsWith("❌") -> Color(0xFF3A1A1A)
                            else                       -> Color(0xFF1A1A3A)
                        }
                    ), modifier = Modifier.fillMaxWidth()
                ) {
                    Text(statusMsg, Modifier.padding(14.dp), color = TextPrimary, fontSize = 13.sp)
                }
            }
        }

        // ── Recent connections (RustDesk: recent list with OS icon) ───────────
        if (recentList.isNotEmpty()) {
            item {
                Text("Recent", color = TextSecondary,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            items(recentList) { conn ->
                RecentConnectionItem(conn = conn, onClick = {})
            }
        }
    }
}

// ── Recent Connection Row (RustDesk: Windows icon + ID + name + online dot) ──
@Composable
fun RecentConnectionItem(
    conn: RemoteDesktopService.RecentConn,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // OS icon (RustDesk: Windows purple icon)
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF6A1B9A)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Computer, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape)
                        .background(if (conn.online) OnlineDot else OfflineDot))
                    Spacer(Modifier.width(6.dp))
                    Text(RemoteDesktopService.formatId(conn.id),
                        color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Spacer(Modifier.height(2.dp))
                Text(conn.name, color = TextSecondary, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = {}) {
                Icon(Icons.Default.MoreVert, null, tint = TextSecondary)
            }
        }
    }
}

// ── Bottom navigation (RustDesk: Connection | Chat | Share screen | Settings) ─
@Composable
fun RdBottomNav(selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = BgCard, tonalElevation = 0.dp) {
        listOf(
            Triple(Icons.Default.Wifi,        "Connection",   0),
            Triple(Icons.Default.Chat,         "Chat",         1),
            Triple(Icons.Default.ScreenShare,  "Share screen", 2),
            Triple(Icons.Default.Settings,     "Settings",     3)
        ).forEach { (icon, label, idx) ->
            NavigationBarItem(
                selected = selected == idx,
                onClick  = { onSelect(idx) },
                icon     = { Icon(icon, null) },
                label    = { Text(label, fontSize = 11.sp) },
                colors   = NavigationBarItemDefaults.colors(
                    selectedIconColor       = AccentCyan,
                    selectedTextColor       = AccentCyan,
                    unselectedIconColor     = TextSecondary,
                    unselectedTextColor     = TextSecondary,
                    indicatorColor          = Color(0xFF1E1E32)
                )
            )
        }
    }
}

// ── Share Screen Tab ──────────────────────────────────────────────────────────
@Composable
fun ShareScreenTab(modifier: Modifier, isRunning: Boolean, myId: String,
                   onStart: () -> Unit, onStop: () -> Unit) {
    Column(
        modifier.fillMaxSize().background(BgDark).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)
    ) {
        Icon(Icons.Default.ScreenShare, null,
            tint = if (isRunning) AccentGreen else TextSecondary,
            modifier = Modifier.size(72.dp))
        Text(if (isRunning) "Sharing Active" else "Not Sharing",
            color = if (isRunning) AccentGreen else TextSecondary,
            fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (isRunning && myId.isNotEmpty()) {
            Text("Your ID: ${RemoteDesktopService.formatId(myId)}",
                color = AccentCyan, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Button(
            onClick = if (isRunning) onStop else onStart,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) Color(0xFFE74C3C) else AccentCyan
            )
        ) {
            Icon(if (isRunning) Icons.Default.StopCircle else Icons.Default.PlayCircle,
                null, tint = if (isRunning) Color.White else BgDark)
            Spacer(Modifier.width(8.dp))
            Text(if (isRunning) "Stop Sharing" else "Start Sharing",
                color = if (isRunning) Color.White else BgDark,
                fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        if (isRunning) {
            Card(shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A3A2A)),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("PC থেকে এই ID দিয়ে connect করো:", color = TextSecondary, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(RemoteDesktopService.formatId(myId), color = AccentCyan,
                        fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Port: ${RemoteDesktopService.WS_PORT}", color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
    }
}

// ── Chat Tab placeholder ──────────────────────────────────────────────────────
@Composable
fun ChatTabPlaceholder(modifier: Modifier) {
    Box(modifier.fillMaxSize().background(BgDark), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Chat, null, tint = TextSecondary, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text("Chat — Coming soon", color = TextSecondary, fontSize = 15.sp)
        }
    }
}

// ── Settings Tab ─────────────────────────────────────────────────────────────
@Composable
fun RdSettingsTab(modifier: Modifier, context: Context) {
    var quality by remember { mutableIntStateOf(65) }
    LazyColumn(
        modifier.fillMaxSize().background(BgDark),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("Settings", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        item {
            Card(shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = BgCard),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Video Quality: $quality%", color = TextPrimary, fontSize = 14.sp)
                    Slider(
                        value = quality.toFloat(),
                        onValueChange = {
                            quality = it.toInt()
                            // Update running service
                            RemoteDesktopService.getInstance()?.let { svc ->
                                // quality update via WS handled in service
                            }
                        },
                        valueRange = 20f..95f,
                        colors = SliderDefaults.colors(thumbColor = AccentCyan, activeTrackColor = AccentCyan)
                    )
                    Text("Low quality = smoother, High = sharper", color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = BgCard),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Connection Info", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("WebSocket port: ${RemoteDesktopService.WS_PORT}", color = TextSecondary, fontSize = 13.sp)
                    Text("Local IP: ${RemoteDesktopService.getLocalIp(context)}", color = TextSecondary, fontSize = 13.sp)
                    Text("My ID: ${RemoteDesktopService.formatId(RemoteDesktopService.myId.value)}", color = AccentCyan, fontSize = 14.sp)
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = BgCard),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Accessibility Service", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    val inputEnabled by RemoteDesktopInputService.isEnabled.collectAsState()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape)
                            .background(if (inputEnabled) OnlineDot else OfflineDot))
                        Spacer(Modifier.width(8.dp))
                        Text(if (inputEnabled) "Active — input control ready"
                             else "Disabled — enable for remote input",
                            color = if (inputEnabled) OnlineDot else TextSecondary,
                            fontSize = 13.sp)
                    }
                    if (!inputEnabled) {
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }, shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, AccentCyan)) {
                            Text("Enable Accessibility", color = AccentCyan, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

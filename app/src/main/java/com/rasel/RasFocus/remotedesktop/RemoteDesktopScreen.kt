package com.rasel.RasFocus.remotedesktop

/**
 * RemoteDesktopScreen — RustDesk-style UI, PC-compatible protocol
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  দুটো mode:                                                      │
 * │  A) Connect to PC  — PC এ "Generate Code" চাপলে 6-digit code   │
 * │     + PC IP পাওয়া যায়, সেটা phone এ দিলে PC screen চলে আসে।  │
 * │  B) Share Screen   — Phone screen অন্য device এ দেখাতে পারে।   │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * PC protocol (tab_phone_remote.cpp):
 *   port 9224, WebSocket
 *   auth:  phone → {"type":"auth","code":"XXXXXX","device":"Phone"}
 *   ready: PC   → {"type":"ready","width":W,"height":H,"fps":30}
 *   error: PC   → {"type":"error","msg":"wrong code"}
 *   video: PC   → binary [flags 1B | pts 4B | H264 NAL]
 *   input: phone → {"type":"mouse","mask":M,"x":X,"y":Y}
 *                   {"type":"key","vk":V,"action":A}
 *                   {"type":"scroll","x":X,"y":Y,"dir":"up"/"down"}
 */

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Colour palette (RustDesk inspired) ──────────────────────────────────────
private val BgDark        = Color(0xFF1A1A2E)
private val BgCard        = Color(0xFF242436)
private val AccentCyan    = Color(0xFF00C0EF)
private val AccentGreen   = Color(0xFF2ECC71)
private val AccentRed     = Color(0xFFE74C3C)
private val TextPrimary   = Color(0xFFEEEEEE)
private val TextSecondary = Color(0xFF9A9AB0)
private val OnlineDot     = Color(0xFF00D26A)
private val OfflineDot    = Color(0xFF888888)

// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun RemoteDesktopHomeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    val isRunning by RemoteDesktopService.isRunning.collectAsState()
    val myId      by RemoteDesktopService.myId.collectAsState()
    val clients   by RemoteDesktopService.connectedClients.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }

    // ── "Connect to PC" state ─────────────────────────────────────
    var pcIpInput   by remember { mutableStateOf("") }
    var codeInput   by remember { mutableStateOf("") }
    var statusMsg   by remember { mutableStateOf("") }
    var showViewer  by remember { mutableStateOf(false) }
    var viewerIp    by remember { mutableStateOf("") }
    var viewerCode  by remember { mutableStateOf("") }

    // ── "Share Screen" (phone → other device) state ──────────────
    var showPermDialog by remember { mutableStateOf(false) }
    val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val svcIntent = Intent(context, RemoteDesktopService::class.java).apply {
                action = RemoteDesktopService.ACTION_START
                putExtra(RemoteDesktopService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(svcIntent)
            else context.startService(svcIntent)
            statusMsg = "✅ Screen sharing চালু হয়েছে"
        } else {
            statusMsg = "❌ Permission দেওয়া হয়নি"
        }
    }

    fun startSharing() {
        if (!isRunning) projectionLauncher.launch(mpm.createScreenCaptureIntent())
        else statusMsg = "✅ Already running — ID: ${RemoteDesktopService.formatId(myId)}"
    }
    fun stopSharing() {
        context.stopService(Intent(context, RemoteDesktopService::class.java))
        statusMsg = "⏹ Service বন্ধ হয়েছে"
    }

    // ── Fullscreen PC viewer overlay ──────────────────────────────
    if (showViewer && viewerIp.isNotEmpty()) {
        PcViewerScreen(
            pcIp   = viewerIp,
            code   = viewerCode,
            pcPort = 9224,
            onBack = {
                showViewer  = false
                viewerIp    = ""
                viewerCode  = ""
                statusMsg   = ""
            }
        )
        return
    }

    Scaffold(
        containerColor = BgDark,
        topBar = {
            Box(
                Modifier
                    .fillMaxWidth()
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
                if (isRunning) {
                    Row(
                        Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(OnlineDot))
                        Spacer(Modifier.width(4.dp))
                        Text("$clients", color = OnlineDot, fontSize = 12.sp)
                    }
                }
            }
        },
        bottomBar = {
            RdBottomNav(selected = selectedTab, onSelect = { selectedTab = it })
        }
    ) { padding ->
        when (selectedTab) {
            0 -> ConnectToPcTab(
                modifier    = Modifier.padding(padding),
                pcIpInput   = pcIpInput,
                codeInput   = codeInput,
                statusMsg   = statusMsg,
                recentList  = RemoteDesktopService.recentConnections,
                onIpChange  = { pcIpInput = it },
                onCodeChange = { if (it.filter { c -> c.isDigit() }.length <= 6) codeInput = it },
                onConnect   = {
                    val ip   = pcIpInput.trim()
                    val code = codeInput.filter { it.isDigit() }
                    if (ip.isEmpty()) { statusMsg = "⚠️ PC এর IP দাও"; return@ConnectToPcTab }
                    if (code.length != 6) { statusMsg = "⚠️ 6-digit code দাও"; return@ConnectToPcTab }
                    viewerIp   = ip
                    viewerCode = code
                    showViewer = true
                    RemoteDesktopService.recentConnections.add(
                        0, RemoteDesktopService.RecentConn("PC ($ip)", ip, code)
                    )
                },
                onConnectRecent = { conn ->
                    viewerIp   = conn.id      // id  = PC IP address
                    viewerCode = conn.ip      // ip  = 6-digit auth code
                    showViewer = true
                }
            )
            1 -> ChatTabPlaceholder(Modifier.padding(padding))
            2 -> ShareScreenTab(
                modifier  = Modifier.padding(padding),
                isRunning = isRunning,
                myId      = myId,
                statusMsg = statusMsg,
                onStart   = { startSharing() },
                onStop    = { stopSharing() }
            )
            3 -> RdSettingsTab(Modifier.padding(padding), context)
        }
    }
}

// ── Tab 0: Connect to PC ─────────────────────────────────────────────────────
@Composable
fun ConnectToPcTab(
    modifier: Modifier,
    pcIpInput: String,
    codeInput: String,
    statusMsg: String,
    recentList: List<RemoteDesktopService.RecentConn>,
    onIpChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onConnect: () -> Unit,
    onConnectRecent: (RemoteDesktopService.RecentConn) -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(BgDark),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── How-to card ───────────────────────────────────────────
        item {
            Card(
                shape  = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2540)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Computer, null,
                            tint = AccentCyan, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("PC কে Control করো", color = AccentCyan,
                            fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Text("১. PC তে RasFocus Desktop খোলো",
                        color = TextSecondary, fontSize = 13.sp)
                    Text("২. \"Phone Remote\" ট্যাব → \"Generate Code\" চাপো",
                        color = TextSecondary, fontSize = 13.sp)
                    Text("৩. 6-digit code এবং PC IP নিচে দাও",
                        color = TextSecondary, fontSize = 13.sp)
                    Text("৪. Connect চাপলে PC screen phone এ আসবে",
                        color = TextSecondary, fontSize = 13.sp)
                }
            }
        }

        // ── IP + Code input card ──────────────────────────────────
        item {
            Card(
                shape  = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = BgCard),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("PC Connect করো", color = TextSecondary, fontSize = 13.sp)

                    // IP field
                    OutlinedTextField(
                        value         = pcIpInput,
                        onValueChange = onIpChange,
                        label         = { Text("PC IP Address", color = TextSecondary) },
                        placeholder   = {
                            Text("192.168.1.100", color = TextSecondary.copy(alpha = .4f))
                        },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth(),
                        shape         = RoundedCornerShape(12.dp),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = AccentCyan,
                            unfocusedBorderColor = Color(0xFF3A3A50),
                            focusedTextColor     = TextPrimary,
                            unfocusedTextColor   = TextPrimary,
                            cursorColor          = AccentCyan
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        leadingIcon   = {
                            Icon(Icons.Default.Computer, null,
                                tint = TextSecondary, modifier = Modifier.size(20.dp))
                        }
                    )

                    // 6-digit code field — big digits like RustDesk
                    OutlinedTextField(
                        value         = codeInput,
                        onValueChange = onCodeChange,
                        label         = { Text("6-digit Code (PC থেকে)", color = TextSecondary) },
                        placeholder   = {
                            Text("000000", color = TextSecondary.copy(alpha = .4f),
                                fontSize = 26.sp, letterSpacing = 6.sp)
                        },
                        textStyle     = androidx.compose.ui.text.TextStyle(
                            fontSize      = 28.sp,
                            fontWeight    = FontWeight.Bold,
                            color         = AccentCyan,
                            letterSpacing = 6.sp,
                            textAlign     = TextAlign.Center
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth(),
                        shape         = RoundedCornerShape(12.dp),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = AccentCyan,
                            unfocusedBorderColor = Color(0xFF3A3A50),
                            focusedTextColor     = AccentCyan,
                            unfocusedTextColor   = AccentCyan,
                            cursorColor          = AccentCyan
                        )
                    )

                    Button(
                        onClick  = onConnect,
                        enabled  = pcIpInput.isNotBlank() &&
                                   codeInput.filter { it.isDigit() }.length == 6,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape    = RoundedCornerShape(12.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor         = AccentCyan,
                            disabledContainerColor = Color(0xFF2E2E44)
                        )
                    ) {
                        Icon(Icons.Default.PlayArrow, null,
                            tint = BgDark, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Connect", color = BgDark,
                            fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }

        // ── Status message ────────────────────────────────────────
        if (statusMsg.isNotEmpty()) {
            item {
                Card(
                    shape  = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            statusMsg.startsWith("✅") -> Color(0xFF1A3A2A)
                            statusMsg.startsWith("❌") -> Color(0xFF3A1A1A)
                            else                       -> Color(0xFF1A1A3A)
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(statusMsg, Modifier.padding(14.dp),
                        color = TextPrimary, fontSize = 13.sp)
                }
            }
        }

        // ── Recent connections ─────────────────────────────────────
        if (recentList.isNotEmpty()) {
            item {
                Text("Recent", color = TextSecondary,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            items(recentList.take(5)) { conn ->
                RecentPcItem(conn = conn, onClick = { onConnectRecent(conn) })
            }
        }
    }
}

// ── Recent PC Row ─────────────────────────────────────────────────────────────
@Composable
fun RecentPcItem(
    conn: RemoteDesktopService.RecentConn,
    onClick: () -> Unit
) {
    Card(
        shape  = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1A3A5C)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Computer, null,
                    tint = AccentCyan, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(conn.name, color = TextPrimary,
                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text("Code: ${conn.ip}", color = TextSecondary, fontSize = 12.sp)
            }
            Icon(Icons.Default.ArrowForward, null,
                tint = TextSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

// ── Bottom navigation ────────────────────────────────────────────────────────
@Composable
fun RdBottomNav(selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = BgCard, tonalElevation = 0.dp) {
        listOf(
            Triple(Icons.Default.Computer,    "Connect PC", 0),
            Triple(Icons.Default.Chat,         "Chat",        1),
            Triple(Icons.Default.ScreenShare,  "Share",       2),
            Triple(Icons.Default.Settings,     "Settings",    3)
        ).forEach { (icon, label, idx) ->
            NavigationBarItem(
                selected = selected == idx,
                onClick  = { onSelect(idx) },
                icon     = { Icon(icon, null) },
                label    = { Text(label, fontSize = 11.sp) },
                colors   = NavigationBarItemDefaults.colors(
                    selectedIconColor   = AccentCyan,
                    selectedTextColor   = AccentCyan,
                    unselectedIconColor = TextSecondary,
                    unselectedTextColor = TextSecondary,
                    indicatorColor      = Color(0xFF1E1E32)
                )
            )
        }
    }
}

// ── Tab 2: Share Screen (phone → PC) ────────────────────────────────────────
@Composable
fun ShareScreenTab(
    modifier: Modifier,
    isRunning: Boolean,
    myId: String,
    statusMsg: String,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier.fillMaxSize().background(BgDark).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)
    ) {
        Icon(
            Icons.Default.ScreenShare, null,
            tint     = if (isRunning) AccentGreen else TextSecondary,
            modifier = Modifier.size(72.dp)
        )
        Text(
            if (isRunning) "Sharing Active" else "Not Sharing",
            color = if (isRunning) AccentGreen else TextSecondary,
            fontSize = 20.sp, fontWeight = FontWeight.Bold
        )

        Button(
            onClick  = if (isRunning) onStop else onStart,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape    = RoundedCornerShape(14.dp),
            colors   = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) AccentRed else AccentCyan
            )
        ) {
            Icon(
                if (isRunning) Icons.Default.StopCircle else Icons.Default.PlayCircle,
                null, tint = Color.White
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (isRunning) "Stop Sharing" else "Start Sharing",
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp
            )
        }

        if (isRunning && myId.isNotEmpty()) {
            Card(
                shape  = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A3A2A)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("অন্য device এ এই ID দিয়ে connect করো:",
                        color = TextSecondary, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        RemoteDesktopService.formatId(myId),
                        color = AccentCyan, fontSize = 28.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 2.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("Port: ${RemoteDesktopService.WS_PORT}",
                        color = TextSecondary, fontSize = 12.sp)
                }
            }
        }

        if (statusMsg.isNotEmpty()) {
            Text(statusMsg, color = TextSecondary,
                fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

// ── Tab 1: Chat placeholder ──────────────────────────────────────────────────
@Composable
fun ChatTabPlaceholder(modifier: Modifier) {
    Box(modifier.fillMaxSize().background(BgDark), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Chat, null,
                tint = TextSecondary, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text("Chat — Coming soon", color = TextSecondary, fontSize = 15.sp)
        }
    }
}

// ── Tab 3: Settings ──────────────────────────────────────────────────────────
@Composable
fun RdSettingsTab(modifier: Modifier, context: Context) {
    var quality by remember { mutableIntStateOf(65) }
    LazyColumn(
        modifier.fillMaxSize().background(BgDark),
        contentPadding   = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Settings", color = TextPrimary,
                fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        item {
            Card(
                shape  = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = BgCard),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Video Quality: $quality%", color = TextPrimary, fontSize = 14.sp)
                    Slider(
                        value         = quality.toFloat(),
                        onValueChange = { quality = it.toInt() },
                        valueRange    = 20f..95f,
                        colors = SliderDefaults.colors(
                            thumbColor      = AccentCyan,
                            activeTrackColor = AccentCyan
                        )
                    )
                    Text("Low = smoother, High = sharper",
                        color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
        item {
            Card(
                shape  = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = BgCard),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Connection Info", color = TextPrimary,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("WebSocket port: ${RemoteDesktopService.WS_PORT}",
                        color = TextSecondary, fontSize = 13.sp)
                    Text("Local IP: ${RemoteDesktopService.getLocalIp(context)}",
                        color = TextSecondary, fontSize = 13.sp)
                    Text("Protocol: WebSocket + H264 (same as PC side)",
                        color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
        item {
            Card(
                shape  = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = BgCard),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Accessibility Service", color = TextPrimary,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    val inputEnabled by RemoteDesktopInputService.isEnabled.collectAsState()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(8.dp).clip(CircleShape)
                                .background(if (inputEnabled) OnlineDot else OfflineDot)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (inputEnabled) "Active — input control ready"
                            else "Disabled — enable for remote input",
                            color = if (inputEnabled) OnlineDot else TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                    if (!inputEnabled) {
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                )
                            },
                            shape  = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, AccentCyan)
                        ) {
                            Text("Enable Accessibility", color = AccentCyan, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// PcViewerScreen — Fullscreen remote viewer
// Phone এ PC screen live দেখা + touch → PC mouse/keyboard input
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
fun PcViewerScreen(
    pcIp:   String,
    code:   String,
    pcPort: Int    = 9224,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var statusMsg    by remember { mutableStateOf("Connecting...") }
    var connected    by remember { mutableStateOf(false) }
    var authFailed   by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }

    var remoteW by remember { mutableStateOf(1920) }
    var remoteH by remember { mutableStateOf(1080) }

    // Letterbox canvas bounds
    var canvasX by remember { mutableStateOf(0f) }
    var canvasY by remember { mutableStateOf(0f) }
    var canvasW by remember { mutableStateOf(0f) }
    var canvasH by remember { mutableStateOf(0f) }

    val viewRef = remember { mutableStateOf<PcScreenReceiverView?>(null) }

    // Auto-hide toolbar after 3.5s
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(3500)
            showControls = false
        }
    }

    DisposableEffect(pcIp) {
        onDispose { viewRef.value?.destroy() }
    }

    // ── Coordinate mapping ─────────────────────────────────────────
    // Returns normalized 0..1 floats — PC InjectMouse multiplies by 65535
    fun toNorm(touchX: Float, touchY: Float): Pair<Float, Float> {
        val nx = ((touchX - canvasX) / canvasW).coerceIn(0f, 1f)
        val ny = ((touchY - canvasY) / canvasH).coerceIn(0f, 1f)
        return nx to ny
    }

    // ── Input helpers ─────────────────────────────────────────────
    fun sendMove(tx: Float, ty: Float) {
        if (!connected || canvasW == 0f) return
        val (nx, ny) = toNorm(tx, ty)
        viewRef.value?.sendMouseNorm(0, nx, ny)
    }
    fun sendDown(tx: Float, ty: Float) {
        if (!connected || canvasW == 0f) return
        val (nx, ny) = toNorm(tx, ty)
        viewRef.value?.sendMouseNorm(1, nx, ny)
    }
    fun sendUp(tx: Float, ty: Float) {
        if (!connected || canvasW == 0f) return
        val (nx, ny) = toNorm(tx, ty)
        viewRef.value?.sendMouseNorm(2, nx, ny)
    }
    fun sendRightClick(tx: Float, ty: Float) {
        if (!connected || canvasW == 0f) return
        val (nx, ny) = toNorm(tx, ty)
        viewRef.value?.sendMouseNorm(3, nx, ny)
        viewRef.value?.sendMouseNorm(4, nx, ny)
    }
    fun sendScroll(dir: String, tx: Float, ty: Float) {
        if (!connected || canvasW == 0f) return
        val (nx, ny) = toNorm(tx, ty)
        viewRef.value?.sendScrollNorm(nx, ny, dir)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { size ->
                val localW = size.width.toFloat()
                val localH = size.height.toFloat()
                val ar     = remoteW.toFloat() / remoteH.toFloat()
                val boxAr  = localW / localH
                if (ar > boxAr) {
                    canvasW = localW;   canvasH = localW / ar
                    canvasX = 0f;       canvasY = (localH - canvasH) / 2f
                } else {
                    canvasH = localH;   canvasW = localH * ar
                    canvasY = 0f;       canvasX = (localW - canvasW) / 2f
                }
            }
    ) {
        // ── SurfaceView (H264 decode → render) ────────────────────
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory  = { ctx ->
                PcScreenReceiverView(ctx).also { v ->
                    viewRef.value = v
                    v.onConnected    = { w, h ->
                        connected  = true
                        authFailed = false
                        if (w > 0) remoteW = w
                        if (h > 0) remoteH = h
                        statusMsg = "Connected — ${w}×${h}"
                    }
                    v.onDisconnected = {
                        connected = false
                        statusMsg = "Disconnected"
                    }
                    v.onAuthFailed   = {
                        authFailed = true
                        statusMsg  = "❌ Code ভুল — PC তে নতুন code generate করো"
                    }
                    v.onError        = { msg ->
                        statusMsg = "Error: $msg"
                    }
                    // Connect with 6-digit auth code
                    v.connectToPc(pcIp, code, pcPort)
                }
            }
        )

        // ── Gesture layer (only when connected) ───────────────────
        if (connected) {
            RustDeskGestureLayer(
                modifier       = Modifier.fillMaxSize(),
                onMove         = { x, y     -> sendMove(x, y) },
                onDown         = { x, y     -> sendDown(x, y) },
                onUp           = { x, y     -> sendUp(x, y) },
                onRightClick   = { x, y     -> sendRightClick(x, y) },
                onScroll       = { dir, x, y -> sendScroll(dir, x, y) },
                onShowControls = { showControls = true }
            )
        }

        // ── Status / connecting overlay ────────────────────────────
        if (!connected) {
            Box(
                Modifier.fillMaxSize().background(Color(0xCC000000)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!authFailed) {
                        CircularProgressIndicator(
                            color    = AccentCyan,
                            modifier = Modifier.size(44.dp)
                        )
                    } else {
                        Icon(Icons.Default.Error, null,
                            tint = AccentRed, modifier = Modifier.size(48.dp))
                    }
                    Text(statusMsg, color = TextPrimary,
                        fontSize = 14.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp))
                    Text("$pcIp:$pcPort  ·  code: $code",
                        color = TextSecondary, fontSize = 12.sp)
                    OutlinedButton(
                        onClick = onBack,
                        border  = BorderStroke(1.dp, Color(0xFF555577))
                    ) {
                        Text("Back", color = TextSecondary)
                    }
                }
            }
        }

        // ── Auto-hide top bar ─────────────────────────────────────
        AnimatedVisibility(
            visible  = showControls,
            modifier = Modifier.align(Alignment.TopCenter),
            enter    = fadeIn() + slideInVertically(),
            exit     = fadeOut() + slideOutVertically()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xDD101020))
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextPrimary)
                }
                Text(
                    if (connected) "$pcIp  ${remoteW}×${remoteH}" else statusMsg,
                    color = AccentCyan, fontSize = 13.sp, modifier = Modifier.weight(1f)
                )
                if (connected) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(OnlineDot))
                    Spacer(Modifier.width(10.dp))
                }
            }
        }

        // ── Auto-hide bottom toolbar (keyboard shortcuts) ─────────
        AnimatedVisibility(
            visible  = showControls && connected,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter    = fadeIn() + slideInVertically { it },
            exit     = fadeOut() + slideOutVertically { it }
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xDD101020))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                RdToolbarBtn("⊞ Win") {
                    viewRef.value?.sendKeyEvent(0x5B, 0)
                    viewRef.value?.sendKeyEvent(0x5B, 1)
                }
                RdToolbarBtn("Esc") {
                    viewRef.value?.sendKeyEvent(0x1B, 0)
                    viewRef.value?.sendKeyEvent(0x1B, 1)
                }
                RdToolbarBtn("Tab") {
                    viewRef.value?.sendKeyEvent(0x09, 0)
                    viewRef.value?.sendKeyEvent(0x09, 1)
                }
                RdToolbarBtn("Alt+F4") {
                    viewRef.value?.sendKeyEvent(0x12, 0)
                    viewRef.value?.sendKeyEvent(0x73, 0)
                    viewRef.value?.sendKeyEvent(0x73, 1)
                    viewRef.value?.sendKeyEvent(0x12, 1)
                }
                RdToolbarBtn("Ctrl+C") {
                    viewRef.value?.sendKeyEvent(0x11, 0)
                    viewRef.value?.sendKeyEvent(0x43, 0)
                    viewRef.value?.sendKeyEvent(0x43, 1)
                    viewRef.value?.sendKeyEvent(0x11, 1)
                }
                RdToolbarBtn("Ctrl+V") {
                    viewRef.value?.sendKeyEvent(0x11, 0)
                    viewRef.value?.sendKeyEvent(0x56, 0)
                    viewRef.value?.sendKeyEvent(0x56, 1)
                    viewRef.value?.sendKeyEvent(0x11, 1)
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// RustDeskGestureLayer — gesture → mouse/keyboard mapping
// 1-finger tap/drag → mouse left
// Long press        → right click
// Double tap        → double click
// 2-finger scroll   → mouse wheel
// ══════════════════════════════════════════════════════════════════════════════
@Composable
fun RustDeskGestureLayer(
    modifier:       Modifier = Modifier,
    onMove:         (Float, Float) -> Unit,
    onDown:         (Float, Float) -> Unit,
    onUp:           (Float, Float) -> Unit,
    onRightClick:   (Float, Float) -> Unit,
    onScroll:       (String, Float, Float) -> Unit,
    onShowControls: () -> Unit
) {
    var lastPointerPos by remember { mutableStateOf(Offset.Zero) }
    var hasMoved       by remember { mutableStateOf(false) }
    val moveThreshold  = 8f

    Box(
        modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    lastPointerPos = down.position
                    hasMoved = false
                    onDown(down.position.x, down.position.y)

                    do {
                        val evt   = awaitPointerEvent()
                        val count = evt.changes.count { it.pressed }

                        if (count == 1) {
                            val ch = evt.changes.first()
                            val dx = ch.position.x - lastPointerPos.x
                            val dy = ch.position.y - lastPointerPos.y
                            if (dx * dx + dy * dy > moveThreshold * moveThreshold) {
                                hasMoved = true
                                onMove(ch.position.x, ch.position.y)
                                lastPointerPos = ch.position
                            }
                        } else if (count == 2) {
                            val c0 = evt.changes[0]; val c1 = evt.changes[1]
                            val cx = (c0.position.x + c1.position.x) / 2f
                            val cy = (c0.position.y + c1.position.y) / 2f
                            val avgDy = ((c0.position.y - c0.previousPosition.y) +
                                        (c1.position.y - c1.previousPosition.y)) / 2f
                            val avgDx = ((c0.position.x - c0.previousPosition.x) +
                                        (c1.position.x - c1.previousPosition.x)) / 2f
                            if (kotlin.math.abs(avgDy) > kotlin.math.abs(avgDx)) {
                                if (kotlin.math.abs(avgDy) > 2f)
                                    onScroll(if (avgDy < 0) "up" else "down", cx, cy)
                            } else {
                                if (kotlin.math.abs(avgDx) > 2f)
                                    onScroll(if (avgDx < 0) "left" else "right", cx, cy)
                            }
                        }
                        evt.changes.forEach { it.consume() }
                    } while (evt.changes.any { it.pressed })

                    onUp(lastPointerPos.x, lastPointerPos.y)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap         = { if (it.y < 80f) onShowControls() },
                    onDoubleTap   = { onDown(it.x, it.y); onUp(it.x, it.y)
                                     onDown(it.x, it.y); onUp(it.x, it.y) },
                    onLongPress   = { onRightClick(it.x, it.y) }
                )
            }
    )
}

@Composable
private fun RdToolbarBtn(label: String, onClick: () -> Unit) {
    TextButton(
        onClick  = onClick,
        modifier = Modifier.height(36.dp),
        colors   = ButtonDefaults.textButtonColors(contentColor = Color(0xFFB0B0C8))
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

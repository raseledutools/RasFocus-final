package com.rasel.RasFocus.remotedesktop

/**
 * RemoteDesktopScreen — RustDesk-style UI v2
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  Screenshot (RustDesk) এর মতো layout:                          │
 * │                                                                  │
 * │  Left panel:  "Your Desktop" — এই device এর ID + password      │
 * │  Right panel: "Control Remote Desktop" — code input + Connect   │
 * │  Bottom grid: Recent connections (Android + Windows cards)       │
 * │                                                                  │
 * │  Modes:                                                          │
 * │  A) Phone → PC:  PC এ "Generate Code" চাপলে 6-digit code আসে.  │
 * │     Phone এ code দিলে Firestore lookup → PC IP → direct connect │
 * │     LAN miss → relay.rasfocus.com/relay/<code> bridge          │
 * │  B) Share Screen: Phone screen → অন্য device                   │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * Protocol: ws://pc-ip:9224, H264 NAL binary frames, JSON control
 * Relay:    wss://relay.rasfocus.com/relay/<code>
 */

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Colour palette (RustDesk dark theme) ────────────────────────────────────
private val BgDark        = Color(0xFF17171F)
private val BgSidebar     = Color(0xFF1E1E2A)
private val BgCard        = Color(0xFF252535)
private val BgCardHover   = Color(0xFF2E2E42)
private val AccentBlue    = Color(0xFF1A73E8)   // RustDesk blue
private val AccentCyan    = Color(0xFF00C0EF)
private val AccentGreen   = Color(0xFF2ECC71)
private val AccentRed     = Color(0xFFE74C3C)
private val AccentPurple  = Color(0xFF9B59B6)
private val TextPrimary   = Color(0xFFEEEEEE)
private val TextSecondary = Color(0xFF9A9AB0)
private val OnlineDot     = Color(0xFF00D26A)
private val OfflineDot    = Color(0xFF888888)
private val DividerColor  = Color(0xFF2A2A3A)

// ── Recent connection model ──────────────────────────────────────────────────
data class RecentDevice(
    val id: String,
    val displayName: String,
    val platform: String,   // "android" or "windows"
    val isOnline: Boolean = false,
    val cardColor: Color = BgCard
)

// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteDesktopHomeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    val isRunning by RemoteDesktopService.isRunning.collectAsState()
    val myId      by RemoteDesktopService.myId.collectAsState()
    val clients   by RemoteDesktopService.connectedClients.collectAsState()

    // ── "Connect to PC" state ──────────────────────────────────────
    var codeInput    by remember { mutableStateOf("") }
    var statusMsg    by remember { mutableStateOf("") }
    var isConnecting by remember { mutableStateOf(false) }
    var showViewer   by remember { mutableStateOf(false) }
    var viewerCode   by remember { mutableStateOf("") }
    var connectError by remember { mutableStateOf("") }

    // ── "Share Screen" state ──────────────────────────────────────
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

    // ── Recent connections (persisted in SharedPrefs) ─────────────
    val recentPrefs = remember { context.getSharedPreferences("rd_recent", Context.MODE_PRIVATE) }
    var recentDevices by remember {
        mutableStateOf(loadRecentDevices(recentPrefs))
    }

    // ── If showing PC viewer ───────────────────────────────────────
    if (showViewer) {
        PcViewerScreen(
            code = viewerCode,
            onBack = { showViewer = false; viewerCode = "" }
        )
        return
    }

    // ── Main layout ────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        // ── Top bar ────────────────────────────────────────────────
        TopBar(onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Main two-column area ──────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Left panel: Your Desktop (this phone's ID)
                YourDesktopPanel(
                    modifier = Modifier.weight(1f),
                    isRunning = isRunning,
                    myId = myId,
                    clients = clients,
                    context = context,
                    onStartSharing = {
                        if (!isRunning) projectionLauncher.launch(mpm.createScreenCaptureIntent())
                        else statusMsg = "Already running — ID: ${RemoteDesktopService.formatId(myId)}"
                    },
                    onStopSharing = {
                        context.startService(Intent(context, RemoteDesktopService::class.java).apply {
                            action = RemoteDesktopService.ACTION_STOP
                        })
                        statusMsg = "Screen sharing বন্ধ হয়েছে"
                    }
                )

                // Right panel: Connect to Remote
                ConnectPanel(
                    modifier = Modifier.weight(1.2f),
                    codeInput = codeInput,
                    onCodeChange = { v ->
                        codeInput = v.filter { it.isDigit() }.take(6)
                        connectError = ""
                    },
                    isConnecting = isConnecting,
                    connectError = connectError,
                    onConnect = {
                        if (codeInput.length == 6) {
                            isConnecting = true
                            connectError = ""
                            scope.launch {
                                val result = connectToRemote(context, codeInput)
                                isConnecting = false
                                when {
                                    result.success -> {
                                        // Save to recent
                                        val dev = RecentDevice(
                                            id = codeInput,
                                            displayName = result.deviceName,
                                            platform = result.platform,
                                            isOnline = true,
                                            cardColor = if(result.platform=="windows")
                                                Color(0xFF5B4EAB) else Color(0xFF2B4A6F)
                                        )
                                        recentDevices = saveAndGetRecent(recentPrefs, dev)
                                        viewerCode = codeInput
                                        showViewer = true
                                    }
                                    else -> {
                                        connectError = result.error
                                    }
                                }
                                codeInput = ""
                            }
                        }
                    }
                )
            }

            // Status message
            if (statusMsg.isNotEmpty()) {
                StatusBanner(statusMsg)
            }

            // ── Recent / saved connections grid ───────────────────
            if (recentDevices.isNotEmpty()) {
                RecentConnectionsSection(
                    devices = recentDevices,
                    onConnect = { dev ->
                        viewerCode = dev.id
                        showViewer = true
                    },
                    onRemove = { dev ->
                        recentDevices = removeRecent(recentPrefs, dev)
                    }
                )
            }

            // ── Info card ─────────────────────────────────────────
            RelayInfoCard()

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Top app bar ───────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(onBack: () -> Unit) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.ScreenShare, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(20.dp))
                Text("Remote Desktop", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextSecondary)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = BgSidebar),
    )
}

// ── Left panel: "Your Desktop" ────────────────────────────────────────────────
@Composable
private fun YourDesktopPanel(
    modifier: Modifier,
    isRunning: Boolean,
    myId: String,
    clients: Int,
    context: Context,
    onStartSharing: () -> Unit,
    onStopSharing: () -> Unit
) {
    val formattedId = if (myId.isNotEmpty()) RemoteDesktopService.formatId(myId) else "---"

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = BgSidebar),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Your Desktop", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("এই device কে অন্য device থেকে control করতে দিন",
                color = TextSecondary, fontSize = 11.sp)

            HorizontalDivider(color = DividerColor)

            // ID row
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ID", color = TextSecondary, fontSize = 11.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = formattedId,
                        color = if (isRunning) AccentCyan else TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (myId.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("RD ID", myId))
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy ID",
                                tint = TextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // Online/offline dot
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isRunning) OnlineDot else OfflineDot))
                Text(
                    text = when {
                        isRunning && clients > 0 -> "$clients device connected"
                        isRunning -> "Sharing — waiting for connection"
                        else -> "Not sharing"
                    },
                    color = if (isRunning) AccentGreen else TextSecondary,
                    fontSize = 11.sp
                )
            }

            // Share / Stop button
            Button(
                onClick = if (isRunning) onStopSharing else onStartSharing,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) AccentRed else AccentGreen
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    if (isRunning) Icons.Default.Stop else Icons.Default.ScreenShare,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(if (isRunning) "Stop Sharing" else "Share Screen",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ── Right panel: "Control Remote Desktop" ────────────────────────────────────
@Composable
private fun ConnectPanel(
    modifier: Modifier,
    codeInput: String,
    onCodeChange: (String) -> Unit,
    isConnecting: Boolean,
    connectError: String,
    onConnect: () -> Unit
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = BgSidebar),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Control Remote Desktop", color = TextPrimary,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Default.Help, contentDescription = null,
                    tint = TextSecondary, modifier = Modifier.size(16.dp))
            }
            Text("PC এ \"Generate Code\" চাপলে 6-digit code পাবে। সেটা এখানে দাও।",
                color = TextSecondary, fontSize = 11.sp)

            // Code input — 6 digit boxes
            CodeInputField(
                code = codeInput,
                onCodeChange = onCodeChange,
                onConnect = onConnect
            )

            // Error message
            if (connectError.isNotEmpty()) {
                Text(connectError, color = AccentRed, fontSize = 11.sp)
            }

            // Connect button
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onConnect,
                    enabled = codeInput.length == 6 && !isConnecting,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Connecting...", fontSize = 13.sp)
                    } else {
                        Icon(Icons.Default.ConnectWithoutContact, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Connect", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }

                // Dropdown arrow (for future: file transfer mode, etc.)
                OutlinedButton(
                    onClick = {},
                    modifier = Modifier.size(44.dp),
                    contentPadding = PaddingValues(0.dp),
                    border = BorderStroke(1.dp, DividerColor),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Options",
                        modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

// ── 6-digit code input (split boxes like RustDesk) ───────────────────────────
@Composable
private fun CodeInputField(
    code: String,
    onCodeChange: (String) -> Unit,
    onConnect: () -> Unit
) {
    // Single text field styled as split boxes
    Column {
        OutlinedTextField(
            value = code,
            onValueChange = onCodeChange,
            placeholder = { Text("6-digit code", color = TextSecondary, fontSize = 13.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentBlue,
                unfocusedBorderColor = DividerColor,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = AccentBlue,
                focusedContainerColor = BgCard,
                unfocusedContainerColor = BgCard,
            ),
            shape = RoundedCornerShape(8.dp),
            textStyle = LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 6.sp
            )
        )

        // Digit hint
        if (code.isNotEmpty()) {
            Text("${code.length}/6 digits", color = TextSecondary, fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp, start = 4.dp))
        }
    }
}

// ── Recent connections section ────────────────────────────────────────────────
@Composable
private fun RecentConnectionsSection(
    devices: List<RecentDevice>,
    onConnect: (RecentDevice) -> Unit,
    onRemove: (RecentDevice) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Tab bar (History active by default, like RustDesk screenshot)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Clock / history tab
            TabChip(icon = Icons.Default.History, label = "Recent", isSelected = true)
            TabChip(icon = Icons.Default.Star, label = "Favourites", isSelected = false)
            TabChip(icon = Icons.Default.Explore, label = "Discover", isSelected = false)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {}, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Search, contentDescription = "Search",
                    tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
        }

        // Device cards grid (LazyRow for now, matches screenshot)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 0.dp)
        ) {
            items(devices) { dev ->
                DeviceCard(
                    device = dev,
                    onClick = { onConnect(dev) },
                    onLongClick = { onRemove(dev) }
                )
            }
        }
    }
}

// ── Tab chip ─────────────────────────────────────────────────────────────────
@Composable
private fun TabChip(icon: ImageVector, label: String, isSelected: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isSelected) BgCard else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Icon(icon, contentDescription = label,
            tint = if (isSelected) AccentCyan else TextSecondary,
            modifier = Modifier.size(16.dp))
        if (isSelected) {
            Text(label, color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ── Device card (matches screenshot design) ───────────────────────────────────
@Composable
private fun DeviceCard(
    device: RecentDevice,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var isHovered by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .width(160.dp)
            .height(120.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(device.cardColor)
            .clickable(onClick = onClick)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { onLongClick() }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Platform icon
            Icon(
                imageVector = if (device.platform == "windows") Icons.Default.Computer else Icons.Default.PhoneAndroid,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(40.dp)
            )

            // Name + ID row
            Column {
                Text(
                    device.displayName,
                    color = Color.White,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (device.isOnline) OnlineDot else OfflineDot)
                    )
                    Text(
                        text = formatDisplayId(device.id),
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.MoreVert, contentDescription = "Options",
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

// ── Relay info card ───────────────────────────────────────────────────────────
@Composable
private fun RelayInfoCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Color(0xFF2A2A50))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.CloudDone, contentDescription = null,
                tint = AccentCyan, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Relay Server Active", color = AccentCyan,
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("Code দিলেই connect হবে — PC IP দিতে হবে না। " +
                     "LAN direct + internet relay দুটোই support করে।",
                    color = TextSecondary, fontSize = 11.sp)
            }
        }
    }
}

// ── Status banner ─────────────────────────────────────────────────────────────
@Composable
private fun StatusBanner(message: String) {
    val isError = message.startsWith("❌")
    Surface(
        color = if (isError) Color(0xFF3A1A1A) else Color(0xFF1A3A2A),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = message,
            color = if (isError) AccentRed else AccentGreen,
            modifier = Modifier.padding(10.dp, 8.dp),
            fontSize = 12.sp
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  PC Viewer Screen (full-screen H264 decode + touch input)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun PcViewerScreen(code: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    val pcActive by RemoteDesktopService.pcStreamActive.collectAsState()
    var statusText by remember { mutableStateOf("Connecting...") }
    var showControls by remember { mutableStateOf(true) }

    // Auto-hide controls after 3s
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(3000)
            showControls = false
        }
    }

    // Connect using relay lookup
    LaunchedEffect(code) {
        statusText = "Firestore lookup: code $code..."
        val devInfo = RdSignaling.lookup(code)
        if (devInfo == null) {
            statusText = "❌ Code not found — PC এ \"Generate Code\" চাপো"
            return@LaunchedEffect
        }
        statusText = "Connecting to ${devInfo.name} (${devInfo.ip}:${devInfo.port})..."
        RemoteDesktopService.getInstance()?.connectToPC(devInfo, code)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Video surface
        if (pcActive) {
            AndroidView(
                factory = { ctx ->
                    PcScreenReceiverView(ctx).also { view ->
                        RemoteDesktopService.getInstance()?.attachPcView(view)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // Touch → mouse events (sent to PC via WebSocket JSON)
                        detectTapGestures(
                            onPress = { offset ->
                                showControls = false
                                val normX = offset.x / size.width
                                val normY = offset.y / size.height
                                RemoteDesktopService.getInstance()?.sendMouseEvent(
                                    nx = normX, ny = normY, mask = 1 /* left down */
                                )
                                tryAwaitRelease()
                                RemoteDesktopService.getInstance()?.sendMouseEvent(
                                    nx = normX, ny = normY, mask = 2 /* left up */
                                )
                            }
                        )
                    }
            )
        } else {
            // Loading state
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = AccentBlue)
                Spacer(Modifier.height(16.dp))
                Text(statusText, color = TextPrimary, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp))
            }
        }

        // Overlay controls (auto-hide)
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top bar
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp, 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            RemoteDesktopService.getInstance()?.disconnectFromPC()
                            onBack()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                        }
                        Text("PC Remote  •  Code: $code", color = Color.White, fontSize = 13.sp,
                            modifier = Modifier.weight(1f))
                        if (pcActive) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(OnlineDot))
                        }
                    }
                }

                // Bottom keyboard/special keys bar
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp, 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "Esc" to 27, "Win" to 91, "Alt" to 18,
                            "Ctrl" to 17, "Tab" to 9, "Del" to 46, "Enter" to 13
                        ).forEach { (label, vk) ->
                            SmallKeyButton(label) {
                                RemoteDesktopService.getInstance()?.sendKeyEvent(vk, "down")
                                scope.launch {
                                    delay(50)
                                    RemoteDesktopService.getInstance()?.sendKeyEvent(vk, "up")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Tap anywhere to show controls
        if (!showControls && pcActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { showControls = true }
            )
        }
    }
}

// ── Small key button ──────────────────────────────────────────────────────────
@Composable
private fun SmallKeyButton(label: String, onClick: () -> Unit) {
    Surface(
        color = Color(0xFF2A2A2A),
        shape = RoundedCornerShape(5.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            label, color = Color.White,
            fontSize = 11.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Connect logic: Firestore lookup → direct WS → relay fallback
// ─────────────────────────────────────────────────────────────────────────────
data class ConnectResult(
    val success: Boolean,
    val deviceName: String = "",
    val platform: String = "windows",
    val error: String = ""
)

private suspend fun connectToRemote(context: Context, code: String): ConnectResult {
    // 1. Lookup in Firestore by code
    val devInfo = RdSignaling.lookup(code)
        ?: return ConnectResult(false, error = "Code পাওয়া যায়নি — PC এ code generate করা হয়েছে কিনা দেখো")

    // 2. Try direct LAN connection first
    val svc = RemoteDesktopService.getInstance()
        ?: return ConnectResult(false, error = "Remote Desktop Service চলছে না")

    val ok = svc.connectToPC(devInfo, code)
    return if (ok) {
        ConnectResult(true, deviceName = devInfo.name, platform = devInfo.platform)
    } else {
        ConnectResult(false, error = "Connect করা যায়নি — Relay চেষ্টা করছে...")
        // TODO: relay fallback via wss://relay.rasfocus.com/relay/<code>
        // This is handled inside RemoteDesktopService.connectToPC() already
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Recent device persistence
// ─────────────────────────────────────────────────────────────────────────────
private fun formatDisplayId(id: String): String {
    return if (id.length == 6)
        "${id.take(3)} ${id.drop(3)}"
    else id
}

private fun loadRecentDevices(prefs: android.content.SharedPreferences): List<RecentDevice> {
    val json = prefs.getString("recent_json", null) ?: return emptyList()
    return try {
        // Simple manual parse — no Gson needed
        val items = mutableListOf<RecentDevice>()
        val lines = json.split("|")
        for (line in lines) {
            val parts = line.split(",")
            if (parts.size >= 3) {
                val cardColor = when (parts.getOrNull(2)) {
                    "windows" -> Color(0xFF5B4EAB)
                    else -> Color(0xFF2B4A6F)
                }
                items.add(RecentDevice(
                    id = parts[0],
                    displayName = parts[1],
                    platform = parts[2],
                    cardColor = cardColor
                ))
            }
        }
        items.take(8)
    } catch (e: Exception) { emptyList() }
}

private fun saveAndGetRecent(
    prefs: android.content.SharedPreferences,
    newDev: RecentDevice
): List<RecentDevice> {
    val existing = loadRecentDevices(prefs).filter { it.id != newDev.id }
    val updated = listOf(newDev) + existing
    val trimmed = updated.take(8)
    val json = trimmed.joinToString("|") { "${it.id},${it.displayName},${it.platform}" }
    prefs.edit().putString("recent_json", json).apply()
    return trimmed
}

private fun removeRecent(
    prefs: android.content.SharedPreferences,
    dev: RecentDevice
): List<RecentDevice> {
    val existing = loadRecentDevices(prefs).filter { it.id != dev.id }
    val json = existing.joinToString("|") { "${it.id},${it.displayName},${it.platform}" }
    prefs.edit().putString("recent_json", json).apply()
    return existing
}

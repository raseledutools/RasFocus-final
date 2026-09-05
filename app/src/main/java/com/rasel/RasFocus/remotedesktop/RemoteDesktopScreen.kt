package com.rasel.RasFocus.remotedesktop

/**
 * RemoteDesktopScreen — RustDesk-exact UI
 *
 * Layout:
 *  ┌──────────────────────────────────────┐
 *  │  TopBar: "RustDesk" style header     │
 *  ├──────────────────────────────────────┤
 *  │  Remote ID card (big cyan text)      │
 *  │  [  135 310 219       ] [X] [→]      │
 *  ├──────────────────────────────────────┤
 *  │  5 icon tabs (clock/star/compass/    │
 *  │    person/monitor) + search + check  │
 *  ├──────────────────────────────────────┤
 *  │  Recent list items:                  │
 *  │  [PlatformIcon] ● ID  Name    [⋮]   │
 *  └──────────────────────────────────────┘
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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

// ── Colour palette (RustDesk dark) ───────────────────────────────────────────
private val BgDark        = Color(0xFF101010)
private val BgCard        = Color(0xFF1C1C1C)
private val BgCardDark    = Color(0xFF252525)
private val AccentBlue    = Color(0xFF1A73E8)
private val AccentCyan    = Color(0xFF00AAFF)
private val AccentGreen   = Color(0xFF2ECC71)
private val AccentRed     = Color(0xFFE74C3C)
private val TextPrimary   = Color(0xFFEEEEEE)
private val TextSecondary = Color(0xFF9A9A9A)
private val OnlineDot     = Color(0xFFFF9800)   // orange like RustDesk screenshot
private val OfflineDot    = Color(0xFF555555)
private val DividerColor  = Color(0xFF2A2A2A)
private val WinPurple     = Color(0xFF7B68EE)
private val LinuxPurple   = Color(0xFF9C6B9E)

// ── Tab definitions ──────────────────────────────────────────────────────────
private enum class RdTab { History, Favourites, Discovery, AddressBook, Grouped }

// ── Recent connection model ──────────────────────────────────────────────────
data class RecentDevice(
    val id: String,
    val displayName: String,
    val platform: String,   // "android", "windows", "linux"
    val isOnline: Boolean = false,
    val cardColor: Color = Color(0xFF5B4EAB)
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

    // Remote ID input (9-digit, spaces stripped for logic)
    var remoteId     by remember { mutableStateOf("") }
    var statusMsg    by remember { mutableStateOf("") }
    var isConnecting by remember { mutableStateOf(false) }
    var showViewer   by remember { mutableStateOf(false) }
    var viewerCode   by remember { mutableStateOf("") }

    // Active tab
    var activeTab by remember { mutableStateOf(RdTab.History) }

    // Share screen launcher
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
        }
    }

    // Recent connections
    val recentPrefs = remember { context.getSharedPreferences("rd_recent", Context.MODE_PRIVATE) }
    var recentDevices by remember { mutableStateOf(loadRecentDevices(recentPrefs)) }

    // Show PC viewer
    if (showViewer) {
        PcViewerScreen(
            code = viewerCode,
            onBack = { showViewer = false; viewerCode = "" }
        )
        return
    }

    // ── Main screen ────────────────────────────────────────────────
    Scaffold(
        containerColor = BgDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Top bar ──────────────────────────────────────────
            RdTopBar(
                onBack = onBack,
                isRunning = isRunning,
                myId = myId,
                context = context,
                onStartShare = { projectionLauncher.launch(mpm.createScreenCaptureIntent()) },
                onStopShare = {
                    context.startService(Intent(context, RemoteDesktopService::class.java).apply {
                        action = RemoteDesktopService.ACTION_STOP
                    })
                }
            )

            // ── Remote ID input card ──────────────────────────────
            RemoteIdCard(
                remoteId = remoteId,
                onIdChange = { v ->
                    // Allow digits only, max 9
                    remoteId = v.filter { it.isDigit() }.take(9)
                },
                onClear = { remoteId = "" },
                onConnect = {
                    val clean = remoteId.filter { it.isDigit() }
                    if (clean.length >= 6) {
                        isConnecting = true
                        scope.launch {
                            val result = connectToRemote(context, clean)
                            isConnecting = false
                            if (result.success) {
                                val dev = RecentDevice(
                                    id = clean,
                                    displayName = result.deviceName,
                                    platform = result.platform,
                                    isOnline = true,
                                    cardColor = if (result.platform == "windows") WinPurple else LinuxPurple
                                )
                                recentDevices = saveAndGetRecent(recentPrefs, dev)
                                viewerCode = clean
                                showViewer = true
                            } else {
                                statusMsg = result.error
                            }
                            remoteId = ""
                        }
                    }
                },
                isConnecting = isConnecting
            )

            // Status msg
            if (statusMsg.isNotEmpty()) {
                Text(
                    statusMsg,
                    color = if (statusMsg.startsWith("✅")) AccentGreen else AccentRed,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // ── 5-tab icon row ────────────────────────────────────
            RdTabRow(activeTab = activeTab, onTabChange = { activeTab = it })

            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)

            // ── Content area ──────────────────────────────────────
            when (activeTab) {
                RdTab.History -> {
                    if (recentDevices.isEmpty()) {
                        EmptyTabContent("No recent connections")
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(recentDevices, key = { it.id }) { dev ->
                                DeviceListItem(
                                    device = dev,
                                    onClick = {
                                        viewerCode = dev.id
                                        showViewer = true
                                    },
                                    onRemove = {
                                        recentDevices = removeRecent(recentPrefs, dev)
                                    }
                                )
                            }
                        }
                    }
                }
                RdTab.Favourites  -> EmptyTabContent("No favourites yet")
                RdTab.Discovery   -> EmptyTabContent("Scanning local network...")
                RdTab.AddressBook -> EmptyTabContent("Address book empty")
                RdTab.Grouped     -> EmptyTabContent("No groups")
            }
        }
    }
}

// ── Top bar: "Remote Desktop" title + your share ID ─────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RdTopBar(
    onBack: () -> Unit,
    isRunning: Boolean,
    myId: String,
    context: Context,
    onStartShare: () -> Unit,
    onStopShare: () -> Unit
) {
    val formattedMyId = if (myId.isNotEmpty()) formatRdId(myId) else ""

    TopAppBar(
        title = {
            Text(
                "Remote Desktop",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextSecondary)
            }
        },
        actions = {
            // My device ID chip
            if (formattedMyId.isNotEmpty()) {
                Surface(
                    color = Color(0xFF1A2A1A),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.clickable {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("My RD ID", myId))
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape)
                            .background(if (isRunning) AccentGreen else OfflineDot))
                        Text(
                            text = "My ID: $formattedMyId",
                            color = AccentGreen,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(Icons.Default.ContentCopy, contentDescription = null,
                            tint = AccentGreen, modifier = Modifier.size(12.dp))
                    }
                }
                Spacer(Modifier.width(4.dp))
            }

            // Share/Stop button
            IconButton(onClick = if (isRunning) onStopShare else onStartShare) {
                Icon(
                    if (isRunning) Icons.Default.StopCircle else Icons.Default.ScreenShare,
                    contentDescription = "Share",
                    tint = if (isRunning) AccentRed else AccentCyan
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = BgCard)
    )
}

// ── Remote ID input card (main entry like RustDesk) ─────────────────────────
@Composable
private fun RemoteIdCard(
    remoteId: String,
    onIdChange: (String) -> Unit,
    onClear: () -> Unit,
    onConnect: () -> Unit,
    isConnecting: Boolean
) {
    // Formatted display: "135 310 219"
    val displayId = buildString {
        remoteId.forEachIndexed { i, c ->
            if (i == 3 || i == 6) append(' ')
            append(c)
        }
    }

    Surface(
        color = BgCard,
        shape = RoundedCornerShape(bottomStart = 0.dp, bottomEnd = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 16.dp)) {
            Text(
                "Remote ID",
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Big ID text (editable but styled like RustDesk)
                Box(modifier = Modifier.weight(1f)) {
                    if (remoteId.isEmpty()) {
                        Text(
                            "Enter Remote ID",
                            color = TextSecondary.copy(alpha = 0.5f),
                            fontSize = 26.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    BasicClickableText(
                        text = if (remoteId.isEmpty()) "" else displayId,
                        color = AccentCyan,
                        fontSize = 26,
                        onIdChange = onIdChange,
                        currentRaw = remoteId
                    )
                }

                // Clear button (X)
                if (remoteId.isNotEmpty()) {
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Clear",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }

                // Arrow / Connect button
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = AccentCyan,
                        strokeWidth = 3.dp
                    )
                } else {
                    IconButton(
                        onClick = onConnect,
                        enabled = remoteId.length >= 6,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (remoteId.length >= 6) AccentCyan.copy(alpha = 0.15f)
                                else Color.Transparent
                            )
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Connect",
                            tint = if (remoteId.length >= 6) AccentCyan else TextSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

// Fake "clickable text field" that looks like big ID text
@Composable
private fun BasicClickableText(
    text: String,
    color: Color,
    fontSize: Int,
    onIdChange: (String) -> Unit,
    currentRaw: String
) {
    var showKeyboard by remember { mutableStateOf(false) }

    // Invisible text field that captures input
    androidx.compose.foundation.text.BasicTextField(
        value = currentRaw,
        onValueChange = onIdChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = androidx.compose.ui.text.TextStyle(
            color = color,
            fontSize = fontSize.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        ),
        decorationBox = { inner ->
            // Display formatted text, actual input hidden beneath
            Text(
                text = text,
                color = color,
                fontSize = fontSize.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            // The real invisible input
            Box(Modifier.size(1.dp, 1.dp)) { inner() }
        }
    )
}

// ── 5-icon tab row ────────────────────────────────────────────────────────────
@Composable
private fun RdTabRow(activeTab: RdTab, onTabChange: (RdTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgCard)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 5 tabs
        RdTabIcon(Icons.Default.History,     "History",      activeTab == RdTab.History)      { onTabChange(RdTab.History) }
        RdTabIcon(Icons.Default.Star,        "Favourites",   activeTab == RdTab.Favourites)   { onTabChange(RdTab.Favourites) }
        RdTabIcon(Icons.Default.Explore,     "Discovery",    activeTab == RdTab.Discovery)    { onTabChange(RdTab.Discovery) }
        RdTabIcon(Icons.Default.Contacts,    "Address Book", activeTab == RdTab.AddressBook)  { onTabChange(RdTab.AddressBook) }
        RdTabIcon(Icons.Default.DeveloperBoard, "Grouped",   activeTab == RdTab.Grouped)      { onTabChange(RdTab.Grouped) }

        Spacer(Modifier.weight(1f))

        // Search icon
        IconButton(onClick = {}, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Search, contentDescription = "Search",
                tint = TextSecondary, modifier = Modifier.size(20.dp))
        }
        // Check / select icon
        IconButton(onClick = {}, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.CheckBox, contentDescription = "Select",
                tint = TextSecondary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun RdTabIcon(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val color = if (isSelected) AccentCyan else TextSecondary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(22.dp))
        if (isSelected) {
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier
                    .width(20.dp)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(AccentCyan)
            )
        }
    }
}

// ── Device list item (RustDesk screenshot style) ─────────────────────────────
@Composable
private fun DeviceListItem(
    device: RecentDevice,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // Platform icon box (Windows purple or Linux dark purple)
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(device.cardColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when (device.platform) {
                    "windows" -> Icons.Default.Computer
                    "linux"   -> Icons.Default.Terminal
                    else      -> Icons.Default.PhoneAndroid
                },
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(Modifier.width(12.dp))

        // ID + name
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Online dot
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (device.isOnline) OnlineDot else OfflineDot)
                )
                Text(
                    text = formatRdId(device.id),
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = device.displayName,
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Three-dot menu
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.MoreVert, contentDescription = "Options",
                    tint = TextSecondary, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                containerColor = BgCardDark
            ) {
                DropdownMenuItem(
                    text = { Text("Connect", color = TextPrimary, fontSize = 13.sp) },
                    onClick = { showMenu = false; onClick() },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = AccentCyan, modifier = Modifier.size(18.dp)) }
                )
                DropdownMenuItem(
                    text = { Text("Remove", color = AccentRed, fontSize = 13.sp) },
                    onClick = { showMenu = false; onRemove() },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = AccentRed, modifier = Modifier.size(18.dp)) }
                )
            }
        }
    }

    HorizontalDivider(
        color = DividerColor,
        thickness = 0.5.dp,
        modifier = Modifier.padding(start = 76.dp)
    )
}

// ── Empty tab placeholder ─────────────────────────────────────────────────────
@Composable
private fun EmptyTabContent(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = TextSecondary, fontSize = 13.sp)
    }
}

// ── Format 9-digit ID like RustDesk: "135 310 219" ───────────────────────────
fun formatRdId(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    return buildString {
        digits.forEachIndexed { i, c ->
            if (i == 3 || i == 6) append(' ')
            append(c)
        }
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

    LaunchedEffect(showControls) {
        if (showControls) { delay(3000); showControls = false }
    }

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
                        detectTapGestures(
                            onPress = { offset ->
                                showControls = false
                                val normX = offset.x / size.width
                                val normY = offset.y / size.height
                                RemoteDesktopService.getInstance()?.sendMouseEvent(nx = normX, ny = normY, mask = 1)
                                tryAwaitRelease()
                                RemoteDesktopService.getInstance()?.sendMouseEvent(nx = normX, ny = normY, mask = 2)
                            }
                        )
                    }
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = AccentBlue)
                Spacer(Modifier.height(16.dp))
                Text(statusText, color = TextPrimary, fontSize = 13.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
            }
        }

        AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize()) {
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
                        Text("PC Remote  •  ID: ${formatRdId(code)}", color = Color.White,
                            fontSize = 13.sp, modifier = Modifier.weight(1f))
                        if (pcActive) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(AccentGreen))
                        }
                    }
                }

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

        if (!showControls && pcActive) {
            Box(modifier = Modifier.fillMaxSize().clickable { showControls = true })
        }
    }
}

@Composable
private fun SmallKeyButton(label: String, onClick: () -> Unit) {
    Surface(
        color = Color(0xFF2A2A2A),
        shape = RoundedCornerShape(5.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Connect logic
// ─────────────────────────────────────────────────────────────────────────────
data class ConnectResult(
    val success: Boolean,
    val deviceName: String = "",
    val platform: String = "windows",
    val error: String = ""
)

private suspend fun connectToRemote(context: Context, code: String): ConnectResult {
    val devInfo = RdSignaling.lookup(code)
        ?: return ConnectResult(false, error = "Code পাওয়া যায়নি — PC এ code generate করা হয়েছে কিনা দেখো")
    val svc = RemoteDesktopService.getInstance()
        ?: return ConnectResult(false, error = "Remote Desktop Service চলছে না")
    val ok = svc.connectToPC(devInfo, code)
    return if (ok) ConnectResult(true, deviceName = devInfo.name, platform = devInfo.platform)
    else ConnectResult(false, error = "Connect করা যায়নি — Relay চেষ্টা করছে...")
}

// ─────────────────────────────────────────────────────────────────────────────
//  Recent persistence
// ─────────────────────────────────────────────────────────────────────────────
private fun loadRecentDevices(prefs: android.content.SharedPreferences): List<RecentDevice> {
    val json = prefs.getString("recent_json", null) ?: return emptyList()
    return try {
        val items = mutableListOf<RecentDevice>()
        val lines = json.split("|")
        for (line in lines) {
            val parts = line.split(",")
            if (parts.size >= 3) {
                val cardColor = when (parts.getOrNull(2)) {
                    "windows" -> WinPurple
                    "linux"   -> LinuxPurple
                    else      -> Color(0xFF2B4A6F)
                }
                items.add(RecentDevice(
                    id = parts[0],
                    displayName = parts[1],
                    platform = parts[2],
                    cardColor = cardColor
                ))
            }
        }
        items.take(20)
    } catch (e: Exception) { emptyList() }
}

private fun saveAndGetRecent(
    prefs: android.content.SharedPreferences,
    newDev: RecentDevice
): List<RecentDevice> {
    val existing = loadRecentDevices(prefs).filter { it.id != newDev.id }
    val updated = (listOf(newDev) + existing).take(20)
    prefs.edit().putString("recent_json",
        updated.joinToString("|") { "${it.id},${it.displayName},${it.platform}" }
    ).apply()
    return updated
}

private fun removeRecent(
    prefs: android.content.SharedPreferences,
    dev: RecentDevice
): List<RecentDevice> {
    val existing = loadRecentDevices(prefs).filter { it.id != dev.id }
    prefs.edit().putString("recent_json",
        existing.joinToString("|") { "${it.id},${it.displayName},${it.platform}" }
    ).apply()
    return existing
}

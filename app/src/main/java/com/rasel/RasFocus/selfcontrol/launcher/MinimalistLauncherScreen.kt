package com.rasel.RasFocus.selfcontrol.launcher

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import androidx.navigation.NavController
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────────────────────
// Colors
// ─────────────────────────────────────────────────────────────────────────────
private val BG          = Color(0xFF000000)
private val TXT         = Color(0xFFFFFFFF)
private val ACCENT      = Color(0xFF14C3B2)
private val ACCENT_BLUE = Color(0xFF4FC3F7)
private val DIM         = Color(0xFF888888)
private val CARD_BG     = Color(0xFF111111)
private val RED         = Color(0xFFFF5252)
private val DIVIDER     = Color(0xFF222222)

private const val PREFS = "launcher_prefs"
private const val KEY_PINNED = "pinned_apps"
private const val KEY_HIDDEN = "hidden_apps"
private const val KEY_RENAMED = "renamed_apps"
private const val KEY_THEME = "launcher_theme"
private const val KEY_FONT_SIZE = "font_size"
private const val KEY_SHOW_ICONS = "show_icons"

// ─────────────────────────────────────────────────────────────────────────────
// Data
// ─────────────────────────────────────────────────────────────────────────────
data class AppInfo(
    val label: String,
    val packageName: String,
    val customName: String = "",
    val isBlocked: Boolean = false,
    val usageMinutes: Long = 0L
)

enum class LauncherTheme(val bg: Color, val text: Color, val accent: Color) {
    PureBlack(Color(0xFF000000), Color(0xFFFFFFFF), Color(0xFF14C3B2)),
    DarkBlue(Color(0xFF0A0E1A), Color(0xFFE8EAF6), Color(0xFF4FC3F7)),
    DarkGreen(Color(0xFF030E0A), Color(0xFFE8F5E9), Color(0xFF00FFB2))
}

// ─────────────────────────────────────────────────────────────────────────────
// Main Screen
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun MinimalistLauncherScreen(navController: NavController? = null) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    // ── State ──────────────────────────────────────────────────────────────
    var query by rememberSaveable { mutableStateOf("") }
    var showAllApps by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var longPressedApp by remember { mutableStateOf<AppInfo?>(null) }

    var pinnedPkgs by remember {
        mutableStateOf(prefs.getStringSet(KEY_PINNED, setOf())!!.toMutableList())
    }
    var hiddenPkgs by remember {
        mutableStateOf(prefs.getStringSet(KEY_HIDDEN, setOf())!!)
    }
    var renamedMap by remember {
        mutableStateOf(loadRenamedMap(prefs))
    }
    var themeIdx by rememberSaveable { mutableStateOf(prefs.getInt(KEY_THEME, 0)) }
    var fontSize by rememberSaveable { mutableStateOf(prefs.getInt(KEY_FONT_SIZE, 1)) } // 0=small,1=med,2=large
    var showIcons by rememberSaveable { mutableStateOf(prefs.getBoolean(KEY_SHOW_ICONS, false)) }

    val theme = LauncherTheme.entries[themeIdx.coerceIn(0, 2)]
    val appFontSize = when (fontSize) { 0 -> 16.sp; 2 -> 26.sp; else -> 21.sp }

    // ── Installed apps ─────────────────────────────────────────────────────
    val allApps: List<AppInfo> = remember(hiddenPkgs, renamedMap) {
        getInstalledApps(context, hiddenPkgs, renamedMap, getBlockedApps(context), getUsageMap(context))
    }

    // ── Pinned apps list (ordered) ─────────────────────────────────────────
    val pinnedApps = remember(pinnedPkgs, allApps) {
        pinnedPkgs.mapNotNull { pkg -> allApps.find { it.packageName == pkg } }
    }

    // ── Clock ──────────────────────────────────────────────────────────────
    var timeState by remember { mutableStateOf(getCurrentTime()) }
    LaunchedEffect(Unit) {
        while (true) { delay(1000L); timeState = getCurrentTime() }
    }

    // ── Battery ────────────────────────────────────────────────────────────
    val battery = getBatteryLevel(context)

    // ── Focus active? ──────────────────────────────────────────────────────
    val focusActive = isFocusActive(context)

    // ── Screen time today ──────────────────────────────────────────────────
    val screenTimeStr = getScreenTimeToday(context)

    // ── Streak ────────────────────────────────────────────────────────────
    val streak = getStreak(context)

    // ── Swipe gestures ────────────────────────────────────────────────────
    var dragDeltaY by remember { mutableStateOf(0f) }
    var dragDeltaX by remember { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.bg)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        val absX = kotlin.math.abs(dragDeltaX)
                        val absY = kotlin.math.abs(dragDeltaY)
                        if (absX > absY) {
                            // horizontal swipe dominant
                            if (dragDeltaX < -80f) showAllApps = true   // swipe left → all apps
                            if (dragDeltaX > 80f) showAllApps = false   // swipe right → home
                        } else {
                            // vertical swipe dominant
                            if (dragDeltaY < -80f) showAllApps = true   // swipe up → all apps
                            if (dragDeltaY > 80f) {                      // swipe down → notification
                                try {
                                    val statusBar = context.getSystemService("statusbar")
                                    val method = statusBar?.javaClass?.getMethod("expandNotificationsPanel")
                                    method?.invoke(statusBar)
                                } catch (_: Exception) {}
                            }
                        }
                        dragDeltaX = 0f
                        dragDeltaY = 0f
                    },
                    onDrag = { _, dragAmount ->
                        dragDeltaX += dragAmount.x
                        dragDeltaY += dragAmount.y
                    }
                )
            }
    ) {
        if (!showAllApps) {
            // ════════════════════════════════════════════════════════════════
            // HOME PAGE
            // ════════════════════════════════════════════════════════════════
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(Modifier.height(40.dp))

                // ── Battery + Screen time row ──────────────────────────────
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BatteryFull, null, tint = when {
                            battery < 20 -> RED
                            battery < 50 -> Color(0xFFFFD740)
                            else -> theme.accent
                        }, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("$battery%", color = theme.text.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                    Text("📱 $screenTimeStr today", color = theme.text.copy(alpha = 0.5f), fontSize = 11.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔥", fontSize = 12.sp)
                        Text(" $streak day streak", color = theme.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── Focus banner ───────────────────────────────────────────
                if (focusActive) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Brush.horizontalGradient(listOf(Color(0xFF005C3B), Color(0xFF001A0A))))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🔥", fontSize = 14.sp)
                            Spacer(Modifier.width(8.dp))
                            Text("Focus Mode Active", color = Color(0xFF00FFB2), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // ── Clock ──────────────────────────────────────────────────
                Text(
                    text = timeState.first,
                    color = theme.text,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Light,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = timeState.second,
                    color = theme.text.copy(alpha = 0.5f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(28.dp))

                // ── Quick toggles ──────────────────────────────────────────
                QuickToggles(context, theme)

                Spacer(Modifier.height(24.dp))

                // ── Pinned apps ────────────────────────────────────────────
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Home Apps", color = theme.text.copy(alpha = 0.4f), fontSize = 11.sp, letterSpacing = 1.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, null, tint = theme.accent, modifier = Modifier
                            .size(18.dp)
                            .clickable { showAddDialog = true })
                        Spacer(Modifier.width(4.dp))
                        Text("Add", color = theme.accent, fontSize = 11.sp, modifier = Modifier.clickable { showAddDialog = true })
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (pinnedApps.isEmpty()) {
                    Text(
                        "Tap + to pin apps here",
                        color = theme.text.copy(alpha = 0.25f),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    pinnedApps.forEach { app ->
                        PinnedAppRow(
                            app = app,
                            theme = theme,
                            fontSize = appFontSize,
                            showIcon = showIcons,
                            onLaunch = {
                                if (!app.isBlocked) launchApp(context, app.packageName)
                            },
                            onLongPress = { longPressedApp = app }
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                // ── Recently used ──────────────────────────────────────────
                val recentApps = remember(allApps) {
                    allApps.filter { it.usageMinutes > 0 }
                        .sortedByDescending { it.usageMinutes }
                        .take(4)
                }
                if (recentApps.isNotEmpty()) {
                    Text("Recent", color = theme.text.copy(alpha = 0.4f), fontSize = 11.sp, letterSpacing = 1.sp)
                    Spacer(Modifier.height(8.dp))
                    recentApps.forEach { app ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { launchApp(context, app.packageName) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                renamedMap[app.packageName] ?: app.label,
                                color = theme.text.copy(alpha = 0.7f),
                                fontSize = 15.sp
                            )
                            Text("${app.usageMinutes}m", color = theme.text.copy(alpha = 0.35f), fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                // ── Pomodoro shortcut ──────────────────────────────────────
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CARD_BG)
                        .clickable { navController?.navigate("deep_study") }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⏱️", fontSize = 18.sp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Pomodoro Timer", color = theme.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("Start a focus session", color = theme.text.copy(alpha = 0.45f), fontSize = 11.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.ChevronRight, null, tint = theme.text.copy(alpha = 0.3f), modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(Modifier.height(60.dp))
            }

            // ── Bottom hint ────────────────────────────────────────────────
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("↑ All Apps", color = theme.text.copy(alpha = 0.3f), fontSize = 11.sp)
            }

            // ── Settings gear ──────────────────────────────────────────────
            Icon(
                Icons.Default.Settings, null,
                tint = DIM,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(22.dp)
                    .clickable { showSettings = true }
            )

        } else {
            // ════════════════════════════════════════════════════════════════
            // ALL APPS PAGE
            // ════════════════════════════════════════════════════════════════
            AllAppsScreen(
                allApps = allApps,
                pinnedPkgs = pinnedPkgs,
                hiddenPkgs = hiddenPkgs,
                renamedMap = renamedMap,
                theme = theme,
                fontSize = appFontSize,
                showIcons = showIcons,
                query = query,
                onQueryChange = { query = it },
                onLaunch = { app -> if (!app.isBlocked) launchApp(context, app.packageName) },
                onPin = { pkg ->
                    val updated = pinnedPkgs.toMutableList()
                    if (pkg in updated) updated.remove(pkg) else updated.add(pkg)
                    pinnedPkgs = updated
                    prefs.edit().putStringSet(KEY_PINNED, updated.toSet()).apply()
                },
                onHide = { pkg ->
                    val updated = hiddenPkgs.toMutableSet()
                    updated.add(pkg)
                    hiddenPkgs = updated
                    prefs.edit().putStringSet(KEY_HIDDEN, updated).apply()
                },
                onRename = { pkg, name ->
                    val updated = renamedMap.toMutableMap()
                    if (name.isBlank()) updated.remove(pkg) else updated[pkg] = name
                    renamedMap = updated
                    saveRenamedMap(prefs, updated)
                },
                onClose = { showAllApps = false; query = "" }
            )
        }

        // ── Long press context menu ────────────────────────────────────────
        longPressedApp?.let { app ->
            AppContextMenu(
                app = app,
                isPinned = app.packageName in pinnedPkgs,
                theme = theme,
                onUnpin = {
                    val updated = pinnedPkgs.toMutableList()
                    updated.remove(app.packageName)
                    pinnedPkgs = updated
                    prefs.edit().putStringSet(KEY_PINNED, updated.toSet()).apply()
                    longPressedApp = null
                },
                onRename = { newName ->
                    val updated = renamedMap.toMutableMap()
                    if (newName.isBlank()) updated.remove(app.packageName) else updated[app.packageName] = newName
                    renamedMap = updated
                    saveRenamedMap(prefs, updated)
                    longPressedApp = null
                },
                onDismiss = { longPressedApp = null }
            )
        }

        // ── Add dialog ────────────────────────────────────────────────────
        if (showAddDialog) {
            AddAppDialog(
                allApps = allApps,
                pinnedPkgs = pinnedPkgs,
                theme = theme,
                onTogglePin = { pkg ->
                    val updated = pinnedPkgs.toMutableList()
                    if (pkg in updated) updated.remove(pkg) else updated.add(pkg)
                    pinnedPkgs = updated
                    prefs.edit().putStringSet(KEY_PINNED, updated.toSet()).apply()
                },
                onDismiss = { showAddDialog = false }
            )
        }

        // ── Settings ──────────────────────────────────────────────────────
        if (showSettings) {
            LauncherSettingsSheet(
                themeIdx = themeIdx,
                fontSize = fontSize,
                showIcons = showIcons,
                hiddenPkgs = hiddenPkgs,
                allApps = allApps,
                theme = theme,
                onThemeChange = { themeIdx = it; prefs.edit().putInt(KEY_THEME, it).apply() },
                onFontChange = { fontSize = it; prefs.edit().putInt(KEY_FONT_SIZE, it).apply() },
                onIconToggle = { showIcons = it; prefs.edit().putBoolean(KEY_SHOW_ICONS, it).apply() },
                onUnhide = { pkg ->
                    val updated = hiddenPkgs.toMutableSet()
                    updated.remove(pkg)
                    hiddenPkgs = updated
                    prefs.edit().putStringSet(KEY_HIDDEN, updated).apply()
                },
                onBack = { navController?.popBackStack() },
                onDismiss = { showSettings = false }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Quick Toggles
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun QuickToggles(context: Context, theme: LauncherTheme) {
    var flashOn by remember { mutableStateOf(false) }
    var silentOn by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // WiFi
        QuickToggleBtn(
            icon = Icons.Default.Wifi,
            label = "Wi-Fi",
            active = false,
            theme = theme,
            onClick = {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            },
            modifier = Modifier.weight(1f)
        )
        // Silent
        QuickToggleBtn(
            icon = if (silentOn) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
            label = if (silentOn) "Silent" else "Sound",
            active = silentOn,
            theme = theme,
            onClick = {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (silentOn) {
                    am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    silentOn = false
                } else {
                    am.ringerMode = AudioManager.RINGER_MODE_SILENT
                    silentOn = true
                }
            },
            modifier = Modifier.weight(1f)
        )
        // Flashlight
        QuickToggleBtn(
            icon = Icons.Default.FlashOn,
            label = if (flashOn) "Flash On" else "Flash",
            active = flashOn,
            theme = theme,
            onClick = {
                try {
                    val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val cameraId = cm.cameraIdList[0]
                    flashOn = !flashOn
                    cm.setTorchMode(cameraId, flashOn)
                } catch (_: Exception) {}
            },
            modifier = Modifier.weight(1f)
        )
        // RasFocus shortcut
        QuickToggleBtn(
            icon = Icons.Default.Shield,
            label = "Focus",
            active = false,
            theme = theme,
            onClick = { navController?.popBackStack() },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun QuickToggleBtn(
    icon: ImageVector,
    label: String,
    active: Boolean,
    theme: LauncherTheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) theme.accent.copy(alpha = 0.2f) else CARD_BG)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = if (active) theme.accent else theme.text.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (active) theme.accent else theme.text.copy(alpha = 0.5f), fontSize = 10.sp)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pinned app row
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun PinnedAppRow(
    app: AppInfo,
    theme: LauncherTheme,
    fontSize: androidx.compose.ui.unit.TextUnit,
    showIcon: Boolean,
    onLaunch: () -> Unit,
    onLongPress: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onLaunch() },
                    onLongPress = { onLongPress() }
                )
            }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showIcon) {
            val bmp = remember(app.packageName) {
                runCatching {
                    context.packageManager.getApplicationIcon(app.packageName).toBitmap(48, 48)
                }.getOrNull()
            }
            if (bmp != null) {
                Image(bmp.asImageBitmap(), null, modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(12.dp))
            }
        }
        Text(
            text = app.customName.ifBlank { app.label },
            color = if (app.isBlocked) RED.copy(alpha = 0.7f) else theme.text,
            fontSize = fontSize,
            fontWeight = FontWeight.Normal
        )
        if (app.isBlocked) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.Lock, null, tint = RED, modifier = Modifier.size(14.dp))
        }
        if (app.usageMinutes > 0) {
            Spacer(Modifier.weight(1f))
            Text("${app.usageMinutes}m", color = theme.text.copy(alpha = 0.25f), fontSize = 10.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// All Apps Screen
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AllAppsScreen(
    allApps: List<AppInfo>,
    pinnedPkgs: List<String>,
    hiddenPkgs: Set<String>,
    renamedMap: Map<String, String>,
    theme: LauncherTheme,
    fontSize: androidx.compose.ui.unit.TextUnit,
    showIcons: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onLaunch: (AppInfo) -> Unit,
    onPin: (String) -> Unit,
    onHide: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var contextApp by remember { mutableStateOf<AppInfo?>(null) }

    val filtered = remember(query, allApps) {
        if (query.isBlank()) allApps
        else allApps.filter { (renamedMap[it.packageName] ?: it.label).contains(query, ignoreCase = true) }
    }
    val letters = remember(filtered) { filtered.map { (renamedMap[it.packageName] ?: it.label).first().uppercaseChar() }.distinct().sorted() }

    var allAppsDragX by remember { mutableStateOf(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .background(theme.bg)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        if (allAppsDragX > 100f) onClose()   // swipe right → back to home
                        allAppsDragX = 0f
                    },
                    onDrag = { _, dragAmount -> allAppsDragX += dragAmount.x }
                )
            }
    ) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(16.dp))

            // Back + search
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.KeyboardArrowDown, null, tint = theme.text.copy(alpha = 0.5f),
                    modifier = Modifier.size(26.dp).clickable { onClose() })
                Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(color = theme.text, fontSize = 18.sp),
                    decorationBox = { inner ->
                        Box(Modifier.weight(1f).padding(bottom = 4.dp)) {
                            if (query.isBlank()) Text("Search apps...", color = theme.text.copy(alpha = 0.3f), fontSize = 18.sp)
                            inner()
                            Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.BottomCenter).background(DIVIDER))
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Icon(Icons.Default.Search, null, tint = theme.text.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
            }

            Spacer(Modifier.height(16.dp))

            // App list + side index
            Box(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize().padding(end = 26.dp)) {
                    items(filtered) { app ->
                        val displayName = renamedMap[app.packageName] ?: app.label
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = { onLaunch(app) },
                                        onLongPress = { contextApp = app }
                                    )
                                }
                                .padding(horizontal = 20.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (showIcons) {
                                val bmp = remember(app.packageName) {
                                    runCatching {
                                        context.packageManager.getApplicationIcon(app.packageName).toBitmap(48, 48)
                                    }.getOrNull()
                                }
                                if (bmp != null) {
                                    Image(bmp.asImageBitmap(), null, modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)))
                                    Spacer(Modifier.width(12.dp))
                                }
                            }
                            Text(
                                displayName,
                                color = if (app.isBlocked) RED.copy(alpha = 0.7f) else theme.text,
                                fontSize = fontSize,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (app.packageName in pinnedPkgs)
                                Icon(Icons.Default.PushPin, null, tint = theme.accent, modifier = Modifier.size(13.dp))
                            if (app.isBlocked)
                                Icon(Icons.Default.Lock, null, tint = RED, modifier = Modifier.size(13.dp))
                        }
                    }
                }

                // Side letter index
                Column(
                    Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    letters.forEach { letter ->
                        Text(letter.toString(), color = theme.text.copy(alpha = 0.45f), fontSize = 10.sp, modifier = Modifier.padding(vertical = 1.dp))
                    }
                }
            }
        }

        // Context menu
        contextApp?.let { app ->
            AppContextMenu(
                app = app,
                isPinned = app.packageName in pinnedPkgs,
                theme = theme,
                onUnpin = { onPin(app.packageName); contextApp = null },
                onRename = { name -> onRename(app.packageName, name); contextApp = null },
                onDismiss = { contextApp = null },
                extraActions = {
                    ContextMenuRow(Icons.Default.VisibilityOff, "Hide App") { onHide(app.packageName); contextApp = null }
                    ContextMenuRow(
                        if (app.packageName in pinnedPkgs) Icons.Default.PushPin else Icons.Default.PushPin,
                        if (app.packageName in pinnedPkgs) "Unpin from Home" else "Pin to Home"
                    ) { onPin(app.packageName); contextApp = null }
                }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// App Context Menu
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AppContextMenu(
    app: AppInfo,
    isPinned: Boolean,
    theme: LauncherTheme,
    onUnpin: () -> Unit,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
    extraActions: @Composable (() -> Unit)? = null
) {
    var showRenameInput by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(app.customName.ifBlank { app.label }) }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.8f)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF1A1A1A))
                .clickable(enabled = false) {}
                .padding(20.dp)
        ) {
            Text(app.customName.ifBlank { app.label }, color = theme.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(app.packageName, color = theme.text.copy(alpha = 0.35f), fontSize = 10.sp)
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = DIVIDER)
            Spacer(Modifier.height(8.dp))

            extraActions?.invoke()

            if (showRenameInput) {
                BasicTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    textStyle = TextStyle(color = theme.text, fontSize = 15.sp),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                            inner()
                            Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.BottomCenter).background(theme.accent))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showRenameInput = false }) { Text("Cancel", color = DIM) }
                    TextButton(onClick = { onRename(renameText) }) { Text("Save", color = theme.accent) }
                }
            } else {
                ContextMenuRow(Icons.Default.Edit, "Rename") { showRenameInput = true }
            }

            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel", color = DIM, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun ContextMenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onClick() }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = TXT.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = TXT.copy(alpha = 0.85f), fontSize = 14.sp)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Add App Dialog
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AddAppDialog(
    allApps: List<AppInfo>,
    pinnedPkgs: List<String>,
    theme: LauncherTheme,
    onTogglePin: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var search by remember { mutableStateOf("") }
    val filtered = remember(search, allApps) {
        if (search.isBlank()) allApps else allApps.filter { it.label.contains(search, ignoreCase = true) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF141414))
                .padding(16.dp)
        ) {
            Text("Pin Apps to Home", color = theme.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            BasicTextField(
                value = search,
                onValueChange = { search = it },
                singleLine = true,
                textStyle = TextStyle(color = theme.text, fontSize = 14.sp),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(CARD_BG).padding(12.dp)) {
                        if (search.isBlank()) Text("Search...", color = theme.text.copy(alpha = 0.3f), fontSize = 14.sp)
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(filtered) { app ->
                    val pinned = app.packageName in pinnedPkgs
                    Row(
                        Modifier.fillMaxWidth().clickable { onTogglePin(app.packageName) }.padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(app.label, color = theme.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Icon(
                            if (pinned) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            null,
                            tint = if (pinned) theme.accent else DIM,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    HorizontalDivider(color = DIVIDER)
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Done", color = theme.accent)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Settings Screen — Niagara-style expandable sections
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun LauncherSettingsSheet(
    themeIdx: Int,
    fontSize: Int,
    showIcons: Boolean,
    hiddenPkgs: Set<String>,
    allApps: List<AppInfo>,
    theme: LauncherTheme,
    onThemeChange: (Int) -> Unit,
    onFontChange: (Int) -> Unit,
    onIconToggle: (Boolean) -> Unit,
    onUnhide: (String) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val hiddenApps = remember(hiddenPkgs, allApps) { allApps.filter { it.packageName in hiddenPkgs } }

    var expandHomeScreen  by remember { mutableStateOf(false) }
    var expandDisplay     by remember { mutableStateOf(false) }
    var expandGestures    by remember { mutableStateOf(false) }
    var expandMore        by remember { mutableStateOf(false) }

    // Full screen settings page — pure black, no overlay
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {

            // ── Top bar ────────────────────────────────────────────────────
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .padding(horizontal = 16.dp, vertical = 18.dp)
            ) {
                Icon(
                    Icons.Default.ArrowBack, null,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(24.dp)
                        .clickable { onDismiss() }
                )
                Text(
                    "Settings",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            HorizontalDivider(color = Color(0xFF222222), thickness = 0.5.dp)

            // ── Scrollable content ─────────────────────────────────────────
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(8.dp))

                // ── Home screen ────────────────────────────────────────────
                SettingsExpandableSection(
                    title = "Home screen",
                    expanded = expandHomeScreen,
                    onToggle = { expandHomeScreen = !expandHomeScreen }
                ) {
                    // Pinned apps count info
                    SettingsInfoRow("Pinned apps shown on home screen")

                    // Show icons toggle
                    SettingsToggleRow(
                        title = "Show app icons",
                        checked = showIcons,
                        onToggle = onIconToggle
                    )

                    // Hidden apps
                    if (hiddenApps.isNotEmpty()) {
                        SettingsSectionLabel("Hidden apps (${hiddenApps.size})")
                        hiddenApps.forEach { app ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onUnhide(app.packageName) }
                                    .padding(horizontal = 24.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(app.label, color = Color.White, fontSize = 17.sp)
                                Text("Unhide", color = Color(0xFF14C3B2), fontSize = 13.sp)
                            }
                        }
                    }
                }

                SettingsDivider()

                // ── Display ────────────────────────────────────────────────
                SettingsExpandableSection(
                    title = "Display",
                    expanded = expandDisplay,
                    onToggle = { expandDisplay = !expandDisplay }
                ) {
                    // Theme
                    SettingsSectionLabel("Theme")
                    listOf("Pure Black", "Dark Blue", "Dark Green").forEachIndexed { i, name ->
                        SettingsRadioRow(
                            title = name,
                            selected = themeIdx == i,
                            onClick = { onThemeChange(i) }
                        )
                    }

                    SettingsSectionLabel("Font size")
                    listOf("Small", "Medium", "Large").forEachIndexed { i, label ->
                        SettingsRadioRow(
                            title = label,
                            selected = fontSize == i,
                            onClick = { onFontChange(i) }
                        )
                    }

                    // Monochrome mode
                    SettingsToggleRow(
                        title = "Monochrome mode",
                        subtitle = "Grayscale app icons",
                        checked = showIcons, // reuse showIcons as placeholder, extend later
                        onToggle = { /* extend */ }
                    )
                }

                SettingsDivider()

                // ── Gestures ───────────────────────────────────────────────
                SettingsExpandableSection(
                    title = "Gestures",
                    expanded = expandGestures,
                    onToggle = { expandGestures = !expandGestures }
                ) {
                    SettingsInfoRow("Swipe left / up  →  All Apps")
                    SettingsInfoRow("Swipe right  →  Home")
                    SettingsInfoRow("Swipe down  →  Notifications")
                    SettingsInfoRow("Long press app  →  Options")
                }

                SettingsDivider()

                // ── More ───────────────────────────────────────────────────
                SettingsExpandableSection(
                    title = "More",
                    expanded = expandMore,
                    onToggle = { expandMore = !expandMore }
                ) {
                    SettingsClickRow("Back to RasFocus") { onBack(); onDismiss() }
                    SettingsClickRow("Set as default launcher") {
                        try {
                            val intent = Intent(Settings.ACTION_HOME_SETTINGS)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    }
                }

                SettingsDivider()

                // ── Flat items ─────────────────────────────────────────────
                SettingsFlatRow("In-app time reminder") {}
                SettingsDivider()
                SettingsFlatRow("Notification filter") {}
                SettingsDivider()
                SettingsFlatRow("Monochrome mode") {}
                SettingsDivider()
                SettingsFlatRow("Recommend to a friend") {
                    try {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "Check out RasFocus — a focus & parental control app!")
                        }
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(Intent.createChooser(intent, "Share").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    } catch (_: Exception) {}
                }
                SettingsDivider()
                SettingsFlatRow("Device settings") {
                    try {
                        val intent = Intent(Settings.ACTION_SETTINGS)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                }

                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Settings UI Components
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SettingsExpandableSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Normal)
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                content()
            }
        }
    }
}

@Composable
fun SettingsToggleRow(title: String, subtitle: String = "", checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
            if (subtitle.isNotBlank())
                Text(subtitle, color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF14C3B2),
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFF444444)
            )
        )
    }
}

@Composable
fun SettingsRadioRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White.copy(alpha = if (selected) 1f else 0.6f), fontSize = 16.sp)
        if (selected)
            Icon(Icons.Default.Check, null, tint = Color(0xFF14C3B2), modifier = Modifier.size(18.dp))
    }
}

@Composable
fun SettingsClickRow(title: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
    }
}

@Composable
fun SettingsFlatRow(title: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Normal)
    }
}

@Composable
fun SettingsSectionLabel(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.4f),
        fontSize = 12.sp,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
    )
}

@Composable
fun SettingsInfoRow(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.5f),
        fontSize = 14.sp,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
    )
}

@Composable
fun SettingsDivider() {
    HorizontalDivider(
        color = Color(0xFF1A1A1A),
        thickness = 0.5.dp,
        modifier = Modifier.padding(horizontal = 0.dp)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun getInstalledApps(
    context: Context,
    hidden: Set<String>,
    renamed: Map<String, String>,
    blocked: Set<String>,
    usage: Map<String, Long>
): List<AppInfo> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
    return pm.queryIntentActivities(intent, 0)
        .filter { it.activityInfo.packageName !in hidden }
        .map { ri ->
            val pkg = ri.activityInfo.packageName
            AppInfo(
                label = ri.loadLabel(pm).toString(),
                packageName = pkg,
                customName = renamed[pkg] ?: "",
                isBlocked = pkg in blocked,
                usageMinutes = usage[pkg] ?: 0L
            )
        }
        .sortedBy { (renamed[it.packageName] ?: it.label).lowercase() }
        .distinctBy { it.packageName }
}

private fun launchApp(context: Context, packageName: String) {
    try {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent != null) context.startActivity(intent)
    } catch (_: Exception) {}
}

private fun getCurrentTime(): Pair<String, String> {
    val cal = java.util.Calendar.getInstance()
    val h = cal.get(java.util.Calendar.HOUR).let { if (it == 0) 12 else it }
    val m = cal.get(java.util.Calendar.MINUTE)
    val s = cal.get(java.util.Calendar.SECOND)
    val ap = if (cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "AM" else "PM"
    val y = cal.get(java.util.Calendar.YEAR)
    val mo = cal.get(java.util.Calendar.MONTH) + 1
    val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
    return Pair("$h:%02d:%02d $ap".format(m, s), "%d-%02d-%02d".format(y, mo, d))
}

private fun getBatteryLevel(context: Context): Int {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
}

private fun isFocusActive(context: Context): Boolean {
    val prefs = context.getSharedPreferences("self_control_prefs", Context.MODE_PRIVATE)
    return prefs.getBoolean("deep_study_enabled", false) ||
           prefs.getBoolean("extreme_block_enabled", false)
}

private fun getScreenTimeToday(context: Context): String {
    return try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return "N/A"
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val startOfDay = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        }.timeInMillis
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, now)
        val totalMs = stats?.sumOf { it.totalTimeInForeground } ?: 0L
        val mins = totalMs / 60000
        if (mins < 60) "${mins}m" else "${mins / 60}h ${mins % 60}m"
    } catch (_: Exception) { "N/A" }
}

private fun getStreak(context: Context): Int {
    val prefs = context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
    val lastDay = prefs.getLong("last_open_day", 0L)
    val today = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val streak = prefs.getInt("streak", 0)
    val dayMs = 86_400_000L
    val newStreak = when {
        lastDay == today -> streak
        lastDay == today - dayMs -> streak + 1
        else -> 1
    }
    prefs.edit().putLong("last_open_day", today).putInt("streak", newStreak).apply()
    return newStreak
}

private fun getUsageMap(context: Context): Map<String, Long> {
    return try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return emptyMap()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val startOfDay = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        }.timeInMillis
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, now)
        stats?.associate { it.packageName to (it.totalTimeInForeground / 60000) } ?: emptyMap()
    } catch (_: Exception) { emptyMap() }
}

private fun getBlockedApps(context: Context): Set<String> {
    val prefs = context.getSharedPreferences("self_control_prefs", Context.MODE_PRIVATE)
    return prefs.getStringSet("blocked_packages", emptySet()) ?: emptySet()
}

private fun loadRenamedMap(prefs: SharedPreferences): Map<String, String> {
    val raw = prefs.getString(KEY_RENAMED, "") ?: ""
    if (raw.isBlank()) return emptyMap()
    return raw.split(";;").mapNotNull {
        val parts = it.split("=:=", limit = 2)
        if (parts.size == 2) parts[0] to parts[1] else null
    }.toMap()
}

private fun saveRenamedMap(prefs: SharedPreferences, map: Map<String, String>) {
    val raw = map.entries.joinToString(";;") { "${it.key}=:=${it.value}" }
    prefs.edit().putString(KEY_RENAMED, raw).apply()
}

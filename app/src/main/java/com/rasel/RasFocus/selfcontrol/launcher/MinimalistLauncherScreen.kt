package com.rasel.RasFocus.selfcontrol.launcher

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.camera2.CameraManager
import android.media.AudioManager
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.min

// ─────────────────────────────────────────────────────────────────────────────
// Colors
// ─────────────────────────────────────────────────────────────────────────────
private val BG      = Color(0xFF000000)
private val TXT     = Color(0xFFFFFFFF)
private val ACCENT  = Color(0xFF14C3B2)
private val DIM     = Color(0xFF888888)
private val CARD_BG = Color(0xFF111111)
private val RED     = Color(0xFFFF5252)
private val DIVIDER = Color(0xFF222222)

private const val PREFS       = "launcher_prefs"
private const val KEY_PINNED  = "pinned_apps"
private const val KEY_HIDDEN  = "hidden_apps"
private const val KEY_RENAMED = "renamed_apps"
private const val KEY_THEME   = "launcher_theme"
private const val KEY_FONT    = "font_size"
private const val KEY_ICONS   = "show_icons"

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
    DarkBlue(Color(0xFF0A0E1A),  Color(0xFFE8EAF6), Color(0xFF4FC3F7)),
    DarkGreen(Color(0xFF030E0A), Color(0xFFE8F5E9), Color(0xFF00FFB2))
}

// ─────────────────────────────────────────────────────────────────────────────
// Main Screen
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun MinimalistLauncherScreen(navController: NavController? = null) {
    val context = LocalContext.current
    val prefs   = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    var showAllApps  by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var longPressedApp by remember { mutableStateOf<AppInfo?>(null) }
    var query by rememberSaveable { mutableStateOf("") }

    var pinnedPkgs by remember { mutableStateOf(prefs.getStringSet(KEY_PINNED, setOf())!!.toMutableList()) }
    var hiddenPkgs by remember { mutableStateOf(prefs.getStringSet(KEY_HIDDEN, setOf())!!) }
    var renamedMap by remember { mutableStateOf(loadRenamedMap(prefs)) }
    var themeIdx   by rememberSaveable { mutableStateOf(prefs.getInt(KEY_THEME, 0)) }
    var fontSize   by rememberSaveable { mutableStateOf(prefs.getInt(KEY_FONT, 1)) }
    var showIcons  by rememberSaveable { mutableStateOf(prefs.getBoolean(KEY_ICONS, false)) }

    val theme       = LauncherTheme.entries[themeIdx.coerceIn(0, 2)]
    val appFontSize = when (fontSize) { 0 -> 16.sp; 2 -> 26.sp; else -> 21.sp }

    val allApps: List<AppInfo> = remember(hiddenPkgs, renamedMap) {
        getInstalledApps(context, hiddenPkgs, renamedMap, getBlockedApps(context), getUsageMap(context))
    }
    val pinnedApps = remember(pinnedPkgs, allApps) {
        pinnedPkgs.mapNotNull { pkg -> allApps.find { it.packageName == pkg } }
    }

    var timeState by remember { mutableStateOf(getCurrentTime()) }
    LaunchedEffect(Unit) { while (true) { delay(1000L); timeState = getCurrentTime() } }

    val battery     = getBatteryLevel(context)
    val isCharging  = isDeviceCharging(context)

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
                            if (dragDeltaX < -80f) showAllApps = true
                            if (dragDeltaX > 80f)  showAllApps = false
                        } else {
                            if (dragDeltaY < -80f) showAllApps = true
                            if (dragDeltaY > 80f) {
                                try {
                                    val sb = context.getSystemService("statusbar")
                                    sb?.javaClass?.getMethod("expandNotificationsPanel")?.invoke(sb)
                                } catch (_: Exception) {}
                            }
                        }
                        dragDeltaX = 0f; dragDeltaY = 0f
                    },
                    onDrag = { _, d -> dragDeltaX += d.x; dragDeltaY += d.y }
                )
            }
    ) {
        if (!showAllApps) {
            // ═══════════════════════════════════════════════════════
            // HOME SCREEN  (Image 2 exact design)
            // ═══════════════════════════════════════════════════════
            HomeScreen(
                timeState      = timeState,
                battery        = battery,
                isCharging     = isCharging,
                pinnedApps     = pinnedApps,
                theme          = theme,
                appFontSize    = appFontSize,
                showIcons      = showIcons,
                renamedMap     = renamedMap,
                onAddClick     = { showAddDialog = true },
                onLaunch       = { app -> if (!app.isBlocked) launchApp(context, app.packageName) },
                onLongPress    = { app -> longPressedApp = app },
                onSettings     = { showSettings = true },
                onPhone        = { launchDialer(context) },
                onCamera       = { launchCamera(context) }
            )
        } else {
            // ═══════════════════════════════════════════════════════
            // ALL APPS SCREEN  (Image 1 exact design)
            // ═══════════════════════════════════════════════════════
            AllAppsScreen(
                allApps    = allApps,
                pinnedPkgs = pinnedPkgs,
                hiddenPkgs = hiddenPkgs,
                renamedMap = renamedMap,
                theme      = theme,
                fontSize   = appFontSize,
                showIcons  = showIcons,
                query      = query,
                onQueryChange = { query = it },
                onLaunch   = { app -> if (!app.isBlocked) launchApp(context, app.packageName) },
                onPin      = { pkg ->
                    val u = pinnedPkgs.toMutableList()
                    if (pkg in u) u.remove(pkg) else u.add(pkg)
                    pinnedPkgs = u
                    prefs.edit().putStringSet(KEY_PINNED, u.toSet()).apply()
                },
                onHide     = { pkg ->
                    val u = hiddenPkgs.toMutableSet(); u.add(pkg); hiddenPkgs = u
                    prefs.edit().putStringSet(KEY_HIDDEN, u).apply()
                },
                onRename   = { pkg, name ->
                    val u = renamedMap.toMutableMap()
                    if (name.isBlank()) u.remove(pkg) else u[pkg] = name
                    renamedMap = u; saveRenamedMap(prefs, u)
                },
                onSettings = { showSettings = true },
                onClose    = { showAllApps = false; query = "" }
            )
        }

        // Long press menu
        longPressedApp?.let { app ->
            AppContextMenu(
                app      = app,
                isPinned = app.packageName in pinnedPkgs,
                theme    = theme,
                onUnpin  = {
                    val u = pinnedPkgs.toMutableList(); u.remove(app.packageName); pinnedPkgs = u
                    prefs.edit().putStringSet(KEY_PINNED, u.toSet()).apply(); longPressedApp = null
                },
                onRename = { name ->
                    val u = renamedMap.toMutableMap()
                    if (name.isBlank()) u.remove(app.packageName) else u[app.packageName] = name
                    renamedMap = u; saveRenamedMap(prefs, u); longPressedApp = null
                },
                onDismiss = { longPressedApp = null }
            )
        }

        // Add dialog
        if (showAddDialog) {
            AddAppDialog(
                allApps    = allApps,
                pinnedPkgs = pinnedPkgs,
                theme      = theme,
                onTogglePin = { pkg ->
                    val u = pinnedPkgs.toMutableList()
                    if (pkg in u) u.remove(pkg) else u.add(pkg)
                    pinnedPkgs = u
                    prefs.edit().putStringSet(KEY_PINNED, u.toSet()).apply()
                },
                onDismiss  = { showAddDialog = false }
            )
        }

        // Settings
        if (showSettings) {
            LauncherSettingsSheet(
                themeIdx    = themeIdx,
                fontSize    = fontSize,
                showIcons   = showIcons,
                hiddenPkgs  = hiddenPkgs,
                allApps     = allApps,
                theme       = theme,
                onThemeChange = { themeIdx = it; prefs.edit().putInt(KEY_THEME, it).apply() },
                onFontChange  = { fontSize = it; prefs.edit().putInt(KEY_FONT, it).apply() },
                onIconToggle  = { showIcons = it; prefs.edit().putBoolean(KEY_ICONS, it).apply() },
                onUnhide      = { pkg ->
                    val u = hiddenPkgs.toMutableSet(); u.remove(pkg); hiddenPkgs = u
                    prefs.edit().putStringSet(KEY_HIDDEN, u).apply()
                },
                onBack    = { navController?.popBackStack() },
                onDismiss = { showSettings = false }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// HOME SCREEN  — exact match Image 2
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun HomeScreen(
    timeState:   Pair<String, String>,
    battery:     Int,
    isCharging:  Boolean,
    pinnedApps:  List<AppInfo>,
    theme:       LauncherTheme,
    appFontSize: androidx.compose.ui.unit.TextUnit,
    showIcons:   Boolean,
    renamedMap:  Map<String, String>,
    onAddClick:  () -> Unit,
    onLaunch:    (AppInfo) -> Unit,
    onLongPress: (AppInfo) -> Unit,
    onSettings:  () -> Unit,
    onPhone:     () -> Unit,
    onCamera:    () -> Unit
) {
    Box(Modifier.fillMaxSize().background(BG)) {

        // ── Main content ──────────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(52.dp))

            // ── Clock with arc (Image 2 style) ────────────────────────────
            ClockWithArc(
                time = timeState.first,
                date = timeState.second,
                battery = battery,
                isCharging = isCharging
            )

            Spacer(Modifier.height(48.dp))

            // ── Pinned apps list ──────────────────────────────────────────
            if (pinnedApps.isEmpty()) {
                // Empty state — just show placeholder text softly
                Text(
                    "No apps added yet",
                    color = TXT.copy(alpha = 0.2f),
                    fontSize = 16.sp,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                pinnedApps.forEach { app ->
                    Text(
                        text     = renamedMap[app.packageName] ?: app.label,
                        color    = if (app.isBlocked) RED.copy(alpha = 0.7f) else TXT,
                        fontSize = appFontSize,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap       = { onLaunch(app) },
                                    onLongPress = { onLongPress(app) }
                                )
                            }
                            .padding(vertical = 12.dp)
                    )
                }
            }
        }

        // ── Bottom bar — phone left, camera right ─────────────────────────
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            // Phone icon
            Icon(
                Icons.Default.Phone,
                contentDescription = "Phone",
                tint     = TXT,
                modifier = Modifier.size(26.dp).clickable { onPhone() }
            )
            // Camera icon
            Icon(
                Icons.Default.CameraAlt,
                contentDescription = "Camera",
                tint     = TXT,
                modifier = Modifier.size(26.dp).clickable { onCamera() }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Clock with curved arc + battery ring  (Image 2 top section)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ClockWithArc(
    time:       String,
    date:       String,
    battery:    Int,
    isCharging: Boolean
) {
    // charging color animation
    val chargingColor by animateColorAsState(
        targetValue = if (isCharging) Color(0xFF00FFB2) else TXT,
        animationSpec = tween(600),
        label = "chargeColor"
    )
    // pulse when charging
    val pulseAnim = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulseAnim.animateFloat(
        initialValue = 0.6f,
        targetValue  = 1.0f,
        animationSpec = infiniteRepeatable(
            animation  = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Box(
        Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        // Arc decoration + battery ring drawn with Canvas
        val arcColor     = if (isCharging) chargingColor else TXT.copy(alpha = 0.35f)
        val arcAlpha     = if (isCharging) pulseAlpha else 1f
        val batteryFrac  = battery / 100f

        Box(
            Modifier
                .size(180.dp)
                .drawBehind {
                    val stroke     = 2.5.dp.toPx()
                    val arcRadius  = size.width / 2f - stroke
                    val cx         = size.width / 2f
                    val cy         = size.height / 2f

                    // Background ring (dim)
                    drawArc(
                        color       = Color.White.copy(alpha = 0.08f),
                        startAngle  = -200f,
                        sweepAngle  = 220f,
                        useCenter   = false,
                        topLeft     = Offset(cx - arcRadius, cy - arcRadius),
                        size        = Size(arcRadius * 2, arcRadius * 2),
                        style       = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                    // Battery progress arc
                    drawArc(
                        color       = arcColor.copy(alpha = arcAlpha),
                        startAngle  = -200f,
                        sweepAngle  = 220f * batteryFrac,
                        useCenter   = false,
                        topLeft     = Offset(cx - arcRadius, cy - arcRadius),
                        size        = Size(arcRadius * 2, arcRadius * 2),
                        style       = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
        ) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement   = Arrangement.Center,
                horizontalAlignment   = Alignment.CenterHorizontally
            ) {
                Text(
                    text       = time,
                    color      = if (isCharging) chargingColor else TXT,
                    fontSize   = 32.sp,
                    fontWeight = FontWeight.Light
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text     = date,
                    color    = TXT.copy(alpha = 0.5f),
                    fontSize = 13.sp
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ALL APPS SCREEN  — exact match Image 1
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AllAppsScreen(
    allApps:       List<AppInfo>,
    pinnedPkgs:    List<String>,
    hiddenPkgs:    Set<String>,
    renamedMap:    Map<String, String>,
    theme:         LauncherTheme,
    fontSize:      androidx.compose.ui.unit.TextUnit,
    showIcons:     Boolean,
    query:         String,
    onQueryChange: (String) -> Unit,
    onLaunch:      (AppInfo) -> Unit,
    onPin:         (String) -> Unit,
    onHide:        (String) -> Unit,
    onRename:      (String, String) -> Unit,
    onSettings:    () -> Unit,
    onClose:       () -> Unit
) {
    val context    = LocalContext.current
    var contextApp by remember { mutableStateOf<AppInfo?>(null) }

    val filtered = remember(query, allApps) {
        if (query.isBlank()) allApps
        else allApps.filter { (renamedMap[it.packageName] ?: it.label).contains(query, ignoreCase = true) }
    }
    val letters = remember(filtered) {
        filtered.map { (renamedMap[it.packageName] ?: it.label).first().uppercaseChar() }.distinct().sorted()
    }

    var swipeDragX by remember { mutableStateOf(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .background(BG)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        if (swipeDragX > 100f) onClose()
                        swipeDragX = 0f
                    },
                    onDrag = { _, d -> swipeDragX += d.x }
                )
            }
    ) {
        Column(Modifier.fillMaxSize()) {

            // ── Search bar (top, like Image 1) ────────────────────────────
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value         = query,
                    onValueChange = onQueryChange,
                    singleLine    = true,
                    textStyle     = TextStyle(color = TXT, fontSize = 17.sp),
                    decorationBox = { inner ->
                        Box(
                            Modifier
                                .weight(1f)
                                .drawBehind {
                                    val y = size.height
                                    drawLine(
                                        color       = Color(0xFF444444),
                                        start       = Offset(0f, y),
                                        end         = Offset(size.width, y),
                                        strokeWidth = 1.dp.toPx()
                                    )
                                }
                                .padding(bottom = 6.dp)
                        ) {
                            if (query.isBlank()) Text("", color = TXT.copy(alpha = 0f))
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Icon(
                    Icons.Default.Search, null,
                    tint     = TXT.copy(alpha = 0.7f),
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── App list + side letter index ──────────────────────────────
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .padding(end = 24.dp)
                ) {
                    items(filtered) { app ->
                        val displayName = renamedMap[app.packageName] ?: app.label
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap       = { onLaunch(app) },
                                        onLongPress = { contextApp = app }
                                    )
                                }
                                .padding(horizontal = 20.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (showIcons) {
                                val bmp = remember(app.packageName) {
                                    runCatching {
                                        context.packageManager.getApplicationIcon(app.packageName).toBitmap(48, 48)
                                    }.getOrNull()
                                }
                                if (bmp != null) {
                                    androidx.compose.foundation.Image(
                                        bmp.asImageBitmap(), null,
                                        modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp))
                                    )
                                    Spacer(Modifier.width(12.dp))
                                }
                            }
                            Text(
                                text     = displayName,
                                color    = if (app.isBlocked) RED.copy(alpha = 0.7f) else TXT,
                                fontSize = fontSize,
                                fontWeight = FontWeight.Light,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // ── Right side letter index (Image 1 exact) ───────────────
                Column(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    letters.forEach { letter ->
                        Text(
                            letter.toString(),
                            color    = TXT.copy(alpha = 0.45f),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }

            // ── Bottom row: padding so settings icon shows (Image 1 bottom right) ──
            Spacer(Modifier.height(56.dp))
        }

        // ── Settings gear — bottom right (Image 1 exact) ──────────────────
        Icon(
            Icons.Default.Settings, null,
            tint     = DIM,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp)
                .size(22.dp)
                .clickable { onSettings() }
        )

        // Context menu
        contextApp?.let { app ->
            AppContextMenu(
                app      = app,
                isPinned = app.packageName in pinnedPkgs,
                theme    = theme,
                onUnpin  = { onPin(app.packageName); contextApp = null },
                onRename = { name -> onRename(app.packageName, name); contextApp = null },
                onDismiss = { contextApp = null },
                extraActions = {
                    ContextMenuRow(Icons.Default.VisibilityOff, "Hide App") {
                        onHide(app.packageName); contextApp = null
                    }
                    ContextMenuRow(
                        if (app.packageName in pinnedPkgs) Icons.Default.PushPin else Icons.Default.PushPin,
                        if (app.packageName in pinnedPkgs) "Remove from Home" else "Add to Home"
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
    app:          AppInfo,
    isPinned:     Boolean,
    theme:        LauncherTheme,
    onUnpin:      () -> Unit,
    onRename:     (String) -> Unit,
    onDismiss:    () -> Unit,
    extraActions: @Composable (() -> Unit)? = null
) {
    var showRename by remember { mutableStateOf(false) }
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
            Text(app.customName.ifBlank { app.label }, color = TXT, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(app.packageName, color = TXT.copy(alpha = 0.35f), fontSize = 10.sp)
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = DIVIDER)
            Spacer(Modifier.height(8.dp))

            extraActions?.invoke()

            if (showRename) {
                BasicTextField(
                    value         = renameText,
                    onValueChange = { renameText = it },
                    textStyle     = TextStyle(color = TXT, fontSize = 15.sp),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                            inner()
                            Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.BottomCenter).background(ACCENT))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showRename = false }) { Text("Cancel", color = DIM) }
                    TextButton(onClick = { onRename(renameText) }) { Text("Save", color = ACCENT) }
                }
            } else {
                ContextMenuRow(Icons.Default.Edit, "Rename") { showRename = true }
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
// Add App Dialog — long press on Home to add
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AddAppDialog(
    allApps:     List<AppInfo>,
    pinnedPkgs:  List<String>,
    theme:       LauncherTheme,
    onTogglePin: (String) -> Unit,
    onDismiss:   () -> Unit
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
            Text("Add to Home", color = TXT, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            BasicTextField(
                value         = search,
                onValueChange = { search = it },
                singleLine    = true,
                textStyle     = TextStyle(color = TXT, fontSize = 14.sp),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(CARD_BG).padding(12.dp)) {
                        if (search.isBlank()) Text("Search...", color = TXT.copy(alpha = 0.3f), fontSize = 14.sp)
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
                        Modifier.fillMaxWidth().clickable { onTogglePin(app.packageName) }
                            .padding(vertical = 11.dp, horizontal = 4.dp),
                        verticalAlignment    = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(app.label, color = TXT, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Icon(
                            if (pinned) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            null,
                            tint     = if (pinned) ACCENT else DIM,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    HorizontalDivider(color = DIVIDER)
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Done", color = ACCENT)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Settings Sheet
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun LauncherSettingsSheet(
    themeIdx:     Int,
    fontSize:     Int,
    showIcons:    Boolean,
    hiddenPkgs:   Set<String>,
    allApps:      List<AppInfo>,
    theme:        LauncherTheme,
    onThemeChange: (Int) -> Unit,
    onFontChange:  (Int) -> Unit,
    onIconToggle:  (Boolean) -> Unit,
    onUnhide:      (String) -> Unit,
    onBack:        () -> Unit,
    onDismiss:     () -> Unit
) {
    val context    = LocalContext.current
    val hiddenApps = remember(hiddenPkgs, allApps) { allApps.filter { it.packageName in hiddenPkgs } }

    var expandHome     by remember { mutableStateOf(false) }
    var expandDisplay  by remember { mutableStateOf(false) }
    var expandGestures by remember { mutableStateOf(false) }
    var expandMore     by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxWidth().background(Color.Black).padding(horizontal = 16.dp, vertical = 18.dp)
            ) {
                Icon(
                    Icons.Default.ArrowBack, null,
                    tint     = Color.White,
                    modifier = Modifier.align(Alignment.CenterStart).size(24.dp).clickable { onDismiss() }
                )
                Text(
                    "Settings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Normal,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            HorizontalDivider(color = Color(0xFF222222), thickness = 0.5.dp)

            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(8.dp))

                SettingsExpandableSection("Home screen", expandHome, { expandHome = !expandHome }) {
                    SettingsToggleRow("Show app icons", checked = showIcons, onToggle = onIconToggle)
                    if (hiddenApps.isNotEmpty()) {
                        SettingsSectionLabel("Hidden apps (${hiddenApps.size})")
                        hiddenApps.forEach { app ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onUnhide(app.packageName) }
                                    .padding(horizontal = 24.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment     = Alignment.CenterVertically
                            ) {
                                Text(app.label, color = Color.White, fontSize = 17.sp)
                                Text("Unhide", color = Color(0xFF14C3B2), fontSize = 13.sp)
                            }
                        }
                    }
                }

                SettingsDivider()

                SettingsExpandableSection("Display", expandDisplay, { expandDisplay = !expandDisplay }) {
                    SettingsSectionLabel("Theme")
                    listOf("Pure Black", "Dark Blue", "Dark Green").forEachIndexed { i, name ->
                        SettingsRadioRow(name, themeIdx == i) { onThemeChange(i) }
                    }
                    SettingsSectionLabel("Font size")
                    listOf("Small", "Medium", "Large").forEachIndexed { i, lbl ->
                        SettingsRadioRow(lbl, fontSize == i) { onFontChange(i) }
                    }
                }

                SettingsDivider()

                SettingsExpandableSection("Gestures", expandGestures, { expandGestures = !expandGestures }) {
                    SettingsInfoRow("Swipe left / up  →  All Apps")
                    SettingsInfoRow("Swipe right  →  Home")
                    SettingsInfoRow("Swipe down  →  Notifications")
                    SettingsInfoRow("Long press app  →  Options")
                }

                SettingsDivider()

                SettingsExpandableSection("More", expandMore, { expandMore = !expandMore }) {
                    SettingsClickRow("Back to RasFocus") { onBack(); onDismiss() }
                    SettingsClickRow("Set as default launcher") {
                        try {
                            val i = Intent(Settings.ACTION_HOME_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            context.startActivity(i)
                        } catch (_: Exception) {}
                    }
                }

                SettingsDivider()
                SettingsFlatRow("Device settings") {
                    try {
                        context.startActivity(Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    } catch (_: Exception) {}
                }

                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Settings UI atoms
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SettingsExpandableSection(title: String, expanded: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { onToggle() }.padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(title, color = Color.White, fontSize = 19.sp)
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null, tint = Color.White, modifier = Modifier.size(22.dp)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) { content() }
        }
    }
}

@Composable
fun SettingsToggleRow(title: String, subtitle: String = "", checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
            if (subtitle.isNotBlank()) Text(subtitle, color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onToggle,
            colors  = SwitchDefaults.colors(
                checkedThumbColor   = Color.White, checkedTrackColor   = Color(0xFF14C3B2),
                uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF444444)
            )
        )
    }
}

@Composable
fun SettingsRadioRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White.copy(alpha = if (selected) 1f else 0.6f), fontSize = 16.sp)
        if (selected) Icon(Icons.Default.Check, null, tint = Color(0xFF14C3B2), modifier = Modifier.size(18.dp))
    }
}

@Composable
fun SettingsClickRow(title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 24.dp, vertical = 16.dp)) {
        Text(title, color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
    }
}

@Composable
fun SettingsFlatRow(title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 24.dp, vertical = 20.dp)) {
        Text(title, color = Color.White, fontSize = 19.sp)
    }
}

@Composable
fun SettingsSectionLabel(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp, letterSpacing = 0.5.sp,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
}

@Composable
fun SettingsInfoRow(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
}

@Composable
fun SettingsDivider() {
    HorizontalDivider(color = Color(0xFF1A1A1A), thickness = 0.5.dp)
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────
private fun getInstalledApps(
    context: Context, hidden: Set<String>, renamed: Map<String, String>,
    blocked: Set<String>, usage: Map<String, Long>
): List<AppInfo> {
    val pm     = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
    return pm.queryIntentActivities(intent, 0)
        .filter { it.activityInfo.packageName !in hidden }
        .map { ri ->
            val pkg = ri.activityInfo.packageName
            AppInfo(
                label         = ri.loadLabel(pm).toString(),
                packageName   = pkg,
                customName    = renamed[pkg] ?: "",
                isBlocked     = pkg in blocked,
                usageMinutes  = usage[pkg] ?: 0L
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

private fun launchDialer(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_DIAL).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    } catch (_: Exception) {}
}

private fun launchCamera(context: Context) {
    try {
        context.startActivity(Intent("android.media.action.IMAGE_CAPTURE").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    } catch (_: Exception) {}
}

private fun getCurrentTime(): Pair<String, String> {
    val cal = java.util.Calendar.getInstance()
    val h   = cal.get(java.util.Calendar.HOUR).let { if (it == 0) 12 else it }
    val m   = cal.get(java.util.Calendar.MINUTE)
    val s   = cal.get(java.util.Calendar.SECOND)
    val ap  = if (cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "AM" else "PM"
    val y   = cal.get(java.util.Calendar.YEAR)
    val mo  = cal.get(java.util.Calendar.MONTH) + 1
    val d   = cal.get(java.util.Calendar.DAY_OF_MONTH)
    return "$h:%02d:%02d $ap".format(m, s) to "%d-%02d-%02d".format(y, mo, d)
}

private fun getBatteryLevel(context: Context): Int {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
}

private fun isDeviceCharging(context: Context): Boolean {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    return bm.isCharging
}

private fun isFocusActive(context: Context): Boolean {
    val prefs = context.getSharedPreferences("self_control_prefs", Context.MODE_PRIVATE)
    return prefs.getBoolean("deep_study_enabled", false) || prefs.getBoolean("extreme_block_enabled", false)
}

private fun getScreenTimeToday(context: Context): String {
    return try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return "N/A"
        val usm  = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now  = System.currentTimeMillis()
        val start = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        }.timeInMillis
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)
        val mins  = (stats?.sumOf { it.totalTimeInForeground } ?: 0L) / 60000
        if (mins < 60) "${mins}m" else "${mins / 60}h ${mins % 60}m"
    } catch (_: Exception) { "N/A" }
}

private fun getStreak(context: Context): Int {
    val prefs   = context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
    val today   = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val lastDay = prefs.getLong("last_open_day", 0L)
    val streak  = prefs.getInt("streak", 0)
    val dayMs   = 86_400_000L
    val newStreak = when {
        lastDay == today          -> streak
        lastDay == today - dayMs  -> streak + 1
        else                      -> 1
    }
    prefs.edit().putLong("last_open_day", today).putInt("streak", newStreak).apply()
    return newStreak
}

private fun getUsageMap(context: Context): Map<String, Long> {
    return try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return emptyMap()
        val usm  = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now  = System.currentTimeMillis()
        val start = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        }.timeInMillis
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)
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
        val p = it.split("=:=", limit = 2)
        if (p.size == 2) p[0] to p[1] else null
    }.toMap()
}

private fun saveRenamedMap(prefs: SharedPreferences, map: Map<String, String>) {
    prefs.edit().putString(KEY_RENAMED, map.entries.joinToString(";;") { "${it.key}=:=${it.value}" }).apply()
}

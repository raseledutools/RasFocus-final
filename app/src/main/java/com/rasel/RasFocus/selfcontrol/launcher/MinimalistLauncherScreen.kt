@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.rasel.RasFocus.selfcontrol.launcher

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import androidx.activity.compose.BackHandler
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlin.math.abs

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
private const val KEY_BTN_L   = "bottom_btn_left"
private const val KEY_BTN_R   = "bottom_btn_right"
private const val KEY_CLOCK_PKG = "clock_btn_pkg"

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

// which picker slot is active
enum class PickerSlot { CLOCK, BTN_LEFT, BTN_RIGHT, NONE }

// ─────────────────────────────────────────────────────────────────────────────
// Main Screen
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun MinimalistLauncherScreen(navController: NavController? = null) {
    val context  = LocalContext.current
    val prefs    = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val keyboard = LocalSoftwareKeyboardController.current

    var showSidebar   by rememberSaveable { mutableStateOf(false) }
    var showSettings  by rememberSaveable { mutableStateOf(false) }
    var longPressedApp by remember { mutableStateOf<AppInfo?>(null) }
    var pickerSlot    by remember { mutableStateOf(PickerSlot.NONE) }
    var query         by rememberSaveable { mutableStateOf("") }

    // Hide keyboard when sidebar closes
    LaunchedEffect(showSidebar) {
        if (!showSidebar) keyboard?.hide()
    }

    // Load pinned apps in saved order (pinned_order string preserves sequence; fall back to Set)
    var pinnedPkgs by remember {
        mutableStateOf(
            run {
                val orderStr = prefs.getString("pinned_order", "")
                if (!orderStr.isNullOrBlank()) {
                    orderStr.split(",").filter { it.isNotBlank() }.toMutableList()
                } else {
                    prefs.getStringSet(KEY_PINNED, setOf())!!.toMutableList()
                }
            }
        )
    }
    var hiddenPkgs by remember { mutableStateOf(prefs.getStringSet(KEY_HIDDEN, setOf())!!) }
    var renamedMap by remember { mutableStateOf(loadRenamedMap(prefs)) }
    var themeIdx   by rememberSaveable { mutableStateOf(prefs.getInt(KEY_THEME, 0)) }
    var fontSize   by rememberSaveable { mutableStateOf(prefs.getInt(KEY_FONT, 1)) }
    var showIcons  by rememberSaveable { mutableStateOf(prefs.getBoolean(KEY_ICONS, false)) }

    // bottom button packages (default: phone + camera)
    var btnLeftPkg  by remember { mutableStateOf(prefs.getString(KEY_BTN_L, "PHONE") ?: "PHONE") }
    var btnRightPkg by remember { mutableStateOf(prefs.getString(KEY_BTN_R, "CAMERA") ?: "CAMERA") }
    var clockPkg    by remember { mutableStateOf(prefs.getString(KEY_CLOCK_PKG, "") ?: "") }

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

    val battery    = getBatteryLevel(context)
    val isCharging = isDeviceCharging(context)

    // Back press: sidebar → settings → context menu → nothing (launcher is home, swallow back)
    BackHandler(enabled = showSidebar || showSettings || longPressedApp != null || pickerSlot != PickerSlot.NONE) {
        when {
            longPressedApp != null  -> { longPressedApp = null }
            pickerSlot != PickerSlot.NONE -> { pickerSlot = PickerSlot.NONE }
            showSidebar             -> { showSidebar = false; query = "" }
            showSettings            -> { showSettings = false }
        }
    }

    // sidebar slide animation
    val sidebarOffsetX by animateDpAsState(
        targetValue   = if (showSidebar) 0.dp else 320.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label         = "sidebarOffset"
    )
    val sidebarAlpha by animateFloatAsState(
        targetValue   = if (showSidebar) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label         = "sidebarAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.bg)
            .pointerInput(showSidebar) {
                // Only intercept gesture AFTER it's confirmed a drag (slop exceeded)
                // This allows child taps (clock, apps) to register normally
                if (showSidebar) {
                    // Sidebar open: only detect rightward swipe to close
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var totalX = 0f
                        var totalY = 0f
                        var drag = true
                        while (drag) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (change.pressed) {
                                val delta = change.position - change.previousPosition
                                totalX += delta.x
                                totalY += delta.y
                                // Only consume if clearly horizontal rightward drag
                                val absX = abs(totalX); val absY = abs(totalY)
                                if (absX > 20f && absX > absY && totalX > 0f) {
                                    change.consume()
                                }
                            } else {
                                drag = false
                                if (totalX > 80f && abs(totalX) > abs(totalY)) {
                                    showSidebar = false
                                }
                            }
                        }
                    }
                } else {
                    // Home: detect leftward swipe to open sidebar, up for apps, down for notifs
                    // Uses awaitEachGesture so child clicks pass through untouched
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var totalX = 0f
                        var totalY = 0f
                        var consumed = false
                        var drag = true
                        while (drag) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (change.pressed) {
                                val delta = change.position - change.previousPosition
                                val dx = delta.x
                                val dy = delta.y
                                totalX += dx; totalY += dy
                                val absX = abs(totalX); val absY = abs(totalY)
                                // Only start consuming after clear directional intent (slop = 20px)
                                if (!consumed && absX > 20f && absX > absY && totalX < 0f) {
                                    consumed = true
                                }
                                if (consumed) change.consume()
                            } else {
                                drag = false
                                val absX = abs(totalX); val absY = abs(totalY)
                                if (absX > absY) {
                                    // Horizontal swipe: left → open sidebar
                                    if (totalX < -80f) showSidebar = true
                                } else {
                                    // Vertical swipe: only swipe down → notifications
                                    // (swipe up no longer opens sidebar)
                                    if (totalY > 80f) {
                                        try {
                                            val sb = context.getSystemService("statusbar")
                                            sb?.javaClass?.getMethod("expandNotificationsPanel")?.invoke(sb)
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                        }
                    }
                }
            }
    ) {
        // ═══════════════════════════════════════════════════════
        // HOME SCREEN
        // ═══════════════════════════════════════════════════════
        HomeScreen(
            timeState   = timeState,
            battery     = battery,
            isCharging  = isCharging,
            pinnedApps  = pinnedApps,
            theme       = theme,
            appFontSize = appFontSize,
            showIcons   = showIcons,
            renamedMap  = renamedMap,
            btnLeftPkg  = btnLeftPkg,
            btnRightPkg = btnRightPkg,
            clockPkg    = clockPkg,
            context     = context,
            onLaunch       = { app -> if (!app.isBlocked) launchApp(context, app.packageName) },
            onLongPress    = { app -> longPressedApp = app },
            onSettings     = { showSettings = true },
            onLongPressClockRing   = { pickerSlot = PickerSlot.CLOCK },
            onLongPressBtnLeft     = { pickerSlot = PickerSlot.BTN_LEFT },
            onLongPressBtnRight    = { pickerSlot = PickerSlot.BTN_RIGHT },
            onReorder = { reorderedApps ->
                val newOrder = reorderedApps.map { it.packageName }.toMutableList()
                pinnedPkgs = newOrder
                // Save order in a comma-separated string to preserve sequence (Set loses order)
                prefs.edit()
                    .putStringSet(KEY_PINNED, newOrder.toSet())
                    .putString("pinned_order", newOrder.joinToString(","))
                    .apply()
            }
        )

        // ═══════════════════════════════════════════════════════
        // SIDEBAR OVERLAY — animated from right
        // ═══════════════════════════════════════════════════════
        if (showSidebar || sidebarAlpha > 0f) {
            // dim background
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f * sidebarAlpha))
                    .clickable(enabled = showSidebar) { showSidebar = false }
            )
            // sidebar panel slides in from right
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.78f)
                    .align(Alignment.CenterEnd)
                    .offset(x = sidebarOffsetX)
                    .background(Color(0xFF0D0D0D))
                    .clickable(enabled = false) {}  // consume clicks so background doesn't close
            ) {
                SidebarContent(
                    allApps    = allApps,
                    pinnedPkgs = pinnedPkgs,
                    hiddenPkgs = hiddenPkgs,
                    renamedMap = renamedMap,
                    theme      = theme,
                    fontSize   = appFontSize,
                    showIcons  = showIcons,
                    query      = query,
                    onQueryChange = { query = it },
                    onLaunch   = { app -> if (!app.isBlocked) { launchApp(context, app.packageName); showSidebar = false } },
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
                    onSettings = { showSettings = true; showSidebar = false },
                    onClose    = { showSidebar = false; query = "" }
                )
            }
        }

        // Long press menu for pinned apps
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
                onDismiss = { longPressedApp = null },
                extraActions = {
                    // Always show Remove from Home since these ARE pinned apps
                    ContextMenuRow(Icons.Default.PushPin, "Remove from Home") {
                        val u = pinnedPkgs.toMutableList(); u.remove(app.packageName); pinnedPkgs = u
                        prefs.edit().putStringSet(KEY_PINNED, u.toSet()).apply(); longPressedApp = null
                    }
                }
            )
        }

        // App picker for clock / bottom buttons
        if (pickerSlot != PickerSlot.NONE) {
            AppPickerDialog(
                allApps   = allApps,
                slotLabel = when (pickerSlot) {
                    PickerSlot.CLOCK     -> "Clock Ring Button"
                    PickerSlot.BTN_LEFT  -> "Left Button"
                    PickerSlot.BTN_RIGHT -> "Right Button"
                    else                 -> ""
                },
                currentPkg = when (pickerSlot) {
                    PickerSlot.CLOCK     -> clockPkg
                    PickerSlot.BTN_LEFT  -> btnLeftPkg
                    PickerSlot.BTN_RIGHT -> btnRightPkg
                    else                 -> ""
                },
                onPick = { pkg ->
                    when (pickerSlot) {
                        PickerSlot.CLOCK -> {
                            clockPkg = pkg
                            prefs.edit().putString(KEY_CLOCK_PKG, pkg).apply()
                        }
                        PickerSlot.BTN_LEFT -> {
                            btnLeftPkg = pkg
                            prefs.edit().putString(KEY_BTN_L, pkg).apply()
                        }
                        PickerSlot.BTN_RIGHT -> {
                            btnRightPkg = pkg
                            prefs.edit().putString(KEY_BTN_R, pkg).apply()
                        }
                        else -> {}
                    }
                    pickerSlot = PickerSlot.NONE
                },
                onDismiss = { pickerSlot = PickerSlot.NONE }
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
// HOME SCREEN
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
    btnLeftPkg:  String,
    btnRightPkg: String,
    clockPkg:    String,
    context:     Context,
    onLaunch:             (AppInfo) -> Unit,
    onLongPress:          (AppInfo) -> Unit,
    onSettings:           () -> Unit,
    onLongPressClockRing: () -> Unit,
    onLongPressBtnLeft:   () -> Unit,
    onLongPressBtnRight:  () -> Unit,
    onReorder:            (List<AppInfo>) -> Unit = {}
) {
    // Drag-reorder state
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragTargetIndex by remember { mutableStateOf<Int?>(null) }
    // Local mutable copy for live drag preview
    var localOrder by remember(pinnedApps) { mutableStateOf(pinnedApps.toMutableList()) }
    LaunchedEffect(pinnedApps) { localOrder = pinnedApps.toMutableList() }

    Box(
        Modifier
            .fillMaxSize()
            .background(BG)
            .navigationBarsPadding()
    ) {
        // ── Main scrollable content ──────────────────────────────────────
        val scrollState = rememberScrollState()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp)
                .padding(bottom = 96.dp)   // leave room for bottom buttons
        ) {
            Spacer(Modifier.height(48.dp))

            // ── Battery ring clock ────────────────────────────────────────
            ClockWithBatteryRing(
                time       = timeState.first,
                date       = timeState.second,
                battery    = battery,
                isCharging = isCharging,
                onLongPress = onLongPressClockRing,
                onTap = {
                    if (clockPkg.isNotBlank()) {
                        launchApp(context, clockPkg)
                    } else {
                        onLongPressClockRing()
                    }
                }
            )

            Spacer(Modifier.height(44.dp))

            // ── Pinned apps list — scrollable + drag reorder ──────────────
            if (localOrder.isEmpty()) {
                Text(
                    "Long press the ring or buttons below to assign apps",
                    color    = TXT.copy(alpha = 0.2f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                localOrder.forEachIndexed { index, app ->
                    val isDragging = draggingIndex == index
                    val isDragTarget = dragTargetIndex == index && draggingIndex != null && draggingIndex != index

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (isDragTarget)
                                    Modifier.drawBehind {
                                        drawRect(
                                            color = ACCENT.copy(alpha = 0.12f),
                                            size  = size
                                        )
                                    }
                                else Modifier
                            )
                            .combinedClickable(
                                onClick = {
                                    if (draggingIndex == null) onLaunch(app)
                                },
                                onLongClick = {
                                    // Long press: start drag mode
                                    draggingIndex = index
                                    dragTargetIndex = index
                                }
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Drag handle — only visible in drag mode
                        if (draggingIndex != null) {
                            Icon(
                                Icons.Default.DragHandle,
                                null,
                                tint = if (isDragging) ACCENT else TXT.copy(alpha = 0.3f),
                                modifier = Modifier
                                    .size(20.dp)
                                    .pointerInput(index) {
                                        detectDragGestures(
                                            onDragStart = { draggingIndex = index; dragTargetIndex = index },
                                            onDragEnd = {
                                                val from = draggingIndex
                                                val to   = dragTargetIndex
                                                if (from != null && to != null && from != to) {
                                                    val newList = localOrder.toMutableList()
                                                    val item = newList.removeAt(from)
                                                    newList.add(to, item)
                                                    localOrder = newList
                                                    onReorder(newList)
                                                }
                                                draggingIndex  = null
                                                dragTargetIndex = null
                                            },
                                            onDragCancel = {
                                                draggingIndex  = null
                                                dragTargetIndex = null
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                // Each ~52dp row = 52 * density px
                                                val rowHeightPx = 52.dp.toPx()
                                                val currentFrom = draggingIndex ?: return@detectDragGestures
                                                val currentTarget = dragTargetIndex ?: currentFrom
                                                // How many rows did we move?
                                                val steps = (dragAmount.y / rowHeightPx).let {
                                                    when {
                                                        it > 0.5f  ->  1
                                                        it < -0.5f -> -1
                                                        else       ->  0
                                                    }
                                                }
                                                if (steps != 0) {
                                                    val next = (currentTarget + steps).coerceIn(0, localOrder.size - 1)
                                                    dragTargetIndex = next
                                                }
                                            }
                                        )
                                    }
                            )
                            Spacer(Modifier.width(10.dp))
                        }

                        Text(
                            text       = renamedMap[app.packageName] ?: app.label,
                            color      = when {
                                isDragging -> ACCENT
                                app.isBlocked -> RED.copy(alpha = 0.7f)
                                else -> TXT
                            },
                            fontSize   = appFontSize,
                            fontWeight = FontWeight.Light,
                            modifier   = Modifier.weight(1f)
                        )

                        // Context menu button in drag mode (exit drag mode)
                        if (draggingIndex != null) {
                            if (isDragging) {
                                Icon(
                                    Icons.Default.Check, null,
                                    tint = ACCENT,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable {
                                            val from = draggingIndex
                                            val to   = dragTargetIndex
                                            if (from != null && to != null && from != to) {
                                                val newList = localOrder.toMutableList()
                                                val item = newList.removeAt(from)
                                                newList.add(to, item)
                                                localOrder = newList
                                                onReorder(newList)
                                            }
                                            draggingIndex  = null
                                            dragTargetIndex = null
                                        }
                                )
                            }
                        }
                    }

                    // Thin divider between items when dragging
                    if (draggingIndex != null && index < localOrder.size - 1) {
                        HorizontalDivider(color = DIVIDER.copy(alpha = 0.5f), thickness = 0.5.dp)
                    }
                }

                // Done button to exit drag mode
                if (draggingIndex != null) {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ACCENT.copy(alpha = 0.15f))
                            .clickable {
                                draggingIndex  = null
                                dragTargetIndex = null
                            }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Check, null, tint = ACCENT, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Done Reordering", color = ACCENT, fontSize = 14.sp)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        // ── Bottom two buttons ───────────────────────────────────────────
        BottomButtonBar(
            btnLeftPkg   = btnLeftPkg,
            btnRightPkg  = btnRightPkg,
            context      = context,
            modifier     = Modifier.align(Alignment.BottomCenter),
            onLongPressLeft  = onLongPressBtnLeft,
            onLongPressRight = onLongPressBtnRight
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Battery Ring Clock  — full circle, fills clockwise as battery increases
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ClockWithBatteryRing(
    time:        String,
    date:        String,
    battery:     Int,
    isCharging:  Boolean,
    onLongPress: () -> Unit,
    onTap:       () -> Unit
) {
    val chargingColor by animateColorAsState(
        targetValue   = if (isCharging) Color(0xFF00FFB2) else TXT,
        animationSpec = tween(600),
        label         = "chargeColor"
    )
    val pulseAnim = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulseAnim.animateFloat(
        initialValue  = 0.55f,
        targetValue   = 1.0f,
        animationSpec = infiniteRepeatable(
            animation  = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val batteryFrac  = (battery / 100f).coerceIn(0f, 1f)
    val ringColor    = if (isCharging) chargingColor else TXT
    val ringAlpha    = if (isCharging) pulseAlpha else 1f

    // Animate battery sweep for smooth change
    val animatedSweep by animateFloatAsState(
        targetValue   = 360f * batteryFrac,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label         = "batterySweep"
    )

    Box(
        Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(180.dp)
                .combinedClickable(
                    onClick     = { onTap() },
                    onLongClick = { onLongPress() }
                )
                .drawBehind {
                    val strokePx  = 2.8.dp.toPx()
                    val radius    = size.width / 2f - strokePx
                    val cx        = size.width / 2f
                    val cy        = size.height / 2f
                    val topLeft   = Offset(cx - radius, cy - radius)
                    val arcSize   = Size(radius * 2, radius * 2)

                    // Background ring — full circle, very dim
                    drawArc(
                        color      = Color.White.copy(alpha = 0.07f),
                        startAngle = -90f,      // start at 12 o'clock
                        sweepAngle = 360f,
                        useCenter  = false,
                        topLeft    = topLeft,
                        size       = arcSize,
                        style      = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )
                    // Battery progress — clockwise from 12 o'clock
                    if (animatedSweep > 0f) {
                        drawArc(
                            color      = ringColor.copy(alpha = ringAlpha),
                            startAngle = -90f,
                            sweepAngle = animatedSweep,
                            useCenter  = false,
                            topLeft    = topLeft,
                            size       = arcSize,
                            style      = Stroke(width = strokePx, cap = StrokeCap.Round)
                        )
                    }
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
                    fontSize   = 30.sp,
                    fontWeight = FontWeight.Light
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text     = date,
                    color    = TXT.copy(alpha = 0.5f),
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(2.dp))
                // Small battery % inside ring
                Text(
                    text     = "$battery%",
                    color    = (if (isCharging) chargingColor else DIM).copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Bottom Button Bar — phone/camera by default, long press to reassign
// Sits just above the Android navigation bar
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun BottomButtonBar(
    btnLeftPkg:      String,
    btnRightPkg:     String,
    context:         Context,
    modifier:        Modifier = Modifier,
    onLongPressLeft:  () -> Unit,
    onLongPressRight: () -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()           // stay above nav bar on all Android versions
            .padding(horizontal = 32.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        // Left button
        BottomIconButton(
            pkg         = btnLeftPkg,
            context     = context,
            onLongPress = onLongPressLeft
        )
        // Right button
        BottomIconButton(
            pkg         = btnRightPkg,
            context     = context,
            onLongPress = onLongPressRight
        )
    }
}

@Composable
fun BottomIconButton(
    pkg:        String,
    context:    Context,
    onLongPress: () -> Unit
) {
    val icon: ImageVector
    val contentDesc: String
    val onTap: () -> Unit

    when (pkg) {
        "PHONE" -> {
            icon = Icons.Default.Phone
            contentDesc = "Phone"
            onTap = { launchDialer(context) }
        }
        "CAMERA" -> {
            icon = Icons.Default.CameraAlt
            contentDesc = "Camera"
            onTap = { launchCamera(context) }
        }
        "" -> {
            // unassigned — show placeholder
            icon = Icons.Default.Add
            contentDesc = "Assign"
            onTap = {}
        }
        else -> {
            icon = Icons.Default.Apps
            contentDesc = pkg
            onTap = { launchApp(context, pkg) }
        }
    }

    // Show icon if it's a system action, otherwise try to show app icon
    if (pkg != "PHONE" && pkg != "CAMERA" && pkg.isNotBlank()) {
        // custom app assigned — show small label
        val appLabel = remember(pkg) {
            try { context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(pkg, 0)
            ).toString() } catch (_: Exception) { pkg.substringAfterLast('.') }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures(
                    onTap       = { onTap() },
                    onLongPress = { onLongPress() }
                )
            }.padding(8.dp)
        ) {
            Icon(icon, contentDesc, tint = TXT, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(3.dp))
            Text(appLabel, color = TXT.copy(alpha = 0.6f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        Icon(
            icon, contentDesc,
            tint     = TXT,
            modifier = Modifier
                .size(26.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap       = { onTap() },
                        onLongPress = { onLongPress() }
                    )
                }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SIDEBAR (previously AllAppsScreen) — slides in from right with animation
// Search bar at top, first letter filters instantly
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SidebarContent(
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
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Auto-focus search bar when sidebar opens
    LaunchedEffect(Unit) {
        delay(300) // wait for slide-in animation
        try { searchFocus.requestFocus(); keyboard?.show() } catch (_: Exception) {}
    }

    // Filter: if query has content, show matching apps; empty shows all
    val filtered = remember(query, allApps) {
        if (query.isBlank()) allApps
        else allApps.filter {
            val name = renamedMap[it.packageName] ?: it.label
            name.startsWith(query, ignoreCase = true) ||
            name.contains(query, ignoreCase = true)
        }.sortedByDescending { app ->
            // prioritize starts-with matches
            val name = renamedMap[app.packageName] ?: app.label
            if (name.startsWith(query, ignoreCase = true)) 1 else 0
        }
    }

    val letters = remember(filtered) {
        filtered.map { (renamedMap[it.packageName] ?: it.label).first().uppercaseChar() }.distinct().sorted()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .navigationBarsPadding()
            .statusBarsPadding()
    ) {
        // ── Header / close row ────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Apps", color = TXT, fontSize = 18.sp, fontWeight = FontWeight.Light)
            Row {
                Icon(Icons.Default.Settings, null, tint = DIM,
                    modifier = Modifier.size(20.dp).clickable { onSettings() })
                Spacer(Modifier.width(16.dp))
                Icon(Icons.Default.Close, null, tint = DIM,
                    modifier = Modifier.size(20.dp).clickable { onClose() })
            }
        }

        // ── Search bar — clearly visible, correct position ────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1A1A1A))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, null, tint = DIM, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value         = query,
                onValueChange = onQueryChange,
                singleLine    = true,
                textStyle     = TextStyle(color = TXT, fontSize = 16.sp),
                decorationBox = { inner ->
                    Box(Modifier.weight(1f)) {
                        if (query.isBlank()) Text("Search apps…", color = TXT.copy(alpha = 0.3f), fontSize = 16.sp)
                        inner()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(searchFocus)
            )
            if (query.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.Clear, null, tint = DIM,
                    modifier = Modifier.size(16.dp).clickable { onQueryChange("") })
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── App list + letter index ───────────────────────────────────────
        Box(Modifier.weight(1f)) {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(end = 20.dp)
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    val displayName = renamedMap[app.packageName] ?: app.label
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick     = { onLaunch(app) },
                                onLongClick = { contextApp = app }
                            )
                            .padding(horizontal = 16.dp, vertical = 13.dp),
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
                            text       = displayName,
                            color      = if (app.isBlocked) RED.copy(alpha = 0.7f) else TXT,
                            fontSize   = fontSize,
                            fontWeight = FontWeight.Light,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis,
                            modifier   = Modifier.weight(1f)
                        )
                    }
                }
            }

            // ── Right side letter index ───────────────────────────────────
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                letters.forEach { letter ->
                    Text(
                        letter.toString(),
                        color    = TXT.copy(alpha = 0.4f),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(vertical = 1.5.dp)
                    )
                }
            }
        }

        // Settings gear at bottom
        Spacer(Modifier.height(8.dp))
    }

    // Context menu overlay
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
                    Icons.Default.PushPin,
                    if (app.packageName in pinnedPkgs) "Remove from Home" else "Add to Home"
                ) { onPin(app.packageName); contextApp = null }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// App Context Menu — bigger text
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
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.82f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1C1C1C))
                .clickable(enabled = false) {}
                .padding(24.dp)                 // bigger padding
        ) {
            // App name — bigger
            Text(app.customName.ifBlank { app.label },
                color = TXT, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(app.packageName, color = TXT.copy(alpha = 0.3f), fontSize = 11.sp)
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = DIVIDER)
            Spacer(Modifier.height(10.dp))

            extraActions?.invoke()

            if (showRename) {
                Spacer(Modifier.height(8.dp))
                BasicTextField(
                    value         = renameText,
                    onValueChange = { renameText = it },
                    textStyle     = TextStyle(color = TXT, fontSize = 17.sp),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                            inner()
                            Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.BottomCenter).background(ACCENT))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showRename = false }) { Text("Cancel", color = DIM, fontSize = 15.sp) }
                    TextButton(onClick = { onRename(renameText) }) { Text("Save", color = ACCENT, fontSize = 15.sp) }
                }
            } else {
                ContextMenuRow(Icons.Default.Edit, "Rename") { showRename = true }
            }

            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel", color = DIM, textAlign = TextAlign.Center, fontSize = 15.sp)
            }
        }
    }
}

@Composable
fun ContextMenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 14.dp, horizontal = 6.dp),   // more vertical spacing
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = TXT.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = TXT.copy(alpha = 0.9f), fontSize = 16.sp)  // bigger text
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// App Picker Dialog — for clock ring / bottom buttons
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AppPickerDialog(
    allApps:    List<AppInfo>,
    slotLabel:  String,
    currentPkg: String,
    onPick:     (String) -> Unit,
    onDismiss:  () -> Unit
) {
    var search by remember { mutableStateOf("") }
    val filtered = remember(search, allApps) {
        if (search.isBlank()) allApps
        else allApps.filter { it.label.contains(search, ignoreCase = true) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF141414))
                .padding(18.dp)
        ) {
            Text("Assign: $slotLabel", color = TXT, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))

            // Search field
            BasicTextField(
                value         = search,
                onValueChange = { search = it },
                singleLine    = true,
                textStyle     = TextStyle(color = TXT, fontSize = 15.sp),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(CARD_BG)
                            .padding(12.dp)
                    ) {
                        if (search.isBlank()) Text("Search…", color = TXT.copy(alpha = 0.3f), fontSize = 15.sp)
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            // Built-in special options first
            if (search.isBlank()) {
                listOf("PHONE" to "📞 Phone Dialer", "CAMERA" to "📷 Camera").forEach { (pkg, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(pkg) }
                            .padding(vertical = 13.dp, horizontal = 4.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(label, color = TXT, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        if (pkg == currentPkg) Icon(Icons.Default.CheckCircle, null, tint = ACCENT, modifier = Modifier.size(20.dp))
                    }
                    HorizontalDivider(color = DIVIDER)
                }
            }

            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                items(filtered) { app ->
                    val selected = app.packageName == currentPkg
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(app.packageName) }
                            .padding(vertical = 13.dp, horizontal = 4.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(app.label, color = TXT, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        if (selected) Icon(Icons.Default.CheckCircle, null, tint = ACCENT, modifier = Modifier.size(20.dp))
                    }
                    HorizontalDivider(color = DIVIDER)
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Cancel", color = DIM, fontSize = 15.sp)
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

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
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
                    SettingsInfoRow("Swipe left  →  Open Apps sidebar")
                    SettingsInfoRow("Swipe right (in sidebar)  →  Close")
                    SettingsInfoRow("Swipe down  →  Notifications")
                    SettingsInfoRow("Long press clock ring  →  Assign app")
                    SettingsInfoRow("Long press bottom buttons  →  Assign app")
                    SettingsInfoRow("Long press app name  →  Options")
                }

                SettingsDivider()

                SettingsExpandableSection("More", expandMore, { expandMore = !expandMore }) {
                    SettingsClickRow("Back to RasFocus") { onBack(); onDismiss() }
                    SettingsClickRow("Set as default launcher") {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_HOME_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            )
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
    val ownPkg = context.packageName  // com.rasel.RasFocus

    val fromLauncher = pm.queryIntentActivities(intent, 0)
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

    // নিজের app (RasFocus+) যদি hidden না হয় কিন্তু LAUNCHER query তে না আসে,
    // তাহলে explicitly যোগ করো — launcher নিজেকে miss করলেও দেখা যাবে
    val hasOwnApp = fromLauncher.any { it.packageName == ownPkg }
    val ownAppEntry = if (!hasOwnApp && ownPkg !in hidden) {
        runCatching {
            val ai    = pm.getApplicationInfo(ownPkg, 0)
            val label = pm.getApplicationLabel(ai).toString()
            AppInfo(
                label        = label,
                packageName  = ownPkg,
                customName   = renamed[ownPkg] ?: "",
                isBlocked    = ownPkg in blocked,
                usageMinutes = usage[ownPkg] ?: 0L
            )
        }.getOrNull()
    } else null

    return (fromLauncher + listOfNotNull(ownAppEntry))
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
    return try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
    } catch (_: Exception) { 100 }
}

private fun isDeviceCharging(context: Context): Boolean {
    return try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.isCharging
    } catch (_: Exception) { false }
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

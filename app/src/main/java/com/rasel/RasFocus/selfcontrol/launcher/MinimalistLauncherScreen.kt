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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import androidx.activity.compose.BackHandler
import androidx.navigation.NavController
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

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

private const val KEY_AUTO_KB   = "sidebar_auto_keyboard"   // boolean — open keyboard on sidebar open

// ── Word Widget prefs ─────────────────────────────────────────────────────────
// User stores custom words in settings; launcher cycles them hourly
private const val KEY_CUSTOM_WORDS  = "word_widget_custom"   // JSON-ish: "word1|meaning1;word2|meaning2"
private const val KEY_WORD_IDX      = "word_widget_idx"       // current cycling index
private const val KEY_WORD_HOUR     = "word_widget_hour"      // last hour shown

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

// ─────────────────────────────────────────────────────────────────────────────
// Word Widget data
// ─────────────────────────────────────────────────────────────────────────────
data class WordPair(
    val english: String,
    val bangla:  String,     // meaning / mnemonic in Bangla
    val example: String = "" // optional English usage sentence
)

/** Built-in fallback word list shown when user hasn't added custom words */
private val DEFAULT_WORDS = listOf(
    WordPair("Ephemeral",   "ক্ষণস্থায়ী",           "The ephemeral beauty of cherry blossoms."),
    WordPair("Resilient",   "স্থিতিস্থাপক / দৃঢ়",    "She remained resilient despite the setbacks."),
    WordPair("Eloquent",    "বাগ্মী / সুবক্তা",      "His eloquent speech moved the crowd."),
    WordPair("Tenacious",   "অদম্য / একগুঁয়ে",       "A tenacious student never gives up."),
    WordPair("Pragmatic",   "ব্যবহারিক / বাস্তববাদী", "Take a pragmatic approach to problems."),
    WordPair("Meticulous",  "সুক্ষ্মদৃষ্টি / নিখুঁত", "She was meticulous in her research."),
    WordPair("Ambiguous",   "দ্ব্যর্থবোধক",           "The contract had ambiguous terms."),
    WordPair("Diligent",    "পরিশ্রমী",               "Diligent effort yields great results."),
    WordPair("Candid",      "খোলামেলা / সৎ",          "Please be candid with your feedback."),
    WordPair("Profound",    "গভীর / গভীরতর",          "A profound silence fell over the room."),
    WordPair("Verbose",     "বাচাল / অতিকথক",         "His verbose emails took an hour to read."),
    WordPair("Austere",     "কঠোর / সাদাসিধা",       "She lived an austere, minimalist life."),
    WordPair("Persevere",   "অধ্যবসায় করা",           "Persevere through difficult times."),
    WordPair("Succinct",    "সংক্ষিপ্ত ও স্পষ্ট",     "Keep your answers succinct and clear."),
    WordPair("Empathy",     "সহানুভূতি / অনুভূতি",   "Empathy makes you a better leader."),
    WordPair("Innovative",  "উদ্ভাবনী",               "An innovative solution saved the day."),
    WordPair("Intrinsic",   "স্বাভাবিক / সহজাত",      "Curiosity is intrinsic to learning."),
    WordPair("Versatile",   "বহুমুখী",                "A versatile developer adapts quickly."),
    WordPair("Fortitude",   "মনোবল / সাহস",           "He faced hardship with great fortitude."),
    WordPair("Prudent",     "বিচক্ষণ / সতর্ক",        "A prudent decision saves future pain.")
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
    var btnLeftPkg    by remember { mutableStateOf(prefs.getString(KEY_BTN_L, "PHONE") ?: "PHONE") }
    var btnRightPkg   by remember { mutableStateOf(prefs.getString(KEY_BTN_R, "CAMERA") ?: "CAMERA") }
    var clockPkg      by remember { mutableStateOf(prefs.getString(KEY_CLOCK_PKG, "") ?: "") }
    var autoKeyboard  by rememberSaveable { mutableStateOf(prefs.getBoolean(KEY_AUTO_KB, false)) }

    val theme       = LauncherTheme.entries[themeIdx.coerceIn(0, 2)]
    val appFontSize = when (fontSize) { 0 -> 16.sp; 2 -> 26.sp; else -> 21.sp }

    val usageMap: Map<String, Long> = remember { getUsageMap(context) }
    val allApps: List<AppInfo> = remember(hiddenPkgs, renamedMap) {
        getInstalledApps(context, hiddenPkgs, renamedMap, getBlockedApps(context), usageMap)
    }
    val pinnedApps = remember(pinnedPkgs, allApps) {
        pinnedPkgs.mapNotNull { pkg -> allApps.find { it.packageName == pkg } }
    }
    // আজকের total screen time (minutes) — usage ring এ ব্যবহার হবে
    val totalScreenMinutes: Long = remember(usageMap) { usageMap.values.sum() }
    // Sidebar এ suggest করার জন্য top-used apps (pinned ছাড়া, top 4)
    val frequentApps: List<AppInfo> = remember(usageMap, allApps, pinnedPkgs) {
        allApps
            .filter { it.packageName !in pinnedPkgs && usageMap.getOrDefault(it.packageName, 0L) > 0L }
            .sortedByDescending { usageMap.getOrDefault(it.packageName, 0L) }
            .take(4)
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

    // ── Live-drag sidebar: Animatable offset controls position in real time ──
    // sidebarWidthPx ≈ 78% of screen width (set during layout via BoxWithConstraints)
    // offsetX: 0f = fully open (left edge at right side start), sidebarWidthPx = hidden
    val density = LocalDensity.current
    // We use a large initial value; it gets clamped properly once we know screen width
    val sidebarOffsetAnim = remember { Animatable(10000f) }  // starts offscreen
    val scope = rememberCoroutineScope()

    val SIDEBAR_WIDTH_FRACTION  = 0.78f
    val OPEN_THRESHOLD_FRACTION = 0.35f
    val SWIPE_SLOP_PX           = 10f    // px before direction lock
    // Fast swipe threshold — px/ms: above this velocity always snap regardless of distance
    val FAST_SWIPE_VELOCITY_PX_MS = 0.5f  // ~500 px/s

    val gestureActiveRef = remember { androidx.compose.runtime.mutableStateOf(false) }

    LaunchedEffect(showSidebar) {
        if (gestureActiveRef.value) return@LaunchedEffect
        keyboard?.hide()
        if (showSidebar) {
            sidebarOffsetAnim.animateTo(
                0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness    = Spring.StiffnessMedium
                )
            )
        } else {
            val target = sidebarOffsetAnim.upperBound ?: 10000f
            sidebarOffsetAnim.animateTo(
                target,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness    = Spring.StiffnessMediumLow
                )
            )
        }
    }

    // Derive dim alpha directly from offset: 0 offset = alpha 0.5, far offset = 0
    val dimAlpha = if ((sidebarOffsetAnim.upperBound ?: 10000f) > 0f) {
        (1f - (sidebarOffsetAnim.value / (sidebarOffsetAnim.upperBound ?: 10000f))).coerceIn(0f, 1f) * 0.5f
    } else 0f

    val isSidebarVisible = sidebarOffsetAnim.value < (sidebarOffsetAnim.upperBound ?: 10000f) - 1f

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.bg)
    ) {
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val sidebarWidthPx = screenWidthPx * SIDEBAR_WIDTH_FRACTION
        val openThresholdPx = sidebarWidthPx * (1f - OPEN_THRESHOLD_FRACTION)  // offset BELOW this = snap open

        // Initialize bounds once screen width is known
        LaunchedEffect(sidebarWidthPx) {
            val wasOpen = showSidebar
            sidebarOffsetAnim.updateBounds(lowerBound = 0f, upperBound = sidebarWidthPx)
            if (!wasOpen) sidebarOffsetAnim.snapTo(sidebarWidthPx)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var totalX           = 0f
                        var totalY           = 0f
                        var directionLocked  = false
                        var isHorizontal     = false
                        var gestureConsuming = false
                        val gestureStartTime = System.currentTimeMillis()

                        // Snapshot showSidebar at gesture start — don't read live state mid-drag
                        val sidebarWasOpen = showSidebar

                        while (true) {
                            val event  = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break

                            if (!change.pressed) {
                                // ── Finger up: velocity + distance based snap ─────────────
                                gestureActiveRef.value = false
                                if (gestureConsuming && isHorizontal) {
                                    val cur           = sidebarOffsetAnim.value
                                    val elapsedMs     = (System.currentTimeMillis() - gestureStartTime).coerceAtLeast(1L)
                                    val velocityPxMs  = abs(totalX) / elapsedMs.toFloat()
                                    val isFastSwipe   = velocityPxMs >= FAST_SWIPE_VELOCITY_PX_MS
                                    // willOpen: fast left swipe OR dragged past threshold
                                    val willOpen = when {
                                        isFastSwipe && totalX < 0 -> true   // fast left → open
                                        isFastSwipe && totalX > 0 -> false  // fast right → close
                                        else -> cur < openThresholdPx
                                    }
                                    scope.launch {
                                        sidebarOffsetAnim.animateTo(
                                            if (willOpen) 0f else sidebarWidthPx,
                                            spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow)
                                        )
                                    }
                                    if (willOpen  && !showSidebar) showSidebar = true
                                    if (!willOpen &&  showSidebar) { showSidebar = false; query = "" }
                                } else if (!gestureConsuming && !sidebarWasOpen && totalY > 80f && abs(totalX) < 40f) {
                                    try {
                                        val sb = context.getSystemService("statusbar")
                                        sb?.javaClass?.getMethod("expandNotificationsPanel")?.invoke(sb)
                                    } catch (_: Exception) {}
                                }
                                break
                            }

                            val dx = change.positionChange().x
                            val dy = change.positionChange().y
                            totalX += dx
                            totalY += dy

                            // ── Lock direction once slop is exceeded ──────────────────────
                            if (!directionLocked) {
                                val absX = abs(totalX)
                                val absY = abs(totalY)
                                if (absX > SWIPE_SLOP_PX || absY > SWIPE_SLOP_PX) {
                                    directionLocked = true
                                    isHorizontal    = absX >= absY * 0.75f

                                    if (isHorizontal) {
                                        val validOpen  = !sidebarWasOpen && totalX < -SWIPE_SLOP_PX
                                        val validClose =  sidebarWasOpen && totalX >  SWIPE_SLOP_PX
                                        gestureConsuming = validOpen || validClose
                                        if (gestureConsuming) gestureActiveRef.value = true
                                    }
                                }
                            }

                            if (gestureConsuming && isHorizontal) {
                                change.consume()
                                val newOffset = (sidebarOffsetAnim.value + dx)
                                    .coerceIn(0f, sidebarWidthPx)
                                scope.launch { sidebarOffsetAnim.snapTo(newOffset) }
                            }
                        }
                    }
                }
        ) {
        // ═══════════════════════════════════════════════════════
        // HOME SCREEN
        // ═══════════════════════════════════════════════════════
        HomeScreen(
            timeState          = timeState,
            battery            = battery,
            isCharging         = isCharging,
            pinnedApps         = pinnedApps,
            theme              = theme,
            appFontSize        = appFontSize,
            showIcons          = showIcons,
            renamedMap         = renamedMap,
            btnLeftPkg         = btnLeftPkg,
            btnRightPkg        = btnRightPkg,
            clockPkg           = clockPkg,
            context            = context,
            totalScreenMinutes = totalScreenMinutes,
            onLaunch       = { app -> if (!app.isBlocked) launchApp(context, app.packageName) },
            onLongPress    = { app -> longPressedApp = app },
            onSettings     = { showSettings = true },
            onLongPressClockRing   = { pickerSlot = PickerSlot.CLOCK },
            onLongPressBtnLeft     = { pickerSlot = PickerSlot.BTN_LEFT },
            onLongPressBtnRight    = { pickerSlot = PickerSlot.BTN_RIGHT },
            onReorder = { reorderedApps ->
                val newOrder = reorderedApps.map { it.packageName }.toMutableList()
                pinnedPkgs = newOrder
                prefs.edit()
                    .putStringSet(KEY_PINNED, newOrder.toSet())
                    .putString("pinned_order", newOrder.joinToString(","))
                    .apply()
            }
        )

        // ═══════════════════════════════════════════════════════
        // SIDEBAR OVERLAY — live drag follows finger exactly
        // ═══════════════════════════════════════════════════════
        if (isSidebarVisible) {
            // Dim background — alpha follows drag progress in real time
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = dimAlpha))
                    .clickable(enabled = showSidebar) {
                        scope.launch {
                            showSidebar = false
                            query = ""
                            sidebarOffsetAnim.animateTo(
                                sidebarWidthPx,
                                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
                            )
                        }
                    }
            )
            // Sidebar panel — offset follows finger
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(SIDEBAR_WIDTH_FRACTION)
                    .align(Alignment.CenterEnd)
                    .offset { IntOffset(sidebarOffsetAnim.value.roundToInt(), 0) }
                    .background(Color(0xFF0D0D0D))
                    .clickable(enabled = false) {}
            ) {
                SidebarContent(
                    allApps       = allApps,
                    frequentApps  = frequentApps,
                    pinnedPkgs    = pinnedPkgs,
                    hiddenPkgs    = hiddenPkgs,
                    renamedMap    = renamedMap,
                    theme         = theme,
                    fontSize      = appFontSize,
                    showIcons     = showIcons,
                    autoKeyboard  = autoKeyboard,
                    query         = query,
                    onQueryChange = { query = it },
                    onLaunch   = { app -> if (!app.isBlocked) {
                        launchApp(context, app.packageName)
                        showSidebar = false
                        query = ""
                    }},
                    onPin      = { pkg ->
                        val u = pinnedPkgs.toMutableList()
                        if (pkg in u) u.remove(pkg) else u.add(pkg)
                        pinnedPkgs = u
                        prefs.edit().putStringSet(KEY_PINNED, u.toSet()).putString("pinned_order", u.joinToString(",")).apply()
                    },
                    onHide     = { pkg ->
                        val u = hiddenPkgs.toMutableSet(); u.add(pkg); hiddenPkgs = u
                        prefs.edit().putStringSet(KEY_HIDDEN, u).apply()
                    },
                    onUnhide   = { pkg ->
                        val u = hiddenPkgs.toMutableSet(); u.remove(pkg); hiddenPkgs = u
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
                    prefs.edit().putStringSet(KEY_PINNED, u.toSet()).putString("pinned_order", u.joinToString(",")).apply(); longPressedApp = null
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
                        prefs.edit().putStringSet(KEY_PINNED, u.toSet()).putString("pinned_order", u.joinToString(",")).apply(); longPressedApp = null
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
                themeIdx     = themeIdx,
                fontSize     = fontSize,
                showIcons    = showIcons,
                autoKeyboard = autoKeyboard,
                hiddenPkgs   = hiddenPkgs,
                allApps      = allApps,
                theme        = theme,
                onThemeChange   = { themeIdx = it; prefs.edit().putInt(KEY_THEME, it).apply() },
                onFontChange    = { fontSize = it; prefs.edit().putInt(KEY_FONT, it).apply() },
                onIconToggle    = { showIcons = it; prefs.edit().putBoolean(KEY_ICONS, it).apply() },
                onAutoKbToggle  = { autoKeyboard = it; prefs.edit().putBoolean(KEY_AUTO_KB, it).apply() },
                onUnhide        = { pkg ->
                    val u = hiddenPkgs.toMutableSet(); u.remove(pkg); hiddenPkgs = u
                    prefs.edit().putStringSet(KEY_HIDDEN, u).apply()
                },
                onBack    = { navController?.popBackStack() },
                onDismiss = { showSettings = false }
            )
        }
        }   // end inner Box (gesture layer)
    }       // end BoxWithConstraints
}

// ─────────────────────────────────────────────────────────────────────────────
// HOME SCREEN
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun HomeScreen(
    timeState:          Pair<String, String>,
    battery:            Int,
    isCharging:         Boolean,
    pinnedApps:         List<AppInfo>,
    theme:              LauncherTheme,
    appFontSize:        androidx.compose.ui.unit.TextUnit,
    showIcons:          Boolean,
    renamedMap:         Map<String, String>,
    btnLeftPkg:         String,
    btnRightPkg:        String,
    clockPkg:           String,
    context:            Context,
    totalScreenMinutes: Long = 0L,
    onLaunch:             (AppInfo) -> Unit,
    onLongPress:          (AppInfo) -> Unit,
    onSettings:           () -> Unit,
    onLongPressClockRing: () -> Unit,
    onLongPressBtnLeft:   () -> Unit,
    onLongPressBtnRight:  () -> Unit,
    onReorder:            (List<AppInfo>) -> Unit = {}
) {
    // Reorder state — long press একটা app → reorder mode চালু,
    // তারপর যেকোনো app এ tap → সেই position এ move, Done tap → save
    var reorderMode       by remember { mutableStateOf(false) }
    var selectedIndex     by remember { mutableStateOf<Int?>(null) }   // যে app টা move করতে চাই
    var localOrder by remember(pinnedApps) { mutableStateOf(pinnedApps.toMutableList()) }
    LaunchedEffect(pinnedApps) {
        if (!reorderMode) localOrder = pinnedApps.toMutableList()
    }

    // Back press → reorder mode cancel
    BackHandler(enabled = reorderMode) {
        reorderMode   = false
        selectedIndex = null
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(BG)
            .navigationBarsPadding()
    ) {
        // ═══════════════════════════════════════════════════════════════
        // Two-column layout: LEFT = clock ring | RIGHT = word widget
        // Both sides scroll together inside a single LazyColumn so that
        // the pinned apps list continues below the split header.
        // ═══════════════════════════════════════════════════════════════
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 80.dp),   // leave room for bottom bar
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 48.dp, bottom = 8.dp
            )
        ) {
            // ── HEADER ROW: clock (left) + word widget (right) ──────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    // LEFT column — clock ring (40% width)
                    Box(
                        modifier = Modifier.weight(0.42f),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        ClockWithBatteryRing(
                            time               = timeState.first,
                            date               = timeState.second,
                            battery            = battery,
                            isCharging         = isCharging,
                            totalScreenMinutes = totalScreenMinutes,
                            onLongPress = onLongPressClockRing,
                            onTap = {
                                if (clockPkg.isNotBlank()) launchApp(context, clockPkg)
                                else onLongPressClockRing()
                            }
                        )
                    }

                    Spacer(Modifier.width(10.dp))

                    // RIGHT column — 5-word widget (58% width)
                    Column(
                        modifier = Modifier.weight(0.58f)
                    ) {
                        HourlyWordWidget(context = context)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // ── PINNED APPS LIST ─────────────────────────────────────────
            if (localOrder.isEmpty()) {
                item {
                    Text(
                        "Long press buttons below to assign apps",
                        color    = TXT.copy(alpha = 0.2f),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                }
            } else {
            if (localOrder.isEmpty()) {
                item {
                    Text(
                        "Long press the ring or buttons below to assign apps",
                        color    = TXT.copy(alpha = 0.2f),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            } else {
                itemsIndexed(localOrder, key = { _, app -> app.packageName }) { index, app ->
                    val isSelected   = reorderMode && selectedIndex == index
                    val isTargetSlot = reorderMode && selectedIndex != null && selectedIndex != index

                    var homeAppPressed by remember { mutableStateOf(false) }
                    val homeAppBgAlpha by animateFloatAsState(
                        targetValue   = if (homeAppPressed) 0.13f else 0f,
                        animationSpec = tween(80),
                        label         = "homeAppBg"
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                when {
                                    isSelected   -> Modifier.drawBehind { drawRect(ACCENT.copy(alpha = 0.18f)) }
                                    isTargetSlot -> Modifier.drawBehind { drawRect(Color.White.copy(alpha = 0.05f)) }
                                    homeAppPressed -> Modifier.drawBehind { drawRect(Color.White.copy(alpha = homeAppBgAlpha)) }
                                    else         -> Modifier
                                }
                            )
                            .combinedClickable(
                                onClick = {
                                    when {
                                        // reorder mode — selected app আছে → move করো
                                        reorderMode && selectedIndex != null && selectedIndex != index -> {
                                            val nl   = localOrder.toMutableList()
                                            val from = selectedIndex!!
                                            nl.add(index, nl.removeAt(from))
                                            localOrder = nl
                                            onReorder(nl)
                                            selectedIndex = null          // deselect — আরেকটা move করা যাবে
                                        }
                                        // reorder mode — এই app ই selected → deselect
                                        reorderMode && selectedIndex == index -> {
                                            selectedIndex = null
                                        }
                                        // reorder mode — কোনো selection নেই → এটা select করো
                                        reorderMode -> {
                                            selectedIndex = index
                                        }
                                        // normal mode — launch
                                        else -> onLaunch(app)
                                    }
                                },
                                onLongClick = {
                                    // long press → reorder mode চালু + এই app selected
                                    reorderMode   = true
                                    selectedIndex = index
                                }
                            )
                            .pointerInput(reorderMode) {
                                if (!reorderMode) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        homeAppPressed = true
                                        waitForUpOrCancellation()
                                        homeAppPressed = false
                                    }
                                }
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // reorder mode এ left side indicator
                        if (reorderMode) {
                            Icon(
                                if (isSelected) Icons.Default.DragHandle else Icons.Default.UnfoldMore,
                                null,
                                tint     = if (isSelected) ACCENT else TXT.copy(alpha = 0.25f),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                        }

                        Text(
                            text       = renamedMap[app.packageName] ?: app.label,
                            color      = when {
                                isSelected    -> ACCENT
                                app.isBlocked -> RED.copy(alpha = 0.7f)
                                else          -> TXT
                            },
                            fontSize   = appFontSize,
                            fontWeight = if (isSelected) FontWeight.Normal else FontWeight.Light,
                            modifier   = Modifier.weight(1f)
                        )

                        // selected app এ ডান দিকে arrow hints
                        if (reorderMode && selectedIndex == null) {
                            Text("tap to move here", color = TXT.copy(alpha = 0.2f), fontSize = 11.sp)
                        }
                        if (isSelected) {
                            Spacer(Modifier.width(8.dp))
                            Text("tap a slot →", color = ACCENT.copy(alpha = 0.7f), fontSize = 11.sp)
                        }
                    }

                    if (reorderMode && index < localOrder.size - 1) {
                        HorizontalDivider(color = DIVIDER.copy(alpha = 0.3f), thickness = 0.5.dp)
                    }
                }

                // Done button — reorder mode এ দেখা যাবে
                if (reorderMode) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(ACCENT.copy(alpha = 0.15f))
                                .clickable {
                                    reorderMode   = false
                                    selectedIndex = null
                                }
                                .padding(vertical = 14.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Check, null, tint = ACCENT, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Done", color = ACCENT, fontSize = 14.sp)
                        }
                    }
                }
            }
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
    time:               String,
    date:               String,
    battery:            Int,
    isCharging:         Boolean,
    totalScreenMinutes: Long = 0L,
    onLongPress:        () -> Unit,
    onTap:              () -> Unit
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

    // ── Charging sweep rotation — arc rotates 360° when charging ──────────────
    val rotateSweepAnim = rememberInfiniteTransition(label = "chargingRotate")
    val chargingRotation by rotateSweepAnim.animateFloat(
        initialValue  = 0f,
        targetValue   = 360f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "chargingRotation"
    )

    // ── Ambient breathing — clock slowly scales 1.0 → 1.018 → 1.0 every ~4s ──
    val breathAnim = rememberInfiniteTransition(label = "breath")
    val breathScale by breathAnim.animateFloat(
        initialValue  = 1.000f,
        targetValue   = 1.018f,
        animationSpec = infiniteRepeatable(
            animation  = tween(3800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathScale"
    )

    // Usage arc — max = 6 hours (360 min) daily goal
    val usageGoalMin   = 360L   // 6 hours
    val usageFrac      = (totalScreenMinutes / usageGoalMin.toFloat()).coerceIn(0f, 1f)
    val usageArcColor  = when {
        usageFrac < 0.5f -> Color(0xFF00C896)   // green — under 3h
        usageFrac < 0.8f -> Color(0xFFFFC947)   // amber — 3-5h
        else             -> Color(0xFFFF5252)   // red   — 5h+
    }
    val animatedUsageSweep by animateFloatAsState(
        targetValue   = 360f * usageFrac,
        animationSpec = tween(1000, easing = FastOutSlowInEasing),
        label         = "usageSweep"
    )
    // Screen time human-readable label
    val screenTimeLabel = if (totalScreenMinutes <= 0L) "" else {
        val h = totalScreenMinutes / 60
        val m = totalScreenMinutes % 60
        if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    Box(
        Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        // ── Outer usage ring (larger box, draws behind) ───────────────────
        Box(
            Modifier
                .size(200.dp)
                .drawBehind {
                    val outerStroke = 2.dp.toPx()
                    val r2       = size.width / 2f - outerStroke
                    val cx2      = size.width / 2f
                    val cy2      = size.height / 2f
                    val tl2      = Offset(cx2 - r2, cy2 - r2)
                    val as2      = Size(r2 * 2, r2 * 2)
                    // dim track
                    drawArc(
                        color = Color.White.copy(alpha = 0.04f),
                        startAngle = -90f, sweepAngle = 360f, useCenter = false,
                        topLeft = tl2, size = as2,
                        style = Stroke(width = outerStroke, cap = StrokeCap.Round)
                    )
                    // usage progress
                    if (animatedUsageSweep > 0f) {
                        drawArc(
                            color = usageArcColor.copy(alpha = 0.75f),
                            startAngle = -90f, sweepAngle = animatedUsageSweep, useCenter = false,
                            topLeft = tl2, size = as2,
                            style = Stroke(width = outerStroke, cap = StrokeCap.Round)
                        )
                    }
                }
        )

        // ── Inner battery ring + clock ────────────────────────────────────
        Box(
            Modifier
                .size(180.dp)
                .graphicsLayer { scaleX = breathScale; scaleY = breathScale }
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
                        startAngle = -90f,
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
                    // Charging: extra bright rotating arc (60°) that sweeps around ring
                    if (isCharging) {
                        drawArc(
                            color      = Color(0xFF00FFB2).copy(alpha = pulseAlpha * 0.85f),
                            startAngle = chargingRotation - 90f,
                            sweepAngle = 60f,
                            useCenter  = false,
                            topLeft    = topLeft,
                            size       = arcSize,
                            style      = Stroke(width = strokePx * 1.8f, cap = StrokeCap.Round)
                        )
                    }
                }
        ) {
            // Only show battery % and charging icon — no time/date text
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement   = Arrangement.Center,
                horizontalAlignment   = Alignment.CenterHorizontally
            ) {
                if (isCharging) {
                    // Charging icon — pulses with color
                    Icon(
                        Icons.Default.BatteryChargingFull,
                        contentDescription = "Charging",
                        tint     = chargingColor.copy(alpha = pulseAlpha),
                        modifier = Modifier
                            .size(32.dp)
                            .graphicsLayer { scaleX = 0.9f + pulseAlpha * 0.1f; scaleY = 0.9f + pulseAlpha * 0.1f }
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    text     = "$battery%",
                    color    = (if (isCharging) chargingColor else DIM).copy(alpha = if (isCharging) pulseAlpha else 0.7f),
                    fontSize = if (isCharging) 14.sp else 11.sp,
                    fontWeight = if (isCharging) FontWeight.Medium else FontWeight.Light
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

    var btnPressed by remember { mutableStateOf(false) }
    val btnScale by animateFloatAsState(
        targetValue = if (btnPressed) 0.80f else 1f,
        animationSpec = tween(120),
        label = "btnScale"
    )

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
            modifier = Modifier
                .graphicsLayer { scaleX = btnScale; scaleY = btnScale }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            btnPressed = true
                            tryAwaitRelease()
                            btnPressed = false
                        },
                        onTap       = { onTap() },
                        onLongPress = { onLongPress() }
                    )
                }
                .padding(8.dp)
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
                .size(36.dp)
                .graphicsLayer { scaleX = btnScale; scaleY = btnScale }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            btnPressed = true
                            tryAwaitRelease()
                            btnPressed = false
                        },
                        onTap       = { onTap() },
                        onLongPress = { onLongPress() }
                    )
                }
                .padding(5.dp)
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
    frequentApps:  List<AppInfo> = emptyList(),
    pinnedPkgs:    List<String>,
    hiddenPkgs:    Set<String>,
    renamedMap:    Map<String, String>,
    theme:         LauncherTheme,
    fontSize:      androidx.compose.ui.unit.TextUnit,
    showIcons:     Boolean,
    autoKeyboard:  Boolean = false,
    query:         String,
    onQueryChange: (String) -> Unit,
    onLaunch:      (AppInfo) -> Unit,
    onPin:         (String) -> Unit,
    onHide:        (String) -> Unit,
    onUnhide:      (String) -> Unit = {},
    onRename:      (String, String) -> Unit,
    onSettings:    () -> Unit,
    onClose:       () -> Unit
) {
    val context    = LocalContext.current
    var contextApp by remember { mutableStateOf<AppInfo?>(null) }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var showHidden by remember { mutableStateOf(false) }

    // Hidden apps list for inline panel
    val hiddenAppsList = remember(hiddenPkgs, allApps) {
        hiddenPkgs.mapNotNull { pkg -> allApps.find { it.packageName == pkg }
            ?: run {
                // app might not be in allApps (already filtered out); create minimal entry
                try {
                    val pm = context.packageManager
                    val ai = pm.getApplicationInfo(pkg, 0)
                    AppInfo(label = pm.getApplicationLabel(ai).toString(), packageName = pkg)
                } catch (_: Exception) { null }
            }
        }
    }

    // Auto-focus search bar only when setting is enabled
    LaunchedEffect(Unit) {
        if (!autoKeyboard) return@LaunchedEffect
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

    // Glassmorphism sidebar — dark base + subtle gradient border on left edge
    Box(Modifier.fillMaxSize()) {
        // Left-edge gradient border
        Box(
            Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0xFF14C3B2).copy(alpha = 0.25f),
                            Color(0xFF14C3B2).copy(alpha = 0.10f),
                            Color.Transparent
                        )
                    )
                )
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F0F0F),
                        Color(0xFF0A0A0A)
                    )
                )
            )
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Hidden apps toggle button
                if (hiddenPkgs.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (showHidden) ACCENT.copy(alpha = 0.18f) else Color.Transparent)
                            .clickable { showHidden = !showHidden }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (showHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Hidden apps",
                                tint = if (showHidden) ACCENT else DIM,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "${hiddenPkgs.size}",
                                color = if (showHidden) ACCENT else DIM,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Icon(Icons.Default.Settings, null, tint = DIM,
                    modifier = Modifier.size(20.dp).clickable { onSettings() })
                Spacer(Modifier.width(16.dp))
                Icon(Icons.Default.Close, null, tint = DIM,
                    modifier = Modifier.size(20.dp).clickable { onClose() })
            }
        }

        // ── Hidden apps panel — slides in when toggle is on ───────────────
        AnimatedVisibility(
            visible = showHidden && hiddenAppsList.isNotEmpty(),
            enter   = expandVertically(animationSpec = tween(220)) + fadeIn(tween(180)),
            exit    = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(140))
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF141414))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    "Hidden Apps",
                    color = ACCENT.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    letterSpacing = 0.8.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                hiddenAppsList.forEach { app ->
                    var hiddenRowPressed by remember { mutableStateOf(false) }
                    val hiddenBgAlpha by animateFloatAsState(
                        targetValue = if (hiddenRowPressed) 0.10f else 0f,
                        animationSpec = tween(80),
                        label = "hiddenBg"
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawBehind { drawRect(Color.White.copy(alpha = hiddenBgAlpha)) }
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    hiddenRowPressed = true
                                    waitForUpOrCancellation()
                                    hiddenRowPressed = false
                                }
                            }
                            .clickable { onUnhide(app.packageName) }
                            .padding(vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = app.customName.ifBlank { app.label },
                            color = TXT.copy(alpha = 0.75f),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "Unhide",
                            color = ACCENT.copy(alpha = 0.8f),
                            fontSize = 12.sp
                        )
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.04f), thickness = 0.5.dp)
                }
            }
            HorizontalDivider(color = Color(0xFF1E1E1E), thickness = 0.5.dp)
        }

        // ── Search bar ────────────────────────────────────────────────────
        val searchInteraction = remember { MutableInteractionSource() }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1A1A1A))
                .clickable(interactionSource = searchInteraction, indication = null) {
                    // Row ক্লিক করলে সরাসরি focus দাও + keyboard দেখাও
                    try { searchFocus.requestFocus(); keyboard?.show() } catch (_: Exception) {}
                }
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
                interactionSource = searchInteraction,
                decorationBox = { inner ->
                    Box(Modifier.weight(1f)) {
                        if (query.isBlank()) Text("Search apps…", color = TXT.copy(alpha = 0.3f), fontSize = 16.sp)
                        inner()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(searchFocus)
                    .onFocusChanged { if (it.isFocused) keyboard?.show() }
            )
            if (query.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.Clear, null, tint = DIM,
                    modifier = Modifier.size(16.dp).clickable { onQueryChange("") })
            }
        }

        // ── Frequent / suggested apps — search নেই এবং usage data আছে তখন ─
        if (query.isBlank() && frequentApps.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "  Frequent",
                color    = DIM,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(6.dp))
            LazyRow(
                contentPadding      = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(frequentApps, key = { it.packageName }) { app ->
                    var chipPressed by remember { mutableStateOf(false) }
                    val chipScale by animateFloatAsState(
                        targetValue   = if (chipPressed) 0.90f else 1f,
                        animationSpec = tween(100),
                        label         = "chipScale"
                    )
                    val chipLabel = renamedMap[app.packageName] ?: app.label
                    Box(
                        Modifier
                            .graphicsLayer { scaleX = chipScale; scaleY = chipScale }
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF1C1C1C))
                            .border(
                                width = 0.5.dp,
                                color = Color(0xFF14C3B2).copy(alpha = 0.18f),
                                shape = RoundedCornerShape(20.dp)
                            )
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        chipPressed = true
                                        tryAwaitRelease()
                                        chipPressed = false
                                    },
                                    onTap = { onLaunch(app) }
                                )
                            }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text     = chipLabel,
                            color    = TXT.copy(alpha = 0.85f),
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.05f), thickness = 0.5.dp)
        }

        Spacer(Modifier.height(8.dp))

        // ── App list + letter index ───────────────────────────────────────
        Box(Modifier.weight(1f)) {
            val sidebarListState = rememberLazyListState()
            LazyColumn(
                state = sidebarListState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 20.dp)
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    val displayName = renamedMap[app.packageName] ?: app.label
                    var appPressed by remember { mutableStateOf(false) }
                    val appBgAlpha by animateFloatAsState(
                        targetValue = if (appPressed) 0.10f else 0f,
                        animationSpec = tween(80),
                        label = "appBg"
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawBehind { if (appPressed) drawRect(Color.White.copy(alpha = appBgAlpha)) }
                            .combinedClickable(
                                onClick = { onLaunch(app) },
                                onLongClick = { contextApp = app }
                            )
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    appPressed = true
                                    waitForUpOrCancellation()
                                    appPressed = false
                                }
                            }
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
    themeIdx:      Int,
    fontSize:      Int,
    showIcons:     Boolean,
    autoKeyboard:  Boolean,
    hiddenPkgs:    Set<String>,
    allApps:       List<AppInfo>,
    theme:         LauncherTheme,
    onThemeChange:    (Int) -> Unit,
    onFontChange:     (Int) -> Unit,
    onIconToggle:     (Boolean) -> Unit,
    onAutoKbToggle:   (Boolean) -> Unit,
    onUnhide:         (String) -> Unit,
    onBack:           () -> Unit,
    onDismiss:        () -> Unit
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
                    SettingsToggleRow(
                        title    = "Sidebar — auto keyboard",
                        subtitle = "Sidebar খুললে search keyboard automatically open হবে",
                        checked  = autoKeyboard,
                        onToggle = onAutoKbToggle
                    )
                }

                // ── Hidden apps — সবসময় দেখা যাবে, expand ছাড়াই ─────────────
                if (hiddenApps.isNotEmpty()) {
                    SettingsDivider()
                    SettingsSectionLabel("Hidden apps (${hiddenApps.size})  •  tap to unhide")
                    hiddenApps.forEach { app ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onUnhide(app.packageName) }
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            Text(app.label, color = Color.White, fontSize = 17.sp)
                            Text("Unhide", color = Color(0xFF14C3B2), fontSize = 14.sp)
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

                // ── Word Widget Settings ──────────────────────────────────────
                var expandWords by remember { mutableStateOf(false) }
                SettingsExpandableSection("প্রতি ঘন্টার শব্দ", expandWords, { expandWords = !expandWords }) {
                    WordSettingsPanel(prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
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

// ─────────────────────────────────────────────────────────────────────────────
// GREETING + DAILY QUOTE — সময় অনুযায়ী greeting, প্রতিদিন নতুন quote
// ─────────────────────────────────────────────────────────────────────────────
private val QUOTES = listOf(
    "ছোট ছোট পদক্ষেপই বড় পথ তৈরি করে।",
    "Focus on the step in front of you, not the whole staircase.",
    "আজকের মনোযোগই আগামীর সাফল্য।",
    "Do less. Do it better. Do it now.",
    "একটু একটু করে এগিয়ে যাও — থেমে যেও না।",
    "Your only limit is your mind.",
    "গভীর মনোযোগই সত্যিকারের শক্তি।",
    "Small progress is still progress.",
    "শান্ত মাথায় বড় কাজ হয়।",
    "Discipline is choosing what you want most over what you want now.",
    "প্রতিটি নতুন দিন একটি নতুন সুযোগ।",
    "Work hard in silence. Let success make the noise.",
    "সময় নষ্ট করা মানে জীবন নষ্ট করা।",
    "You don't have to be great to start, but you have to start to be great.",
    "মনকে নিয়ন্ত্রণ করতে পারলে সবকিছু নিয়ন্ত্রণে আসে।",
    "One focused hour beats ten distracted hours.",
    "আজকের পরিশ্রম আগামীর আরামের বীজ।",
    "Be present. Be focused. Be unstoppable.",
    "বিজয়ীরা ভিন্ন কাজ করে না — তারা একই কাজ ভিন্নভাবে করে।",
    "Clarity of mind brings clarity of action.",
    "সফলতা কোনো দুর্ঘটনা নয় — এটা পছন্দের ফলাফল।",
    "The secret of getting ahead is getting started.",
    "ধৈর্য এবং পরিশ্রম — এই দুটোই যথেষ্ট।",
    "Focus is the new IQ.",
    "তোমার সময় তোমার সম্পদ — সঠিকভাবে ব্যয় করো।",
    "Less distraction. More intention.",
    "প্রতিটি মুহূর্তকে সার্থক করো।",
    "Consistency beats perfection every time.",
    "নিজেকে বিশ্বাস করো — বাকিটা এমনিতেই হবে।",
    "Your future self is watching. Make them proud."
)

@Composable
fun GreetingQuoteSection(context: Context) {
    val hour = remember {
        java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    }
    val greeting = when (hour) {
        in 5..11  -> "শুভ সকাল"
        in 12..16 -> "শুভ বিকেল"
        in 17..20 -> "শুভ সন্ধ্যা"
        else      -> "শুভ রাত্রি"
    }
    val greetingEmoji = when (hour) {
        in 5..11  -> "🌤"
        in 12..16 -> "☀️"
        in 17..20 -> "🌆"
        else      -> "🌙"
    }

    // প্রতিদিন একটা নির্দিষ্ট quote — day of year দিয়ে index বের করি
    val quote = remember {
        val dayOfYear = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        QUOTES[dayOfYear % QUOTES.size]
    }

    // Fade-in animation
    var visible by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue   = if (visible) 1f else 0f,
        animationSpec = tween(800),
        label         = "greetAlpha"
    )
    LaunchedEffect(Unit) { delay(200); visible = true }

    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text       = "$greeting $greetingEmoji",
            color      = TXT.copy(alpha = 0.75f),
            fontSize   = 16.sp,
            fontWeight = FontWeight.Light
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text      = "\"$quote\"",
            color     = TXT.copy(alpha = 0.35f),
            fontSize  = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 17.sp
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// HOURLY WORD WIDGET
// প্রতি ঘন্টায় একটি নতুন শব্দ দেখায়।
// User settings থেকে custom words নিয়ে cycle করে;
// custom না থাকলে built-in DEFAULT_WORDS ব্যবহার করে।
//
// Format stored in prefs (KEY_CUSTOM_WORDS):
//   "EnglishWord|বাংলা অর্থ|Example sentence;NextWord|অর্থ|Example"
//   Example অংশ optional — শুধু "|" separator থাকলেও কাজ করবে।
// ─────────────────────────────────────────────────────────────────────────────

/** Parse raw prefs string into list of WordPair */
internal fun parseCustomWords(raw: String): List<WordPair> {
    if (raw.isBlank()) return emptyList()
    return raw.split(";").mapNotNull { entry ->
        val parts = entry.split("|")
        if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            WordPair(
                english = parts[0].trim(),
                bangla  = parts[1].trim(),
                example = if (parts.size >= 3) parts[2].trim() else ""
            )
        } else null
    }
}

/** Serialize list back to prefs string */
internal fun serializeCustomWords(words: List<WordPair>): String =
    words.joinToString(";") { "${it.english}|${it.bangla}|${it.example}" }

/**
 * Pick which word to show for the current hour.
 * — hour changes  → advance index by 1 (saved in prefs so it survives restarts)
 * — index cycles  through the full word list
 */
internal fun currentWordForHour(
    prefs:    SharedPreferences,
    wordList: List<WordPair>
): WordPair {
    if (wordList.isEmpty()) return WordPair("Focus", "মনোযোগ", "Stay focused every hour.")
    val cal      = java.util.Calendar.getInstance()
    val thisHour = cal.get(java.util.Calendar.HOUR_OF_DAY) +
                   cal.get(java.util.Calendar.DAY_OF_YEAR) * 24  // unique per hour per day
    val lastHour = prefs.getInt(KEY_WORD_HOUR, -1)
    // Clamp saved idx to valid range first (handles -1 reset and list size changes)
    var idx = prefs.getInt(KEY_WORD_IDX, 0).coerceIn(0, wordList.size - 1)

    if (thisHour != lastHour) {
        // Hour changed — advance to next word
        idx = (idx + 1) % wordList.size
        prefs.edit()
            .putInt(KEY_WORD_HOUR, thisHour)
            .putInt(KEY_WORD_IDX,  idx)
            .apply()
    }
    return wordList[idx]
}

/** Returns up to [count] consecutive words starting from current hour index */
internal fun currentWordsForHour(
    prefs:    SharedPreferences,
    wordList: List<WordPair>,
    count:    Int = 5
): List<WordPair> {
    if (wordList.isEmpty()) return listOf(WordPair("Focus", "মনোযোগ", "Stay focused every hour."))
    val cal      = java.util.Calendar.getInstance()
    val thisHour = cal.get(java.util.Calendar.HOUR_OF_DAY) +
                   cal.get(java.util.Calendar.DAY_OF_YEAR) * 24
    val lastHour = prefs.getInt(KEY_WORD_HOUR, -1)
    var startIdx = prefs.getInt(KEY_WORD_IDX, 0).coerceIn(0, wordList.size - 1)

    if (thisHour != lastHour) {
        startIdx = (startIdx + 1) % wordList.size
        prefs.edit()
            .putInt(KEY_WORD_HOUR, thisHour)
            .putInt(KEY_WORD_IDX,  startIdx)
            .apply()
    }
    // Return [count] words starting at startIdx, wrapping around
    return (0 until count.coerceAtMost(wordList.size)).map { i ->
        wordList[(startIdx + i) % wordList.size]
    }
}

@Composable
fun HourlyWordWidget(context: Context) {
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    // Re-read every minute in case hour flips while app is open
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { delay(60_000L); tick++ }
    }

    val wordList: List<WordPair> = remember(tick) {
        val custom = parseCustomWords(prefs.getString(KEY_CUSTOM_WORDS, "") ?: "")
        if (custom.isNotEmpty()) custom else DEFAULT_WORDS
    }

    // 5 words — highlighted index cycles each tick (so they blink in turn)
    val words: List<WordPair> = remember(tick, wordList) {
        currentWordsForHour(prefs, wordList, 5)
    }

    // Which word index is "active" (highlighted) — cycles every 3 s
    var activeIdx by remember { mutableStateOf(0) }
    LaunchedEffect(words) { activeIdx = 0 }
    LaunchedEffect(words) {
        while (true) {
            delay(3000L)
            activeIdx = (activeIdx + 1) % words.size
        }
    }

    // Header
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            "শব্দ • Hour",
            color = ACCENT.copy(alpha = 0.55f),
            fontSize = 10.sp,
            letterSpacing = 0.6.sp
        )
        Icon(Icons.Default.Schedule, null, tint = ACCENT.copy(alpha = 0.35f), modifier = Modifier.size(11.dp))
    }

    // 5 word cards — vertically stacked, each tappable
    words.forEachIndexed { i, wordPair ->
        val isActive = i == activeIdx
        val cardAlpha by animateFloatAsState(
            targetValue   = if (isActive) 1f else 0.42f,
            animationSpec = tween(500),
            label         = "wordAlpha$i"
        )
        val cardBg by animateColorAsState(
            targetValue   = if (isActive) Color(0xFF0E2A22) else Color(0xFF0A1410),
            animationSpec = tween(500),
            label         = "wordBg$i"
        )
        val borderAlpha by animateFloatAsState(
            targetValue   = if (isActive) 0.55f else 0.10f,
            animationSpec = tween(500),
            label         = "wordBorder$i"
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .graphicsLayer { alpha = cardAlpha }
                .clip(RoundedCornerShape(10.dp))
                .background(cardBg)
                .border(0.5.dp, ACCENT.copy(alpha = borderAlpha), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text       = wordPair.english,
                    color      = if (isActive) TXT else TXT.copy(alpha = 0.75f),
                    fontSize   = 15.sp,
                    fontWeight = if (isActive) FontWeight.Normal else FontWeight.Light,
                    modifier   = Modifier.weight(1f)
                )
                Text(
                    text     = wordPair.bangla,
                    color    = ACCENT.copy(alpha = if (isActive) 0.9f else 0.5f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 6.dp, bottom = 1.dp)
                )
            }
            if (isActive && wordPair.example.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text       = "\"${wordPair.example}\"",
                    color      = TXT.copy(alpha = 0.30f),
                    fontSize   = 10.sp,
                    lineHeight = 14.sp,
                    fontStyle  = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// WORD SETTINGS PANEL — user এখানে নিজের শব্দ format এ add করে
// Format: "English|বাংলা অর্থ|Example sentence" প্রতিটা line তে
// এই composable LauncherSettingsSheet এ include হবে
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun WordSettingsPanel(prefs: SharedPreferences) {
    val context = LocalContext.current
    var rawText by remember {
        mutableStateOf(
            run {
                val saved = prefs.getString(KEY_CUSTOM_WORDS, "") ?: ""
                // Convert stored semicolon-sep to newline-sep for editing
                saved.replace(";", "\n")
            }
        )
    }
    var saved by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Text(
            text  = "প্রতি ঘন্টার শব্দ তালিকা",
            color = TXT,
            fontSize = 15.sp,
            fontWeight = FontWeight.Light
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text  = "প্রতিটা শব্দ আলাদা line এ লেখো:\nEnglish|বাংলা অর্থ|Example (optional)",
            color = DIM,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(10.dp))

        // Multi-line text field
        BasicTextField(
            value         = rawText,
            onValueChange = { rawText = it; saved = false },
            textStyle     = TextStyle(
                color      = TXT,
                fontSize   = 13.sp,
                lineHeight = 20.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            ),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 260.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF141414))
                        .border(
                            width = 0.5.dp,
                            color = if (saved) ACCENT.copy(alpha = 0.5f) else DIVIDER,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .padding(12.dp)
                ) {
                    if (rawText.isBlank()) {
                        Text(
                            "Resilient|স্থিতিস্থাপক|She stayed resilient.\nFocus|মনোযোগ|Stay focused.\n...",
                            color    = TXT.copy(alpha = 0.22f),
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        )

        Spacer(Modifier.height(10.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            // Word count preview
            val count = remember(rawText) {
                parseCustomWords(rawText.replace("\n", ";")).size
            }
            Text(
                text  = if (count > 0) "$count টি শব্দ" else "খালি → built-in 20 শব্দ ব্যবহার হবে",
                color = DIM,
                fontSize = 11.sp
            )

            Row {
                if (rawText.isNotBlank()) {
                    TextButton(onClick = { rawText = ""; saved = false }) {
                        Text("Clear", color = RED.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(ACCENT.copy(alpha = 0.18f))
                        .clickable {
                            val serialized = rawText.trim().replace("\n", ";")
                            prefs.edit().putString(KEY_CUSTOM_WORDS, serialized).apply()
                            // Reset cycling index so next widget refresh picks first word
                            prefs.edit().putInt(KEY_WORD_IDX, 0).putInt(KEY_WORD_HOUR, -1).apply()
                            saved = true
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        if (saved) "✓ Saved" else "Save",
                        color    = ACCENT,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

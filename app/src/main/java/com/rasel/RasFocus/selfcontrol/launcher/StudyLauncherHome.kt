@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.rasel.RasFocus.selfcontrol.launcher

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

// ─── Study Launcher Color Tokens ─────────────────────────────────────────────
private val SL_BG         = Color(0xFF110E07)   // warm dark parchment bg
private val SL_BG2        = Color(0xFF1A1509)   // slightly lighter card bg
private val SL_BG3        = Color(0xFF221C0C)   // card surface
private val SL_AMBER      = Color(0xFFD4922A)   // primary accent
private val SL_AMBER_DIM  = Color(0xFFD4922A).copy(alpha = 0.35f)
private val SL_PARCHMENT  = Color(0xFFEED9A0)   // text
private val SL_PARCHMENT2 = Color(0xFFB09860)   // muted text
private val SL_GREEN      = Color(0xFF5BC88A)   // success
private val SL_RED        = Color(0xFFE07070)   // danger
private val SL_BLUE       = Color(0xFF78B4E8)   // link / pdf
private val SL_PURPLE     = Color(0xFFBB88FF)   // word widget accent
private val SL_DIVIDER    = Color(0xFF2E2616)

// ─── Local default word list (mirrors launcher's DEFAULT_WORDS) ───────────────
private val SL_DEFAULT_WORDS = listOf(
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
    WordPair("Persevere",   "অধ্যবসায় করা",           "Persevere through difficult times."),
    WordPair("Succinct",    "সংক্ষিপ্ত ও স্পষ্ট",     "Keep your answers succinct and clear."),
    WordPair("Empathy",     "সহানুভূতি / অনুভূতি",   "Empathy makes you a better leader."),
    WordPair("Innovative",  "উদ্ভাবনী",               "An innovative solution saved the day."),
    WordPair("Versatile",   "বহুমুখী",                "A versatile developer adapts quickly."),
    WordPair("Fortitude",   "মনোবল / সাহস",           "He faced hardship with great fortitude."),
    WordPair("Prudent",     "বিচক্ষণ / সতর্ক",        "A prudent decision saves future pain.")
)

// ─── Local launchApp helper ───────────────────────────────────────────────────
private fun studyLaunchApp(context: Context, packageName: String) {
    try {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent != null) context.startActivity(intent)
    } catch (_: Exception) {}
}

private const val SL_PREFS            = "launcher_prefs"
private const val KEY_PINNED_MSG      = "study_pinned_messages"   // JSON array of PinnedMessage
private const val KEY_PINNED_IMG      = "study_pinned_images"     // JSON array of uri strings
private const val KEY_STUDY_TASKS     = "study_tasks"             // JSON array of StudyTask
private const val KEY_STUDY_WORD_DATA = "study_word_display"      // reuse launcher word data

// ─── Data models ─────────────────────────────────────────────────────────────
data class PinnedMessage(
    val id:        String = UUID.randomUUID().toString(),
    val text:      String,
    val color:     Int    = 0xFFD4922A.toInt(),   // argb int for serialization
    val isPinned:  Boolean = true,
    val timestamp: Long    = System.currentTimeMillis()
)

data class StudyTask(
    val id:        String = UUID.randomUUID().toString(),
    val text:      String,
    val done:      Boolean = false
)

// ─── Serialization helpers ────────────────────────────────────────────────────
private fun savePinnedMessages(prefs: SharedPreferences, msgs: List<PinnedMessage>) {
    val arr = JSONArray()
    msgs.forEach { m ->
        arr.put(JSONObject().apply {
            put("id", m.id); put("text", m.text); put("color", m.color)
            put("isPinned", m.isPinned); put("timestamp", m.timestamp)
        })
    }
    prefs.edit().putString(KEY_PINNED_MSG, arr.toString()).apply()
}

private fun loadPinnedMessages(prefs: SharedPreferences): List<PinnedMessage> {
    val raw = prefs.getString(KEY_PINNED_MSG, "") ?: return emptyList()
    return try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            PinnedMessage(
                id        = o.optString("id", UUID.randomUUID().toString()),
                text      = o.optString("text", ""),
                color     = o.optInt("color", 0xFFD4922A.toInt()),
                isPinned  = o.optBoolean("isPinned", true),
                timestamp = o.optLong("timestamp", System.currentTimeMillis())
            )
        }
    } catch (e: Exception) { emptyList() }
}

private fun savePinnedImages(prefs: SharedPreferences, uris: List<String>) {
    val arr = JSONArray(); uris.forEach { arr.put(it) }
    prefs.edit().putString(KEY_PINNED_IMG, arr.toString()).apply()
}

private fun loadPinnedImages(prefs: SharedPreferences): List<String> {
    val raw = prefs.getString(KEY_PINNED_IMG, "") ?: return emptyList()
    return try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i -> arr.getString(i) }
    } catch (e: Exception) { emptyList() }
}

private fun saveStudyTasks(prefs: SharedPreferences, tasks: List<StudyTask>) {
    val arr = JSONArray()
    tasks.forEach { t ->
        arr.put(JSONObject().apply { put("id", t.id); put("text", t.text); put("done", t.done) })
    }
    prefs.edit().putString(KEY_STUDY_TASKS, arr.toString()).apply()
}

private fun loadStudyTasks(prefs: SharedPreferences): List<StudyTask> {
    val raw = prefs.getString(KEY_STUDY_TASKS, "") ?: return emptyList()
    return try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            StudyTask(id = o.optString("id", UUID.randomUUID().toString()), text = o.optString("text", ""), done = o.optBoolean("done", false))
        }
    } catch (e: Exception) { emptyList() }
}

// ─── Pinned color palette for messages ────────────────────────────────────────
private val NOTE_COLORS = listOf(
    Color(0xFFD4922A), Color(0xFF5BC88A), Color(0xFF78B4E8),
    Color(0xFFBB88FF), Color(0xFFE07070), Color(0xFFEED9A0)
)

// ─────────────────────────────────────────────────────────────────────────────
// StudyLauncherHome — main entry composable
// Called from MinimalistLauncherScreen when theme == Study (index 3)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun StudyLauncherHome(
    timeState:          Pair<String, String>,
    battery:            Int,
    isCharging:         Boolean,
    pinnedApps:         List<AppInfo>,
    appFontSize:        androidx.compose.ui.unit.TextUnit,
    renamedMap:         Map<String, String>,
    btnLeftPkg:         String,
    btnRightPkg:        String,
    clockPkg:           String,
    context:            Context,
    totalScreenMinutes: Long = 0L,
    onLaunch:           (AppInfo) -> Unit,
    onLongPress:        (AppInfo) -> Unit,
    onSettings:         () -> Unit,
    onLongPressClockRing:  () -> Unit,
    onLongPressBtnLeft:    () -> Unit,
    onLongPressBtnRight:   () -> Unit,
    onReorder:          (List<AppInfo>) -> Unit = {}
) {
    val prefs = remember { context.getSharedPreferences(SL_PREFS, Context.MODE_PRIVATE) }

    // ── Persisted state ────────────────────────────────────────────────────
    var pinnedMessages by remember { mutableStateOf(loadPinnedMessages(prefs)) }
    var pinnedImages   by remember { mutableStateOf(loadPinnedImages(prefs)) }
    var studyTasks     by remember { mutableStateOf(loadStudyTasks(prefs)) }

    // ── Dialogs ────────────────────────────────────────────────────────────
    var showAddMsgDialog  by remember { mutableStateOf(false) }
    var showAddPdfPicker  by remember { mutableStateOf(false) }
    var selectedPdfUri    by remember { mutableStateOf<String?>(null) }
    var showWordExpanded  by remember { mutableStateOf(false) }
    var showAddWordDialog by remember { mutableStateOf(false) }

    // ── Image picker ───────────────────────────────────────────────────────
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val uriStr = uri.toString()
            val updated = pinnedImages.toMutableList()
            if (!updated.contains(uriStr)) {
                updated.add(0, uriStr)     // latest first
                if (updated.size > 6) updated.removeLastOrNull()
            }
            pinnedImages = updated
            savePinnedImages(prefs, updated)
        }
    }

    // ── PDF picker ─────────────────────────────────────────────────────────
    val pdfPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            // Open PDF externally — system PDF viewer
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                // Fallback: open with chooser
                try {
                    val chooser = Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/pdf")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Open PDF with")
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(chooser)
                } catch (e2: Exception) { /* ignore */ }
            }
        }
    }

    val scroll = rememberScrollState()

    Box(
        Modifier
            .fillMaxSize()
            .background(SL_BG)
            .navigationBarsPadding()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(bottom = 100.dp)
        ) {
            // ── Top bar: clock + date + battery ───────────────────────────
            StudyTopBar(
                timeState  = timeState,
                battery    = battery,
                isCharging = isCharging,
                clockPkg   = clockPkg,
                context    = context,
                onLongPress = onLongPressClockRing,
                onSettings  = onSettings
            )

            // ── Section: Pinned Messages / Notes ──────────────────────────
            StudySectionHeader(
                icon  = "📌",
                title = "Pinned Notes",
                color = SL_AMBER,
                action = "Add" to { showAddMsgDialog = true }
            )
            PinnedMessagesSection(
                messages = pinnedMessages,
                onDelete = { id ->
                    val updated = pinnedMessages.filter { it.id != id }
                    pinnedMessages = updated
                    savePinnedMessages(prefs, updated)
                },
                onEdit   = { updated ->
                    val list = pinnedMessages.map { if (it.id == updated.id) updated else it }
                    pinnedMessages = list
                    savePinnedMessages(prefs, list)
                }
            )

            // ── Section: Pinned Images ─────────────────────────────────────
            StudySectionHeader(
                icon  = "🖼️",
                title = "Pinned Images",
                color = SL_BLUE,
                action = "Add" to { imagePicker.launch("image/*") }
            )
            PinnedImagesSection(
                uris      = pinnedImages,
                context   = context,
                onDelete  = { uri ->
                    val updated = pinnedImages.filter { it != uri }
                    pinnedImages = updated
                    savePinnedImages(prefs, updated)
                }
            )

            // ── Section: Word Widget ─────────────────────────────────────
            WordSectionHeader(
                isExpanded   = showWordExpanded,
                onToggle     = { showWordExpanded = !showWordExpanded },
                onAddWord    = { showAddWordDialog = true }
            )
            StudyWordSection(
                context      = context,
                prefs        = prefs,
                isExpanded   = showWordExpanded
            )

            // ── Section: PDF Reader ─────────────────────────────────────
            StudySectionHeader(
                icon  = "📄",
                title = "PDF পড়ো",
                color = SL_RED,
                action = "Open PDF" to { pdfPicker.launch("application/pdf") }
            )
            PdfReaderSection(context = context)

            // ── Section: Today's Study Tasks ────────────────────────────
            StudySectionHeader(
                icon  = "✅",
                title = "আজকের কাজ",
                color = SL_GREEN,
                action = null
            )
            StudyTasksSection(
                tasks  = studyTasks,
                prefs  = prefs,
                onUpdate = { updated ->
                    studyTasks = updated
                    saveStudyTasks(prefs, updated)
                }
            )

            // ── Section: Pinned Apps ─────────────────────────────────────
            if (pinnedApps.isNotEmpty()) {
                StudySectionHeader(icon = "⚡", title = "Quick Apps", color = SL_AMBER, action = null)
                PinnedAppsStudySection(
                    apps        = pinnedApps,
                    renamedMap  = renamedMap,
                    appFontSize = appFontSize,
                    onLaunch    = onLaunch,
                    onLongPress = onLongPress
                )
            }

            Spacer(Modifier.height(12.dp))
        }

        // ── Bottom buttons ─────────────────────────────────────────────────
        BottomButtonBar(
            btnLeftPkg   = btnLeftPkg,
            btnRightPkg  = btnRightPkg,
            context      = context,
            modifier     = Modifier.align(Alignment.BottomCenter),
            onLongPressLeft  = onLongPressBtnLeft,
            onLongPressRight = onLongPressBtnRight
        )
    }

    // ── Add Message Dialog ─────────────────────────────────────────────────
    if (showAddMsgDialog) {
        AddMessageDialog(
            onConfirm = { text, color ->
                if (text.isNotBlank()) {
                    val newMsg = PinnedMessage(text = text, color = color)
                    val updated = listOf(newMsg) + pinnedMessages
                    pinnedMessages = updated
                    savePinnedMessages(prefs, updated)
                }
                showAddMsgDialog = false
            },
            onDismiss = { showAddMsgDialog = false }
        )
    }

    if (showAddWordDialog) {
        AddWordDialog(
            prefs     = prefs,
            onDismiss = { showAddWordDialog = false }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Top Bar — warm amber-themed clock, battery, date, settings icon
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun StudyTopBar(
    timeState:  Pair<String, String>,
    battery:    Int,
    isCharging: Boolean,
    clockPkg:   String,
    context:    Context,
    onLongPress: () -> Unit,
    onSettings:  () -> Unit
) {
    val pulseAnim = rememberInfiniteTransition(label = "sl_pulse")
    val pulseAlpha by pulseAnim.animateFloat(
        initialValue = 0.6f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "slPulse"
    )

    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF1D170A), SL_BG))
            )
            .padding(horizontal = 20.dp, vertical = 0.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: date
            Column {
                Text(
                    SimpleDateFormat("EEE, dd MMM", Locale.getDefault()).format(Date()),
                    color = SL_PARCHMENT2,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isCharging) Icons.Default.BatteryChargingFull else Icons.Default.Battery5Bar,
                        null, tint = if (isCharging) SL_GREEN.copy(alpha = pulseAlpha) else SL_PARCHMENT2,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text("$battery%", color = SL_PARCHMENT2, fontSize = 11.sp)
                }
            }

            // Center: clock
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .combinedClickable(
                        onClick = { if (clockPkg.isNotBlank()) studyLaunchApp(context, clockPkg) else onLongPress() },
                        onLongClick = onLongPress
                    )
            ) {
                Text(
                    timeState.first,
                    color = SL_PARCHMENT,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Light
                )
                // Amber underline accent
                Box(
                    Modifier.width(60.dp).height(1.5.dp)
                        .background(
                            Brush.horizontalGradient(listOf(Color.Transparent, SL_AMBER, Color.Transparent)),
                            RoundedCornerShape(1.dp)
                        )
                )
            }

            // Right: settings
            Column(horizontalAlignment = Alignment.End) {
                Icon(
                    Icons.Default.Settings, null,
                    tint = SL_PARCHMENT2,
                    modifier = Modifier.size(20.dp).clickable { onSettings() }
                )
                Spacer(Modifier.height(6.dp))
                // Study label
                Box(
                    Modifier
                        .background(SL_AMBER.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text("STUDY", color = SL_AMBER, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                }
            }
        }

        // Amber bottom border
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(0.5.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, SL_AMBER_DIM, Color.Transparent)))
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Word Section Header — with "+ Word" and Expand/Collapse buttons
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun WordSectionHeader(
    isExpanded: Boolean,
    onToggle:   () -> Unit,
    onAddWord:  () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left: icon + title
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📖", fontSize = 14.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                "শব্দ মুখস্থ",
                color = SL_PURPLE,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.3.sp
            )
        }
        // Right: "+ Word" button + Expand/Collapse button
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // + Word button
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(SL_PURPLE.copy(alpha = 0.13f))
                    .clickable { onAddWord() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("+ Word", color = SL_PURPLE, fontSize = 11.sp)
            }
            // Expand / Collapse
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(SL_PURPLE.copy(alpha = 0.08f))
                    .clickable { onToggle() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    if (isExpanded) "Collapse" else "Expand",
                    color = SL_PURPLE.copy(alpha = 0.75f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Add Word Dialog — user টা নতুন word add করতে পারবে
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun AddWordDialog(
    prefs:     SharedPreferences,
    onDismiss: () -> Unit
) {
    var english  by remember { mutableStateOf("") }
    var bangla   by remember { mutableStateOf("") }
    var example  by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF1C1608),
        shape            = RoundedCornerShape(16.dp),
        title = {
            Text(
                "নতুন Word যোগ করো",
                color = SL_PURPLE,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // English word
                OutlinedTextField(
                    value = english,
                    onValueChange = { english = it },
                    label = { Text("English Word", color = SL_PARCHMENT2, fontSize = 12.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = SL_PURPLE,
                        unfocusedBorderColor = SL_PURPLE.copy(alpha = 0.3f),
                        focusedTextColor     = SL_PARCHMENT,
                        unfocusedTextColor   = SL_PARCHMENT,
                        cursorColor          = SL_PURPLE
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                // Bangla meaning
                OutlinedTextField(
                    value = bangla,
                    onValueChange = { bangla = it },
                    label = { Text("বাংলা অর্থ", color = SL_PARCHMENT2, fontSize = 12.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = SL_PURPLE,
                        unfocusedBorderColor = SL_PURPLE.copy(alpha = 0.3f),
                        focusedTextColor     = SL_PARCHMENT,
                        unfocusedTextColor   = SL_PARCHMENT,
                        cursorColor          = SL_PURPLE
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                // Example sentence (optional)
                OutlinedTextField(
                    value = example,
                    onValueChange = { example = it },
                    label = { Text("উদাহরণ বাক্য (optional)", color = SL_PARCHMENT2, fontSize = 12.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = SL_PURPLE.copy(alpha = 0.5f),
                        unfocusedBorderColor = SL_PURPLE.copy(alpha = 0.2f),
                        focusedTextColor     = SL_PARCHMENT,
                        unfocusedTextColor   = SL_PARCHMENT,
                        cursorColor          = SL_PURPLE
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (english.isNotBlank() && bangla.isNotBlank()) SL_PURPLE else SL_PURPLE.copy(alpha = 0.35f))
                    .clickable(enabled = english.isNotBlank() && bangla.isNotBlank()) {
                        // Save to prefs
                        val saved = prefs.getString("word_widget_custom", "") ?: ""
                        val newEntry = "${english.trim()}|${bangla.trim()}|${example.trim()}"
                        val updated = if (saved.isBlank()) newEntry else "$saved;$newEntry"
                        prefs.edit().putString("word_widget_custom", updated).apply()
                        onDismiss()
                    }
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Text("Save", color = SL_BG, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        },
        dismissButton = {
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onDismiss() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("বাতিল", color = SL_PARCHMENT2, fontSize = 13.sp)
            }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Section header
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun StudySectionHeader(
    icon:   String,
    title:  String,
    color:  Color,
    action: Pair<String, () -> Unit>?
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 14.sp)
            Spacer(Modifier.width(6.dp))
            Text(title, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp)
        }
        if (action != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(color.copy(alpha = 0.13f))
                    .clickable { action.second() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(action.first, color = color, fontSize = 11.sp)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pinned Messages Section
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun PinnedMessagesSection(
    messages: List<PinnedMessage>,
    onDelete: (String) -> Unit,
    onEdit:   (PinnedMessage) -> Unit
) {
    if (messages.isEmpty()) {
        StudyEmptySlot(
            icon = "📌",
            text = "কোনো note নেই — 'Add' tap করে যোগ করো",
            color = SL_AMBER
        )
        return
    }

    LazyRow(
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(messages, key = { it.id }) { msg ->
            PinnedMessageCard(msg = msg, onDelete = { onDelete(msg.id) }, onEdit = onEdit)
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun PinnedMessageCard(
    msg:      PinnedMessage,
    onDelete: () -> Unit,
    onEdit:   (PinnedMessage) -> Unit
) {
    val accentColor = Color(msg.color)
    var showMenu by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }
    var editText by remember(msg.id) { mutableStateOf(msg.text) }
    val keyboard = LocalSoftwareKeyboardController.current

    Box(
        Modifier
            .width(170.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.verticalGradient(
                    listOf(SL_BG3, Color(0xFF1A150A))
                )
            )
            .border(0.7.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = { if (!editMode) showMenu = !showMenu },
                onLongClick = { editMode = true }
            )
            .padding(12.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Top row: pin icon + delete
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.PushPin, null, tint = accentColor, modifier = Modifier.size(12.dp))
                AnimatedVisibility(showMenu) {
                    Row {
                        Icon(
                            Icons.Default.Edit, null,
                            tint = SL_PARCHMENT2,
                            modifier = Modifier.size(14.dp).clickable { editMode = true; showMenu = false }
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Close, null,
                            tint = SL_RED,
                            modifier = Modifier.size(14.dp).clickable { onDelete() }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            if (editMode) {
                BasicTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    textStyle = TextStyle(color = SL_PARCHMENT, fontSize = 13.sp, lineHeight = 19.sp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        "Cancel", color = SL_PARCHMENT2, fontSize = 11.sp,
                        modifier = Modifier.clickable { editMode = false; editText = msg.text }
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Save", color = accentColor, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable {
                            onEdit(msg.copy(text = editText))
                            editMode = false
                            keyboard?.hide()
                        }
                    )
                }
            } else {
                Text(
                    msg.text,
                    color = SL_PARCHMENT,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                // Accent line at bottom
                Box(
                    Modifier.height(2.dp).fillMaxWidth(0.4f)
                        .background(accentColor.copy(alpha = 0.5f), RoundedCornerShape(1.dp))
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pinned Images Section
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun PinnedImagesSection(
    uris:    List<String>,
    context: Context,
    onDelete: (String) -> Unit
) {
    if (uris.isEmpty()) {
        StudyEmptySlot(icon = "🖼️", text = "কোনো image pin করা নেই — 'Add' tap করো", color = SL_BLUE)
        return
    }

    LazyRow(
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(uris) { uriStr ->
            PinnedImageCard(uriStr = uriStr, context = context, onDelete = { onDelete(uriStr) })
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun PinnedImageCard(
    uriStr:  String,
    context: Context,
    onDelete: () -> Unit
) {
    var showDelete by remember { mutableStateOf(false) }
    val uri = remember(uriStr) { Uri.parse(uriStr) }

    // Try to load bitmap
    val bmp = remember(uriStr) {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                android.graphics.ImageDecoder.decodeBitmap(
                    android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
                ) { decoder, _, _ ->
                    decoder.setTargetSampleSize(4)   // downsample for thumbnail
                }
            } else {
                @Suppress("DEPRECATION")
                android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        }.getOrNull()?.asImageBitmap()
    }

    Box(
        Modifier
            .size(130.dp, 110.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(0.7.dp, SL_BLUE.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .background(SL_BG3)
            .combinedClickable(
                onClick = {
                    // Open full image
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "image/*")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        )
                    } catch (e: Exception) { /* ignore */ }
                },
                onLongClick = { showDelete = !showDelete }
            )
    ) {
        if (bmp != null) {
            androidx.compose.foundation.Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.BrokenImage, null, tint = SL_BLUE.copy(alpha = 0.4f))
            }
        }

        // Gradient overlay for delete button
        if (showDelete) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(
                        Icons.Default.Close, null, tint = SL_RED,
                        modifier = Modifier.size(28.dp)
                            .background(SL_RED.copy(alpha = 0.18f), CircleShape)
                            .padding(4.dp)
                            .clickable { onDelete() }
                    )
                    Icon(
                        Icons.Default.Cancel, null, tint = SL_PARCHMENT2,
                        modifier = Modifier.size(28.dp)
                            .background(SL_PARCHMENT2.copy(alpha = 0.12f), CircleShape)
                            .padding(4.dp)
                            .clickable { showDelete = false }
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Study Word Section — মুখস্ত শব্দ widget (expanded or collapsed)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun StudyWordSection(
    context:    Context,
    prefs:      SharedPreferences,
    isExpanded: Boolean
) {
    val wordList: List<WordPair> = remember {
        val custom = parseCustomWords(prefs.getString("word_widget_custom", "") ?: "")
        if (custom.isNotEmpty()) custom else SL_DEFAULT_WORDS
    }

    // Compact: show 2 words side-by-side; Expanded: show all
    val words: List<WordPair> = remember(wordList) {
        currentWordsForHour(prefs, wordList, if (isExpanded) wordList.size else 2)
    }

    if (!isExpanded) {
        // ── Compact: 2 cards side by side ────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            words.take(2).forEach { word ->
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(SL_BG3)
                        .border(0.6.dp, SL_PURPLE.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text(
                        word.english,
                        color = SL_PARCHMENT,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.2.sp
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        word.bangla,
                        color = SL_PURPLE,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal
                    )
                    if (word.example.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(0.6.dp)
                                .background(SL_PURPLE.copy(alpha = 0.18f))
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "\"${word.example}\"",
                            color = SL_PARCHMENT2.copy(alpha = 0.65f),
                            fontSize = 10.sp,
                            fontStyle = FontStyle.Italic,
                            lineHeight = 14.sp,
                            maxLines = 2
                        )
                    }
                }
            }
        }
    } else {
        // ── Expanded: all words, clean list ──────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            words.forEachIndexed { i, word ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SL_BG3)
                        .border(0.5.dp, SL_PURPLE.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Number badge
                    Box(
                        Modifier
                            .size(22.dp)
                            .background(SL_PURPLE.copy(alpha = 0.14f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${i + 1}",
                            color = SL_PURPLE,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            word.english,
                            color = SL_PARCHMENT,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            word.bangla,
                            color = SL_PURPLE,
                            fontSize = 11.sp
                        )
                        if (word.example.isNotBlank()) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "\"${word.example}\"",
                                color = SL_PARCHMENT2.copy(alpha = 0.55f),
                                fontSize = 10.sp,
                                fontStyle = FontStyle.Italic,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

// ─────────────────────────────────────────────────────────────────────────────
// PDF Reader Section — quick actions
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun PdfReaderSection(context: Context) {
    val pdfPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                try {
                    val chooser = Intent.createChooser(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/pdf")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Open with"
                    )
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(chooser)
                } catch (e2: Exception) { }
            }
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Open PDF card
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF2A1010), Color(0xFF1A0808))
                    )
                )
                .border(0.7.dp, SL_RED.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                .clickable { pdfPicker.launch("application/pdf") }
                .padding(16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PictureAsPdf, null, tint = SL_RED, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("PDF খোলো", color = SL_PARCHMENT, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("Storage থেকে PDF", color = SL_RED.copy(alpha = 0.65f), fontSize = 10.sp)
                }
            }
        }

        // Browse study links card
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF0A1A2A), Color(0xFF08141E))
                    )
                )
                .border(0.7.dp, SL_BLUE.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                .clickable {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://drive.google.com"))
                                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        )
                    } catch (e: Exception) { }
                }
                .padding(16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudQueue, null, tint = SL_BLUE, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Drive PDF", color = SL_PARCHMENT, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("Google Drive খোলো", color = SL_BLUE.copy(alpha = 0.65f), fontSize = 10.sp)
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

// ─────────────────────────────────────────────────────────────────────────────
// Study Tasks Section — today's checklist
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun StudyTasksSection(
    tasks:    List<StudyTask>,
    prefs:    SharedPreferences,
    onUpdate: (List<StudyTask>) -> Unit
) {
    var newTaskText by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    Column(Modifier.padding(horizontal = 14.dp)) {
        // Input row
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SL_BG3)
                .border(0.5.dp, SL_GREEN.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = newTaskText,
                onValueChange = { newTaskText = it },
                singleLine = true,
                textStyle = TextStyle(color = SL_PARCHMENT, fontSize = 14.sp),
                decorationBox = { inner ->
                    Box(Modifier.weight(1f)) {
                        if (newTaskText.isBlank()) Text("নতুন কাজ লিখো…", color = SL_PARCHMENT2.copy(alpha = 0.5f), fontSize = 14.sp)
                        inner()
                    }
                },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.Add, null,
                tint = if (newTaskText.isBlank()) SL_PARCHMENT2.copy(alpha = 0.3f) else SL_GREEN,
                modifier = Modifier.size(20.dp).clickable {
                    if (newTaskText.isNotBlank()) {
                        val updated = tasks + StudyTask(text = newTaskText.trim())
                        onUpdate(updated)
                        newTaskText = ""
                        keyboard?.hide()
                    }
                }
            )
        }

        Spacer(Modifier.height(6.dp))

        if (tasks.isEmpty()) {
            StudyEmptySlot(icon = "✅", text = "কোনো কাজ নেই — উপরে লিখে add করো", color = SL_GREEN)
        } else {
            // Progress indicator
            val doneCount = tasks.count { it.done }
            if (tasks.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.weight(1f).height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(SL_BG3)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(doneCount.toFloat() / tasks.size.toFloat())
                                .fillMaxHeight()
                                .background(
                                    Brush.horizontalGradient(listOf(SL_GREEN, SL_AMBER)),
                                    RoundedCornerShape(2.dp)
                                )
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("$doneCount/${tasks.size}", color = SL_GREEN, fontSize = 11.sp)
                }
            }

            tasks.forEachIndexed { i, task ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (task.done) SL_BG3 else Color.Transparent)
                        .clickable {
                            val updated = tasks.map { t -> if (t.id == task.id) t.copy(done = !t.done) else t }
                            onUpdate(updated)
                        }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Checkbox
                    Box(
                        Modifier.size(18.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (task.done) SL_GREEN else Color.Transparent)
                            .border(
                                1.5.dp,
                                if (task.done) SL_GREEN else SL_PARCHMENT2.copy(alpha = 0.3f),
                                RoundedCornerShape(4.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (task.done) Icon(Icons.Default.Check, null, tint = SL_BG, modifier = Modifier.size(12.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        task.text,
                        color = if (task.done) SL_PARCHMENT2.copy(alpha = 0.4f) else SL_PARCHMENT,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Light,
                        textDecoration = if (task.done) TextDecoration.LineThrough else TextDecoration.None,
                        modifier = Modifier.weight(1f)
                    )
                    // Delete button
                    Icon(
                        Icons.Default.Close, null,
                        tint = SL_PARCHMENT2.copy(alpha = 0.25f),
                        modifier = Modifier.size(14.dp).clickable {
                            val updated = tasks.filter { it.id != task.id }
                            onUpdate(updated)
                        }
                    )
                }
                if (i < tasks.size - 1) {
                    HorizontalDivider(color = SL_DIVIDER.copy(alpha = 0.5f), thickness = 0.5.dp)
                }
            }

            // Clear done button
            if (tasks.any { it.done }) {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        "Done গুলো clear করো",
                        color = SL_RED.copy(alpha = 0.55f),
                        fontSize = 11.sp,
                        modifier = Modifier.clickable {
                            val updated = tasks.filter { !it.done }
                            onUpdate(updated)
                        }
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

// ─────────────────────────────────────────────────────────────────────────────
// Pinned Apps — compact list with study styling
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun PinnedAppsStudySection(
    apps:       List<AppInfo>,
    renamedMap: Map<String, String>,
    appFontSize: androidx.compose.ui.unit.TextUnit,
    onLaunch:   (AppInfo) -> Unit,
    onLongPress: (AppInfo) -> Unit
) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        apps.forEach { app ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick    = { onLaunch(app) },
                        onLongClick = { onLongPress(app) }
                    )
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Amber dot indicator
                Box(
                    Modifier.size(5.dp).background(SL_AMBER.copy(alpha = 0.5f), CircleShape)
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    renamedMap[app.packageName] ?: app.label,
                    color = SL_PARCHMENT,
                    fontSize = appFontSize,
                    fontWeight = FontWeight.Light
                )
            }
            HorizontalDivider(color = SL_DIVIDER, thickness = 0.5.dp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Add Message Dialog
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun AddMessageDialog(
    onConfirm: (String, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var text  by rememberSaveable { mutableStateOf("") }
    var selectedColorIdx by remember { mutableStateOf(0) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1E1808))
                .border(0.7.dp, SL_AMBER.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                .padding(20.dp)
        ) {
            Text("📌 নতুন Note", color = SL_PARCHMENT, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp))

            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = SL_PARCHMENT, fontSize = 14.sp, lineHeight = 20.sp),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 90.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF150F04))
                            .border(0.5.dp, SL_AMBER.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                            .padding(12.dp)
                    ) {
                        if (text.isBlank()) Text("এখানে লেখো…", color = SL_PARCHMENT2.copy(alpha = 0.35f), fontSize = 14.sp)
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))

            // Color picker
            Text("রঙ বেছে নাও:", color = SL_PARCHMENT2, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NOTE_COLORS.forEachIndexed { i, color ->
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(
                                if (selectedColorIdx == i) 2.dp else 0.dp,
                                SL_PARCHMENT,
                                CircleShape
                            )
                            .clickable { selectedColorIdx = i }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = SL_PARCHMENT2, fontSize = 13.sp)
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(SL_AMBER.copy(alpha = 0.18f))
                        .clickable { onConfirm(text, NOTE_COLORS[selectedColorIdx].toArgb()) }
                        .padding(horizontal = 16.dp, vertical = 9.dp)
                ) {
                    Text("📌 Pin করো", color = SL_AMBER, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helper: empty slot placeholder
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun StudyEmptySlot(icon: String, text: String, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SL_BG3)
            .border(0.5.dp, SL_DIVIDER, RoundedCornerShape(10.dp))
            .padding(14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 16.sp, color = color.copy(alpha = 0.4f))
            Spacer(Modifier.width(10.dp))
            Text(text, color = color.copy(alpha = 0.4f), fontSize = 12.sp, fontStyle = FontStyle.Italic)
        }
    }
}

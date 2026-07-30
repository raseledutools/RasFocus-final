package com.rasel.RasFocus.selfcontrol.study_tools

import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import com.rasel.RasFocus.selfcontrol.StudyToolsActivity
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import java.text.SimpleDateFormat
import java.util.*

// ── Colors ────────────────────────────────────────────────────────────────────
private val RmTeal       = Color(0xFF00897B)
private val RmTealDark   = Color(0xFF00695C)
private val RmTealAccent = Color(0xFF4DB6AC)
private val RmBg         = Color(0xFFF2F2F2)
private val RmWhite      = Color(0xFFFFFFFF)
private val RmDivider    = Color(0xFFE0E0E0)
private val RmText       = Color(0xFF212121)
private val RmTextSub    = Color(0xFF757575)
private val RmCritical   = Color(0xFFE53935)
private val RmImportant  = Color(0xFF43A047)
private val RmFavorite   = Color(0xFF1E88E5)

private const val RM_CHANNEL_ID   = "rasfocus_reminder_channel"
private const val RM_CHANNEL_NAME = "RasFocus Reminders"

// ── Enums ──────────────────────────────────────────────────────────────────────
enum class ReminderPriority(val label: String, val color: Color) {
    NORMAL("Normal", Color(0xFFFFB300)),
    CRITICAL("Critical", Color(0xFFE53935)),
    IMPORTANT("Important", Color(0xFF43A047)),
    FAVORITE("Favorite", Color(0xFF1E88E5))
}

enum class RepeatType(val label: String) {
    NONE("No Repetition"),
    EVERY_HOUR("Every Hour"),
    EVERY_DAY("Every Day"),
    EVERY_WEEK("Every Week"),
    EVERY_MONTH("Every Month"),
    EVERY_YEAR("Every Year"),
    SELECTED_WEEKDAYS("Selected Weekdays"),
    MONTHLY_ON("Monthly on the.."),
    CUSTOM("Custom")
}

// ── Data model ─────────────────────────────────────────────────────────────────
data class ReminderItem(
    val id: Int,
    val title: String,
    val description: String = "",
    val triggerMillis: Long,
    val repeatType: RepeatType = RepeatType.NONE,
    val priority: ReminderPriority = ReminderPriority.NORMAL,
    val withVibration: Boolean = true,
    val isCompleted: Boolean = false
)

// ── Helpers ────────────────────────────────────────────────────────────────────
private fun relativeDate(millis: Long): String {
    val diffDays = ((millis - System.currentTimeMillis()) / (1000L * 60 * 60 * 24)).toInt()
    return when {
        diffDays < 0  -> "Overdue"
        diffDays == 0 -> "Today"
        diffDays == 1 -> "Tomorrow"
        diffDays < 8  -> "in $diffDays days"
        diffDays < 32 -> "in ${diffDays / 7} weeks"
        else          -> "in ${diffDays / 30} months"
    }
}

private fun formatDateTime(millis: Long): String =
    SimpleDateFormat("d MMM yy  h:mm a", Locale.getDefault()).format(Date(millis))

private fun formatTime(hour: Int, minute: Int): String {
    val c = Calendar.getInstance().also { it.set(Calendar.HOUR_OF_DAY, hour); it.set(Calendar.MINUTE, minute) }
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(c.time)
}

// ── Notification channel ───────────────────────────────────────────────────────
fun ensureReminderChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val ch = NotificationChannel(RM_CHANNEL_ID, RM_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 200, 500)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }
}

// ── BroadcastReceiver ──────────────────────────────────────────────────────────
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label   = intent.getStringExtra("label") ?: "Reminder"
        val withVib = intent.getBooleanExtra("withVibration", true)
        val notifId = intent.getIntExtra("notifId", System.currentTimeMillis().toInt())
        ensureReminderChannel(context)
        if (withVib) {
            try {
                val pattern = longArrayOf(0, 700, 200, 700, 200, 700)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
                    else
                        @Suppress("DEPRECATION") v.vibrate(pattern, -1)
                }
            } catch (_: Exception) {}
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(notifId, NotificationCompat.Builder(context, RM_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("⏰ RasFocus Reminder")
                .setContentText(label)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true).build())
    }
}

// ── AlarmManager helpers ───────────────────────────────────────────────────────
fun scheduleReminderAlarm(context: Context, reminderId: Int, label: String, triggerMillis: Long, withVibration: Boolean) {
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val pi = PendingIntent.getBroadcast(
        context, reminderId,
        Intent(context, ReminderAlarmReceiver::class.java).apply {
            putExtra("label", label); putExtra("withVibration", withVibration); putExtra("notifId", reminderId)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val triggerAt = SystemClock.elapsedRealtime() + (triggerMillis - System.currentTimeMillis()).coerceAtLeast(1000L)
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        else
            am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
    } catch (_: Exception) { am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi) }
}

fun cancelReminderAlarm(context: Context, reminderId: Int) {
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val pi = PendingIntent.getBroadcast(context, reminderId, Intent(context, ReminderAlarmReceiver::class.java),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
    pi?.let { am.cancel(it) }
}

// ── Home shortcut ──────────────────────────────────────────────────────────────
fun addReminderHomeShortcut(context: Context) {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return
    ShortcutManagerCompat.requestPinShortcut(
        context,
        ShortcutInfoCompat.Builder(context, "reminder_shortcut_st")
            .setShortLabel("Reminder").setLongLabel("RasFocus Reminder")
            .setIcon(IconCompat.createWithResource(context, android.R.drawable.ic_lock_idle_alarm))
            .setIntent(Intent(context, StudyToolsActivity::class.java).apply {
                action = Intent.ACTION_VIEW; putExtra("open_reminder", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }).build(),
        null
    )
}

// ═════════════════════════════════════════════════════════════════════════════
// Entry card shown in StudyToolsMain
// ═════════════════════════════════════════════════════════════════════════════
@Composable
fun ReminderEntryCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(6.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth()
                .background(
                    Brush.linearGradient(listOf(RmTealDark, RmTeal, RmTealAccent)),
                    RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(48.dp).background(RmWhite.copy(.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Alarm, contentDescription = null, tint = RmWhite, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Reminder & Alarm", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = RmWhite)
                    Text("Set reminders, alarms & repeat schedule", fontSize = 12.sp, color = RmWhite.copy(.8f))
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = RmWhite.copy(.8f))
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// Full Reminder Screen
// ═════════════════════════════════════════════════════════════════════════════
@Composable
fun ReminderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var activeTab      by remember { mutableStateOf(0) }
    var selectedFilter by remember { mutableStateOf("All") }
    var reminders      by remember { mutableStateOf(listOf<ReminderItem>()) }
    var nextId         by remember { mutableStateOf(9200) }
    var showAddDialog  by remember { mutableStateOf(false) }
    var editItem       by remember { mutableStateOf<ReminderItem?>(null) }

    val filters = listOf("All", "Critical", "Important", "Favorites")
    val displayed = reminders.filter { r ->
        val tabOk = if (activeTab == 0) !r.isCompleted else r.isCompleted
        val filterOk = when (selectedFilter) {
            "Critical"  -> r.priority == ReminderPriority.CRITICAL
            "Important" -> r.priority == ReminderPriority.IMPORTANT
            "Favorites" -> r.priority == ReminderPriority.FAVORITE
            else        -> true
        }
        tabOk && filterOk
    }

    Box(modifier = Modifier.fillMaxSize().background(RmBg)) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Top bar
            Box(
                modifier = Modifier.fillMaxWidth().background(RmTeal)
                    .statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = RmWhite)
                    }
                    Text(
                        "Reminder", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                        color = RmWhite, modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { addReminderHomeShortcut(context) }) {
                        Icon(Icons.Default.AddToHomeScreen, contentDescription = "Shortcut", tint = RmWhite)
                    }
                    IconButton(onClick = {}) {
                        Icon(Icons.Default.FilterList, contentDescription = "Filter", tint = RmWhite)
                    }
                }
            }

            // Tabs
            TabRow(
                selectedTabIndex = activeTab,
                containerColor = RmTeal,
                contentColor = RmWhite,
                indicator = { tp ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tp[activeTab]),
                        color = RmWhite
                    )
                }
            ) {
                Tab(selected = activeTab == 0, onClick = { activeTab = 0 },
                    text = { Text("ACTIVE", fontSize = 13.sp, fontWeight = FontWeight.Bold) })
                Tab(selected = activeTab == 1, onClick = { activeTab = 1 },
                    text = { Text("COMPLETED", fontSize = 13.sp, fontWeight = FontWeight.Bold) })
            }

            // Filter chips
            LazyRow(
                modifier = Modifier.fillMaxWidth().background(RmWhite)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filters) { f ->
                    val sel = selectedFilter == f
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (sel) RmTeal else RmBg)
                            .border(1.dp, if (sel) RmTeal else RmDivider, RoundedCornerShape(20.dp))
                            .clickable { selectedFilter = f }
                            .padding(horizontal = 16.dp, vertical = 7.dp)
                    ) {
                        Text(
                            f, fontSize = 13.sp,
                            color = if (sel) RmWhite else RmTextSub,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
            Divider(color = RmDivider, thickness = 1.dp)

            // List or empty state
            if (displayed.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.NotificationsNone, contentDescription = null,
                            tint = RmDivider, modifier = Modifier.size(72.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No reminders yet", fontSize = 16.sp, color = RmTextSub, fontWeight = FontWeight.Medium)
                        Text("Tap + to add a new reminder", fontSize = 13.sp, color = RmDivider)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    itemsIndexed(displayed) { _, reminder ->
                        ReminderListItem(
                            reminder = reminder,
                            onComplete = {
                                reminders = reminders.map {
                                    if (it.id == reminder.id) it.copy(isCompleted = !it.isCompleted) else it
                                }
                            },
                            onDelete = {
                                cancelReminderAlarm(context, reminder.id)
                                reminders = reminders.filter { it.id != reminder.id }
                            },
                            onEdit = { editItem = reminder; showAddDialog = true }
                        )
                    }
                }
            }
        }

        // FAB
        FloatingActionButton(
            onClick = { editItem = null; showAddDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 24.dp),
            containerColor = RmTeal, contentColor = RmWhite, shape = CircleShape,
            elevation = FloatingActionButtonDefaults.elevation(6.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add reminder", modifier = Modifier.size(28.dp))
        }
    }

    if (showAddDialog) {
        ReminderAddDialog(
            initial = editItem,
            onDismiss = { showAddDialog = false },
            onSave = { item ->
                ensureReminderChannel(context)
                if (editItem != null) {
                    cancelReminderAlarm(context, item.id)
                    reminders = reminders.map { if (it.id == item.id) item else it }
                } else {
                    val newItem = item.copy(id = nextId++)
                    reminders = reminders + newItem
                    scheduleReminderAlarm(context, newItem.id, newItem.title, newItem.triggerMillis, newItem.withVibration)
                }
                showAddDialog = false
            }
        )
    }
}

// ─── Each reminder row ────────────────────────────────────────────────────────
@Composable
private fun ReminderListItem(
    reminder: ReminderItem,
    onComplete: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(RmWhite)
                .clickable { onEdit() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Checkbox
            Box(
                modifier = Modifier.size(22.dp).clip(CircleShape)
                    .border(2.dp, if (reminder.isCompleted) RmTeal else RmDivider, CircleShape)
                    .background(if (reminder.isCompleted) RmTeal else Color.Transparent)
                    .clickable { onComplete() },
                contentAlignment = Alignment.Center
            ) {
                if (reminder.isCompleted)
                    Icon(Icons.Default.Check, contentDescription = null, tint = RmWhite, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    reminder.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = if (reminder.isCompleted) RmTextSub else RmText,
                    textDecoration = if (reminder.isCompleted) TextDecoration.LineThrough else null,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatDateTime(reminder.triggerMillis), fontSize = 12.sp, color = RmTextSub)
                    if (reminder.repeatType != RepeatType.NONE)
                        Text("  \u2022  ${reminder.repeatType.label}", fontSize = 12.sp, color = RmTextSub)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                val rel = relativeDate(reminder.triggerMillis)
                Text(
                    rel, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    color = when (rel) {
                        "Today"    -> RmTeal
                        "Tomorrow" -> RmImportant
                        "Overdue"  -> RmCritical
                        else       -> RmTextSub
                    }
                )
                Spacer(Modifier.height(4.dp))
                Icon(
                    if (reminder.priority != ReminderPriority.NORMAL) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = null, tint = reminder.priority.color, modifier = Modifier.size(20.dp)
                )
            }
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = null, tint = RmTextSub, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { showMenu = false; onEdit() },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) })
                    DropdownMenuItem(
                        text = { Text(if (reminder.isCompleted) "Mark Active" else "Mark Complete") },
                        onClick = { showMenu = false; onComplete() },
                        leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null) })
                    DropdownMenuItem(
                        text = { Text("Delete", color = RmCritical) },
                        onClick = { showMenu = false; onDelete() },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = RmCritical) })
                }
            }
        }
        Divider(color = RmDivider, thickness = 0.5.dp, modifier = Modifier.padding(start = 52.dp))
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// Add / Edit Dialog
// ═════════════════════════════════════════════════════════════════════════════
@Composable
fun ReminderAddDialog(
    initial: ReminderItem? = null,
    onDismiss: () -> Unit,
    onSave: (ReminderItem) -> Unit
) {
    val context = LocalContext.current
    var title       by remember { mutableStateOf(initial?.title ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var priority    by remember { mutableStateOf(initial?.priority ?: ReminderPriority.NORMAL) }
    var repeatType  by remember { mutableStateOf(initial?.repeatType ?: RepeatType.NONE) }
    var withVib     by remember { mutableStateOf(initial?.withVibration ?: true) }
    var showRepeat  by remember { mutableStateOf(false) }

    val initCal = remember {
        Calendar.getInstance().also { c ->
            if (initial != null) c.timeInMillis = initial.triggerMillis
            else { c.add(Calendar.HOUR_OF_DAY, 1); c.set(Calendar.MINUTE, 0) }
        }
    }
    var selYear   by remember { mutableStateOf(initCal.get(Calendar.YEAR)) }
    var selMonth  by remember { mutableStateOf(initCal.get(Calendar.MONTH)) }
    var selDay    by remember { mutableStateOf(initCal.get(Calendar.DAY_OF_MONTH)) }
    var selHour   by remember { mutableStateOf(initCal.get(Calendar.HOUR_OF_DAY)) }
    var selMinute by remember { mutableStateOf(initCal.get(Calendar.MINUTE)) }

    fun buildTrigger(): Long {
        val c = Calendar.getInstance()
        c.set(selYear, selMonth, selDay, selHour, selMinute, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    val dateStr = remember(selYear, selMonth, selDay) {
        val c = Calendar.getInstance().also { it.set(selYear, selMonth, selDay) }
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(c.time)
    }
    val timeStr = remember(selHour, selMinute) { formatTime(selHour, selMinute) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.97f)
                .fillMaxHeight(0.93f)
                .clip(RoundedCornerShape(4.dp))
                .background(RmWhite)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // Header
                Box(
                    modifier = Modifier.fillMaxWidth().background(RmTeal)
                        .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.ArrowBack, contentDescription = null, tint = RmWhite)
                        }
                        Text(
                            if (initial == null) "New Reminder" else "Edit Reminder",
                            color = RmWhite, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Form body
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {

                    // Title field
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .background(RmTeal.copy(.04f))
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.MicNone, contentDescription = null, tint = RmTextSub, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            TextField(
                                value = title, onValueChange = { title = it },
                                placeholder = { Text("Title", fontSize = 18.sp, color = RmTextSub.copy(.5f)) },
                                modifier = Modifier.weight(1f), singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    focusedTextColor = RmText, unfocusedTextColor = RmText, cursorColor = RmTeal
                                ),
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontSize = 18.sp, fontWeight = FontWeight.Medium
                                )
                            )
                        }
                    }
                    Divider(color = RmDivider)

                    // Description field
                    Box(modifier = Modifier.fillMaxWidth().padding(start = 48.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                        TextField(
                            value = description, onValueChange = { description = it },
                            placeholder = { Text("Description", fontSize = 15.sp, color = RmTextSub.copy(.5f)) },
                            modifier = Modifier.fillMaxWidth(), maxLines = 3,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = RmText, unfocusedTextColor = RmText, cursorColor = RmTeal
                            )
                        )
                    }
                    Divider(color = RmDivider)
                    Spacer(Modifier.height(8.dp))

                    // Date
                    RmFormRow(Icons.Default.CalendarToday, "Date", dateStr) {
                        DatePickerDialog(context, { _, y, m, d ->
                            selYear = y; selMonth = m; selDay = d
                        }, selYear, selMonth, selDay).show()
                    }
                    Divider(color = RmDivider, modifier = Modifier.padding(start = 52.dp))

                    // Time
                    RmFormRow(Icons.Default.AccessTime, "Time", timeStr) {
                        TimePickerDialog(context, { _, h, m ->
                            selHour = h; selMinute = m
                        }, selHour, selMinute, false).show()
                    }
                    Divider(color = RmDivider, modifier = Modifier.padding(start = 52.dp))

                    // Repeat
                    RmFormRow(Icons.Default.Repeat, "Repeat", repeatType.label) { showRepeat = true }
                    Divider(color = RmDivider, modifier = Modifier.padding(start = 52.dp))

                    // End Date (display only for now)
                    RmFormRow(Icons.Default.Event, "End Date", "Forever") {}
                    Divider(color = RmDivider, modifier = Modifier.padding(start = 52.dp))

                    // Adv. reminder placeholder
                    RmFormRow(Icons.Default.NotificationAdd, "Adv. reminder", "None") {}
                    Divider(color = RmDivider, modifier = Modifier.padding(start = 52.dp))

                    // Vibration switch
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { withVib = !withVib }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (withVib) Icons.Default.Vibration else Icons.Default.NotificationsOff,
                            contentDescription = null, tint = RmTextSub, modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(16.dp))
                        Text("Vibration", fontSize = 15.sp, color = RmText, modifier = Modifier.weight(1f))
                        Switch(
                            checked = withVib, onCheckedChange = { withVib = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = RmTeal,
                                checkedTrackColor = RmTeal.copy(.3f)
                            )
                        )
                    }
                    Divider(color = RmDivider)

                    // Priority stars
                    Spacer(Modifier.height(16.dp))
                    Text("  Priority", fontSize = 12.sp, color = RmTextSub, modifier = Modifier.padding(start = 16.dp))
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ReminderPriority.values().forEach { p ->
                            val sel = priority == p
                            Column(
                                modifier = Modifier.weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (sel) p.color.copy(.12f) else RmBg)
                                    .border(1.dp, if (sel) p.color else RmDivider, RoundedCornerShape(10.dp))
                                    .clickable { priority = p }
                                    .padding(vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    if (sel) Icons.Default.Star else Icons.Default.StarBorder,
                                    contentDescription = null, tint = p.color, modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    p.label, fontSize = 10.sp,
                                    color = if (sel) p.color else RmTextSub,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }

                // Save button
                Box(
                    modifier = Modifier.fillMaxWidth().background(RmWhite)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Button(
                        onClick = {
                            if (title.isBlank()) return@Button
                            onSave(
                                ReminderItem(
                                    id = initial?.id ?: 0,
                                    title = title.trim(),
                                    description = description.trim(),
                                    triggerMillis = buildTrigger(),
                                    repeatType = repeatType,
                                    priority = priority,
                                    withVibration = withVib
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RmTeal)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = RmWhite)
                        Spacer(Modifier.width(8.dp))
                        Text("Save Reminder", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = RmWhite)
                    }
                }
            }
        }
    }

    if (showRepeat) {
        RepeatSelectionDialog(
            current = repeatType,
            onSelect = { repeatType = it; showRepeat = false },
            onDismiss = { showRepeat = false }
        )
    }
}

// ─── Form row ──────────────────────────────────────────────────────────────────
@Composable
private fun RmFormRow(
    icon: ImageVector,
    label: String,
    value: String,
    valueColor: Color = RmTeal,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = RmTextSub, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, fontSize = 15.sp, color = RmText, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, color = valueColor, fontWeight = FontWeight.Medium)
    }
}

// ─── Repeat selector dialog ────────────────────────────────────────────────────
@Composable
private fun RepeatSelectionDialog(
    current: RepeatType,
    onSelect: (RepeatType) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = RmWhite),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column {
                RepeatType.values().forEach { rt ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(rt) }
                            .padding(horizontal = 20.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(rt.label, fontSize = 15.sp, color = RmText, modifier = Modifier.weight(1f))
                        if (current == rt)
                            Icon(Icons.Default.Check, contentDescription = null, tint = RmTeal, modifier = Modifier.size(18.dp))
                        if (rt == RepeatType.SELECTED_WEEKDAYS || rt == RepeatType.MONTHLY_ON || rt == RepeatType.CUSTOM)
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = RmTextSub, modifier = Modifier.size(18.dp))
                    }
                    if (rt != RepeatType.values().last())
                        Divider(color = RmDivider, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
        }
    }
}

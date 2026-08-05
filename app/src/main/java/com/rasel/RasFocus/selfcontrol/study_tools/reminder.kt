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
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

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

// Custom unit for CUSTOM repeat
enum class CustomRepeatUnit(val label: String, val millis: Long) {
    MINUTES("Minutes", 60_000L),
    HOURS("Hours", 3_600_000L),
    DAYS("Days", 86_400_000L),
    MONTHS("Months", 2_592_000_000L),   // ~30 days
    YEARS("Years", 31_536_000_000L)     // ~365 days
}

// Duration for ringtone
enum class RingtoneDuration(val label: String) {
    ONE_MINUTE("1 Minute"),
    CONTINUOUS("Continuous")
}

// ── Data model ─────────────────────────────────────────────────────────────────
data class ReminderItem(
    val id: Int,
    val title: String,
    val description: String = "",
    val triggerMillis: Long,
    val repeatType: RepeatType = RepeatType.NONE,
    val customRepeatAmount: Int = 1,
    val customRepeatUnit: CustomRepeatUnit = CustomRepeatUnit.DAYS,
    val priority: ReminderPriority = ReminderPriority.NORMAL,
    val withVibration: Boolean = true,
    val ringtoneDuration: RingtoneDuration = RingtoneDuration.ONE_MINUTE,
    val ringtoneUriString: String = "", // empty = system default alarm
    val isCompleted: Boolean = false,
    val isActive: Boolean = true
)

// ── Storage Helpers ────────────────────────────────────────────────────────────
object ReminderStorage {
    private const val PREFS_NAME = "reminder_prefs"
    private const val KEY_LIST = "reminders_list"

    fun save(context: Context, items: List<ReminderItem>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LIST, com.google.gson.Gson().toJson(items)).apply()
    }

    fun load(context: Context): List<ReminderItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<ReminderItem>>() {}.type
            com.google.gson.Gson().fromJson(json, type)
        } catch (_: Exception) { emptyList() }
    }
}

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

/** Get interval millis for a repeat type */
fun repeatIntervalMillis(item: ReminderItem): Long? {
    return when (item.repeatType) {
        RepeatType.NONE            -> null
        RepeatType.EVERY_HOUR      -> 3_600_000L
        RepeatType.EVERY_DAY       -> 86_400_000L
        RepeatType.EVERY_WEEK      -> 7 * 86_400_000L
        RepeatType.EVERY_MONTH     -> 30 * 86_400_000L
        RepeatType.EVERY_YEAR      -> 365 * 86_400_000L
        RepeatType.SELECTED_WEEKDAYS -> 86_400_000L // daily default, days filtered at schedule
        RepeatType.MONTHLY_ON      -> 30 * 86_400_000L
        RepeatType.CUSTOM          -> item.customRepeatAmount * item.customRepeatUnit.millis
    }
}

// ── MediaPlayer & Vibrator singleton for alarm ────────────────────────────────
object ReminderAlarmPlayer {
    private var player: MediaPlayer? = null
    private var stopTimer: java.util.Timer? = null
    private var vibrator: Vibrator? = null
    private var vibratorManager: VibratorManager? = null

    fun play(context: Context, ringtoneUri: Uri, durationEnum: RingtoneDuration, withVib: Boolean) {
        stop()
        
        if (withVib) {
            try {
                val pattern = longArrayOf(0, 1000, 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vibratorManager = vm
                    vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    vibrator = v
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        v.vibrate(VibrationEffect.createWaveform(pattern, 0))
                    else
                        @Suppress("DEPRECATION") v.vibrate(pattern, 0)
                }
            } catch (_: Exception) {}
        }

        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, ringtoneUri)
                // ONE_MINUTE: loop করে ১ মিনিট বাজবে, তারপর auto-stop
                // CONTINUOUS: ইউজার dismiss না করা পর্যন্ত loop চলবে
                isLooping = true
                prepare()
                start()
            }
            if (durationEnum == RingtoneDuration.ONE_MINUTE) {
                stopTimer = java.util.Timer()
                stopTimer?.schedule(object : java.util.TimerTask() {
                    override fun run() { stop() }
                }, 60_000L)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        stopTimer?.cancel()
        stopTimer = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                vibratorManager?.defaultVibrator?.cancel()
            }
            vibrator?.cancel()
        } catch (_: Exception) {}
        vibrator = null
        vibratorManager = null
        try {
            player?.run { if (isPlaying) stop(); release() }
        } catch (_: Exception) {}
        player = null
    }
}

// ── Notification channel ───────────────────────────────────────────────────────
fun ensureReminderChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val ch = NotificationChannel(RM_CHANNEL_ID, RM_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 200, 500)
            // Sound is played via MediaPlayer in receiver, not through channel
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }
}

// ── BroadcastReceiver ──────────────────────────────────────────────────────────
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label            = intent.getStringExtra("label") ?: "Reminder"
        val description      = intent.getStringExtra("description") ?: ""
        val withVib          = intent.getBooleanExtra("withVibration", true)
        val notifId          = intent.getIntExtra("notifId", System.currentTimeMillis().toInt())
        val ringtoneUriStr   = intent.getStringExtra("ringtoneUri") ?: ""
        val durationStr      = intent.getStringExtra("ringtoneDuration") ?: RingtoneDuration.ONE_MINUTE.name
        val repeatTypeStr    = intent.getStringExtra("repeatType") ?: RepeatType.NONE.name
        val customAmount     = intent.getIntExtra("customAmount", 1)
        val customUnitStr    = intent.getStringExtra("customUnit") ?: CustomRepeatUnit.DAYS.name
        val triggerMillis    = intent.getLongExtra("triggerMillis", System.currentTimeMillis())
        val priorityStr      = intent.getStringExtra("priority") ?: "NORMAL"

        val duration   = try { RingtoneDuration.valueOf(durationStr) } catch (_: Exception) { RingtoneDuration.ONE_MINUTE }
        val repeatType = try { RepeatType.valueOf(repeatTypeStr) } catch (_: Exception) { RepeatType.NONE }
        val customUnit = try { CustomRepeatUnit.valueOf(customUnitStr) } catch (_: Exception) { CustomRepeatUnit.DAYS }

        ensureReminderChannel(context)

        // 1. Play ringtone and vibrate via singleton
        val ringtoneUri = when {
            ringtoneUriStr.isNotEmpty() -> android.net.Uri.parse(ringtoneUriStr)
            else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }
        ReminderAlarmPlayer.play(context, ringtoneUri, duration, withVib)

        // 3. Build repeat label for popup
        val repeatLabel = when (repeatType) {
            RepeatType.NONE    -> ""
            RepeatType.CUSTOM  -> "Every $customAmount ${customUnit.label}"
            else               -> repeatType.label
        }

        // 4. Launch full-screen alarm popup Activity
        val popupIntent = Intent(context, ReminderAlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("title", label)
            putExtra("description", description)
            putExtra("repeatLabel", repeatLabel)
            putExtra("priority", priorityStr)
            putExtra("notifId", notifId)
            putExtra("withVibration", withVib)
            putExtra("ringtoneUri", ringtoneUriStr)
            putExtra("ringtoneDuration", durationStr)
        }
        context.startActivity(popupIntent)

        // 5. Also show a notification (for when screen is off / app killed)
        val stopIntent = Intent(context, StopRingtoneReceiver::class.java).let {
            PendingIntent.getBroadcast(context, notifId + 10000, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        
        val contentIntent = PendingIntent.getActivity(
            context, notifId + 40000,
            Intent(context, StudyToolsActivity::class.java).apply {
                putExtra("open_tab", "reminder")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(notifId, NotificationCompat.Builder(context, RM_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("⏰ $label")
                .setContentText(if (description.isNotBlank()) description else "Tap to view")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(contentIntent)
                .setFullScreenIntent(
                    PendingIntent.getActivity(
                        context, notifId + 20000, popupIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    ), true
                )
                .setSound(null)
                .addAction(android.R.drawable.ic_media_pause, "Snooze 5 min",
                    PendingIntent.getBroadcast(context, notifId + 30000,
                        Intent(context, SnoozeReminderReceiver::class.java).apply {
                            putExtra("notifId", notifId)
                            putExtra("label", label)
                            putExtra("withVibration", withVib)
                            putExtra("ringtoneUri", ringtoneUriStr)
                            putExtra("ringtoneDuration", durationStr)
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .addAction(android.R.drawable.ic_delete, "Dismiss", stopIntent)
                .setAutoCancel(true)
                .build())

        // 6. Schedule next repeat alarm & update storage
        val fakeItem = ReminderItem(
            id = notifId,
            title = label,
            description = description,
            triggerMillis = triggerMillis,
            repeatType = repeatType,
            customRepeatAmount = customAmount,
            customRepeatUnit = customUnit,
            withVibration = withVib,
            ringtoneDuration = duration,
            ringtoneUriString = ringtoneUriStr,
            priority = try { ReminderPriority.valueOf(priorityStr) } catch (_: Exception) { ReminderPriority.NORMAL }
        )
        val interval = repeatIntervalMillis(fakeItem)
        if (interval != null && interval > 0) {
            val nextTrigger = triggerMillis + interval
            scheduleReminderAlarmFull(context, fakeItem.copy(triggerMillis = nextTrigger))
            
            val items = ReminderStorage.load(context).toMutableList()
            val idx = items.indexOfFirst { it.id == notifId }
            if (idx != -1) {
                items[idx] = items[idx].copy(triggerMillis = nextTrigger, isActive = true)
                ReminderStorage.save(context, items)
            }
        } else {
            val items = ReminderStorage.load(context).toMutableList()
            val idx = items.indexOfFirst { it.id == notifId }
            if (idx != -1) {
                items[idx] = items[idx].copy(isCompleted = true, isActive = false)
                ReminderStorage.save(context, items)
            }
        }
    }
}

// Stop ringtone receiver
class StopRingtoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderAlarmPlayer.stop()
    }
}

// Snooze reminder receiver (from notification action)
class SnoozeReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderAlarmPlayer.stop()
        val notifId     = intent.getIntExtra("notifId", 0)
        val label       = intent.getStringExtra("label") ?: "Reminder"
        val withVib     = intent.getBooleanExtra("withVibration", true)
        val ringtoneUri = intent.getStringExtra("ringtoneUri") ?: ""
        val durationStr = intent.getStringExtra("ringtoneDuration") ?: RingtoneDuration.ONE_MINUTE.name
        val duration    = try { RingtoneDuration.valueOf(durationStr) } catch (_: Exception) { RingtoneDuration.ONE_MINUTE }
        // Cancel existing notification
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notifId)
        // Schedule snooze 5 min later
        val snoozeItem = ReminderItem(
            id = notifId + 50000,
            title = "\uD83D\uDD14 $label (Snoozed)",
            triggerMillis = System.currentTimeMillis() + 5 * 60_000L,
            repeatType = RepeatType.NONE,
            withVibration = withVib,
            ringtoneDuration = duration,
            ringtoneUriString = ringtoneUri
        )
        scheduleReminderAlarmFull(context, snoozeItem)
    }
}

// ── AlarmManager helpers ───────────────────────────────────────────────────────
fun scheduleReminderAlarm(context: Context, reminderId: Int, label: String, triggerMillis: Long, withVibration: Boolean) {
    // Minimal overload kept for compatibility
    scheduleReminderAlarmFull(context, ReminderItem(
        id = reminderId, title = label, triggerMillis = triggerMillis, withVibration = withVibration
    ))
}

fun scheduleReminderAlarmFull(context: Context, item: ReminderItem) {
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
        putExtra("label", item.title)
        putExtra("description", item.description)
        putExtra("priority", item.priority.name)
        putExtra("withVibration", item.withVibration)
        putExtra("notifId", item.id)
        putExtra("ringtoneUri", item.ringtoneUriString)
        putExtra("ringtoneDuration", item.ringtoneDuration.name)
        putExtra("repeatType", item.repeatType.name)
        putExtra("customAmount", item.customRepeatAmount)
        putExtra("customUnit", item.customRepeatUnit.name)
        putExtra("triggerMillis", item.triggerMillis)
    }
    val pi = PendingIntent.getBroadcast(
        context, item.id, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val triggerAt = SystemClock.elapsedRealtime() + (item.triggerMillis - System.currentTimeMillis()).coerceAtLeast(1000L)
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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    var activeTab      by remember { mutableStateOf(0) }
    var selectedFilter by remember { mutableStateOf("All") }
    var reminders      by remember { mutableStateOf(listOf<ReminderItem>()) }
    var nextId         by remember { mutableStateOf(9200) }
    var showAddDialog  by remember { mutableStateOf(false) }
    var editItem       by remember { mutableStateOf<ReminderItem?>(null) }
    var showSettings   by remember { mutableStateOf(false) }
    var showQuickDlg   by remember { mutableStateOf(false) }

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
                    // Quick Reminder shortcut
                    IconButton(onClick = { showQuickDlg = true }) {
                        Icon(Icons.Default.Bolt, contentDescription = "Quick Reminder", tint = RmWhite)
                    }
                    IconButton(onClick = { addReminderHomeShortcut(context) }) {
                        Icon(Icons.Default.AddToHomeScreen, contentDescription = "Shortcut", tint = RmWhite)
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = RmWhite)
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
                            onEdit = { editItem = reminder; showAddDialog = true },
                            onToggleActive = {
                                val nowActive = !reminder.isActive
                                if (nowActive) {
                                    // Re-schedule the alarm
                                    ensureReminderChannel(context)
                                    scheduleReminderAlarmFull(context, reminder.copy(isActive = true))
                                } else {
                                    // Cancel the alarm
                                    cancelReminderAlarm(context, reminder.id)
                                }
                                reminders = reminders.map {
                                    if (it.id == reminder.id) it.copy(isActive = nowActive) else it
                                }
                                ReminderStorage.save(context, reminders)
                            }
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
                    scheduleReminderAlarmFull(context, item)
                } else {
                    val newItem = item.copy(id = nextId++)
                    reminders = reminders + newItem
                    scheduleReminderAlarmFull(context, newItem)
                }
                showAddDialog = false
            }
        )
    }

    if (showSettings) {
        ReminderSettingsDialog(onDismiss = { showSettings = false })
    }

    if (showQuickDlg) {
        QuickReminderDialog(
            onDismiss = { showQuickDlg = false },
            onSet = { minutes, label ->
                scheduleQuickReminder(context, minutes, label)
                android.widget.Toast.makeText(
                    context,
                    "⏰ Reminder set for ${if (minutes < 60) "$minutes min" else "${minutes / 60}h ${minutes % 60}min"}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                showQuickDlg = false
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
    onEdit: () -> Unit,
    onToggleActive: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    // Active / Inactive colours
    val activeColor   = Color(0xFF43A047)   // green
    val inactiveColor = Color(0xFFBDBDBD)   // grey

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (reminder.isActive) RmWhite else Color(0xFFF5F5F5))
                .clickable { onEdit() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    reminder.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = if (reminder.isCompleted || !reminder.isActive) RmTextSub else RmText,
                    textDecoration = if (reminder.isCompleted) TextDecoration.LineThrough else null,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatDateTime(reminder.triggerMillis), fontSize = 12.sp, color = RmTextSub)
                    if (reminder.repeatType != RepeatType.NONE) {
                        val repeatLabel = if (reminder.repeatType == RepeatType.CUSTOM) {
                            "Every ${reminder.customRepeatAmount} ${reminder.customRepeatUnit.label}"
                        } else reminder.repeatType.label
                        Text("  •  $repeatLabel", fontSize = 12.sp, color = RmTextSub)
                    }
                }
                Spacer(Modifier.height(6.dp))
                // ── Double-button: Activate / Deactivate ──
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // ACTIVATE button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (reminder.isActive) activeColor else activeColor.copy(alpha = 0.12f)
                            )
                            .border(
                                1.dp,
                                if (reminder.isActive) activeColor else activeColor.copy(alpha = 0.4f),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable(enabled = !reminder.isActive) { onToggleActive() }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Activate",
                                tint = if (reminder.isActive) RmWhite else activeColor,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                "Active",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (reminder.isActive) RmWhite else activeColor
                            )
                        }
                    }
                    // DEACTIVATE button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (!reminder.isActive) inactiveColor else inactiveColor.copy(alpha = 0.12f)
                            )
                            .border(
                                1.dp,
                                if (!reminder.isActive) inactiveColor else inactiveColor.copy(alpha = 0.4f),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable(enabled = reminder.isActive) { onToggleActive() }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Pause,
                                contentDescription = "Deactivate",
                                tint = if (!reminder.isActive) RmWhite else inactiveColor,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                "Inactive",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (!reminder.isActive) RmWhite else inactiveColor
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
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

    // Custom repeat
    var customAmount by remember { mutableStateOf(initial?.customRepeatAmount?.toString() ?: "1") }
    var customUnit   by remember { mutableStateOf(initial?.customRepeatUnit ?: CustomRepeatUnit.DAYS) }
    var unitExpanded by remember { mutableStateOf(false) }

    // Ringtone
    var ringtoneDuration by remember { mutableStateOf(initial?.ringtoneDuration ?: RingtoneDuration.ONE_MINUTE) }
    var ringtoneUriStr   by remember { mutableStateOf(initial?.ringtoneUriString ?: "") }
    var ringtoneName     by remember {
        val defaultLabel = "Default Alarm"
        mutableStateOf(
            if (initial?.ringtoneUriString.isNullOrEmpty()) defaultLabel
            else {
                try {
                    val rm = RingtoneManager.getRingtone(context, Uri.parse(initial!!.ringtoneUriString))
                    rm?.getTitle(context) ?: defaultLabel
                } catch (_: Exception) { defaultLabel }
            }
        )
    }

    // Ringtone picker launcher
    val ringtoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (uri != null) {
            ringtoneUriStr = uri.toString()
            try {
                val rm = RingtoneManager.getRingtone(context, uri)
                ringtoneName = rm?.getTitle(context) ?: "Custom"
            } catch (_: Exception) { ringtoneName = "Custom" }
        }
    }

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

                    // Custom repeat sub-row (only when CUSTOM is selected)
                    if (repeatType == RepeatType.CUSTOM) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(RmTeal.copy(.04f))
                                .padding(start = 52.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Every:", color = RmText, fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
                            OutlinedTextField(
                                value = customAmount,
                                onValueChange = { if (it.all(Char::isDigit) && it.length <= 4) customAmount = it },
                                modifier = Modifier.width(72.dp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontSize = 14.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = RmTeal,
                                    unfocusedBorderColor = RmDivider
                                )
                            )
                            Spacer(Modifier.width(8.dp))
                            // Unit picker
                            Box {
                                OutlinedButton(
                                    onClick = { unitExpanded = true },
                                    border = androidx.compose.foundation.BorderStroke(1.dp, RmTeal)
                                ) {
                                    Text(customUnit.label, color = RmTeal, fontSize = 14.sp)
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = RmTeal)
                                }
                                DropdownMenu(expanded = unitExpanded, onDismissRequest = { unitExpanded = false }) {
                                    CustomRepeatUnit.values().forEach { u ->
                                        DropdownMenuItem(
                                            text = { Text(u.label) },
                                            onClick = { customUnit = u; unitExpanded = false }
                                        )
                                    }
                                }
                            }
                        }
                        Divider(color = RmDivider, modifier = Modifier.padding(start = 52.dp))
                    }

                    // ── Ringtone row ──
                    RmFormRow(Icons.Default.MusicNote, "Ringtone", ringtoneName) {
                        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Ringtone")
                            if (ringtoneUriStr.isNotEmpty()) {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(ringtoneUriStr))
                            } else {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                            }
                        }
                        ringtoneLauncher.launch(intent)
                    }
                    Divider(color = RmDivider, modifier = Modifier.padding(start = 52.dp))

                    // ── Ringtone Duration row ──
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Timer, contentDescription = null, tint = RmTextSub, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(16.dp))
                        Text("Ringtone Duration", fontSize = 15.sp, color = RmText, modifier = Modifier.weight(1f))
                        // Toggle chips
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RingtoneDuration.values().forEach { dur ->
                                val sel = ringtoneDuration == dur
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(if (sel) RmTeal else RmBg)
                                        .border(1.dp, if (sel) RmTeal else RmDivider, RoundedCornerShape(16.dp))
                                        .clickable { ringtoneDuration = dur }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(dur.label, fontSize = 12.sp,
                                        color = if (sel) RmWhite else RmTextSub,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                    Divider(color = RmDivider, modifier = Modifier.padding(start = 52.dp))

                    // End Date
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
                            val parsedAmount = customAmount.toIntOrNull()?.coerceAtLeast(1) ?: 1
                            onSave(
                                ReminderItem(
                                    id = initial?.id ?: 0,
                                    title = title.trim(),
                                    description = description.trim(),
                                    triggerMillis = buildTrigger(),
                                    repeatType = repeatType,
                                    customRepeatAmount = parsedAmount,
                                    customRepeatUnit = customUnit,
                                    priority = priority,
                                    withVibration = withVib,
                                    ringtoneDuration = ringtoneDuration,
                                    ringtoneUriString = ringtoneUriStr
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
        Text(value, fontSize = 14.sp, color = valueColor, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp))
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

// ═════════════════════════════════════════════════════════════════════════════
// Reminder Settings Dialog
// ═════════════════════════════════════════════════════════════════════════════
@Composable
fun ReminderSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    // Persisted settings via SharedPreferences
    val prefs = remember { context.getSharedPreferences("reminder_settings", Context.MODE_PRIVATE) }
    var popupEnabled       by remember { mutableStateOf(prefs.getBoolean("popup_enabled", true)) }
    var snoozeDuration     by remember { mutableStateOf(prefs.getInt("snooze_duration", 5)) }
    var defaultRingDuration by remember { mutableStateOf(
        try { RingtoneDuration.valueOf(prefs.getString("ring_duration", "ONE_MINUTE")!!) }
        catch (_: Exception) { RingtoneDuration.ONE_MINUTE }
    ) }
    var vibrationDefault   by remember { mutableStateOf(prefs.getBoolean("vib_default", true)) }
    var showSnoozeMenu     by remember { mutableStateOf(false) }

    fun save() {
        prefs.edit()
            .putBoolean("popup_enabled", popupEnabled)
            .putInt("snooze_duration", snoozeDuration)
            .putString("ring_duration", defaultRingDuration.name)
            .putBoolean("vib_default", vibrationDefault)
            .apply()
    }

    Dialog(
        onDismissRequest = { save(); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(16.dp))
                .background(RmWhite)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // Header
                Box(
                    modifier = Modifier.fillMaxWidth().background(RmTeal)
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { save(); onDismiss() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = null, tint = RmWhite)
                        }
                        Text(
                            "Settings", color = RmWhite,
                            fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {

                    // Section: Notifications
                    SettingsSectionHeader("Sounds & Notifications")

                    SettingsRowSwitch(
                        icon = Icons.Default.NotificationsActive,
                        title = "Popup notification",
                        subtitle = "Show full-screen alarm when reminder fires",
                        checked = popupEnabled,
                        onCheckedChange = { popupEnabled = it }
                    )
                    Divider(color = RmDivider, modifier = Modifier.padding(start = 56.dp))

                    SettingsRowSwitch(
                        icon = Icons.Default.Vibration,
                        title = "Vibration (default)",
                        subtitle = "Default vibration for new reminders",
                        checked = vibrationDefault,
                        onCheckedChange = { vibrationDefault = it }
                    )
                    Divider(color = RmDivider, modifier = Modifier.padding(start = 56.dp))

                    // Ring duration default
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Timer, contentDescription = null,
                            tint = RmTextSub, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Default ring duration", fontSize = 15.sp, color = RmText)
                            Text("How long ringtone plays by default", fontSize = 12.sp, color = RmTextSub)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RingtoneDuration.values().forEach { dur ->
                                val sel = defaultRingDuration == dur
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (sel) RmTeal else RmBg)
                                        .border(1.dp, if (sel) RmTeal else RmDivider, RoundedCornerShape(12.dp))
                                        .clickable { defaultRingDuration = dur }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(dur.label, fontSize = 11.sp,
                                        color = if (sel) RmWhite else RmTextSub,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                    Divider(color = RmDivider, modifier = Modifier.padding(start = 56.dp))

                    // Section: Behaviour
                    SettingsSectionHeader("Behaviour")

                    // Snooze duration
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Snooze, contentDescription = null,
                            tint = RmTextSub, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Default snooze duration", fontSize = 15.sp, color = RmText)
                            Text("How many minutes to snooze", fontSize = 12.sp, color = RmTextSub)
                        }
                        Box {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(RmBg)
                                    .clickable { showSnoozeMenu = true }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("$snoozeDuration min", color = RmTeal, fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = RmTeal)
                            }
                            DropdownMenu(expanded = showSnoozeMenu, onDismissRequest = { showSnoozeMenu = false }) {
                                listOf(1, 2, 5, 10, 15, 20, 30).forEach { mins ->
                                    DropdownMenuItem(
                                        text = { Text("$mins minutes") },
                                        onClick = { snoozeDuration = mins; showSnoozeMenu = false }
                                    )
                                }
                            }
                        }
                    }
                    Divider(color = RmDivider, modifier = Modifier.padding(start = 56.dp))

                    // Section: Info
                    SettingsSectionHeader("Help")

                    SettingsRowInfo(
                        icon = Icons.Default.Info,
                        title = "Reminders not working?",
                        subtitle = "Grant exact alarm & notification permissions",
                        actionLabel = "Permissions",
                        onAction = {
                            try {
                                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = android.net.Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }
                    )
                    Divider(color = RmDivider, modifier = Modifier.padding(start = 56.dp))

                    SettingsRowInfo(
                        icon = Icons.Default.Bolt,
                        title = "Quick Reminder tile",
                        subtitle = "Add 'Quick Reminder' to notification shade (action centre)",
                        actionLabel = "Guide",
                        onAction = {
                            android.widget.Toast.makeText(
                                context,
                                "Pull down notification shade → Edit → find 'Quick Reminder' → drag to top",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                    Spacer(Modifier.height(24.dp))
                }

                // Save button
                Box(modifier = Modifier.fillMaxWidth().background(RmWhite)
                    .padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Button(
                        onClick = { save(); onDismiss() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RmTeal)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = RmWhite)
                        Spacer(Modifier.width(8.dp))
                        Text("Save Settings", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = RmWhite)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        title,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = RmTeal,
        modifier = Modifier
            .fillMaxWidth()
            .background(RmBg)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun SettingsRowSwitch(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = RmTextSub, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = RmText)
            Text(subtitle, fontSize = 12.sp, color = RmTextSub)
        }
        Switch(
            checked = checked, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = RmTeal, checkedTrackColor = RmTeal.copy(0.3f))
        )
    }
}

@Composable
private fun SettingsRowInfo(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = RmTextSub, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = RmText)
            Text(subtitle, fontSize = 12.sp, color = RmTextSub)
        }
        TextButton(onClick = onAction) {
            Text(actionLabel, color = RmTeal, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

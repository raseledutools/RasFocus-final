package com.rasel.RasFocus.selfcontrol.study_tools

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

// ─── Color Palette (matches study_tools.kt) ──────────────────────────────────
private val RmBgDeep       = Color(0xFF0D0D1A)
private val RmBgCard       = Color(0xFF1A1A2E)
private val RmBgCard2      = Color(0xFF16213E)
private val RmAccentBlue   = Color(0xFF4FACFE)
private val RmAccentCyan   = Color(0xFF00F2FE)
private val RmAccentRed    = Color(0xFFFF6B6B)
private val RmAccentOrange = Color(0xFFFF8E53)
private val RmAccentPurple = Color(0xFFA18CD1)
private val RmAccentPink   = Color(0xFFFBC2EB)
private val RmAccentGreen  = Color(0xFF43E97B)
private val RmAccentTeal   = Color(0xFF38F9D7)
private val RmAccentYellow = Color(0xFFFFD93D)
private val RmTextWhite    = Color(0xFFFFFFFF)
private val RmTextMuted    = Color(0xFF8888AA)

private const val RM_CHANNEL_ID   = "rasfocus_reminder_channel"
private const val RM_CHANNEL_NAME = "RasFocus Reminders"

// ─── Data model ──────────────────────────────────────────────────────────────
data class ReminderItem(
    val id: Int,
    val label: String,
    val triggerMillis: Long,
    val withVibration: Boolean,
    val isActive: Boolean = true
)

// ─── Notification Channel setup ──────────────────────────────────────────────
fun ensureReminderChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            RM_CHANNEL_ID,
            RM_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "RasFocus Alarm Reminders"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}

// ─── BroadcastReceiver for alarm ─────────────────────────────────────────────
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label         = intent.getStringExtra("label") ?: "Reminder"
        val withVibration = intent.getBooleanExtra("withVibration", true)
        val notifId       = intent.getIntExtra("notifId", System.currentTimeMillis().toInt())

        ensureReminderChannel(context)

        // ── Vibrate ──
        if (withVibration) {
            try {
                val pattern = longArrayOf(0, 700, 200, 700, 200, 700)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
                    } else {
                        @Suppress("DEPRECATION")
                        v.vibrate(pattern, -1)
                    }
                }
            } catch (_: Exception) {}
        }

        // ── Notification ──
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(context, RM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("⏰ RasFocus Reminder")
            .setContentText(label)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()
        nm.notify(notifId, notif)
    }
}

// ─── Schedule / Cancel alarm helpers ─────────────────────────────────────────
fun scheduleReminderAlarm(
    context: Context,
    reminderId: Int,
    label: String,
    delayMillis: Long,
    withVibration: Boolean
) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
        putExtra("label", label)
        putExtra("withVibration", withVibration)
        putExtra("notifId", reminderId)
    }
    val pi = PendingIntent.getBroadcast(
        context, reminderId, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val triggerAt = SystemClock.elapsedRealtime() + delayMillis
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        } else {
            alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        }
    } catch (_: Exception) {
        alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
    }
}

fun cancelReminderAlarm(context: Context, reminderId: Int) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intent = Intent(context, ReminderAlarmReceiver::class.java)
    val pi = PendingIntent.getBroadcast(
        context, reminderId, intent,
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    )
    pi?.let { alarmManager.cancel(it) }
}

// ─── Add Home Shortcut ────────────────────────────────────────────────────────
fun addReminderHomeShortcut(context: Context) {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return
    val intent = Intent(context, StudyToolsActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        putExtra("open_reminder", true)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    val shortcut = ShortcutInfoCompat.Builder(context, "reminder_shortcut_st")
        .setShortLabel("Reminder")
        .setLongLabel("RasFocus Reminder")
        .setIcon(IconCompat.createWithResource(context, android.R.drawable.ic_lock_idle_alarm))
        .setIntent(intent)
        .build()
    ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
}

// ─────────────────────────────────────────────────────────────────────────────
// Reminder Card  (placed in StudyToolsMain)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ReminderCard() {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    var reminders  by remember { mutableStateOf(listOf<ReminderItem>()) }
    var nextId     by remember { mutableStateOf(9000) }

    // Pulsing bell animation
    val infiniteTransition = rememberInfiniteTransition(label = "bell")
    val bellScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.14f,
        animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bell_scale"
    )

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(14.dp, RoundedCornerShape(22.dp)),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF1F1040), Color(0xFF0F1B35), RmBgDeep)),
                        RoundedCornerShape(22.dp)
                    )
                    .border(
                        1.dp,
                        Brush.horizontalGradient(listOf(RmAccentPurple.copy(.4f), RmAccentOrange.copy(.3f))),
                        RoundedCornerShape(22.dp)
                    )
            ) {
                Column(modifier = Modifier.padding(18.dp)) {

                    // ── Header ──────────────────────────────────────────────────
                    Row(verticalAlignment = Alignment.CenterVertically) {

                        // Bell glow
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .background(
                                    Brush.radialGradient(listOf(RmAccentOrange.copy(.28f), Color.Transparent)),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Alarm,
                                contentDescription = null,
                                tint = RmAccentOrange,
                                modifier = Modifier.size(28.dp).scale(bellScale)
                            )
                        }
                        Spacer(Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text("⏰ Reminder", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = RmTextWhite)
                            Text(
                                "${reminders.count { it.isActive }} টি active reminder",
                                fontSize = 12.sp, color = RmTextMuted
                            )
                        }

                        // Add to Home Screen button
                        IconButton(
                            onClick = { addReminderHomeShortcut(context) },
                            modifier = Modifier
                                .size(38.dp)
                                .background(RmAccentBlue.copy(.12f), CircleShape)
                        ) {
                            Icon(
                                Icons.Default.AddToHomeScreen,
                                contentDescription = "Homescreen shortcut",
                                tint = RmAccentBlue,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))

                        // Set Alarm button
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Brush.horizontalGradient(listOf(RmAccentOrange, RmAccentRed)))
                                .clickable { showDialog = true }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Set Alarm", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }

                    // ── Divider ─────────────────────────────────────────────────
                    Spacer(Modifier.height(14.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth().height(1.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color.Transparent, RmAccentPurple.copy(.4f), RmAccentOrange.copy(.3f), Color.Transparent)
                                )
                            )
                    )
                    Spacer(Modifier.height(14.dp))

                    // ── Active reminder list ─────────────────────────────────────
                    if (reminders.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🔔", fontSize = 30.sp)
                                Spacer(Modifier.height(6.dp))
                                Text("কোনো reminder নেই", fontSize = 13.sp, color = RmTextMuted)
                                Text("\"Set Alarm\" চেপে নতুন reminder যোগ করো", fontSize = 11.sp, color = RmTextMuted.copy(.6f))
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            reminders.forEachIndexed { index, reminder ->
                                ReminderItemRow(reminder = reminder) {
                                    cancelReminderAlarm(context, reminder.id)
                                    reminders = reminders.toMutableList().also { it.removeAt(index) }
                                }
                            }
                        }
                    }

                    // ── Quick presets ────────────────────────────────────────────
                    Spacer(Modifier.height(14.dp))
                    Text("⚡ Quick Set", fontSize = 11.sp, color = RmTextMuted, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "5m"  to  5L * 60_000L,
                            "10m" to 10L * 60_000L,
                            "30m" to 30L * 60_000L,
                            "1h"  to  3_600_000L
                        ).forEach { (display, delay) ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(RmBgCard2)
                                    .border(1.dp, RmAccentPurple.copy(.3f), RoundedCornerShape(10.dp))
                                    .clickable {
                                        ensureReminderChannel(context)
                                        val id    = nextId++
                                        val label = "$display পরে"
                                        scheduleReminderAlarm(context, id, label, delay, true)
                                        reminders = reminders + ReminderItem(id, label, System.currentTimeMillis() + delay, true)
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(display, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RmAccentPurple)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    // ── Reminder Dialog ──────────────────────────────────────────────────────
    if (showDialog) {
        ReminderSetDialog(
            onDismiss = { showDialog = false },
            onSet = { label, delayMillis, withVibration ->
                ensureReminderChannel(context)
                val id = nextId++
                scheduleReminderAlarm(context, id, label, delayMillis, withVibration)
                reminders = reminders + ReminderItem(
                    id             = id,
                    label          = label,
                    triggerMillis  = System.currentTimeMillis() + delayMillis,
                    withVibration  = withVibration
                )
                showDialog = false
            }
        )
    }
}

// ─── Individual reminder row ──────────────────────────────────────────────────
@Composable
private fun ReminderItemRow(reminder: ReminderItem, onCancel: () -> Unit) {
    val diff = reminder.triggerMillis - System.currentTimeMillis()
    val remaining = if (diff > 0) {
        val totalMin = diff / 60_000L
        val hrs  = totalMin / 60
        val mins = totalMin % 60
        if (hrs > 0) "${hrs}h ${mins}m বাকি" else "${mins}m বাকি"
    } else "সময় শেষ"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RmBgCard2)
            .border(1.dp, RmAccentPurple.copy(.15f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Alarm, contentDescription = null, tint = if (reminder.withVibration) RmAccentOrange else RmAccentBlue, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(reminder.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = RmTextWhite, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(remaining, fontSize = 11.sp, color = RmAccentCyan)
                if (reminder.withVibration) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.Vibration, contentDescription = null, tint = RmAccentGreen, modifier = Modifier.size(11.dp))
                }
            }
        }
        IconButton(onClick = onCancel, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Default.Cancel, contentDescription = "Cancel", tint = RmAccentRed.copy(.8f), modifier = Modifier.size(16.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Reminder Set Dialog
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ReminderSetDialog(
    onDismiss: () -> Unit,
    onSet: (label: String, delayMillis: Long, withVibration: Boolean) -> Unit
) {
    var labelText     by remember { mutableStateOf("") }
    var selectedMode  by remember { mutableStateOf(0) }  // 0=minutes, 1=hours
    var minuteValue   by remember { mutableStateOf(5) }
    var hourValue     by remember { mutableStateOf(1) }
    var withVibration by remember { mutableStateOf(true) }

    val delayMillis: Long = if (selectedMode == 0) minuteValue * 60_000L else hourValue * 3_600_000L

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF1A1A35), Color(0xFF0D0D1A))))
                .border(1.dp, RmAccentPurple.copy(.3f), RoundedCornerShape(28.dp))
                .padding(24.dp)
        ) {
            Column {

                // ── Title ───────────────────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(Brush.radialGradient(listOf(RmAccentOrange.copy(.3f), Color.Transparent)), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Alarm, contentDescription = null, tint = RmAccentOrange, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Reminder সেট করো", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = RmTextWhite)
                        Text("নির্দিষ্ট সময় পর alarm বাজবে", fontSize = 12.sp, color = RmTextMuted)
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ── Label input ─────────────────────────────────────────────
                OutlinedTextField(
                    value = labelText,
                    onValueChange = { labelText = it },
                    placeholder = { Text("কী মনে করিয়ে দেবে?", color = RmTextMuted, fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = RmAccentPurple,
                        unfocusedBorderColor = RmTextMuted.copy(.3f),
                        focusedTextColor     = RmTextWhite,
                        unfocusedTextColor   = RmTextWhite,
                        cursorColor          = RmAccentPurple
                    ),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = RmAccentPurple, modifier = Modifier.size(18.dp)) }
                )

                Spacer(Modifier.height(20.dp))

                // ── Mode selector (Minutes / Hours) ──────────────────────────
                Text("সময় নির্বাচন করো", fontSize = 13.sp, color = RmTextMuted, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RmModeChip("মিনিট", Icons.Default.Timer, selectedMode == 0, RmAccentCyan, Modifier.weight(1f)) { selectedMode = 0 }
                    RmModeChip("ঘন্টা", Icons.Default.AccessTime, selectedMode == 1, RmAccentOrange, Modifier.weight(1f)) { selectedMode = 1 }
                }

                Spacer(Modifier.height(18.dp))

                // ── Number picker ────────────────────────────────────────────
                if (selectedMode == 0) {
                    RmNumberPicker(minuteValue, 1..120, "মিনিট", RmAccentCyan, listOf(5, 10, 15, 20, 30, 45, 60)) { minuteValue = it }
                } else {
                    RmNumberPicker(hourValue, 1..24, "ঘন্টা", RmAccentOrange, listOf(1, 2, 3, 4, 6, 8, 12)) { hourValue = it }
                }

                Spacer(Modifier.height(18.dp))

                // ── Vibration toggle ─────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(RmBgCard2)
                        .clickable { withVibration = !withVibration }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (withVibration) Icons.Default.Vibration else Icons.Default.NotificationsOff,
                        contentDescription = null,
                        tint = if (withVibration) RmAccentGreen else RmTextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Vibration", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = RmTextWhite)
                        Text(if (withVibration) "Alarm এ vibrate করবে" else "Vibration বন্ধ আছে", fontSize = 11.sp, color = RmTextMuted)
                    }
                    Switch(
                        checked = withVibration,
                        onCheckedChange = { withVibration = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor   = RmAccentGreen,
                            checkedTrackColor   = RmAccentGreen.copy(.3f),
                            uncheckedThumbColor = RmTextMuted,
                            uncheckedTrackColor = RmTextMuted.copy(.2f)
                        )
                    )
                }

                Spacer(Modifier.height(10.dp))

                // ── Summary ──────────────────────────────────────────────────
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .background(RmAccentPurple.copy(.12f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val timeStr = if (selectedMode == 0) "$minuteValue মিনিট পরে" else "$hourValue ঘন্টা পরে"
                    Text("⏰ $timeStr alarm বাজবে", fontSize = 12.sp, color = RmAccentPurple, fontWeight = FontWeight.Medium)
                }

                Spacer(Modifier.height(20.dp))

                // ── Action buttons ───────────────────────────────────────────
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = RmTextMuted)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("বাতিল", fontSize = 13.sp)
                    }
                    Button(
                        onClick = {
                            val finalLabel = labelText.ifBlank {
                                if (selectedMode == 0) "$minuteValue মিনিট পরে" else "$hourValue ঘন্টা পরে"
                            }
                            onSet(finalLabel, delayMillis, withVibration)
                        },
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize()
                                .background(Brush.horizontalGradient(listOf(RmAccentOrange, RmAccentRed)), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Alarm, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Alarm সেট করো", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Mode chip ────────────────────────────────────────────────────────────────
@Composable
private fun RmModeChip(
    label: String, icon: ImageVector, selected: Boolean, color: Color,
    modifier: Modifier = Modifier, onClick: () -> Unit
) {
    val bg     by animateColorAsState(if (selected) color.copy(.2f) else RmBgCard2, label = "rmChipBg")
    val border by animateColorAsState(if (selected) color else RmTextMuted.copy(.2f), label = "rmChipBorder")
    Box(
        modifier = modifier.height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, tint = if (selected) color else RmTextMuted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = if (selected) color else RmTextMuted)
        }
    }
}

// ─── Number picker with quick values ─────────────────────────────────────────
@Composable
private fun RmNumberPicker(
    value: Int, range: IntRange, label: String, color: Color,
    quickValues: List<Int>, onValueChange: (Int) -> Unit
) {
    Column {
        // Quick chips
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            quickValues.take(7).forEach { qv ->
                val sel = value == qv
                Box(
                    modifier = Modifier
                        .weight(1f).height(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) color.copy(.25f) else RmBgCard2)
                        .border(1.dp, if (sel) color else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { onValueChange(qv) },
                    contentAlignment = Alignment.Center
                ) {
                    Text("$qv", fontSize = 11.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, color = if (sel) color else RmTextMuted)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // Stepper
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            IconButton(
                onClick = { if (value > range.first) onValueChange(value - 1) },
                modifier = Modifier.size(40.dp).background(RmBgCard2, CircleShape)
            ) { Icon(Icons.Default.Remove, contentDescription = null, tint = color, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(16.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$value", fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, color = color)
                Text(label, fontSize = 11.sp, color = RmTextMuted)
            }
            Spacer(Modifier.width(16.dp))
            IconButton(
                onClick = { if (value < range.last) onValueChange(value + 1) },
                modifier = Modifier.size(40.dp).background(RmBgCard2, CircleShape)
            ) { Icon(Icons.Default.Add, contentDescription = null, tint = color, modifier = Modifier.size(18.dp)) }
        }
    }
}

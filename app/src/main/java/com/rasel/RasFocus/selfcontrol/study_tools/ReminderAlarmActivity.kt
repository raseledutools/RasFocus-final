package com.rasel.RasFocus.selfcontrol.study_tools

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

/**
 * Full-screen overlay Activity shown when a reminder alarm fires.
 * - রিং ১ মিনিট বাজে।
 * - ইউজার Dismiss/Close না করলে ২ মিনিট পরপর আবার রিং বাজে।
 * - Snooze বা Dismiss করলে re-ring loop বন্ধ হয়।
 */
class ReminderAlarmActivity : ComponentActivity() {

    private val reRingHandler = Handler(Looper.getMainLooper())
    private var reRingRunnable: Runnable? = null
    private var isDismissed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over lock screen, keep screen on
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val title       = intent.getStringExtra("title") ?: "Reminder"
        val description = intent.getStringExtra("description") ?: ""
        val repeatLabel = intent.getStringExtra("repeatLabel") ?: ""
        val priority    = intent.getStringExtra("priority") ?: "NORMAL"
        val notifId     = intent.getIntExtra("notifId", 0)
        val withVib     = intent.getBooleanExtra("withVibration", true)
        val ringtoneUri = intent.getStringExtra("ringtoneUri") ?: ""
        val durationStr = intent.getStringExtra("ringtoneDuration") ?: "ONE_MINUTE"
        val repeatTypeStr   = intent.getStringExtra("repeatType") ?: "NONE"
        val customAmount    = intent.getIntExtra("customAmount", 1)
        val customUnit      = intent.getStringExtra("customUnit") ?: "DAYS"
        val triggerMillis   = intent.getLongExtra("triggerMillis", System.currentTimeMillis())

        val duration = try { RingtoneDuration.valueOf(durationStr) } catch (_: Exception) { RingtoneDuration.ONE_MINUTE }

        // ── Re-ring loop: ২ মিনিট পরপর আবার বাজাবে যতক্ষণ dismiss না হয় ──
        fun startReRingLoop(ringtoneUriStr: String) {
            val runnable = object : Runnable {
                override fun run() {
                    if (isDismissed) return
                    val uri = if (ringtoneUriStr.isNotEmpty())
                        android.net.Uri.parse(ringtoneUriStr)
                    else
                        android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                            ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                    ReminderAlarmPlayer.play(this@ReminderAlarmActivity, uri, duration, withVib)
                    // ২ মিনিট পরে আবার
                    reRingHandler.postDelayed(this, 2 * 60_000L)
                }
            }
            reRingRunnable = runnable
            // প্রথম রিং এখনই বাজছে; ২ মিনিট পরে re-ring শুরু
            reRingHandler.postDelayed(runnable, 2 * 60_000L)
        }

        startReRingLoop(ringtoneUri)

        setContent {
            ReminderAlarmScreen(
                title = title,
                description = description,
                repeatLabel = repeatLabel,
                priority = priority,
                onSnooze = {
                    isDismissed = true
                    reRingRunnable?.let { reRingHandler.removeCallbacks(it) }
                    ReminderAlarmPlayer.stop()
                    sendBroadcast(Intent(this, StopRingtoneReceiver::class.java))
                    (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
                        .cancel(notifId)
                    // Re-schedule alarm 5 minutes from now
                    val snoozeMillis = System.currentTimeMillis() + 5 * 60_000L
                    val snoozeItem = ReminderItem(
                        id = notifId + 50000, // offset to avoid ID collision
                        title = title,
                        description = description,
                        triggerMillis = snoozeMillis,
                        repeatType = RepeatType.NONE, // snooze fires once
                        withVibration = withVib,
                        ringtoneDuration = duration,
                        ringtoneUriString = ringtoneUri
                    )
                    scheduleReminderAlarmFull(this, snoozeItem)
                    finish()
                },
                onDismiss = {
                    isDismissed = true
                    reRingRunnable?.let { reRingHandler.removeCallbacks(it) }
                    // Stop via both singleton AND broadcast so any separately-started player also stops
                    ReminderAlarmPlayer.stop()
                    sendBroadcast(Intent(this, StopRingtoneReceiver::class.java))
                    // Cancel the persistent notification so it doesn't linger
                    (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
                        .cancel(notifId)
                    finish()
                }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Activity destroy হলে re-ring loop বন্ধ করো
        isDismissed = true
        reRingRunnable?.let { reRingHandler.removeCallbacks(it) }
    }

    override fun onBackPressed() {
        // Do nothing — user must explicitly snooze or dismiss
    }
}

@Composable
fun ReminderAlarmScreen(
    title: String,
    description: String,
    repeatLabel: String,
    priority: String,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit
) {
    // Pulsing animation for the alarm icon
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val priorityColor = when (priority) {
        "CRITICAL"  -> Color(0xFFE53935)
        "IMPORTANT" -> Color(0xFF43A047)
        "FAVORITE"  -> Color(0xFF1E88E5)
        else        -> Color(0xFF00897B)
    }

    val now = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF001B29), Color(0xFF00344F), Color(0xFF001B29))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            // Clock time
            Text(
                now,
                fontSize = 52.sp,
                fontWeight = FontWeight.Light,
                color = Color.White,
                letterSpacing = 2.sp
            )

            Spacer(Modifier.height(8.dp))

            // Pulsing alarm icon
            Box(
                modifier = Modifier
                    .scale(scale)
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(priorityColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Alarm,
                    contentDescription = null,
                    tint = priorityColor,
                    modifier = Modifier.size(44.dp)
                )
            }

            // Priority badge + title card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.10f)),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (priority != "NORMAL") {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = priorityColor
                        ) {
                            Text(
                                priority.lowercase().replaceFirstChar { it.uppercase() },
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    Text(
                        title,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )

                    if (description.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            description,
                            fontSize = 14.sp,
                            color = Color.White.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }

                    if (repeatLabel.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Repeat, contentDescription = null,
                                tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Repeat:  $repeatLabel", fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.6f))
                        }
                        val nextRun = SimpleDateFormat("d MMM yy  h:mm a", Locale.getDefault())
                            .format(Date(System.currentTimeMillis()))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = null,
                                tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Next Run:  $nextRun", fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.6f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Action buttons row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Snooze
                Button(
                    onClick = onSnooze,
                    modifier = Modifier.weight(1f).height(60.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.15f),
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.Snooze, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Snooze", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("5 minutes", fontSize = 11.sp, color = Color.White.copy(0.7f))
                    }
                }

                // Dismiss
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(60.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE53935).copy(alpha = 0.85f),
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Dismiss", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

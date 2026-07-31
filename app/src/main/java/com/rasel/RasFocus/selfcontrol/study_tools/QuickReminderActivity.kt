package com.rasel.RasFocus.selfcontrol.study_tools

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.*

/**
 * Transparent dialog-style Activity launched from Quick Settings Tile.
 * Lets user set a quick alarm within 24 hours (5, 10, 15, 30 min or custom).
 * Alarm rings for exactly 1 minute.
 */
class QuickReminderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QuickReminderDialog(
                onDismiss = { finish() },
                onSet = { minutes, label ->
                    scheduleQuickReminder(this, minutes, label)
                    Toast.makeText(
                        this,
                        "⏰ Reminder set for ${formatMinutes(minutes)}",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            )
        }
    }
}

private fun formatMinutes(minutes: Int): String {
    return if (minutes < 60) "$minutes min" else "${minutes / 60}h ${minutes % 60}min"
}

fun scheduleQuickReminder(context: Context, afterMinutes: Int, label: String = "Quick Reminder") {
    val triggerMillis = System.currentTimeMillis() + afterMinutes * 60_000L
    val item = ReminderItem(
        id = (90000 + afterMinutes + System.currentTimeMillis().toInt() % 1000),
        title = label,
        triggerMillis = triggerMillis,
        repeatType = RepeatType.NONE,
        withVibration = true,
        ringtoneDuration = RingtoneDuration.ONE_MINUTE,
        ringtoneUriString = ""
    )
    ensureReminderChannel(context)
    scheduleReminderAlarmFull(context, item)
}

@Composable
fun QuickReminderDialog(
    onDismiss: () -> Unit,
    onSet: (Int, String) -> Unit
) {
    val context = LocalContext.current
    var selectedMinutes by remember { mutableStateOf<Int?>(null) }
    var customMinutes by remember { mutableStateOf("") }
    var customHours   by remember { mutableStateOf("") }
    var showCustom    by remember { mutableStateOf(false) }
    var label         by remember { mutableStateOf("Quick Reminder") }

    val presets = listOf(
        Triple(5,  "5 min",  Icons.Default.Alarm),
        Triple(10, "10 min", Icons.Default.Alarm),
        Triple(15, "15 min", Icons.Default.Alarm),
        Triple(30, "30 min", Icons.Default.Alarm),
        Triple(60, "1 hour", Icons.Default.AccessTime),
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF00695C), Color(0xFF004D40)))
                )
        ) {
            Column(modifier = Modifier.padding(24.dp)) {

                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BoltOutlined ?: Icons.Default.FlashOn,
                        contentDescription = null, tint = Color(0xFF80CBC4), modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Quick Reminder",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = Color.White.copy(0.7f))
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    "Rings for 1 minute • Within 24 hours",
                    color = Color(0xFF80CBC4),
                    fontSize = 12.sp
                )

                Spacer(Modifier.height(20.dp))

                // Label field
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Label (optional)", color = Color.White.copy(0.4f)) },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = Color(0xFF80CBC4)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF80CBC4),
                        unfocusedBorderColor = Color.White.copy(0.3f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFF80CBC4)
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(Modifier.height(20.dp))

                // Preset grid
                Text("Remind me in:", color = Color.White.copy(0.8f), fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))

                // 2-column grid of presets
                val rows = presets.chunked(3)
                rows.forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowItems.forEach { (minutes, label2, icon) ->
                            val isSelected = selectedMinutes == minutes && !showCustom
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (isSelected) Color(0xFF00BFA5)
                                        else Color.White.copy(alpha = 0.10f)
                                    )
                                    .border(
                                        1.dp,
                                        if (isSelected) Color(0xFF00BFA5) else Color.White.copy(0.2f),
                                        RoundedCornerShape(14.dp)
                                    )
                                    .clickable {
                                        selectedMinutes = minutes
                                        showCustom = false
                                    }
                                    .padding(vertical = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        label2,
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                        // Fill remaining slots if row is not full
                        repeat(3 - rowItems.size) {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // Custom time row
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (showCustom) Color(0xFF00BFA5)
                            else Color.White.copy(alpha = 0.10f)
                        )
                        .border(
                            1.dp,
                            if (showCustom) Color(0xFF00BFA5) else Color.White.copy(0.2f),
                            RoundedCornerShape(14.dp)
                        )
                        .clickable { showCustom = true; selectedMinutes = null }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tune, contentDescription = null,
                            tint = Color.White.copy(0.8f), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Custom time", color = Color.White, fontSize = 15.sp,
                            fontWeight = if (showCustom) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.weight(1f))
                        if (showCustom)
                            Icon(Icons.Default.ExpandLess, contentDescription = null, tint = Color.White)
                        else
                            Icon(Icons.Default.ExpandMore, contentDescription = null, tint = Color.White.copy(0.6f))
                    }
                }

                // Custom time input (expanded)
                if (showCustom) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Hours
                        OutlinedTextField(
                            value = customHours,
                            onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) customHours = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Hours", color = Color.White.copy(0.6f)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF80CBC4),
                                unfocusedBorderColor = Color.White.copy(0.3f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFF80CBC4)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )
                        Text(":", color = Color.White, fontSize = 24.sp)
                        // Minutes
                        OutlinedTextField(
                            value = customMinutes,
                            onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) customMinutes = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Minutes", color = Color.White.copy(0.6f)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF80CBC4),
                                unfocusedBorderColor = Color.White.copy(0.3f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFF80CBC4)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Max 24 hours (1440 min)",
                        color = Color.White.copy(0.5f),
                        fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.height(24.dp))

                // Set button
                val canSet = (selectedMinutes != null && !showCustom) ||
                        (showCustom && (customMinutes.isNotEmpty() || customHours.isNotEmpty()))

                Button(
                    onClick = {
                        val totalMinutes = if (showCustom) {
                            val h = customHours.toIntOrNull() ?: 0
                            val m = customMinutes.toIntOrNull() ?: 0
                            (h * 60 + m).coerceIn(1, 1440)
                        } else selectedMinutes ?: return@Button

                        onSet(totalMinutes, label.ifBlank { "Quick Reminder" })
                    },
                    enabled = canSet,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00BFA5),
                        disabledContainerColor = Color.White.copy(0.2f)
                    )
                ) {
                    Icon(Icons.Default.Alarm, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Set Reminder",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

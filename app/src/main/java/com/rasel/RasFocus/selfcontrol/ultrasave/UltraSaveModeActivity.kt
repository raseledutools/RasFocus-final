package com.rasel.RasFocus.selfcontrol.ultrasave

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ultra Save Mode এর dialog Activity।
 *
 * Intent extra "mode":
 *  "activate" (default) — তিনটি option দেখাও (Normal / Self Control / Parents)
 *  "exit"               — lock check করো (timer দেখাও বা password চাও)
 */
class UltraSaveModeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent?.getStringExtra("mode") ?: "activate"

        setContent {
            if (mode == "exit") {
                ExitDialog(onDismiss = { finish() }, onUnlocked = { finish() })
            } else {
                ActivateDialog(onDismiss = { finish() }, onActivated = { finish() })
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════
// ① ACTIVATE DIALOG — তিনটি option: Normal / Self Control / Parents
// ═════════════════════════════════════════════════════════════════════

@Composable
private fun ActivateDialog(onDismiss: () -> Unit, onActivated: () -> Unit) {
    val ctx = LocalContext.current

    // কোন option selected
    var selected by remember { mutableStateOf<UltraSaveManager.LockType?>(null) }
    // Self Control: কতক্ষণ?
    var selfDurationMs by remember { mutableLongStateOf(30 * 60_000L) } // default 30min
    // Parents: password
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1A1A2E))
                .padding(20.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {

                // ── Header ──
                Text("⚡ Ultra Save Mode", color = Color.White,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("কোন mode এ চালু করবেন?",
                    color = Color(0xFFAAAAAA), fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))

                // ── Option 1: Normal ──
                OptionCard(
                    emoji = "✅",
                    title = "Normal Mode",
                    subtitle = "যেকোনো সময় বন্ধ করা যাবে",
                    selected = selected == UltraSaveManager.LockType.NORMAL,
                    borderColor = Color(0xFF4CAF50)
                ) { selected = UltraSaveManager.LockType.NORMAL }

                Spacer(Modifier.height(10.dp))

                // ── Option 2: Self Control ──
                OptionCard(
                    emoji = "⏱",
                    title = "Self Control",
                    subtitle = "নির্দিষ্ট সময়ের আগে বের হওয়া যাবে না",
                    selected = selected == UltraSaveManager.LockType.SELF_CONTROL,
                    borderColor = Color(0xFF2196F3)
                ) { selected = UltraSaveManager.LockType.SELF_CONTROL }

                // Self Control: সময় বাছাই
                AnimatedVisibility(selected == UltraSaveManager.LockType.SELF_CONTROL) {
                    Column(Modifier.padding(top = 10.dp)) {
                        Text("কতক্ষণের জন্য?",
                            color = Color(0xFFAAAAAA), fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        DurationPicker(
                            selected = selfDurationMs,
                            onSelect = { selfDurationMs = it }
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ── Option 3: Parents ──
                OptionCard(
                    emoji = "🔒",
                    title = "Parents Control",
                    subtitle = "পাসওয়ার্ড ছাড়া বের হওয়া যাবে না",
                    selected = selected == UltraSaveManager.LockType.PARENTS,
                    borderColor = Color(0xFFFF9800)
                ) { selected = UltraSaveManager.LockType.PARENTS }

                // Parents: password set
                AnimatedVisibility(selected == UltraSaveManager.LockType.PARENTS) {
                    Column(Modifier.padding(top = 10.dp)) {
                        PasswordField("পাসওয়ার্ড দিন", password) { password = it }
                        Spacer(Modifier.height(8.dp))
                        PasswordField("পাসওয়ার্ড নিশ্চিত করুন", confirmPassword) {
                            confirmPassword = it
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ── Activate Button ──
                Button(
                    onClick = {
                        val type = selected ?: run {
                            Toast.makeText(ctx, "একটা option বেছে নিন", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (type == UltraSaveManager.LockType.PARENTS) {
                            if (password.length < 4) {
                                Toast.makeText(ctx, "পাসওয়ার্ড কমপক্ষে ৪ অক্ষর হতে হবে", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (password != confirmPassword) {
                                Toast.makeText(ctx, "পাসওয়ার্ড মিলছে না!", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                        }
                        UltraSaveManager.activate(
                            ctx = ctx,
                            lockType = type,
                            durationMs = if (type == UltraSaveManager.LockType.SELF_CONTROL) selfDurationMs else 0L,
                            password = if (type == UltraSaveManager.LockType.PARENTS) password else ""
                        )
                        Toast.makeText(ctx, "⚡ Ultra Save চালু!", Toast.LENGTH_SHORT).show()
                        onActivated()
                    },
                    enabled = selected != null,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF6C63FF),
                        disabledContainerColor = Color(0xFF444444)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("⚡ Activate", fontWeight = FontWeight.Bold,
                        color = Color.White, fontSize = 16.sp)
                }

                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss) {
                    Text("বাতিল", color = Color(0xFFAAAAAA))
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════
// ② EXIT DIALOG — timer দেখাও বা password চাও
// ═════════════════════════════════════════════════════════════════════

@Composable
private fun ExitDialog(onDismiss: () -> Unit, onUnlocked: () -> Unit) {
    val ctx = LocalContext.current
    val lockType = UltraSaveManager.getLockType(ctx)
    var password by remember { mutableStateOf("") }
    var wrongPass by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1A1A2E))
                .padding(24.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when (lockType) {

                    // ── Self Control: timer দেখাও ──
                    UltraSaveManager.LockType.SELF_CONTROL -> {
                        val remaining = UltraSaveManager.remainingMs(ctx)
                        val unlockAt = UltraSaveManager.getUnlockAtMs(ctx)
                        val unlockStr = SimpleDateFormat("h:mm a", Locale.getDefault())
                            .format(Date(unlockAt))

                        Text("⏱", fontSize = 48.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Self Control Mode চালু",
                            color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF2A2A4A))
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(UltraSaveManager.formatRemaining(remaining),
                                    color = Color(0xFF6C63FF), fontSize = 32.sp,
                                    fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(4.dp))
                                Text("আরও বাকি",
                                    color = Color(0xFFAAAAAA), fontSize = 13.sp)
                                Spacer(Modifier.height(8.dp))
                                Text("$unlockStr পর্যন্ত বের হওয়া যাবে না",
                                    color = Color(0xFF888888), fontSize = 12.sp,
                                    textAlign = TextAlign.Center)
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF444466)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("বুঝেছি", color = Color.White) }
                    }

                    // ── Parents: password চাও ──
                    UltraSaveManager.LockType.PARENTS -> {
                        Text("🔒", fontSize = 48.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Parents Control Mode",
                            color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("পাসওয়ার্ড দিয়ে বের হোন",
                            color = Color(0xFFAAAAAA), fontSize = 13.sp)
                        Spacer(Modifier.height(16.dp))

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it; wrongPass = false },
                            placeholder = { Text("পাসওয়ার্ড", color = Color(0xFF666666)) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            isError = wrongPass,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF6C63FF),
                                unfocusedBorderColor = Color(0xFF444466),
                                errorBorderColor = Color(0xFFFF5252)
                            )
                        )
                        if (wrongPass) {
                            Text("❌ পাসওয়ার্ড ভুল হয়েছে",
                                color = Color(0xFFFF5252), fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp))
                        }

                        Spacer(Modifier.height(16.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("বাতিল", color = Color(0xFFAAAAAA)) }

                            Button(
                                onClick = {
                                    if (UltraSaveManager.checkPassword(ctx, password)) {
                                        UltraSaveManager.deactivate(ctx)
                                        Toast.makeText(ctx, "✅ Unlocked!", Toast.LENGTH_SHORT).show()
                                        onUnlocked()
                                    } else {
                                        wrongPass = true
                                        password = ""
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF6C63FF)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("Unlock", color = Color.White, fontWeight = FontWeight.Bold) }
                        }
                    }

                    // ── Normal: সরাসরি বন্ধ করো ──
                    UltraSaveManager.LockType.NORMAL -> {
                        UltraSaveManager.deactivate(ctx)
                        onUnlocked()
                    }
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════
// Helper Composables
// ═════════════════════════════════════════════════════════════════════

@Composable
private fun OptionCard(
    emoji: String,
    title: String,
    subtitle: String,
    selected: Boolean,
    borderColor: Color,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0xFF2A2A4A) else Color(0xFF14142A))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) borderColor else Color(0xFF333355),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 24.sp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = Color.White,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Color(0xFF999999), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun DurationPicker(selected: Long, onSelect: (Long) -> Unit) {
    val options = listOf(
        "15m" to 15 * 60_000L,
        "30m" to 30 * 60_000L,
        "1h"  to 60 * 60_000L,
        "2h"  to 2 * 60 * 60_000L,
        "4h"  to 4 * 60 * 60_000L,
        "8h"  to 8 * 60 * 60_000L,
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { (label, ms) ->
            val isSelected = selected == ms
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) Color(0xFF2196F3) else Color(0xFF1E1E3A))
                    .border(1.dp,
                        if (isSelected) Color(0xFF2196F3) else Color(0xFF333366),
                        RoundedCornerShape(8.dp))
                    .clickable { onSelect(ms) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = Color.White,
                    fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(label, color = Color(0xFF666666)) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = Color(0xFFFF9800),
            unfocusedBorderColor = Color(0xFF444466)
        )
    )
}

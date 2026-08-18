package com.rasel.RasFocus.selfcontrol.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

// ─────────────────────────────────────────────────────────────────────────────
// Colors — minimal dark palette (matches screenshot)
// ─────────────────────────────────────────────────────────────────────────────
private val LauncherBg   = Color(0xFF000000)
private val LauncherText = Color(0xFFFFFFFF)
private val SearchLine   = Color(0xFF444444)
private val GearTint     = Color(0xFF888888)

data class AppInfo(val label: String, val packageName: String)

// ─────────────────────────────────────────────────────────────────────────────
// Main launcher screen
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun MinimalistLauncherScreen(navController: NavController? = null) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }

    val allApps: List<AppInfo> = remember {
        getInstalledApps(context)
    }

    val currentTime = remember { mutableStateOf(getCurrentTime()) }

    // refresh time every minute
    LaunchedEffect(Unit) {
        while (true) {
            currentTime.value = getCurrentTime()
            kotlinx.coroutines.delay(60_000L)
        }
    }

    val filtered = remember(query, allApps) {
        if (query.isBlank()) allApps
        else allApps.filter { it.label.contains(query, ignoreCase = true) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LauncherBg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Clock (shown only when not searching) ──────────────────────
            if (query.isBlank()) {
                Spacer(Modifier.height(40.dp))
                Text(
                    text = currentTime.value.first,   // e.g. "3:34:30 PM"
                    color = LauncherText,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Light,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = currentTime.value.second,  // e.g. "2026-08-18"
                    color = LauncherText.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(32.dp))
            } else {
                Spacer(Modifier.height(16.dp))
            }

            // ── Search bar ─────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = LauncherText, fontSize = 18.sp),
                    decorationBox = { inner ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(bottom = 4.dp)
                        ) {
                            if (query.isBlank()) {
                                Text(
                                    text = "",
                                    color = LauncherText.copy(alpha = 0f),
                                    fontSize = 18.sp
                                )
                            }
                            inner()
                            // bottom border
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .align(Alignment.BottomCenter)
                                    .background(SearchLine)
                            )
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = LauncherText,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(Modifier.height(24.dp))

            // ── App list ───────────────────────────────────────────────────
            Box(modifier = Modifier.weight(1f)) {
                // Alphabetical side-bar
                val letters = filtered.map { it.label.first().uppercaseChar() }.distinct().sorted()
                
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = 30.dp) // leave space for side bar
                ) {
                    items(filtered) { app ->
                        Text(
                            text = app.label,
                            color = LauncherText,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { launchApp(context, app.packageName) }
                                .padding(horizontal = 20.dp, vertical = 14.dp)
                        )
                    }
                }

                // Side letter index
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    letters.forEach { letter ->
                        Text(
                            text = letter.toString(),
                            color = LauncherText.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }

        // ── Settings gear (bottom right) ───────────────────────────────────
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = "Settings",
            tint = GearTint,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .size(24.dp)
                .clickable { showSettings = true }
        )

        // ── Settings sheet ─────────────────────────────────────────────────
        if (showSettings) {
            LauncherSettingsSheet(
                onDismiss = { showSettings = false },
                onBack = { navController?.popBackStack() }
            )
        }
    }
}

@Composable
fun LauncherSettingsSheet(onDismiss: () -> Unit, onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(Color(0xFF1A1A1A))
                .padding(24.dp)
                .clickable(enabled = false) {} // prevent dismiss on content click
        ) {
            Text("Launcher Settings", color = LauncherText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onBack(); onDismiss() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = null, tint = Color(0xFF14C3B2))
                Spacer(Modifier.width(16.dp))
                Text("Back to RasFocus", color = LauncherText, fontSize = 16.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Tip: Set this screen as your default launcher from Android Settings → Apps → Default Apps → Home App → RasFocus.",
                color = LauncherText.copy(alpha = 0.5f),
                fontSize = 12.sp
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun getInstalledApps(context: Context): List<AppInfo> {
    val pm: PackageManager = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
    val resolveInfos: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)
    return resolveInfos
        .map { ri -> AppInfo(ri.loadLabel(pm).toString(), ri.activityInfo.packageName) }
        .sortedBy { it.label.lowercase() }
        .distinctBy { it.packageName }
}

private fun launchApp(context: Context, packageName: String) {
    try {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    } catch (e: Exception) {
        // silently ignore
    }
}

private fun getCurrentTime(): Pair<String, String> {
    val cal = java.util.Calendar.getInstance()
    val hour = cal.get(java.util.Calendar.HOUR)
    val minute = cal.get(java.util.Calendar.MINUTE)
    val second = cal.get(java.util.Calendar.SECOND)
    val amPm = if (cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "AM" else "PM"
    val timeStr = String.format("%d:%02d:%02d %s", if (hour == 0) 12 else hour, minute, second, amPm)

    val year = cal.get(java.util.Calendar.YEAR)
    val month = cal.get(java.util.Calendar.MONTH) + 1
    val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
    val dateStr = String.format("%d-%02d-%02d", year, month, day)
    return Pair(timeStr, dateStr)
}

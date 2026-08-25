package com.rasel.RasFocus.selfcontrol

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rasel.RasFocus.R
import com.rasel.RasFocus.ui.theme.SoftWhite

private val DrawerBg   = Color(0xFF1A1A2E)
private val TextWhite  = SoftWhite
private val AccentTeal = Color(0xFF14C3B2)

@Composable
fun DrawerContent(
    onNavigate: (String) -> Unit,
    closeDrawer: () -> Unit
) {
    ModalDrawerSheet(
        drawerContainerColor = DrawerBg,
        drawerContentColor   = TextWhite,
        modifier = Modifier.width(280.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Profile Header (fixed)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFF08504B), DrawerBg))
                    )
                    .padding(24.dp)
            ) {
                Column {
                    Spacer(modifier = Modifier.height(24.dp))
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(AccentTeal),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("R", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = DrawerBg)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("RasFocus User", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextWhite)
                    Text("Super Professional Mode", fontSize = 12.sp, color = AccentTeal)
                }
            }

            HorizontalDivider(color = SoftWhite.copy(alpha = 0.1f))

            // Scrollable menu — all items visible regardless of screen height
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                DrawerMenuItem(Icons.Default.Home, "Home") { onNavigate("home"); closeDrawer() }
                DrawerMenuItem(Icons.Default.Analytics, "Statistics") { onNavigate("statistics"); closeDrawer() }
                DrawerMenuItem(Icons.Default.Block, "Block List") { onNavigate("extreme_block"); closeDrawer() }
                DrawerMenuItem(Icons.Default.MobileOff, "Block Apps") { onNavigate("single_apps"); closeDrawer() }
                DrawerMenuItem(Icons.Default.DesktopWindows, "Block Websites") { onNavigate("single_website"); closeDrawer() }
                DrawerMenuItem(Icons.Default.PlaylistAddCheck, "Blocking Plan") { onNavigate("blocking_plan"); closeDrawer() }
                DrawerMenuItem(Icons.Default.Shield, "Adult Block") { onNavigate("adult_block"); closeDrawer() }
                DrawerMenuItem(Icons.Default.MenuBook, "Deep Study") { onNavigate("deep_study"); closeDrawer() }
                DrawerMenuItem(Icons.Default.Settings, "Settings") { onNavigate("settings"); closeDrawer() }
                DrawerMenuItem(Icons.Default.Lock, "Master Password", tint = AccentTeal) { onNavigate("master_password"); closeDrawer() }
                val context = LocalContext.current
                // ── RasGram row: open button + "Add to Home" pin shortcut button ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Main RasGram open button (takes up remaining space)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                closeDrawer()
                                val intent = android.content.Intent(
                                    context,
                                    com.rasel.RasFocus.selfcontrol.rasgram.RasGramActivity::class.java
                                ).apply {
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Chat,
                            contentDescription = "RasGram",
                            tint = Color(0xFF25D366),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "RasGram",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF25D366)
                        )
                    }

                    // "Add to Home" pin shortcut button
                    var pinTooltip by androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(false)
                    }
                    androidx.compose.foundation.layout.Box {
                        IconButton(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    val shortcutManager =
                                        context.getSystemService(ShortcutManager::class.java)
                                    if (shortcutManager?.isRequestPinShortcutSupported == true) {
                                        val launchIntent = android.content.Intent(
                                            context,
                                            com.rasel.RasFocus.selfcontrol.rasgram.RasGramActivity::class.java
                                        ).apply {
                                            action = android.content.Intent.ACTION_MAIN
                                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        val shortcut = ShortcutInfo.Builder(context, "rasgram_home_shortcut")
                                            .setShortLabel("RasGram")
                                            .setLongLabel("RasGram Messenger")
                                            .setIcon(
                                                Icon.createWithResource(context, R.mipmap.ic_rasgram_launcher)
                                            )
                                            .setIntent(launchIntent)
                                            .build()
                                        shortcutManager.requestPinShortcut(shortcut, null)
                                    } else {
                                        pinTooltip = true
                                    }
                                } else {
                                    // Pre-Oreo: RasGramActivity has LAUNCHER category, so icon
                                    // already appears in app drawer. Tell the user.
                                    pinTooltip = true
                                }
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF25D366).copy(alpha = 0.13f))
                        ) {
                            Icon(
                                Icons.Default.AddToHomeScreen,
                                contentDescription = "Add RasGram to Home Screen",
                                tint = Color(0xFF25D366),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        // Tooltip for unsupported launchers
                        if (pinTooltip) {
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { pinTooltip = false },
                                confirmButton = {
                                    TextButton(onClick = { pinTooltip = false }) {
                                        Text("OK", color = Color(0xFF25D366))
                                    }
                                },
                                containerColor = Color(0xFF1A1A2E),
                                title = { Text("RasGram", color = Color.White, fontWeight = FontWeight.Bold) },
                                text = {
                                    Text(
                                        "RasGram already installed as a separate app.\n\nApp drawer থেকে RasGram icon দেখুন।\n(কিছু launchers এ pin shortcut support নেই।)",
                                        color = Color.White.copy(alpha = 0.8f),
                                        fontSize = 14.sp
                                    )
                                }
                            )
                        }
                    }
                }
                DrawerMenuItem(Icons.Default.Apps, "Set as Default Launcher", tint = Color(0xFF4FC3F7)) {
                    closeDrawer()
                    val intent = Intent(Settings.ACTION_HOME_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        // fallback: manage default apps
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        } catch (_: Exception) {}
                    }
                }

                var showUpdateDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                DrawerMenuItem(Icons.Default.SystemUpdateAlt, "Check for Updates") { showUpdateDialog = true }
                if (showUpdateDialog) {
                    AlertDialog(
                        onDismissRequest = { showUpdateDialog = false },
                        confirmButton = { TextButton(onClick = { showUpdateDialog = false }) { Text("Close", color = AccentTeal) } },
                        containerColor = DrawerBg,
                        text = { UpdateCenterSection(context) }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Footer (fixed)
            HorizontalDivider(color = SoftWhite.copy(alpha = 0.1f))
            DrawerMenuItem(Icons.Default.Logout, "Logout", AccentTeal) { closeDrawer() }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun DrawerMenuItem(
    icon: ImageVector,
    label: String,
    tint: Color = TextWhite,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = label, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = tint)
    }
}


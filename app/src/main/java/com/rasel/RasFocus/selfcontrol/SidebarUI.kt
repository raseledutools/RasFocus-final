package com.rasel.RasFocus.selfcontrol

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
                // ── Railway Staff Directory ──────────────────────────────────
                RailwayStaffSection(context = context, closeDrawer = closeDrawer)

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

// ─────────────────────────────────────────────────────────────────────────────
// Railway Staff Directory
// ঢাকা ডিজেল ওয়ার্কসপ — মেকানিক্যাল সিডিউল সেকশন
// ─────────────────────────────────────────────────────────────────────────────

private data class RailwayStaff(
    val serial: Int,
    val name: String,
    val tin: String,
    val designation: String,
    val phone: String
)

private val railwayStaffList = listOf(
    RailwayStaff(1,  "মোহামদ ছড়োয়ার হোসেন", "—",     "এসএসএই/ইলেকঃ", "01912541819"),
    RailwayStaff(2,  "আব্দুর রহিম",          "৬৬০",   "ফিটার-১",       "01822968204"),
    RailwayStaff(3,  "মোঃ জাকির হোসেন",     "৯৩০",   "ফিটার-১",       "01771026030"),
    RailwayStaff(4,  "মোঃ মজিদ শেখ",        "২৩৪",   "ফিটার-২",       "01992669460"),
    RailwayStaff(5,  "মোক্তার হোসেন",        "২৪৫",   "ফিটার-২",       "01777122860"),
    RailwayStaff(6,  "এসএম হাইউল",           "১১১০",  "ফিটার-২",       "01704895716"),
    RailwayStaff(7,  "মোঃ ভহরুল হক",         "৬৫",    "ফিটার-২",       "01712052042"),
    RailwayStaff(8,  "মোঃ নাহিদ খান",        "২২০২৮", "ফিটার-২",       "01686778957"),
    RailwayStaff(9,  "মোঃ দেলোয়ার হোসেন",   "২৫০০৪", "ফিটার-২",       "01570248027"),
    RailwayStaff(10, "আরিফুল ইসলাম",         "২২০২৫", "ফিটার-২",       "01755042887"),
    RailwayStaff(11, "মোঃ ইসমাইল হোসেন",    "১১২১",  "এসএস ফিটার",    "01772068986"),
    RailwayStaff(12, "মোঃ শাহীন",            "১০৭৪",  "এসএস ফিটার",    "01676269469"),
    RailwayStaff(13, "মোঃ ফয়সাল মিয়া",     "৮১৩",   "খালাসী",        "01943348360"),
    RailwayStaff(14, "মোঃ শিপন মিয়া",       "১১২৬",  "খালাসী",        "01722609803"),
    RailwayStaff(15, "মোঃ সফিকুল ইসলাম",    "৯৩৩",   "খালাসী",        "01721006811"),
    RailwayStaff(16, "মোঃ রিয়াজ উদ্দিন খান","১০১৫",  "খালাসী",        "01766405026"),
    RailwayStaff(17, "কাজী আসলাম উদ্দিন",   "১১২২",  "খালাসী",        "01726272268")
)

private val RailwayOrange = Color(0xFFFF8C00)
private val RailwayBg     = Color(0xFF1E1A0F)
private val RailwayCard   = Color(0xFF2A2410)

@Composable
fun RailwayStaffSection(context: Context, closeDrawer: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    // ── Header Button ──
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (expanded) RailwayBg else Color.Transparent)
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Train icon box
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(RailwayOrange.copy(alpha = 0.18f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Train,
                contentDescription = null,
                tint = RailwayOrange,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "রেলওয়ে",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = RailwayOrange
            )
            Text(
                "ঢাকা ডিজেল ওয়ার্কসপ",
                fontSize = 11.sp,
                color = RailwayOrange.copy(alpha = 0.65f)
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = RailwayOrange.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp)
        )
    }

    // ── Expandable Staff List ──
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(),
        exit  = shrinkVertically()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                .background(RailwayBg)
                .padding(bottom = 8.dp)
        ) {
            // Section label
            Text(
                "মেকানিক্যাল সিডিউল সেকশন",
                fontSize = 11.sp,
                color = RailwayOrange.copy(alpha = 0.7f),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp)
            )

            railwayStaffList.forEach { staff ->
                RailwayStaffRow(staff = staff, context = context)
                if (staff.serial < railwayStaffList.size) {
                    HorizontalDivider(
                        color = RailwayOrange.copy(alpha = 0.08f),
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun RailwayStaffRow(staff: RailwayStaff, context: Context) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                // Direct call on row tap
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:${staff.phone}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Serial badge
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(RailwayOrange.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${staff.serial}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = RailwayOrange
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Name + designation
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = staff.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${staff.designation}  •  TIN: ${staff.tin}",
                fontSize = 10.sp,
                color = TextWhite.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Call button
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(
                    Brush.linearGradient(listOf(Color(0xFF1B6B1B), Color(0xFF2E9D2E))),
                    CircleShape
                )
                .clickable {
                    val intent = Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:${staff.phone}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Call,
                contentDescription = "Call ${staff.name}",
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}


package com.rasel.RasFocus.combo.selfcontrol

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.rasel.RasFocus.ui.theme.SoftWhite

private val DrawerBg = Color(0xFF1A1A2E)
private val TextWhite = SoftWhite
private val AccentTeal = Color(0xFF14C3B2)

@Composable
fun DrawerContent(
    onNavigate: (String) -> Unit,
    closeDrawer: () -> Unit
) {
    ModalDrawerSheet(
        drawerContainerColor = DrawerBg,
        drawerContentColor = TextWhite,
        modifier = Modifier.width(280.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Profile Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF08504B), DrawerBg)
                        )
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
                    Text(
                        "RasFocus User",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                    Text(
                        "Super Professional Mode",
                        fontSize = 12.sp,
                        color = AccentTeal
                    )
                }
            }

            HorizontalDivider(color = SoftWhite.copy(alpha = 0.1f))

            // Menu Items
            Spacer(modifier = Modifier.height(16.dp))
            DrawerMenuItem(Icons.Default.Home, "Home") {
                onNavigate("home")
                closeDrawer()
            }
            DrawerMenuItem(Icons.Default.Analytics, "Statistics") {
                onNavigate("statistics")
                closeDrawer()
            }
            DrawerMenuItem(Icons.Default.Block, "Block List") {
                onNavigate("extreme_block")
                closeDrawer()
            }
            DrawerMenuItem(Icons.Default.MobileOff, "Block Apps") {
                onNavigate("single_apps")
                closeDrawer()
            }
            DrawerMenuItem(Icons.Default.DesktopWindows, "Block Websites") {
                onNavigate("single_website")
                closeDrawer()
            }
            DrawerMenuItem(Icons.Default.PlaylistAddCheck, "Blocking Plan") {
                onNavigate("blocking_plan")
                closeDrawer()
            }
            DrawerMenuItem(Icons.Default.Shield, "Adult Block") {
                onNavigate("adult_block")
                closeDrawer()
            }
            DrawerMenuItem(Icons.Default.MenuBook, "Deep Study") {
                onNavigate("deep_study")
                closeDrawer()
            }
            DrawerMenuItem(Icons.Default.Settings, "Settings") {
                onNavigate("settings")
                closeDrawer()
            }

            // Master Password — Professional lock system
            DrawerMenuItem(Icons.Default.Lock, "Master Password", tint = AccentTeal) {
                onNavigate("master_password")
                closeDrawer()
            }

            var showUpdateDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            val context = androidx.compose.ui.platform.LocalContext.current

            DrawerMenuItem(Icons.Default.SystemUpdateAlt, "Check for Updates") {
                showUpdateDialog = true
            }

            if (showUpdateDialog) {
                AlertDialog(
                    onDismissRequest = { showUpdateDialog = false },
                    confirmButton = {
                        TextButton(onClick = { showUpdateDialog = false }) { Text("Close", color = AccentTeal) }
                    },
                    containerColor = DrawerBg,
                    text = {
                        UpdateCenterSection(context)
                    }
                )
            }

            // ── Railway Staff Directory ──────────────────────────────────
            val railwayCtx = LocalContext.current
            ComboRailwayStaffSection(context = railwayCtx, closeDrawer = closeDrawer)

            Spacer(modifier = Modifier.weight(1f))

            // Footer
            HorizontalDivider(color = SoftWhite.copy(alpha = 0.1f))
            DrawerMenuItem(Icons.Default.Logout, "Logout", AccentTeal) {
                closeDrawer()
            }
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
        Text(
            text = label,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = tint
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Railway Staff Directory (Combo flavour)
// ঢাকা ডিজেল ওয়ার্কসপ — মেকানিক্যাল সিডিউল সেকশন
// ─────────────────────────────────────────────────────────────────────────────

private data class ComboRailwayStaff(
    val serial: Int,
    val name: String,
    val tin: String,
    val designation: String,
    val phone: String
)

private val comboRailwayStaffList = listOf(
    ComboRailwayStaff(1,  "মোহামদ ছড়োয়ার হোসেন", "—",     "এসএসএই/ইলেকঃ", "01912541819"),
    ComboRailwayStaff(2,  "আব্দুর রহিম",          "৬৬০",   "ফিটার-১",       "01822968204"),
    ComboRailwayStaff(3,  "মোঃ জাকির হোসেন",     "৯৩০",   "ফিটার-১",       "01771026030"),
    ComboRailwayStaff(4,  "মোঃ মজিদ শেখ",        "২৩৪",   "ফিটার-২",       "01992669460"),
    ComboRailwayStaff(5,  "মোক্তার হোসেন",        "২৪৫",   "ফিটার-২",       "01777122860"),
    ComboRailwayStaff(6,  "এসএম হাইউল",           "১১১০",  "ফিটার-২",       "01704895716"),
    ComboRailwayStaff(7,  "মোঃ ভহরুল হক",         "৬৫",    "ফিটার-২",       "01712052042"),
    ComboRailwayStaff(8,  "মোঃ নাহিদ খান",        "২২০২৮", "ফিটার-২",       "01686778957"),
    ComboRailwayStaff(9,  "মোঃ দেলোয়ার হোসেন",   "২৫০০৪", "ফিটার-২",       "01570248027"),
    ComboRailwayStaff(10, "আরিফুল ইসলাম",         "২২০২৫", "ফিটার-২",       "01755042887"),
    ComboRailwayStaff(11, "মোঃ ইসমাইল হোসেন",    "১১২১",  "এসএস ফিটার",    "01772068986"),
    ComboRailwayStaff(12, "মোঃ শাহীন",            "১০৭৪",  "এসএস ফিটার",    "01676269469"),
    ComboRailwayStaff(13, "মোঃ ফয়সাল মিয়া",     "৮১৩",   "খালাসী",        "01943348360"),
    ComboRailwayStaff(14, "মোঃ শিপন মিয়া",       "১১২৬",  "খালাসী",        "01722609803"),
    ComboRailwayStaff(15, "মোঃ সফিকুল ইসলাম",    "৯৩৩",   "খালাসী",        "01721006811"),
    ComboRailwayStaff(16, "মোঃ রিয়াজ উদ্দিন খান","১০১৫",  "খালাসী",        "01766405026"),
    ComboRailwayStaff(17, "কাজী আসলাম উদ্দিন",   "১১২২",  "খালাসী",        "01726272268")
)

private val ComboRailwayOrange = Color(0xFFFF8C00)
private val ComboRailwayBg     = Color(0xFF1E1A0F)

@Composable
fun ComboRailwayStaffSection(context: android.content.Context, closeDrawer: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (expanded) ComboRailwayBg else Color.Transparent)
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(ComboRailwayOrange.copy(alpha = 0.18f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Train,
                contentDescription = null,
                tint = ComboRailwayOrange,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("রেলওয়ে", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ComboRailwayOrange)
            Text("ঢাকা ডিজেল ওয়ার্কসপ", fontSize = 11.sp, color = ComboRailwayOrange.copy(alpha = 0.65f))
        }
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = ComboRailwayOrange.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp)
        )
    }

    AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                .background(ComboRailwayBg)
                .padding(bottom = 8.dp)
        ) {
            Text(
                "মেকানিক্যাল সিডিউল সেকশন",
                fontSize = 11.sp,
                color = ComboRailwayOrange.copy(alpha = 0.7f),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp)
            )
            comboRailwayStaffList.forEach { staff ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val intent = Intent(Intent.ACTION_DIAL).apply {
                                data = Uri.parse("tel:${staff.phone}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(ComboRailwayOrange.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${staff.serial}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ComboRailwayOrange)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            staff.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = TextWhite, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${staff.designation}  •  TIN: ${staff.tin}",
                            fontSize = 10.sp, color = TextWhite.copy(alpha = 0.5f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
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
                            Icons.Default.Call, contentDescription = "Call ${staff.name}",
                            tint = Color.White, modifier = Modifier.size(16.dp)
                        )
                    }
                }
                if (staff.serial < comboRailwayStaffList.size) {
                    HorizontalDivider(
                        color = ComboRailwayOrange.copy(alpha = 0.08f),
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

package com.rasel.RasFocus.filemanager

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object SettingsManager {
    private const val PREFS_NAME = "FileManagerSettings"

    fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Appearance ────────────────────────────────────────────────────────────
    fun isDarkMode(context: Context) = getPrefs(context).getBoolean("dark_mode", true)
    fun setDarkMode(context: Context, v: Boolean) = getPrefs(context).edit().putBoolean("dark_mode", v).apply()

    fun getPrimaryColor(context: Context) = getPrefs(context).getInt("primary_color", 0xFF1565C0.toInt())
    fun setPrimaryColor(context: Context, v: Int) = getPrefs(context).edit().putInt("primary_color", v).apply()

    // ── File Browser ──────────────────────────────────────────────────────────
    fun showHiddenFiles(context: Context) = getPrefs(context).getBoolean("show_hidden", false)
    fun setShowHiddenFiles(context: Context, v: Boolean) = getPrefs(context).edit().putBoolean("show_hidden", v).apply()

    fun getDateFormat(context: Context) = getPrefs(context).getString("date_format", "MMM dd, yyyy") ?: "MMM dd, yyyy"
    fun setDateFormat(context: Context, v: String) = getPrefs(context).edit().putString("date_format", v).apply()

    fun getFileSizeUnit(context: Context) = getPrefs(context).getString("file_size_unit", "Auto") ?: "Auto"
    fun setFileSizeUnit(context: Context, v: String) = getPrefs(context).edit().putString("file_size_unit", v).apply()

    // ── Recycle Bin ───────────────────────────────────────────────────────────
    fun useRecycleBin(context: Context) = getPrefs(context).getBoolean("use_recycle_bin", true)
    fun setUseRecycleBin(context: Context, v: Boolean) = getPrefs(context).edit().putBoolean("use_recycle_bin", v).apply()

    fun showRecycleConfirmation(context: Context) = getPrefs(context).getBoolean("recycle_confirm", true)
    fun setShowRecycleConfirmation(context: Context, v: Boolean) = getPrefs(context).edit().putBoolean("recycle_confirm", v).apply()

    // ── Advanced ──────────────────────────────────────────────────────────────
    fun showHistory(context: Context) = getPrefs(context).getBoolean("show_history", true)
    fun setShowHistory(context: Context, v: Boolean) = getPrefs(context).edit().putBoolean("show_history", v).apply()

    fun storageFullNotification(context: Context) = getPrefs(context).getBoolean("storage_full_notif", true)
    fun setStorageFullNotification(context: Context, v: Boolean) = getPrefs(context).edit().putBoolean("storage_full_notif", v).apply()

    // ── Advanced menus ────────────────────────────────────────────────────────
    fun showHideUnhide(context: Context) = getPrefs(context).getBoolean("menu_hide", true)
    fun setShowHideUnhide(context: Context, v: Boolean) = getPrefs(context).edit().putBoolean("menu_hide", v).apply()

    fun showOpenAs(context: Context) = getPrefs(context).getBoolean("menu_open_as", true)
    fun setShowOpenAs(context: Context, v: Boolean) = getPrefs(context).edit().putBoolean("menu_open_as", v).apply()

    // ── App version ───────────────────────────────────────────────────────────
    fun getAppVersion(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (e: PackageManager.NameNotFoundException) { "1.0" }
    }
}

// ── Helper composables ────────────────────────────────────────────────────────
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = Color(0xFF00796B),
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp, end = 16.dp)
    )
}

@Composable
private fun SettingToggle(
    icon: ImageVector,
    title: String,
    subtitle: String = "",
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (subtitle.isNotEmpty()) ({ Text(subtitle, color = Color.Gray, fontSize = 12.sp) }) else null,
        leadingContent = { Icon(icon, contentDescription = null, tint = Color(0xFF00796B)) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF00796B))
            )
        },
        modifier = Modifier.clickable { onToggle(!checked) }
    )
    HorizontalDivider(color = Color(0xFFF0F0F0))
}

@Composable
private fun SettingItem(
    icon: ImageVector,
    title: String,
    subtitle: String = "",
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (subtitle.isNotEmpty()) ({ Text(subtitle, color = Color.Gray, fontSize = 12.sp) }) else null,
        leadingContent = { Icon(icon, contentDescription = null, tint = Color(0xFF00796B)) },
        modifier = Modifier.clickable { onClick() }
    )
    HorizontalDivider(color = Color(0xFFF0F0F0))
}

// ── Date format picker dialog ─────────────────────────────────────────────────
@Composable
private fun DateFormatDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val formats = listOf("MMM dd, yyyy", "dd/MM/yyyy", "MM/dd/yyyy", "yyyy-MM-dd", "dd MMM yyyy")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Date Format") },
        text = {
            Column {
                formats.forEach { fmt ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(fmt) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = fmt == current, onClick = { onSelect(fmt) },
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF00796B)))
                        Spacer(Modifier.width(8.dp))
                        Text(fmt)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── File size unit picker dialog ──────────────────────────────────────────────
@Composable
private fun FileSizeUnitDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val units = listOf("Auto", "B", "KB", "MB", "GB")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("File Size Unit") },
        text = {
            Column {
                units.forEach { u ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(u) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = u == current, onClick = { onSelect(u) },
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF00796B)))
                        Spacer(Modifier.width(8.dp))
                        Text(u)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── Main Settings Screen ──────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var showHidden       by remember { mutableStateOf(SettingsManager.showHiddenFiles(context)) }
    var useRecycle       by remember { mutableStateOf(SettingsManager.useRecycleBin(context)) }
    var recycleConfirm   by remember { mutableStateOf(SettingsManager.showRecycleConfirmation(context)) }
    var showHistory      by remember { mutableStateOf(SettingsManager.showHistory(context)) }
    var storageNotif     by remember { mutableStateOf(SettingsManager.storageFullNotification(context)) }
    var showHideMenu     by remember { mutableStateOf(SettingsManager.showHideUnhide(context)) }
    var showOpenAs       by remember { mutableStateOf(SettingsManager.showOpenAs(context)) }
    var dateFormat       by remember { mutableStateOf(SettingsManager.getDateFormat(context)) }
    var fileSizeUnit     by remember { mutableStateOf(SettingsManager.getFileSizeUnit(context)) }
    var showDateDialog   by remember { mutableStateOf(false) }
    var showSizeDialog   by remember { mutableStateOf(false) }
    val appVersion       = remember { SettingsManager.getAppVersion(context) }

    if (showDateDialog) {
        DateFormatDialog(
            current = dateFormat,
            onSelect = { dateFormat = it; SettingsManager.setDateFormat(context, it); showDateDialog = false },
            onDismiss = { showDateDialog = false }
        )
    }
    if (showSizeDialog) {
        FileSizeUnitDialog(
            current = fileSizeUnit,
            onSelect = { fileSizeUnit = it; SettingsManager.setFileSizeUnit(context, it); showSizeDialog = false },
            onDismiss = { showSizeDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF00796B))
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {

            // ── General Settings ───────────────────────────────────────────────
            item { SectionHeader("General Settings") }
            item {
                SettingToggle(
                    icon = Icons.Default.Visibility,
                    title = "Show Hidden Files",
                    subtitle = "Display files starting with a dot (.)",
                    checked = showHidden,
                    onToggle = { showHidden = it; SettingsManager.setShowHiddenFiles(context, it) }
                )
            }
            item {
                SettingItem(
                    icon = Icons.Default.CalendarToday,
                    title = "Date Format",
                    subtitle = dateFormat,
                    onClick = { showDateDialog = true }
                )
            }
            item {
                SettingItem(
                    icon = Icons.Default.Storage,
                    title = "File Size Unit",
                    subtitle = fileSizeUnit,
                    onClick = { showSizeDialog = true }
                )
            }

            // ── Recycle Bin ────────────────────────────────────────────────────
            item { SectionHeader("Recycle Bin Settings") }
            item {
                SettingToggle(
                    icon = Icons.Default.Delete,
                    title = "Use Recycle Bin by Default",
                    subtitle = "Move deleted files to bin instead of permanent delete",
                    checked = useRecycle,
                    onToggle = { useRecycle = it; SettingsManager.setUseRecycleBin(context, it) }
                )
            }
            item {
                SettingToggle(
                    icon = Icons.Default.Warning,
                    title = "Show Recycle Confirmation",
                    subtitle = "Ask before moving files to recycle bin",
                    checked = recycleConfirm,
                    onToggle = { recycleConfirm = it; SettingsManager.setShowRecycleConfirmation(context, it) }
                )
            }

            // ── Notification Settings ──────────────────────────────────────────
            item { SectionHeader("Notification Settings") }
            item {
                SettingToggle(
                    icon = Icons.Default.Notifications,
                    title = "Storage is Full",
                    subtitle = "Show notification when storage is over 97% full",
                    checked = storageNotif,
                    onToggle = { storageNotif = it; SettingsManager.setStorageFullNotification(context, it) }
                )
            }

            // ── Advanced Settings ──────────────────────────────────────────────
            item { SectionHeader("Advanced Settings") }
            item {
                SettingToggle(
                    icon = Icons.Default.History,
                    title = "Show History",
                    subtitle = "Remember recently visited folders",
                    checked = showHistory,
                    onToggle = { showHistory = it; SettingsManager.setShowHistory(context, it) }
                )
            }

            // ── Advanced Menus ─────────────────────────────────────────────────
            item { SectionHeader("Show Advanced Menus") }
            item {
                SettingToggle(
                    icon = Icons.Default.VisibilityOff,
                    title = "Hide / Unhide",
                    subtitle = "Show hide option in file context menu",
                    checked = showHideMenu,
                    onToggle = { showHideMenu = it; SettingsManager.setShowHideUnhide(context, it) }
                )
            }
            item {
                SettingToggle(
                    icon = Icons.Default.OpenInNew,
                    title = "Open As",
                    subtitle = "Show open as option in file context menu",
                    checked = showOpenAs,
                    onToggle = { showOpenAs = it; SettingsManager.setShowOpenAs(context, it) }
                )
            }

            // ── About ──────────────────────────────────────────────────────────
            item { SectionHeader("About") }
            item {
                ListItem(
                    headlineContent = { Text("RasFocus File Manager") },
                    supportingContent = { Text("Version $appVersion", color = Color.Gray, fontSize = 12.sp) },
                    leadingContent = { Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF00796B)) }
                )
                HorizontalDivider(color = Color(0xFFF0F0F0))
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

package com.rasel.RasFocus.filemanager

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ─────────────────────────────────────────────────────────────────────────────
// Data class — per-account cache stats
// ─────────────────────────────────────────────────────────────────────────────
data class AccountCacheInfo(
    val accountName: String,
    val offlineBytes: Long,   // "Available offline" pinned files
    val cachedBytes: Long,    // Auto-cached (metadata + recently opened)
    val totalBytes: Long = offlineBytes + cachedBytes
)

// ─────────────────────────────────────────────────────────────────────────────
// Main screen — "Manage Offline Files" (like PC Google Drive)
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveOfflineSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var accountInfoList by remember { mutableStateOf<List<AccountCacheInfo>>(emptyList()) }
    var totalBytes by remember { mutableStateOf(0L) }
    var isLoading by remember { mutableStateOf(true) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showClearOfflineDialog by remember { mutableStateOf(false) }

    // Load cache stats
    fun reload() {
        isLoading = true
        DriveCacheManager.init(context)
        val cacheDir = DriveCacheManager.getCacheDir(context)
        val pinnedIds = DriveCacheManager.getPinnedFileIds()

        // Gather per-account info from SharedPrefs keys "filelist_ACCOUNT_FOLDER"
        val prefs = context.getSharedPreferences("DriveCachePrefs", Context.MODE_PRIVATE)
        val accountSet = mutableSetOf<String>()
        prefs.all.keys.filter { it.startsWith("filelist_") }.forEach { key ->
            val parts = key.removePrefix("filelist_").split("_")
            if (parts.isNotEmpty()) accountSet.add(parts[0])
        }

        // Calculate sizes
        val infos = accountSet.map { account ->
            var offlineSize = 0L
            var cachedSize = 0L
            cacheDir.listFiles()?.forEach { f ->
                val size = f.length()
                val fileId = f.name.substringBefore("_")
                if (pinnedIds.contains(fileId)) offlineSize += size
                else cachedSize += size
            }
            // Approximate per account — metadata in prefs
            val metaSize = prefs.all.keys
                .filter { it.startsWith("filelist_${account}_") }
                .sumOf { k -> (prefs.getString(k, "") ?: "").length.toLong() }
            AccountCacheInfo(account, offlineSize, cachedSize + metaSize)
        }

        accountInfoList = infos
        totalBytes = infos.sumOf { it.totalBytes }
        isLoading = false
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { reload() }
    }

    // ── Clear Cache Dialog ────────────────────────────────────────────────────
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = Color(0xFF1565C0)) },
            title = { Text("Clear cached files?") },
            text = {
                Text(
                    "This removes temporarily cached files (recently opened). " +
                    "Files marked 'Available offline' are not affected. " +
                    "You can still browse file & folder names offline.",
                    color = Color.Gray
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // Keep pinned files — only remove non-pinned cache
                    val pinnedIds = DriveCacheManager.getPinnedFileIds()
                    DriveCacheManager.getCacheDir(context).listFiles()?.forEach { f ->
                        val fileId = f.name.substringBefore("_")
                        if (!pinnedIds.contains(fileId)) f.delete()
                    }
                    // Clear folder listing metadata
                    val prefs = context.getSharedPreferences("DriveCachePrefs", Context.MODE_PRIVATE)
                    prefs.edit().also { ed ->
                        prefs.all.keys
                            .filter { it.startsWith("filelist_") || it.startsWith("cachetime_") }
                            .forEach { ed.remove(it) }
                    }.apply()
                    showClearCacheDialog = false
                    android.widget.Toast.makeText(context, "Cache cleared", android.widget.Toast.LENGTH_SHORT).show()
                    reload()
                }) { Text("Clear", color = Color(0xFF1565C0)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) { Text("Cancel") }
            }
        )
    }

    // ── Clear Offline Files Dialog ────────────────────────────────────────────
    if (showClearOfflineDialog) {
        AlertDialog(
            onDismissRequest = { showClearOfflineDialog = false },
            icon = { Icon(Icons.Default.WifiOff, contentDescription = null, tint = Color.Red) },
            title = { Text("Remove offline files?") },
            text = {
                Text(
                    "This removes all files marked 'Available offline' from local storage. " +
                    "File and folder names will still be browsable offline, " +
                    "but file contents will need internet to open.",
                    color = Color.Gray
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val pinnedIds = DriveCacheManager.getPinnedFileIds().toSet()
                    pinnedIds.forEach { id -> DriveCacheManager.unpin(id) }
                    DriveCacheManager.getCacheDir(context).listFiles()?.forEach { f ->
                        val fileId = f.name.substringBefore("_")
                        if (pinnedIds.contains(fileId)) f.delete()
                    }
                    showClearOfflineDialog = false
                    android.widget.Toast.makeText(context, "Offline files removed", android.widget.Toast.LENGTH_SHORT).show()
                    reload()
                }) { Text("Remove", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showClearOfflineDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Offline Files") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A73E8),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFFF5F5F5)
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ── Header info card ─────────────────────────────────────────
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "Storage on your device",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Device storage is used to store files you make available offline " +
                                "and folder/file names for offline browsing (cache). " +
                                "You can free up space by clearing offline files or cache.",
                                fontSize = 13.sp,
                                color = Color.Gray,
                                lineHeight = 18.sp
                            )
                            Spacer(Modifier.height(16.dp))
                            // ── Total bar ────────────────────────────────────
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Total used", fontSize = 13.sp, color = Color.Gray)
                                Text(
                                    formatBytes(totalBytes),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            // ── Action buttons ───────────────────────────────
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { showClearOfflineDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8)),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) { Text("Clear offline files", fontSize = 12.sp) }
                                OutlinedButton(
                                    onClick = { showClearCacheDialog = true },
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) { Text("Clear cache", fontSize = 12.sp) }
                            }
                        }
                    }
                }

                // ── Per-account cards ────────────────────────────────────────
                items(accountInfoList) { info ->
                    AccountOfflineCard(info = info)
                }

                // ── Empty state ──────────────────────────────────────────────
                if (accountInfoList.isEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.CloudOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = Color.LightGray
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "No accounts have cached data.\nBrowse Google Drive to cache file names.",
                                    color = Color.Gray,
                                    fontSize = 14.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }

                // ── Info footer ──────────────────────────────────────────────
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F0FE)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = Color(0xFF1A73E8),
                                modifier = Modifier.size(18.dp).padding(top = 2.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "File & folder names are always cached for offline browsing. " +
                                "Use 'Make available offline' (long press a file → ⋮ menu) " +
                                "to download file contents for offline access.",
                                fontSize = 12.sp,
                                color = Color(0xFF1A73E8),
                                lineHeight = 17.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Per-account card (like PC Drive's per-account section)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun AccountOfflineCard(info: AccountCacheInfo) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            // Account header row
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Avatar circle with initial
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1A73E8)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = info.accountName.firstOrNull()?.uppercase() ?: "?",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(info.accountName, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                }
                Text(
                    formatBytes(info.totalBytes),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = Color(0xFF1A73E8)
                )
            }

            Spacer(Modifier.height(10.dp))

            // Stacked progress bar
            val totalSafe = if (info.totalBytes == 0L) 1L else info.totalBytes
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFFE0E0E0))
            ) {
                // Total bar (cached — lighter blue)
                Box(
                    modifier = Modifier
                        .fillMaxWidth(info.cachedBytes.toFloat() / totalSafe)
                        .fillMaxHeight()
                        .background(Color(0xFF90CAF9))
                )
                // Offline bar (pinned — darker blue on top)
                Box(
                    modifier = Modifier
                        .fillMaxWidth(info.offlineBytes.toFloat() / totalSafe)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF1A73E8))
                )
            }

            Spacer(Modifier.height(12.dp))
            Divider()
            Spacer(Modifier.height(8.dp))

            // Offline files row
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1A73E8))
                )
                Spacer(Modifier.width(10.dp))
                Text("Offline files", Modifier.weight(1f), fontSize = 14.sp)
                Text(
                    formatBytes(info.offlineBytes),
                    fontSize = 14.sp,
                    color = Color.Gray
                )
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = Color.LightGray,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            // Cached files row
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF90CAF9))
                )
                Spacer(Modifier.width(10.dp))
                Text("Cached files", Modifier.weight(1f), fontSize = 14.sp)
                Text(
                    formatBytes(info.cachedBytes),
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 bytes"
    val units = arrayOf("bytes", "KB", "MB", "GB")
    val i = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, 3)
    return if (i == 0) "$bytes bytes"
    else "%.1f %s".format(bytes / Math.pow(1024.0, i.toDouble()), units[i])
}

package com.rasel.RasFocus.drivebackup

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.google.api.services.drive.model.File
import com.rasel.RasFocus.filemanager.DriveCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DriveFileManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                DriveFileManagerScreen { finish() }
            }
        }
    }
}

// ── View mode ──────────────────────────────────────────────────────────────────
enum class ViewMode { LIST, GRID }

// ── Offline menu state ─────────────────────────────────────────────────────────
data class ContextMenuState(
    val file: File,
    val show: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DriveFileManagerScreen(onClose: () -> Unit) {
    val context     = LocalContext.current
    val scope       = rememberCoroutineScope()
    val accountName = com.google.android.gms.auth.api.signin.GoogleSignIn
        .getLastSignedInAccount(context)?.email ?: ""

    // ── State ──────────────────────────────────────────────────────────────────
    var navStack    by remember { mutableStateOf(listOf(Pair("root", "My Drive"))) }
    val currentFolder = navStack.last()

    var files       by remember { mutableStateOf<List<File>>(emptyList()) }
    var isLoading   by remember { mutableStateOf(true) }
    var errorMsg    by remember { mutableStateOf<String?>(null) }
    var showFixDrive by remember { mutableStateOf(false) }
    var isOnline    by remember { mutableStateOf(DriveCacheManager.isOnline(context)) }
    var viewMode    by remember { mutableStateOf(ViewMode.LIST) }
    var contextMenu by remember { mutableStateOf<ContextMenuState?>(null) }
    var offlineProgress by remember { mutableStateOf<Pair<String, Float>?>(null) } // fileName to 0..1

    // ── Permission recovery launcher ───────────────────────────────────────────
    val fixDriveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        showFixDrive = false
        scope.launch { loadFolder(context, accountName, currentFolder.first,
            onFiles = { files = it; errorMsg = null },
            onError = { msg, hasIntent -> errorMsg = msg; showFixDrive = hasIntent },
            onLoading = { isLoading = it }) }
    }

    // ── Network connectivity callback — auto-refresh when online ──────────────
    DisposableEffect(Unit) {
        DriveCacheManager.init(context)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!isOnline) {
                    isOnline = true
                    scope.launch {
                        // Internet came back → silently refresh
                        loadFolder(context, accountName, currentFolder.first,
                            onFiles = { files = it; errorMsg = null },
                            onError = { _, _ -> /* keep cached view */ },
                            onLoading = { isLoading = it })
                        // Also kick off background sync for pinned files
                        DriveBackgroundSyncWorker.schedule(context, accountName)
                    }
                }
            }
            override fun onLost(network: Network) { isOnline = false }
        }
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(req, cb)
        onDispose { cm.unregisterNetworkCallback(cb) }
    }

    // ── Initial load ───────────────────────────────────────────────────────────
    LaunchedEffect(currentFolder.first) {
        DriveCacheManager.init(context)
        loadFolder(context, accountName, currentFolder.first,
            onFiles = { files = it; errorMsg = null },
            onError = { msg, hasIntent -> errorMsg = msg; showFixDrive = hasIntent },
            onLoading = { isLoading = it })
    }

    BackHandler {
        if (navStack.size > 1) navStack = navStack.dropLast(1) else onClose()
    }

    // ── Context menu (long-press) ──────────────────────────────────────────────
    contextMenu?.let { menu ->
        if (menu.show) {
            val isPinned = DriveCacheManager.isPinned(menu.file.id ?: "")
            AlertDialog(
                onDismissRequest = { contextMenu = null },
                title = { Text(menu.file.name ?: "File", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                text = {
                    Column {
                        TextButton(onClick = {
                            contextMenu = null
                            val fid  = menu.file.id  ?: return@TextButton
                            val name = menu.file.name ?: return@TextButton
                            if (isPinned) {
                                DriveCacheManager.unpin(fid)
                                Toast.makeText(context, "Removed from offline", Toast.LENGTH_SHORT).show()
                            } else {
                                DriveCacheManager.pin(fid)
                                scope.launch {
                                    offlineProgress = Pair(name, 0f)
                                    val dest = DriveCacheManager.getCacheDir(context)
                                    if (menu.file.mimeType == "application/vnd.google-apps.folder") {
                                        val ok = DriveFileManager.downloadFolder(
                                            context, accountName, fid, name, dest)
                                        offlineProgress = null
                                        Toast.makeText(context,
                                            if (ok) "Folder saved offline" else "Download failed",
                                            Toast.LENGTH_SHORT).show()
                                    } else {
                                        offlineProgress = Pair(name, 0.5f)
                                        val f = DriveFileManager.downloadFile(
                                            context, accountName, fid, name, dest)
                                        offlineProgress = null
                                        if (f != null) {
                                            DriveCacheManager.markFileDownloaded(context, fid, name)
                                            Toast.makeText(context, "Saved offline: $name",
                                                Toast.LENGTH_SHORT).show()
                                        } else {
                                            DriveCacheManager.unpin(fid)
                                            Toast.makeText(context, "Download failed",
                                                Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (isPinned) Icons.Default.CloudDone else Icons.Default.CloudDownload,
                                    contentDescription = null,
                                    tint = if (isPinned) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(if (isPinned) "Remove offline copy" else "Make available offline")
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { contextMenu = null }) { Text("Close") }
                }
            )
        }
    }

    // ── Offline download progress snackbar ────────────────────────────────────
    offlineProgress?.let { (name, progress) ->
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF323232))
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Saving offline: $name",
                        color = Color.White, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF4CAF50)
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(currentFolder.second, fontSize = 17.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        if (!isOnline) {
                            Text("Offline", fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.75f))
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (navStack.size > 1) navStack = navStack.dropLast(1) else onClose()
                    }) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    // View toggle
                    IconButton(onClick = {
                        viewMode = if (viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
                    }) {
                        Icon(
                            if (viewMode == ViewMode.LIST) Icons.Default.GridView else Icons.Default.ViewList,
                            contentDescription = "Toggle view",
                            tint = Color.White
                        )
                    }
                    // Offline indicator dot
                    if (!isOnline) {
                        Box(
                            Modifier.size(8.dp)
                                .background(Color(0xFFFF9800), shape = RoundedCornerShape(50))
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF4A90D9),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFFF8FAFC))
        ) {
            when {
                isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                errorMsg != null -> Column(
                    Modifier.align(Alignment.Center).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Error: $errorMsg", color = Color.Red,
                        modifier = Modifier.padding(bottom = 16.dp))
                    if (showFixDrive) {
                        Button(onClick = {
                            DriveFileManager.lastRecoveryIntent?.let { fixDriveLauncher.launch(it) }
                        }, colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF9800))) {
                            Text("Grant Drive Permission")
                        }
                    } else {
                        Button(onClick = {
                            scope.launch {
                                loadFolder(context, accountName, currentFolder.first,
                                    onFiles = { files = it; errorMsg = null },
                                    onError = { m, h -> errorMsg = m; showFixDrive = h },
                                    onLoading = { isLoading = it })
                            }
                        }) { Text("Retry") }
                    }
                }

                files.isEmpty() -> Text("Folder is empty",
                    modifier = Modifier.align(Alignment.Center), color = Color.Gray)

                viewMode == ViewMode.LIST -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(files) { file ->
                            DriveFileListItem(
                                file = file,
                                accountName = accountName,
                                isOnline = isOnline,
                                onClick = {
                                    handleFileClick(context, scope, file, accountName, navStack,
                                        onNavigate = { navStack = it })
                                },
                                onLongClick = {
                                    contextMenu = ContextMenuState(file, show = true)
                                }
                            )
                            HorizontalDivider(color = Color.LightGray.copy(alpha = 0.4f))
                        }
                    }
                }

                else -> { // GRID
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(files) { file ->
                            DriveFileGridItem(
                                file = file,
                                accountName = accountName,
                                context = context,
                                isOnline = isOnline,
                                onClick = {
                                    handleFileClick(context, scope, file, accountName, navStack,
                                        onNavigate = { navStack = it })
                                },
                                onLongClick = {
                                    contextMenu = ContextMenuState(file, show = true)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Folder load helper ────────────────────────────────────────────────────────
private suspend fun loadFolder(
    context: Context,
    accountName: String,
    folderId: String,
    onFiles: (List<File>) -> Unit,
    onError: (String, Boolean) -> Unit,
    onLoading: (Boolean) -> Unit
) {
    onLoading(true)
    DriveCacheManager.init(context)

    if (DriveCacheManager.isOnline(context)) {
        val result = withContext(Dispatchers.IO) {
            DriveFileManager.listFiles(context, accountName, folderId)
        }
        if (result != null) {
            val sorted = result.sortedWith(
                compareBy({ it.mimeType != "application/vnd.google-apps.folder" },
                          { it.name?.lowercase() ?: "" })
            )
            DriveCacheManager.saveFileList(context, accountName, folderId, sorted)
            onFiles(sorted)
        } else {
            // Online but API failed — fall back to cache
            val cached = DriveCacheManager.loadFileList(context, accountName, folderId)
            if (cached != null) {
                onFiles(cached)
            } else {
                onError(DriveFileManager.lastError ?: "Unknown error",
                    DriveFileManager.lastRecoveryIntent != null)
            }
        }
    } else {
        // Offline — serve from cache
        val cached = DriveCacheManager.loadFileList(context, accountName, folderId)
        if (cached != null) {
            onFiles(cached)
        } else {
            onError("No internet and no cached data for this folder.", false)
        }
    }
    onLoading(false)
}

// ── File click handler ────────────────────────────────────────────────────────
private fun handleFileClick(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    file: File,
    accountName: String,
    navStack: List<Pair<String, String>>,
    onNavigate: (List<Pair<String, String>>) -> Unit
) {
    if (file.mimeType == "application/vnd.google-apps.folder") {
        onNavigate(navStack + Pair(file.id ?: "", file.name ?: "Folder"))
    } else {
        Toast.makeText(context, "Downloading ${file.name}…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val dest = DriveCacheManager.getCacheDir(context)
            val f = DriveFileManager.downloadFile(context, accountName,
                file.id ?: "", file.name ?: "file", dest)
            if (f != null) {
                DriveCacheManager.markFileDownloaded(context, file.id ?: "", file.name ?: "")
                Toast.makeText(context, "Downloaded: ${f.name}", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

// ── Image mime check ──────────────────────────────────────────────────────────
private fun isImageMime(mimeType: String?): Boolean =
    mimeType?.startsWith("image/") == true

// ── Thumbnail loader ──────────────────────────────────────────────────────────
@Composable
private fun DriveThumbnail(
    file: File,
    accountName: String,
    modifier: Modifier = Modifier,
    context: Context = LocalContext.current
) {
    var bitmap by remember(file.id) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(file.id) {
        if (!isImageMime(file.mimeType)) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            // 1. Check local cache first
            val cached = DriveCacheManager.getCachedFile(context, file.id ?: "", file.name ?: "")
            if (cached != null && cached.exists()) {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = 4 // small thumbnail
                    inJustDecodeBounds = false
                }
                bitmap = BitmapFactory.decodeFile(cached.absolutePath, opts)
                return@withContext
            }
            // 2. Download thumbnail from Drive if online
            if (DriveCacheManager.isOnline(context)) {
                val dest = DriveCacheManager.getCacheDir(context)
                val f = DriveFileManager.downloadFile(
                    context, accountName, file.id ?: "", file.name ?: "img", dest)
                if (f != null) {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    bitmap = BitmapFactory.decodeFile(f.absolutePath, opts)
                    DriveCacheManager.markFileDownloaded(context, file.id ?: "", file.name ?: "")
                }
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = file.name,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(modifier.background(Color(0xFFE3E8F0)),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Image, contentDescription = null,
                tint = Color(0xFFADB5BD), modifier = Modifier.size(32.dp))
        }
    }
}

// ── List item ─────────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DriveFileListItem(
    file: File,
    accountName: String,
    isOnline: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context  = LocalContext.current
    val isFolder = file.mimeType == "application/vnd.google-apps.folder"
    val isPinned = DriveCacheManager.isPinned(file.id ?: "")
    val isCached = DriveCacheManager.isFileCached(context, file.id ?: "", file.name ?: "")

    val dateStr = try {
        SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            .format(Date(file.modifiedTime?.value ?: 0))
    } catch (_: Exception) { "" }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon / thumbnail
        Box(
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (isImageMime(file.mimeType)) {
                DriveThumbnail(file, accountName,
                    Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), context)
            } else {
                Icon(
                    imageVector = if (isFolder) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                    contentDescription = null,
                    tint = if (isFolder) Color(0xFFFBC02D) else Color(0xFF5C6BC0),
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Text(file.name ?: "Unknown",
                fontWeight = FontWeight.Medium, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sizeKb = if (!isFolder) "${(file.getSize() ?: 0) / 1024} KB • " else ""
            Text("$sizeKb$dateStr", fontSize = 12.sp, color = Color.Gray)
        }

        // Offline / sync status icon
        Spacer(Modifier.width(8.dp))
        when {
            isPinned && isCached ->
                Icon(Icons.Default.CheckCircle, "Offline ready",
                    tint = Color(0xFF4CAF50), modifier = Modifier.size(18.dp))
            isPinned && !isCached ->
                Icon(Icons.Default.Sync, "Syncing",
                    tint = Color(0xFFFF9800), modifier = Modifier.size(18.dp))
            isCached ->
                Icon(Icons.Default.CloudDone, "Cached",
                    tint = Color(0xFF90CAF9), modifier = Modifier.size(18.dp))
            !isOnline ->
                Icon(Icons.Default.Cloud, "Cloud only",
                    tint = Color(0xFFBDBDBD), modifier = Modifier.size(18.dp))
        }
    }
}

// ── Grid item ─────────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DriveFileGridItem(
    file: File,
    accountName: String,
    context: Context,
    isOnline: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val isFolder = file.mimeType == "application/vnd.google-apps.folder"
    val isPinned = DriveCacheManager.isPinned(file.id ?: "")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Box(Modifier.fillMaxSize()) {
            // Main content
            if (isImageMime(file.mimeType)) {
                DriveThumbnail(file, accountName, Modifier.fillMaxSize(), context)
            } else {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (isFolder) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                        contentDescription = null,
                        tint = if (isFolder) Color(0xFFFBC02D) else Color(0xFF5C6BC0),
                        modifier = Modifier.size(44.dp)
                    )
                }
            }

            // Offline pin badge (top-right)
            if (isPinned) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp)
                        .size(20.dp)
                        .background(Color(0xFF4CAF50), RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, contentDescription = "Pinned offline",
                        tint = Color.White, modifier = Modifier.size(12.dp))
                }
            }

            // File name label at bottom
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(file.name ?: "",
                    color = Color.White, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

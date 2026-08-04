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
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.ui.window.Dialog
import com.google.api.services.drive.model.File
import com.rasel.RasFocus.filemanager.DriveCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─── Mime helpers ──────────────────────────────────────────────────────────────
private val IMAGE_MIMES = setOf(
    "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp", "image/heic"
)
private fun File.isImage() = mimeType in IMAGE_MIMES
private fun File.isFolder() = mimeType == "application/vnd.google-apps.folder"

// ─── Activity ─────────────────────────────────────────────────────────────────
class DriveFileManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DriveCacheManager.init(this)
        setContent {
            MaterialTheme {
                DriveFileManagerScreen { finish() }
            }
        }
    }
}

// ─── Main Screen ──────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DriveFileManagerScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val accountName = com.google.android.gms.auth.api.signin.GoogleSignIn
        .getLastSignedInAccount(context)?.email ?: ""

    // ── State ──────────────────────────────────────────────────────────────────
    var navStack   by remember { mutableStateOf(listOf(Pair("root", "My Drive"))) }
    val curFolder  = navStack.last()

    var files      by remember { mutableStateOf<List<File>>(emptyList()) }
    var isLoading  by remember { mutableStateOf(true) }
    var errorMsg   by remember { mutableStateOf<String?>(null) }
    var isOffline  by remember { mutableStateOf(!DriveCacheManager.isOnline(context)) }
    var isGrid     by remember { mutableStateOf(false) }

    // Long-press context menu
    var contextFile   by remember { mutableStateOf<File?>(null) }
    var showCtxMenu   by remember { mutableStateOf(false) }

    // Offline snackbar
    var showOfflineSnack by remember { mutableStateOf(false) }

    val fixDriveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        scope.launch { loadFolder(context, accountName, curFolder.first) { f, e, off ->
            files = f; errorMsg = e; isLoading = false; isOffline = off
        }}
    }

    // ── Network callback — auto-refresh when online ────────────────────────────
    DisposableEffect(Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Back online → reload current folder from Drive
                scope.launch {
                    isOffline = false
                    isLoading = true
                    loadFolder(context, accountName, curFolder.first) { f, e, off ->
                        files = f; errorMsg = e; isLoading = false; isOffline = off
                    }
                }
            }
            override fun onLost(network: Network) {
                scope.launch { isOffline = true }
            }
        }
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(req, cb)
        onDispose { cm.unregisterNetworkCallback(cb) }
    }

    // ── Load on folder change ──────────────────────────────────────────────────
    LaunchedEffect(curFolder.first) {
        isLoading = true
        loadFolder(context, accountName, curFolder.first) { f, e, off ->
            files = f; errorMsg = e; isLoading = false; isOffline = off
        }
    }

    BackHandler {
        if (navStack.size > 1) navStack = navStack.dropLast(1)
        else onClose()
    }

    // ── Scaffold ───────────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            Column {
                // Offline banner
                if (isOffline) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFFFF8E1))
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CloudOff, null,
                            tint = Color(0xFFF57C00), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Offline — showing cached data",
                            fontSize = 12.sp, color = Color(0xFFF57C00))
                    }
                }
                TopAppBar(
                    title = {
                        Column {
                            Text(curFolder.second, fontSize = 17.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            if (!isOffline) {
                                DriveCacheManager.cacheAgeMinutes(accountName, curFolder.first)
                                    ?.let { age ->
                                        Text("Synced ${age}m ago", fontSize = 10.sp,
                                            color = Color.White.copy(alpha = 0.7f))
                                    }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (navStack.size > 1) navStack = navStack.dropLast(1)
                            else onClose()
                        }) { Icon(Icons.Default.ArrowBack, "Back") }
                    },
                    actions = {
                        // Grid / List toggle
                        IconButton(onClick = { isGrid = !isGrid }) {
                            Icon(
                                if (isGrid) Icons.Default.ViewList else Icons.Default.GridView,
                                contentDescription = if (isGrid) "List view" else "Grid view",
                                tint = Color.White
                            )
                        }
                        // Refresh
                        if (!isOffline) {
                            IconButton(onClick = {
                                scope.launch {
                                    isLoading = true
                                    loadFolder(context, accountName, curFolder.first) { f, e, off ->
                                        files = f; errorMsg = e; isLoading = false; isOffline = off
                                    }
                                }
                            }) { Icon(Icons.Default.Refresh, "Refresh", tint = Color.White) }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF4A90D9),
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White
                    )
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(Color(0xFFF8FAFC))
        ) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                errorMsg != null -> ErrorView(
                    msg = errorMsg!!,
                    recoveryIntent = DriveFileManager.lastRecoveryIntent,
                    onFix = { DriveFileManager.lastRecoveryIntent?.let { fixDriveLauncher.launch(it) } },
                    onRetry = {
                        scope.launch {
                            isLoading = true
                            loadFolder(context, accountName, curFolder.first) { f, e, off ->
                                files = f; errorMsg = e; isLoading = false; isOffline = off
                            }
                        }
                    }
                )

                files.isEmpty() -> Text(
                    "Folder is empty",
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.Gray
                )

                isGrid -> DriveGridView(
                    files = files,
                    context = context,
                    accountName = accountName,
                    onFolderClick = { f -> navStack = navStack + Pair(f.id, f.name) },
                    onFileClick = { f ->
                        scope.launch { openFile(context, accountName, f) }
                    },
                    onLongPress = { f -> contextFile = f; showCtxMenu = true }
                )

                else -> DriveListView(
                    files = files,
                    context = context,
                    onFolderClick = { f -> navStack = navStack + Pair(f.id, f.name) },
                    onFileClick = { f ->
                        scope.launch { openFile(context, accountName, f) }
                    },
                    onLongPress = { f -> contextFile = f; showCtxMenu = true }
                )
            }
        }
    }

    // ── Context Menu (long-press) ──────────────────────────────────────────────
    if (showCtxMenu && contextFile != null) {
        DriveContextMenu(
            file = contextFile!!,
            context = context,
            accountName = accountName,
            onDismiss = { showCtxMenu = false },
            onRefreshNeeded = {
                showCtxMenu = false
                scope.launch {
                    isLoading = true
                    loadFolder(context, accountName, curFolder.first) { f, e, off ->
                        files = f; errorMsg = e; isLoading = false; isOffline = off
                    }
                }
            }
        )
    }
}

// ─── Load folder helper ───────────────────────────────────────────────────────
private suspend fun loadFolder(
    context: Context,
    accountName: String,
    folderId: String,
    onDone: (List<File>, String?, Boolean) -> Unit
) = withContext(Dispatchers.IO) {
    val online = DriveCacheManager.isOnline(context)
    if (online) {
        val result = DriveFileManager.listFiles(context, accountName, folderId)
        if (result != null) {
            val sorted = result.sortedWith(
                compareBy({ !it.isFolder() }, { it.name?.lowercase() ?: "" })
            )
            DriveCacheManager.saveFileList(context, accountName, folderId, sorted)
            withContext(Dispatchers.Main) { onDone(sorted, null, false) }
        } else {
            // Online but error — try cache fallback
            val cached = DriveCacheManager.loadFileList(context, accountName, folderId)
            val err = if (cached != null) null else DriveFileManager.lastError
            withContext(Dispatchers.Main) { onDone(cached ?: emptyList(), err, cached != null) }
        }
    } else {
        val cached = DriveCacheManager.loadFileList(context, accountName, folderId) ?: emptyList()
        withContext(Dispatchers.Main) { onDone(cached, null, true) }
    }
}

// ─── Open file helper ─────────────────────────────────────────────────────────
private suspend fun openFile(context: Context, accountName: String, file: File) {
    withContext(Dispatchers.Main) {
        Toast.makeText(context, "Downloading ${file.name}…", Toast.LENGTH_SHORT).show()
    }
    val cached = DriveCacheManager.getCachedFile(context, file.id, file.name ?: "")
    val target = cached ?: DriveFileManager.downloadFile(
        context, accountName, file.id, file.name ?: "file",
        DriveCacheManager.getCacheDir(context)
    )
    withContext(Dispatchers.Main) {
        if (target != null) {
            DriveCacheManager.markFileDownloaded(context, file.id, file.name ?: "")
            Toast.makeText(context, "Saved: ${target.absolutePath}", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
        }
    }
}

// ─── Grid view ────────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DriveGridView(
    files: List<File>,
    context: Context,
    accountName: String,
    onFolderClick: (File) -> Unit,
    onFileClick: (File) -> Unit,
    onLongPress: (File) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 100.dp),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(files, key = { it.id }) { file ->
            DriveGridItem(
                file = file,
                context = context,
                accountName = accountName,
                onClick = { if (file.isFolder()) onFolderClick(file) else onFileClick(file) },
                onLongPress = { onLongPress(file) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DriveGridItem(
    file: File,
    context: Context,
    accountName: String,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var thumb by remember(file.id) { mutableStateOf<Bitmap?>(
        DriveCacheManager.getThumbnail(context, file.id)
    )}
    val isPinned = DriveCacheManager.isFilePinned(file.id)
    val isCached = DriveCacheManager.isFileCached(context, file.id, file.name ?: "")

    // Async thumbnail load for images
    LaunchedEffect(file.id) {
        if (file.isImage() && thumb == null && DriveCacheManager.isOnline(context)) {
            scope.launch(Dispatchers.IO) {
                try {
                    val downloaded = DriveFileManager.downloadFile(
                        context, accountName, file.id, file.name ?: "img",
                        context.cacheDir
                    ) ?: return@launch
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = 4   // 1/4 size — fast thumbnail
                    }
                    val bmp = BitmapFactory.decodeFile(downloaded.absolutePath, opts)
                    if (bmp != null) {
                        DriveCacheManager.saveThumbnail(context, file.id, bmp)
                        withContext(Dispatchers.Main) { thumb = bmp }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (thumb != null) {
                androidx.compose.foundation.Image(
                    bitmap = thumb!!.asImageBitmap(),
                    contentDescription = file.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Folder or non-image icon
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (file.isFolder()) Color(0xFFFFF9C4) else Color(0xFFE8EAF6)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (file.isFolder()) Icons.Default.Folder
                                      else Icons.Default.InsertDriveFile,
                        contentDescription = null,
                        tint = if (file.isFolder()) Color(0xFFFBC02D) else Color(0xFF5C6BC0),
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            // Status badges
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (isPinned) {
                    Icon(Icons.Default.OfflinePin, "Pinned offline",
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(14.dp))
                }
                if (isCached) {
                    Icon(Icons.Default.CheckCircle, "Cached",
                        tint = Color(0xFF2196F3),
                        modifier = Modifier.size(14.dp))
                }
            }

            // File name at bottom
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 4.dp, vertical = 3.dp)
            ) {
                Text(
                    file.name ?: "Unknown",
                    color = Color.White,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ─── List view ────────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DriveListView(
    files: List<File>,
    context: Context,
    onFolderClick: (File) -> Unit,
    onFileClick: (File) -> Unit,
    onLongPress: (File) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(files, key = { it.id }) { file ->
            DriveListItem(
                file = file,
                context = context,
                onClick = { if (file.isFolder()) onFolderClick(file) else onFileClick(file) },
                onLongPress = { onLongPress(file) }
            )
            HorizontalDivider(color = Color.LightGray.copy(alpha = 0.4f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DriveListItem(
    file: File,
    context: Context,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val isPinned = DriveCacheManager.isFilePinned(file.id)
    val isCached = DriveCacheManager.isFileCached(context, file.id, file.name ?: "")
    val thumb    = if (file.isImage()) DriveCacheManager.getThumbnail(context, file.id) else null

    val dateStr = try {
        SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
            .format(Date(file.modifiedTime.value))
    } catch (_: Exception) { "" }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail or icon
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (file.isFolder()) Color(0xFFFFF9C4)
                    else if (thumb != null) Color.Transparent
                    else Color(0xFFE8EAF6)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (thumb != null) {
                androidx.compose.foundation.Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = if (file.isFolder()) Icons.Default.Folder
                                  else Icons.Default.InsertDriveFile,
                    contentDescription = null,
                    tint = if (file.isFolder()) Color(0xFFFBC02D) else Color(0xFF5C6BC0),
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(file.name ?: "Unknown", fontWeight = FontWeight.Medium, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!file.isFolder()) {
                val sizeKb = (file.getSize() ?: 0) / 1024
                Text("$dateStr · ${sizeKb}KB", fontSize = 11.sp, color = Color.Gray)
            } else {
                Text(dateStr, fontSize = 11.sp, color = Color.Gray)
            }
        }

        // Status icons
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (isPinned) Icon(Icons.Default.OfflinePin, "Pinned",
                tint = Color(0xFF4CAF50), modifier = Modifier.size(18.dp))
            if (isCached && !isPinned) Icon(Icons.Default.CheckCircle, "Cached",
                tint = Color(0xFF2196F3), modifier = Modifier.size(18.dp))
            if (!isCached && !isPinned) Icon(Icons.Default.Cloud, "Cloud only",
                tint = Color.LightGray, modifier = Modifier.size(18.dp))
        }
    }
}

// ─── Context menu dialog (long-press) ─────────────────────────────────────────
@Composable
private fun DriveContextMenu(
    file: File,
    context: Context,
    accountName: String,
    onDismiss: () -> Unit,
    onRefreshNeeded: () -> Unit
) {
    val scope    = rememberCoroutineScope()
    var isPinned by remember { mutableStateOf(DriveCacheManager.isFilePinned(file.id)) }
    var isBusy   by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                // Title
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (file.isFolder()) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                        null,
                        tint = if (file.isFolder()) Color(0xFFFBC02D) else Color(0xFF5C6BC0),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(file.name ?: "Unknown", fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider()

                // ── Make available offline ─────────────────────────────────────
                ContextMenuItem(
                    icon = if (isPinned) Icons.Default.OfflinePin else Icons.Default.DownloadForOffline,
                    label = if (isPinned) "Remove offline copy" else "Make available offline",
                    tint  = if (isPinned) Color(0xFF4CAF50) else Color(0xFF1976D2),
                    busy  = isBusy
                ) {
                    if (isPinned) {
                        DriveCacheManager.unpinFileOffline(file.id)
                        isPinned = false
                        Toast.makeText(context, "Removed from offline", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    } else {
                        // Pin + download in background
                        DriveCacheManager.pinFileOffline(file.id)
                        isPinned = true
                        isBusy = true
                        scope.launch {
                            val dest = DriveCacheManager.getCacheDir(context)
                            if (file.isFolder()) {
                                val ok = DriveFileManager.downloadFolder(
                                    context, accountName, file.id, file.name ?: "folder", dest
                                )
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context,
                                        if (ok) "Folder pinned for offline" else "Partial download",
                                        Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                val f = DriveFileManager.downloadFile(
                                    context, accountName, file.id, file.name ?: "file", dest
                                )
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context,
                                        if (f != null) "Available offline ✓" else "Download failed",
                                        Toast.LENGTH_SHORT).show()
                                }
                                if (f != null) DriveCacheManager.markFileDownloaded(
                                    context, file.id, file.name ?: "")
                            }
                            isBusy = false
                            onDismiss()
                        }
                    }
                }

                // ── Open / Download ────────────────────────────────────────────
                if (!file.isFolder()) {
                    ContextMenuItem(
                        icon  = Icons.Default.FileOpen,
                        label = "Open",
                        tint  = Color(0xFF607D8B)
                    ) {
                        scope.launch { openFile(context, accountName, file) }
                        onDismiss()
                    }
                }

                // ── Rename ─────────────────────────────────────────────────────
                ContextMenuItem(Icons.Default.DriveFileRenameOutline, "Rename",
                    Color(0xFF9C27B0)) {
                    // Rename dialog handled via separate state (kept simple here)
                    Toast.makeText(context, "Rename: long-press → rename", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }

                // ── Delete ─────────────────────────────────────────────────────
                ContextMenuItem(Icons.Default.Delete, "Delete", Color(0xFFF44336)) {
                    isBusy = true
                    scope.launch {
                        val ok = DriveFileManager.deleteFile(context, accountName, file.id)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context,
                                if (ok) "${file.name} deleted" else "Delete failed",
                                Toast.LENGTH_SHORT).show()
                            isBusy = false
                            if (ok) onRefreshNeeded() else onDismiss()
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun ContextMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    busy: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 15.sp)
    }
}

// ─── Error view ───────────────────────────────────────────────────────────────
@Composable
private fun BoxScope.ErrorView(
    msg: String,
    recoveryIntent: Intent?,
    onFix: () -> Unit,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.align(Alignment.Center).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.CloudOff, null, tint = Color.Gray, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(msg, color = Color.Gray, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
        if (recoveryIntent != null) {
            Button(onClick = onFix,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))) {
                Text("Grant Drive Permission")
            }
        } else {
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

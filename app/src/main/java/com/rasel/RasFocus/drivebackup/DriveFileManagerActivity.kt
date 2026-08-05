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
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.core.content.FileProvider
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.api.services.drive.model.File
import com.rasel.RasFocus.filemanager.DriveCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.net.URLEncoder
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
enum class ViewMode { LIST, GRID, PHOTOS }

// ── Offline menu state ─────────────────────────────────────────────────────────
data class ContextMenuState(
    val file: File,
    val show: Boolean = false
)

// ── Root composable — owns NavController ──────────────────────────────────────
@Composable
fun DriveFileManagerScreen(onClose: () -> Unit) {
    val navController = rememberNavController()
    val context       = LocalContext.current
    val accountName   = com.google.android.gms.auth.api.signin.GoogleSignIn
        .getLastSignedInAccount(context)?.email ?: ""

    // One-time metadata pre-cache (background, runs once per open)
    val scope = rememberCoroutineScope()
    LaunchedEffect(accountName) {
        if (accountName.isNotEmpty() && DriveCacheManager.isOnline(context)) {
            DriveMetadataSyncWorker.runNow(context, accountName)
            DriveMetadataSyncWorker.schedule(context, accountName)
        }
    }

    NavHost(
        navController = navController,
        startDestination = "folder/{folderId}/{folderName}",
    ) {
        composable(
            route = "folder/{folderId}/{folderName}",
            arguments = listOf(
                navArgument("folderId")   { type = NavType.StringType; defaultValue = "root" },
                navArgument("folderName") { type = NavType.StringType; defaultValue = "My Drive" }
            )
        ) { backStackEntry ->
            val folderId   = backStackEntry.arguments?.getString("folderId")   ?: "root"
            val folderName = backStackEntry.arguments?.getString("folderName")
                ?.let { URLDecoder.decode(it, "UTF-8") } ?: "My Drive"

            DriveFolderScreen(
                folderId    = folderId,
                folderName  = folderName,
                accountName = accountName,
                onNavigateToFolder = { id, name ->
                    val encoded = URLEncoder.encode(name, "UTF-8")
                    navController.navigate("folder/$id/$encoded")
                },
                onBack = {
                    if (!navController.popBackStack()) onClose()
                }
            )
        }
    }
}

// ── Per-folder screen — each subfolder is a NEW composable instance ────────────
// NavController পপ করলে এই instance destroy হয়, আগেরটা resume হয়।
// আগের files state সহ — কোনো reload নেই, কোনো blank নেই।
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DriveFolderScreen(
    folderId: String,
    folderName: String,
    accountName: String,
    onNavigateToFolder: (id: String, name: String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    // ── State — প্রতিটা folder এর নিজস্ব, NavController রক্ষা করে ────────────
    var files           by remember { mutableStateOf<List<File>>(emptyList()) }
    var isLoading       by remember { mutableStateOf(true) }
    var errorMsg        by remember { mutableStateOf<String?>(null) }
    var showFixDrive    by remember { mutableStateOf(false) }
    var isOnline        by remember { mutableStateOf(DriveCacheManager.isOnline(context)) }
    var viewMode        by remember { mutableStateOf(ViewMode.LIST) }
    var contextMenu     by remember { mutableStateOf<ContextMenuState?>(null) }
    var offlineProgress by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var isUploading     by remember { mutableStateOf(false) }
    var uploadProgress  by remember { mutableStateOf<String?>(null) }
    var isDownloading   by remember { mutableStateOf(false) }
    var downloadingName by remember { mutableStateOf<String?>(null) }

    // ── File picker for upload ─────────────────────────────────────────────────
    val uploadFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            isUploading = true
            var successCount = 0; var failCount = 0
            uris.forEach { uri ->
                uploadProgress = "Uploading ${successCount + failCount + 1}/${uris.size}..."
                val inputStream = context.contentResolver.openInputStream(uri)
                val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    c.moveToFirst()
                    if (idx >= 0) c.getString(idx) else "upload_${System.currentTimeMillis()}"
                } ?: "upload_${System.currentTimeMillis()}"
                if (inputStream != null) {
                    val tempFile = java.io.File(context.cacheDir, fileName)
                    tempFile.outputStream().use { out -> inputStream.copyTo(out) }
                    val result = DriveFileManager.uploadFile(context, accountName, tempFile, folderId)
                    tempFile.delete()
                    if (result != null) successCount++ else failCount++
                } else failCount++
            }
            isUploading = false; uploadProgress = null
            Toast.makeText(
                context,
                if (failCount == 0) "Uploaded $successCount file(s)"
                else "$successCount uploaded, $failCount failed",
                Toast.LENGTH_SHORT
            ).show()
            // Refresh this folder
            loadFolder(context, accountName, folderId,
                onFiles = { files = it; errorMsg = null },
                onError = { m, h -> errorMsg = m; showFixDrive = h },
                onLoading = { isLoading = it })
        }
    }

    // ── Permission recovery launcher ───────────────────────────────────────────
    val fixDriveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        showFixDrive = false
        scope.launch {
            loadFolder(context, accountName, folderId,
                onFiles = { files = it; errorMsg = null },
                onError = { msg, hasIntent -> errorMsg = msg; showFixDrive = hasIntent },
                onLoading = { isLoading = it })
        }
    }

    // ── Network callback ───────────────────────────────────────────────────────
    DisposableEffect(Unit) {
        DriveCacheManager.init(context)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!isOnline) {
                    isOnline = true
                    scope.launch {
                        loadFolder(context, accountName, folderId,
                            onFiles = { files = it; errorMsg = null },
                            onError = { _, _ -> },
                            onLoading = { isLoading = it })
                        DriveBackgroundSyncWorker.schedule(context, accountName)
                        DriveMetadataSyncWorker.runNow(context, accountName)
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

    // ── Load folder once on entry ──────────────────────────────────────────────
    // শুধু প্রথমবার চলে। Back করলে এই composable resume হয়, re-run হয় না।
    LaunchedEffect(Unit) {
        DriveCacheManager.init(context)
        loadFolder(context, accountName, folderId,
            onFiles = { files = it; errorMsg = null },
            onError = { msg, hasIntent -> errorMsg = msg; showFixDrive = hasIntent },
            onLoading = { isLoading = it })
    }

    // ── Context menu ──────────────────────────────────────────────────────────
    contextMenu?.let { menu ->
        if (menu.show) {
            val fid      = menu.file.id   ?: ""
            val fname    = menu.file.name ?: "File"
            val isPinned = DriveCacheManager.isPinned(fid)
            val isCached = DriveCacheManager.isFileCached(context, fid, fname)
            val isFolder = menu.file.mimeType == "application/vnd.google-apps.folder"

            AlertDialog(
                onDismissRequest = { contextMenu = null },
                title = {
                    Column {
                        Text(fname, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp)) {
                            if (isCached) {
                                Icon(Icons.Default.PhoneAndroid, null,
                                    tint = Color(0xFF4CAF50), modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("Local copy", fontSize = 11.sp, color = Color(0xFF4CAF50))
                            } else {
                                Icon(Icons.Default.Cloud, null,
                                    tint = Color(0xFF4A90D9), modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("Cloud only", fontSize = 11.sp, color = Color(0xFF4A90D9))
                            }
                        }
                    }
                },
                text = {
                    Column {
                        if (!isFolder) {
                            TextButton(onClick = {
                                contextMenu = null
                                if (isPinned) {
                                    DriveCacheManager.unpin(fid)
                                    Toast.makeText(context, "Removed from offline", Toast.LENGTH_SHORT).show()
                                } else {
                                    DriveCacheManager.pin(fid)
                                    scope.launch {
                                        offlineProgress = Pair(fname, 0.5f)
                                        val dest = DriveCacheManager.getCacheDir(context)
                                        val f = DriveFileManager.downloadFile(context, accountName, fid, fname, dest)
                                        offlineProgress = null
                                        if (f != null) {
                                            DriveCacheManager.markFileDownloaded(context, fid, fname)
                                            Toast.makeText(context, "Saved offline: $fname", Toast.LENGTH_SHORT).show()
                                        } else {
                                            DriveCacheManager.unpin(fid)
                                            Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
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
                        } else {
                            TextButton(onClick = {
                                contextMenu = null
                                DriveCacheManager.pin(fid)
                                scope.launch {
                                    offlineProgress = Pair(fname, 0f)
                                    val ok = DriveFileManager.downloadFolder(
                                        context, accountName, fid, fname, DriveCacheManager.getCacheDir(context))
                                    offlineProgress = null
                                    Toast.makeText(context,
                                        if (ok) "Folder saved offline" else "Download failed",
                                        Toast.LENGTH_SHORT).show()
                                }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CloudDownload, null,
                                        tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Download folder offline")
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        if (isCached) {
                            TextButton(onClick = {
                                contextMenu = null
                                val cacheFile = DriveCacheManager.getCachedFile(context, fid, fname)
                                val deleted = cacheFile?.delete() == true
                                DriveCacheManager.unpin(fid)
                                Toast.makeText(context,
                                    if (deleted) "Cache cleared: $fname" else "Nothing to clear",
                                    Toast.LENGTH_SHORT).show()
                            }, modifier = Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.DeleteSweep, null, tint = Color(0xFFE53935))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Clear local cache", color = Color(0xFFE53935))
                                }
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

    // ── Offline progress snackbar ──────────────────────────────────────────────
    offlineProgress?.let { (name, _) ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF323232))
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Saving offline: $name", color = Color.White, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFF4CAF50))
                }
            }
        }
    }

    // ── Scaffold ───────────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(folderName, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!isOnline) {
                            Text("Offline", fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f))
                        }
                    }
                },
                navigationIcon = {
                    // সহজ একটা back — NavController.popBackStack() শেষ কথা
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewMode = if (viewMode == ViewMode.PHOTOS) ViewMode.LIST else ViewMode.PHOTOS
                    }) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = "Photos",
                            tint = if (viewMode == ViewMode.PHOTOS) Color(0xFFFFD54F) else Color.White)
                    }
                    if (viewMode != ViewMode.PHOTOS) {
                        IconButton(onClick = {
                            viewMode = if (viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
                        }) {
                            Icon(
                                if (viewMode == ViewMode.LIST) Icons.Default.GridView else Icons.Default.ViewList,
                                contentDescription = "Toggle view", tint = Color.White
                            )
                        }
                    }
                    if (!isOnline) {
                        Box(Modifier.size(8.dp).background(Color(0xFFFF9800), RoundedCornerShape(50)))
                        Spacer(Modifier.width(8.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF4A90D9),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        floatingActionButton = {
            if (viewMode != ViewMode.PHOTOS && isOnline) {
                FloatingActionButton(
                    onClick = { uploadFileLauncher.launch("*/*") },
                    containerColor = Color(0xFF4A90D9), contentColor = Color.White
                ) {
                    if (isUploading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp),
                            color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.CloudUpload, contentDescription = "Upload")
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFFF8FAFC))
        ) {
            if (uploadProgress != null) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                    color = Color(0xFF4A90D9)
                )
            }

            if (isDownloading) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center) {
                    Card(shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
                        elevation = CardDefaults.cardElevation(8.dp)) {
                        Column(Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color(0xFF4A90D9), strokeWidth = 3.dp)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = if (downloadingName != null) "Downloading…\n${downloadingName!!}" else "Downloading…",
                                color = Color.White, fontSize = 14.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }

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
                        }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))) {
                            Text("Grant Drive Permission")
                        }
                    } else {
                        Button(onClick = {
                            scope.launch {
                                loadFolder(context, accountName, folderId,
                                    onFiles = { files = it; errorMsg = null },
                                    onError = { m, h -> errorMsg = m; showFixDrive = h },
                                    onLoading = { isLoading = it })
                            }
                        }) { Text("Retry") }
                    }
                }

                files.isEmpty() -> Text("Folder is empty",
                    modifier = Modifier.align(Alignment.Center), color = Color.Gray)

                viewMode == ViewMode.PHOTOS -> {
                    DrivePhotoGalleryScreen(accountName = accountName, isOnline = isOnline)
                }

                viewMode == ViewMode.LIST -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(files) { file ->
                            DriveFileListItem(
                                file = file,
                                accountName = accountName,
                                isOnline = isOnline,
                                onClick = {
                                    if (file.mimeType == "application/vnd.google-apps.folder") {
                                        onNavigateToFolder(file.id ?: "", file.name ?: "Folder")
                                    } else {
                                        scope.launch {
                                            openFile(context, scope, file, accountName,
                                                onDownloading = { dl ->
                                                    isDownloading = dl
                                                    downloadingName = if (dl) file.name else null
                                                })
                                        }
                                    }
                                },
                                onLongClick = { contextMenu = ContextMenuState(file, show = true) }
                            )
                            HorizontalDivider(color = Color.LightGray.copy(alpha = 0.4f))
                        }
                    }
                }

                else -> {
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
                                    if (file.mimeType == "application/vnd.google-apps.folder") {
                                        onNavigateToFolder(file.id ?: "", file.name ?: "Folder")
                                    } else {
                                        scope.launch {
                                            openFile(context, scope, file, accountName,
                                                onDownloading = { dl ->
                                                    isDownloading = dl
                                                    downloadingName = if (dl) file.name else null
                                                })
                                        }
                                    }
                                },
                                onLongClick = { contextMenu = ContextMenuState(file, show = true) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Folder load helper ─────────────────────────────────────────────────────────
private suspend fun loadFolder(
    context: Context,
    accountName: String,
    folderId: String,
    onFiles: (List<File>) -> Unit,
    onError: (String, Boolean) -> Unit,
    onLoading: (Boolean) -> Unit
) {
    DriveCacheManager.init(context)

    // Cache থাকলে instantly দেখাও, loading spinner নেই
    val cached = DriveCacheManager.loadFileList(context, accountName, folderId)
    if (cached != null) {
        onFiles(cached)
        // Background এ silently refresh
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
            }
        }
        return
    }

    // Cache নেই — spinner দেখাও এবং fetch করো
    onLoading(true)
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
            onError(DriveFileManager.lastError ?: "Unknown error",
                DriveFileManager.lastRecoveryIntent != null)
        }
    } else {
        onError("No internet and no cached data for this folder.", false)
    }
    onLoading(false)
}

// ── File open helper ──────────────────────────────────────────────────────────
private suspend fun openFile(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    file: File,
    accountName: String,
    onDownloading: (Boolean) -> Unit
) {
    val fid  = file.id   ?: return
    val name = file.name ?: return

    val cached = DriveCacheManager.getCachedFile(context, fid, name)
    if (cached != null && cached.exists()) {
        openDriveCachedFile(context, cached, file.mimeType)
        return
    }

    onDownloading(true)
    val dest       = DriveCacheManager.getCacheDir(context)
    val downloaded = withContext(Dispatchers.IO) {
        DriveFileManager.downloadFile(context, accountName, fid, name, dest)
    }
    onDownloading(false)

    if (downloaded != null) {
        DriveCacheManager.markFileDownloaded(context, fid, name)
        openDriveCachedFile(context, downloaded, file.mimeType)
    } else {
        Toast.makeText(context,
            "Download failed: ${DriveFileManager.lastError ?: "unknown"}",
            Toast.LENGTH_SHORT).show()
    }
}

// ── Open cached file via FileProvider ─────────────────────────────────────────
private fun openDriveCachedFile(context: Context, file: java.io.File, mimeType: String?) {
    try {
        val uri  = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val mime = mimeType?.takeIf { it != "application/vnd.google-apps.folder" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "*/*"

        val pkg = context.packageName.replace(".combo", "")
        val cls = try { Class.forName("$pkg.selfcontrol.study_tools.UniversalViewerActivity") }
                  catch (_: ClassNotFoundException) { null }
        if (cls != null && mime != "*/*") {
            context.startActivity(Intent(context, cls).apply {
                action = Intent.ACTION_VIEW
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return
        }
        context.startActivity(Intent.createChooser(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, "Open with"
        ))
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot open: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

// ── Image mime check ──────────────────────────────────────────────────────────
private fun isImageMime(mimeType: String?): Boolean =
    mimeType?.startsWith("image/") == true

// ── Thumbnail ─────────────────────────────────────────────────────────────────
@Composable
private fun DriveThumbnail(
    file: File,
    accountName: String,
    modifier: Modifier = Modifier,
    context: Context = LocalContext.current
) {
    val thumbUrl = file.thumbnailLink?.replace("=s220", "=s400")

    var localBitmap by remember(file.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file.id) {
        if (thumbUrl != null) return@LaunchedEffect
        if (!isImageMime(file.mimeType)) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val cached = DriveCacheManager.getCachedFile(context, file.id ?: "", file.name ?: "")
            if (cached != null && cached.exists()) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                localBitmap = BitmapFactory.decodeFile(cached.absolutePath, opts)
            }
        }
    }

    when {
        thumbUrl != null -> {
            AsyncImage(
                model = ImageRequest.Builder(context).data(thumbUrl).crossfade(true).build(),
                contentDescription = file.name,
                modifier = modifier,
                contentScale = ContentScale.Crop,
                error = androidx.compose.ui.graphics.painter.ColorPainter(Color(0xFFE3E8F0))
            )
        }
        localBitmap != null -> {
            Image(bitmap = localBitmap!!.asImageBitmap(), contentDescription = file.name,
                modifier = modifier, contentScale = ContentScale.Crop)
        }
        else -> {
            Box(modifier.background(Color(0xFFE3E8F0)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Image, contentDescription = null,
                    tint = Color(0xFFADB5BD), modifier = Modifier.size(32.dp))
            }
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
        Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center) {
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
            Text(file.name ?: "Unknown", fontWeight = FontWeight.Medium, fontSize = 15.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sizeKb = if (!isFolder) "${(file.getSize() ?: 0) / 1024} KB • " else ""
            Text("$sizeKb$dateStr", fontSize = 12.sp, color = Color.Gray)
        }

        Spacer(Modifier.width(8.dp))
        when {
            isPinned && isCached -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.PhoneAndroid, "Local",
                    tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                Text("Local", fontSize = 9.sp, color = Color(0xFF4CAF50))
            }
            isPinned && !isCached -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Sync, "Syncing",
                    tint = Color(0xFFFF9800), modifier = Modifier.size(16.dp))
                Text("Sync", fontSize = 9.sp, color = Color(0xFFFF9800))
            }
            isCached -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.PhoneAndroid, "Cached",
                    tint = Color(0xFF90CAF9), modifier = Modifier.size(16.dp))
                Text("Cache", fontSize = 9.sp, color = Color(0xFF90CAF9))
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Cloud, "Cloud",
                    tint = Color(0xFFBDBDBD), modifier = Modifier.size(16.dp))
                Text("Cloud", fontSize = 9.sp, color = Color(0xFFBDBDBD))
            }
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
            if (isImageMime(file.mimeType)) {
                DriveThumbnail(file, accountName, Modifier.fillMaxSize(), context)
            } else {
                Column(Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Icon(
                        imageVector = if (isFolder) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                        contentDescription = null,
                        tint = if (isFolder) Color(0xFFFBC02D) else Color(0xFF5C6BC0),
                        modifier = Modifier.size(44.dp)
                    )
                }
            }

            if (isPinned) {
                Box(Modifier.align(Alignment.TopEnd).padding(4.dp)
                    .size(20.dp).background(Color(0xFF4CAF50), RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, contentDescription = "Pinned offline",
                        tint = Color.White, modifier = Modifier.size(12.dp))
                }
            }

            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 6.dp, vertical = 3.dp)) {
                Text(file.name ?: "", color = Color.White, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

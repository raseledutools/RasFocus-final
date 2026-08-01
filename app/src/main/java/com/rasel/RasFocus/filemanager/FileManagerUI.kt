package com.rasel.RasFocus.filemanager

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SelectAll
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.rasel.RasFocus.drivebackup.DriveFileManager
import com.rasel.RasFocus.selfcontrol.study_tools.UniversalViewerActivity

@Composable
fun LocalFileScreen(
    path: String, 
    onNavigate: (NavState) -> Unit, 
    onBack: () -> Unit,
    clipboard: ClipboardState?,
    onSetClipboard: (ClipboardState?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedFiles by remember { mutableStateOf<Set<String>>(emptySet()) }
    var hasPermission by remember { mutableStateOf(LocalFileManager.hasStorageAccess(context)) }

    // Settings থেকে ফিরে এলে permission state refresh করা
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasPermission = LocalFileManager.hasStorageAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermission = LocalFileManager.hasStorageAccess(context)
        if (hasPermission) {
            isLoading = true
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val result = LocalFileManager.listFiles(path)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    files = result
                    isLoading = false
                }
            }
        }
    }
    
    val manageStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        hasPermission = LocalFileManager.hasStorageAccess(context)
        if (hasPermission) {
            isLoading = true
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val result = LocalFileManager.listFiles(path)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    files = result
                    isLoading = false
                }
            }
        }
    }

    LaunchedEffect(path, hasPermission) {
        if (hasPermission) {
            isLoading = true
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                val result = LocalFileManager.listFiles(path)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    files = result
                    isLoading = false
                }
            }
        } else {
            isLoading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Header: normal mode vs selection mode ──────────────────────────
        if (selectedFiles.isNotEmpty()) {
            SelectionTopBar(
                selectedCount = selectedFiles.size,
                totalCount = files.size,
                onClose = { selectedFiles = emptySet() },
                onSelectAll = {
                    selectedFiles = if (selectedFiles.size == files.size)
                        emptySet()
                    else
                        files.map { it.absolutePath }.toSet()
                }
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = path.substringAfterLast("/").ifEmpty { "Root" },
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
            }
        }

        if (!hasPermission) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Storage permission is required to view files.", color = Color.Gray)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            try {
                                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                }
                                manageStorageLauncher.launch(intent)
                            } catch (e: Exception) {
                                val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                manageStorageLauncher.launch(intent)
                            }
                        } else {
                            permissionLauncher.launch(arrayOf(
                                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ))
                        }
                    }) {
                        Text("Grant Permission")
                    }
                }
            }
        } else if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (files.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Folder is empty", color = Color.Gray)
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(files) { file ->
                        val isSelected = selectedFiles.contains(file.absolutePath)
                        FileListItem(
                            name = file.name,
                            isDirectory = file.isDirectory,
                            size = if (file.isDirectory) "" else formatFileSize(file.length()),
                            date = formatDate(file.lastModified()),
                            isSelected = isSelected,
                            onClick = {
                                if (selectedFiles.isNotEmpty()) {
                                    selectedFiles = if (isSelected) selectedFiles - file.absolutePath else selectedFiles + file.absolutePath
                                } else {
                                    if (file.isDirectory) {
                                        onNavigate(NavState.Local(file.absolutePath))
                                    } else {
                                        openLocalFile(context, file)
                                    }
                                }
                            },
                            onLongClick = {
                                selectedFiles = if (isSelected) selectedFiles - file.absolutePath else selectedFiles + file.absolutePath
                            }
                        )
                    }
                }
                
                if (clipboard != null && selectedFiles.isEmpty()) {
                    FloatingActionButton(
                        onClick = {
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                var success = true
                                if (clipboard.sourceEnv == "Local") {
                                    for (item in clipboard.items) {
                                        val src = java.io.File(item)
                                        val dst = java.io.File(path, src.name)
                                        try {
                                            src.copyRecursively(dst, overwrite = true)
                                            if (clipboard.isCut) src.deleteRecursively()
                                        } catch (e: Exception) { success = false }
                                    }
                                } else if (clipboard.sourceEnv == "Cloud") {
                                    val acc = clipboard.accountName ?: ""
                                    for (i in clipboard.items.indices) {
                                        val id = clipboard.items[i]
                                        val name = clipboard.itemNames.getOrNull(i) ?: "unknown_file"
                                        val result = com.rasel.RasFocus.drivebackup.DriveFileManager.downloadFolder(
                                            context, acc, id, name, java.io.File(path)
                                        )
                                        if (!result) {
                                            // Fallback to downloading as single file if not a folder (downloadFolder handles this internally, but if it fails completely maybe it was just a file)
                                            val fileResult = com.rasel.RasFocus.drivebackup.DriveFileManager.downloadFile(context, acc, id, name, java.io.File(path))
                                            if (fileResult == null) success = false
                                        }
                                        if (clipboard.isCut) {
                                            // Optional: delete from Drive API (needs delete support, skipping for safety unless explicitly requested)
                                        }
                                    }
                                }
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    Toast.makeText(context, if (success) "Pasted successfully" else "Some items failed to paste", Toast.LENGTH_SHORT).show()
                                    onSetClipboard(null)
                                    // Trigger refresh
                                    files = LocalFileManager.listFiles(path)
                                }
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                    }
                }
            }
        }
    }
    
        // ── Footer: selection action bar ──────────────────────────────────
        if (selectedFiles.isNotEmpty()) {
            SelectionBottomBar(
                onCopy = {
                    onSetClipboard(ClipboardState("Local", selectedFiles.toList(), isCut = false))
                    selectedFiles = emptySet()
                },
                onMove = {
                    onSetClipboard(ClipboardState("Local", selectedFiles.toList(), isCut = true))
                    selectedFiles = emptySet()
                },
                onRename = {
                    Toast.makeText(context, "Rename coming soon", Toast.LENGTH_SHORT).show()
                },
                onDelete = {
                    Toast.makeText(context, "Delete coming soon", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

@Composable
fun CloudFileScreen(
    accountName: String,
    folderId: String, 
    pathName: String, 
    onNavigate: (NavState) -> Unit, 
    onBack: () -> Unit,
    clipboard: ClipboardState?,
    onSetClipboard: (ClipboardState?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<com.google.api.services.drive.model.File>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var selectedFiles by remember { mutableStateOf<Set<String>>(emptySet()) }
    
    val fixDriveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        scope.launch {
            isLoading = true
            val result = DriveFileManager.listFiles(context, accountName, folderId)
            if (result != null) {
                files = result
                errorMsg = null
            } else {
                errorMsg = DriveFileManager.lastError
            }
            isLoading = false
        }
    }

    LaunchedEffect(folderId) {
        isLoading = true
        val result = DriveFileManager.listFiles(context, accountName, folderId)
        if (result != null) {
            files = result.sortedWith(compareBy({ it.mimeType != "application/vnd.google-apps.folder" }, { it.name.lowercase() }))
            errorMsg = null
        } else {
            errorMsg = DriveFileManager.lastError
            val recoveryIntent: Intent? = DriveFileManager.lastRecoveryIntent
            if (recoveryIntent != null) {
                fixDriveLauncher.launch(recoveryIntent)
            }
        }
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Header: normal vs selection mode ──────────────────────────────
        if (selectedFiles.isNotEmpty()) {
            SelectionTopBar(
                selectedCount = selectedFiles.size,
                totalCount = files.size,
                onClose = { selectedFiles = emptySet() },
                onSelectAll = {
                    selectedFiles = if (selectedFiles.size == files.size)
                        emptySet()
                    else
                        files.map { it.id }.toSet()
                }
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = pathName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (errorMsg != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Error: $errorMsg", color = Color.Red, modifier = Modifier.padding(16.dp))
            }
        } else if (files.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Folder is empty", color = Color.Gray)
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(files) { file ->
                        val isDir = file.mimeType == "application/vnd.google-apps.folder"
                        val isSelected = selectedFiles.contains(file.id)
                        FileListItem(
                            name = file.name,
                            isDirectory = isDir,
                            size = if (isDir || file.size == null) "" else formatFileSize(file.size.toLong()),
                            date = file.modifiedTime?.value?.let { formatDate(it) } ?: "",
                            isSelected = isSelected,
                            onClick = {
                                if (selectedFiles.isNotEmpty()) {
                                    selectedFiles = if (isSelected) selectedFiles - file.id else selectedFiles + file.id
                                } else {
                                    if (isDir) {
                                        onNavigate(NavState.Cloud(accountName, file.id, file.name))
                                    } else {
                                        Toast.makeText(context, "Downloading...", Toast.LENGTH_SHORT).show()
                                        scope.launch {
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                DriveFileManager.downloadFile(context, accountName, file.id, file.name)
                                            }
                                            Toast.makeText(context, "Downloaded to Cache", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            onLongClick = {
                                selectedFiles = if (isSelected) selectedFiles - file.id else selectedFiles + file.id
                            }
                        )
                    }
                }
                
                if (clipboard != null && selectedFiles.isEmpty()) {
                    FloatingActionButton(
                        onClick = {
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                var success = true
                                if (clipboard.sourceEnv == "Local") {
                                    for (item in clipboard.items) {
                                        val src = java.io.File(item)
                                        if (src.isDirectory) {
                                            if (!com.rasel.RasFocus.drivebackup.DriveFileManager.uploadFolder(context, accountName, src, folderId)) {
                                                success = false
                                            }
                                        } else {
                                            if (com.rasel.RasFocus.drivebackup.DriveFileManager.uploadFile(context, accountName, src, folderId) == null) {
                                                success = false
                                            }
                                        }
                                        if (clipboard.isCut) {
                                            try { src.deleteRecursively() } catch (e: Exception) {}
                                        }
                                    }
                                } else if (clipboard.sourceEnv == "Cloud") {
                                    val srcAccount = clipboard.accountName ?: accountName
                                    for (id in clipboard.items) {
                                        val result = if (clipboard.isCut) {
                                            com.rasel.RasFocus.drivebackup.DriveFileManager.moveFile(context, srcAccount, id, folderId, "root") // We don't have oldParentId easily accessible, this might need refinement
                                        } else {
                                            com.rasel.RasFocus.drivebackup.DriveFileManager.copyFile(context, srcAccount, id, folderId)
                                        }
                                        if (result == null) success = false
                                    }
                                }
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    Toast.makeText(context, if (success) "Pasted successfully" else "Some items failed to paste", Toast.LENGTH_SHORT).show()
                                    onSetClipboard(null)
                                    // Trigger refresh
                                    isLoading = true
                                    files = com.rasel.RasFocus.drivebackup.DriveFileManager.listFiles(context, accountName, folderId)?.sortedWith(compareBy({ it.mimeType != "application/vnd.google-apps.folder" }, { it.name.lowercase() })) ?: emptyList()
                                    isLoading = false
                                }
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                    }
                }
            }
        }
    }
    
        // ── Footer: selection action bar ──────────────────────────────────
        if (selectedFiles.isNotEmpty()) {
            SelectionBottomBar(
                onCopy = {
                    val names = selectedFiles.mapNotNull { id -> files.find { it.id == id }?.name }
                    onSetClipboard(ClipboardState("Cloud", selectedFiles.toList(), itemNames = names, isCut = false, accountName = accountName))
                    selectedFiles = emptySet()
                },
                onMove = {
                    val names = selectedFiles.mapNotNull { id -> files.find { it.id == id }?.name }
                    onSetClipboard(ClipboardState("Cloud", selectedFiles.toList(), itemNames = names, isCut = true, accountName = accountName))
                    selectedFiles = emptySet()
                },
                onRename = {
                    Toast.makeText(context, "Rename coming soon", Toast.LENGTH_SHORT).show()
                },
                onDelete = {
                    Toast.makeText(context, "Delete coming soon", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListItem(
    name: String,
    isDirectory: Boolean,
    size: String,
    date: String,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) Color(0xFFE0F7FA) else Color.Transparent)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
            contentDescription = null,
            tint = if (isDirectory) Color(0xFFFFA000) else Color.Gray,
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row {
                Text(text = date, fontSize = 12.sp, color = Color.Gray)
                if (size.isNotEmpty()) {
                    Text(text = " • $size", fontSize = 12.sp, color = Color.Gray)
                }
            }
        }
    }
}

fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.US, "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    return sdf.format(timestamp)
}

// ── File opener — FileProvider URI বানিয়ে UniversalViewerActivity তে পাঠায় ──
fun openLocalFile(context: android.content.Context, file: java.io.File) {
    try {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val mime = context.contentResolver.getType(uri)
            ?: getMimeFromExtension(file.extension.lowercase())
        val intent = Intent(context, UniversalViewerActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: IllegalArgumentException) {
        // FileProvider path not configured — fallback to system opener
        try {
            val uri = Uri.fromFile(file)
            val mime = getMimeFromExtension(file.extension.lowercase())
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e2: Exception) {
            Toast.makeText(context, "Cannot open: ${file.name}", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

fun getMimeFromExtension(ext: String): String = when (ext) {
    "pdf"                       -> "application/pdf"
    "jpg", "jpeg"               -> "image/jpeg"
    "png"                       -> "image/png"
    "gif"                       -> "image/gif"
    "webp"                      -> "image/webp"
    "bmp"                       -> "image/bmp"
    "heic", "heif"              -> "image/heic"
    "mp4", "mkv", "mov", "avi",
    "3gp", "webm"               -> "video/mp4"
    "mp3", "m4a", "aac",
    "ogg", "flac", "wav"        -> "audio/mpeg"
    "docx"                      -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "doc"                       -> "application/msword"
    "pptx"                      -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "ppt"                       -> "application/vnd.ms-powerpoint"
    "xlsx"                      -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "xls"                       -> "application/vnd.ms-excel"
    "txt", "md", "kt", "java",
    "py", "js", "ts", "html",
    "css", "xml", "json",
    "yaml", "yml", "csv",
    "sh", "c", "cpp", "h"       -> "text/plain"
    "apk"                       -> "application/vnd.android.package-archive"
    "zip"                       -> "application/zip"
    "rar"                       -> "application/x-rar-compressed"
    else                        -> "*/*"
}

// ── Selection top bar — image এর মত: X | 2/56 | select-all icons ──────────
@Composable
fun SelectionTopBar(
    selectedCount: Int,
    totalCount: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit
) {
    Surface(
        color = Color(0xFF1A6B6B), // teal, image এর মত
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // X button — selection বাতিল
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Cancel selection",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            // Counter: 2/56
            Text(
                text = "$selectedCount/$totalCount",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            // Select-all (filled square icon)
            IconButton(onClick = onSelectAll) {
                Icon(
                    imageVector = Icons.Default.SelectAll,
                    contentDescription = "Select all",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
            // Partial / range select (dashed square — MoreVert দিয়ে approximate করা)
            IconButton(onClick = onSelectAll) {
                Icon(
                    imageVector = Icons.Default.Deselect,
                    contentDescription = "Deselect all",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

// ── Selection bottom bar — image এর মত: Copy | Move | Rename | Delete | More
@Composable
fun SelectionBottomBar(
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = Color(0xFF1A6B6B), // teal — image এর মত
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SelectionAction(icon = Icons.Default.ContentCopy,  label = "Copy",   onClick = onCopy)
            SelectionAction(icon = Icons.Default.DriveFileMove, label = "Move",  onClick = onMove)
            SelectionAction(icon = Icons.Default.Edit,          label = "Rename", onClick = onRename)
            SelectionAction(icon = Icons.Default.Delete,        label = "Delete", onClick = onDelete)
            SelectionAction(icon = Icons.Default.MoreVert,      label = "More",   onClick = {})
        }
    }
}

@Composable
private fun SelectionAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

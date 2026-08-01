package com.rasel.RasFocus.filemanager

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material.icons.filled.VideoFile
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.rasel.RasFocus.drivebackup.DriveFileManager
import com.rasel.RasFocus.selfcontrol.study_tools.UniversalViewerActivity

// ── Rename Dialog — local ও cloud উভয়তে ব্যবহার হয় ──────────────────────
@Composable
fun RenameDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("New name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = newName.trim()
                    if (trimmed.isNotEmpty()) onConfirm(trimmed)
                },
                enabled = newName.trim().isNotEmpty() && newName.trim() != currentName
            ) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ── Delete Confirmation Dialog ─────────────────────────────────────────────
@Composable
fun DeleteConfirmDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete") },
        text = {
            Text(
                if (count == 1) "Delete this item permanently?"
                else "Delete $count items permanently?"
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = Color(0xFFD32F2F))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

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

    // Rename dialog state
    var showRenameDialog by remember { mutableStateOf(false) }
    // Single-select rename: which file to rename
    var renameTarget by remember { mutableStateOf<File?>(null) }

    // Delete confirm dialog state
    var showDeleteDialog by remember { mutableStateOf(false) }

    // ── Helper: refresh file list ──────────────────────────────────────────
    fun refreshFiles() {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result = LocalFileManager.listFiles(path)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                files = result
            }
        }
    }

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

    // ── Rename Dialog ──────────────────────────────────────────────────────
    if (showRenameDialog && renameTarget != null) {
        RenameDialog(
            currentName = renameTarget!!.name,
            onConfirm = { newName ->
                val target = renameTarget!!
                val dest = File(target.parent, newName)
                val ok = target.renameTo(dest)
                Toast.makeText(
                    context,
                    if (ok) "Renamed to $newName" else "Rename failed",
                    Toast.LENGTH_SHORT
                ).show()
                showRenameDialog = false
                renameTarget = null
                selectedFiles = emptySet()
                if (ok) refreshFiles()
            },
            onDismiss = {
                showRenameDialog = false
                renameTarget = null
            }
        )
    }

    // ── Delete Confirm Dialog ──────────────────────────────────────────────
    if (showDeleteDialog) {
        DeleteConfirmDialog(
            count = selectedFiles.size,
            onConfirm = {
                showDeleteDialog = false
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    var allOk = true
                    for (path in selectedFiles) {
                        val f = File(path)
                        if (!f.deleteRecursively()) allOk = false
                    }
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            if (allOk) "Deleted successfully" else "Some items could not be deleted",
                            Toast.LENGTH_SHORT
                        ).show()
                        selectedFiles = emptySet()
                        refreshFiles()
                    }
                }
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Header: normal mode vs selection mode ──────────────────────────────
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
                            localFile = if (file.isDirectory) null else file,
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
                                            val fileResult = com.rasel.RasFocus.drivebackup.DriveFileManager.downloadFile(context, acc, id, name, java.io.File(path))
                                            if (fileResult == null) success = false
                                        }
                                    }
                                }
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    Toast.makeText(context, if (success) "Pasted successfully" else "Some items failed to paste", Toast.LENGTH_SHORT).show()
                                    onSetClipboard(null)
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
                    if (selectedFiles.size == 1) {
                        val filePath = selectedFiles.first()
                        renameTarget = File(filePath)
                        showRenameDialog = true
                    } else {
                        Toast.makeText(context, "Select only one item to rename", Toast.LENGTH_SHORT).show()
                    }
                },
                onDelete = {
                    showDeleteDialog = true
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

    // Rename dialog state (Cloud)
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTargetId by remember { mutableStateOf("") }
    var renameTargetName by remember { mutableStateOf("") }

    // Delete confirm dialog state
    var showDeleteDialog by remember { mutableStateOf(false) }

    // ── Helper: refresh cloud file list ────────────────────────────────────
    fun refreshFiles() {
        scope.launch {
            isLoading = true
            val result = DriveFileManager.listFiles(context, accountName, folderId)
            files = result?.sortedWith(compareBy({ it.mimeType != "application/vnd.google-apps.folder" }, { it.name.lowercase() })) ?: files
            isLoading = false
        }
    }
    
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

    // ── Cloud Rename Dialog ────────────────────────────────────────────────
    if (showRenameDialog && renameTargetId.isNotEmpty()) {
        RenameDialog(
            currentName = renameTargetName,
            onConfirm = { newName ->
                showRenameDialog = false
                scope.launch {
                    val result = DriveFileManager.renameFile(context, accountName, renameTargetId, newName)
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            if (result != null) "Renamed to $newName" else "Rename failed",
                            Toast.LENGTH_SHORT
                        ).show()
                        renameTargetId = ""
                        renameTargetName = ""
                        selectedFiles = emptySet()
                        if (result != null) refreshFiles()
                    }
                }
            },
            onDismiss = {
                showRenameDialog = false
                renameTargetId = ""
                renameTargetName = ""
            }
        )
    }

    // ── Cloud Delete Confirm Dialog ────────────────────────────────────────
    if (showDeleteDialog) {
        DeleteConfirmDialog(
            count = selectedFiles.size,
            onConfirm = {
                showDeleteDialog = false
                scope.launch {
                    var allOk = true
                    for (fileId in selectedFiles) {
                        val ok = DriveFileManager.deleteFile(context, accountName, fileId)
                        if (!ok) allOk = false
                    }
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            if (allOk) "Deleted from Drive" else "Some items could not be deleted",
                            Toast.LENGTH_SHORT
                        ).show()
                        selectedFiles = emptySet()
                        refreshFiles()
                    }
                }
            },
            onDismiss = { showDeleteDialog = false }
        )
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
                                            com.rasel.RasFocus.drivebackup.DriveFileManager.moveFile(context, srcAccount, id, folderId, "root")
                                        } else {
                                            com.rasel.RasFocus.drivebackup.DriveFileManager.copyFile(context, srcAccount, id, folderId)
                                        }
                                        if (result == null) success = false
                                    }
                                }
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    Toast.makeText(context, if (success) "Pasted successfully" else "Some items failed to paste", Toast.LENGTH_SHORT).show()
                                    onSetClipboard(null)
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
                    if (selectedFiles.size == 1) {
                        val fileId = selectedFiles.first()
                        val fileName = files.find { it.id == fileId }?.name ?: ""
                        renameTargetId = fileId
                        renameTargetName = fileName
                        showRenameDialog = true
                    } else {
                        Toast.makeText(context, "Select only one item to rename", Toast.LENGTH_SHORT).show()
                    }
                },
                onDelete = {
                    showDeleteDialog = true
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
    localFile: java.io.File? = null,   // thumbnail এর জন্য — local file হলে দেওয়া হয়
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val ext = name.substringAfterLast('.', "").lowercase()
    val isImage = ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    val isPdf   = ext == "pdf"
    val isVideo = ext in setOf("mp4", "mkv", "mov", "avi", "3gp", "webm")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) Color(0xFFB2DFDB) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── Thumbnail / Icon ────────────────────────────────────────────
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (!isDirectory && localFile != null && (isImage || isPdf || isVideo)) {
                // Native thumbnail — Coil image, PdfRenderer for PDF, ThumbnailUtils for video
                val bitmapState = remember(localFile.absolutePath) {
                    androidx.compose.runtime.mutableStateOf<android.graphics.Bitmap?>(null)
                }
                LaunchedEffect(localFile.absolutePath) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val bmp: android.graphics.Bitmap? = when {
                                isImage -> {
                                    val opts = android.graphics.BitmapFactory.Options().apply {
                                        inJustDecodeBounds = true
                                    }
                                    android.graphics.BitmapFactory.decodeFile(localFile.absolutePath, opts)
                                    val scale = maxOf(1, minOf(opts.outWidth, opts.outHeight) / 96)
                                    val opts2 = android.graphics.BitmapFactory.Options().apply {
                                        inSampleSize = scale
                                    }
                                    android.graphics.BitmapFactory.decodeFile(localFile.absolutePath, opts2)
                                }
                                isPdf -> {
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                        val fd = android.os.ParcelFileDescriptor.open(
                                            localFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY
                                        )
                                        val renderer = android.graphics.pdf.PdfRenderer(fd)
                                        val page = renderer.openPage(0)
                                        val bmp = android.graphics.Bitmap.createBitmap(
                                            96, (96 * page.height / page.width.toFloat()).toInt(),
                                            android.graphics.Bitmap.Config.ARGB_8888
                                        )
                                        bmp.eraseColor(android.graphics.Color.WHITE)
                                        page.render(bmp, null, null,
                                            android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                        page.close()
                                        renderer.close()
                                        fd.close()
                                        bmp
                                    } else null
                                }
                                isVideo -> {
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                        android.media.ThumbnailUtils.createVideoThumbnail(
                                            localFile,
                                            android.util.Size(96, 96),
                                            null
                                        )
                                    } else {
                                        @Suppress("DEPRECATION")
                                        android.media.ThumbnailUtils.createVideoThumbnail(
                                            localFile.absolutePath,
                                            android.provider.MediaStore.Images.Thumbnails.MINI_KIND
                                        )
                                    }
                                }
                                else -> null
                            }
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                bitmapState.value = bmp
                            }
                        } catch (_: Exception) {}
                    }
                }

                val bmp = bitmapState.value
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = name,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (isVideo) {
                        Icon(
                            Icons.Default.PlayCircleOutline,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    // thumbnail load হওয়ার আগে বা error হলে — type icon দেখাও
                    FileTypeIcon(ext = ext, isDirectory = false, sizeDp = 40)
                }
            } else {
                FileTypeIcon(ext = ext, isDirectory = isDirectory, sizeDp = 40)
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isDirectory) FontWeight.Medium else FontWeight.Normal
            )
            Spacer(modifier = Modifier.height(3.dp))
            Row {
                Text(text = date, fontSize = 11.sp, color = Color.Gray)
                if (size.isNotEmpty()) {
                    Text(text = " · $size", fontSize = 11.sp, color = Color.Gray)
                }
            }
        }
    }
}

// ── File type icon — extension অনুযায়ী রঙিন icon ─────────────────────────
@Composable
fun FileTypeIcon(ext: String, isDirectory: Boolean, sizeDp: Int) {
    val (icon, tint) = when {
        isDirectory -> Icons.Default.Folder to Color(0xFFFFA000)
        ext in setOf("jpg","jpeg","png","gif","webp","bmp","heic","heif")
                    -> Icons.Default.Image to Color(0xFFE91E63)
        ext == "pdf"-> Icons.Default.PictureAsPdf to Color(0xFFD32F2F)
        ext in setOf("mp4","mkv","mov","avi","3gp","webm")
                    -> Icons.Default.VideoFile to Color(0xFF7B1FA2)
        ext in setOf("mp3","m4a","aac","ogg","flac","wav")
                    -> Icons.Default.AudioFile to Color(0xFF1565C0)
        ext in setOf("docx","doc")
                    -> Icons.Default.Description to Color(0xFF1976D2)
        ext in setOf("pptx","ppt")
                    -> Icons.Default.Slideshow to Color(0xFFE65100)
        ext in setOf("xlsx","xls")
                    -> Icons.Default.TableChart to Color(0xFF2E7D32)
        ext in setOf("zip","rar","7z","tar","gz")
                    -> Icons.Default.FolderZip to Color(0xFF795548)
        ext == "apk"-> Icons.Default.Android to Color(0xFF388E3C)
        ext in setOf("txt","md","csv","json","xml","yaml","yml")
                    -> Icons.Default.TextSnippet to Color(0xFF546E7A)
        ext in setOf("kt","java","py","js","ts","html","css","sh","c","cpp")
                    -> Icons.Default.Code to Color(0xFF00838F)
        else        -> Icons.Default.InsertDriveFile to Color(0xFF9E9E9E)
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(sizeDp.dp)
    )
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

package com.rasel.RasFocus.filemanager

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material.icons.filled.VideoFile
import kotlinx.coroutines.withContext
import androidx.compose.ui.draw.clip
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
import android.provider.MediaStore
import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.Dispatchers

// ── Rename Dialog ──────────────────────────────────────────────────────────────
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

// ── New Folder Dialog ──────────────────────────────────────────────────────────
@Composable
fun NewFolderDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var folderName by remember { mutableStateOf("New folder") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Folder") },
        text = {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = { Text("Folder name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = folderName.trim()
                    if (trimmed.isNotEmpty()) onConfirm(trimmed)
                },
                enabled = folderName.trim().isNotEmpty()
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ── Delete Confirmation Dialog ─────────────────────────────────────────────────
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

// ── File Properties Dialog ─────────────────────────────────────────────────────
@Composable
fun FilePropertiesDialog(
    file: File,
    onDismiss: () -> Unit
) {
    val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Properties", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PropertyRow("Name", file.name)
                PropertyRow("Path", file.absolutePath)
                PropertyRow("Type", if (file.isDirectory) "Folder" else file.extension.uppercase().ifEmpty { "File" })
                if (!file.isDirectory) {
                    PropertyRow("Size", formatFileSize(file.length()))
                }
                PropertyRow("Modified", sdf.format(file.lastModified()))
                PropertyRow("Readable", if (file.canRead()) "Yes" else "No")
                PropertyRow("Writable", if (file.canWrite()) "Yes" else "No")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Column {
        Text(label, fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
        Text(value, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

// ── Apply sort to local files ──────────────────────────────────────────────────
fun sortFiles(files: List<File>, mode: SortMode): List<File> {
    val (folders, regular) = files.partition { it.isDirectory }
    fun sortedList(list: List<File>) = when (mode) {
        SortMode.NAME_ASC  -> list.sortedBy { it.name.lowercase() }
        SortMode.NAME_DESC -> list.sortedByDescending { it.name.lowercase() }
        SortMode.DATE_ASC  -> list.sortedBy { it.lastModified() }
        SortMode.DATE_DESC -> list.sortedByDescending { it.lastModified() }
        SortMode.SIZE_ASC  -> list.sortedBy { it.length() }
        SortMode.SIZE_DESC -> list.sortedByDescending { it.length() }
    }
    // Folders always first, then files
    return sortedList(folders) + sortedList(regular)
}

@Composable
fun LocalFileScreen(
    path: String,
    onNavigate: (NavState) -> Unit,
    onBack: () -> Unit,
    clipboard: ClipboardState?,
    onSetClipboard: (ClipboardState?) -> Unit,
    sortMode: SortMode = SortMode.NAME_ASC,
    searchQuery: String = ""
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rawFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedFiles by remember { mutableStateOf<Set<String>>(emptySet()) }
    var hasPermission by remember { mutableStateOf(LocalFileManager.hasStorageAccess(context)) }

    // Dialog states
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var propertiesTarget by remember { mutableStateOf<File?>(null) }

    // Sorted + filtered files
    val files by remember(rawFiles, sortMode, searchQuery) {
        derivedStateOf {
            val sorted = sortFiles(rawFiles, sortMode)
            if (searchQuery.isBlank()) sorted
            else sorted.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
    }

    fun refreshFiles() {
        // Refresh silently in background — no loading spinner
        scope.launch(Dispatchers.IO) {
            val result = LocalFileManager.listFiles(path)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                rawFiles = result
            }
        }
    }

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
                    rawFiles = result
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
                    rawFiles = result
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
                    rawFiles = result
                    isLoading = false
                }
            }
        } else {
            isLoading = false
        }
    }

    // ── Rename Dialog ──────────────────────────────────────────────────────────
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

    // ── New Folder Dialog ──────────────────────────────────────────────────────
    if (showNewFolderDialog) {
        NewFolderDialog(
            onConfirm = { folderName ->
                showNewFolderDialog = false
                val newDir = File(path, folderName)
                val ok = newDir.mkdirs()
                Toast.makeText(
                    context,
                    if (ok) "Folder \"$folderName\" created" else "Failed to create folder",
                    Toast.LENGTH_SHORT
                ).show()
                if (ok) refreshFiles()
            },
            onDismiss = { showNewFolderDialog = false }
        )
    }

    // ── Delete Confirm Dialog ──────────────────────────────────────────────────
    if (showDeleteDialog) {
        DeleteConfirmDialog(
            count = selectedFiles.size,
            onConfirm = {
                showDeleteDialog = false
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val recycleBin = File(LocalFileManager.mainStoragePath + "/.RecycleBin")
                    if (!recycleBin.exists()) recycleBin.mkdirs()
                    var allOk = true
                    for (p in selectedFiles) {
                        val f = File(p)
                        // Move to RecycleBin; if name conflicts append timestamp
                        val dest = File(recycleBin, f.name).let { base ->
                            if (!base.exists()) base
                            else File(recycleBin, "${f.nameWithoutExtension}_${System.currentTimeMillis()}.${f.extension}")
                        }
                        val moved = f.renameTo(dest)
                        // renameTo fails across filesystems — fall back to copy+delete
                        if (!moved) {
                            try {
                                f.copyRecursively(dest, overwrite = true)
                                f.deleteRecursively()
                            } catch (e: Exception) {
                                allOk = false
                            }
                        }
                    }
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            if (allOk) "Moved to Recycle Bin" else "Some items could not be deleted",
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

    // ── File Properties Dialog ─────────────────────────────────────────────────
    propertiesTarget?.let { propFile ->
        FilePropertiesDialog(
            file = propFile,
            onDismiss = { propertiesTarget = null }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Header ─────────────────────────────────────────────────────────────
        if (selectedFiles.isNotEmpty()) {
            SelectionTopBar(
                selectedCount = selectedFiles.size,
                totalCount = rawFiles.size,
                onClose = { selectedFiles = emptySet() },
                onSelectAll = { selectedFiles = rawFiles.map { it.absolutePath }.toSet() },
                onDeselectAll = { selectedFiles = emptySet() }
            )
        } else {
            FileManagerHeader(
                title = path.substringAfterLast("/").ifEmpty { "Root" },
                subtitle = if (searchQuery.isNotBlank()) "${files.size} found" else "${rawFiles.size} items",
                onBack = onBack,
                onNewFolder = { showNewFolderDialog = true }
            )
        }

        // ── Content area with weight(1f) so footer stays pinned ───────────────
        Box(modifier = Modifier.weight(1f)) {
            when {
                !hasPermission -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                            Icon(Icons.Default.Folder, contentDescription = null,
                                tint = Color(0xFF00796B), modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("Storage access required", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("Allow RasFocus to access files", color = Color.Gray, fontSize = 13.sp)
                            Spacer(Modifier.height(20.dp))
                            Button(
                                onClick = {
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
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00796B))
                            ) { Text("Grant Permission", color = Color.White) }
                        }
                    }
                }
                // No full-screen loading spinner — show empty state instantly
                // then content appears as soon as IO returns (usually <50ms on local storage)
                isLoading && files.isEmpty() -> {
                    // Only show spinner on very first load of an empty-so-far list
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                            color = Color(0xFF00796B),
                            trackColor = Color(0xFF00796B).copy(alpha = 0.15f)
                        )
                    }
                }
                files.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Folder, contentDescription = null,
                                tint = Color.LightGray, modifier = Modifier.size(56.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(
                                if (searchQuery.isNotBlank()) "No results for \"$searchQuery\"" else "Folder is empty",
                                color = Color.Gray, fontSize = 14.sp
                            )
                        }
                    }
                }
                else -> {
                    // ── File list + paste FAB inside same Box ─────────────────
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
                                onLongClick = {
                                    selectedFiles = if (isSelected) selectedFiles - file.absolutePath else selectedFiles + file.absolutePath
                                },
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
                                onPropertiesClick = { propertiesTarget = file },
                                onShareClick = { if (!file.isDirectory) shareLocalFile(context, file) }
                            )
                        }
                        // bottom padding so FAB doesn't cover last item
                        item { Spacer(Modifier.height(80.dp)) }
                    }

                    // ── Paste FAB — only when clipboard active & no selection ─
                    if (clipboard != null && selectedFiles.isEmpty()) {
                        PasteFloatingButton(
                            itemCount = clipboard.items.size,
                            isCut = clipboard.isCut,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
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
                                            val result = DriveFileManager.downloadFolder(context, acc, id, name, java.io.File(path))
                                            if (!result) {
                                                val fileResult = DriveFileManager.downloadFile(context, acc, id, name, java.io.File(path))
                                                if (fileResult == null) success = false
                                            }
                                        }
                                    }
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        Toast.makeText(context, if (success) "Pasted successfully" else "Some items failed to paste", Toast.LENGTH_SHORT).show()
                                        onSetClipboard(null)
                                        rawFiles = LocalFileManager.listFiles(path)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        // ── Footer: selection action bar — always below content ────────────────
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
                        renameTarget = File(selectedFiles.first())
                        showRenameDialog = true
                    } else {
                        Toast.makeText(context, "Select only one item to rename", Toast.LENGTH_SHORT).show()
                    }
                },
                onDelete = { showDeleteDialog = true },
                onProperties = {
                    if (selectedFiles.size == 1) {
                        propertiesTarget = File(selectedFiles.first())
                        selectedFiles = emptySet()
                    } else {
                        Toast.makeText(context, "Select only one item to view properties", Toast.LENGTH_SHORT).show()
                    }
                },
                onShare = {
                    val filesToShare = selectedFiles.map { File(it) }.filter { !it.isDirectory }
                    if (filesToShare.isNotEmpty()) {
                        shareLocalFiles(context, filesToShare)
                        selectedFiles = emptySet()
                    } else {
                        Toast.makeText(context, "Cannot share folders", Toast.LENGTH_SHORT).show()
                    }
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
    onSetClipboard: (ClipboardState?) -> Unit,
    sortMode: SortMode = SortMode.NAME_ASC,
    searchQuery: String = ""
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rawFiles by remember { mutableStateOf<List<com.google.api.services.drive.model.File>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var selectedFiles by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Dialog states
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTargetId by remember { mutableStateOf("") }
    var renameTargetName by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }

    // Sorted + filtered cloud files
    val files by remember(rawFiles, sortMode, searchQuery) {
        derivedStateOf {
            val sorted = when (sortMode) {
                SortMode.NAME_ASC  -> rawFiles.sortedWith(compareBy({ it.mimeType != "application/vnd.google-apps.folder" }, { it.name.lowercase() }))
                SortMode.NAME_DESC -> rawFiles.sortedWith(compareByDescending<com.google.api.services.drive.model.File> { it.name.lowercase() }.thenBy { it.mimeType != "application/vnd.google-apps.folder" })
                SortMode.DATE_ASC  -> rawFiles.sortedWith(compareBy({ it.mimeType != "application/vnd.google-apps.folder" }, { it.modifiedTime?.value ?: 0L }))
                SortMode.DATE_DESC -> rawFiles.sortedWith(compareBy<com.google.api.services.drive.model.File> { it.mimeType != "application/vnd.google-apps.folder" }.thenByDescending { it.modifiedTime?.value ?: 0L })
                SortMode.SIZE_ASC  -> rawFiles.sortedWith(compareBy({ it.mimeType != "application/vnd.google-apps.folder" }, { it.size?.toLong() ?: 0L }))
                SortMode.SIZE_DESC -> rawFiles.sortedWith(compareBy<com.google.api.services.drive.model.File> { it.mimeType != "application/vnd.google-apps.folder" }.thenByDescending { it.size?.toLong() ?: 0L })
            }
            if (searchQuery.isBlank()) sorted
            else sorted.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
    }

    fun refreshFiles() {
        scope.launch {
            isLoading = true
            val result = DriveFileManager.listFiles(context, accountName, folderId)
            rawFiles = result ?: rawFiles
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
                rawFiles = result
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
            rawFiles = result
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

    // ── Cloud Rename Dialog ────────────────────────────────────────────────────
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

    // ── Cloud New Folder Dialog ────────────────────────────────────────────────
    if (showNewFolderDialog) {
        NewFolderDialog(
            onConfirm = { folderName ->
                showNewFolderDialog = false
                scope.launch {
                    val result = DriveFileManager.createFolder(context, accountName, folderName, folderId)
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            if (result != null) "Folder \"$folderName\" created" else "Failed to create folder",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (result != null) refreshFiles()
                    }
                }
            },
            onDismiss = { showNewFolderDialog = false }
        )
    }

    // ── Cloud Delete Confirm Dialog ────────────────────────────────────────────
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
        // ── Header ─────────────────────────────────────────────────────────────
        if (selectedFiles.isNotEmpty()) {
            SelectionTopBar(
                selectedCount = selectedFiles.size,
                totalCount = rawFiles.size,
                onClose = { selectedFiles = emptySet() },
                onSelectAll = { selectedFiles = rawFiles.map { it.id }.toSet() },
                onDeselectAll = { selectedFiles = emptySet() }
            )
        } else {
            FileManagerHeader(
                title = pathName,
                subtitle = if (searchQuery.isNotBlank()) "${files.size} found"
                           else if (!isLoading) "${rawFiles.size} items · Drive" else "Loading…",
                onBack = onBack,
                onNewFolder = { showNewFolderDialog = true },
                headerColor = Color(0xFF1565C0)
            )
        }

        // ── Content area with weight(1f) ───────────────────────────────────────
        Box(modifier = Modifier.weight(1f)) {
            when {
                isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color(0xFF1565C0))
                            Spacer(Modifier.height(12.dp))
                            Text("Loading Drive…", color = Color.Gray, fontSize = 13.sp)
                        }
                    }
                }
                errorMsg != null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                            Icon(Icons.Default.CloudQueue, contentDescription = null,
                                tint = Color.Red.copy(alpha = 0.6f), modifier = Modifier.size(56.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Drive Error", fontWeight = FontWeight.SemiBold, color = Color.Red)
                            Spacer(Modifier.height(6.dp))
                            Text(errorMsg!!, color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
                files.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.CloudQueue, contentDescription = null,
                                tint = Color.LightGray, modifier = Modifier.size(56.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(
                                if (searchQuery.isNotBlank()) "No results for \"$searchQuery\"" else "Folder is empty",
                                color = Color.Gray, fontSize = 14.sp
                            )
                        }
                    }
                }
                else -> {
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
                                            Toast.makeText(context, "Opening…", Toast.LENGTH_SHORT).show()
                                            scope.launch {
                                                val localFile = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                    DriveFileManager.downloadFile(context, accountName, file.id, file.name)
                                                }
                                                if (localFile != null) {
                                                    openLocalFile(context, localFile)
                                                } else {
                                                    Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                },
                                onLongClick = {
                                    selectedFiles = if (isSelected) selectedFiles - file.id else selectedFiles + file.id
                                }
                            )
                        }
                        item { Spacer(Modifier.height(80.dp)) }
                    }

                    if (clipboard != null && selectedFiles.isEmpty()) {
                        PasteFloatingButton(
                            itemCount = clipboard.items.size,
                            isCut = clipboard.isCut,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
                            onClick = {
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    var success = true
                                    if (clipboard.sourceEnv == "Local") {
                                        for (item in clipboard.items) {
                                            val src = java.io.File(item)
                                            if (src.isDirectory) {
                                                if (!DriveFileManager.uploadFolder(context, accountName, src, folderId)) success = false
                                            } else {
                                                if (DriveFileManager.uploadFile(context, accountName, src, folderId) == null) success = false
                                            }
                                            if (clipboard.isCut) try { src.deleteRecursively() } catch (_: Exception) {}
                                        }
                                    } else if (clipboard.sourceEnv == "Cloud") {
                                        val srcAccount = clipboard.accountName ?: accountName
                                        for (id in clipboard.items) {
                                            val result = if (clipboard.isCut)
                                                DriveFileManager.moveFile(context, srcAccount, id, folderId, "root")
                                            else
                                                DriveFileManager.copyFile(context, srcAccount, id, folderId)
                                            if (result == null) success = false
                                        }
                                    }
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        Toast.makeText(context, if (success) "Pasted successfully" else "Some items failed to paste", Toast.LENGTH_SHORT).show()
                                        onSetClipboard(null)
                                        isLoading = true
                                        rawFiles = DriveFileManager.listFiles(context, accountName, folderId) ?: emptyList()
                                        isLoading = false
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        // ── Footer: selection action bar — always pinned at bottom ────────────
        if (selectedFiles.isNotEmpty()) {
            SelectionBottomBar(
                onCopy = {
                    val names = selectedFiles.mapNotNull { id -> rawFiles.find { it.id == id }?.name }
                    onSetClipboard(ClipboardState("Cloud", selectedFiles.toList(), itemNames = names, isCut = false, accountName = accountName))
                    selectedFiles = emptySet()
                },
                onMove = {
                    val names = selectedFiles.mapNotNull { id -> rawFiles.find { it.id == id }?.name }
                    onSetClipboard(ClipboardState("Cloud", selectedFiles.toList(), itemNames = names, isCut = true, accountName = accountName))
                    selectedFiles = emptySet()
                },
                onRename = {
                    if (selectedFiles.size == 1) {
                        val fileId = selectedFiles.first()
                        renameTargetId = fileId
                        renameTargetName = rawFiles.find { it.id == fileId }?.name ?: ""
                        showRenameDialog = true
                    } else {
                        Toast.makeText(context, "Select only one item to rename", Toast.LENGTH_SHORT).show()
                    }
                },
                onDelete = { showDeleteDialog = true },
                onProperties = { Toast.makeText(context, "Cloud file properties coming soon", Toast.LENGTH_SHORT).show() },
                onShare = { Toast.makeText(context, "Cloud file sharing coming soon", Toast.LENGTH_SHORT).show() }
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
    localFile: java.io.File? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onPropertiesClick: (() -> Unit)? = null,
    onShareClick: (() -> Unit)? = null
) {
    val ext = name.substringAfterLast('.', "").lowercase()
    val isImage = ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    val isPdf   = ext == "pdf"
    val isVideo = ext in setOf("mp4", "mkv", "mov", "avi", "3gp", "webm")
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) Color(0xFFB2DFDB) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── Thumbnail / Icon ──────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (!isDirectory && localFile != null && (isImage || isPdf || isVideo)) {
                // Check cache synchronously first — instant if already loaded
                val cached = remember(localFile.absolutePath) {
                    thumbnailCache.get(localFile.absolutePath)
                }
                val context = LocalContext.current
                val bitmapState = remember(localFile.absolutePath) {
                    androidx.compose.runtime.mutableStateOf<Bitmap?>(cached)
                }

                // Only launch IO if not cached
                if (cached == null) {
                    LaunchedEffect(localFile.absolutePath) {
                        val bmp = loadThumbnailFor(context, localFile)
                        bitmapState.value = bmp
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
                    // Show icon while loading (no spinner — instant fallback)
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

        // ── Per-item 3-dot menu (properties / share) ──────────────────────────
        if (onPropertiesClick != null || onShareClick != null) {
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.Gray, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    if (onShareClick != null && !isDirectory) {
                        DropdownMenuItem(
                            text = { Text("Share") },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = { showMenu = false; onShareClick() }
                        )
                    }
                    if (onPropertiesClick != null) {
                        DropdownMenuItem(
                            text = { Text("Properties") },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = { showMenu = false; onPropertiesClick() }
                        )
                    }
                }
            }
        }
    }
}

// ── Global thumbnail memory cache (shared across all FileListItems) ────────────
//    Holds up to 24 MB worth of 96×96 bitmaps — enough for ~100+ thumbnails
private val thumbnailCache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(
    (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtMost(24 * 1024) // KB, max 24 MB
) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
}

// Decode thumb for a local file — tries MediaStore first (instant), then raw decode
suspend fun loadThumbnailFor(context: android.content.Context, file: java.io.File): Bitmap? {
    val key = file.absolutePath
    thumbnailCache.get(key)?.let { return it }

    return kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val ext = file.extension.lowercase()
            val isImage = ext in setOf("jpg","jpeg","png","gif","webp","bmp","heic","heif")
            val isPdf   = ext == "pdf"
            val isVideo = ext in setOf("mp4","mkv","mov","avi","3gp","webm")

            val bmp: Bitmap? = when {
                // ── Images: try MediaStore (system cache) first ──────────────
                isImage -> {
                    var thumb: Bitmap? = null
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        try {
                            thumb = context.contentResolver.loadThumbnail(
                                android.net.Uri.fromFile(file),
                                android.util.Size(96, 96), null
                            )
                        } catch (_: Exception) {}
                    }
                    if (thumb == null) {
                        // MediaStore content URI lookup
                        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        val proj = arrayOf(MediaStore.Images.Media._ID)
                        val sel  = "${MediaStore.Images.Media.DATA} = ?"
                        context.contentResolver.query(uri, proj, sel, arrayOf(file.absolutePath), null)?.use { c ->
                            if (c.moveToFirst()) {
                                val id = c.getLong(0)
                                val contentUri = android.content.ContentUris.withAppendedId(uri, id)
                                try {
                                    thumb = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                        context.contentResolver.loadThumbnail(contentUri, android.util.Size(96, 96), null)
                                    } else {
                                        @Suppress("DEPRECATION")
                                        MediaStore.Images.Thumbnails.getThumbnail(
                                            context.contentResolver, id,
                                            MediaStore.Images.Thumbnails.MICRO_KIND, null
                                        )
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                    // Fallback: decode file directly at small size
                    if (thumb == null) {
                        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
                        val scale = maxOf(1, minOf(opts.outWidth, opts.outHeight) / 96)
                        val opts2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = scale }
                        thumb = android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts2)
                    }
                    thumb
                }

                // ── Videos: MediaStore thumbnail ─────────────────────────────
                isVideo -> {
                    var thumb: Bitmap? = null
                    val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    val proj = arrayOf(MediaStore.Video.Media._ID)
                    val sel  = "${MediaStore.Video.Media.DATA} = ?"
                    context.contentResolver.query(uri, proj, sel, arrayOf(file.absolutePath), null)?.use { c ->
                        if (c.moveToFirst()) {
                            val id = c.getLong(0)
                            val contentUri = android.content.ContentUris.withAppendedId(uri, id)
                            try {
                                thumb = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                    context.contentResolver.loadThumbnail(contentUri, android.util.Size(96, 96), null)
                                } else {
                                    @Suppress("DEPRECATION")
                                    MediaStore.Video.Thumbnails.getThumbnail(
                                        context.contentResolver, id,
                                        MediaStore.Video.Thumbnails.MICRO_KIND, null
                                    )
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    // Fallback: ThumbnailUtils
                    if (thumb == null) {
                        try {
                            thumb = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                android.media.ThumbnailUtils.createVideoThumbnail(file, android.util.Size(96, 96), null)
                            } else {
                                @Suppress("DEPRECATION")
                                android.media.ThumbnailUtils.createVideoThumbnail(
                                    file.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND
                                )
                            }
                        } catch (_: Exception) {}
                    }
                    thumb
                }

                // ── PDF: PdfRenderer (fast at 96px) ──────────────────────────
                isPdf && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP -> {
                    try {
                        val fd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                        val renderer = android.graphics.pdf.PdfRenderer(fd)
                        val page = renderer.openPage(0)
                        val w = 96; val h = (96f * page.height / page.width).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close(); renderer.close(); fd.close()
                        bmp
                    } catch (_: Exception) { null }
                }

                else -> null
            }

            bmp?.also { thumbnailCache.put(key, it) }
        } catch (_: Exception) { null }
    }
}

// ── File type icon ─────────────────────────────────────────────────────────────
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

// ── File opener ────────────────────────────────────────────────────────────────
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

// ── Share single local file ────────────────────────────────────────────────────
fun shareLocalFile(context: android.content.Context, file: java.io.File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val mime = getMimeFromExtension(file.extension.lowercase())
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot share: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

// ── Share multiple local files ─────────────────────────────────────────────────
fun shareLocalFiles(context: android.content.Context, files: List<java.io.File>) {
    try {
        if (files.size == 1) {
            shareLocalFile(context, files[0])
            return
        }
        val uris = ArrayList(files.map { file ->
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ${files.size} files"))
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot share: ${e.message}", Toast.LENGTH_SHORT).show()
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

// ── Folder view header — clean design with breadcrumb feel ────────────────────
@Composable
fun FileManagerHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onNewFolder: () -> Unit,
    headerColor: Color = Color(0xFF00796B)
) {
    Surface(
        color = headerColor,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(start = 2.dp)) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onNewFolder) {
                    Icon(
                        Icons.Default.CreateNewFolder,
                        contentDescription = "New folder",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

// ── Paste FAB with count badge ─────────────────────────────────────────────────
@Composable
fun PasteFloatingButton(
    itemCount: Int,
    isCut: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(modifier = modifier) {
        ExtendedFloatingActionButton(
            onClick = onClick,
            containerColor = Color(0xFF00796B),
            contentColor = Color.White,
            icon = {
                Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
            },
            text = {
                Text(
                    text = if (isCut) "Move $itemCount item${if (itemCount != 1) "s" else ""} here"
                           else "Paste $itemCount item${if (itemCount != 1) "s" else ""} here",
                    fontSize = 13.sp
                )
            }
        )
    }
}

// ── Selection top bar ──────────────────────────────────────────────────────────
@Composable
fun SelectionTopBar(
    selectedCount: Int,
    totalCount: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit = {}
) {
    Surface(
        color = Color(0xFF1A6B6B),
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Cancel selection", tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Text(
                text = "$selectedCount/$totalCount",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            IconButton(onClick = onSelectAll) {
                Icon(imageVector = Icons.Default.SelectAll, contentDescription = "Select all", tint = Color.White, modifier = Modifier.size(26.dp))
            }
            IconButton(onClick = onDeselectAll) {
                Icon(imageVector = Icons.Default.Deselect, contentDescription = "Deselect all", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(26.dp))
            }
        }
    }
}

// ── Selection bottom bar (extended) ───────────────────────────────────────────
@Composable
fun SelectionBottomBar(
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onProperties: () -> Unit = {},
    onShare: () -> Unit = {}
) {
    Surface(
        color = Color(0xFF1A6B6B),
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 2.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SelectionAction(icon = Icons.Default.ContentCopy,  label = "Copy",       onClick = onCopy)
            SelectionAction(icon = Icons.Default.DriveFileMove, label = "Move",      onClick = onMove)
            SelectionAction(icon = Icons.Default.Share,         label = "Share",     onClick = onShare)
            SelectionAction(icon = Icons.Default.Edit,          label = "Rename",    onClick = onRename)
            SelectionAction(icon = Icons.Default.Delete,        label = "Delete",    onClick = onDelete)
            SelectionAction(icon = Icons.Default.Info,          label = "Info",      onClick = onProperties)
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
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(text = label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

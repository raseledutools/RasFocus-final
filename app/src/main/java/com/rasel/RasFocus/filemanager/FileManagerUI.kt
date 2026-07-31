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
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.rasel.RasFocus.drivebackup.DriveFileManager

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
                                        Toast.makeText(context, "Opening local files not implemented", Toast.LENGTH_SHORT).show()
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
            onDelete = { Toast.makeText(context, "Delete logic coming soon", Toast.LENGTH_SHORT).show() },
            onClearSelection = { selectedFiles = emptySet() }
        )
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
            onDelete = { Toast.makeText(context, "Delete logic coming soon", Toast.LENGTH_SHORT).show() },
            onClearSelection = { selectedFiles = emptySet() }
        )
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

@Composable
fun SelectionBottomBar(
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onClearSelection: () -> Unit
) {
    Surface(
        color = Color(0xFF1E1E1E),
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCopy) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ContentCopy, "Copy", tint = Color.White)
                    Text("Copy", color = Color.White, fontSize = 10.sp)
                }
            }
            IconButton(onClick = onMove) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.DriveFileMove, "Move", tint = Color.White)
                    Text("Move", color = Color.White, fontSize = 10.sp)
                }
            }
            IconButton(onClick = onDelete) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Delete, "Delete", tint = Color.White)
                    Text("Delete", color = Color.White, fontSize = 10.sp)
                }
            }
            IconButton(onClick = onClearSelection) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Close, "Cancel", tint = Color.White)
                    Text("Cancel", color = Color.White, fontSize = 10.sp)
                }
            }
        }
    }
}

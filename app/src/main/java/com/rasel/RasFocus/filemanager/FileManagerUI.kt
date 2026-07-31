package com.rasel.RasFocus.filemanager

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
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
    var isLoading by remember { mutableStateOf(true) }
    var selectedFiles by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(path) {
        isLoading = true
        files = LocalFileManager.listFiles(path)
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
                text = path.substringAfterLast("/").ifEmpty { "Root" },
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
        } else if (files.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Folder is empty", color = Color.Gray)
            }
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
                            Toast.makeText(context, "Pasting from ${clipboard.sourceEnv} to Local...", Toast.LENGTH_SHORT).show()
                            onSetClipboard(null)
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
                onSetClipboard(ClipboardState("Local", selectedFiles.toList(), false))
                selectedFiles = emptySet()
            },
            onMove = { 
                onSetClipboard(ClipboardState("Local", selectedFiles.toList(), true))
                selectedFiles = emptySet()
            },
            onDelete = { Toast.makeText(context, "Delete logic coming soon", Toast.LENGTH_SHORT).show() },
            onClearSelection = { selectedFiles = emptySet() }
        )
    }
}

@Composable
fun CloudFileScreen(
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
            val result = DriveFileManager.listFiles(context, folderId)
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
        val result = DriveFileManager.listFiles(context, folderId)
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
                                        onNavigate(NavState.Cloud(file.id, file.name))
                                    } else {
                                        Toast.makeText(context, "Downloading...", Toast.LENGTH_SHORT).show()
                                        scope.launch {
                                            DriveFileManager.downloadFile(context, file.id, file.name)
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
                            Toast.makeText(context, "Pasting from ${clipboard.sourceEnv} to Cloud...", Toast.LENGTH_SHORT).show()
                            onSetClipboard(null)
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
                onSetClipboard(ClipboardState("Cloud", selectedFiles.toList(), false))
                selectedFiles = emptySet()
            },
            onMove = { 
                onSetClipboard(ClipboardState("Cloud", selectedFiles.toList(), true))
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

package com.rasel.RasFocus.filemanager


import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material.icons.filled.VideoFile
import kotlinx.coroutines.withContext
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// ── Share helpers — defined here so FileManagerUI resolves them independently ──
// (same definitions also exist in FileManagerPlusActivity; Kotlin deduplicates
// them at link time since they are package-level functions in the same package)
internal fun shareLocalFile(context: android.content.Context, file: java.io.File) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val ext = file.extension.lowercase()
        val mimeType = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext) ?: "*/*"
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Share ${file.name}"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Cannot share file: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

internal fun shareLocalFiles(context: android.content.Context, files: List<java.io.File>) {
    if (files.size == 1) { shareLocalFile(context, files.first()); return }
    try {
        val uris = ArrayList(files.map { file ->
            androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
        })
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Share ${files.size} files"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Cannot share files: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

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
                if (file.isDirectory) {
                    val count = file.list()?.size ?: 0
                    PropertyRow("Contains", "$count items")
                } else {
                    PropertyRow("Size", "${formatFileSize(file.length())} (${java.text.NumberFormat.getInstance().format(file.length())} bytes)")
                    PropertyRow("MD5 Hash", "Calculate (Coming soon)") // Placeholder for MD5
                }
                PropertyRow("Modified", sdf.format(file.lastModified()))
                
                val perms = buildString {
                    append(if (file.canRead()) "r" else "-")
                    append(if (file.canWrite()) "w" else "-")
                    append(if (file.canExecute()) "x" else "-")
                }
                PropertyRow("Permissions", perms)
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
    LaunchedEffect(Unit) { DriveCacheManager.init(context) }
    var rawFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedFiles by remember { mutableStateOf<Set<String>>(emptySet()) }
    var hasPermission by remember { mutableStateOf(LocalFileManager.hasStorageAccess(context)) }
    var isGridView by remember { mutableStateOf(false) }
    var localSearchQuery by remember { mutableStateOf(searchQuery) }
    val operations by FileOperationManager.operations.collectAsState()
    // Track the last paste operation so we can auto-refresh when it finishes
    var pendingOpId by remember { mutableStateOf<String?>(null) }

    // Dialog states
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var propertiesTarget by remember { mutableStateOf<File?>(null) }
    var showProgressDialog by remember { mutableStateOf(false) }
    val activeProgressOp by remember(operations) {
        derivedStateOf { operations.firstOrNull { !it.isComplete && !it.isCancelled && !it.isError } }
    }

    // Sorted + filtered files
    val files by remember(rawFiles, sortMode, localSearchQuery) {
        derivedStateOf {
            val sorted = sortFiles(rawFiles, sortMode)
            if (localSearchQuery.isBlank()) sorted
            else sorted.filter { it.name.contains(localSearchQuery, ignoreCase = true) }
        }
    }

    fun refreshFiles() {
        // Refresh silently in background — no loading spinner
        scope.launch(Dispatchers.IO) {
            val showHidden = SettingsManager.showHiddenFiles(context)
            val result = LocalFileManager.listFiles(path, showHidden)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                rawFiles = result
            }
        }
    }

    // Auto-refresh when the pending paste/move operation completes
    LaunchedEffect(operations, pendingOpId) {
        val id = pendingOpId ?: return@LaunchedEffect
        val op = operations.find { it.id == id }
        if (op != null && (op.isComplete || op.isError || op.isCancelled)) {
            pendingOpId = null
            refreshFiles()
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
                val showHidden = SettingsManager.showHiddenFiles(context)
                val result = LocalFileManager.listFiles(path, showHidden)
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
                val showHidden = SettingsManager.showHiddenFiles(context)
                val result = LocalFileManager.listFiles(path, showHidden)
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
                    val recycleBin = File(LocalFileManager.trashPath)
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
            val showMerge = selectedFiles.size > 1 && selectedFiles.all { it.lowercase().endsWith(".pdf") }
            val showUnzip = selectedFiles.size == 1 && selectedFiles.first().lowercase().endsWith(".zip")
            val showPdfToImages = selectedFiles.size == 1 && selectedFiles.first().lowercase().endsWith(".pdf")
            val showImagesToPdf = selectedFiles.isNotEmpty() && selectedFiles.all { it.lowercase().endsWith(".jpg") || it.lowercase().endsWith(".png") || it.lowercase().endsWith(".jpeg") }
            
            SelectionTopBar(
                selectedCount = selectedFiles.size,
                totalCount = rawFiles.size,
                onClose = { selectedFiles = emptySet() },
                onSelectAll = { selectedFiles = rawFiles.map { it.absolutePath }.toSet() },
                onDeselectAll = { selectedFiles = emptySet() },
                onZip = {
                    if (selectedFiles.isNotEmpty()) {
                        scope.launch(Dispatchers.IO) {
                            val filesToZip = selectedFiles.map { File(it) }
                            var zipFile = File(path, "archive_${System.currentTimeMillis()}.zip")
                            var success = LocalFileManager.zipFiles(filesToZip, zipFile)
                            var fallbackUsed = false
                            if (!success) {
                                val fallback = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "RasFocus")
                                fallback.mkdirs()
                                zipFile = File(fallback, zipFile.name)
                                success = LocalFileManager.zipFiles(filesToZip, zipFile)
                                fallbackUsed = success
                            }
                            withContext(Dispatchers.Main) {
                                if (success) {
                                    Toast.makeText(context, if (fallbackUsed) "Saved to internal Documents/RasFocus due to SD card limits" else "Zipped successfully", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Failed to zip", Toast.LENGTH_SHORT).show()
                                }
                                selectedFiles = emptySet()
                                rawFiles = LocalFileManager.listFiles(path)
                            }
                        }
                    }
                },
                onUnzip = if (showUnzip) { {
                    scope.launch(Dispatchers.IO) {
                        var success = true
                        var fallbackUsed = false
                        for (item in selectedFiles) {
                            val zipFile = File(item)
                            if (zipFile.extension.lowercase() == "zip") {
                                var targetDir = File(path, zipFile.nameWithoutExtension)
                                if (!LocalFileManager.unzipFile(zipFile, targetDir)) {
                                    val fallback = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "RasFocus/${zipFile.nameWithoutExtension}")
                                    fallback.mkdirs()
                                    if (LocalFileManager.unzipFile(zipFile, fallback)) {
                                        fallbackUsed = true
                                    } else {
                                        success = false
                                    }
                                }
                            }
                        }
                        withContext(Dispatchers.Main) {
                            if (success) {
                                Toast.makeText(context, if (fallbackUsed) "Unzipped to internal Documents/RasFocus due to SD card limits" else "Unzipped successfully", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Unzip failed", Toast.LENGTH_SHORT).show()
                            }
                            selectedFiles = emptySet()
                            rawFiles = LocalFileManager.listFiles(path)
                        }
                    }
                } } else null,
                onMergePdf = if (showMerge) { {
                    scope.launch(Dispatchers.IO) {
                        val filesToMerge = selectedFiles.map { File(it) }
                        var destFile = File(path, "Merged_${System.currentTimeMillis()}.pdf")
                        var success = PdfHelper.mergePdfs(context, filesToMerge, destFile)
                        var fallbackUsed = false
                        if (!success) {
                            val fallback = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "RasFocus")
                            fallback.mkdirs()
                            destFile = File(fallback, destFile.name)
                            success = PdfHelper.mergePdfs(context, filesToMerge, destFile)
                            fallbackUsed = success
                        }
                        withContext(Dispatchers.Main) {
                            if (success) {
                                Toast.makeText(context, if (fallbackUsed) "Merged & saved to internal Documents/RasFocus due to SD limits" else "Merged successfully", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Merge failed", Toast.LENGTH_SHORT).show()
                            }
                            selectedFiles = emptySet()
                            rawFiles = LocalFileManager.listFiles(path)
                        }
                    }
                } } else null,
                onPdfToImages = if (showPdfToImages) { {
                    scope.launch(Dispatchers.IO) {
                        val pdfFile = File(selectedFiles.first())
                        var targetDir = File(path, pdfFile.nameWithoutExtension)
                        targetDir.mkdirs()
                        var success = PdfHelper.pdfToImages(context, pdfFile, targetDir)
                        var fallbackUsed = false
                        if (!success) {
                            targetDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "RasFocus/${pdfFile.nameWithoutExtension}")
                            targetDir.mkdirs()
                            success = PdfHelper.pdfToImages(context, pdfFile, targetDir)
                            fallbackUsed = success
                        }
                        withContext(Dispatchers.Main) {
                            if (success) {
                                Toast.makeText(context, if (fallbackUsed) "Images saved to internal Documents/RasFocus due to SD limits" else "PDF converted to images", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Conversion failed", Toast.LENGTH_SHORT).show()
                            }
                            selectedFiles = emptySet()
                            rawFiles = LocalFileManager.listFiles(path)
                        }
                    }
                } } else null,
                onImagesToPdf = if (showImagesToPdf) { {
                    scope.launch(Dispatchers.IO) {
                        val images = selectedFiles.map { File(it) }
                        var pdfDest = File(path, "images_${System.currentTimeMillis()}.pdf")
                        var success = PdfHelper.imagesToPdf(context, images, pdfDest)
                        var fallbackUsed = false
                        if (!success) {
                            val fallback = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "RasFocus")
                            fallback.mkdirs()
                            pdfDest = File(fallback, pdfDest.name)
                            success = PdfHelper.imagesToPdf(context, images, pdfDest)
                            fallbackUsed = success
                        }
                        withContext(Dispatchers.Main) {
                            if (success) {
                                Toast.makeText(context, if (fallbackUsed) "PDF saved to internal Documents/RasFocus due to SD limits" else "Images converted to PDF", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Conversion failed", Toast.LENGTH_SHORT).show()
                            }
                            selectedFiles = emptySet()
                            rawFiles = LocalFileManager.listFiles(path)
                        }
                    }
                } } else null,
                onSecure = {
                    scope.launch(Dispatchers.IO) {
                        var success = true
                        for (item in selectedFiles) {
                            val file = File(item)
                            if (!LocalFileManager.moveToVault(file)) success = false
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, if (success) "Secured in vault" else "Failed to secure", Toast.LENGTH_SHORT).show()
                            selectedFiles = emptySet()
                            rawFiles = LocalFileManager.listFiles(path)
                        }
                    }
                },
                onProperties = {
                    if (selectedFiles.size == 1) {
                        propertiesTarget = File(selectedFiles.first())
                        selectedFiles = emptySet()
                    } else {
                        Toast.makeText(context, "Select one item for properties", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        } else {
            FileManagerHeader(
                title = path.substringAfterLast("/").ifEmpty { "Root" },
                subtitle = if (localSearchQuery.isNotBlank()) "${files.size} found" else "${rawFiles.size} items",
                onBack = onBack,
                onNewFolder = { showNewFolderDialog = true },
                isGridView = isGridView,
                onToggleGrid = { isGridView = !isGridView },
                searchQuery = localSearchQuery,
                onSearchQueryChange = { localSearchQuery = it }
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
                                if (localSearchQuery.isNotBlank()) "No results for \"$localSearchQuery\"" else "Folder is empty",
                                color = Color.Gray, fontSize = 14.sp
                            )
                        }
                    }
                }
                else -> {
                    // ── File list + paste FAB inside same Box ─────────────────
                    if (isGridView) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(100.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 80.dp)
                        ) {
                            items(files) { file ->
                                val isSelected = selectedFiles.contains(file.absolutePath)
                                FileListItem(
                                    name = file.name,
                                    isDirectory = file.isDirectory,
                                    size = if (file.isDirectory) "${file.list()?.size ?: 0} items" else formatFileSize(file.length()),
                                    date = formatDate(file.lastModified()),
                                    isSelected = isSelected,
                                    isGrid = true,
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
                                                openLocalFile(context, file, onNavigate)
                                            }
                                        }
                                    },
                                    onPropertiesClick = { propertiesTarget = file },
                                    onShareClick = { if (!file.isDirectory) shareLocalFile(context, file) }
                                )
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(files) { file ->
                                val isSelected = selectedFiles.contains(file.absolutePath)
                                FileListItem(
                                    name = file.name,
                                    isDirectory = file.isDirectory,
                                    size = if (file.isDirectory) "${file.list()?.size ?: 0} items" else formatFileSize(file.length()),
                                    date = formatDate(file.lastModified()),
                                    isSelected = isSelected,
                                    isGrid = false,
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
                                                openLocalFile(context, file, onNavigate)
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
                    }

                    // ── Paste FAB — only when clipboard active & no selection ─

                }
            }
        }

        // ── Copy/Move progress dialog ─────────────────────────────────────────
        if (showProgressDialog) {
            val op = activeProgressOp
            if (op != null) {
                CopyMoveProgressDialog(
                    operation = op,
                    onHide = { showProgressDialog = false },
                    onCancel = {
                        FileOperationManager.updateOperation(op.id) { it.copy(isCancelled = true) }
                        showProgressDialog = false
                    }
                )
            } else {
                // Operation finished — auto close dialog
                showProgressDialog = false
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
                onShare = {
                    val filesToShare = selectedFiles.map { File(it) }.filter { !it.isDirectory }
                    if (filesToShare.isNotEmpty()) {
                        shareLocalFiles(context, filesToShare)
                        selectedFiles = emptySet()
                    } else {
                        Toast.makeText(context, "Cannot share folders", Toast.LENGTH_SHORT).show()
                    }
                },
                onZip = {
                    scope.launch(Dispatchers.IO) {
                        val filesToZip = selectedFiles.map { File(it) }
                        var zipFile = File(path, "archive_${System.currentTimeMillis()}.zip")
                        var success = LocalFileManager.zipFiles(filesToZip, zipFile)
                        if (!success) {
                            val fb = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "RasFocus")
                            fb.mkdirs(); zipFile = File(fb, zipFile.name)
                            success = LocalFileManager.zipFiles(filesToZip, zipFile)
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, if (success) "Zipped successfully" else "Failed to zip", Toast.LENGTH_SHORT).show()
                            selectedFiles = emptySet(); rawFiles = LocalFileManager.listFiles(path)
                        }
                    }
                },
                onUnzip = run {
                    val canUnzip = selectedFiles.size == 1 && selectedFiles.first().lowercase().endsWith(".zip")
                    if (canUnzip) ({
                        scope.launch(Dispatchers.IO) {
                            val zipFile = File(selectedFiles.first())
                            val targetDir = File(path, zipFile.nameWithoutExtension)
                            val success = LocalFileManager.unzipFile(zipFile, targetDir)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, if (success) "Unzipped successfully" else "Unzip failed", Toast.LENGTH_SHORT).show()
                                selectedFiles = emptySet(); rawFiles = LocalFileManager.listFiles(path)
                            }
                        }
                    }) else null
                },
                onMergePdf = run {
                    val canMerge = selectedFiles.size > 1 && selectedFiles.all { it.lowercase().endsWith(".pdf") }
                    if (canMerge) ({
                        scope.launch(Dispatchers.IO) {
                            val filesToMerge = selectedFiles.map { File(it) }
                            var dest = File(path, "Merged_${System.currentTimeMillis()}.pdf")
                            var success = PdfHelper.mergePdfs(context, filesToMerge, dest)
                            if (!success) {
                                val fb = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "RasFocus")
                                fb.mkdirs(); dest = File(fb, dest.name)
                                success = PdfHelper.mergePdfs(context, filesToMerge, dest)
                            }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, if (success) "Merged successfully" else "Merge failed", Toast.LENGTH_SHORT).show()
                                selectedFiles = emptySet(); rawFiles = LocalFileManager.listFiles(path)
                            }
                        }
                    }) else null
                },
                onPdfToImages = run {
                    val canConvert = selectedFiles.size == 1 && selectedFiles.first().lowercase().endsWith(".pdf")
                    if (canConvert) ({
                        scope.launch(Dispatchers.IO) {
                            val pdfFile = File(selectedFiles.first())
                            var targetDir = File(path, pdfFile.nameWithoutExtension)
                            targetDir.mkdirs()
                            var success = PdfHelper.pdfToImages(context, pdfFile, targetDir)
                            if (!success) {
                                targetDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "RasFocus/${pdfFile.nameWithoutExtension}")
                                targetDir.mkdirs()
                                success = PdfHelper.pdfToImages(context, pdfFile, targetDir)
                            }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, if (success) "PDF converted to images" else "Conversion failed", Toast.LENGTH_SHORT).show()
                                selectedFiles = emptySet(); rawFiles = LocalFileManager.listFiles(path)
                            }
                        }
                    }) else null
                },
                onImagesToPdf = run {
                    val imgExts = listOf("jpg", "jpeg", "png", "bmp", "webp")
                    val canConvert = selectedFiles.isNotEmpty() && selectedFiles.all { it.substringAfterLast('.').lowercase() in imgExts }
                    if (canConvert) ({
                        scope.launch(Dispatchers.IO) {
                            val images = selectedFiles.map { File(it) }
                            var pdfDest = File(path, "images_${System.currentTimeMillis()}.pdf")
                            var success = PdfHelper.imagesToPdf(context, images, pdfDest)
                            if (!success) {
                                val fb = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "RasFocus")
                                fb.mkdirs(); pdfDest = File(fb, pdfDest.name)
                                success = PdfHelper.imagesToPdf(context, images, pdfDest)
                            }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, if (success) "Images converted to PDF" else "Conversion failed", Toast.LENGTH_SHORT).show()
                                selectedFiles = emptySet(); rawFiles = LocalFileManager.listFiles(path)
                            }
                        }
                    }) else null
                },
                onSecure = {
                    scope.launch(Dispatchers.IO) {
                        var ok = true
                        for (item in selectedFiles) { if (!LocalFileManager.moveToVault(File(item))) ok = false }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, if (ok) "Secured in vault" else "Failed to secure", Toast.LENGTH_SHORT).show()
                            selectedFiles = emptySet(); rawFiles = LocalFileManager.listFiles(path)
                        }
                    }
                },
                onProperties = {
                    if (selectedFiles.size == 1) { propertiesTarget = File(selectedFiles.first()); selectedFiles = emptySet() }
                    else Toast.makeText(context, "Select one item for properties", Toast.LENGTH_SHORT).show()
                }
            )
        }
        // ── Paste footer bar ─────────────────────────────────────────────────────
        if (clipboard != null && selectedFiles.isEmpty()) {
            PasteFooterBar(
                isCut = clipboard.isCut,
                itemCount = clipboard.items.size,
                onCancel = { onSetClipboard(null) },
                onPaste = {
                    val snap = clipboard
                    onSetClipboard(null)

                    if (snap.sourceEnv == "Cloud") {
                        // ── Drive → Local: download then optionally delete from Drive ──
                        val opId = java.util.UUID.randomUUID().toString()
                        FileOperationManager.addOperation(
                            FileOperation(
                                id = opId,
                                type = if (snap.isCut) OperationType.MOVE else OperationType.COPY,
                                sourceCount = snap.items.size
                            )
                        )
                        val srcAccount = snap.accountName ?: ""
                        val destDir = java.io.File(path)
                        if (!destDir.exists()) destDir.mkdirs()

                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                android.widget.Toast.makeText(
                                    context,
                                    if (snap.isCut) "Moving ${snap.items.size} item(s) from Drive…"
                                    else "Downloading ${snap.items.size} item(s) from Drive…",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            var success = true
                            for (idx in snap.items.indices) {
                                val fileId = snap.items[idx]
                                val fileName = snap.itemNames.getOrNull(idx) ?: "file"
                                val mime = snap.itemMimeTypes.getOrNull(idx) ?: ""
                                val isFolder = mime == "application/vnd.google-apps.folder"

                                FileOperationManager.updateOperation(opId) {
                                    it.copy(currentFileName = fileName)
                                }

                                val ok = if (isFolder) {
                                    // Download entire folder recursively
                                    DriveFileManager.downloadFolder(context, srcAccount, fileId, fileName, destDir)
                                } else {
                                    // Download single file directly to destDir
                                    val downloaded = DriveFileManager.downloadFile(context, srcAccount, fileId, fileName, destDir)
                                    downloaded != null
                                }

                                if (ok) {
                                    if (snap.isCut) {
                                        // Delete from Drive only after successful download
                                        DriveFileManager.deleteFile(context, srcAccount, fileId)
                                    }
                                } else {
                                    success = false
                                }

                                FileOperationManager.updateOperation(opId) {
                                    it.copy(itemsProcessed = it.itemsProcessed + 1)
                                }
                            }

                            FileOperationManager.updateOperation(opId) { it.copy(isComplete = true) }
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                android.widget.Toast.makeText(
                                    context,
                                    if (success) "Done!" else "Some items failed to download",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                // Refresh local file list
                                rawFiles = LocalFileManager.listFiles(path)
                            }
                        }
                        pendingOpId = opId
                        showProgressDialog = true

                    } else {
                        // ── Local → Local: use FileOperationService (existing, works fine) ──
                        val opId = java.util.UUID.randomUUID().toString()
                        FileOperationManager.addOperation(
                            FileOperation(
                                id = opId,
                                type = if (snap.isCut) OperationType.MOVE else OperationType.COPY,
                                sourceCount = snap.items.size
                            )
                        )
                        val intent = android.content.Intent(context, FileOperationService::class.java).apply {
                            action = "ACTION_START"
                            putExtra("OP_ID", opId)
                            putStringArrayListExtra("SOURCE_PATHS", ArrayList(snap.items))
                            putExtra("DEST_PATH", path)
                            putExtra("IS_CUT", snap.isCut)
                            putExtra("SOURCE_ENV", "Local")
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                        pendingOpId = opId
                        showProgressDialog = true
                    }
                }
            )
        }
        // Note: ActiveOperationsBar is now rendered globally in HomeScreen
        // so it appears on every screen (Local, Cloud, etc.)
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
    LaunchedEffect(Unit) { DriveCacheManager.init(context) }
    var rawFiles by remember { mutableStateOf<List<com.google.api.services.drive.model.File>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var selectedFiles by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Offline state
    var isOfflineMode by remember { mutableStateOf(false) }
    var cacheAgeMinutes by remember { mutableStateOf<Long?>(null) }

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
        isOfflineMode = false

        val online = DriveCacheManager.isOnline(context)
        if (online) {
            // ── Online: fetch from Drive, cache current folder ─────────────────
            val result = DriveFileManager.listFiles(context, accountName, folderId)
            if (result != null) {
                rawFiles = result
                errorMsg = null
                DriveCacheManager.saveFileList(context, accountName, folderId, result)

                // ── Background: root folder open হলে সব subfolder recursively cache করো
                // এতে offline এ গেলেও পুরো Drive tree browse করা যাবে
                if (folderId == "root") {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        DriveCacheManager.cacheAllSubfoldersRecursively(
                            context = context,
                            accountName = accountName,
                            folderId = "root"
                        )
                    }
                }
            } else {
                // Network available but Drive call failed (token expired, etc.)
                val cached = DriveCacheManager.loadFileList(context, accountName, folderId)
                if (cached != null && cached.isNotEmpty()) {
                    rawFiles = cached
                    errorMsg = null
                    isOfflineMode = true
                    cacheAgeMinutes = DriveCacheManager.cacheAgeMinutes(accountName, folderId)
                } else {
                    errorMsg = DriveFileManager.lastError
                    val recoveryIntent: Intent? = DriveFileManager.lastRecoveryIntent
                    if (recoveryIntent != null) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            fixDriveLauncher.launch(recoveryIntent)
                        }
                    }
                }
            }
        } else {
            // ── Offline: load from cache ──────────────────────────────────────
            val cached = DriveCacheManager.loadFileList(context, accountName, folderId)
            if (cached != null && cached.isNotEmpty()) {
                rawFiles = cached
                errorMsg = null
                isOfflineMode = true
                cacheAgeMinutes = DriveCacheManager.cacheAgeMinutes(accountName, folderId)
            } else {
                // Never visited this folder online before → no cache
                errorMsg = if (DriveCacheManager.hasCachedList(accountName, "root"))
                    "This folder was never opened online — no offline data available"
                else
                    "No internet connection and no cached data"
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
                onDeselectAll = { selectedFiles = emptySet() },
                onProperties = {
                    if (selectedFiles.size == 1) {
                        val fileId = selectedFiles.first()
                        val file = rawFiles.find { it.id == fileId }
                        if (file != null) {
                            val sb = java.lang.StringBuilder()
                            sb.appendLine("Name: ${file.name}")
                            sb.appendLine("Type: ${if (file.mimeType == "application/vnd.google-apps.folder") "Folder" else file.mimeType ?: "Unknown"}")
                            if (file.size != null) sb.appendLine("Size: ${formatFileSize(file.size.toLong())}")
                            if (file.modifiedTime != null) sb.appendLine("Modified: ${formatDate(file.modifiedTime.value)}")
                            sb.appendLine("Drive ID: ${file.id}")
                            Toast.makeText(context, sb.toString().trimEnd(), Toast.LENGTH_LONG).show()
                        }
                        selectedFiles = emptySet()
                    } else {
                        Toast.makeText(context, "Select only one item", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        } else {
            FileManagerHeader(
                title = pathName,
                subtitle = if (searchQuery.isNotBlank()) "${files.size} found"
                           else if (isLoading) "Loading…"
                           else if (isOfflineMode) {
                               val age = cacheAgeMinutes
                               if (age == null) "Offline · cached" 
                               else if (age < 60) "Offline · cached ${age}m ago"
                               else "Offline · cached ${age/60}h ago"
                           }
                           else "${rawFiles.size} items · Drive",
                onBack = onBack,
                onNewFolder = { showNewFolderDialog = true },
                headerColor = if (isOfflineMode) Color(0xFF546E7A) else Color(0xFF1565C0)
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
                    val isOfflineErr = !DriveCacheManager.isOnline(context)
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Icon(
                                if (isOfflineErr) Icons.Default.CloudQueue else Icons.Default.CloudQueue,
                                contentDescription = null,
                                tint = if (isOfflineErr) Color(0xFF546E7A) else Color.Red.copy(alpha = 0.6f),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                if (isOfflineErr) "You're offline" else "Drive Error",
                                fontWeight = FontWeight.SemiBold,
                                color = if (isOfflineErr) Color(0xFF546E7A) else Color.Red
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (isOfflineErr)
                                    "Open this folder online first to enable offline access"
                                else errorMsg!!,
                                color = Color.Gray,
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
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
                            val isUploading = file.id?.startsWith("uploading_") == true
                            val isCached = !isDir && !isUploading && DriveCacheManager.isFileCached(context, file.id, file.name)
                            val isFolderCached = isDir && DriveCacheManager.hasCachedList(accountName, file.id)
                            FileListItem(
                                name = file.name,
                                isDirectory = isDir,
                                size = when {
                                    isDir -> {
                                        val cachedList = DriveCacheManager.loadFileList(context, accountName, file.id)
                                        if (cachedList != null && cachedList.isNotEmpty()) "${cachedList.size} items"
                                        else if (DriveCacheManager.hasCachedList(accountName, file.id)) "0 items"
                                        else ""
                                    }
                                    file.size != null -> formatFileSize(file.size.toLong())
                                    else -> ""
                                },
                                date = if (isUploading) "Uploading..." else (file.modifiedTime?.value?.let { formatDate(it) } ?: ""),
                                isSelected = isSelected,
                                syncIcon = when {
                                    isUploading -> Icons.Default.KeyboardArrowUp
                                    isDir && isOfflineMode -> if (isFolderCached) Icons.Default.CheckCircle else Icons.Default.CloudQueue
                                    !isDir -> if (isCached) Icons.Default.CheckCircle else Icons.Default.CloudQueue
                                    else -> null
                                },
                                syncIconTint = when {
                                    isUploading -> Color(0xFF2196F3)
                                    isDir && isOfflineMode -> if (isFolderCached) Color(0xFF4CAF50) else Color(0xFFBDBDBD)
                                    isCached -> Color(0xFF4CAF50)
                                    else -> Color(0xFFBDBDBD)
                                },
                                onClick = {
                                    if (isUploading) {
                                        Toast.makeText(context, "File is currently uploading", Toast.LENGTH_SHORT).show()
                                    } else if (selectedFiles.isNotEmpty()) {
                                        selectedFiles = if (isSelected) selectedFiles - file.id else selectedFiles + file.id
                                    } else {
                                        if (isDir) {
                                            // Offline + never cached → warn but still navigate (will show error there)
                                            if (isOfflineMode && !isFolderCached) {
                                                Toast.makeText(context, "Folder not cached — open it online first", Toast.LENGTH_SHORT).show()
                                            }
                                            onNavigate(NavState.Cloud(accountName, file.id, file.name))
                                        } else {
                                            scope.launch {
                                                if (isCached) {
                                                    val localFile = DriveCacheManager.getCachedFile(context, file.id, file.name)
                                                    if (localFile != null) {
                                                        openLocalFile(context, localFile, onNavigate)
                                                        return@launch
                                                    }
                                                }
                                                // Offline + not cached → cannot download
                                                if (isOfflineMode) {
                                                    Toast.makeText(context, "No internet — file not available offline", Toast.LENGTH_SHORT).show()
                                                    return@launch
                                                }
                                                Toast.makeText(context, "Downloading...", Toast.LENGTH_SHORT).show()
                                                val localFile = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                    val destDir = DriveCacheManager.getCacheDir(context)
                                                    val safeName = "${file.id}_${file.name}"
                                                    DriveFileManager.downloadFile(context, accountName, file.id, safeName, destDir)
                                                }
                                                if (localFile != null) {
                                                    DriveCacheManager.markFileDownloaded(context, file.id, file.name)
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

            }
            }
        }

        // ── Paste footer bar (cloud) ─────────────────────────────────────────────
        if (clipboard != null && selectedFiles.isEmpty()) {
            PasteFooterBar(
                isCut = clipboard.isCut,
                itemCount = clipboard.items.size,
                onCancel = { onSetClipboard(null) },
                onPaste = {
                    // Snapshot clipboard before clearing so lambda keeps correct data
                    val snap = clipboard
                    onSetClipboard(null)
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(context,
                                if (snap.isCut) "Moving ${snap.items.size} item(s) to Drive…"
                                else "Uploading ${snap.items.size} item(s) to Drive…",
                                Toast.LENGTH_SHORT).show()
                        }
                        var success = true
                        if (snap.sourceEnv == "Local") {
                            for (item in snap.items) {
                                val src = java.io.File(item)
                                val ok = if (src.isDirectory) {
                                    DriveFileManager.uploadFolder(context, accountName, src, folderId)
                                } else {
                                    DriveFileManager.uploadFile(context, accountName, src, folderId) != null
                                }
                                if (!ok) { success = false } else if (snap.isCut) {
                                    // Delete local only after successful upload
                                    try { src.deleteRecursively() } catch (_: Exception) {}
                                }
                            }
                        } else if (snap.sourceEnv == "Cloud") {
                            val srcAccount = snap.accountName ?: accountName
                            val isCrossAccount = srcAccount != accountName
                            for (i in snap.items.indices) {
                                val id   = snap.items[i]
                                val name = snap.itemNames.getOrNull(i) ?: "file"
                                val mime = snap.itemMimeTypes.getOrNull(i) ?: ""
                                val isFolder = mime == "application/vnd.google-apps.folder"
                                val ok = when {
                                    snap.isCut && !isCrossAccount -> DriveFileManager.moveFile(context, srcAccount, id, folderId, "root") != null
                                    isCrossAccount -> DriveFileManager.crossAccountCopyFile(context, srcAccount, accountName, id, name, folderId, isFolder).also {
                                        if (it && snap.isCut) DriveFileManager.deleteFile(context, srcAccount, id)
                                    }
                                    isFolder -> DriveFileManager.copyFolderRecursive(context, srcAccount, id, name, folderId)
                                    else -> DriveFileManager.copyFile(context, srcAccount, id, folderId) != null
                                }
                                if (!ok) success = false
                            }
                        }
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(context, if (success) "Done!" else "Some items failed", Toast.LENGTH_SHORT).show()
                            isLoading = true
                            rawFiles = DriveFileManager.listFiles(context, accountName, folderId) ?: emptyList()
                            isLoading = false
                        }
                    }
                }
            )
        }

        // ── Footer: selection action bar — always pinned at bottom ────────────
        if (selectedFiles.isNotEmpty()) {
            SelectionBottomBar(
                onCopy = {
                    val names = selectedFiles.mapNotNull { id -> rawFiles.find { it.id == id }?.name }
                    val mimes = selectedFiles.mapNotNull { id -> rawFiles.find { it.id == id }?.mimeType }
                    onSetClipboard(ClipboardState("Cloud", selectedFiles.toList(), itemNames = names, itemMimeTypes = mimes, isCut = false, accountName = accountName))
                    selectedFiles = emptySet()
                },
                onMove = {
                    val names = selectedFiles.mapNotNull { id -> rawFiles.find { it.id == id }?.name }
                    val mimes = selectedFiles.mapNotNull { id -> rawFiles.find { it.id == id }?.mimeType }
                    onSetClipboard(ClipboardState("Cloud", selectedFiles.toList(), itemNames = names, itemMimeTypes = mimes, isCut = true, accountName = accountName))
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
                onShare = {
                    scope.launch {
                        val filesToShare = selectedFiles.mapNotNull { fileId ->
                            rawFiles.find { it.id == fileId }
                        }.filter { it.mimeType != "application/vnd.google-apps.folder" }
                        if (filesToShare.isEmpty()) {
                            Toast.makeText(context, "Cannot share folders", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Downloading ${filesToShare.size} file(s) to share...", Toast.LENGTH_SHORT).show()
                            val localFiles = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val dir = DriveCacheManager.getCacheDir(context)
                                filesToShare.mapNotNull { f ->
                                    DriveFileManager.downloadFile(context, accountName, f.id, "${f.id}_${f.name}", dir)
                                }
                            }
                            if (localFiles.isNotEmpty()) {
                                shareLocalFiles(context, localFiles)
                                selectedFiles = emptySet()
                            } else {
                                Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                onProperties = {
                    if (selectedFiles.size == 1) {
                        val fileId = selectedFiles.first()
                        val file = rawFiles.find { it.id == fileId }
                        if (file != null) {
                            val sb = StringBuilder()
                            sb.appendLine("Name: ${file.name}")
                            sb.appendLine("Type: ${if (file.mimeType == "application/vnd.google-apps.folder") "Folder" else file.mimeType ?: "Unknown"}")
                            if (file.size != null) sb.appendLine("Size: ${formatFileSize(file.size.toLong())}")
                            if (file.modifiedTime != null) sb.appendLine("Modified: ${formatDate(file.modifiedTime.value)}")
                            sb.appendLine("Drive ID: ${file.id}")
                            Toast.makeText(context, sb.toString().trimEnd(), Toast.LENGTH_LONG).show()
                        }
                        selectedFiles = emptySet()
                    } else {
                        Toast.makeText(context, "Select one item for properties", Toast.LENGTH_SHORT).show()
                    }
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
    isGrid: Boolean = false,
    localFile: java.io.File? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onPropertiesClick: (() -> Unit)? = null,
    onShareClick: (() -> Unit)? = null,
    syncIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    syncIconTint: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Gray
) {
    val ext = name.substringAfterLast('.', "").lowercase()
    var showMenu by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isSelected) Color(0xFFB2DFDB) else Color.White)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                val isImage = ext in listOf("jpg","jpeg","png","gif","bmp","webp","heic","heif")
                val isVideo = ext in listOf("mp4","mkv","avi","mov","webm","3gp")
                val isApk = ext == "apk"

                var apkIcon by remember(localFile) { mutableStateOf<android.graphics.drawable.Drawable?>(null) }
                val context = androidx.compose.ui.platform.LocalContext.current

                if (localFile != null && isApk) {
                    LaunchedEffect(localFile) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val pm = context.packageManager
                            val pi = pm.getPackageArchiveInfo(localFile!!.absolutePath, 0)
                            pi?.applicationInfo?.let {
                                it.sourceDir = localFile!!.absolutePath
                                it.publicSourceDir = localFile!!.absolutePath
                                apkIcon = it.loadIcon(pm)
                            }
                        }
                    }
                }

                if (localFile != null && (isImage || isVideo || apkIcon != null)) {
                    AsyncImage(
                        model = coil.request.ImageRequest.Builder(context)
                            .data(if (apkIcon != null) apkIcon else localFile)
                            .crossfade(false)
                            .size(96)
                            .apply {
                                if (isVideo) {
                                    decoderFactory(coil.decode.VideoFrameDecoder.Factory())
                                }
                            }
                            .build(),
                        contentDescription = name,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        error = androidx.compose.ui.graphics.painter.ColorPainter(
                            androidx.compose.ui.graphics.Color(0xFFEEEEEE)
                        )
                    )
                    if (isVideo) {
                        Icon(
                            imageVector = Icons.Default.PlayCircleOutline,
                            contentDescription = null,
                            tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(22.dp).align(Alignment.Center)
                        )
                    }
                } else {
                    FileTypeIcon(ext = ext, isDirectory = isDirectory, sizeDp = 56, fileName = name)
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    fontSize = 16.sp,
                    color = Color.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Normal
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = size,
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                    Text(
                        text = date,
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                }
            }

            if (syncIcon != null) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = syncIcon,
                    contentDescription = "Sync state",
                    tint = syncIconTint,
                    modifier = Modifier.size(18.dp)
                )
            }

            if (onPropertiesClick != null || onShareClick != null) {
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = Color.Gray, modifier = Modifier.size(18.dp))
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
        HorizontalDivider(
            color = Color(0xFFBDBDBD),
            thickness = 0.8.dp
        )
    }
}

// ── Folder view header — clean design with breadcrumb feel ────────────────────
@Composable
fun FileManagerHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onNewFolder: () -> Unit,
    isGridView: Boolean = false,
    onToggleGrid: () -> Unit = {},
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    headerColor: Color = Color(0xFF00796B),
    onClearCache: (() -> Unit)? = null
) {
    var isSearchExpanded by remember { mutableStateOf(false) }

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
                if (isSearchExpanded) {
                    IconButton(onClick = { 
                        isSearchExpanded = false
                        onSearchQueryChange("")
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Close search", tint = Color.White)
                    }
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier.weight(1f).padding(vertical = 4.dp, horizontal = 4.dp),
                        placeholder = { Text("Search...", color = Color.White.copy(alpha = 0.7f)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true
                    )
                } else {
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
                    IconButton(onClick = { isSearchExpanded = true }) {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
                    }
                    IconButton(onClick = onToggleGrid) {
                        Icon(
                            if (isGridView) Icons.Default.ViewList else Icons.Default.GridView,
                            contentDescription = "Toggle View",
                            tint = Color.White
                        )
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
}

// ── Copy/Move Progress Dialog ─────────────────────────────────────────────────
@Composable
fun CopyMoveProgressDialog(
    operation: FileOperation,
    onHide: () -> Unit,
    onCancel: () -> Unit
) {
    val pct = (operation.progress * 100)
    val pctText = "%.2f%%".format(pct)
    val elapsedFormatted = run {
        val s = operation.elapsedSeconds
        "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }
    val speedText = run {
        val bps = operation.speedBytesPerSec
        when {
            bps >= 1024 * 1024 -> "%.1f MB/s".format(bps / (1024.0 * 1024.0))
            bps >= 1024        -> "%.1f KB/s".format(bps / 1024.0)
            else               -> "$bps B/s"
        }
    }
    fun fmtBytes(b: Long): String = when {
        b >= 1024L * 1024 * 1024 -> "%.2f GB".format(b / (1024.0 * 1024 * 1024))
        b >= 1024L * 1024        -> "%.2f MB".format(b / (1024.0 * 1024))
        b >= 1024L               -> "%.2f KB".format(b / 1024.0)
        else                     -> "$b B"
    }
    val title = if (operation.type == OperationType.MOVE) "Moving" else "Copying"

    Dialog(
        onDismissRequest = { /* block dismiss on outside tap */ },
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFFF5F5F5),
            shadowElevation = 16.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {

                // Title row: "Copying" / "Moving"   +   "12.12%"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF111111)
                    )
                    Text(
                        text = pctText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF111111)
                    )
                }

                Spacer(Modifier.height(16.dp))

                // From path
                if (operation.currentSourcePath.isNotBlank()) {
                    Text(
                        text = "From : ${operation.currentSourcePath}",
                        fontSize = 13.sp,
                        color = Color(0xFF555555),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(10.dp))
                }

                // To path
                if (operation.currentDestPath.isNotBlank()) {
                    Text(
                        text = "To : ${operation.currentDestPath}",
                        fontSize = 13.sp,
                        color = Color(0xFF555555),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(14.dp))
                }

                // Size progress + speed
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${fmtBytes(operation.bytesProcessed)} / ${fmtBytes(operation.totalBytes)}",
                        fontSize = 14.sp,
                        color = Color(0xFF444444)
                    )
                    Text(
                        text = speedText,
                        fontSize = 14.sp,
                        color = Color(0xFF444444)
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Progress bar
                LinearProgressIndicator(
                    progress = { operation.progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = Color(0xFF009688),
                    trackColor = Color(0xFFCCCCCC)
                )

                Spacer(Modifier.height(8.dp))

                // Item count + elapsed time
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Progress : ${operation.itemsProcessed} / ${operation.sourceCount}",
                        fontSize = 13.sp,
                        color = Color(0xFF666666)
                    )
                    Text(
                        text = elapsedFormatted,
                        fontSize = 13.sp,
                        color = Color(0xFF666666)
                    )
                }

                Spacer(Modifier.height(20.dp))

                // Buttons: Hide | Cancel
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onHide) {
                        Text("Hide", color = Color(0xFF009688), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                    TextButton(onClick = onCancel) {
                        Text("Cancel", color = Color(0xFF009688), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

// ── Paste Footer Bar — Cancel + Paste (full width, 2 buttons) ────────────────
@Composable
fun PasteFooterBar(
    isCut: Boolean,
    itemCount: Int,
    onCancel: () -> Unit,
    onPaste: () -> Unit
) {
    Surface(
        color = Color(0xFF1A5C5C),
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Cancel
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onCancel() },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Close, contentDescription = "Cancel",
                    tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Cancel", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            // Divider
            Box(modifier = Modifier.width(1.dp).fillMaxHeight().padding(vertical = 12.dp)
                .background(Color.White.copy(alpha = 0.3f)))
            // Paste
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onPaste() },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = "Paste",
                    tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isCut) "Move here" else "Paste",
                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// ── Selection top bar ──────────────────────────────────────────────────────────
@Composable
fun SelectionTopBar(
    selectedCount: Int,
    totalCount: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit = {},
    onZip: (() -> Unit)? = null,
    onUnzip: (() -> Unit)? = null,
    onMergePdf: (() -> Unit)? = null,
    onPdfToImages: (() -> Unit)? = null,
    onImagesToPdf: (() -> Unit)? = null,
    onSecure: (() -> Unit)? = null,
    onProperties: (() -> Unit)? = null
) {
    var showMoreMenu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

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
            
            androidx.compose.foundation.layout.Box {
                IconButton(onClick = { showMoreMenu = true }) {
                    Icon(imageVector = Icons.Default.MoreVert, contentDescription = "More Options", tint = Color.White)
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false }
                ) {
                    if (onZip != null) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Zip") },
                            onClick = { showMoreMenu = false; onZip() },
                            leadingIcon = { Icon(Icons.Default.FolderZip, null) }
                        )
                    }
                    if (onUnzip != null) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Unzip") },
                            onClick = { showMoreMenu = false; onUnzip() },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, null) }
                        )
                    }
                    if (onMergePdf != null) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Merge PDFs") },
                            onClick = { showMoreMenu = false; onMergePdf() },
                            leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) }
                        )
                    }
                    if (onPdfToImages != null) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("PDF to Images") },
                            onClick = { showMoreMenu = false; onPdfToImages() },
                            leadingIcon = { Icon(Icons.Default.Image, null) }
                        )
                    }
                    if (onImagesToPdf != null) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Images to PDF") },
                            onClick = { showMoreMenu = false; onImagesToPdf() },
                            leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) }
                        )
                    }
                    if (onSecure != null) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Secure") },
                            onClick = { showMoreMenu = false; onSecure() },
                            leadingIcon = { Icon(Icons.Default.Lock, null) }
                        )
                    }
                    if (onProperties != null) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Properties") },
                            onClick = { showMoreMenu = false; onProperties() },
                            leadingIcon = { Icon(Icons.Default.Info, null) }
                        )
                    }
                }
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
    onShare: () -> Unit = {},
    onZip: (() -> Unit)? = null,
    onUnzip: (() -> Unit)? = null,
    onMergePdf: (() -> Unit)? = null,
    onPdfToImages: (() -> Unit)? = null,
    onImagesToPdf: (() -> Unit)? = null,
    onSecure: (() -> Unit)? = null,
    onProperties: (() -> Unit)? = null
) {
    var showMoreMenu by remember { mutableStateOf(false) }
    val hasMore = onZip != null || onUnzip != null || onMergePdf != null ||
                  onPdfToImages != null || onImagesToPdf != null ||
                  onSecure != null || onProperties != null

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
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SelectionAction(icon = Icons.Default.ContentCopy,   label = "Copy",   onClick = onCopy)
            SelectionAction(icon = Icons.Default.DriveFileMove, label = "Move",   onClick = onMove)
            SelectionAction(icon = Icons.Default.Share,         label = "Share",  onClick = onShare)
            SelectionAction(icon = Icons.Default.Edit,          label = "Rename", onClick = onRename)
            SelectionAction(icon = Icons.Default.Delete,        label = "Delete", onClick = onDelete)
            if (hasMore) {
                Box {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { showMoreMenu = true }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Icon(imageVector = Icons.Default.MoreVert, contentDescription = "More",
                            tint = Color.White, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(2.dp))
                        Text(text = "More", color = Color.White, fontSize = 10.sp,
                            fontWeight = FontWeight.Medium)
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false }
                    ) {
                        if (onZip != null) DropdownMenuItem(
                            text = { Text("Zip") },
                            leadingIcon = { Icon(Icons.Default.FolderZip, null) },
                            onClick = { showMoreMenu = false; onZip() }
                        )
                        if (onUnzip != null) DropdownMenuItem(
                            text = { Text("Unzip") },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                            onClick = { showMoreMenu = false; onUnzip() }
                        )
                        if (onMergePdf != null) DropdownMenuItem(
                            text = { Text("Merge PDFs") },
                            leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) },
                            onClick = { showMoreMenu = false; onMergePdf() }
                        )
                        if (onPdfToImages != null) DropdownMenuItem(
                            text = { Text("PDF to Images") },
                            leadingIcon = { Icon(Icons.Default.Image, null) },
                            onClick = { showMoreMenu = false; onPdfToImages() }
                        )
                        if (onImagesToPdf != null) DropdownMenuItem(
                            text = { Text("Images to PDF") },
                            leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) },
                            onClick = { showMoreMenu = false; onImagesToPdf() }
                        )
                        if (onSecure != null) DropdownMenuItem(
                            text = { Text("Secure to Vault") },
                            leadingIcon = { Icon(Icons.Default.Lock, null) },
                            onClick = { showMoreMenu = false; onSecure() }
                        )
                        if (onProperties != null) DropdownMenuItem(
                            text = { Text("Properties") },
                            leadingIcon = { Icon(Icons.Default.Info, null) },
                            onClick = { showMoreMenu = false; onProperties() }
                        )
                    }
                }
            }
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

// ── FileTypeIcon ───────────────────────────────────────────────────────────────
@Composable
fun FileTypeIcon(ext: String, isDirectory: Boolean, sizeDp: Int = 40, fileName: String = "") {
    val (icon, tint) = when {
        isDirectory -> Icons.Default.Folder to Color(0xFFEBA953)
        ext == "pdf" -> Icons.Default.PictureAsPdf to Color(0xFFE53935)
        ext in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic") ->
            Icons.Default.Image to Color(0xFF1E88E5)
        ext in listOf("mp4", "mkv", "avi", "mov", "webm", "3gp") ->
            Icons.Default.VideoFile to Color(0xFF8E24AA)
        ext in listOf("mp3", "wav", "ogg", "flac", "aac", "m4a") ->
            Icons.Default.AudioFile to Color(0xFF00ACC1)
        ext in listOf("doc", "docx", "odt", "txt", "rtf") ->
            Icons.Default.Description to Color(0xFF1565C0)
        ext in listOf("xls", "xlsx", "ods", "csv") ->
            Icons.Default.TableChart to Color(0xFF2E7D32)
        ext in listOf("ppt", "pptx", "odp") ->
            Icons.Default.Slideshow to Color(0xFFE65100)
        ext in listOf("zip", "rar", "7z", "tar", "gz", "bz2") ->
            Icons.Default.FolderZip to Color(0xFF6D4C41)
        ext == "apk" -> Icons.Default.Android to Color(0xFF43A047)
        ext in listOf("kt", "java", "py", "js", "ts", "html", "css", "xml", "json", "sh") ->
            Icons.Default.Code to Color(0xFF546E7A)
        ext in listOf("txt", "log", "md") ->
            Icons.Default.TextSnippet to Color(0xFF78909C)
        else -> Icons.Default.InsertDriveFile to Color(0xFF90A4AE)
    }
    
    Box(contentAlignment = Alignment.Center) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(sizeDp.dp)
        )
        
        if (isDirectory && fileName.isNotEmpty()) {
            val badgeIcon = when (fileName.lowercase()) {
                "dcim", "pictures", "images" -> Icons.Default.CameraAlt
                "download", "downloads" -> Icons.Default.Download
                "documents", "document" -> Icons.Default.Description
                "music", "audio" -> Icons.Default.MusicNote
                "movies", "video" -> Icons.Default.Movie
                "android" -> Icons.Default.Android
                else -> null
            }
            if (badgeIcon != null) {
                Box(
                    modifier = Modifier
                        .padding(top = (sizeDp * 0.15f).dp)
                        .background(Color.White, RoundedCornerShape(3.dp))
                        .padding(1.5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = badgeIcon,
                        contentDescription = null,
                        tint = Color(0xFF2196F3),
                        modifier = Modifier.size((sizeDp * 0.35f).dp)
                    )
                }
            }
        }
    }
}



@Composable
fun ActiveOperationsBar(operations: List<FileOperation>) {
    if (operations.isEmpty()) return
    val op = operations.firstOrNull { !it.isComplete && !it.isCancelled } ?: return
    val pct = (op.progress * 100).toInt()
    val text = if (op.type == OperationType.COPY) "Copying ${op.itemsProcessed}/${op.sourceCount} items... $pct%" else "Moving ${op.itemsProcessed}/${op.sourceCount} items... $pct%"
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF333333)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text, color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            androidx.compose.material3.LinearProgressIndicator(
                progress = { op.progress },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = Color(0xFF00B0FF),
                trackColor = Color.DarkGray
            )
            Text(op.currentFileName, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = { FileOperationManager.updateOperation(op.id) { it.copy(isCancelled = true) } }) {
            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
        }
    }
}















@Composable
fun FileOperationsBanner(modifier: Modifier = Modifier) {
    val operations by FileOperationManager.operations.collectAsState()
    val activeOps = operations.filter { !it.isComplete && !it.isCancelled && !it.isError }

    AnimatedVisibility(
        visible = activeOps.isNotEmpty(),
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .padding(16.dp)
        ) {
            Text("Active Operations ()", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            activeOps.forEach { op ->
                val progress = if (op.totalBytes > 0) op.bytesProcessed.toFloat() / op.totalBytes else 0f
                val typeName = if (op.type == OperationType.MOVE) "Moving" else "Copying"
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "$typeName: ",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                            trackColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
                        )
                    }
                    IconButton(onClick = { FileOperationManager.updateOperation(op.id) { it.copy(isCancelled = true) } }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                }
            }
        }
    }
}





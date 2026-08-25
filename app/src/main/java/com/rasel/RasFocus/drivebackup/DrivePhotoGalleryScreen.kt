package com.rasel.RasFocus.drivebackup

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.api.services.drive.model.File
import com.rasel.RasFocus.filemanager.DriveCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Group images by date (e.g. "Aug 2026") ────────────────────────────────────
private fun groupByMonth(images: List<File>): List<Pair<String, List<File>>> {
    val fmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    val grouped = LinkedHashMap<String, MutableList<File>>()
    images.forEach { file ->
        val ts = file.modifiedTime?.value ?: 0L
        val label = if (ts > 0) fmt.format(Date(ts)) else "Unknown date"
        grouped.getOrPut(label) { mutableListOf() }.add(file)
    }
    return grouped.entries.map { Pair(it.key, it.value) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrivePhotoGalleryScreen(
    accountName: String,
    isOnline: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var images by remember { mutableStateOf<List<File>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var selectedImage by remember { mutableStateOf<File?>(null) }
    var offlineProgress by remember { mutableStateOf<String?>(null) }

    // Load all images from Drive
    LaunchedEffect(accountName) {
        isLoading = true
        errorMsg = null
        val result = withContext(Dispatchers.IO) {
            DriveFileManager.listImages(context, accountName)
        }
        if (result != null) {
            images = result
        } else {
            errorMsg = DriveFileManager.lastError ?: "Failed to load photos"
        }
        isLoading = false
    }

    val grouped = remember(images) { groupByMonth(images) }

    Box(Modifier.fillMaxSize().background(Color(0xFF0D0D0D))) {
        when {
            isLoading -> {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Text("Loading photos...", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                }
            }

            errorMsg != null -> {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.CloudOff, contentDescription = null,
                        tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(errorMsg ?: "", color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            }

            images.isEmpty() -> {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null,
                        tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No photos in Drive", color = Color.White.copy(alpha = 0.5f), fontSize = 15.sp)
                }
            }

            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    grouped.forEach { (month, monthImages) ->
                        // Month header
                        item(span = { GridItemSpan(3) }) {
                            Text(
                                text = month,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF0D0D0D))
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }

                        items(monthImages, key = { it.id ?: it.name ?: "" }) { image ->
                            GalleryPhotoCell(
                                file = image,
                                context = context,
                                onClick = { selectedImage = image }
                            )
                        }
                    }

                    // Bottom padding
                    item(span = { GridItemSpan(3) }) { Spacer(Modifier.height(80.dp)) }
                }

                // Summary chip at top
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.55f),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Text(
                            text = "${images.size} photos",
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }

                // Offline progress snackbar
                if (offlineProgress != null) {
                    Box(Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF323232))) {
                            Column(Modifier.padding(12.dp)) {
                                Text(offlineProgress!!, color = Color.White, fontSize = 13.sp)
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Color(0xFF4CAF50)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Full-screen photo viewer ───────────────────────────────────────────────
    selectedImage?.let { photo ->
        PhotoViewerDialog(
            file = photo,
            context = context,
            accountName = accountName,
            isOnline = isOnline,
            onDismiss = { selectedImage = null },
            onSaveOffline = { file ->
                scope.launch {
                    val fid = file.id ?: return@launch
                    val name = file.name ?: return@launch
                    if (DriveCacheManager.isPinned(fid)) {
                        DriveCacheManager.unpin(fid)
                        Toast.makeText(context, "Removed from offline", Toast.LENGTH_SHORT).show()
                    } else {
                        DriveCacheManager.pin(fid)
                        offlineProgress = "Saving $name offline..."
                        val dest = DriveCacheManager.getCacheDir(context)
                        val result = DriveFileManager.downloadFile(context, accountName, fid, name, dest)
                        offlineProgress = null
                        if (result != null) {
                            DriveCacheManager.markFileDownloaded(context, fid, name)
                            Toast.makeText(context, "Saved offline", Toast.LENGTH_SHORT).show()
                        } else {
                            DriveCacheManager.unpin(fid)
                            Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        )
    }
}

// ── Single photo cell in grid ─────────────────────────────────────────────────
@Composable
private fun GalleryPhotoCell(
    file: File,
    context: Context,
    onClick: () -> Unit
) {
    val thumbUrl = file.thumbnailLink?.replace("=s220", "=s400")
    val isPinned = DriveCacheManager.isPinned(file.id ?: "")

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0xFF1A1A1A))
            .clickable { onClick() }
    ) {
        if (thumbUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(thumbUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = file.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            // No thumbnail available
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Image, contentDescription = null,
                    tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(28.dp))
            }
        }

        // Offline badge
        if (isPinned) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(3.dp)
                    .size(16.dp)
                    .background(Color(0xFF4CAF50), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Check, contentDescription = "Offline",
                    tint = Color.White, modifier = Modifier.size(10.dp))
            }
        }
    }
}

// ── Full-screen photo viewer dialog ───────────────────────────────────────────
@Composable
private fun PhotoViewerDialog(
    file: File,
    context: Context,
    accountName: String,
    isOnline: Boolean,
    onDismiss: () -> Unit,
    onSaveOffline: (File) -> Unit
) {
    val isPinned = remember(file.id) { DriveCacheManager.isPinned(file.id ?: "") }

    // Use full-size webContentLink or thumbnailLink without size cap
    val fullUrl = file.thumbnailLink?.replace("=s220", "=s1600")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Full image
            if (fullUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(fullUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = file.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Image, contentDescription = null,
                            tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Preview not available", color = Color.White.copy(alpha = 0.5f))
                    }
                }
            }

            // Top bar — close + file name
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.ArrowBack, "Close", tint = Color.White)
                }
                Text(
                    text = file.name ?: "",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            // Bottom bar — info + offline save
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .align(Alignment.BottomCenter),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    val dateStr = try {
                        SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                            .format(Date(file.modifiedTime?.value ?: 0))
                    } catch (_: Exception) { "" }
                    if (dateStr.isNotEmpty()) {
                        Text(dateStr, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                    val sizeStr = run {
                        val bytes = file.getSize() ?: 0L
                        when {
                            bytes > 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
                            bytes > 1024 -> "${bytes / 1024} KB"
                            bytes > 0 -> "$bytes B"
                            else -> ""
                        }
                    }
                    if (sizeStr.isNotEmpty()) {
                        Text(sizeStr, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                    }
                }

                // Save offline button
                if (isOnline) {
                    IconButton(onClick = { onSaveOffline(file) }) {
                        Icon(
                            imageVector = if (isPinned) Icons.Default.CloudDone else Icons.Default.CloudDownload,
                            contentDescription = if (isPinned) "Remove offline" else "Save offline",
                            tint = if (isPinned) Color(0xFF4CAF50) else Color.White
                        )
                    }
                }
            }
        }
    }
}

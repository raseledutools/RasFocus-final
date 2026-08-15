package com.rasel.RasFocus.filemanager

import android.content.Context
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class CategoryMediaItem(
    val id: Long,
    val path: String,
    val name: String,
    val size: Long,
    val dateModified: Long,
    val mimeType: String
)

object CategoryUtils {
    
    fun getCategoryStats(context: Context, category: String): Pair<Int, Long> {
        var count = 0
        var totalSize = 0L
        val uri = when (category) {
            "Images" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "Audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            "Videos" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "Documents", "New files", "Apps" -> MediaStore.Files.getContentUri("external")
            else -> return Pair(0, 0L)
        }

        val projection = arrayOf(MediaStore.MediaColumns.SIZE)
        var selection: String? = null
        var selectionArgs: Array<String>? = null

        when (category) {
            "Documents" -> {
                selection = "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?"
                selectionArgs = arrayOf("%text%", "%pdf%", "%msword%")
            }
            "New files" -> {
                val sevenDaysAgo = (System.currentTimeMillis() / 1000) - (7 * 24 * 60 * 60)
                selection = "${MediaStore.MediaColumns.DATE_ADDED} > ?"
                selectionArgs = arrayOf(sevenDaysAgo.toString())
            }
            "Apps" -> {
                selection = "${MediaStore.Files.FileColumns.DATA} LIKE ?"
                selectionArgs = arrayOf("%.apk")
            }
        }

        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                count = cursor.count
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    totalSize += cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return Pair(count, totalSize)
    }

    suspend fun getCategoryFiles(context: Context, category: String): List<CategoryMediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<CategoryMediaItem>()
        val uri = when (category) {
            "Images" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "Audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            "Videos" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "Documents", "New files", "Apps" -> MediaStore.Files.getContentUri("external")
            else -> return@withContext emptyList()
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE
        )
        
        var selection: String? = null
        var selectionArgs: Array<String>? = null
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        when (category) {
            "Documents" -> {
                selection = "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?"
                selectionArgs = arrayOf("%text%", "%pdf%", "%msword%")
            }
            "New files" -> {
                val sevenDaysAgo = (System.currentTimeMillis() / 1000) - (7 * 24 * 60 * 60)
                selection = "${MediaStore.MediaColumns.DATE_ADDED} > ?"
                selectionArgs = arrayOf(sevenDaysAgo.toString())
            }
            "Apps" -> {
                selection = "${MediaStore.Files.FileColumns.DATA} LIKE ?"
                selectionArgs = arrayOf("%.apk")
            }
        }

        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val path = cursor.getString(pathCol)
                    if (path != null && File(path).exists()) {
                        items.add(
                            CategoryMediaItem(
                                id = cursor.getLong(idCol),
                                path = path,
                                name = cursor.getString(nameCol) ?: File(path).name,
                                size = cursor.getLong(sizeCol),
                                dateModified = cursor.getLong(dateCol) * 1000,
                                mimeType = cursor.getString(mimeCol) ?: "*/*"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext items
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(category: String, onBack: () -> Unit, onFileClick: (File) -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<CategoryMediaItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(category) {
        items = CategoryUtils.getCategoryFiles(context, category)
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(category) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF00796B), titleContentColor = Color.White, navigationIconContentColor = Color.White)
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No files found", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color(0xFFF2F2F7))
            ) {
                items(items) { item ->
                    CategoryFileItem(item = item, onClick = { onFileClick(File(item.path)) })
                }
            }
        }
    }
}

@Composable
fun CategoryFileItem(item: CategoryMediaItem, onClick: () -> Unit) {
    val dateFormatter = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(Color.White)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.InsertDriveFile,
            contentDescription = null,
            tint = Color.Gray,
            modifier = Modifier.size(40.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Row {
                Text(
                    text = dateFormatter.format(Date(item.dateModified)),
                    color = Color.Gray,
                    fontSize = 12.sp
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatFileSizeForCategory(item.size),
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
        }
    }
    HorizontalDivider(color = Color(0xFFEEEEEE), thickness = 1.dp)
}

fun formatFileSizeForCategory(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.US, "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

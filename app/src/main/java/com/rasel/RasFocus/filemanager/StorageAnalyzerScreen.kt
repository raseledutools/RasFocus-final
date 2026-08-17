package com.rasel.RasFocus.filemanager

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageAnalyzerScreen(onBack: () -> Unit) {
    var isLoading by remember { mutableStateOf(true) }
    var stats by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var totalSize by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val root = File(LocalFileManager.mainStoragePath)
            val calculatedStats = mutableMapOf(
                "Images" to 0L,
                "Videos" to 0L,
                "Audio" to 0L,
                "Documents" to 0L,
                "APKs" to 0L,
                "Archives" to 0L,
                "Others" to 0L
            )
            
            fun scan(dir: File) {
                val files = dir.listFiles() ?: return
                for (file in files) {
                    if (file.isDirectory && !file.name.startsWith(".")) {
                        scan(file)
                    } else if (file.isFile) {
                        val size = file.length()
                        val ext = file.extension.lowercase()
                        totalSize += size
                        when (ext) {
                            "jpg", "jpeg", "png", "gif", "webp" -> calculatedStats["Images"] = calculatedStats["Images"]!! + size
                            "mp4", "mkv", "avi", "mov" -> calculatedStats["Videos"] = calculatedStats["Videos"]!! + size
                            "mp3", "wav", "ogg", "flac" -> calculatedStats["Audio"] = calculatedStats["Audio"]!! + size
                            "pdf", "doc", "docx", "txt", "xls", "xlsx" -> calculatedStats["Documents"] = calculatedStats["Documents"]!! + size
                            "apk" -> calculatedStats["APKs"] = calculatedStats["APKs"]!! + size
                            "zip", "rar", "7z", "tar", "gz" -> calculatedStats["Archives"] = calculatedStats["Archives"]!! + size
                            else -> calculatedStats["Others"] = calculatedStats["Others"]!! + size
                        }
                    }
                }
            }
            
            scan(root)
            stats = calculatedStats
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage Analyzer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Scanning entire storage... This may take a minute.")
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val colors = listOf(Color.Red, Color.Blue, Color.Green, Color.Magenta, Color(0xFFFFA500), Color.Cyan, Color.Gray)
                val entries = stats.entries.filter { it.value > 0 }.sortedByDescending { it.value }
                
                if (totalSize > 0 && entries.isNotEmpty()) {
                    Box(modifier = Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            var startAngle = -90f
                            entries.forEachIndexed { index, entry ->
                                val sweepAngle = (entry.value.toFloat() / totalSize) * 360f
                                drawArc(
                                    color = colors[index % colors.size],
                                    startAngle = startAngle,
                                    sweepAngle = sweepAngle,
                                    useCenter = true
                                )
                                startAngle += sweepAngle
                            }
                        }
                        Surface(
                            modifier = Modifier.size(100.dp),
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = MaterialTheme.colorScheme.background
                        ) {}
                        Text(formatSize(totalSize), fontWeight = FontWeight.Bold)
                    }
                    
                    Spacer(Modifier.height(32.dp))
                    
                    entries.forEachIndexed { index, entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier.size(16.dp),
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = colors[index % colors.size]
                            ) {}
                            Spacer(Modifier.width(16.dp))
                            Text(entry.key, modifier = Modifier.weight(1f))
                            Text(formatSize(entry.value), color = Color.Gray)
                        }
                    }
                } else {
                    Text("No files found or storage is empty.")
                }
            }
        }
    }
}

private fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return java.text.DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
}

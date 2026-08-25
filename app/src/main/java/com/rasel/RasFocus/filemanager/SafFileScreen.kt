package com.rasel.RasFocus.filemanager

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafFileScreen(
    uriString: String,
    onNavigate: (NavState) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var files by remember { mutableStateOf<List<SafFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    
    val currentUri = Uri.parse(uriString)
    val docFile = remember(uriString) { DocumentFile.fromTreeUri(context, currentUri) }

    LaunchedEffect(uriString) {
        scope.launch(Dispatchers.IO) {
            isLoading = true
            if (docFile != null && docFile.isDirectory) {
                files = SafFileManager.listFilesFromDocument(docFile)
            }
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(docFile?.name ?: "SD Card", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E1E))
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (files.isEmpty()) {
                Text("Folder is empty", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(files) { file ->
                        ListItem(
                            headlineContent = { Text(file.name) },
                            supportingContent = {
                                if (file.isDirectory) Text("Folder")
                                else Text("${file.size} bytes")
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                    contentDescription = null,
                                    tint = if (file.isDirectory) Color(0xFFFFA500) else Color.Gray
                                )
                            },
                            modifier = Modifier.clickable {
                                if (file.isDirectory) {
                                    // Navigate deeper — parent pushes current URI onto backstack
                                    onNavigate(NavState.Saf(file.uri.toString()))
                                } else {
                                    // Open file via UniversalViewerActivity (RasFocus internal viewers)
                                    // instead of the system chooser so PDF/DOCX/image etc. open
                                    // directly inside the app without a picker dialog.
                                    openSafFile(context, file.uri)
                                }
                            }
                        )
                        Divider()
                    }
                }
            }
        }
    }
}

/**
 * Open a SAF (content://) URI using RasFocus's UniversalViewerActivity.
 * Falls back to the system chooser only if UniversalViewerActivity is not found
 * (should never happen in a normal build).
 */
private fun openSafFile(context: android.content.Context, uri: Uri) {
    val mimeType = context.contentResolver.getType(uri) ?: "*/*"

    // Try to route through the internal viewer first
    val pkg = context.packageName.replace(".combo", "")
    val cls = try {
        Class.forName("$pkg.selfcontrol.study_tools.UniversalViewerActivity")
    } catch (_: ClassNotFoundException) { null }

    if (cls != null) {
        val intent = Intent(context, cls).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Carry the grant forward so the viewer can read the file
            clipData = android.content.ClipData.newRawUri("", uri)
        }
        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) { /* fall through to system chooser */ }
    }

    // System chooser fallback
    val fallback = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(fallback)
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Cannot open file: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

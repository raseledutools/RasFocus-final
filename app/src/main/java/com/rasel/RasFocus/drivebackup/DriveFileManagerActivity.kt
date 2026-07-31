package com.rasel.RasFocus.drivebackup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.api.services.drive.model.File
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DriveFileManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                DriveFileManagerScreen {
                    finish()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveFileManagerScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountName = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(context)?.email ?: ""
    
    // Navigation stack: Pair<FolderId, FolderName>
    var navStack by remember { mutableStateOf(listOf(Pair("root", "My Drive"))) }
    val currentFolder = navStack.last()
    
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var showFixDrive by remember { mutableStateOf(false) }
    
    val fixDriveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        showFixDrive = false
        // Retry loading
        scope.launch {
            isLoading = true
            val result = DriveFileManager.listFiles(context, accountName, currentFolder.first)
            if (result != null) {
                files = result
                errorMsg = null
            } else {
                errorMsg = DriveFileManager.lastError
                showFixDrive = DriveFileManager.lastRecoveryIntent != null
            }
            isLoading = false
        }
    }

    fun loadCurrentFolder() {
        scope.launch {
            isLoading = true
            errorMsg = null
            val result = DriveFileManager.listFiles(context, accountName, currentFolder.first)
            if (result != null) {
                // Sort folders first, then files
                files = result.sortedWith(compareBy({ it.mimeType != "application/vnd.google-apps.folder" }, { it.name.lowercase() }))
            } else {
                errorMsg = DriveFileManager.lastError
                showFixDrive = DriveFileManager.lastRecoveryIntent != null
                files = emptyList()
            }
            isLoading = false
        }
    }

    LaunchedEffect(currentFolder.first) {
        loadCurrentFolder()
    }
    
    BackHandler {
        if (navStack.size > 1) {
            navStack = navStack.dropLast(1)
        } else {
            onClose()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentFolder.second, fontSize = 18.sp, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (navStack.size > 1) {
                            navStack = navStack.dropLast(1)
                        } else {
                            onClose()
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF4A90D9),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(Color(0xFFF8FAFC))) {
            
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (errorMsg != null) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Error: $errorMsg", color = Color.Red, modifier = Modifier.padding(bottom = 16.dp))
                    if (showFixDrive) {
                        Button(
                            onClick = {
                                DriveFileManager.lastRecoveryIntent?.let {
                                    fixDriveLauncher.launch(it)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                        ) {
                            Text("Grant Drive Permission")
                        }
                    } else {
                        Button(onClick = { loadCurrentFolder() }) {
                            Text("Retry")
                        }
                    }
                }
            } else if (files.isEmpty()) {
                Text(
                    "Folder is empty",
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.Gray
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(files) { file ->
                        DriveFileItem(
                            file = file,
                            onClick = {
                                if (file.mimeType == "application/vnd.google-apps.folder") {
                                    navStack = navStack + Pair(file.id, file.name)
                                } else {
                                    // Handle file click (Download & Open)
                                    Toast.makeText(context, "Downloading ${file.name}...", Toast.LENGTH_SHORT).show()
                                    scope.launch {
                                        val downloadedFile = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            DriveFileManager.downloadFile(context, accountName, file.id, file.name)
                                        }
                                        if (downloadedFile != null) {
                                            Toast.makeText(context, "Downloaded to ${downloadedFile.absolutePath}", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, "Failed to download", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        )
                        HorizontalDivider(color = Color.LightGray.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

@Composable
fun DriveFileItem(file: File, onClick: () -> Unit) {
    val isFolder = file.mimeType == "application/vnd.google-apps.folder"
    val icon = if (isFolder) Icons.Default.Folder else Icons.Default.InsertDriveFile
    val tint = if (isFolder) Color(0xFFFBC02D) else Color(0xFF5C6BC0)
    
    val dateStr = try {
        val date = Date(file.modifiedTime.value)
        SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(date)
    } catch (e: Exception) {
        ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(40.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name ?: "Unknown", fontWeight = FontWeight.Medium, fontSize = 16.sp)
            if (!isFolder) {
                val sizeKb = (file.getSize() ?: 0) / 1024
                Text("$dateStr • $sizeKb KB", fontSize = 12.sp, color = Color.Gray)
            } else {
                Text(dateStr, fontSize = 12.sp, color = Color.Gray)
            }
        }
    }
}

package com.rasel.RasFocus.p2p

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun P2PChatScreen(
    device: DiscoveredDevice,
    connectionManager: P2PConnectionManager,
    onBack: () -> Unit,
    onBrowseFolders: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var messageText by remember { mutableStateOf("") }
    
    val messages by connectionManager.messages.collectAsState(initial = null)
    val chatHistory = remember { mutableStateListOf<P2PMessage>() }
    
    LaunchedEffect(messages) {
        messages?.let { chatHistory.add(it) }
    }

    val audioRecorder = remember { AudioRecorderHelper(context) }
    val audioPlayer = remember { AudioPlayerHelper() }
    var isRecording by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val fileName = getFileNameFromUri(context, uri) ?: "file_${System.currentTimeMillis()}"
                    val tempFile = File(context.cacheDir, fileName)
                    inputStream?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (tempFile.exists() && tempFile.length() > 0) {
                        connectionManager.sendFile(tempFile)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    val recordPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "Microphone permission required", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(device.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (connectionManager.isConnected) "Connected (${device.ip})" else "P2P Session (${device.ip})",
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { filePickerLauncher.launch("*/*") }) {
                        Icon(Icons.Default.AttachFile, contentDescription = "Send File", tint = Color.White)
                    }
                    IconButton(onClick = onBrowseFolders) {
                        Icon(Icons.Default.Folder, contentDescription = "Browse Shared Folders", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF128C7E))
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFFE5DDD5))
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f).padding(8.dp),
                reverseLayout = false
            ) {
                items(chatHistory) { msg ->
                    MessageBubble(msg, audioPlayer)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Input Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { filePickerLauncher.launch("*/*") }
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Attach File", tint = Color(0xFF128C7E))
                }

                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    modifier = Modifier.weight(1f).background(Color.White, RoundedCornerShape(24.dp)),
                    placeholder = { Text("Type a message") },
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )

                Spacer(modifier = Modifier.width(8.dp))

                if (messageText.isNotBlank()) {
                    FloatingActionButton(
                        onClick = {
                            scope.launch {
                                connectionManager.sendText(messageText)
                                messageText = ""
                            }
                        },
                        containerColor = Color(0xFF128C7E),
                        shape = RoundedCornerShape(50)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White)
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(if (isRecording) Color.Red else Color(0xFF128C7E), RoundedCornerShape(50))
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            return@detectTapGestures
                                        }
                                        isRecording = true
                                        audioRecorder.startRecording()
                                        
                                        tryAwaitRelease()
                                        
                                        isRecording = false
                                        val file = audioRecorder.stopRecording()
                                        if (file != null && file.exists() && file.length() > 0) {
                                            scope.launch {
                                                connectionManager.sendVoice(file)
                                            }
                                        }
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = "Hold to record", tint = Color.White)
                    }
                }
            }
        }
    }
}

private fun getFileNameFromUri(context: Context, uri: Uri): String? {
    var name: String? = null
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index != -1) name = it.getString(index)
        }
    }
    return name
}

@Composable
fun MessageBubble(msg: P2PMessage, audioPlayer: AudioPlayerHelper) {
    val isMe = when (msg) {
        is P2PMessage.Text -> msg.isMe
        is P2PMessage.FileMsg -> msg.isMe
        is P2PMessage.Voice -> msg.isMe
    }

    val bubbleColor = if (isMe) Color(0xFFDCF8C6) else Color.White

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 1.dp,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Box(modifier = Modifier.padding(12.dp)) {
                when (msg) {
                    is P2PMessage.Text -> {
                        Text(msg.message, color = Color.Black, fontSize = 16.sp)
                    }
                    is P2PMessage.FileMsg -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.InsertDriveFile, contentDescription = "File")
                            Spacer(Modifier.width(8.dp))
                            Text(msg.fileName, color = Color.Black)
                        }
                    }
                    is P2PMessage.Voice -> {
                        var playing by remember { mutableStateOf(false) }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                            if (playing) {
                                audioPlayer.stop()
                                playing = false
                            } else {
                                playing = true
                                audioPlayer.play(msg.filePath) {
                                    playing = false
                                }
                            }
                        }) {
                            Icon(if (playing) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = "Play")
                            Spacer(Modifier.width(8.dp))
                            Text("Voice Message", color = Color.Black)
                        }
                    }
                }
            }
        }
    }
}

package com.rasel.RasFocus.filemanager

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile

data class RemoteServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    val pass: String,
    val protocol: String = "FTP"
)

// A simple in-memory store for remote servers. In a real app, save to SharedPreferences or Room.
object RemoteStore {
    val servers = mutableStateListOf<RemoteServer>()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteConnectionsScreen(
    onNavigate: (NavState) -> Unit,
    onBack: () -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Remote Connections", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Server", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E1E))
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (RemoteStore.servers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No remote connections added.", color = Color.Gray)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(RemoteStore.servers) { server ->
                    ListItem(
                        headlineContent = { Text(server.name) },
                        supportingContent = { Text("${server.protocol} • ${server.host}:${server.port}") },
                        leadingContent = { 
                            Icon(
                                if (server.protocol == "SMB") Icons.Default.Folder else Icons.Default.Computer, 
                                contentDescription = null, 
                                tint = Color(0xFF1565C0)
                            ) 
                        },
                        modifier = Modifier.clickable {
                            onNavigate(NavState.Remote(server.id, if (server.protocol == "SMB") "" else "/"))
                        }
                    )
                    Divider()
                }
            }
        }
    }

    if (showAddDialog) {
        var name by remember { mutableStateOf("") }
        var host by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("21") }
        var user by remember { mutableStateOf("anonymous") }
        var pass by remember { mutableStateOf("") }
        var protocol by remember { mutableStateOf("FTP") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add FTP Server") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = protocol == "FTP", onClick = { protocol = "FTP"; port = "21" })
                        Text("FTP")
                        Spacer(Modifier.width(16.dp))
                        RadioButton(selected = protocol == "SMB", onClick = { protocol = "SMB"; port = "445" })
                        Text("SMB")
                    }
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Connection Name") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host / IP") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Username") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("Password") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val newServer = RemoteServer(
                        id = java.util.UUID.randomUUID().toString(),
                        name = name.ifEmpty { host },
                        host = host,
                        port = port.toIntOrNull() ?: if (protocol == "FTP") 21 else 445,
                        user = user,
                        pass = pass,
                        protocol = protocol
                    )
                    RemoteStore.servers.add(newServer)
                    showAddDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun RemoteFileScreen(
    serverId: String,
    initialPath: String,
    onNavigate: (NavState) -> Unit,
    onBack: () -> Unit
) {
    val server = RemoteStore.servers.find { it.id == serverId }
    if (server == null) {
        onBack()
        return
    }

    if (server.protocol == "SMB") {
        SmbFileScreen(server, initialPath, onNavigate, onBack)
    } else {
        FtpFileScreen(server, initialPath, onNavigate, onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FtpFileScreen(
    server: RemoteServer,
    initialPath: String,
    onNavigate: (NavState) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var files by remember { mutableStateOf<List<FTPFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    
    val ftpClient = remember { FTPClient() }

    LaunchedEffect(initialPath) {
        withContext(Dispatchers.IO) {
            try {
                isLoading = true
                if (!ftpClient.isConnected) {
                    ftpClient.connect(server.host, server.port)
                    val success = ftpClient.login(server.user, server.pass)
                    if (!success) {
                        errorMsg = "Login failed"
                        isLoading = false
                        return@withContext
                    }
                    ftpClient.enterLocalPassiveMode()
                }
                ftpClient.changeWorkingDirectory(initialPath)
                val list = ftpClient.listFiles()
                files = list.filter { it.name != "." && it.name != ".." }.sortedBy { !it.isDirectory }
                isLoading = false
            } catch (e: Exception) {
                e.printStackTrace()
                errorMsg = e.message ?: "Connection error"
                isLoading = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            scope.launch(Dispatchers.IO) {
                try {
                    if (ftpClient.isConnected) {
                        ftpClient.logout()
                        ftpClient.disconnect()
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(initialPath, color = Color.White) },
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
            } else if (errorMsg != null) {
                Text(errorMsg!!, color = Color.Red, modifier = Modifier.align(Alignment.Center))
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
                                    imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.Computer,
                                    contentDescription = null,
                                    tint = if (file.isDirectory) Color(0xFFFFA500) else Color.Gray
                                )
                            },
                            modifier = Modifier.clickable {
                                if (file.isDirectory) {
                                    val nextPath = if (initialPath.endsWith("/")) initialPath + file.name else "$initialPath/${file.name}"
                                    onNavigate(NavState.Remote(server.id, nextPath))
                                } else {
                                    android.widget.Toast.makeText(context, "Downloading remote files is coming soon", android.widget.Toast.LENGTH_SHORT).show()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmbFileScreen(
    server: RemoteServer,
    initialPath: String,
    onNavigate: (NavState) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var files by remember { mutableStateOf<List<SmbFile>>(emptyList()) }
    var shares by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    
    val isRoot = initialPath.isEmpty()

    LaunchedEffect(initialPath) {
        isLoading = true
        errorMsg = null
        if (isRoot) {
            val list = SmbFileManager.listShares(server)
            if (list.isEmpty()) {
                errorMsg = "No shares found or connection failed"
            } else {
                shares = list
            }
            isLoading = false
        } else {
            val parts = initialPath.split("/", limit = 2)
            val shareName = parts[0]
            val internalPath = if (parts.size > 1) parts[1] else ""
            val list = SmbFileManager.listFiles(server, shareName, internalPath)
            files = list.sortedBy { !it.isDirectory }
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isRoot) "SMB Shares" else initialPath, color = Color.White) },
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
            } else if (errorMsg != null) {
                Text(errorMsg!!, color = Color.Red, modifier = Modifier.align(Alignment.Center))
            } else if (isRoot && shares.isEmpty()) {
                Text("No shares found", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
            } else if (!isRoot && files.isEmpty()) {
                Text("Folder is empty", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (isRoot) {
                        items(shares) { share ->
                            ListItem(
                                headlineContent = { Text(share) },
                                leadingContent = {
                                    Icon(Icons.Default.Folder, contentDescription = null, tint = Color(0xFFFFA500))
                                },
                                modifier = Modifier.clickable {
                                    onNavigate(NavState.Remote(server.id, share))
                                }
                            )
                            Divider()
                        }
                    } else {
                        items(files) { file ->
                            ListItem(
                                headlineContent = { Text(file.name) },
                                supportingContent = {
                                    if (file.isDirectory) Text("Folder")
                                    else Text("${file.size} bytes")
                                },
                                leadingContent = {
                                    Icon(
                                        imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.Computer,
                                        contentDescription = null,
                                        tint = if (file.isDirectory) Color(0xFFFFA500) else Color.Gray
                                    )
                                },
                                modifier = Modifier.clickable {
                                    if (file.isDirectory) {
                                        val nextPath = "$initialPath/${file.name}"
                                        onNavigate(NavState.Remote(server.id, nextPath))
                                    } else {
                                        android.widget.Toast.makeText(context, "Downloading remote files is coming soon", android.widget.Toast.LENGTH_SHORT).show()
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
}

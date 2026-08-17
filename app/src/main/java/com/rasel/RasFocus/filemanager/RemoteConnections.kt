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
import com.rasel.RasFocus.p2p.P2PDiscoveryManager
import com.rasel.RasFocus.p2p.P2PConnectionManager
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.filled.Wifi

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
    p2pDiscovery: P2PDiscoveryManager,
    p2pConnection: P2PConnectionManager,
    onNavigate: (NavState) -> Unit,
    onBack: () -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showManualIpDialog by remember { mutableStateOf(false) }
    val myIp = remember { P2PDiscoveryManager.getLocalIpAddress() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Remote & P2P Connections", color = Color.White) },
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
        val discoveredDevices by p2pDiscovery.discoveredDevices.collectAsState()

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            // P2P Info Header Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1565C0).copy(alpha = 0.1f)),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Wifi,
                                    contentDescription = null,
                                    tint = Color(0xFF1565C0),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "P2P Local Connection",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color(0xFF1565C0),
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                )
                            }
                            IconButton(onClick = {
                                p2pDiscovery.discoverServices()
                            }) {
                                Icon(
                                    Icons.Default.Add, // or refresh indicator
                                    contentDescription = "Scan",
                                    tint = Color(0xFF1565C0)
                                )
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "My Device IP: $myIp",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Text(
                            text = "Share this IP with other RasFocus devices on your Wi-Fi/Hotspot to connect.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )

                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { p2pDiscovery.discoverServices() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Re-Scan Nearby")
                            }
                            OutlinedButton(
                                onClick = { showManualIpDialog = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Connect by IP")
                            }
                        }
                    }
                }
            }

            // Discovered Devices Section
            item {
                Text(
                    text = "Nearby Devices (Auto-Discovered)",
                    color = Color(0xFF1565C0),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                )
            }

            if (discoveredDevices.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Scanning for nearby RasFocus devices...\nMake sure both devices are on the same Wi-Fi or Hotspot.",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                items(discoveredDevices) { device ->
                    ListItem(
                        headlineContent = { Text(device.name) },
                        supportingContent = { Text("${device.ip}:${device.port}") },
                        leadingContent = { 
                            Icon(
                                Icons.Default.Wifi, 
                                contentDescription = null, 
                                tint = Color(0xFF388E3C)
                            ) 
                        },
                        trailingContent = {
                            Button(
                                onClick = {
                                    p2pConnection.connectToDevice(device.ip, device.port, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO))
                                    onNavigate(NavState.P2PChat(device.name, device.ip, device.port))
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF388E3C))
                            ) {
                                Text("Connect")
                            }
                        },
                        modifier = Modifier.clickable {
                            p2pConnection.connectToDevice(device.ip, device.port, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO))
                            onNavigate(NavState.P2PChat(device.name, device.ip, device.port))
                        }
                    )
                    Divider()
                }
            }

            // Saved Servers Section
            if (RemoteStore.servers.isNotEmpty()) {
                item {
                    Text(
                        text = "Saved Servers (FTP / SMB)",
                        color = Color(0xFF1565C0),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                    )
                }
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

    if (showManualIpDialog) {
        var inputIp by remember { mutableStateOf("") }
        var inputPort by remember { mutableStateOf("50000") }

        AlertDialog(
            onDismissRequest = { showManualIpDialog = false },
            title = { Text("Connect to Device by IP") },
            text = {
                Column {
                    Text("Enter the IP Address shown on the target device:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputIp,
                        onValueChange = { inputIp = it },
                        label = { Text("IP Address (e.g. 192.168.1.100)") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputPort,
                        onValueChange = { inputPort = it },
                        label = { Text("Port (Default: 50000-60000)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val port = inputPort.toIntOrNull() ?: 50000
                    if (inputIp.isNotBlank()) {
                        p2pConnection.connectToDevice(inputIp.trim(), port, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO))
                        onNavigate(NavState.P2PChat("Device ($inputIp)", inputIp.trim(), port))
                        showManualIpDialog = false
                    }
                }) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualIpDialog = false }) { Text("Cancel") }
            }
        )
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
                    ftpClient.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE)
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
                                    val nextPath = if (initialPath.endsWith("/")) initialPath + file.name else "$initialPath/${file.name}"
                                    android.widget.Toast.makeText(context, "Downloading ${file.name}...", android.widget.Toast.LENGTH_SHORT).show()
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val downloadsDir = java.io.File(LocalFileManager.mainStoragePath, "Download")
                                            if (!downloadsDir.exists()) downloadsDir.mkdirs()
                                            val localFile = java.io.File(downloadsDir, file.name)
                                            val outputStream = java.io.FileOutputStream(localFile)
                                            val downloadSuccess = ftpClient.retrieveFile(nextPath, outputStream)
                                            outputStream.close()
                                            withContext(Dispatchers.Main) {
                                                if (downloadSuccess) {
                                                    android.widget.Toast.makeText(context, "Downloaded to Downloads folder", android.widget.Toast.LENGTH_SHORT).show()
                                                } else {
                                                    android.widget.Toast.makeText(context, "Failed to download file", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                            withContext(Dispatchers.Main) {
                                                android.widget.Toast.makeText(context, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
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

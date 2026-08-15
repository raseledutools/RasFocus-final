package com.rasel.RasFocus.filemanager

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File
import java.util.Locale

object FtpServerManager {
    private var server: FtpServer? = null
    var isRunning = false
        private set

    var port = 2221
    var username = "rasfocus"
    var password = ""

    fun startServer(context: Context, onResult: (Boolean, String?) -> Unit) {
        if (isRunning) {
            onResult(true, null)
            return
        }
        try {
            val serverFactory = FtpServerFactory()
            val listenerFactory = ListenerFactory()
            listenerFactory.port = port
            serverFactory.addListener("default", listenerFactory.createListener())

            val userManagerFactory = PropertiesUserManagerFactory()
            // In-memory user manager avoids needing a properties file on disk
            val userManager: UserManager = userManagerFactory.createUserManager()
            val user = BaseUser()
            user.name = username
            user.password = password
            user.homeDirectory = LocalFileManager.mainStoragePath
            
            val authorities = ArrayList<Authority>()
            authorities.add(WritePermission())
            user.authorities = authorities

            userManager.save(user)
            serverFactory.userManager = userManager

            server = serverFactory.createServer()
            server?.start()
            isRunning = true
            onResult(true, null)
        } catch (e: Exception) {
            e.printStackTrace()
            isRunning = false
            onResult(false, e.message)
        }
    }

    fun stopServer() {
        if (!isRunning) return
        try {
            server?.stop()
            server = null
            isRunning = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getLocalIpAddress(context: Context): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipAddress = wifiInfo.ipAddress
            if (ipAddress == 0) return null
            String.format(
                Locale.US,
                "%d.%d.%d.%d",
                ipAddress and 0xff,
                ipAddress shr 8 and 0xff,
                ipAddress shr 16 and 0xff,
                ipAddress shr 24 and 0xff
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FtpServerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var isRunning by remember { mutableStateOf(FtpServerManager.isRunning) }
    var ipAddress by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(isRunning) {
        ipAddress = FtpServerManager.getLocalIpAddress(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Access from PC", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF00796B))
            )
        },
        containerColor = Color(0xFFF2F2F7)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))
            
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(if (isRunning) Color(0xFF4CAF50).copy(alpha = 0.2f) else Color.LightGray),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Default.Wifi else Icons.Default.WifiOff,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = if (isRunning) Color(0xFF4CAF50) else Color.Gray
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = if (isRunning) "Server is running" else "Server is stopped",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = if (isRunning) Color(0xFF4CAF50) else Color.DarkGray
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (isRunning) {
                if (ipAddress != null) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Enter this address in your PC's browser or File Explorer:", color = Color.Gray, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "ftp://$ipAddress:${FtpServerManager.port}",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1E1E1E)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Username:", color = Color.Gray)
                                Text(FtpServerManager.username, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Password:", color = Color.Gray)
                                Text(if (FtpServerManager.password.isEmpty()) "None" else "••••", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                } else {
                    Text("Please connect to Wi-Fi to use this feature.", color = Color.Red)
                }
            } else {
                Text(
                    text = "Start the FTP server to browse and transfer files from your computer using a Wi-Fi connection.",
                    color = Color.Gray,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    if (isRunning) {
                        FtpServerManager.stopServer()
                        isRunning = false
                    } else {
                        if (FtpServerManager.getLocalIpAddress(context) == null) {
                            android.widget.Toast.makeText(context, "Please connect to Wi-Fi first", android.widget.Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                FtpServerManager.startServer(context) { success, error ->
                                    scope.launch {
                                        if (success) {
                                            isRunning = true
                                        } else {
                                            android.widget.Toast.makeText(context, "Failed to start: $error", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (isRunning) Color.Red else Color(0xFF1565C0))
            ) {
                Text(text = if (isRunning) "STOP SERVER" else "START SERVER", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

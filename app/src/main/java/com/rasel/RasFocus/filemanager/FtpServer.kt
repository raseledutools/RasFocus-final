package com.rasel.RasFocus.filemanager

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

object FtpServerManager {
    private var server: FtpServer? = null
    var isRunning = false
        private set

    var port = 1111
    var username = "rasfocus"
    var password = "1111"

    /** Last known IP — UI এ সবসময় দেখানোর জন্য cache করা হয় */
    var lastKnownIp: String? = null
        private set

    fun startServer(context: Context, onResult: (Boolean, String?) -> Unit) {
        if (isRunning) {
            // Already running — just refresh IP cache and return success
            lastKnownIp = getLocalIpAddress(context) ?: lastKnownIp
            onResult(true, null)
            return
        }
        try {
            val serverFactory = FtpServerFactory()
            val listenerFactory = ListenerFactory()
            listenerFactory.port = port
            serverFactory.addListener("default", listenerFactory.createListener())

            val userManagerFactory = PropertiesUserManagerFactory()
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
            // Cache IP immediately after start so UI always has a link to show
            lastKnownIp = getLocalIpAddress(context)
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
            lastKnownIp = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * WiFi connected OR Hotspot host — দুটো ক্ষেত্রেই IP দেয়।
     *
     * পুরনো পদ্ধতি (WifiManager.connectionInfo.ipAddress) শুধু
     * WiFi client হলে কাজ করত — hotspot host হলে 0 রিটার্ন করত।
     *
     * নতুন পদ্ধতি: সব active network interface scan করে প্রথম
     * non-loopback IPv4 address নেয়, তাই WiFi + Hotspot উভয়েই কাজ করে।
     */
    fun getLocalIpAddress(context: Context): String? {
        return try {
            // Method 1: NetworkInterface scan — WiFi client & Hotspot host দুটোতেই কাজ করে
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in ifaces.iterator()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses.iterator()) {
                    if (addr.isLoopbackAddress) continue
                    if (addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        // 169.254.x.x = APIPA/link-local, skip করো
                        if (ip.startsWith("169.254")) continue
                        return ip
                    }
                }
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** WiFi connected অথবা Hotspot host — যেকোনো একটা থাকলে true */
    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val nw = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(nw) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            info != null && info.isConnected &&
            (info.type == ConnectivityManager.TYPE_WIFI || info.type == ConnectivityManager.TYPE_ETHERNET)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FtpServerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var isRunning by remember { mutableStateOf(FtpServerManager.isRunning) }
    // Use cached IP from manager so it's stable across recompositions
    var ipAddress by remember { mutableStateOf(FtpServerManager.lastKnownIp) }
    val scope = rememberCoroutineScope()

    // Refresh IP whenever running state changes (e.g. after start)
    LaunchedEffect(isRunning) {
        if (isRunning) {
            ipAddress = withContext(Dispatchers.IO) {
                FtpServerManager.lastKnownIp ?: FtpServerManager.getLocalIpAddress(context)
            }
        } else {
            ipAddress = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Access from PC", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E1E))
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
                            Text(
                                "Enter this address in your PC's browser or File Explorer:",
                                color = Color.Gray,
                                fontSize = 14.sp
                            )
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
                                Text("1111", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                } else {
                    // IP পাওয়া যায়নি — hotspot বা WiFi কোনোটাই active না
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "⚠️ IP address পাওয়া যাচ্ছে না",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE65100)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Mobile hotspot চালু করুন এবং PC-তে connect করুন, অথবা WiFi-তে connect করুন।",
                                color = Color(0xFF795548),
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = "Mobile hotspot বা WiFi দিয়ে PC connect করুন, তারপর FTP server start করুন।",
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
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                FtpServerManager.startServer(context) { success, error ->
                                    scope.launch {
                                        if (success) {
                                            isRunning = true
                                            // Immediately show the stable IP link
                                            ipAddress = FtpServerManager.lastKnownIp
                                                ?: withContext(Dispatchers.IO) {
                                                    FtpServerManager.getLocalIpAddress(context)
                                                }
                                            if (ipAddress == null) {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Server চালু হয়েছে। Mobile hotspot বা WiFi চালু করুন যাতে IP দেখা যায়।",
                                                    android.widget.Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        } else {
                                            android.widget.Toast.makeText(
                                                context,
                                                "Failed to start: $error",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
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
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) Color.Red else Color(0xFF1565C0)
                )
            ) {
                Text(
                    text = if (isRunning) "STOP SERVER" else "START SERVER",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}


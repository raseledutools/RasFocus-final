package com.rasel.RasFocus.selfcontrol.rasgram

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.*
import java.net.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

// ── Data class: discovered LAN user ──────────────────────────────────────────
data class LanDiscoveredUser(
    val mobile: String,
    val name: String,
    val ip: String,
    val port: Int = LanChatManager.TCP_PORT
)

/**
 * LanChatManager — WiFi LAN peer-to-peer chat
 *
 * Architecture:
 *  • UDP broadcast (port 5555) for peer discovery (beacon every 3 s)
 *  • TCP server (port 5556) for message / file / voice transfer
 *
 * Usage:
 *   val mgr = LanChatManager.getInstance(context)
 *   mgr.start(myMobile, myName)          // call on login / LAN toggle ON
 *   mgr.stop()                           // call on LAN toggle OFF / logout
 *   mgr.discoveredUsers                  // StateFlow<List<LanDiscoveredUser>>
 *   mgr.sendText(peer, text, chatId)
 *   mgr.sendFile(peer, file, mimeType, chatId)
 *   mgr.sendVoice(peer, file, durationSecs, chatId)
 */
class LanChatManager private constructor(private val context: Context) {

    companion object {
        const val UDP_PORT = 5555
        const val TCP_PORT = 5556
        private const val BEACON_INTERVAL_MS = 3_000L
        private const val PEER_TIMEOUT_MS = 10_000L
        private const val TAG = "LanChatManager"

        @Volatile
        private var INSTANCE: LanChatManager? = null

        fun getInstance(context: Context): LanChatManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: LanChatManager(context.applicationContext).also { INSTANCE = it }
            }

        /** Returns the device's current WiFi/LAN IP, or "Unknown" */
        fun getLocalIp(context: Context): String {
            return try {
                val wifi = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                val ip = wifi.connectionInfo.ipAddress
                if (ip == 0) {
                    // Fallback: enumerate network interfaces
                    NetworkInterface.getNetworkInterfaces()?.toList()
                        ?.flatMap { it.inetAddresses.toList() }
                        ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                        ?.hostAddress ?: "Unknown"
                } else {
                    String.format(
                        "%d.%d.%d.%d",
                        ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "getLocalIp: ${e.message}")
                "Unknown"
            }
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private val _discoveredUsers = MutableStateFlow<List<LanDiscoveredUser>>(emptyList())
    val discoveredUsers: StateFlow<List<LanDiscoveredUser>> = _discoveredUsers.asStateFlow()

    private val peerLastSeen = ConcurrentHashMap<String, Long>()   // mobile → timestamp
    private val peerMap = ConcurrentHashMap<String, LanDiscoveredUser>()

    private var myMobile = ""
    private var myName = ""
    private var localIp = "0.0.0.0"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var udpSocket: DatagramSocket? = null
    private var tcpServer: ServerSocket? = null
    private var running = false

    // ── Start / Stop ─────────────────────────────────────────────────────────
    fun start(mobile: String, name: String) {
        if (running) return
        running = true
        myMobile = mobile
        myName = name
        localIp = getLocalIp(context)
        Log.i(TAG, "Starting LAN mode — IP: $localIp")

        scope.launch { runUdpBeacon() }
        scope.launch { runUdpListener() }
        scope.launch { runTcpServer() }
        scope.launch { runPeerPruner() }
    }

    fun stop() {
        running = false
        scope.coroutineContext.cancelChildren()
        try { udpSocket?.close() } catch (_: Exception) {}
        try { tcpServer?.close() } catch (_: Exception) {}
        udpSocket = null
        tcpServer = null
        peerMap.clear()
        peerLastSeen.clear()
        _discoveredUsers.value = emptyList()
        Log.i(TAG, "LAN mode stopped")
    }

    // ── UDP Beacon (broadcast presence) ──────────────────────────────────────
    private suspend fun runUdpBeacon() = withContext(Dispatchers.IO) {
        while (running) {
            try {
                val sock = DatagramSocket()
                sock.broadcast = true
                val payload = JSONObject().apply {
                    put("type", "beacon")
                    put("mobile", myMobile)
                    put("name", myName)
                    put("ip", localIp)
                    put("port", TCP_PORT)
                }.toString().toByteArray()
                val packet = DatagramPacket(
                    payload, payload.size,
                    InetAddress.getByName("255.255.255.255"), UDP_PORT
                )
                sock.send(packet)
                sock.close()
            } catch (e: Exception) {
                Log.w(TAG, "Beacon error: ${e.message}")
            }
            delay(BEACON_INTERVAL_MS)
        }
    }

    // ── UDP Listener (receive beacons from peers) ─────────────────────────────
    private suspend fun runUdpListener() = withContext(Dispatchers.IO) {
        try {
            udpSocket = DatagramSocket(UDP_PORT).also { it.broadcast = true }
            val buf = ByteArray(1024)
            while (running) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    udpSocket!!.receive(packet)
                    val json = JSONObject(String(packet.data, 0, packet.length))
                    if (json.optString("type") != "beacon") continue
                    val mobile = json.optString("mobile")
                    if (mobile.isEmpty() || mobile == myMobile) continue

                    val peer = LanDiscoveredUser(
                        mobile = mobile,
                        name = json.optString("name", mobile),
                        ip = json.optString("ip", packet.address.hostAddress ?: ""),
                        port = json.optInt("port", TCP_PORT)
                    )
                    peerLastSeen[mobile] = System.currentTimeMillis()
                    if (peerMap[mobile] != peer) {
                        peerMap[mobile] = peer
                        _discoveredUsers.value = peerMap.values.toList()
                    }
                } catch (e: SocketTimeoutException) { /* ignore */ }
                  catch (e: Exception) {
                    if (running) Log.w(TAG, "UDP recv: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "UDP listener failed: ${e.message}")
        }
    }

    // ── Peer pruner (remove stale peers) ─────────────────────────────────────
    private suspend fun runPeerPruner() = withContext(Dispatchers.IO) {
        while (running) {
            delay(5_000L)
            val cutoff = System.currentTimeMillis() - PEER_TIMEOUT_MS
            val stale = peerLastSeen.entries.filter { it.value < cutoff }.map { it.key }
            if (stale.isNotEmpty()) {
                stale.forEach { peerMap.remove(it); peerLastSeen.remove(it) }
                _discoveredUsers.value = peerMap.values.toList()
            }
        }
    }

    // ── TCP Server (receive messages / files from peers) ──────────────────────
    private suspend fun runTcpServer() = withContext(Dispatchers.IO) {
        try {
            tcpServer = ServerSocket(TCP_PORT)
            Log.i(TAG, "TCP server listening on $TCP_PORT")
            while (running) {
                val client = try { tcpServer!!.accept() } catch (e: Exception) { break }
                scope.launch { handleTcpClient(client) }
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "TCP server error: ${e.message}")
        }
    }

    private suspend fun handleTcpClient(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val dis = DataInputStream(socket.getInputStream())
            val headerLen = dis.readInt()
            val headerBytes = ByteArray(headerLen)
            dis.readFully(headerBytes)
            val header = JSONObject(String(headerBytes))

            val type = header.optString("type")
            val chatId = header.optString("chatId")
            val senderMobile = header.optString("senderMobile")
            val senderName = header.optString("senderName")

            when (type) {
                "text" -> {
                    val text = header.optString("text")
                    saveMessageToFirestore(chatId, senderMobile, senderName, text, null, null, null)
                }
                "file", "voice" -> {
                    val mimeType = header.optString("mimeType", "application/octet-stream")
                    val fileName = header.optString("fileName", "file_${System.currentTimeMillis()}")
                    val durationSecs = header.optLong("durationSecs", 0L)
                    val fileSize = dis.readLong()

                    // Save to cache
                    val outFile = File(context.cacheDir, "lan_recv_$fileName")
                    val fos = FileOutputStream(outFile)
                    val buf = ByteArray(8192)
                    var remaining = fileSize
                    while (remaining > 0) {
                        val read = dis.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (read == -1) break
                        fos.write(buf, 0, read)
                        remaining -= read
                    }
                    fos.close()

                    // Save to Firebase with local file path as URL placeholder
                    // FIX: Changed from "lan://" to "file://" so that standard Android
                    // components like Coil (AsyncImage) and MediaPlayer can read it
                    val localUrl = "file://${outFile.absolutePath}"
                    saveMessageToFirestore(
                        chatId, senderMobile, senderName,
                        if (type == "voice") "" else "",
                        localUrl, fileName, mimeType,
                        if (type == "voice") durationSecs else null
                    )
                }
                else -> {
                    // Unknown type — ignore
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleTcpClient: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // ── Send: Text ────────────────────────────────────────────────────────────
    suspend fun sendText(peer: LanDiscoveredUser, text: String, chatId: String) =
        withContext(Dispatchers.IO) {
            try {
                val header = JSONObject().apply {
                    put("type", "text")
                    put("chatId", chatId)
                    put("senderMobile", myMobile)
                    put("senderName", myName)
                    put("text", text)
                }
                sendTcpPacket(peer, header, null)
                // Also persist locally (sender side)
                saveMessageToFirestore(chatId, myMobile, myName, text, null, null, null)
            } catch (e: Exception) {
                Log.e(TAG, "sendText: ${e.message}")
            }
        }

    // ── Send: File ────────────────────────────────────────────────────────────
    fun sendFile(peer: LanDiscoveredUser, file: File, mimeType: String, chatId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val header = JSONObject().apply {
                    put("type", "file")
                    put("chatId", chatId)
                    put("senderMobile", myMobile)
                    put("senderName", myName)
                    put("mimeType", mimeType)
                    put("fileName", file.name)
                }
                sendTcpPacket(peer, header, file)
                // FIX: Also persist locally (sender side) so the UI updates
                val localUrl = "file://${file.absolutePath}"
                saveMessageToFirestore(chatId, myMobile, myName, "", localUrl, file.name, mimeType, null)
            } catch (e: Exception) {
                Log.e(TAG, "sendFile: ${e.message}")
            }
        }
    }

    // ── Send: Voice ───────────────────────────────────────────────────────────
    fun sendVoice(peer: LanDiscoveredUser, file: File, durationSecs: Long, chatId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val header = JSONObject().apply {
                    put("type", "voice")
                    put("chatId", chatId)
                    put("senderMobile", myMobile)
                    put("senderName", myName)
                    put("mimeType", "audio/mp4")
                    put("fileName", file.name)
                    put("durationSecs", durationSecs)
                }
                sendTcpPacket(peer, header, file)
                // FIX: Also persist locally (sender side) so the UI updates
                val localUrl = "file://${file.absolutePath}"
                saveMessageToFirestore(chatId, myMobile, myName, "", localUrl, file.name, "audio/mp4", durationSecs)
            } catch (e: Exception) {
                Log.e(TAG, "sendVoice: ${e.message}")
            }
        }
    }

    // ── TCP packet sender ─────────────────────────────────────────────────────
    private fun sendTcpPacket(peer: LanDiscoveredUser, header: JSONObject, file: File?) {
        Socket().use { sock ->
            sock.connect(InetSocketAddress(peer.ip, peer.port), 5_000)
            val dos = DataOutputStream(sock.getOutputStream())
            val headerBytes = header.toString().toByteArray()
            dos.writeInt(headerBytes.size)
            dos.write(headerBytes)
            if (file != null && file.exists()) {
                dos.writeLong(file.length())
                FileInputStream(file).use { fis ->
                    val buf = ByteArray(8192)
                    var read: Int
                    while (fis.read(buf).also { read = it } != -1) {
                        dos.write(buf, 0, read)
                    }
                }
            }
            dos.flush()
        }
    }

    // ── Save received message to Firestore ────────────────────────────────────
    // (so existing message list / UI shows LAN messages too)
    private fun saveMessageToFirestore(
        chatId: String,
        senderMobile: String,
        senderName: String,
        text: String,
        fileUrl: String?,
        fileName: String?,
        mimeType: String?,
        durationSecs: Long? = null
    ) {
        try {
            val db = FirebaseFirestore.getInstance()
            // FIX: Incorrect Firestore path. RasGram uses pvt_msg_$chatId for private chats.
            val msgId = db.collection("pvt_msg_$chatId").document().id
            val timestamp = System.currentTimeMillis()
            val dateStr = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(timestamp))

            val data = hashMapOf<String, Any?>(
                "id" to msgId,
                "senderMobile" to senderMobile,
                "senderName" to senderName,
                "text" to text,
                "timestamp" to timestamp,
                "date" to dateStr,
                "read" to false,
                "deliveredViaLan" to true
            )
            if (fileUrl != null) data["fileUrl"] = fileUrl
            if (fileName != null) data["fileName"] = fileName
            if (mimeType != null) data["fileType"] = mimeType
            if (durationSecs != null && durationSecs > 0) data["durationSecs"] = durationSecs

            // Because Firestore has local caching enabled by default in Android,
            // this set() call works instantly offline, and the UI's SnapshotListener
            // will pick it up and show it in the chat screen immediately!
            db.collection("pvt_msg_$chatId").document(msgId)
                .set(data)
                .addOnFailureListener { e ->
                    Log.w(TAG, "Firestore save failed (LAN msg): ${e.message}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "saveMessageToFirestore: ${e.message}")
        }
    }
}

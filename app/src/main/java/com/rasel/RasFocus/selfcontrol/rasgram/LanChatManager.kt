package com.rasel.RasFocus.selfcontrol.rasgram

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
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

// ── timeString helper (same WhatsApp format as sendMessage() in RasGramModule) ─
private fun lanTimeString(): String {
    val now = System.currentTimeMillis()
    val todayCal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
    }
    val yesterdayCal = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
    }
    return when {
        now >= todayCal.timeInMillis ->
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(now))
        now >= yesterdayCal.timeInMillis -> "Yesterday"
        else -> SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(Date(now))
    }
}

// ── Data class: discovered LAN user ──────────────────────────────────────────
data class LanDiscoveredUser(
    val mobile: String,
    val name: String,
    val ip: String,
    val port: Int = LanChatManager.TCP_PORT
)

/**
 * LanChatManager — 100% offline WiFi/Hotspot LAN peer-to-peer chat
 *
 * NO Firebase / Firestore / internet required.
 * All messages stored in local Room DB only.
 *
 * Architecture:
 *  • UDP broadcast (port 5555) for peer discovery (beacon every 3 s)
 *  • TCP server (port 5556) for message / file / voice transfer
 *  • Room DB for persistent message storage (both sender & receiver)
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
        private const val PEER_TIMEOUT_MS    = 10_000L
        private const val TAG = "LanChatManager"

        @Volatile
        private var INSTANCE: LanChatManager? = null

        fun getInstance(context: Context): LanChatManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: LanChatManager(context.applicationContext).also { INSTANCE = it }
            }

        /** Returns the device's current WiFi/hotspot IP, or "Unknown" */
        fun getLocalIp(context: Context): String {
            return try {
                val wifi = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                val ip = wifi.connectionInfo.ipAddress
                if (ip == 0) {
                    // Fallback: enumerate network interfaces (works for hotspot too)
                    NetworkInterface.getNetworkInterfaces()?.toList()
                        ?.flatMap { it.inetAddresses.toList() }
                        ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                        ?.hostAddress ?: "Unknown"
                } else {
                    String.format(
                        "%d.%d.%d.%d",
                        ip and 0xff, ip shr 8 and 0xff,
                        ip shr 16 and 0xff, ip shr 24 and 0xff
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
    private val peerMap      = ConcurrentHashMap<String, LanDiscoveredUser>()

    private var myMobile = ""
    private var myName   = ""
    private var localIp  = "0.0.0.0"

    private val scope     = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var udpSocket: DatagramSocket? = null
    private var tcpServer: ServerSocket?   = null
    private var running = false

    // ── Room DB (no Firebase) ─────────────────────────────────────────────────
    private val repo: RasGramRepository by lazy { RasGramRepository.getInstance(context) }

    // ── Start / Stop ─────────────────────────────────────────────────────────
    fun start(mobile: String, name: String) {
        if (running) return
        running  = true
        myMobile = mobile
        myName   = name
        localIp  = getLocalIp(context)
        Log.i(TAG, "LAN mode ON — IP: $localIp")

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
        Log.i(TAG, "LAN mode OFF")
    }

    // ── UDP Beacon — broadcast "I'm here" every 3 s ───────────────────────────
    private suspend fun runUdpBeacon() = withContext(Dispatchers.IO) {
        while (running) {
            try {
                DatagramSocket().use { sock ->
                    sock.broadcast = true
                    val payload = JSONObject().apply {
                        put("type",   "beacon")
                        put("mobile", myMobile)
                        put("name",   myName)
                        put("ip",     localIp)
                        put("port",   TCP_PORT)
                    }.toString().toByteArray()
                    sock.send(
                        DatagramPacket(
                            payload, payload.size,
                            InetAddress.getByName("255.255.255.255"), UDP_PORT
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Beacon error: ${e.message}")
            }
            delay(BEACON_INTERVAL_MS)
        }
    }

    // ── UDP Listener — receive peer beacons ───────────────────────────────────
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
                        name   = json.optString("name", mobile),
                        ip     = json.optString("ip", packet.address.hostAddress ?: ""),
                        port   = json.optInt("port", TCP_PORT)
                    )
                    peerLastSeen[mobile] = System.currentTimeMillis()
                    if (peerMap[mobile] != peer) {
                        peerMap[mobile] = peer
                        _discoveredUsers.value = peerMap.values.toList()
                    }
                } catch (_: SocketTimeoutException) {}
                  catch (e: Exception) {
                    if (running) Log.w(TAG, "UDP recv: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "UDP listener failed: ${e.message}")
        }
    }

    // ── Peer pruner — remove stale peers after PEER_TIMEOUT_MS ───────────────
    private suspend fun runPeerPruner() = withContext(Dispatchers.IO) {
        while (running) {
            delay(5_000L)
            val cutoff = System.currentTimeMillis() - PEER_TIMEOUT_MS
            val stale  = peerLastSeen.entries.filter { it.value < cutoff }.map { it.key }
            if (stale.isNotEmpty()) {
                stale.forEach { peerMap.remove(it); peerLastSeen.remove(it) }
                _discoveredUsers.value = peerMap.values.toList()
            }
        }
    }

    // ── TCP Server — receive text / file / voice from peers ───────────────────
    private suspend fun runTcpServer() = withContext(Dispatchers.IO) {
        try {
            tcpServer = ServerSocket(TCP_PORT)
            Log.i(TAG, "TCP server ready on :$TCP_PORT")
            while (running) {
                val client = try { tcpServer!!.accept() } catch (_: Exception) { break }
                scope.launch { handleTcpClient(client) }
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "TCP server error: ${e.message}")
        }
    }

    private suspend fun handleTcpClient(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val dis         = DataInputStream(socket.getInputStream())
            val headerLen   = dis.readInt()
            val headerBytes = ByteArray(headerLen)
            dis.readFully(headerBytes)
            val header = JSONObject(String(headerBytes))

            val type         = header.optString("type")
            val chatId       = header.optString("chatId")
            val senderMobile = header.optString("senderMobile")
            val senderName   = header.optString("senderName")
            // receiverMobile is myMobile since this is an incoming packet
            val receiverMobile = myMobile

            when (type) {
                "text" -> {
                    val text = header.optString("text")
                    saveToRoom(
                        chatId        = chatId,
                        senderMobile  = senderMobile,
                        receiverMobile= receiverMobile,
                        senderName    = senderName,
                        text          = text
                    )
                }

                "file", "voice" -> {
                    val mimeType    = header.optString("mimeType", "application/octet-stream")
                    val fileName    = header.optString("fileName", "lan_${System.currentTimeMillis()}")
                    val durationSec = header.optLong("durationSecs", 0L)
                    val fileSize    = dis.readLong()

                    // Save file bytes to app's files dir (persistent across reboots)
                    val lanDir = File(context.getExternalFilesDir(null), "RasGram/LAN").also { it.mkdirs() }
                    val outFile = File(lanDir, "${System.currentTimeMillis()}_$fileName")
                    FileOutputStream(outFile).use { fos ->
                        val buf  = ByteArray(8192)
                        var rem  = fileSize
                        while (rem > 0) {
                            val read = dis.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
                            if (read == -1) break
                            fos.write(buf, 0, read)
                            rem -= read
                        }
                    }

                    // Use a local:// URI so ChatArea knows this is a local file
                    val localUrl = "local://${outFile.absolutePath}"
                    saveToRoom(
                        chatId         = chatId,
                        senderMobile   = senderMobile,
                        receiverMobile = receiverMobile,
                        senderName     = senderName,
                        text           = "",
                        fileUrl        = localUrl,
                        fileName       = fileName,
                        fileType       = mimeType,
                        fileSizeBytes  = outFile.length(),
                        duration       = durationSec.toInt()
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleTcpClient: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // ── Send: Text ────────────────────────────────────────────────────────────
    // ChatArea already saves sender's message to Room optimistically.
    // We only send the TCP packet to the peer here.
    suspend fun sendText(peer: LanDiscoveredUser, text: String, chatId: String) =
        withContext(Dispatchers.IO) {
            try {
                val header = JSONObject().apply {
                    put("type",          "text")
                    put("chatId",        chatId)
                    put("senderMobile",  myMobile)
                    put("senderName",    myName)
                    put("text",          text)
                }
                sendTcpPacket(peer, header, null)
            } catch (e: Exception) {
                Log.e(TAG, "sendText: ${e.message}")
            }
        }

    // ── Send: File ────────────────────────────────────────────────────────────
    // Saves sender's own copy to Room, then TCP-sends file bytes to peer.
    fun sendFile(peer: LanDiscoveredUser, file: File, mimeType: String, chatId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val header = JSONObject().apply {
                    put("type",         "file")
                    put("chatId",       chatId)
                    put("senderMobile", myMobile)
                    put("senderName",   myName)
                    put("mimeType",     mimeType)
                    put("fileName",     file.name)
                }
                sendTcpPacket(peer, header, file)
                // Sender side: save our copy to Room
                saveToRoom(
                    chatId         = chatId,
                    senderMobile   = myMobile,
                    receiverMobile = peer.mobile,
                    senderName     = myName,
                    text           = "",
                    fileUrl        = "local://${file.absolutePath}",
                    fileName       = file.name,
                    fileType       = mimeType,
                    fileSizeBytes  = file.length()
                )
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
                    put("type",         "voice")
                    put("chatId",       chatId)
                    put("senderMobile", myMobile)
                    put("senderName",   myName)
                    put("mimeType",     "audio/mp4")
                    put("fileName",     file.name)
                    put("durationSecs", durationSecs)
                }
                sendTcpPacket(peer, header, file)
                // Sender side: save our copy to Room
                saveToRoom(
                    chatId         = chatId,
                    senderMobile   = myMobile,
                    receiverMobile = peer.mobile,
                    senderName     = myName,
                    text           = "",
                    fileUrl        = "local://${file.absolutePath}",
                    fileName       = file.name,
                    fileType       = "audio/mp4",
                    fileSizeBytes  = file.length(),
                    duration       = durationSecs.toInt()
                )
            } catch (e: Exception) {
                Log.e(TAG, "sendVoice: ${e.message}")
            }
        }
    }

    // ── TCP packet sender ─────────────────────────────────────────────────────
    private fun sendTcpPacket(peer: LanDiscoveredUser, header: JSONObject, file: File?) {
        Socket().use { sock ->
            sock.connect(InetSocketAddress(peer.ip, peer.port), 5_000)
            val dos         = DataOutputStream(sock.getOutputStream())
            val headerBytes = header.toString().toByteArray()
            dos.writeInt(headerBytes.size)
            dos.write(headerBytes)
            if (file != null && file.exists()) {
                dos.writeLong(file.length())
                FileInputStream(file).use { fis ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (fis.read(buf).also { n = it } != -1) dos.write(buf, 0, n)
                }
            }
            dos.flush()
        }
    }

    // ── Save message to Room DB (100% offline — no Firebase) ─────────────────
    private fun saveToRoom(
        chatId: String,
        senderMobile: String,
        receiverMobile: String,
        senderName: String,
        text: String,
        fileUrl: String?   = null,
        fileName: String?  = null,
        fileType: String?  = null,
        fileSizeBytes: Long = 0L,
        duration: Int      = 0
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val now       = System.currentTimeMillis()
                val timeStr   = lanTimeString()
                val msgId     = "lan_${now}_${senderMobile.takeLast(4)}_${(0..9999).random()}"

                repo.messageDao.upsertMessage(
                    CachedMessage(
                        id             = msgId,
                        chatId         = chatId,
                        text           = text,
                        senderMobile   = senderMobile,
                        receiverMobile = receiverMobile,
                        timestamp      = now,
                        timeString     = timeStr,
                        fileUrl        = fileUrl,
                        fileName       = fileName,
                        fileType       = fileType,
                        fileSizeBytes  = fileSizeBytes,
                        duration       = duration,
                        read           = (senderMobile == myMobile), // own messages are "read"
                        delivered      = true,
                        isPending      = false
                    )
                )

                // Update chat preview (last message shown in contact list)
                val previewText = when {
                    text.isNotEmpty()                          -> text
                    fileType?.startsWith("audio/") == true     -> "🎵 Voice message"
                    fileType?.startsWith("image/") == true     -> "📷 Image"
                    fileType?.startsWith("video/") == true     -> "📹 Video"
                    fileName != null                           -> "📎 $fileName"
                    else                                       -> "File"
                }
                val contactMobile = if (senderMobile == myMobile) receiverMobile else senderMobile
                val existing = repo.chatPreviewDao.getPreview(contactMobile)
                repo.chatPreviewDao.upsertPreview(
                    CachedChatPreview(
                        contactMobile      = contactMobile,
                        contactName        = if (senderMobile == myMobile) "" else senderName,
                        contactAvatarUrl   = existing?.contactAvatarUrl ?: "",
                        lastMessageText    = previewText,
                        lastMessageSender  = senderMobile,
                        lastTimestamp      = now,
                        lastTimeString     = timeStr,
                        lastFileType       = fileType,
                        lastIsCallLog      = false,
                        // Unread: increment only for received messages (not our own)
                        unreadCount        = if (senderMobile != myMobile)
                                                (existing?.unreadCount ?: 0) + 1
                                            else
                                                existing?.unreadCount ?: 0
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "saveToRoom: ${e.message}")
            }
        }
    }
}

package com.rasel.RasFocus.remotedesktop

/**
 * PcScreenReceiverView — Updated with Relay + Firebase lookup
 *
 * Discovery priority:
 *   1. Firebase lookup: Firestore "rd_sessions/<code>" → PC's local IP
 *   2. Direct LAN WS:  ws://pc-ip:9224  (fast, low latency)
 *   3. Relay fallback: wss://relay.rasfocus.com/relay/<code>
 *      (used when PC and phone are on different networks)
 *
 * Original UDP broadcast discovery is kept as a last-resort fallback
 * for cases where Firebase is unavailable.
 *
 * New public API:
 *   connectByCode(code)                     — UDP only (legacy)
 *   connectByCode(code, ip, port)           — direct (from Firebase lookup)
 *   connectViaRelay(code)                   — relay server
 */

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.TimeUnit

class PcScreenReceiverView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val TAG      = "PcScreenReceiver"
    private val UDP_PORT = 9225
    private val WS_PORT  = 9224
    private val RELAY_URL = "wss://relay.rasfocus.com"

    // ── WS client (OkHttp — supports both ws:// and wss://) ───────
    private var wsClient:  okhttp3.WebSocket? = null
    private var okHttp:    OkHttpClient? = null
    private var udpSocket: DatagramSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // H264 decoder
    private var decoder:      MediaCodec? = null
    private var decoderReady  = false
    private var surfaceReady  = false

    // ── Callbacks ──────────────────────────────────────────────────
    var onDiscovering:  (() -> Unit)?                        = null
    var onConnected:    ((width: Int, height: Int) -> Unit)? = null
    var onDisconnected: (() -> Unit)?                        = null
    var onError:        ((String) -> Unit)?                  = null
    var onAuthFailed:   (() -> Unit)?                        = null

    init { holder.addCallback(this) }

    override fun surfaceCreated(h: SurfaceHolder)  { surfaceReady = true }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) {
        surfaceReady = false
        releaseDecoder()
    }

    // ── [NEW] Connect using IP from Firebase lookup (direct) ───────
    fun connectByCode(authCode: String, pcIp: String, pcPort: Int) {
        disconnect()
        onDiscovering?.invoke()
        Log.d(TAG, "Direct connect: ws://$pcIp:$pcPort  code=$authCode")
        scope.launch {
            val ok = tryConnectWs("ws://$pcIp:$pcPort", authCode, isRelay = false)
            if (!ok) {
                Log.w(TAG, "Direct connect failed → trying relay")
                withContext(Dispatchers.Main) {
                    onError?.invoke("LAN connect failed — relay চেষ্টা করছি...")
                }
                val relayOk = tryConnectWs("$RELAY_URL/relay/$authCode", authCode, isRelay = true)
                if (!relayOk) {
                    withContext(Dispatchers.Main) {
                        onError?.invoke("❌ Connect করা যায়নি — PC চালু আছে এবং code generate করা হয়েছে কিনা দেখো")
                    }
                }
            }
        }
    }

    // ── [ORIGINAL] Connect by code with UDP discovery (LAN only) ───
    fun connectByCode(authCode: String) {
        disconnect()
        onDiscovering?.invoke()
        scope.launch { discoverAndConnect(authCode) }
    }

    // ── [NEW] Connect via relay directly ──────────────────────────
    fun connectViaRelay(authCode: String) {
        disconnect()
        scope.launch {
            tryConnectWs("$RELAY_URL/relay/$authCode", authCode, isRelay = true)
        }
    }

    // ── OkHttp WebSocket connect (handles both ws:// and wss://) ──
    private suspend fun tryConnectWs(wsUrl: String, authCode: String, isRelay: Boolean): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        var success = false

        okHttp = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()

        val req = Request.Builder().url(wsUrl).build()
        var authSent = false

        wsClient = okHttp!!.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(ws: okhttp3.WebSocket, response: Response) {
                Log.d(TAG, "WS open: $wsUrl")
                if (isRelay) {
                    // For relay: first tell relay we're the client, then send auth to peer
                    ws.send(JSONObject().apply {
                        put("type", "relay_client")
                        put("code", authCode)
                    }.toString())
                } else {
                    sendAuth(ws, authCode)
                    authSent = true
                }
            }

            override fun onMessage(ws: okhttp3.WebSocket, text: String) {
                try {
                    val j = JSONObject(text)
                    when (j.optString("type")) {
                        // Relay handshake
                        "relay_ready", "peer_connected" -> {
                            if (isRelay && !authSent) {
                                sendAuth(ws, authCode)
                                authSent = true
                            }
                        }
                        "waiting_for_host" -> {
                            Log.d(TAG, "Waiting for PC to connect to relay...")
                        }
                        // PC responses
                        "ready", "info" -> {
                            val w = j.optInt("width", 1280)
                            val h = j.optInt("height", 720)
                            Log.d(TAG, "PC ready: ${w}x${h}")
                            initDecoder(w, h)
                            success = true
                            latch.countDown()
                            scope.launch(Dispatchers.Main) { onConnected?.invoke(w, h) }
                        }
                        "error" -> {
                            val msg = j.optString("msg", "error")
                            Log.w(TAG, "PC error: $msg")
                            latch.countDown()
                            scope.launch(Dispatchers.Main) {
                                when {
                                    msg.contains("wrong code", ignoreCase = true) ||
                                    msg.contains("auth",       ignoreCase = true) ->
                                        onAuthFailed?.invoke()
                                    msg.contains("busy", ignoreCase = true) ->
                                        onError?.invoke("PC তে already অন্য device connected")
                                    else -> onError?.invoke(msg)
                                }
                            }
                        }
                        "pong" -> {}
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "JSON parse: ${e.message}")
                }
            }

            override fun onMessage(ws: okhttp3.WebSocket, bytes: ByteString) {
                handleBinaryFrame(bytes.toByteArray())
            }

            override fun onClosed(ws: okhttp3.WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WS closed: $reason")
                latch.countDown()
                scope.launch(Dispatchers.Main) { onDisconnected?.invoke() }
            }

            override fun onFailure(ws: okhttp3.WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS failure: ${t.message}")
                latch.countDown()
                scope.launch(Dispatchers.Main) {
                    onError?.invoke("Connect failed: ${t.message}")
                }
            }
        })

        // Wait for auth response (up to 10s)
        withContext(Dispatchers.IO) {
            latch.await(10, TimeUnit.SECONDS)
        }
        return success
    }

    private fun sendAuth(ws: okhttp3.WebSocket, authCode: String) {
        val auth = JSONObject().apply {
            put("type", "auth")
            put("code", authCode)
            put("device", android.os.Build.MODEL)
        }.toString()
        ws.send(auth)
        Log.d(TAG, "Auth sent: $authCode")
    }

    // ── UDP Discovery (legacy LAN fallback) ───────────────────────
    private suspend fun discoverAndConnect(authCode: String) {
        try {
            val buf = ByteArray(1024)
            val pkt = DatagramPacket(buf, buf.size)
            udpSocket = DatagramSocket(UDP_PORT).apply { soTimeout = 8_000 }

            while (currentCoroutineContext().isActive) {
                try {
                    udpSocket?.receive(pkt) ?: break
                    val msg = String(pkt.data, 0, pkt.length)
                    val j = JSONObject(msg)
                    if (j.optString("type") == "rd_announce" && j.optString("code") == authCode) {
                        val pcIp   = j.optString("ip",   pkt.address.hostAddress ?: "")
                        val pcPort = j.optInt("port", WS_PORT)
                        Log.d(TAG, "PC found via UDP: $pcIp:$pcPort")
                        udpSocket?.close(); udpSocket = null
                        scope.launch { tryConnectWs("ws://$pcIp:$pcPort", authCode, false) }
                        return
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    withContext(Dispatchers.Main) {
                        onError?.invoke("UDP timeout — PC পাওয়া যায়নি (same WiFi এ আছো?).\nRelay চেষ্টা করছি...")
                    }
                    // Fall through to relay
                    scope.launch { tryConnectWs("$RELAY_URL/relay/$authCode", authCode, true) }
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "UDP error: ${e.message}")
            withContext(Dispatchers.Main) { onError?.invoke("UDP error: ${e.message}") }
        }
    }

    // ── H264 decoder init ─────────────────────────────────────────
    private fun initDecoder(width: Int, height: Int) {
        releaseDecoder()
        val surface = holder.surface
        if (!surfaceReady || surface == null || !surface.isValid) {
            Log.w(TAG, "Surface not ready")
            onError?.invoke("Surface not ready")
            return
        }
        try {
            val fmt = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            decoder = MediaCodec.createDecoderByType("video/avc").also { dec ->
                dec.configure(fmt, surface, null, 0)
                dec.start()
                decoderReady = true
                Log.d(TAG, "Decoder started ${width}x${height}")
                scope.launch(Dispatchers.IO) { drainDecoder(dec) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "initDecoder: ${e.message}")
            decoderReady = false
            onError?.invoke("Decoder error: ${e.message}")
        }
    }

    private suspend fun drainDecoder(dec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (currentCoroutineContext().isActive && decoderReady) {
            try {
                val idx = dec.dequeueOutputBuffer(info, 5_000L)
                if (idx >= 0) dec.releaseOutputBuffer(idx, true)
            } catch (e: Exception) {
                if (decoderReady) Log.e(TAG, "drain: ${e.message}")
                break
            }
        }
    }

    // ── Binary H264 frame: [flags:1B][pts:4B][NAL...] ─────────────
    private fun handleBinaryFrame(data: ByteArray) {
        if (data.size < 5) return
        val dec = decoder ?: return
        if (!decoderReady) return

        val flags    = data[0].toInt()
        val isConfig = (flags and 2) != 0
        val pts      = ((data[1].toLong() and 0xFF) shl 24) or
                       ((data[2].toLong() and 0xFF) shl 16) or
                       ((data[3].toLong() and 0xFF) shl  8) or
                        (data[4].toLong() and 0xFF)
        val nal = data.copyOfRange(5, data.size)

        try {
            val idx = dec.dequeueInputBuffer(10_000L)
            if (idx < 0) return
            val buf = dec.getInputBuffer(idx) ?: return
            buf.clear(); buf.put(nal)
            val codecFlags = if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
            dec.queueInputBuffer(idx, 0, nal.size, pts * 1000L, codecFlags)
        } catch (e: Exception) {
            Log.e(TAG, "feedDecoder: ${e.message}")
        }
    }

    // ── Input send helpers ─────────────────────────────────────────
    fun sendMouseNorm(mask: Int, nx: Float, ny: Float) {
        wsClient?.send("""{"type":"mouse","mask":$mask,"nx":$nx,"ny":$ny}""")
    }
    fun sendScrollNorm(nx: Float, ny: Float, dir: String) {
        wsClient?.send("""{"type":"scroll","nx":$nx,"ny":$ny,"dir":"$dir"}""")
    }
    fun sendKeyEvent(vk: Int, action: Int) {
        val actionStr = if (action == 1) "up" else "down"
        wsClient?.send("""{"type":"key","vk":$vk,"action":"$actionStr"}""")
    }

    // ── Cleanup ────────────────────────────────────────────────────
    fun disconnect() {
        udpSocket?.close(); udpSocket = null
        wsClient?.close(1000, "Disconnected"); wsClient = null
        okHttp?.dispatcher?.executorService?.shutdown()
        okHttp = null
    }
    private fun releaseDecoder() {
        decoderReady = false
        try { decoder?.stop()    } catch (_: Exception) {}
        try { decoder?.release() } catch (_: Exception) {}
        decoder = null
    }
    fun destroy() { disconnect(); releaseDecoder(); scope.cancel() }
    fun getSurface() = holder.surface
}

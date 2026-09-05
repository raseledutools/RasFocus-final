package com.rasel.RasFocus.remotedesktop

/**
 * PcScreenReceiverView — Code-only connection (no IP needed)
 *
 * User শুধু 6-digit code দেয়।
 * Phone LAN এ UDP broadcast শুনে (port 9225), code match হলে
 * সেই IP তে WebSocket connect করে।
 *
 * PC broadcast (প্রতি 1s):
 *   UDP 255.255.255.255:9225 →
 *   {"type":"rd_announce","code":"XXXXXX","port":9224,"ip":"192.168.x.x"}
 *
 * তারপর WS flow:
 *   Phone → ws://pcIp:9224
 *   Phone → {"type":"auth","code":"XXXXXX","device":"Model"}
 *   PC    → {"type":"ready","width":W,"height":H,"fps":30}
 *   PC    → binary H264 NAL frames
 *   Phone → {"type":"mouse","mask":M,"x":NX,"y":NY}  (NX,NY = 0..1)
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
import okio.ByteString
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.TimeUnit

class PcScreenReceiverView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val TAG = "PcScreenReceiver"
    private val UDP_PORT = 9225      // PC broadcast port
    private val WS_PORT  = 9224      // PC WebSocket port

    private var wsClient:    WebSocket? = null
    private var udpSocket:   DatagramSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var decoder:     MediaCodec? = null
    private var decoderReady = false
    private var surfaceReady = false

    // Callbacks
    var onDiscovering:  (() -> Unit)?                        = null  // UDP scan শুরু
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

    // ── Entry point — শুধু 6-digit code দিলেই হবে ─────────────────
    fun connectByCode(authCode: String) {
        disconnect()
        onDiscovering?.invoke()
        scope.launch { discoverAndConnect(authCode) }
    }

    // ── UDP Discovery — PC এর broadcast শোনো ──────────────────────
    private suspend fun discoverAndConnect(authCode: String) {
        try {
            udpSocket = DatagramSocket(UDP_PORT).apply {
                soTimeout    = 15_000   // 15s timeout
                reuseAddress = true
            }
            val buf = ByteArray(512)
            val pkt = DatagramPacket(buf, buf.size)

            Log.d(TAG, "UDP listening on port $UDP_PORT for code $authCode")

            while (currentCoroutineContext().isActive) {
                try {
                    udpSocket?.receive(pkt) ?: break
                    val msg = String(pkt.data, 0, pkt.length)
                    Log.d(TAG, "UDP recv: $msg")

                    val j = JSONObject(msg)
                    if (j.optString("type") == "rd_announce" &&
                        j.optString("code") == authCode) {

                        val pcIp   = j.optString("ip",   pkt.address.hostAddress ?: "")
                        val pcPort = j.optInt("port", WS_PORT)

                        Log.d(TAG, "PC found at $pcIp:$pcPort via UDP")
                        udpSocket?.close()
                        udpSocket = null

                        // WS connect এ switch করো
                        withContext(Dispatchers.Main) {
                            connectWs(pcIp, pcPort, authCode)
                        }
                        return
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    Log.w(TAG, "UDP timeout — PC not found in 15s")
                    withContext(Dispatchers.Main) {
                        onError?.invoke("⏱ PC পাওয়া যায়নি — PC তে 'Generate Code' চাপো, একই WiFi তে আছো কি?")
                    }
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "UDP discovery error: ${e.message}")
            withContext(Dispatchers.Main) {
                onError?.invoke("UDP error: ${e.message}")
            }
        }
    }

    // ── WebSocket connect (IP পাওয়ার পর) ─────────────────────────
    private fun connectWs(pcIp: String, pcPort: Int, authCode: String) {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()

        val req = Request.Builder().url("ws://$pcIp:$pcPort").build()

        wsClient = client.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WS open → sending auth")
                ws.send("""{"type":"auth","code":"$authCode","device":"${android.os.Build.MODEL}"}""")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleTextMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                handleBinaryFrame(bytes.toByteArray())
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WS closed: $reason")
                onDisconnected?.invoke()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS failure: ${t.message}")
                onError?.invoke("Connect failed: ${t.message}")
                onDisconnected?.invoke()
            }
        })
    }

    // ── PC text messages ──────────────────────────────────────────
    private fun handleTextMessage(text: String) {
        try {
            val j = JSONObject(text)
            when (j.optString("type")) {
                "ready", "info" -> {
                    val w = j.optInt("width",  1280)
                    val h = j.optInt("height",  720)
                    Log.d(TAG, "PC ready: ${w}x${h}")
                    initDecoder(w, h)
                    onConnected?.invoke(w, h)
                }
                "error" -> {
                    val msg = j.optString("msg", "error")
                    Log.w(TAG, "PC error: $msg")
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
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse: ${e.message}")
        }
    }

    // ── H264 decoder — PC resolution দিয়ে init ────────────────────
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

    // ── Binary H264 NAL packet ─────────────────────────────────────
    // Format: [flags 1B][pts_ms 4B][NAL...]
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

    // ── Input send ────────────────────────────────────────────────
    fun sendMouseNorm(mask: Int, nx: Float, ny: Float) {
        wsClient?.send("""{"type":"mouse","mask":$mask,"x":$nx,"y":$ny}""")
    }
    fun sendScrollNorm(nx: Float, ny: Float, dir: String) {
        wsClient?.send("""{"type":"scroll","x":$nx,"y":$ny,"dir":"$dir"}""")
    }
    fun sendKeyEvent(vk: Int, action: Int) {
        wsClient?.send("""{"type":"key","vk":$vk,"action":$action}""")
    }

    // ── Cleanup ───────────────────────────────────────────────────
    fun disconnect() {
        udpSocket?.close(); udpSocket = null
        wsClient?.close(1000, "Disconnected"); wsClient = null
    }
    private fun releaseDecoder() {
        decoderReady = false
        try { decoder?.stop()    } catch (_: Exception) {}
        try { decoder?.release() } catch (_: Exception) {}
        decoder = null
    }
    fun destroy() {
        disconnect(); releaseDecoder(); scope.cancel()
    }
}

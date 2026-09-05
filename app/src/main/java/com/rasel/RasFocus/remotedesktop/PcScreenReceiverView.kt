package com.rasel.RasFocus.remotedesktop

/**
 * PcScreenReceiverView — Self-contained PC screen receiver
 *
 * Service এর উপর নির্ভর করে না। নিজেই:
 *   1. WebSocket connect → auth
 *   2. MediaCodec H264 decoder init (PC ready message থেকে actual resolution নেয়)
 *   3. Binary NAL frames decode → SurfaceView render
 *   4. Mouse/key/scroll send
 *
 * Flow:
 *   Phone → ws://pcIp:9224  (connect)
 *   Phone → {"type":"auth","code":"XXXXXX","device":"Model"}
 *   PC   → {"type":"ready","width":W,"height":H,"fps":30,"codec":"h264"}
 *   PC   → binary [flags 1B | pts_ms 4B | H264 NAL...]
 *   Phone → {"type":"mouse","mask":M,"x":NX,"y":NY}  (NX,NY = 0..1 normalized)
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
import java.util.concurrent.TimeUnit

class PcScreenReceiverView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val TAG = "PcScreenReceiver"

    // WebSocket
    private var wsClient:    WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // H264 decoder — owned by this view, not by Service
    private var decoder: MediaCodec? = null
    private var decoderReady = false
    private var surfaceReady = false

    // Callbacks → PcViewerScreen
    var onConnected:    ((width: Int, height: Int) -> Unit)? = null
    var onDisconnected: (() -> Unit)?                        = null
    var onError:        ((String) -> Unit)?                  = null
    var onAuthFailed:   (() -> Unit)?                        = null

    init { holder.addCallback(this) }

    // ── SurfaceHolder.Callback ─────────────────────────────────────
    override fun surfaceCreated(h: SurfaceHolder)  { surfaceReady = true }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) {
        surfaceReady = false
        releaseDecoder()
    }

    // ── Connect to PC ──────────────────────────────────────────────
    fun connectToPc(pcIp: String, authCode: String, pcPort: Int = 9224) {
        disconnect()
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0,   TimeUnit.SECONDS)
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
                onError?.invoke(t.message ?: "Connection failed")
                onDisconnected?.invoke()
            }
        })
    }

    // ── Handle PC text messages ───────────────────────────────────
    private fun handleTextMessage(text: String) {
        try {
            val j = org.json.JSONObject(text)
            when (j.optString("type")) {
                "ready" -> {
                    val w   = j.optInt("width",  1280)
                    val h   = j.optInt("height",  720)
                    Log.d(TAG, "PC ready: ${w}x${h}")
                    // Init decoder with ACTUAL PC resolution
                    initDecoder(w, h)
                    onConnected?.invoke(w, h)
                }
                "info"  -> {
                    // Legacy compat — some builds send "info" instead of "ready"
                    val w = j.optInt("width", 1280)
                    val h = j.optInt("height", 720)
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
                            onError?.invoke("PC এ already অন্য device connected আছে")
                        else -> onError?.invoke(msg)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error: ${e.message}")
        }
    }

    // ── Init MediaCodec H264 decoder with PC's actual resolution ──
    private fun initDecoder(width: Int, height: Int) {
        releaseDecoder()
        val surface = holder.surface
        if (!surfaceReady || surface == null || !surface.isValid) {
            Log.w(TAG, "Surface not ready for decoder init")
            onError?.invoke("Surface not ready — try again")
            return
        }
        try {
            val fmt = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                // Low-latency hints
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            decoder = MediaCodec.createDecoderByType("video/avc").also { dec ->
                dec.configure(fmt, surface, null, 0)
                dec.start()
                decoderReady = true
                Log.d(TAG, "Decoder started ${width}x${height}")
                // Start output drain loop
                scope.launch(Dispatchers.IO) { drainDecoder(dec) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "initDecoder failed: ${e.message}")
            decoderReady = false
            onError?.invoke("Decoder init failed: ${e.message}")
        }
    }

    // ── Drain decoder output → render to Surface ──────────────────
    private suspend fun drainDecoder(dec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (currentCoroutineContext().isActive && decoderReady) {
            try {
                val idx = dec.dequeueOutputBuffer(info, 5_000L)
                if (idx >= 0) {
                    dec.releaseOutputBuffer(idx, true) // render=true → draws to Surface
                }
            } catch (e: Exception) {
                if (decoderReady) Log.e(TAG, "drainDecoder: ${e.message}")
                break
            }
        }
    }

    // ── Handle binary H264 NAL packet from PC ────────────────────
    // Packet format: [flags 1B][pts_ms 4B][NAL data...]
    private fun handleBinaryFrame(data: ByteArray) {
        if (data.size < 5) return
        val dec = decoder
        if (dec == null || !decoderReady) return

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
            buf.clear()
            buf.put(nal)
            val codecFlags = if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
            // pts_ms থেকে microseconds convert করে decoder এ দাও
            dec.queueInputBuffer(idx, 0, nal.size, pts * 1000L, codecFlags)
        } catch (e: Exception) {
            Log.e(TAG, "feedDecoder: ${e.message}")
        }
    }

    // ── Mouse input (normalized 0..1 coords) ─────────────────────
    // PC InjectMouse: inp.mi.dx = (LONG)(rx * 65535)
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
        wsClient?.close(1000, "Disconnected")
        wsClient = null
    }

    private fun releaseDecoder() {
        decoderReady = false
        try { decoder?.stop();    } catch (_: Exception) {}
        try { decoder?.release(); } catch (_: Exception) {}
        decoder = null
    }

    fun destroy() {
        disconnect()
        releaseDecoder()
        scope.cancel()
    }
}

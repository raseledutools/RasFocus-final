package com.rasel.RasFocus.remotedesktop

/**
 * PcScreenReceiverView
 *
 * Phone এ PC screen দেখানোর জন্য।
 * Flow (PC-generated code):
 *   1. Phone → ws://pcIp:9224  (WebSocket connect)
 *   2. Phone → {"type":"auth","code":"XXXXXX","device":"Phone model"}
 *   3. PC   → {"type":"ready","width":W,"height":H,"fps":30,"codec":"h264"}
 *   4. PC   → binary H264 NAL frames (1-byte flags | 4-byte pts | NAL data)
 *   5. Phone → {"type":"mouse"/"key"/"scroll"} for input
 *
 * PC side port: 9224 (tab_phone_remote.cpp)
 */

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PcScreenReceiverView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val TAG = "PcScreenReceiver"

    private var wsClient: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var surfaceReady = false
    private var authCode = ""

    var onConnected:    ((width: Int, height: Int) -> Unit)? = null
    var onDisconnected: (() -> Unit)?                        = null
    var onError:        ((String) -> Unit)?                  = null
    var onAuthFailed:   (() -> Unit)?                        = null

    init { holder.addCallback(this) }

    override fun surfaceCreated(h: SurfaceHolder) {
        surfaceReady = true
        RemoteDesktopService.getInstance()?.initDecoder(h.surface)
    }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) { surfaceReady = false }

    // ── Connect to PC WebSocket using 6-digit auth code ─────────
    // PC always listens on port 9224 (RD_PORT in tab_phone_remote.cpp)
    fun connectToPc(pcIp: String, authCode: String, pcPort: Int = 9224) {
        this.authCode = authCode
        disconnect()

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)   // no timeout for streaming
            .build()

        val req = Request.Builder().url("ws://$pcIp:$pcPort").build()

        wsClient = client.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WS connected to $pcIp:$pcPort — sending auth code")
                // Step 1: authenticate with the 6-digit code PC generated
                val deviceName = android.os.Build.MODEL
                ws.send("""{"type":"auth","code":"$authCode","device":"$deviceName"}""")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val j = JSONObject(text)
                    when (j.optString("type")) {
                        "ready" -> {
                            // PC accepted the code — stream starting
                            val w = j.optInt("width",  1280)
                            val h = j.optInt("height", 720)
                            Log.d(TAG, "PC ready: ${w}x${h} @ ${j.optInt("fps", 30)}fps")
                            onConnected?.invoke(w, h)
                        }
                        "error" -> {
                            val msg = j.optString("msg", "auth failed")
                            Log.w(TAG, "PC error: $msg")
                            if (msg.contains("wrong code") || msg.contains("auth")) {
                                onAuthFailed?.invoke()
                            } else {
                                onError?.invoke(msg)
                            }
                        }
                        "info" -> {
                            // legacy compat
                            val w = j.optInt("width",  1280)
                            val h = j.optInt("height", 720)
                            onConnected?.invoke(w, h)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "JSON parse error: ${e.message}")
                }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                // H264 NAL packet: [flags 1B][pts_ms 4B][NAL data...]
                val data = bytes.toByteArray()
                if (data.size < 5) return
                val flags    = data[0].toInt()
                val isConfig = (flags and 2) != 0
                val pts      = ((data[1].toLong() and 0xFF) shl 24) or
                               ((data[2].toLong() and 0xFF) shl 16) or
                               ((data[3].toLong() and 0xFF) shl  8) or
                                (data[4].toLong() and 0xFF)
                val nal = data.copyOfRange(5, data.size)
                RemoteDesktopService.getInstance()?.feedDecoderFrame(nal, pts, isConfig)
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

    // ── Send normalized 0..1 mouse coords to PC ─────────────────
    // PC InjectMouse: inp.mi.dx = (LONG)(rx * 65535) where rx is 0..1
    fun sendMouseNorm(mask: Int, nx: Float, ny: Float) {
        wsClient?.send("""{"type":"mouse","mask":$mask,"x":$nx,"y":$ny}""")
    }

    fun sendScrollNorm(nx: Float, ny: Float, dir: String) {
        wsClient?.send("""{"type":"scroll","x":$nx,"y":$ny,"dir":"$dir"}""")
    }

    fun sendKeyEvent(vk: Int, action: Int) {
        wsClient?.send("""{"type":"key","vk":$vk,"action":$action}""")
    }

    // Legacy compat (keep for any other callers)
    fun sendMouseEvent(mask: Int, x: Int, y: Int) =
        sendMouseNorm(mask, x.toFloat(), y.toFloat())

    fun disconnect() {
        wsClient?.close(1000, "Disconnected")
        wsClient = null
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }
}

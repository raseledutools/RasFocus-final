package com.rasel.RasFocus.remotedesktop

/**
 * PcScreenReceiverView
 *
 * Phone এ PC screen দেখানোর জন্য।
 * PC থেকে H264 NAL unit WebSocket দিয়ে আসে → MediaCodec decode → SurfaceView render।
 *
 * PC সাইডে যখন user "Connect to PC" করে, PC ও তার screen encode করে পাঠায়।
 * এই view সেই stream receive করে phone এ live দেখায়।
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

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    init { holder.addCallback(this) }

    override fun surfaceCreated(h: SurfaceHolder) {
        surfaceReady = true
        // If service has a decoder waiting, give it this surface
        RemoteDesktopService.getInstance()?.initDecoder(h.surface)
    }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) { surfaceReady = false }

    // ── Connect to PC WebSocket (PC is a WS server on port 9225) ──
    // Direction: PC screen → phone
    fun connectToPc(pcIp: String, pcPort: Int = 9225) {
        disconnect()
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)  // no timeout for streaming
            .build()
        val req = Request.Builder().url("ws://$pcIp:$pcPort").build()
        wsClient = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Connected to PC at $pcIp:$pcPort")
                onConnected?.invoke()
            }
            override fun onMessage(ws: WebSocket, text: String) {
                // JSON info from PC
                try {
                    val j = JSONObject(text)
                    if (j.optString("type") == "info") {
                        Log.d(TAG, "PC info: ${j.optString("name")} ${j.optInt("width")}x${j.optInt("height")}")
                    }
                } catch (_: Exception) {}
            }
            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                // H264 NAL packet: 1 byte flags | 4 byte pts | NAL data
                val data = bytes.toByteArray()
                if (data.size < 5) return
                val flags    = data[0].toInt()
                val isConfig = (flags and 2) != 0
                val pts      = ((data[1].toLong() and 0xFF) shl 24) or
                               ((data[2].toLong() and 0xFF) shl 16) or
                               ((data[3].toLong() and 0xFF) shl  8) or
                                (data[4].toLong() and 0xFF)
                val nal      = data.copyOfRange(5, data.size)
                RemoteDesktopService.getInstance()?.feedDecoderFrame(nal, pts, isConfig)
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                onDisconnected?.invoke()
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS failure: ${t.message}")
                onError?.invoke(t.message ?: "Connection failed")
                onDisconnected?.invoke()
            }
        })
    }

    // Send mouse/keyboard input to PC
    fun sendMouseEvent(mask: Int, x: Int, y: Int) {
        wsClient?.send("{\"type\":\"mouse\",\"mask\":$mask,\"x\":$x,\"y\":$y}")
    }
    fun sendKeyEvent(vk: Int, action: Int) {
        wsClient?.send("{\"type\":\"key\",\"vk\":$vk,\"action\":$action}")
    }
    fun sendScroll(x: Int, y: Int, dir: String) {
        wsClient?.send("{\"type\":\"scroll\",\"x\":$x,\"y\":$y,\"dir\":\"$dir\"}")
    }

    fun disconnect() {
        wsClient?.close(1000, "User disconnected")
        wsClient = null
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }
}

package com.rasel.RasFocus.remotedesktop

/**
 * RemoteDesktopService — RustDesk-style bidirectional remote desktop
 *
 * UPDATED: Added connectToPC() for Phone→PC direction with relay support.
 * The service now handles BOTH directions:
 *
 *   Phone → PC (new):
 *     user types 6-digit code → Firebase lookup → ws://pc-ip:9224
 *     relay fallback → wss://relay.rasfocus.com/relay/<code>
 *
 *   Phone → Other phone (existing):
 *     MediaProjection → H264 encode → WebSocket server on port 9224
 *
 * Inspired by RustDesk MainService.kt (MIT License)
 */

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.rasel.RasFocus.R
import kotlinx.coroutines.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okio.ByteString
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class RemoteDesktopService : Service() {

    companion object {
        const val ACTION_START       = "com.rasel.RasFocus.remotedesktop.START"
        const val ACTION_STOP        = "com.rasel.RasFocus.remotedesktop.STOP"
        const val EXTRA_RESULT_DATA  = "result_data"
        const val WS_PORT            = 9224
        const val NOTIFY_ID          = 5501
        const val CHANNEL_ID         = "rd_channel"

        private const val MIME        = "video/avc"   // H264
        private const val TARGET_FPS  = 30
        private const val TARGET_BPS  = 2_000_000
        private const val MAX_DIM     = 1280

        // ── Observable state ──────────────────────────────────────
        private val _isRunning        = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _connectedClients = MutableStateFlow(0)
        val connectedClients: StateFlow<Int> = _connectedClients.asStateFlow()

        private val _myId             = MutableStateFlow("")
        val myId: StateFlow<String>   = _myId.asStateFlow()

        // PC→Phone stream active (phone is viewing a PC)
        private val _pcStreamActive   = MutableStateFlow(false)
        val pcStreamActive: StateFlow<Boolean> = _pcStreamActive.asStateFlow()

        val recentConnections         = mutableListOf<RecentConn>()

        private var instance: RemoteDesktopService? = null
        fun getInstance()             = instance

        fun formatId(id: String)      = if (id.length == 9)
            "${id.take(3)} ${id.substring(3,6)} ${id.drop(6)}" else id

        fun getLocalIp(ctx: Context): String {
            try {
                val wm = ctx.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                val ip = wm.connectionInfo.ipAddress
                if (ip != 0) return "%d.%d.%d.%d".format(
                    ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
            } catch (_: Exception) {}
            try {
                for (intf in java.net.NetworkInterface.getNetworkInterfaces())
                    for (addr in intf.inetAddresses)
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address)
                            return addr.hostAddress ?: ""
            } catch (_: Exception) {}
            return "0.0.0.0"
        }
    }

    data class RecentConn(
        val name: String,
        val id: String,
        val ip: String,
        val ts: Long = System.currentTimeMillis(),
        val online: Boolean = true
    )

    private val TAG = "RDService"

    // ── Phone→Other: MediaProjection encoding ─────────────────────
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay:  VirtualDisplay?  = null
    private var encoder:         MediaCodec?      = null

    // ── Phone→PC: OkHttp WebSocket client ────────────────────────
    private var pcWsClient:      WebSocket? = null   // java_websocket direction (unused here)
    private var pcOkClient:      okhttp3.WebSocket? = null
    private var pcOkHttp:        OkHttpClient? = null
    private var pcView:          PcScreenReceiverView? = null

    // Screen dimensions
    private var sw = 0; private var sh = 0; private var dpi = 0

    // WebSocket server (phone is host)
    private var wsServer: RasWsServer? = null
    private val svcScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var encodeJob: Job? = null
    private var deviceId = ""

    // ─────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        instance = this
        _isRunning.value = true

        val prefs = getSharedPreferences("rd_prefs", Context.MODE_PRIVATE)
        deviceId = prefs.getString("device_id", null) ?: run {
            val id = (100_000_000..999_999_999).random().toString()
            prefs.edit().putString("device_id", id).apply(); id
        }
        _myId.value = deviceId

        startForeground(NOTIFY_ID, buildNotification())
        startWsServer()
        RdSignaling.register(this, deviceId, WS_PORT)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
                data?.let { initProjection(it) }
            }
            ACTION_STOP -> {
                stopProjection()
            }
        }
        return START_NOT_STICKY
    }

    // ── Phone→PC: connect to a PC by code (uses PcScreenReceiverView) ─
    // Called from the UI when user taps "Connect"
    // devInfo comes from Firebase (RdSignaling.lookupSession(code))
    fun connectToPC(devInfo: RdSignaling.DeviceInfo, code: String): Boolean {
        // Delegate to PcScreenReceiverView which handles WS+H264 decode
        pcView?.connectByCode(code, devInfo.ip, devInfo.port)
        return true // connection result is async via callbacks
    }

    fun disconnectFromPC() {
        _pcStreamActive.value = false
        pcView?.disconnect()
        pcView = null
    }

    // Attach the SurfaceView from the Compose screen
    fun attachPcView(view: PcScreenReceiverView) {
        pcView = view
        view.onConnected = { _, _ -> _pcStreamActive.value = true }
        view.onDisconnected = { _pcStreamActive.value = false }
        view.onError = { Log.e(TAG, "PcView error: $it") }
        view.onAuthFailed = {
            _pcStreamActive.value = false
            Log.e(TAG, "Auth failed on PC connection")
        }
    }

    // ── Input forwarding to PC ─────────────────────────────────────
    fun sendMouseEvent(nx: Float, ny: Float, mask: Int) {
        pcView?.sendMouseNorm(mask, nx, ny)
    }

    fun sendKeyEvent(vk: Int, action: String) {
        val actionInt = if (action == "down") 1 else 0
        pcView?.sendKeyEvent(vk, actionInt)
    }

    fun sendScrollEvent(nx: Float, ny: Float, dir: String) {
        pcView?.sendScrollNorm(nx, ny, dir)
    }

    // ─────────────────────────────────────────────────────────────
    // Phone→Other direction (existing screen sharing logic)
    // ─────────────────────────────────────────────────────────────

    private fun initProjection(data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(Activity.RESULT_OK, data)
        updateScreenInfo()
        startH264Encoder()
    }

    private fun stopProjection() {
        encodeJob?.cancel()
        runCatching { encoder?.stop(); encoder?.release() }
        runCatching { virtualDisplay?.release() }
        runCatching { mediaProjection?.stop() }
        encoder = null; virtualDisplay = null; mediaProjection = null
        _isRunning.value = false
    }

    private fun updateScreenInfo() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            sw = b.width(); sh = b.height()
            dpi = resources.configuration.densityDpi
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            sw = dm.widthPixels; sh = dm.heightPixels; dpi = dm.densityDpi
        }
        val maxDim = maxOf(sw, sh)
        if (maxDim > MAX_DIM) {
            val s = maxDim.toFloat() / MAX_DIM
            sw = (sw / s).toInt().let { if (it % 2 == 0) it else it - 1 }
            sh = (sh / s).toInt().let { if (it % 2 == 0) it else it - 1 }
        }
        sw = (sw / 16) * 16
        sh = (sh / 16) * 16
    }

    private fun startH264Encoder() {
        val mp = mediaProjection ?: return
        try {
            val format = MediaFormat.createVideoFormat(MIME, sw, sh).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, TARGET_BPS)
                setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    setInteger(MediaFormat.KEY_LATENCY, 0)
            }

            encoder = MediaCodec.createEncoderByType(MIME).also { enc ->
                enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val encSurface: Surface = enc.createInputSurface()
                enc.start()

                virtualDisplay = mp.createVirtualDisplay(
                    "RasFocusRD", sw, sh, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    encSurface, null, null
                )

                encodeJob = svcScope.launch(Dispatchers.IO) { drainEncoder(enc) }
            }
            _isRunning.value = true
        } catch (e: Exception) {
            Log.e(TAG, "startH264Encoder: ${e.message}")
        }
    }

    private suspend fun drainEncoder(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (currentCoroutineContext().isActive) {
            val idx = withContext(Dispatchers.IO) {
                try { enc.dequeueOutputBuffer(info, 10_000L) } catch (e: Exception) { -1 }
            }
            if (idx < 0) continue
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue

            val buf = enc.getOutputBuffer(idx)
            if (buf == null) { enc.releaseOutputBuffer(idx, false); continue }
            val data = ByteArray(info.size).also { buf.get(it) }
            enc.releaseOutputBuffer(idx, false)
            if (data.isEmpty()) continue

            val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            val flags = ((if (isKeyFrame) 1 else 0) or (if (isConfig) 2 else 0)).toByte()
            val pts = info.presentationTimeUs
            val packet = ByteArray(5 + data.size)
            packet[0] = flags
            packet[1] = (pts shr 24 and 0xFF).toByte()
            packet[2] = (pts shr 16 and 0xFF).toByte()
            packet[3] = (pts shr  8 and 0xFF).toByte()
            packet[4] = (pts        and 0xFF).toByte()
            data.copyInto(packet, 5)
            wsServer?.broadcastStream(ByteBuffer.wrap(packet))
        }
    }

    // ── WebSocket Server (phone is host — other devices connect) ──
    private fun startWsServer() {
        try { wsServer = RasWsServer(WS_PORT).also { it.start() } }
        catch (e: Exception) { Log.e(TAG, "ws: ${e.message}") }
    }

    inner class RasWsServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {

        fun broadcastStream(data: ByteBuffer) {
            connections.forEach { conn ->
                try { if (conn.isOpen) conn.send(data.duplicate()) } catch (_: Exception) {}
            }
        }

        override fun onOpen(conn: WebSocket, h: ClientHandshake) {
            _connectedClients.value = connections.size
            conn.send(JSONObject().apply {
                put("type", "info")
                put("id", deviceId)
                put("width", sw); put("height", sh)
                put("device", Build.MODEL)
                put("fps", TARGET_FPS)
                put("codec", "h264")
            }.toString())
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            _connectedClients.value = connections.size
        }

        override fun onMessage(conn: WebSocket, msg: String) {
            try {
                val j = JSONObject(msg)
                when (j.optString("type")) {
                    "touch", "mouse" -> RemoteDesktopInputService.onPointer(
                        j.optInt("mask"), j.optInt("x"), j.optInt("y"))
                    "key"    -> RemoteDesktopInputService.onKey(j.optInt("code"), j.optInt("action"))
                    "scroll" -> RemoteDesktopInputService.onScroll(
                        j.optInt("x"), j.optInt("y"), j.optString("dir"))
                    "quality" -> {
                        val mbps = j.optInt("value", 2)
                        encoder?.setParameters(Bundle().apply {
                            putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, mbps * 1_000_000)
                        })
                    }
                    "keyframe" -> {
                        encoder?.setParameters(Bundle().apply {
                            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                        })
                    }
                    "ping" -> conn.send("{\"type\":\"pong\"}")
                }
            } catch (_: Exception) {}
        }

        override fun onMessage(conn: WebSocket, msg: ByteBuffer) {
            // Incoming binary from another device viewing us — not used in host mode
        }

        override fun onError(conn: WebSocket?, ex: Exception) { Log.e(TAG, "ws: ${ex.message}") }
        override fun onStart() { Log.d(TAG, "WS server ready :$WS_PORT") }
    }

    // ── Notification ──────────────────────────────────────────────
    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Remote Desktop", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_rasgram_notif)
            .setContentTitle("RasFocus Remote")
            .setContentText("ID: ${formatId(deviceId)} • Ready")
            .setOngoing(true).setContentIntent(pi).build()
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        _isRunning.value = false; _connectedClients.value = 0
        _pcStreamActive.value = false; instance = null
        svcScope.cancel()
        disconnectFromPC()
        runCatching { encoder?.stop(); encoder?.release() }
        runCatching { virtualDisplay?.release() }
        runCatching { mediaProjection?.stop() }
        runCatching { wsServer?.stop(1000) }
        RdSignaling.unregister(deviceId)
        super.onDestroy()
    }

    // Compat stubs
    fun initDecoder(surface: Surface) {}
    fun feedDecoderFrame(nalData: ByteArray, pts: Long, isConfig: Boolean) {}
}

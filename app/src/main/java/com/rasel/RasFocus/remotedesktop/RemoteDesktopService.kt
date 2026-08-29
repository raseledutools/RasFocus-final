package com.rasel.RasFocus.remotedesktop

/**
 * RemoteDesktopService — RustDesk-style bidirectional remote desktop
 *
 * Phone → PC:  MediaProjection → MediaCodec H264 encode → WebSocket binary → PC decode & render
 * PC   → Phone: WebSocket binary H264 NAL units → MediaCodec decode → SurfaceView
 * Input:        Both sides send JSON control messages over same WebSocket
 *
 * Inspired by RustDesk MainService.kt (MIT License)
 * https://github.com/rustdesk/rustdesk
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
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.ByteBuffer

class RemoteDesktopService : Service() {

    companion object {
        const val ACTION_START       = "com.rasel.RasFocus.remotedesktop.START"
        const val EXTRA_RESULT_DATA  = "result_data"
        const val WS_PORT            = 9224
        const val NOTIFY_ID          = 5501
        const val CHANNEL_ID         = "rd_channel"

        // H264 settings (RustDesk: VP9 on Rust side, we use H264 via MediaCodec)
        private const val MIME        = "video/avc"   // H264
        private const val TARGET_FPS  = 30
        private const val TARGET_BPS  = 2_000_000     // 2 Mbps — smooth on LAN
        private const val MAX_DIM     = 1280

        // ── Observable state ──────────────────────────────────────
        private val _isRunning        = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _connectedClients = MutableStateFlow(0)
        val connectedClients: StateFlow<Int> = _connectedClients.asStateFlow()

        private val _myId             = MutableStateFlow("")
        val myId: StateFlow<String>   = _myId.asStateFlow()

        // PC → Phone stream state (phone receives PC screen)
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
        val name: String, val id: String, val ip: String,
        val ts: Long = System.currentTimeMillis(), val online: Boolean = true
    )

    private val TAG = "RDService"

    // ── MediaProjection (Phone → PC stream) ───────────────────────
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay:  VirtualDisplay?  = null
    private var encoder:         MediaCodec?      = null  // H264 encoder

    // ── MediaCodec decoder (PC → Phone stream) ───────────────────
    private var decoder:         MediaCodec?      = null
    private var decoderSurface:  Surface?         = null  // set by RemoteDesktopScreen

    // Screen dimensions
    private var sw = 0; private var sh = 0; private var dpi = 0

    // WebSocket server
    private var wsServer: RasWsServer? = null
    private val svcScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Encode thread
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
        // Register on Firebase signaling so other devices can find this ID
        RdSignaling.register(this, deviceId, WS_PORT)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
            data?.let { initProjection(it) }
        }
        return START_NOT_STICKY
    }

    // ── MediaProjection init ──────────────────────────────────────
    private fun initProjection(data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(Activity.RESULT_OK, data)
        updateScreenInfo()
        startH264Encoder()
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
        // Scale down (RustDesk: MAX_SCREEN_SIZE cap)
        val maxDim = maxOf(sw, sh)
        if (maxDim > MAX_DIM) {
            val s = maxDim.toFloat() / MAX_DIM
            sw = (sw / s).toInt().let { if (it % 2 == 0) it else it - 1 }
            sh = (sh / s).toInt().let { if (it % 2 == 0) it else it - 1 }
        }
        // Must be multiple of 16 for H264
        sw = (sw / 16) * 16
        sh = (sh / 16) * 16
        Log.d(TAG, "Screen: ${sw}x${sh} dpi=$dpi")
    }

    // ── H264 Encoder (RustDesk: MediaCodec H264 encode → send NAL units) ──
    private fun startH264Encoder() {
        val mp = mediaProjection ?: return
        try {
            val format = MediaFormat.createVideoFormat(MIME, sw, sh).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, TARGET_BPS)
                setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)  // keyframe every 1s
                // Low-latency encoding (RustDesk approach)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LATENCY, 0)
                }
            }

            encoder = MediaCodec.createEncoderByType(MIME).also { enc ->
                enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val encSurface: Surface = enc.createInputSurface()
                enc.start()

                // VirtualDisplay renders directly to encoder surface (RustDesk: createOrSetVirtualDisplay)
                virtualDisplay = mp.createVirtualDisplay(
                    "RasFocusRD", sw, sh, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    encSurface, null, null
                )

                // Drain encoded NAL units and send via WebSocket
                encodeJob = svcScope.launch(Dispatchers.IO) {
                    drainEncoder(enc)
                }
            }
            Log.d(TAG, "H264 encoder started ${sw}x${sh}")
        } catch (e: Exception) {
            Log.e(TAG, "startH264Encoder: ${e.message}")
        }
    }

    // ── Drain encoded H264 NAL units → WebSocket broadcast ────────
    private suspend fun drainEncoder(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var spsData: ByteArray? = null
        var ppsData: ByteArray? = null

        while (currentCoroutineContext().isActive) {
            val idx = withContext(Dispatchers.IO) {
                try { enc.dequeueOutputBuffer(info, 10_000L) }
                catch (e: Exception) { -1 }
            }
            if (idx < 0) continue
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue

            val buf = enc.getOutputBuffer(idx)
            if (buf == null) { enc.releaseOutputBuffer(idx, false); continue }
            val data = ByteArray(info.size).also { buf.get(it) }
            enc.releaseOutputBuffer(idx, false)

            if (data.isEmpty()) continue

            // Detect SPS/PPS (config frame) — must send to new clients
            val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            if (isConfig) {
                // Parse SPS and PPS from config frame
                parseSpsPs(data)?.let { (sps, pps) ->
                    spsData = sps; ppsData = pps
                }
            }

            val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

            // Wrap: 1 byte flags | 4 byte pts | NAL data
            // flags: bit0=keyframe, bit1=config
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

    private fun parseSpsPs(config: ByteArray): Pair<ByteArray, ByteArray>? {
        // Find 0x00 0x00 0x00 0x01 start codes and split SPS/PPS
        var spsStart = -1; var ppsStart = -1
        for (i in 0..config.size - 4) {
            if (config[i] == 0.toByte() && config[i+1] == 0.toByte() &&
                config[i+2] == 0.toByte() && config[i+3] == 1.toByte()) {
                if (spsStart == -1) spsStart = i
                else if (ppsStart == -1) ppsStart = i
            }
        }
        if (spsStart == -1 || ppsStart == -1) return null
        val sps = config.copyOfRange(spsStart, ppsStart)
        val pps = config.copyOfRange(ppsStart, config.size)
        return Pair(sps, pps)
    }

    // ── H264 Decoder (PC → Phone) — init when PC connects ────────
    fun initDecoder(surface: Surface) {
        decoderSurface = surface
        try {
            decoder = MediaCodec.createDecoderByType(MIME).also { dec ->
                val fmt = MediaFormat.createVideoFormat(MIME, 1920, 1080) // PC resolution
                dec.configure(fmt, surface, null, 0)
                dec.start()
                Log.d(TAG, "H264 decoder started (PC→Phone)")
                _pcStreamActive.value = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "initDecoder: ${e.message}")
        }
    }

    // Feed PC H264 NAL unit to decoder
    fun feedDecoderFrame(nalData: ByteArray, pts: Long, isConfig: Boolean) {
        val dec = decoder ?: return
        try {
            val idx = dec.dequeueInputBuffer(10_000L)
            if (idx < 0) return
            val buf = dec.getInputBuffer(idx) ?: return
            buf.clear(); buf.put(nalData)
            val flags = if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
            dec.queueInputBuffer(idx, 0, nalData.size, pts, flags)

            // Render decoded frame to surface
            val info = MediaCodec.BufferInfo()
            val outIdx = dec.dequeueOutputBuffer(info, 0)
            if (outIdx >= 0) dec.releaseOutputBuffer(outIdx, true)
        } catch (e: Exception) {
            Log.e(TAG, "feedDecoder: ${e.message}")
        }
    }

    // ── WebSocket Server ──────────────────────────────────────────
    private fun startWsServer() {
        try { wsServer = RasWsServer(WS_PORT).also { it.start() } }
        catch (e: Exception) { Log.e(TAG, "ws: ${e.message}") }
    }

    inner class RasWsServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {

        // Send H264 stream only to stream-subscribed clients
        fun broadcastStream(data: ByteBuffer) {
            connections.forEach { conn ->
                try {
                    if (conn.isOpen) conn.send(data.duplicate())
                } catch (_: Exception) {}
            }
        }

        override fun onOpen(conn: WebSocket, h: ClientHandshake) {
            _connectedClients.value = connections.size
            // Send device info
            conn.send(JSONObject().apply {
                put("type", "info")
                put("id", deviceId)
                put("width", sw); put("height", sh)
                put("device", Build.MODEL)
                put("fps", TARGET_FPS)
                put("codec", "h264")
            }.toString())
            Log.d(TAG, "Client connected: ${conn.remoteSocketAddress}")
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            _connectedClients.value = connections.size
            if (connections.isEmpty()) _pcStreamActive.value = false
        }

        override fun onMessage(conn: WebSocket, msg: String) {
            // JSON control messages
            try {
                val j = JSONObject(msg)
                when (j.optString("type")) {
                    // Input from PC → inject to phone (Phone→PC direction)
                    "touch", "mouse" -> RemoteDesktopInputService.onPointer(
                        j.optInt("mask"), j.optInt("x"), j.optInt("y"))
                    "key"    -> RemoteDesktopInputService.onKey(j.optInt("code"), j.optInt("action"))
                    "scroll" -> RemoteDesktopInputService.onScroll(
                        j.optInt("x"), j.optInt("y"), j.optString("dir"))
                    "quality" -> {
                        // Adjust bitrate dynamically
                        val mbps = j.optInt("value", 2)
                        encoder?.setParameters(Bundle().apply {
                            putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, mbps * 1_000_000)
                        })
                    }
                    "keyframe" -> {
                        // Force keyframe (PC requests after new connect)
                        encoder?.setParameters(Bundle().apply {
                            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                        })
                    }
                    "ping" -> conn.send("{\"type\":\"pong\"}")
                }
            } catch (_: Exception) {}
        }

        // Binary message = H264 frame from PC (PC → Phone direction)
        override fun onMessage(conn: WebSocket, msg: ByteBuffer) {
            if (msg.remaining() < 5) return
            val flags   = msg.get().toInt()
            val isConfig = (flags and 2) != 0
            val pts     = ((msg.get().toLong() and 0xFF) shl 24) or
                          ((msg.get().toLong() and 0xFF) shl 16) or
                          ((msg.get().toLong() and 0xFF) shl  8) or
                           (msg.get().toLong() and 0xFF)
            val nal     = ByteArray(msg.remaining())
            msg.get(nal)
            feedDecoderFrame(nal, pts, isConfig)
        }

        override fun onError(conn: WebSocket?, ex: Exception) { Log.e(TAG, "ws: ${ex.message}") }
        override fun onStart() { Log.d(TAG, "WS ready :$WS_PORT") }
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
            .setContentText("ID: ${formatId(deviceId)} • H264 stream ready")
            .setOngoing(true).setContentIntent(pi).build()
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        _isRunning.value = false; _connectedClients.value = 0
        _pcStreamActive.value = false; instance = null
        svcScope.cancel()
        runCatching { encoder?.stop(); encoder?.release() }
        runCatching { decoder?.stop(); decoder?.release() }
        runCatching { virtualDisplay?.release() }
        runCatching { mediaProjection?.stop() }
        runCatching { wsServer?.stop(1000) }
        RdSignaling.unregister(deviceId)
        super.onDestroy()
    }
}

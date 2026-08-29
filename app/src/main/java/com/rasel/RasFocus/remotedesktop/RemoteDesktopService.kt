package com.rasel.RasFocus.remotedesktop

/**
 * RemoteDesktopService
 * Inspired by RustDesk MainService.kt (MIT License)
 * https://github.com/rustdesk/rustdesk
 *
 * Browser ছাড়া native screen capture:
 *   MediaProjection → ImageReader (RGBA) → JPEG → WebSocket → PC native render
 */

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.rasel.RasFocus.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer

class RemoteDesktopService : Service() {

    companion object {
        const val ACTION_START        = "com.rasel.RasFocus.remotedesktop.START"
        const val EXTRA_RESULT_DATA   = "result_data"
        const val WS_PORT             = 9224
        const val NOTIFY_ID           = 5501
        const val CHANNEL_ID          = "rd_channel"
        private const val MAX_DIM     = 1200   // RustDesk-style max resolution cap
        private const val TARGET_FPS_MS = 33L  // ~30 fps

        // ── Observable state (collect from UI) ──
        private val _isRunning       = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _connectedClients = MutableStateFlow(0)
        val connectedClients: StateFlow<Int> = _connectedClients.asStateFlow()

        private val _myId = MutableStateFlow("")
        val myId: StateFlow<String> = _myId.asStateFlow()

        // recent sessions list (in-memory, same session)
        val recentConnections = mutableListOf<RecentConn>()

        private var instance: RemoteDesktopService? = null
        fun getInstance() = instance

        fun formatId(id: String) = if (id.length == 9)
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

    // ── MediaProjection (RustDesk: mMediaProjection / mImageReader / mVirtualDisplay) ──
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captureHandler: Handler? = null
    private var captureThread: HandlerThread? = null

    // screen dims (scaled)
    private var sw = 0; private var sh = 0; private var dpi = 0

    // WebSocket
    private var wsServer: RasWsServer? = null
    private val svcScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // frame control
    private var lastFrameMs = 0L
    private var jpegQ = 65   // adaptive quality

    // device ID
    private var deviceId = ""

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        instance = this
        _isRunning.value = true

        // Stable 9-digit ID (RustDesk: get_id())
        val prefs = getSharedPreferences("rd_prefs", Context.MODE_PRIVATE)
        deviceId = prefs.getString("device_id", null) ?: run {
            val id = (100_000_000..999_999_999).random().toString()
            prefs.edit().putString("device_id", id).apply(); id
        }
        _myId.value = deviceId

        // Background handler thread (RustDesk: HandlerThread for capture)
        captureThread = HandlerThread("RDCapture", Process.THREAD_PRIORITY_VIDEO).also {
            it.start()
            captureHandler = Handler(it.looper)
        }

        startForeground(NOTIFY_ID, buildNotification())
        startWsServer()
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

    // ── MediaProjection init (RustDesk: onActivityResult → startCapture) ─────
    private fun initProjection(data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(Activity.RESULT_OK, data)
        updateScreenInfo()
        startCapture()
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
        // scale down (RustDesk: MAX_SCREEN_SIZE)
        val maxDim = maxOf(sw, sh)
        if (maxDim > MAX_DIM) {
            val s = maxDim.toFloat() / MAX_DIM
            sw = (sw / s).toInt(); sh = (sh / s).toInt()
        }
        Log.d(TAG, "Screen: ${sw}x${sh} dpi=$dpi")
    }

    // ── Frame capture (RustDesk: startRawVideoRecorder / onImageAvailable) ───
    private fun startCapture() {
        val mp = mediaProjection ?: return
        try {
            imageReader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 4).also { ir ->
                ir.setOnImageAvailableListener({ reader ->
                    try {
                        reader.acquireLatestImage()?.use { image ->
                            // skip if no client
                            if (wsServer?.connections?.isEmpty() != false) return@setOnImageAvailableListener
                            // throttle FPS
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastFrameMs < TARGET_FPS_MS) return@setOnImageAvailableListener
                            lastFrameMs = now

                            // Raw RGBA → Bitmap → JPEG (RustDesk: FFI.onVideoFrameUpdate)
                            val plane = image.planes[0]
                            val ps    = plane.pixelStride
                            val rs    = plane.rowStride
                            val bmp   = Bitmap.createBitmap(sw + (rs - ps * sw) / ps, sh, Bitmap.Config.ARGB_8888)
                            bmp.copyPixelsFromBuffer(plane.buffer)
                            val cropped = Bitmap.createBitmap(bmp, 0, 0, sw, sh)
                            bmp.recycle()

                            val baos = ByteArrayOutputStream()
                            cropped.compress(Bitmap.CompressFormat.JPEG, jpegQ, baos)
                            cropped.recycle()

                            wsServer?.broadcast(ByteBuffer.wrap(baos.toByteArray()))
                        }
                    } catch (e: Exception) { Log.e(TAG, "frame: ${e.message}") }
                }, captureHandler)
            }

            // VirtualDisplay (RustDesk: createOrSetVirtualDisplay)
            virtualDisplay = mp.createVirtualDisplay(
                "RasFocusRD", sw, sh, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, null
            )
            Log.d(TAG, "VirtualDisplay started")
        } catch (e: Exception) { Log.e(TAG, "startCapture: ${e.message}") }
    }

    // ── WebSocket Server ──────────────────────────────────────────────────────
    private fun startWsServer() {
        try { wsServer = RasWsServer(WS_PORT).also { it.start() } }
        catch (e: Exception) { Log.e(TAG, "ws start: ${e.message}") }
    }

    inner class RasWsServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {
        override fun onOpen(conn: WebSocket, h: ClientHandshake) {
            _connectedClients.value = connections.size
            // send device info (RustDesk: LoginResponse)
            conn.send(JSONObject().apply {
                put("type", "info")
                put("id", deviceId)
                put("width", sw); put("height", sh)
                put("device", Build.MODEL)
            }.toString())
        }
        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            _connectedClients.value = connections.size
        }
        // Input events from PC (RustDesk: rustPointerInput / rustKeyEventInput)
        override fun onMessage(conn: WebSocket, msg: String) {
            try {
                val j = JSONObject(msg)
                when (j.optString("type")) {
                    "touch", "mouse" -> RemoteDesktopInputService.onPointer(
                        j.optInt("mask"), j.optInt("x"), j.optInt("y"))
                    "key"    -> RemoteDesktopInputService.onKey(j.optInt("code"), j.optInt("action"))
                    "scroll" -> RemoteDesktopInputService.onScroll(
                        j.optInt("x"), j.optInt("y"), j.optString("dir"))
                    "quality" -> jpegQ = j.optInt("value", 65).coerceIn(20, 95)
                    "ping"    -> conn.send("{\"type\":\"pong\"}")
                }
            } catch (_: Exception) {}
        }
        override fun onMessage(conn: WebSocket, msg: ByteBuffer) {}
        override fun onError(conn: WebSocket?, ex: Exception) { Log.e(TAG, "ws err: ${ex.message}") }
        override fun onStart() { Log.d(TAG, "WS ready :$WS_PORT") }
    }

    // ── Notification ──────────────────────────────────────────────────────────
    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Remote Desktop", NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_rasgram_notif)
            .setContentTitle("RasFocus Remote")
            .setContentText("ID: ${formatId(deviceId)} • Port $WS_PORT")
            .setOngoing(true).setContentIntent(pi).build()
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        _isRunning.value = false; _connectedClients.value = 0; instance = null
        svcScope.cancel()
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { mediaProjection?.stop() }
        runCatching { wsServer?.stop(1000) }
        captureThread?.quitSafely()
        super.onDestroy()
    }
}

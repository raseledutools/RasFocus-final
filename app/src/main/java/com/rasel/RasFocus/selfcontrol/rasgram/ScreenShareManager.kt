package com.rasel.RasFocus.selfcontrol.rasgram

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.webrtc.*

/**
 * ScreenShareManager
 *
 * Screen share দুটো mode এ কাজ করে:
 *   1. Normal mode  — Firebase Firestore signaling channel দিয়ে "screenShare" event
 *   2. LAN mode     — LanCallManager TCP socket দিয়ে SCREEN_SHARE_START / _STOP
 *
 * Flow (sharer):
 *   startScreenShare(mediaProjectionData) → ScreenCapturer দিয়ে VideoTrack তৈরি
 *   → existing PeerConnection এ replaceTrack (camera → screen)
 *   → peer কে notify করো (Firestore বা LAN TCP)
 *
 * Flow (viewer):
 *   "screenShare" Firestore event / LAN packet → isRemoteScreenSharing = true
 *   → UI তে remote video full-screen দেখায় (same SurfaceViewRenderer, track same)
 *
 * Remote Input:
 *   MotionEvent serialise → JSON → Firestore "inputEvent" subcollection / LAN TCP
 *   → receiver side: RemoteInputAccessibilityService.injectGesture()
 */
object ScreenShareManager {

    private const val TAG = "ScreenShareManager"

    // Signal types (Firestore + LAN TCP উভয়ে)
    const val SIG_SCREEN_SHARE_START  = "SCREEN_SHARE_START"
    const val SIG_SCREEN_SHARE_STOP   = "SCREEN_SHARE_STOP"
    const val SIG_REMOTE_INPUT        = "REMOTE_INPUT"
    const val SIG_REMOTE_INPUT_GRANT  = "REMOTE_INPUT_GRANT"   // other side accepted
    const val SIG_REMOTE_INPUT_DENY   = "REMOTE_INPUT_DENY"

    // ── State (observed by CallingScreen) ────────────────────────────────────
    private val _isSharingScreen  = MutableStateFlow(false)
    val isSharingScreen: StateFlow<Boolean> = _isSharingScreen.asStateFlow()

    private val _isRemoteSharing  = MutableStateFlow(false)   // peer is sharing
    val isRemoteSharing: StateFlow<Boolean> = _isRemoteSharing.asStateFlow()

    private val _remoteInputGranted = MutableStateFlow(false) // we got input access
    val remoteInputGranted: StateFlow<Boolean> = _remoteInputGranted.asStateFlow()

    private val _incomingInputRequest = MutableStateFlow(false) // peer wants input
    val incomingInputRequest: StateFlow<Boolean> = _incomingInputRequest.asStateFlow()

    // ── Internal WebRTC state ─────────────────────────────────────────────────
    private var mediaProjection: MediaProjection? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var screenVideoSource: VideoSource? = null
    private var screenVideoTrack: VideoTrack? = null
    private var prevCameraTrack: VideoTrack? = null    // restore on stop
    private var activePeerConnection: PeerConnection? = null
    private var activeFactory: PeerConnectionFactory? = null
    private var activeEglBase: EglBase? = null
    private var activeLocalStream: MediaStream? = null

    // ── Firestore call doc reference ──────────────────────────────────────────
    private var callDocId: String = ""
    private var lanCallManager: LanCallManager? = null
    private var isLanMode: Boolean = false

    /**
     * Called once when a call starts — gives ScreenShareManager access to the
     * existing WebRTC session so it can swap tracks.
     */
    fun attachCall(
        peerConnection: PeerConnection,
        factory: PeerConnectionFactory,
        eglBase: EglBase,
        localStream: MediaStream,
        callDocId: String,
        lanMode: Boolean = false,
        lanManager: LanCallManager? = null
    ) {
        activePeerConnection   = peerConnection
        activeFactory          = factory
        activeEglBase          = eglBase
        activeLocalStream      = localStream
        this.callDocId         = callDocId
        this.isLanMode         = lanMode
        this.lanCallManager    = lanManager
        _isSharingScreen.value = false
        _isRemoteSharing.value = false
        _remoteInputGranted.value = false
        _incomingInputRequest.value = false
        Log.i(TAG, "attachCall: ready (lan=$lanMode callId=$callDocId)")
    }

    /**
     * Start screen sharing.
     * @param data Intent from MediaProjection permission result
     */
    fun startScreenShare(context: Context, data: Intent) {
        val factory = activeFactory ?: return
        val egl     = activeEglBase ?: return
        val pc      = activePeerConnection ?: return

        try {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(Activity.RESULT_OK, data)
            mediaProjection = projection

            val metrics = DisplayMetrics()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val bounds = wm.currentWindowMetrics.bounds
                metrics.widthPixels  = bounds.width()
                metrics.heightPixels = bounds.height()
                metrics.densityDpi   = context.resources.displayMetrics.densityDpi
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(metrics)
            }

            val videoSource = factory.createVideoSource(/* isScreencast= */ true)
            screenVideoSource = videoSource

            val helper = SurfaceTextureHelper.create("ScreenCapThread", egl.eglBaseContext)
            val capturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
                override fun onStop() { stopScreenShare(context) }
            })
            capturer.initialize(helper, context, videoSource.capturerObserver)
            capturer.startCapture(metrics.widthPixels, metrics.heightPixels, 30)
            screenCapturer = capturer

            val track = factory.createVideoTrack("screenTrack", videoSource)
            screenVideoTrack = track

            // Swap camera → screen in existing PeerConnection senders
            val senders = pc.senders
            val videoSender = senders.firstOrNull { it.track() is VideoTrack }
            prevCameraTrack = videoSender?.track() as? VideoTrack
            if (videoSender != null) {
                videoSender.setTrack(track, false)
                Log.i(TAG, "Replaced camera track with screen track")
            } else {
                // No existing video sender (audio-only call) — add new
                pc.addTrack(track, listOf("screenStream"))
                Log.i(TAG, "Added screen track to audio-only call")
            }

            _isSharingScreen.value = true

            // Notify peer
            sendSignal(context, JSONObject().apply {
                put("type", SIG_SCREEN_SHARE_START)
                put("callId", callDocId)
            })
            Log.i(TAG, "Screen share started")

        } catch (e: Exception) {
            Log.e(TAG, "startScreenShare error: ${e.message}")
            cleanup()
        }
    }

    fun stopScreenShare(context: Context) {
        if (!_isSharingScreen.value) return
        try {
            val pc  = activePeerConnection
            val prev = prevCameraTrack
            if (pc != null && prev != null) {
                val videoSender = pc.senders.firstOrNull { it.track() is VideoTrack || it.track() == screenVideoTrack }
                videoSender?.setTrack(prev, false)
                Log.i(TAG, "Restored camera track")
            }
            cleanup()
            _isSharingScreen.value = false
            sendSignal(context, JSONObject().apply {
                put("type", SIG_SCREEN_SHARE_STOP)
                put("callId", callDocId)
            })
            Log.i(TAG, "Screen share stopped")
        } catch (e: Exception) {
            Log.e(TAG, "stopScreenShare error: ${e.message}")
        }
    }

    // ── Remote Input ──────────────────────────────────────────────────────────

    /** Viewer requests input control over sharer's device */
    fun requestRemoteInput(context: Context) {
        sendSignal(context, JSONObject().apply {
            put("type", SIG_REMOTE_INPUT)
            put("callId", callDocId)
            put("action", "request")
        })
    }

    /** Sharer grants input to viewer */
    fun grantRemoteInput(context: Context) {
        _incomingInputRequest.value = false
        sendSignal(context, JSONObject().apply {
            put("type", SIG_REMOTE_INPUT_GRANT)
            put("callId", callDocId)
        })
    }

    /** Sharer denies input request */
    fun denyRemoteInput(context: Context) {
        _incomingInputRequest.value = false
        sendSignal(context, JSONObject().apply {
            put("type", SIG_REMOTE_INPUT_DENY)
            put("callId", callDocId)
        })
    }

    /**
     * Viewer sends a touch event to sharer.
     * @param normX  0.0–1.0 normalized X (relative to displayed remote video bounds)
     * @param normY  0.0–1.0 normalized Y
     * @param action android.view.MotionEvent action constant
     */
    fun sendTouchEvent(context: Context, normX: Float, normY: Float, action: Int) {
        if (!_remoteInputGranted.value) return
        sendSignal(context, JSONObject().apply {
            put("type", SIG_REMOTE_INPUT)
            put("callId", callDocId)
            put("action", "touch")
            put("x", normX.toDouble())
            put("y", normY.toDouble())
            put("motionAction", action)
        })
    }

    /** Called when we receive a signal from peer (from Firestore or LAN TCP) */
    fun handleSignal(context: Context, json: JSONObject) {
        when (json.optString("type")) {
            SIG_SCREEN_SHARE_START -> _isRemoteSharing.value = true
            SIG_SCREEN_SHARE_STOP  -> _isRemoteSharing.value = false
            SIG_REMOTE_INPUT_GRANT -> _remoteInputGranted.value = true
            SIG_REMOTE_INPUT_DENY  -> _remoteInputGranted.value = false
            SIG_REMOTE_INPUT -> {
                val action = json.optString("action")
                when (action) {
                    "request" -> _incomingInputRequest.value = true
                    "touch"   -> {
                        // Execute gesture via Accessibility Service
                        val x = json.optDouble("x").toFloat()
                        val y = json.optDouble("y").toFloat()
                        val motionAction = json.optInt("motionAction")
                        RemoteInputAccessibilityService.injectTouch(x, y, motionAction)
                    }
                }
            }
        }
    }

    fun reset() {
        cleanup()
        _isSharingScreen.value     = false
        _isRemoteSharing.value     = false
        _remoteInputGranted.value  = false
        _incomingInputRequest.value = false
        callDocId          = ""
        activePeerConnection = null
        activeFactory        = null
        activeEglBase        = null
        activeLocalStream    = null
        lanCallManager       = null
    }

    // ── Signaling ─────────────────────────────────────────────────────────────
    private fun sendSignal(context: Context, msg: JSONObject) {
        if (isLanMode) {
            // LAN: reuse existing LanCallManager TCP connection
            lanCallManager?.let { lcm ->
                lcm.sendScreenShareSignal(msg)
            }
        } else {
            // Normal: write to Firestore "calls/{callId}/signals" subcollection
            if (callDocId.isNotEmpty()) {
                FirebaseFirestore.getInstance()
                    .collection("calls").document(callDocId)
                    .collection("screenShare")
                    .add(mapOf(
                        "payload"   to msg.toString(),
                        "timestamp" to System.currentTimeMillis()
                    ))
            }
        }
    }

    private fun cleanup() {
        try { screenCapturer?.stopCapture(); screenCapturer?.dispose() } catch (_: Exception) {}
        try { screenVideoTrack?.dispose() } catch (_: Exception) {}
        try { screenVideoSource?.dispose() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        screenCapturer    = null
        screenVideoSource = null
        screenVideoTrack  = null
        mediaProjection   = null
    }
}

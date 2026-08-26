package com.rasel.RasFocus.selfcontrol.rasgram

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import java.io.*
import java.net.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * LanCallManager — WebRTC Audio/Video calling over LAN (no internet/Firebase required)
 *
 * Architecture:
 *   • TCP port 5558 = signaling server (accepts SDP offer/answer & ICE candidates)
 *   • WebRTC PeerConnection with loopback STUN (same LAN = direct P2P, no TURN needed)
 *   • Caller sends CALL_OFFER over TCP → callee shows IncomingLanCall UI
 *   • Callee sends CALL_ANSWER or CALL_REJECT
 *   • ICE candidates exchanged over same TCP connection
 *   • Both sides then connect directly via WebRTC (no relay)
 *
 * Flow:
 *   Caller:  LanCallManager.startCall(peer, callType) → sends CALL_OFFER TCP packet
 *   Callee:  receives packet → incomingCall StateFlow fires → UI shows IncomingLanCallScreen
 *   Callee:  acceptCall() → sends CALL_ANSWER → CallingLanScreen starts
 *   Caller:  receives answer → setRemoteDescription → ICE → connected
 */
class LanCallManager private constructor(private val context: Context) {

    companion object {
        const val SIGNAL_PORT = 5558
        private const val TAG = "LanCallManager"
        private const val MSG_CALL_OFFER   = "CALL_OFFER"
        private const val MSG_CALL_ANSWER  = "CALL_ANSWER"
        private const val MSG_CALL_REJECT  = "CALL_REJECT"
        private const val MSG_CALL_END     = "CALL_END"
        private const val MSG_ICE          = "ICE_CANDIDATE"

        @Volatile private var INSTANCE: LanCallManager? = null
        fun getInstance(context: Context): LanCallManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: LanCallManager(context.applicationContext).also { INSTANCE = it }
            }

        /** Send a screen-share signal over the active LAN TCP connection */
        fun sendScreenShareSignal(msg: org.json.JSONObject) {
            INSTANCE?.sendSignal(msg)
        }
    }

    // ── Incoming call state (observed by UI) ──────────────────────────────────
    data class LanIncomingCall(
        val callId: String,
        val callerMobile: String,
        val callerName: String,
        val callerIp: String,
        val callType: String   // "audio" | "video"
    )

    private val _incomingCall = MutableStateFlow<LanIncomingCall?>(null)
    val incomingCall: StateFlow<LanIncomingCall?> = _incomingCall.asStateFlow()

    // ── Active call state ─────────────────────────────────────────────────────
    data class LanCallState(
        val callId: String,
        val isConnected: Boolean = false,
        val isEnded: Boolean = false,
        val durationSecs: Int = 0
    )
    private val _callState = MutableStateFlow<LanCallState?>(null)
    val callState: StateFlow<LanCallState?> = _callState.asStateFlow()

    // ── WebRTC session state (set by CallingLanScreen) ────────────────────────
    var localVideoTrack: VideoTrack? = null
    var remoteVideoTrack: VideoTrack? = null
    var localSurfaceView: SurfaceViewRenderer? = null
    var remoteSurfaceView: SurfaceViewRenderer? = null

    // Internal state
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var signalServer: ServerSocket? = null
    private var signalSocket: Socket? = null          // active call signaling socket
    private var signalOut: PrintWriter? = null
<<<<<<< Updated upstream
    private var peerConnection: PeerConnection? = null
    private var eglBase: EglBase? = null
    fun getEglBase(): EglBase? = eglBase
    private var factory: PeerConnectionFactory? = null
    private var localStream: MediaStream? = null
=======
    internal var peerConnection: PeerConnection? = null
    internal var eglBase: EglBase? = null
    internal var factory: PeerConnectionFactory? = null
    internal var localStream: MediaStream? = null
>>>>>>> Stashed changes
    private var videoCapturer: VideoCapturer? = null
    private var isRunning = false
    var currentCallId = ""
    private var isCallerRole = false                  // true = we initiated
    private val pendingIceCandidates = ConcurrentLinkedQueue<IceCandidate>()

    // ── Start LAN signal server ───────────────────────────────────────────────
    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch { runSignalServer() }
        Log.i(TAG, "LAN Call signal server started on port $SIGNAL_PORT")
    }

    fun stop() {
        isRunning = false
        endCall(notifyPeer = false)
        try { signalServer?.close() } catch (_: Exception) {}
        signalServer = null
        scope.coroutineContext.cancelChildren()
        Log.i(TAG, "LAN Call stopped")
    }

    // ── Outgoing call ─────────────────────────────────────────────────────────
    /**
     * Initiate a LAN call to [peer].
     * Returns false if WebRTC init fails.
     */
    suspend fun startCall(
        peer: LanDiscoveredUser,
        myMobile: String,
        myName: String,
        callType: String,          // "audio" | "video"
        callId: String = "lan_call_${System.currentTimeMillis()}"
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            currentCallId = callId
            isCallerRole = true

            // Connect TCP signaling to callee
            val sock = Socket()
            sock.connect(InetSocketAddress(peer.ip, SIGNAL_PORT), 5_000)
            signalSocket = sock
            signalOut = PrintWriter(BufferedWriter(OutputStreamWriter(sock.getOutputStream())), true)

            // Init WebRTC
            if (!initWebRtc(callType)) return@withContext false

            // Create offer
            val offerSdp = createOffer(callType) ?: return@withContext false

            // Send CALL_OFFER
            val msg = JSONObject().apply {
                put("type", MSG_CALL_OFFER)
                put("callId", callId)
                put("mobile", myMobile)
                put("name", myName)
                put("callType", callType)
                put("sdp", offerSdp)
            }
            sendSignal(msg)

            // Listen for answer/ICE from callee
            scope.launch { listenSignal(sock.getInputStream()) }

            _callState.value = LanCallState(callId = callId)
            true
        } catch (e: Exception) {
            Log.e(TAG, "startCall error: ${e.message}")
            false
        }
    }

    // ── Accept incoming call ──────────────────────────────────────────────────
    suspend fun acceptCall(
        call: LanIncomingCall,
        answerSdp: String? = null   // pass if already created
    ) = withContext(Dispatchers.IO) {
        try {
            // answerSdp is built in CallingLanScreen after initWebRtc
            val sdp = answerSdp ?: return@withContext
            val msg = JSONObject().apply {
                put("type", MSG_CALL_ANSWER)
                put("callId", call.callId)
                put("sdp", sdp)
            }
            sendSignal(msg)
            _callState.value = LanCallState(callId = call.callId)
            _incomingCall.value = null
        } catch (e: Exception) {
            Log.e(TAG, "acceptCall error: ${e.message}")
        }
    }

    fun rejectCall(call: LanIncomingCall) {
        scope.launch(Dispatchers.IO) {
            try {
                val msg = JSONObject().apply {
                    put("type", MSG_CALL_REJECT)
                    put("callId", call.callId)
                }
                sendSignal(msg)
            } catch (_: Exception) {}
            _incomingCall.value = null
        }
    }

    fun endCall(notifyPeer: Boolean = true) {
        if (notifyPeer && currentCallId.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                try {
                    val msg = JSONObject().apply {
                        put("type", MSG_CALL_END)
                        put("callId", currentCallId)
                    }
                    sendSignal(msg)
                } catch (_: Exception) {}
            }
        }
        cleanupWebRtc()
        _callState.value = _callState.value?.copy(isEnded = true)
        currentCallId = ""
        isCallerRole = false
        signalSocket = null
        signalOut = null
    }

    // ── WebRTC init ───────────────────────────────────────────────────────────
    /**
     * Initialize EglBase + PeerConnectionFactory + local media stream.
     * Called from IO thread (heavy native ops).
     */
    suspend fun initWebRtc(callType: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val base = EglBase.create()
            eglBase = base
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                    .builder(context)
                    .createInitializationOptions()
            )
            val f = PeerConnectionFactory.builder()
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(base.eglBaseContext))
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(base.eglBaseContext, true, true))
                .createPeerConnectionFactory()
            factory = f

            val stream = f.createLocalMediaStream("lanStream")
            val audioSrc = f.createAudioSource(MediaConstraints())
            stream.addTrack(f.createAudioTrack("lanAudio", audioSrc))

            if (callType == "video") {
                val capturer = getVideoCapturer(context)
                if (capturer != null) {
                    val helper = SurfaceTextureHelper.create("LanCapture", base.eglBaseContext)
                    val videoSrc = f.createVideoSource(capturer.isScreencast)
                    capturer.initialize(helper, context, videoSrc.capturerObserver)
                    capturer.startCapture(1280, 720, 30)
                    val vTrack = f.createVideoTrack("lanVideo", videoSrc)
                    stream.addTrack(vTrack)
                    localVideoTrack = vTrack
                    videoCapturer = capturer
                }
            }
            localStream = stream

            // Create PeerConnection — LAN = no TURN needed, STUN only
            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }

            val pc = f.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let { sendIceCandidate(it) }
                }
                override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
                override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> {
                            _callState.value = _callState.value?.copy(isConnected = true)
                            Log.i(TAG, "LAN call connected!")
                        }
                        PeerConnection.IceConnectionState.FAILED,
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            _callState.value = _callState.value?.copy(isEnded = true)
                        }
                        else -> {}
                    }
                }
                override fun onIceConnectionReceivingChange(b: Boolean) {}
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
                override fun onAddStream(s: MediaStream?) {
                    s?.videoTracks?.firstOrNull()?.let { track ->
                        remoteVideoTrack = track
                        remoteSurfaceView?.let { track.addSink(it) }
                    }
                }
                override fun onRemoveStream(s: MediaStream?) {}
                override fun onDataChannel(d: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(r: RtpReceiver?, streams: Array<out MediaStream>?) {
                    r?.track()?.let { track ->
                        if (track is VideoTrack) {
                            remoteVideoTrack = track
                            remoteSurfaceView?.let { track.addSink(it) }
                        }
                    }
                }
            }) ?: return@withContext false

            stream.audioTracks.forEach { pc.addTrack(it, listOf("lanStream")) }
            if (callType == "video") stream.videoTracks.forEach { pc.addTrack(it, listOf("lanStream")) }
            peerConnection = pc

            // Drain any ICE candidates that arrived before PC was ready
            pendingIceCandidates.forEach { pc.addIceCandidate(it) }
            pendingIceCandidates.clear()

            true
        } catch (e: Exception) {
            Log.e(TAG, "initWebRtc error: ${e.message}")
            false
        }
    }

    // ── Create SDP offer (caller) ─────────────────────────────────────────────
    suspend fun createOffer(callType: String): String? = withContext(Dispatchers.IO) {
        val pc = peerConnection ?: return@withContext null
        suspendCancellableCoroutine { cont ->
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                if (callType == "video") mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            }
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let { s ->
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() { cont.resume(s.description) {} }
                            override fun onCreateSuccess(s2: SessionDescription?) {}
                            override fun onCreateFailure(e: String?) {}
                            override fun onSetFailure(e: String?) { cont.resume(null) {} }
                        }, s)
                    } ?: cont.resume(null) {}
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(e: String?) { cont.resume(null) {} }
                override fun onSetFailure(e: String?) {}
            }, constraints)
        }
    }

    // ── Create SDP answer (callee) ────────────────────────────────────────────
    suspend fun createAnswer(offerSdp: String, callType: String): String? = withContext(Dispatchers.IO) {
        val pc = peerConnection ?: return@withContext null
        suspendCancellableCoroutine { cont ->
            pc.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    val constraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                        if (callType == "video") mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                    }
                    pc.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(sdp: SessionDescription?) {
                            sdp?.let { s ->
                                pc.setLocalDescription(object : SdpObserver {
                                    override fun onSetSuccess() { cont.resume(s.description) {} }
                                    override fun onCreateSuccess(s2: SessionDescription?) {}
                                    override fun onCreateFailure(e: String?) {}
                                    override fun onSetFailure(e: String?) { cont.resume(null) {} }
                                }, s)
                            } ?: cont.resume(null) {}
                        }
                        override fun onSetSuccess() {}
                        override fun onCreateFailure(e: String?) { cont.resume(null) {} }
                        override fun onSetFailure(e: String?) {}
                    }, constraints)
                }
                override fun onCreateSuccess(s: SessionDescription?) {}
                override fun onCreateFailure(e: String?) {}
                override fun onSetFailure(e: String?) { cont.resume(null) {} }
            }, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
        }
    }

    // ── Set remote answer (caller) ────────────────────────────────────────────
    suspend fun setRemoteAnswer(answerSdp: String) = withContext(Dispatchers.IO) {
        val pc = peerConnection ?: return@withContext
        suspendCancellableCoroutine<Unit> { cont ->
            pc.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() { cont.resume(Unit) {} }
                override fun onCreateSuccess(s: SessionDescription?) {}
                override fun onCreateFailure(e: String?) {}
                override fun onSetFailure(e: String?) { cont.resume(Unit) {} }
            }, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
        }
    }

    fun setMuted(muted: Boolean) {
        localStream?.audioTracks?.firstOrNull()?.setEnabled(!muted)
    }

    fun setCameraEnabled(enabled: Boolean) {
        localStream?.videoTracks?.firstOrNull()?.setEnabled(enabled)
    }

    fun setSpeaker(on: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = on
    }

    fun flipCamera() {
        try {
            (videoCapturer as? Camera2Capturer)?.switchCamera(null)
                ?: (videoCapturer as? Camera1Capturer)?.switchCamera(null)
        } catch (_: Exception) {}
    }

    // ── TCP Signaling server (listen for incoming calls) ──────────────────────
    private suspend fun runSignalServer() = withContext(Dispatchers.IO) {
        try {
            signalServer = ServerSocket(SIGNAL_PORT)
            Log.i(TAG, "Signal server listening on $SIGNAL_PORT")
            while (isRunning) {
                val client = try { signalServer!!.accept() } catch (_: Exception) { break }
                scope.launch { handleSignalClient(client) }
            }
        } catch (e: Exception) {
            if (isRunning) Log.e(TAG, "Signal server error: ${e.message}")
        }
    }

    private suspend fun handleSignalClient(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            signalSocket = socket
            signalOut = PrintWriter(BufferedWriter(OutputStreamWriter(socket.getOutputStream())), true)
            listenSignal(socket.getInputStream())
        } catch (e: Exception) {
            Log.e(TAG, "handleSignalClient: ${e.message}")
        }
    }

    // ── Signal message processor ──────────────────────────────────────────────
    private suspend fun listenSignal(input: InputStream) = withContext(Dispatchers.IO) {
        try {
            val reader = BufferedReader(InputStreamReader(input))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val msg = try { JSONObject(line!!) } catch (_: Exception) { continue }
                processSignalMessage(msg)
            }
        } catch (e: Exception) {
            if (isRunning) Log.w(TAG, "Signal stream closed: ${e.message}")
            _callState.value = _callState.value?.copy(isEnded = true)
        }
    }

    private fun processSignalMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            MSG_CALL_OFFER -> {
                // Someone is calling us
                val call = LanIncomingCall(
                    callId    = msg.optString("callId"),
                    callerMobile = msg.optString("mobile"),
                    callerName   = msg.optString("name"),
                    callerIp     = signalSocket?.inetAddress?.hostAddress ?: "",
                    callType     = msg.optString("callType", "audio")
                )
                // Store offer SDP for later use
                pendingOfferSdp = msg.optString("sdp")
                pendingCallType = call.callType
                _incomingCall.value = call
            }
            MSG_CALL_ANSWER -> {
                // Our offer was accepted
                val sdp = msg.optString("sdp")
                scope.launch { setRemoteAnswer(sdp) }
            }
            MSG_CALL_REJECT -> {
                _callState.value = _callState.value?.copy(isEnded = true)
                _incomingCall.value = null
            }
            MSG_CALL_END -> {
                cleanupWebRtc()
                _callState.value = _callState.value?.copy(isEnded = true)
            }
            MSG_ICE -> {
                val candidate = IceCandidate(
                    msg.optString("sdpMid"),
                    msg.optInt("sdpMLineIndex"),
                    msg.optString("candidate")
                )
                val pc = peerConnection
                if (pc != null) {
                    pc.addIceCandidate(candidate)
                } else {
                    pendingIceCandidates.add(candidate)
                }
            }
            // ── Screen share + remote input signals ────────────────────────
            ScreenShareManager.SIG_SCREEN_SHARE_START,
            ScreenShareManager.SIG_SCREEN_SHARE_STOP,
            ScreenShareManager.SIG_REMOTE_INPUT,
            ScreenShareManager.SIG_REMOTE_INPUT_GRANT,
            ScreenShareManager.SIG_REMOTE_INPUT_DENY -> {
                ScreenShareManager.handleSignal(context, msg)
            }
        }
    }

    // Temp storage for offer while UI is deciding
    var pendingOfferSdp: String = ""
    var pendingCallType: String = "audio"

    // ── Send ICE candidate to peer ────────────────────────────────────────────
    private fun sendIceCandidate(candidate: IceCandidate) {
        val msg = JSONObject().apply {
            put("type", MSG_ICE)
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            put("candidate", candidate.sdp)
        }
        sendSignal(msg)
    }

    // ── Send raw JSON signal over TCP ─────────────────────────────────────────
    private fun sendSignal(msg: JSONObject) {
        try {
            signalOut?.println(msg.toString())
        } catch (e: Exception) {
            Log.w(TAG, "sendSignal failed: ${e.message}")
        }
    }

    // ── Cleanup WebRTC resources ──────────────────────────────────────────────
    private fun cleanupWebRtc() {
        try { videoCapturer?.stopCapture(); videoCapturer?.dispose() } catch (_: Exception) {}
        try { localVideoTrack?.removeSink(localSurfaceView) } catch (_: Exception) {}
        try { remoteVideoTrack?.removeSink(remoteSurfaceView) } catch (_: Exception) {}
        try { localSurfaceView?.release() } catch (_: Exception) {}
        try { remoteSurfaceView?.release() } catch (_: Exception) {}
        try { peerConnection?.close() } catch (_: Exception) {}
        try { localStream?.dispose() } catch (_: Exception) {}
        try { eglBase?.release() } catch (_: Exception) {}
        peerConnection = null
        localStream = null
        localVideoTrack = null
        remoteVideoTrack = null
        localSurfaceView = null
        remoteSurfaceView = null
        videoCapturer = null
        eglBase = null
        factory = null
        pendingIceCandidates.clear()

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_NORMAL
        am.isSpeakerphoneOn = false
    }

    // ── Helper: get best camera capturer ─────────────────────────────────────
    private fun getVideoCapturer(context: Context): VideoCapturer? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Camera2Enumerator(context).run {
                deviceNames.firstOrNull { isFrontFacing(it) }?.let { createCapturer(it, null) }
                    ?: deviceNames.firstOrNull()?.let { createCapturer(it, null) }
            }
        } else {
            Camera1Enumerator(false).run {
                deviceNames.firstOrNull { isFrontFacing(it) }?.let { createCapturer(it, null) }
                    ?: deviceNames.firstOrNull()?.let { createCapturer(it, null) }
            }
        }
    }
}

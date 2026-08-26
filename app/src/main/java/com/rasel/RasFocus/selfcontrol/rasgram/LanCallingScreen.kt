package com.rasel.RasFocus.selfcontrol.rasgram

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

// ==================== LAN INCOMING CALL SCREEN ====================
/**
 * Shown on the receiving device when a LAN audio/video call comes in.
 * Internet-free, no Firebase. Same look as the regular IncomingCallScreen.
 */
@Composable
fun IncomingLanCallScreen(
    call: LanCallManager.LanIncomingCall,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lanCallManager = remember { LanCallManager.getInstance(context) }

    // Ringing animation
    val infiniteTransition = rememberInfiniteTransition(label = "lan_ring")
    val ringScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.18f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "lan_ring_scale"
    )
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Restart),
        label = "lan_ring_alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B3D2E), Color(0xFF071A14))))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(60.dp))

            Text(
                text = if (call.callType == "video") "📹  LAN ভিডিও কল আসছে..." else "📞  LAN অডিও কল আসছে...",
                color = Color(0xFF00BCD4).copy(alpha = 0.9f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                "📶 ইন্টারনেট ছাড়া — একই WiFi",
                color = Color(0xFF00BCD4).copy(alpha = 0.6f),
                fontSize = 12.sp
            )

            Spacer(Modifier.height(32.dp))

            // Avatar with ripple
            Box(contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.size((120 * ringScale).dp).background(Color(0xFF00BCD4).copy(alpha = ringAlpha * 0.3f), CircleShape))
                Box(modifier = Modifier.size(130.dp).background(Color(0xFF00BCD4).copy(alpha = 0.15f), CircleShape))
                AsyncImage(
                    model = "https://ui-avatars.com/api/?name=${call.callerName.replace(" ", "+")}&size=200&background=006064&color=fff",
                    contentDescription = null,
                    modifier = Modifier.size(110.dp).clip(CircleShape).border(3.dp, Color(0xFF00BCD4), CircleShape)
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(call.callerName, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
            Spacer(Modifier.height(6.dp))
            Text("+${call.callerMobile}", color = RasGramTheme.TextMuted, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            Surface(color = Color(0xFF00BCD4).copy(alpha = 0.2f), shape = RoundedCornerShape(12.dp)) {
                Text(
                    "📶 LAN • ${call.callerIp}",
                    color = Color(0xFF00BCD4),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 60.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Decline
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingActionButton(
                        onClick = { lanCallManager.rejectCall(call); onDecline() },
                        containerColor = Color(0xFFE53935),
                        modifier = Modifier.size(72.dp)
                    ) {
                        Icon(Icons.Default.CallEnd, "Decline", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("প্রত্যাখ্যান", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                }
                // Accept
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingActionButton(
                        onClick = { onAccept() },
                        containerColor = Color(0xFF00BCD4),
                        modifier = Modifier.size(72.dp)
                    ) {
                        Icon(
                            if (call.callType == "video") Icons.Default.Videocam else Icons.Default.Call,
                            "Accept",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("গ্রহণ", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                }
            }
        }
    }
}

// ==================== LAN CALLING SCREEN ====================
/**
 * Active call screen — used by both caller and callee once the call is accepted.
 * Mirrors CallingScreen but uses LanCallManager (TCP signaling, no Firebase).
 */
@Composable
fun CallingLanScreen(
    currentUser: User,
    peerName: String,
    peerMobile: String,
    callType: String,
    call: LanCallManager.LanIncomingCall? = null,   // non-null = we are callee
    onEndCall: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lanCallManager = remember { LanCallManager.getInstance(context) }
    val callState by lanCallManager.callState.collectAsState()
    val currentView = LocalView.current

    var isMuted by remember { mutableStateOf(false) }
    var isCameraOff by remember { mutableStateOf(false) }
    var isSpeakerOn by remember { mutableStateOf(callType == "video") }
    var callSeconds by remember { mutableIntStateOf(0) }
    var isConnected by remember { mutableStateOf(false) }
    var callStatus by remember { mutableStateOf("Connecting...") }
    var showEndedSummary by remember { mutableStateOf(false) }
    var finalCallSeconds by remember { mutableIntStateOf(0) }
    val eglBase = remember { mutableStateOf(lanCallManager.eglBase) }

    // ── Screen Share (LAN mode — TCP signaling) ───────────────────────────────
    val isSharingScreen    by ScreenShareManager.isSharingScreen.collectAsState()
    val isRemoteSharing    by ScreenShareManager.isRemoteSharing.collectAsState()
    val remoteInputGranted by ScreenShareManager.remoteInputGranted.collectAsState()
    val incomingInputRequest by ScreenShareManager.incomingInputRequest.collectAsState()
    var showInputRequestDialog by remember { mutableStateOf(false) }

    val mediaProjectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
    }
    val screenShareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            ScreenShareManager.startScreenShare(context, result.data!!)
        }
    }

    LaunchedEffect(incomingInputRequest) {
        if (incomingInputRequest) showInputRequestDialog = true
    }

    // Callee path: init WebRTC and create answer
    LaunchedEffect(Unit) {
        if (call != null) {
            // We are callee — need to init WebRTC then create answer
            val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (!hasMic) { Toast.makeText(context, "Microphone permission needed", Toast.LENGTH_SHORT).show(); onEndCall(); return@LaunchedEffect }

            val ok = lanCallManager.initWebRtc(callType)
            if (!ok) { onEndCall(); return@LaunchedEffect }
            eglBase.value = lanCallManager.eglBase

            // Attach ScreenShareManager for LAN screen share via TCP signaling
            val pc = lanCallManager.peerConnection
            val factory = lanCallManager.factory
            val egl = lanCallManager.eglBase
            val stream = lanCallManager.localStream
            if (pc != null && factory != null && egl != null && stream != null) {
                ScreenShareManager.attachCall(
                    peerConnection = pc,
                    factory        = factory,
                    eglBase        = egl,
                    localStream    = stream,
                    callDocId      = call?.callId ?: "",
                    lanMode        = true,
                    lanManager     = lanCallManager
                )
            }

            val answerSdp = lanCallManager.createAnswer(lanCallManager.pendingOfferSdp, callType)
            if (answerSdp == null) { onEndCall(); return@LaunchedEffect }
            lanCallManager.acceptCall(call, answerSdp)
        } else {
            // Caller path: attach ScreenShareManager after startCall completes
            // (peerConnection is set up in LanCallManager.startCall — we attach here once connected)
            kotlinx.coroutines.delay(500)   // brief wait for PC to initialize
            val pc = lanCallManager.peerConnection
            val factory = lanCallManager.factory
            val egl = lanCallManager.eglBase
            val stream = lanCallManager.localStream
            if (pc != null && factory != null && egl != null && stream != null) {
                ScreenShareManager.attachCall(
                    peerConnection = pc,
                    factory        = factory,
                    eglBase        = egl,
                    localStream    = stream,
                    callDocId      = lanCallManager.currentCallId,
                    lanMode        = true,
                    lanManager     = lanCallManager
                )
            }
        }

        // Audio mode
        lanCallManager.setSpeaker(isSpeakerOn)
    }

    // Observe call state
    LaunchedEffect(callState) {
        callState?.let { state ->
            if (state.isConnected && !isConnected) {
                isConnected = true
                callStatus = "Connected"
            }
            if (state.isEnded) {
                onEndCall()
            }
        }
    }

    // Timer
    LaunchedEffect(isConnected) {
        if (isConnected) while (true) { delay(1000L); callSeconds++ }
    }

    // Keep screen on
    DisposableEffect(Unit) {
        currentView.keepScreenOn = true
        onDispose { currentView.keepScreenOn = false }
    }

    // Pulse animation
    val pulseTransition = rememberInfiniteTransition(label = "lan_pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "lan_pulse_scale"
    )

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B141A)) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (callType == "video") {
                val eglCtx = eglBase.value?.eglBaseContext
                if (eglCtx != null) {
                    // Remote full-screen
                    AndroidView(
                        factory = { ctx ->
                            SurfaceViewRenderer(ctx).apply {
                                try { init(eglCtx, null); setMirror(false); setEnableHardwareScaler(true) } catch (_: Exception) {}
                                lanCallManager.remoteSurfaceView = this
                                lanCallManager.remoteVideoTrack?.addSink(this)
                            }
                        },
                        update = { renderer ->
                            lanCallManager.remoteVideoTrack?.let { try { it.addSink(renderer) } catch (_: Exception) {} }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    // ── Remote touch forwarding overlay ───────────────────────
                    // When viewer has input access, drag on remote video → send to sharer
                    if (remoteInputGranted) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            val normX = offset.x / size.width.toFloat()
                                            val normY = offset.y / size.height.toFloat()
                                            ScreenShareManager.sendTouchEvent(context, normX, normY, android.view.MotionEvent.ACTION_DOWN)
                                        },
                                        onDrag = { change, _ ->
                                            val normX = change.position.x / size.width.toFloat()
                                            val normY = change.position.y / size.height.toFloat()
                                            ScreenShareManager.sendTouchEvent(context, normX, normY, android.view.MotionEvent.ACTION_MOVE)
                                        },
                                        onDragEnd = {
                                            ScreenShareManager.sendTouchEvent(context, 0f, 0f, android.view.MotionEvent.ACTION_UP)
                                        }
                                    )
                                }
                        ) {
                            // Touch mode badge
                            Surface(
                                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                                color = Color(0xFFFF9800).copy(0.9f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    "✋ Touch Mode",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                    // Local PiP
                    AndroidView(
                        factory = { ctx ->
                            SurfaceViewRenderer(ctx).apply {
                                try { init(eglCtx, null); setMirror(true); setEnableHardwareScaler(true); setZOrderMediaOverlay(true) } catch (_: Exception) {}
                                lanCallManager.localSurfaceView = this
                                lanCallManager.localVideoTrack?.addSink(this)
                            }
                        },
                        update = { renderer ->
                            lanCallManager.localVideoTrack?.let { try { it.addSink(renderer) } catch (_: Exception) {} }
                        },
                        modifier = Modifier.size(120.dp, 160.dp).align(Alignment.TopEnd).padding(16.dp).clip(RoundedCornerShape(12.dp))
                    )
                    // Cross-attach race fix
                    LaunchedEffect(lanCallManager.remoteVideoTrack, lanCallManager.remoteSurfaceView) {
                        val t = lanCallManager.remoteVideoTrack ?: return@LaunchedEffect
                        val r = lanCallManager.remoteSurfaceView ?: return@LaunchedEffect
                        try { t.addSink(r) } catch (_: Exception) {}
                    }
                    LaunchedEffect(lanCallManager.localVideoTrack, lanCallManager.localSurfaceView) {
                        val t = lanCallManager.localVideoTrack ?: return@LaunchedEffect
                        val r = lanCallManager.localSurfaceView ?: return@LaunchedEffect
                        try { t.addSink(r) } catch (_: Exception) {}
                    }
                }
            }

            Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(80.dp))
                if (callType != "video" || !isConnected) {
                    Box(contentAlignment = Alignment.Center) {
                        if (!isConnected) {
                            Box(modifier = Modifier.size((120 * pulseScale).dp).clip(CircleShape).background(Color(0xFF00BCD4).copy(alpha = 0.12f)))
                            Box(modifier = Modifier.size((100 * pulseScale).dp).clip(CircleShape).background(Color(0xFF00BCD4).copy(alpha = 0.18f)))
                        }
                        AsyncImage(
                            model = "https://ui-avatars.com/api/?name=${peerName.replace(" ", "+")}&size=200&background=006064&color=fff&bold=true",
                            contentDescription = null,
                            modifier = Modifier.size(100.dp).clip(CircleShape).border(3.dp, Color(0xFF00BCD4), CircleShape)
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(peerName, style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    if (isConnected) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF00BCD4)))
                            Spacer(Modifier.width(6.dp))
                            Text(formatTime(callSeconds), color = Color(0xFF00BCD4), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(8.dp))
                            Surface(color = Color(0xFF00BCD4).copy(0.15f), shape = RoundedCornerShape(8.dp)) {
                                Text("📶 LAN", color = Color(0xFF00BCD4), fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    } else {
                        Text(callStatus, color = RasGramTheme.TextMuted, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Surface(color = Color(0xFF00BCD4).copy(0.15f), shape = RoundedCornerShape(8.dp)) {
                            Text("📶 LAN — ইন্টারনেট ছাড়া", color = Color(0xFF00BCD4), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                    }
                }
                Spacer(Modifier.weight(1f))

                Surface(shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), color = Color(0xFF182229).copy(0.95f), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            LanCallControlButton(icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic, label = if (isMuted) "Unmute" else "Mute", isActive = isMuted, activeColor = RasGramTheme.Red) {
                                isMuted = !isMuted
                                lanCallManager.setMuted(isMuted)
                            }
                            FloatingActionButton(
                                onClick = {
                                    finalCallSeconds = callSeconds
                                    lanCallManager.endCall()
                                    if (isConnected && callSeconds > 0) showEndedSummary = true else onEndCall()
                                },
                                containerColor = RasGramTheme.Red,
                                modifier = Modifier.size(72.dp)
                            ) {
                                Icon(Icons.Default.CallEnd, null, tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                            LanCallControlButton(icon = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff, label = "Speaker", isActive = isSpeakerOn, activeColor = Color(0xFF00BCD4)) {
                                isSpeakerOn = !isSpeakerOn
                                lanCallManager.setSpeaker(isSpeakerOn)
                            }
                        }
                        if (callType == "video") {
                            Spacer(Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                LanCallControlButton(icon = if (isCameraOff) Icons.Default.VideocamOff else Icons.Default.Videocam, label = "Camera", isActive = isCameraOff, activeColor = RasGramTheme.Red) {
                                    isCameraOff = !isCameraOff
                                    lanCallManager.setCameraEnabled(!isCameraOff)
                                }
                                LanCallControlButton(icon = Icons.Default.Cameraswitch, label = "Flip", isActive = false, activeColor = Color(0xFF00BCD4)) {
                                    lanCallManager.flipCamera()
                                }
                                // ── Screen Share ──────────────────────────────
                                LanCallControlButton(
                                    icon = if (isSharingScreen) Icons.Default.StopScreenShare else Icons.Default.ScreenShare,
                                    label = if (isSharingScreen) "Stop" else "Share Screen",
                                    isActive = isSharingScreen,
                                    activeColor = Color(0xFF00BCD4)
                                ) {
                                    if (isSharingScreen) {
                                        ScreenShareManager.stopScreenShare(context)
                                    } else {
                                        screenShareLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                                    }
                                }
                                // ── Remote Input (viewer) ─────────────────────
                                if (isRemoteSharing) {
                                    LanCallControlButton(
                                        icon = if (remoteInputGranted) Icons.Default.TouchApp else Icons.Default.PanTool,
                                        label = if (remoteInputGranted) "Touch On" else "Req. Touch",
                                        isActive = remoteInputGranted,
                                        activeColor = Color(0xFFFF9800)
                                    ) {
                                        if (!remoteInputGranted) {
                                            if (RemoteInputAccessibilityService.isServiceEnabled(context)) {
                                                ScreenShareManager.requestRemoteInput(context)
                                            } else {
                                                RemoteInputAccessibilityService.openAccessibilitySettings(context)
                                            }
                                        }
                                    }
                                }
                            }

                            // Remote input permission dialog (sharer side)
                            if (showInputRequestDialog) {
                                androidx.compose.material3.AlertDialog(
                                    onDismissRequest = { showInputRequestDialog = false },
                                    containerColor = RasGramTheme.DarkPanel,
                                    icon = { Icon(Icons.Default.TouchApp, null, tint = Color(0xFFFF9800)) },
                                    title = { Text("Remote Touch Request", color = RasGramTheme.TextPrimary, fontWeight = FontWeight.Bold) },
                                    text = {
                                        Text(
                                            "$peerName আপনার স্ক্রিনে ট্যাচ করার অনুমতি চাইছে।",
                                            color = RasGramTheme.TextMuted
                                        )
                                    },
                                    confirmButton = {
                                        Button(
                                            onClick = { showInputRequestDialog = false; ScreenShareManager.grantRemoteInput(context) },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                                        ) { Text("অনুমতি দিন") }
                                    },
                                    dismissButton = {
                                        OutlinedButton(onClick = { showInputRequestDialog = false; ScreenShareManager.denyRemoteInput(context) }) {
                                            Text("না", color = RasGramTheme.TextMuted)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Ended summary
    if (showEndedSummary) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(modifier = Modifier.size(80.dp).background(Color(0xFF1E2B23), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.CallEnd, null, tint = Color(0xFFFF4444), modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.height(20.dp))
                Text(peerName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Spacer(Modifier.height(8.dp))
                Text("কল শেষ", color = RasGramTheme.TextMuted, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                Box(modifier = Modifier.background(Color(0xFF1E3A2B), RoundedCornerShape(50.dp)).padding(horizontal = 24.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AccessTime, null, tint = Color(0xFF00BCD4), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        val mins = finalCallSeconds / 60; val secs = finalCallSeconds % 60
                        Text(
                            when { mins == 0 -> "$secs সেকেন্ড"; secs == 0 -> "$mins মিনিট"; else -> "$mins মিনিট $secs সেকেন্ড" }.let { "কথা হয়েছে $it" },
                            color = Color(0xFF00BCD4),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                    }
                }
                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = { showEndedSummary = false; onEndCall() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                    shape = RoundedCornerShape(50.dp),
                    contentPadding = PaddingValues(horizontal = 36.dp, vertical = 14.dp)
                ) { Text("বন্ধ করুন", color = Color.White, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

@Composable
private fun LanCallControlButton(icon: ImageVector, label: String, isActive: Boolean, activeColor: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FloatingActionButton(onClick = onClick, containerColor = if (isActive) activeColor else Color.White.copy(0.15f), modifier = Modifier.size(56.dp)) {
            Icon(icon, label, tint = Color.White)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = RasGramTheme.TextMuted, style = MaterialTheme.typography.labelSmall)
    }
}

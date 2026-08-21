package com.rasel.RasFocus.selfcontrol.rasgram

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import coil.compose.AsyncImage
import com.google.firebase.firestore.FirebaseFirestore
import com.rasel.RasFocus.ui.theme.RasFocusAppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.launch

/**
 * WhatsApp / Truecaller style incoming call overlay.
 *
 * Fix summary (v3):
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. Double notification বন্ধ:
 *    - startForeground() notification এ এখন caller name, number, call type
 *      এবং Answer / Decline action button আছে।
 *    - IMPORTANCE_HIGH (ছিল IMPORTANCE_MIN) → notification টা visible হয়।
 *    - RasgramMessagingService আলাদা notification পোস্ট করে না (overlay path এ)।
 *
 * 2. Ring সমস্যা ঠিক:
 *    - AudioManager.requestAudioFocus() নেওয়া হচ্ছে → ring শোনা যাবে।
 *    - Activity launch হলে (lock screen path) ring STOP করা হচ্ছে না —
 *      Activity খোলার পর সে নিজেই ring চালায় (IncomingCallScreen এ ring আছে)।
 *    - Activity খুললে stopSelf() না করে শুধু overlay remove করা হয়।
 *
 * 3. Overlay এ caller info + Accept/Decline:
 *    - CallOverlayCard এ caller name, mobile, call type সব আছে।
 *    - Answer/Decline button কাজ করে।
 *
 * 4. Screen off/locked → Activity launch + ring চলতে থাকে।
 *    Screen on/unlocked → overlay card + ring।
 * ─────────────────────────────────────────────────────────────────────────────
 */
class IncomingCallOverlayService : Service(),
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val _viewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = _viewModelStore
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activeListenerRegistration: ListenerRegistration? = null

    companion object {
        const val EXTRA_CALL_ID       = "callId"
        const val EXTRA_CALLER_NAME   = "callerName"
        const val EXTRA_CALLER_MOBILE = "callerMobile"
        const val EXTRA_CALL_TYPE     = "callType"

        const val OVERLAY_NOTIF_ID   = 8888
        const val OVERLAY_CHANNEL_ID = "OVERLAY_CALL_CHANNEL"

        // Duplicate start guard — একই callId এর জন্য একাধিক overlay যাতে না আসে
        @Volatile var isRunning: Boolean = false
        @Volatile var activeCallId: String = ""

        fun start(
            context: Context,
            callId: String,
            callerName: String,
            callerMobile: String,
            callType: String
        ) {
            val intent = Intent(context, IncomingCallOverlayService::class.java).apply {
                putExtra(EXTRA_CALL_ID,       callId)
                putExtra(EXTRA_CALLER_NAME,   callerName)
                putExtra(EXTRA_CALLER_MOBILE, callerMobile)
                putExtra(EXTRA_CALL_TYPE,     callType)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, IncomingCallOverlayService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callId       = intent?.getStringExtra(EXTRA_CALL_ID)      ?: return START_NOT_STICKY
        val callerName   = intent.getStringExtra(EXTRA_CALLER_NAME)   ?: "Unknown"
        val callerMobile = intent.getStringExtra(EXTRA_CALLER_MOBILE) ?: ""
        val callType     = intent.getStringExtra(EXTRA_CALL_TYPE)     ?: "audio"

        // ── Duplicate guard ──────────────────────────────────────────────────
        if (isRunning && activeCallId == callId) return START_NOT_STICKY
        isRunning    = true
        activeCallId = callId

        // ── 1) Foreground notification — visible, caller info সহ ────────────
        startForegroundWithNotification(callerName, callerMobile, callType, callId)

        // ── 2) Screen জ্বালানো ──────────────────────────────────────────────
        acquireWakeLock()

        // ── 3) AudioFocus নাও, তারপর ring + vibrate ─────────────────────────
        requestAudioFocus()
        startRinging()

        // ── 4) Screen/lock state বুঝে overlay বা Activity ──────────────────
        // IMPORTANT: WakeLock নেওয়ার পরেও screen চালু হতে কয়েক millisecond লাগে।
        // তাই coroutine এ সামান্য delay দিয়ে screen state check করা হচ্ছে।
        // এতে overlay সঠিক সময়ে দেখাবে।
        serviceScope.launch {
            // WakeLock effect এর জন্য ছোট delay
            kotlinx.coroutines.delay(200L)

            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager

            val screenOff  = !pm.isInteractive
            val keyguardUp = km.isKeyguardLocked

            if (screenOff || keyguardUp) {
                // Lock screen / screen off: full page Activity খোলো।
                // Activity খোলার পর ring চলতে থাকে — IncomingCallScreen এ
                // নিজের ring আছে কিন্তু service এর ring বন্ধ করতে হবে।
                // RasGramActivity → stopOverlayService() → onDestroy() → stopRinging()
                launchFullScreenCallActivity(callId, callerName, callerMobile, callType)
            } else {
                // Unlocked + screen on: floating overlay card দেখাও
                showOverlay(callId, callerName, callerMobile, callType)
            }
        }

        // ── 5) Firestore listener — caller কল কাটলে ring বন্ধ করো ────────────
        // Caller end করলে status "ended"/"missed"/"cancelled" হবে।
        // এই listener না থাকলে callee এর phone 60s ধরে ring করতে থাকে।
        val callRef = FirebaseFirestore.getInstance().collection("calls").document(callId)
        activeListenerRegistration = callRef.addSnapshotListener { snap, _ ->
            val status = snap?.getString("status") ?: return@addSnapshotListener
            if (status == "ended" || status == "missed" ||
                status == "cancelled" || status == "declined" || status == "rejected") {
                stopSelf()
            }
        }

        // ── 6) 60s auto-dismiss ──────────────────────────────────────────────
        serviceScope.launch {
            kotlinx.coroutines.delay(60_000L)
            if (isRunning && activeCallId == callId) {
                missedCall(callId)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    // ── Full page Activity (lock screen / screen off) ─────────────────────────
    private fun launchFullScreenCallActivity(
        callId: String, callerName: String, callerMobile: String, callType: String
    ) {
        val i = Intent(this, RasGramActivity::class.java).apply {
            action = "ACTION_INCOMING_CALL"
            putExtra("callId",       callId)
            putExtra("callerMobile", callerMobile)
            putExtra("callerName",   callerName)
            putExtra("callType",     callType)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK      or
                Intent.FLAG_ACTIVITY_CLEAR_TOP     or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        startActivity(i)
    }

    // ── Foreground notification: visible, caller info + action buttons ────────
    // IMPORTANCE_HIGH → shade এ দেখাবে (heads-up notification হিসেবে)।
    // Answer/Decline button সরাসরি notification এ।
    private fun startForegroundWithNotification(
        callerName: String, callerMobile: String, callType: String, callId: String
    ) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_HIGH → heads-up দেখাবে কিন্তু ringtone বাজাবে না
            // (ring আলাদাভাবে startRinging() এ করা হচ্ছে)
            val ch = NotificationChannel(
                OVERLAY_CHANNEL_ID,
                "Incoming Call",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)        // overlay path এ ring code এ বাজে
                enableVibration(false)      // vibration ও code এ হয়
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(ch)
        }

        // ── Answer intent ────────────────────────────────────────────────────
        val answerIntent = Intent(this, RasGramActivity::class.java).apply {
            action = "ACTION_INCOMING_CALL"
            putExtra("callId",       callId)
            putExtra("callerMobile", callerMobile)
            putExtra("callerName",   callerName)
            putExtra("callType",     callType)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val answerPending = PendingIntent.getActivity(
            this, 10, answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ── Decline intent ───────────────────────────────────────────────────
        val declineIntent = Intent(this, DeclineCallReceiver::class.java).apply {
            putExtra("callId", callId)
        }
        val declinePending = PendingIntent.getBroadcast(
            this, 11, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (callType == "video") "📹 Incoming Video Call" else "📞 Incoming Voice Call"
        val notif = NotificationCompat.Builder(this, OVERLAY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(title)
            .setContentText("$callerName · $callerMobile")
            .setSubText("RasGram")
            .setContentIntent(answerPending)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_menu_call, "Answer",  answerPending)
            .addAction(android.R.drawable.ic_delete,    "Decline", declinePending)
            .setOngoing(true)
            .setShowWhen(true)
            .setAutoCancel(false)
            .build()

        startForeground(OVERLAY_NOTIF_ID, notif)
    }

    // ── Wake lock: screen জ্বালাও ────────────────────────────────────────────
    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP   or
            PowerManager.ON_AFTER_RELEASE,
            "RasGram:IncomingCallOverlay"
        )
        wakeLock?.acquire(65_000L)
    }

    // ── AudioFocus: ring শোনার জন্য অপরিহার্য ────────────────────────────────
    // AudioFocus না নিলে Android অনেক সময় ringtone কে silent করে দেয়।
    @Suppress("DEPRECATION")
    private fun requestAudioFocus() {
        audioManager?.apply {
            mode = AudioManager.MODE_RINGTONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requestAudioFocus(
                    android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .setAudioAttributes(
                            android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        .setWillPauseWhenDucked(true)
                        .build()
                )
            } else {
                requestAudioFocus(null, AudioManager.STREAM_RING, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            }
            // Ring বাজার জন্য stream volume ঠিক করো
            val vol = getStreamMaxVolume(AudioManager.STREAM_RING)
            setStreamVolume(AudioManager.STREAM_RING, vol, 0)
            isSpeakerphoneOn = false  // earpiece/speaker system default ব্যবহার করবে
        }
    }

    // ── Ring + vibrate ────────────────────────────────────────────────────────
    private fun startRinging() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ringtone?.isLooping = true
            ringtone?.play()
        } catch (_: Exception) {}

        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else
                @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator

            val pattern = longArrayOf(0, 500, 1000, 500, 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            else
                @Suppress("DEPRECATION") vibrator?.vibrate(pattern, 0)
        } catch (_: Exception) {}
    }

    private fun stopRinging() {
        try { ringtone?.stop() } catch (_: Exception) {}
        try { vibrator?.cancel() } catch (_: Exception) {}
        try {
            audioManager?.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
    }

    // ── Window overlay card (screen on + unlocked) ────────────────────────────
    private fun showOverlay(
        callId: String, callerName: String, callerMobile: String, callType: String
    ) {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            // FLAG_NOT_TOUCH_MODAL: overlay এর বাইরে touch pass-through হবে
            // FLAG_NOT_FOCUSABLE: overlay keyboard focus নেবে না — Compose button কাজ করবে
            // FLAG_WATCH_OUTSIDE_TOUCH: outside touch detect করতে পারবে (optional)
            // FLAG_SHOW_WHEN_LOCKED: lock screen এর উপরেও দেখাবে
            // FLAG_TURN_SCREEN_ON + FLAG_KEEP_SCREEN_ON: screen জ্বলবে ও জ্বলে থাকবে
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON  or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@IncomingCallOverlayService)
            setViewTreeViewModelStoreOwner(this@IncomingCallOverlayService)
            setViewTreeSavedStateRegistryOwner(this@IncomingCallOverlayService)
            setContent {
                RasFocusAppTheme {
                    CallOverlayCard(
                        callerName   = callerName,
                        callerMobile = callerMobile,
                        callType     = callType,
                        onAccept     = { answerCall(callId, callerName, callerMobile, callType) },
                        onDecline    = { declineCall(callId) }
                    )
                }
            }
        }

        overlayView = composeView
        windowManager?.addView(composeView, params)
    }

    // ── Actions ───────────────────────────────────────────────────────────────
    private fun answerCall(callId: String, callerName: String, callerMobile: String, callType: String) {
        stopRinging()
        FirebaseFirestore.getInstance().collection("calls").document(callId)
            .update("status", "answered")
        val i = Intent(this, RasGramActivity::class.java).apply {
            action = "ACTION_ANSWER_CALL"
            putExtra("callId",       callId)
            putExtra("callerMobile", callerMobile)
            putExtra("callerName",   callerName)
            putExtra("callType",     callType)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(i)
        stopSelf()
    }

    private fun declineCall(callId: String) {
        stopRinging()
        serviceScope.launch {
            FirebaseFirestore.getInstance().collection("calls").document(callId)
                .update("status", "rejected")
        }
        stopSelf()
    }

    private fun missedCall(callId: String) {
        serviceScope.launch {
            try {
                FirebaseFirestore.getInstance().collection("calls").document(callId)
                    .update("status", "missed")
            } catch (_: Exception) {}
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onDestroy() {
        isRunning    = false
        activeCallId = ""
        activeListenerRegistration?.remove()
        activeListenerRegistration = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
        stopRinging()
        try { wakeLock?.release() } catch (_: Exception) {}
        try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

// ── Compose UI: overlay card ──────────────────────────────────────────────────
@Composable
private fun CallOverlayCard(
    callerName: String,
    callerMobile: String,
    callType: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ring")
    val ringScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 1.15f,
        animationSpec = infiniteRepeatable(
            animation  = tween(700, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringScale"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .systemBarsPadding()
            .padding(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF0B3D2E), Color(0xFF0D1F1A)))
                )
                .border(1.dp, Color(0xFF25D366).copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "RasGram  •  ${if (callType == "video") "Video Call" else "Voice Call"}",
                    color = Color(0xFF25D366).copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(14.dp))
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size((80 * ringScale).dp)
                            .background(Color(0xFF25D366).copy(alpha = 0.12f), CircleShape)
                    )
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(Color(0xFF128C7E).copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text  = callerName.take(1).uppercase(),
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    AsyncImage(
                        model = "https://ui-avatars.com/api/?name=${callerName.replace(" ", "+")}&size=200&background=128C7E&color=fff",
                        contentDescription = null,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .border(2.dp, Color(0xFF25D366), CircleShape),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(callerName, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text(callerMobile, color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp)
                Spacer(Modifier.height(22.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FloatingActionButton(
                            onClick = onDecline,
                            containerColor = Color(0xFFE53935),
                            modifier = Modifier.size(62.dp),
                            shape = CircleShape
                        ) {
                            Icon(Icons.Default.CallEnd, "Decline", tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Decline", color = Color.White.copy(0.65f), fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FloatingActionButton(
                            onClick = onAccept,
                            containerColor = Color(0xFF25D366),
                            modifier = Modifier.size(62.dp),
                            shape = CircleShape
                        ) {
                            Icon(
                                if (callType == "video") Icons.Default.Videocam else Icons.Default.Call,
                                "Accept", tint = Color.White, modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Accept", color = Color.White.copy(0.65f), fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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

        // SharedPreferences constants
        const val PREF_NAME_CONST     = "rasgram_prefs"
        const val PREF_MOBILE_CONST   = "saved_mobile"
        const val PREF_NAME_KEY_CONST = "saved_name"

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

        // Direct Boot: mobile number device encrypted storage এ mirror করো।
        // Reboot এর পর lock screen এ call আসলে credential storage accessible নয়।
        // এই mirror এর ফলে overlay service টা সঠিক user identify করতে পারবে।
        mirrorPrefsToDeviceStorage()
    }

    // Credential encrypted prefs → device encrypted prefs এ copy করো।
    // শুধু mobile number দরকার — sensitive data (password etc) নয়।
    private fun mirrorPrefsToDeviceStorage() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return
        try {
            val credPrefs = getSharedPreferences(PREF_NAME_CONST, Context.MODE_PRIVATE)
            val mobile = credPrefs.getString(PREF_MOBILE_CONST, null) ?: return
            val name   = credPrefs.getString(PREF_NAME_KEY_CONST, "") ?: ""
            createDeviceProtectedStorageContext()
                .getSharedPreferences(PREF_NAME_CONST, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_MOBILE_CONST, mobile)
                .putString(PREF_NAME_KEY_CONST, name)
                .apply()
        } catch (_: Exception) {}
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
            val appForeground = RasGramActivity.isVisible

            if (appForeground) {
                // App খোলা আছে — Activity তে সরাসরি onNewIntent পাঠাও।
                // এতে IncomingCallScreen তাৎক্ষণিক দেখাবে, overlay card লাগবে না।
                launchFullScreenCallActivity(callId, callerName, callerMobile, callType)
            } else if (screenOff || keyguardUp) {
                // Lock screen / screen off: full page Activity খোলো।
                launchFullScreenCallActivity(callId, callerName, callerMobile, callType)
            } else {
                // Unlocked + screen on + app background: floating overlay card দেখাও
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
    // FLAG_ACTIVITY_NO_USER_ACTION: prevents Keyguard from intercepting the
    // intent and blocking Activity launch when screen is locked.
    // FLAG_ACTIVITY_NO_ANIMATION: avoids transition flicker on lock screen.
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
                Intent.FLAG_ACTIVITY_NEW_TASK          or
                Intent.FLAG_ACTIVITY_CLEAR_TOP         or
                Intent.FLAG_ACTIVITY_SINGLE_TOP        or
                Intent.FLAG_ACTIVITY_NO_USER_ACTION    or
                Intent.FLAG_ACTIVITY_NO_ANIMATION
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
        // API 34+ (Android 14+): foregroundServiceType must match manifest declaration.
        // Manifest now declares phoneCall → use FOREGROUND_SERVICE_TYPE_PHONE_CALL.
        // Older APIs: plain startForeground (no type needed).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(OVERLAY_NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } else {
            startForeground(OVERLAY_NOTIF_ID, notif)
        }
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

        // WhatsApp style: full screen overlay — MATCH_PARENT x MATCH_PARENT
        // FLAG_NOT_FOCUSABLE সরানো হয়েছে — না হলে Compose button touch পায় না।
        // FLAG_LAYOUT_IN_SCREEN: status/nav bar এর নিচে না, পুরো screen cover করবে।
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON  or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON  or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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
// ── Full-screen WhatsApp-style incoming call overlay ──────────────────────────
// Screen unlocked থাকলে এই Composable পুরো screen cover করে।
// IncomingCallScreen (Activity path) এর সাথে exact same layout।
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
        targetValue  = 1.18f,
        animationSpec = infiniteRepeatable(
            animation  = tween(700, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringScale"
    )
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue  = 0f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1000),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringAlpha"
    )

    // Full screen — same as IncomingCallScreen in RasGramModule
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF0B3D2E), Color(0xFF071A14)))
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(60.dp))

            // Call type label
            Text(
                text = if (callType == "video") "📹  Video Call আসছে..." else "📞  Voice Call আসছে...",
                color = Color(0xFF25D366).copy(alpha = 0.85f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(Modifier.height(32.dp))

            // Avatar with ripple ring
            Box(contentAlignment = Alignment.Center) {
                // Outer ripple ring
                Box(
                    modifier = Modifier
                        .size((120 * ringScale).dp)
                        .background(Color(0xFF25D366).copy(alpha = ringAlpha * 0.3f), CircleShape)
                )
                // Inner ring
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .background(Color(0xFF25D366).copy(alpha = 0.15f), CircleShape)
                )
                // Avatar
                AsyncImage(
                    model = "https://ui-avatars.com/api/?name=${callerName.replace(" ", "+")}&size=200&background=128C7E&color=fff",
                    contentDescription = null,
                    modifier = Modifier
                        .size(110.dp)
                        .clip(CircleShape)
                        .border(3.dp, Color(0xFF25D366), CircleShape),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = callerName,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 28.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = callerMobile,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 15.sp
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "RasGram",
                color = Color(0xFF25D366).copy(alpha = 0.7f),
                fontSize = 13.sp
            )

            Spacer(Modifier.weight(1f))

            // Accept / Decline row — pinned to bottom like WhatsApp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 60.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Decline
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingActionButton(
                        onClick = onDecline,
                        containerColor = Color(0xFFE53935),
                        modifier = Modifier.size(68.dp),
                        shape = CircleShape
                    ) {
                        Icon(
                            Icons.Default.CallEnd,
                            contentDescription = "Decline",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Decline", color = Color.White.copy(0.7f), fontSize = 13.sp)
                }

                // Accept
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingActionButton(
                        onClick = onAccept,
                        containerColor = Color(0xFF25D366),
                        modifier = Modifier.size(68.dp),
                        shape = CircleShape
                    ) {
                        Icon(
                            if (callType == "video") Icons.Default.Videocam else Icons.Default.Call,
                            contentDescription = "Accept",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Accept", color = Color.White.copy(0.7f), fontSize = 13.sp)
                }
            }
        }
    }
}

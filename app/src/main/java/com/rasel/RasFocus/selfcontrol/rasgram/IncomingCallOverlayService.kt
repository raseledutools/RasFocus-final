package com.rasel.RasFocus.selfcontrol.rasgram

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
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
import kotlinx.coroutines.launch

/**
 * WhatsApp / Truecaller style incoming call overlay.
 *
 * Fix summary (v2):
 * - Screen off / locked → full-page Activity launch করো (WakeLock দিয়ে screen জ্বালাও)
 * - Screen on + unlocked → overlay card (TYPE_APPLICATION_OVERLAY)
 * - Foreground notification: IMPORTANCE_MIN, shade এ দেখাবে না → single notification
 *   RasgramMessagingService আর আলাদা notification post করবে না (silent=true path এ)
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
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callId       = intent?.getStringExtra(EXTRA_CALL_ID)      ?: return START_NOT_STICKY
        val callerName   = intent.getStringExtra(EXTRA_CALLER_NAME)   ?: "Unknown"
        val callerMobile = intent.getStringExtra(EXTRA_CALLER_MOBILE) ?: ""
        val callType     = intent.getStringExtra(EXTRA_CALL_TYPE)     ?: "audio"

        // ── Duplicate guard: same callId এর জন্য আবার start এলে ignore ────
        if (isRunning && activeCallId == callId) return START_NOT_STICKY
        isRunning    = true
        activeCallId = callId

        // ── 1) Foreground notification (Android 8+ requirement) ──────────────
        // IMPORTANCE_MIN → shade এ দেখাবে না, ring করবে না।
        // এটাই একমাত্র notification — RasgramMessagingService আর কোনো
        // call notification post করে না যখন overlay active থাকে।
        startForegroundWithNotification(callerName, callType)

        // ── 2) Screen জ্বালানো (screen off হলে) ─────────────────────────────
        acquireWakeLock()

        // ── 3) Ring + vibrate ─────────────────────────────────────────────────
        startRinging()

        // ── 4) Screen off / locked → Activity; unlocked → overlay ────────────
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager

        val screenOff  = !pm.isInteractive
        val keyguardUp = km.isKeyguardLocked

        if (screenOff || keyguardUp) {
            // Lock screen / screen off: full page Activity খোলো।
            // enableLockScreenDisplay() সেখানে আছে।
            launchFullScreenCallActivity(callId, callerName, callerMobile, callType)
        } else {
            // Unlocked + screen on: floating overlay card
            showOverlay(callId, callerName, callerMobile, callType)
        }

        // ── 5) 60s auto-dismiss ───────────────────────────────────────────────
        serviceScope.launch {
            kotlinx.coroutines.delay(60_000L)
            missedCall(callId)
            stopSelf()
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

    // ── Foreground notification: silent, shade এ দেখাবে না ─────────────────
    private fun startForegroundWithNotification(callerName: String, callType: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                OVERLAY_CHANNEL_ID,
                "Call Overlay Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            }
            nm.createNotificationChannel(ch)
        }

        val title = if (callType == "video") "Incoming Video Call" else "Incoming Voice Call"
        val notif = NotificationCompat.Builder(this, OVERLAY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(title)
            .setContentText(callerName)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setShowWhen(false)
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
            // FLAG_NOT_FOCUSABLE সরানো হয়েছে → overlay এ touch কাজ করবে
            // FLAG_KEEP_SCREEN_ON রাখা হয়েছে
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
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
        stopRinging()
        serviceScope.launch {
            FirebaseFirestore.getInstance().collection("calls").document(callId)
                .update("status", "missed")
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onDestroy() {
        isRunning    = false
        activeCallId = ""
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

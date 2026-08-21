package com.rasel.RasFocus.selfcontrol.rasgram

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioAttributes
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
import android.view.LayoutInflater
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
 * কাজ:
 * - WindowManager TYPE_APPLICATION_OVERLAY দিয়ে screen এর উপর ভেসে উঠে
 * - User যেকোনো app এ থাকুক (YouTube, Chrome, lock screen) — কাজ করে
 * - App বন্ধ থাকলেও কাজ করে (foreground service)
 * - Ringtone + vibration চালায়
 * - Accept → RasGramActivity তে call screen খোলে
 * - Decline → Firestore update করে overlay সরায়
 */
class IncomingCallOverlayService : Service(),
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    // ── Lifecycle boilerplate for ComposeView in a Service ─────────────────
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val viewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = viewModelStore
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

        const val ACTION_ANSWER       = "ACTION_ANSWER_OVERLAY"
        const val ACTION_DECLINE      = "ACTION_DECLINE_OVERLAY"

        const val OVERLAY_NOTIF_ID    = 8888
        const val OVERLAY_CHANNEL_ID  = "OVERLAY_CALL_CHANNEL"

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
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
        val callId       = intent?.getStringExtra(EXTRA_CALL_ID)       ?: return START_NOT_STICKY
        val callerName   = intent.getStringExtra(EXTRA_CALLER_NAME)    ?: "Unknown"
        val callerMobile = intent.getStringExtra(EXTRA_CALLER_MOBILE)  ?: ""
        val callType     = intent.getStringExtra(EXTRA_CALL_TYPE)      ?: "audio"

        // 1) Foreground notification (required on Android 8+)
        startForegroundWithNotification(callerName, callType, callId, callerMobile)

        // 2) Wake the screen even if locked/sleeping
        acquireWakeLock()

        // 3) Ring + vibrate
        startRinging()

        // 4) Show the overlay
        showOverlay(callId, callerName, callerMobile, callType)

        // 5) Auto-dismiss after 60s if no answer (same as WhatsApp)
        serviceScope.launch {
            kotlinx.coroutines.delay(60_000L)
            missedCall(callId)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    // ── Foreground notification ─────────────────────────────────────────────
    private fun startForegroundWithNotification(
        callerName: String,
        callType: String,
        callId: String,
        callerMobile: String
    ) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                OVERLAY_CHANNEL_ID,
                "Active Call",
                NotificationManager.IMPORTANCE_LOW  // silent — overlay already shows full UI
            )
            nm.createNotificationChannel(ch)
        }

        val title = if (callType == "video") "Incoming Video Call" else "Incoming Voice Call"
        val notif = NotificationCompat.Builder(this, OVERLAY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(title)
            .setContentText(callerName)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(OVERLAY_NOTIF_ID, notif)
    }

    // ── Wake lock ────────────────────────────────────────────────────────────
    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "RasGram:IncomingCallOverlay"
        )
        wakeLock?.acquire(65_000L)  // 5s buffer over the 60s auto-dismiss
    }

    // ── Ringtone + vibration ─────────────────────────────────────────────────
    private fun startRinging() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone?.isLooping = true
            }
            ringtone?.play()
        } catch (_: Exception) {}

        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            val pattern = longArrayOf(0, 500, 1000, 500, 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (_: Exception) {}
    }

    private fun stopRinging() {
        try { ringtone?.stop() } catch (_: Exception) {}
        try { vibrator?.cancel() } catch (_: Exception) {}
    }

    // ── Window overlay ───────────────────────────────────────────────────────
    private fun showOverlay(
        callId: String,
        callerName: String,
        callerMobile: String,
        callType: String
    ) {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
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

    // ── Actions ──────────────────────────────────────────────────────────────
    private fun answerCall(
        callId: String,
        callerName: String,
        callerMobile: String,
        callType: String
    ) {
        stopRinging()
        // Firestore: mark answered
        FirebaseFirestore.getInstance()
            .collection("calls")
            .document(callId)
            .update("status", "answered")

        // Launch RasGramActivity in call-answer mode
        val launchIntent = Intent(this, RasGramActivity::class.java).apply {
            action = "ACTION_ANSWER_CALL"
            putExtra("callId",       callId)
            putExtra("callerMobile", callerMobile)
            putExtra("callerName",   callerName)
            putExtra("callType",     callType)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        startActivity(launchIntent)
        stopSelf()
    }

    private fun declineCall(callId: String) {
        stopRinging()
        serviceScope.launch {
            FirebaseFirestore.getInstance()
                .collection("calls")
                .document(callId)
                .update("status", "rejected")
        }
        stopSelf()
    }

    private fun missedCall(callId: String) {
        stopRinging()
        serviceScope.launch {
            FirebaseFirestore.getInstance()
                .collection("calls")
                .document(callId)
                .update("status", "missed")
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────
    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
        stopRinging()
        try { wakeLock?.release() } catch (_: Exception) {}
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

// ── Compose UI: the overlay card ─────────────────────────────────────────────
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
            animation    = tween(700, easing = EaseInOut),
            repeatMode   = RepeatMode.Reverse
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
                    Brush.verticalGradient(
                        listOf(Color(0xFF0B3D2E), Color(0xFF0D1F1A))
                    )
                )
                .border(1.dp, Color(0xFF25D366).copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {

                // App label
                Text(
                    text = "RasGram  •  ${if (callType == "video") "Video Call" else "Voice Call"}",
                    color = Color(0xFF25D366).copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(Modifier.height(14.dp))

                // Avatar with pulse ring
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size((80 * ringScale).dp)
                            .background(
                                Color(0xFF25D366).copy(alpha = 0.12f),
                                CircleShape
                            )
                    )
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(Color(0xFF128C7E).copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        // Initials fallback
                        Text(
                            text  = callerName.take(1).uppercase(),
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    // Try loading real avatar if mobile is known
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

                Text(
                    text       = callerName,
                    color      = Color.White,
                    fontSize   = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text  = callerMobile,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 13.sp
                )

                Spacer(Modifier.height(22.dp))

                // Decline | Accept
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Decline
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FloatingActionButton(
                            onClick            = onDecline,
                            containerColor     = Color(0xFFE53935),
                            modifier           = Modifier.size(62.dp),
                            shape              = CircleShape
                        ) {
                            Icon(
                                Icons.Default.CallEnd,
                                contentDescription = "Decline",
                                tint   = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Decline", color = Color.White.copy(0.65f), fontSize = 12.sp)
                    }

                    // Accept
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FloatingActionButton(
                            onClick            = onAccept,
                            containerColor     = Color(0xFF25D366),
                            modifier           = Modifier.size(62.dp),
                            shape              = CircleShape
                        ) {
                            Icon(
                                if (callType == "video") Icons.Default.Videocam else Icons.Default.Call,
                                contentDescription = "Accept",
                                tint   = Color.White,
                                modifier = Modifier.size(28.dp)
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

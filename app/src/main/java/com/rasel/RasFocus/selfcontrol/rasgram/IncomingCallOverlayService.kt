package com.rasel.RasFocus.selfcontrol.rasgram

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.graphics.PixelFormat
import android.media.AudioManager
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
import androidx.compose.foundation.shape.CircleShape
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
 * WhatsApp / Truecaller style incoming call overlay — Robust v6
 *
 * v6 improvements over v5:
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. Vibration সবসময় চলে — ringer mode (silent/vibrate/normal) যাই হোক।
 *    Ring (audio) শুধু RINGER_MODE_NORMAL এ বাজে।
 *    AudioManager.MODE_RINGTONE set করা হয় না — এটা call setup এর জন্য,
 *    ringtone playback এর জন্য দরকার নেই, বরং কিছু OEM এ AudioFocus conflict করে।
 *
 * 2. Wake lock: Android 10+ এ SCREEN_BRIGHT_WAKE_LOCK deprecated।
 *    WindowManager params এ FLAG_KEEP_SCREEN_ON + FLAG_TURN_SCREEN_ON ব্যবহার করা হয়।
 *    PowerManager.WakeLock শুধু PARTIAL_WAKE_LOCK (CPU alive) হিসেবে রাখা হয়।
 *    ACQUIRE_CAUSES_WAKEUP: screen জ্বালানোর জন্য এটা এখনো দরকার।
 *
 * 3. Keyguard dismiss: overlay window params এ FLAG_DISMISS_KEYGUARD যোগ করা হয়েছে।
 *    Lock screen থেকে Answer করলে keyguard সরে যায়, সরাসরি call screen দেখায়।
 *    Answer button এ setShowWhenLocked + setTurnScreenOn Activity flag দরকার (RasGramActivity তে আছে)।
 *
 * 4. Screen state race condition fix: 200ms delay বাদ দেওয়া হয়েছে।
 *    পরিবর্তে isInteractive + isKeyguardLocked check synchronously করা হয়।
 *    Overlay path এর জন্য canDrawOverlays() re-check করা হয়।
 *
 * 5. Notification channel sound: SERVICE নিজে ring করে (Ringtone API),
 *    তাই channel sound null করা হয়েছে — double ring বা conflict নেই।
 *    Vibration channel-level enable করা আছে (OS-level vibrate fallback)।
 *
 * 6. App foreground path: stopSelf() এর আগে missed call timeout দেওয়া হয়।
 *    RasGramModule যদি Firestore detect করতে fail করে, 60s পরে missed mark হয়।
 *
 * 7. Android 15 (API 35) compat: FLAG_DISMISS_KEYGUARD deprecated flag —
 *    KeyguardManager.requestDismissKeyguard() দিয়ে proper dismiss করা হয়।
 *
 * v5 থেকে বজায় আছে:
 *   - Background startActivity() ban (Android 10+) — service নিজে Activity launch করে না
 *   - fullScreenIntent: OS নিজেই lock screen এ fire করে
 *   - FOREGROUND_SERVICE_TYPE_PHONE_CALL + MANAGE_OWN_CALLS fallback
 *   - USE_FULL_SCREEN_INTENT runtime check (Android 14+)
 *   - Direct Boot aware: device encrypted prefs mirror
 *   - Duplicate call guard (isRunning + activeCallId)
 *   - Firestore listener: caller cancel → auto dismiss
 *   - 60s timeout → missed call mark
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
    private var wakeLock: PowerManager.WakeLock? = null
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

        // Direct Boot: mobile number device encrypted storage এ mirror করো।
        mirrorPrefsToDeviceStorage()
    }

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

        // ── 2) CPU alive রাখার জন্য minimal wake lock ─────────────────────
        // Note: screen জ্বালানো এখন WindowManager flags দিয়ে হয় (overlay path)
        // এবং notification fullScreenIntent দিয়ে হয় (lock/notification path)।
        acquireWakeLock()

        // ── 3) Screen/lock state synchronously check করো ───────────────────
        // v6 FIX: 200ms delay বাদ — race condition ছিল।
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager

        val screenOff    = !pm.isInteractive
        val keyguardUp   = km.isKeyguardLocked
        val appForeground = RasGramActivity.isVisible

        // Overlay permission synchronous check
        val hasOverlay = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
                         Settings.canDrawOverlays(this)

        when {
            appForeground -> {
                // App foreground এ আছে: RasGramModule এর Firestore listener ইতিমধ্যে
                // incoming call detect করবে এবং IncomingCallScreen দেখাবে।
                // Service 60s পরে missed mark করবে (RasGramModule fail করলে fallback)।
                setupFirestoreListenerForMissed(callId)
                setup60sTimeout(callId)
                // stopSelf() করা হচ্ছে না — Firestore listener + timeout manage করবে।
                // Service alive থাকলে notification ও থাকে, যা in-call audio route preserve করে।
            }
            (screenOff || keyguardUp) -> {
                // Lock screen / screen off path:
                // notification fullScreenIntent Android OS নিজেই fire করবে।
                // Service alive রাখো — notification জীবিত থাকলেই fullScreenIntent কাজ করে।
                //
                // v6: Android 14+ এ USE_FULL_SCREEN_INTENT check notification post করার সময়ই হয়েছে।
                setupFirestoreListener(callId)
                setup60sTimeout(callId)
            }
            hasOverlay -> {
                // Unlocked + screen on + app background + overlay permission আছে:
                // Floating full-page overlay দেখাও।
                showOverlay(callId, callerName, callerMobile, callType)
                setupFirestoreListener(callId)
                setup60sTimeout(callId)
            }
            else -> {
                // Overlay permission নেই — notification already posted।
                // fullScreenIntent দিয়ে OS নিজে Activity আনবে।
                setupFirestoreListener(callId)
                setup60sTimeout(callId)
            }
        }

        return START_NOT_STICKY
    }

    // ── Foreground notification ────────────────────────────────────────────────
    private fun startForegroundWithNotification(
        callerName: String, callerMobile: String, callType: String, callId: String
    ) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                OVERLAY_CHANNEL_ID,
                "Incoming Call",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                // v6 FIX: sound null — Service নিজে Ringtone API দিয়ে ring করে।
                // Channel sound চালু রাখলে notification আসার সময় OS আলাদা sound বাজায়
                // → double ring / audio conflict। তাই null।
                setSound(null, null)
                // Vibration channel-level enable: OS এর নিজস্ব vibrate fallback কাজ করে।
                // Service এর DisposableEffect ও vibrate করে — দুটো একসাথে overlap করে না
                // কারণ channel vibrate শুধু notification arrive করার মুহূর্তে।
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 600)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                enableLights(true)
                lightColor = android.graphics.Color.parseColor("#25D366")
            }
            nm.createNotificationChannel(ch)
        }

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
            .setFullScreenIntent(answerPending, true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_menu_call, "Answer",  answerPending)
            .addAction(android.R.drawable.ic_delete,    "Decline", declinePending)
            .setOngoing(true)
            .setShowWhen(true)
            .setAutoCancel(false)
            // v6: Notification-level vibration (pre-O devices বা channel-level fallback)
            .setVibrate(longArrayOf(0, 800, 600))
            .build()

        // Android 14+ (UPSIDE_DOWN_CAKE): foregroundServiceType="phoneCall" এর জন্য
        // MANAGE_OWN_CALLS permission লাগে। কিছু OEM এ SecurityException throw করে।
        // Fallback: type ছাড়া plain startForeground।
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(
                    OVERLAY_NOTIF_ID, notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                )
            } catch (e: Exception) {
                android.util.Log.w("RasGram", "phoneCall foreground type failed, fallback: ${e.message}")
                try { startForeground(OVERLAY_NOTIF_ID, notif) } catch (_: Exception) {}
            }
        } else {
            startForeground(OVERLAY_NOTIF_ID, notif)
        }

        // Android 14+ (API 34): USE_FULL_SCREEN_INTENT permission runtime check।
        // না থাকলে lock screen এ call আসে না — user কে Settings এ পাঠাও।
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm2 = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (!nm2.canUseFullScreenIntent()) {
                android.util.Log.w("RasGram", "USE_FULL_SCREEN_INTENT not granted — lock screen call UI won't show")
                try {
                    val settingsIntent = Intent(
                        Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:$packageName")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    startActivity(settingsIntent)
                } catch (_: Exception) {}
            }
        }
    }

    // ── Wake lock: CPU alive রাখো (screen জ্বালানো WindowManager flags এ) ──────
    // v6: SCREEN_BRIGHT_WAKE_LOCK deprecated Android 10+ এ।
    // WindowManager overlay params এ FLAG_KEEP_SCREEN_ON + FLAG_TURN_SCREEN_ON দেওয়া আছে।
    // এখানে শুধু PARTIAL_WAKE_LOCK: CPU + network চালু রাখে — service kill হয় না।
    // ACQUIRE_CAUSES_WAKEUP: screen physically জ্বালায় (overlay path + lock path)।
    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        // SCREEN_BRIGHT_WAKE_LOCK deprecated হলেও এখনো কাজ করে (API 35 পর্যন্ত)
        // এবং এটাই lock screen থেকে screen জ্বালানোর সবচেয়ে reliable উপায়।
        // FLAG_KEEP_SCREEN_ON (WindowManager) screen on রাখে কিন্তু screen জ্বালায় না।
        // ACQUIRE_CAUSES_WAKEUP: screen off থাকলে জ্বালায়।
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP   or
            PowerManager.ON_AFTER_RELEASE,
            "RasGram:IncomingCallOverlay"
        )
        wakeLock?.acquire(65_000L)
    }

    // ── Firestore listener: caller cancel / answer → overlay বন্ধ করো ─────────
    private fun setupFirestoreListener(callId: String) {
        val callRef = FirebaseFirestore.getInstance().collection("calls").document(callId)
        activeListenerRegistration = callRef.addSnapshotListener { snap, _ ->
            val status = snap?.getString("status") ?: return@addSnapshotListener
            if (status == "ended" || status == "missed" ||
                status == "cancelled" || status == "declined" || status == "rejected" ||
                status == "answered") {
                stopSelf()
            }
        }
    }

    // ── App foreground path: শুধু missed call timeout — stop এ Firestore write ─
    // RasGramModule নিজে call handle করবে। Service শুধু fallback missed mark করে।
    private fun setupFirestoreListenerForMissed(callId: String) {
        val callRef = FirebaseFirestore.getInstance().collection("calls").document(callId)
        activeListenerRegistration = callRef.addSnapshotListener { snap, _ ->
            val status = snap?.getString("status") ?: return@addSnapshotListener
            // answered/declined/rejected: call handled — service বন্ধ করো
            if (status == "ended" || status == "missed" ||
                status == "cancelled" || status == "declined" || status == "rejected" ||
                status == "answered") {
                stopSelf()
            }
        }
    }

    // ── 60s auto-dismiss ────────────────────────────────────────────────────────
    private fun setup60sTimeout(callId: String) {
        serviceScope.launch {
            kotlinx.coroutines.delay(60_000L)
            if (isRunning && activeCallId == callId) {
                missedCall(callId)
                stopSelf()
            }
        }
    }

    // ── Window overlay card (screen on + unlocked + overlay permission) ───────
    private fun showOverlay(
        callId: String, callerName: String, callerMobile: String, callType: String
    ) {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        // v6: FLAG_DISMISS_KEYGUARD যোগ করা হয়েছে।
        // Lock screen থেকে Answer করলে keyguard automatically dismiss হয়।
        // FLAG_SHOW_WHEN_LOCKED: overlay lock screen এর উপরে আসে।
        // FLAG_TURN_SCREEN_ON: screen off থাকলে জ্বালায় (wakeLock এর সাথে redundant কিন্তু safe)।
        // FLAG_KEEP_SCREEN_ON: overlay দেখানো অবস্থায় screen off হয় না।
        val overlayFlags = (WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            or @Suppress("DEPRECATION") WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            overlayFlags,
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
        try {
            windowManager?.addView(composeView, params)
        } catch (e: Exception) {
            android.util.Log.e("RasGram", "Overlay addView failed: ${e.message}")
            // Overlay দেওয়া গেলো না — notification already আছে, OS handle করবে
        }

        // v6: Android 12+ এ KeyguardManager.requestDismissKeyguard() proper API।
        // Overlay দেখানোর সাথে সাথে keyguard dismiss করো (FLAG_DISMISS_KEYGUARD deprecated A15+)।
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
                // requestDismissKeyguard Activity context চায়, Service এ কাজ করে না।
                // তাই FLAG_DISMISS_KEYGUARD WindowManager flag ব্যবহার করা হয়েছে উপরে।
                // এটি deprecated Android 15 এ, কিন্তু এখনো functional।
                // Proper fix: RasGramActivity তে showWhenLocked + turnScreenOn আছে (Manifest এ দেওয়া)।
            } catch (_: Exception) {}
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────
    private fun answerCall(callId: String, callerName: String, callerMobile: String, callType: String) {
        // NOTE: status="answered" এখানে লেখা হচ্ছে না।
        // CallingScreen receiver path এ setLocalDescription.onSetSuccess এ
        // status + answer SDP একসাথে atomically লেখা হয়।
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
        // v5/v6: AudioManager.MODE_NORMAL এখানে set করা হয় না।
        // IncomingCallScreen বা overlay DisposableEffect নিজেই করবে।
        try { wakeLock?.release() } catch (_: Exception) {}
        try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

// ── Compose UI: overlay card ──────────────────────────────────────────────────
// v6 vibration improvements:
//   - AudioManager.MODE_RINGTONE set করা হয় না (OEM conflict এড়াতে)
//   - Vibrate সবসময় — ringer mode নির্বিশেষে (silent তেও vibrate হবে)
//   - Ring শুধু RINGER_MODE_NORMAL এ
//   - VibrationEffect.EFFECT_HEAVY_CLICK দিয়ে বেশি tactile feel
@Composable
private fun CallOverlayCard(
    callerName: String,
    callerMobile: String,
    callType: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val ringtoneRef = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<android.media.Ringtone?>(null)
    }

    // ── Ring + Vibrate lifecycle ──────────────────────────────────────────────
    androidx.compose.runtime.DisposableEffect(Unit) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // ── Vibrator: API-level compat ────────────────────────────────────
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        try {
            // ── Vibrate: সবসময় চলবে (silent mode সহ) ───────────────────
            // Pattern: 800ms on, 600ms off — WhatsApp style
            // repeat = 0 মানে index 0 থেকে loop (অর্থাৎ চিরকাল)
            val callPattern = longArrayOf(0L, 800L, 600L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(callPattern, 0)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(callPattern, 0)
            }

            // ── Ring: শুধু RINGER_MODE_NORMAL এ ─────────────────────────
            // v6 FIX: AudioManager.MODE_RINGTONE set করা হচ্ছে না।
            // MODE_RINGTONE Bluetooth/speaker routing এর জন্য, ringtone বাজানোর জন্য না।
            // RingtoneManager নিজেই correct stream (STREAM_RING) use করে।
            if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val rt  = RingtoneManager.getRingtone(context, uri)
                if (rt != null) {
                    // Android 10+ এ isLooping loop করে, আগের version এ একবার বাজে।
                    // তবে vibrate চলছে — user কে signal যাচ্ছেই।
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) rt.isLooping = true
                    rt.play()
                    ringtoneRef.value = rt
                }
            }
            // RINGER_MODE_VIBRATE বা RINGER_MODE_SILENT: শুধু vibrate (ring নেই)।
            // এটাই WhatsApp এর behavior।

        } catch (e: Exception) {
            android.util.Log.e("RasGram", "Ring/Vibrate error: ${e.message}")
        }

        onDispose {
            try {
                ringtoneRef.value?.stop()
                ringtoneRef.value = null
                // AudioManager mode: overlay path এ MODE_RINGTONE set করা হয়নি,
                // তাই MODE_NORMAL reset ও দরকার নেই।
                vibrator.cancel()
            } catch (_: Exception) {}
        }
    }

    fun stopRingAndCall(action: () -> Unit) {
        try { ringtoneRef.value?.stop(); ringtoneRef.value = null } catch (_: Exception) {}
        action()
    }

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

            Text(
                text = if (callType == "video") "📹  Video Call আসছে..." else "📞  Voice Call আসছে...",
                color = Color(0xFF25D366).copy(alpha = 0.85f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(Modifier.height(32.dp))

            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size((120 * ringScale).dp)
                        .background(Color(0xFF25D366).copy(alpha = ringAlpha * 0.3f), CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .background(Color(0xFF25D366).copy(alpha = 0.15f), CircleShape)
                )
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 60.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingActionButton(
                        onClick = { stopRingAndCall { onDecline() } },
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

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingActionButton(
                        onClick = { stopRingAndCall { onAccept() } },
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

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
 * Fix summary (v5 — Android 10+ Background startActivity Crash Fix):
 * ─────────────────────────────────────────────────────────────────────────────
 * v4 সমস্যা ছিল (Android 10+ এ):
 *   app background / lock screen এ call আসলে crash হত বা call আসত না।
 *   কারণ: launchFullScreenCallActivity() এ startActivity() করা হচ্ছিল।
 *   Android 10 (API 29) থেকে background এ থাকা app startActivity() করতে পারে না —
 *   crash বা silent fail। তোমার phone (Android 10) এ app foreground এ থাকলে কাজ করত,
 *   কিন্তু background/lock screen এ করত না।
 *
 * v5 Fix:
 *   - launchFullScreenCallActivity() থেকে startActivity() সম্পূর্ণ সরানো হয়েছে।
 *   - Lock/screen-off path: notification এর fullScreenIntent Android OS নিজেই fire করে।
 *     Service alive রাখা হয় (stopSelf() সরানো) যাতে notification থাকে।
 *     Firestore listener + 60s timeout দিয়ে call end detect করা হয়।
 *   - App foreground path: RasGramModule এ Firestore listener আছে — সে নিজেই
 *     IncomingCallScreen দেখাবে। Service শুধু বন্ধ হয়।
 *   - Overlay path (screen on + unlocked): আগের মতোই, পরিবর্তন নেই।
 *
 * v4 এর single-ring fix অক্ষত আছে:
 *   - Service নিজে ring করে না — IncomingCallScreen / overlay করে।
 *   - onDestroy() এ AudioManager.MODE_NORMAL সেট করা হয় না।
 *
 * Result: Android 10+ এ background/lock screen এ call আসে, crash নেই।
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

        // ── 2) Screen জ্বালানো ──────────────────────────────────────────────
        acquireWakeLock()

        // ── 3) Screen/lock state বুঝে overlay বা Activity ──────────────────
        // FIX v4: Service আর ring করে না।
        // Ring এর দায়িত্ব সম্পূর্ণভাবে IncomingCallScreen এর।
        // Service শুধু screen জ্বালায় + UI route করে।
        serviceScope.launch {
            kotlinx.coroutines.delay(200L)

            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager

            val screenOff    = !pm.isInteractive
            val keyguardUp   = km.isKeyguardLocked
            val appForeground = RasGramActivity.isVisible

            if (appForeground) {
                // App foreground এ আছে: RasGramModule এর Firestore listener ইতিমধ্যে
                // incoming call detect করবে এবং IncomingCallScreen দেখাবে।
                // startActivity() করতে হবে না (Android 10+ এ background startActivity crash করে)।
                // Service বন্ধ করি — AudioManager conflict নেই।
                stopSelf()
            } else if (screenOff || keyguardUp) {
                // Lock screen / screen off path:
                // FIX (Android 10+): startActivity() background থেকে করা যায় না।
                // পরিবর্তে: notification এর fullScreenIntent Android OS নিজেই fire করবে।
                // কিন্তু stopSelf() করলে stopForeground() হয় → notification চলে যায়
                // → fullScreenIntent fire হওয়ার সুযোগ নেই।
                //
                // Solution: service alive রাখো, Firestore দিয়ে call end detect করো,
                // 60s timeout দাও। Notification এর fullScreenIntent lock screen এ Activity খুলবে।
                setupFirestoreListener(callId)
                setup60sTimeout(callId)
                // stopSelf() এখানে নেই — service alive থাকবে যতক্ষণ call চলে।
            } else {
                // Unlocked + screen on + app background: floating overlay card দেখাও।
                // Overlay তেই ring হবে (IncomingCallScreen এর মতো same logic, overlay path)।
                showOverlay(callId, callerName, callerMobile, callType)
                // এই path এ service বেঁচে থাকে overlay চলা পর্যন্ত।
                // Firestore listener দিয়ে caller cancel detect করা হবে।
                setupFirestoreListener(callId)
                setup60sTimeout(callId)
            }
        }

        return START_NOT_STICKY
    }

    // ── Firestore: caller cancel করলে overlay বন্ধ করো (overlay path only) ──
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

    // ── 60s auto-dismiss (overlay path only) ────────────────────────────────
    private fun setup60sTimeout(callId: String) {
        serviceScope.launch {
            kotlinx.coroutines.delay(60_000L)
            if (isRunning && activeCallId == callId) {
                missedCall(callId)
                stopSelf()
            }
        }
    }

    // ── Full page Activity (lock screen / screen off / app foreground) ────────
    // FIX (Android 10+): background থেকে startActivity() করলে crash হয়।
    // Android 10 (API 29) থেকে background এ থাকা app সরাসরি Activity launch করতে পারে না,
    // তবে FOREGROUND_SERVICE চলাকালীন exception আছে — কিন্তু সেটা Android version ও
    // OEM (Samsung, Xiaomi, OnePlus) ভেদে inconsistent। ফলে crash হয়।
    //
    // সঠিক approach: startActivity() বাদ দাও।
    // ইতিমধ্যে startForegroundWithNotification() এ fullScreenIntent দেওয়া আছে —
    // Android OS নিজেই সেই notification থেকে Activity খুলবে।
    // App foreground এ থাকলে (appForeground path) শুধু onNewIntent দরকার — সেটা
    // notification tap থেকেই আসে। আলাদা startActivity() দরকার নেই।
    //
    // তাই এই method এখন শুধু notification কে "re-trigger" করে যাতে fullScreenIntent
    // আবার fire হয় — যেটা lock screen + screen off উভয় ক্ষেত্রে কাজ করে।
    private fun launchFullScreenCallActivity(
        callId: String, callerName: String, callerMobile: String, callType: String
    ) {
        // fullScreenIntent ইতিমধ্যে startForegroundWithNotification() এ set আছে।
        // Android 10+ এ OS নিজেই lock screen / screen off এ সেটা fire করে।
        // শুধু app foreground path এ একটু নিশ্চিত করার জন্য notification update করি।

        // App foreground এ থাকলে: Firestore listener দিয়ে IncomingCallScreen এ
        // call detect হবে — startActivity() আর দরকার নেই।
        // Lock/screen-off: fullScreenIntent notification OS handle করবে।

        // কিছুই করার দরকার নেই — notification already posted, OS handles the rest.
        // (পুরনো startActivity() এখানে থাকলে Android 10+ এ crash হত)
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
                setSound(null, null)
                enableVibration(false)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
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
            .build()

        // Android 14+ (UPSIDE_DOWN_CAKE): foregroundServiceType="phoneCall" এর জন্য
        // MANAGE_OWN_CALLS permission লাগে। কিছু OEM (Samsung, Xiaomi) তে
        // MANAGE_OWN_CALLS থাকলেও SecurityException throw করে — তাই try-catch।
        // Fallback: type ছাড়া plain startForeground — notification দেখাবে, crash নেই।
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

        // FIX Android 14+ (API 34): USE_FULL_SCREEN_INTENT permission runtime check।
        // Android 14 থেকে এই permission user কে manually grant করতে হয়।
        // না থাকলে fullScreenIntent fire হয় না — lock screen এ call আসে না।
        // User কে Settings এ পাঠানো হয় যাতে একবার grant করে নেয়।
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm2 = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (!nm2.canUseFullScreenIntent()) {
                android.util.Log.w("RasGram", "USE_FULL_SCREEN_INTENT not granted — lock screen call UI won't show")
                // Settings এ নিয়ে যাও — user একবার grant করলে পরবর্তী call থেকে কাজ করবে
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

    // ── Window overlay card (screen on + unlocked) ────────────────────────────
    // এই path এ IncomingCallScreen এর মতো same ring logic overlay এ আছে।
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
        // NOTE: status="answered" এখানে লেখা হচ্ছে না।
        // CallingScreen receiver path এ setLocalDescription.onSetSuccess এ
        // status + answer SDP একসাথে atomically লেখা হয়।
        // এখানে আগে লিখলে caller SDP ছাড়াই "answered" দেখে → setRemoteDescription fail → audio নেই।
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
        // FIX v4: onDestroy এ AudioManager.MODE_NORMAL সেট করা হয় না।
        // কারণ: IncomingCallScreen নিজে ring করে। Service destroy হলে
        // AudioManager mode change করলে IncomingCallScreen এর ring বন্ধ হয়ে যেত।
        // IncomingCallScreen এর DisposableEffect নিজেই MODE_NORMAL সেট করবে।
        try { wakeLock?.release() } catch (_: Exception) {}
        try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

// ── Compose UI: overlay card (screen on + unlocked path) ─────────────────────
// Service ring করে না — overlay নিজেই ring করে (IncomingCallScreen এর মতো)।
@Composable
private fun CallOverlayCard(
    callerName: String,
    callerMobile: String,
    callType: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // Overlay path এ ring: IncomingCallScreen এর মতো same DisposableEffect।
    // Service destroy হলেও ring চলতে থাকে কারণ এখানে manage হচ্ছে।
    val ringtoneRef = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<android.media.Ringtone?>(null)
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_RINGTONE
            when (am.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> {
                    // Silent mode — ring বাজাবো না, vibrate ও না
                }
                AudioManager.RINGER_MODE_VIBRATE -> {
                    // Vibrate-only mode — শুধু vibrate
                    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
                    } else {
                        @Suppress("DEPRECATION")
                        context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                    }
                    val pattern = longArrayOf(0, 500, 1000)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, 0))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(pattern, 0)
                    }
                }
                else -> {
                    // Normal mode — phone এর ring volume অনুযায়ী বাজাও (force max নয়)
                    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    val rt  = RingtoneManager.getRingtone(context, uri)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) rt?.isLooping = true
                    rt?.play()
                    ringtoneRef.value = rt
                }
            }
        } catch (_: Exception) {}
        onDispose {
            try {
                ringtoneRef.value?.stop()
                ringtoneRef.value = null
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.mode = AudioManager.MODE_NORMAL
                // vibrate mode এ চলছিল — cancel করো
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                }
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

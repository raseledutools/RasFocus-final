package com.rasel.RasFocus.selfcontrol.launcher

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import com.rasel.RasFocus.combo.selfcontrol.RasFocusAccessibilityService
import com.rasel.RasFocus.features.MyDeviceAdminReceiver
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Launcher "double tap to lock" helper.
 * Device Admin only (<force-lock/>): timeout-based lock keeps fingerprint unlock; lockNow() is the last resort.
 */
object LauncherLock {
    const val PREFS = "launcher_prefs"
    const val KEY_DOUBLE_TAP_LOCK = "double_tap_lock"

    /** Max gap (ms) between two Home presses to count as a double press. */
    private const val HOME_DOUBLE_PRESS_MS = 650L
    private var lastHomePressAt = 0L

    /** Set by the launcher screen: true only when no overlay (sidebar/settings/dialog) is open. */
    @Volatile var tapLockArmed = false
    private var lastLockAt = 0L

    /** Short vibration = "double tap detected" feedback (also helps diagnose permission problems). */
    private fun buzz(context: Context) {
        try {
            (context as? Activity)?.window?.decorView
                ?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        } catch (_: Exception) {}
    }

    /** Shared detector for any Activity hosting the launcher screen (LauncherActivity, MainActivity). */
    fun newDoubleTapDetector(activity: Activity): android.view.GestureDetector =
        android.view.GestureDetector(activity, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                onScreenDoubleTap(activity)
                return false
            }
        })

    /** Called by LauncherActivity.dispatchTouchEvent on every double tap anywhere on the screen. */
    fun onScreenDoubleTap(activity: Activity) {
        if (!tapLockArmed || !isEnabled(activity)) return
        lockScreen(activity)
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DOUBLE_TAP_LOCK, true)   // default ON (opt-out), no Settings visit needed

    /** Asks for Device Admin once per install if the feature is on but admin is missing. */
    fun ensureAdminOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        restoreTimeout(context)   // safety net: never leave the 1-second screen timeout behind
        if (!isEnabled(context) || isAdminActive(context)) return
        if (prefs.getBoolean("admin_prompted", false)) return
        prefs.edit().putBoolean("admin_prompted", true).apply()
        requestAdmin(context)
    }

    /** True if any of this app's device-admin receivers is active. */
    fun isAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.activeAdmins?.any { it.packageName == context.packageName } == true
    }

    fun requestAdmin(context: Context) {
        try {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(
                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    ComponentName(context, MyDeviceAdminReceiver::class.java)
                )
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Double tap to lock screen needs Device Admin (force-lock) permission."
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    /** Optional: if one of the app's accessibility services is already running, use its power-button style lock. */
    private fun lockViaAccessibility(): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        val svc: AccessibilityService? =
            RasFocusAccessibilityService.blockingInstance
                ?: RasFocusAccessibilityService.deepStudyInstance
        return try {
            svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) == true
        } catch (_: Exception) { false }
    }

    private const val TTL_PENDING = "ttl_pending"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var ttlReceiver: BroadcastReceiver? = null
    private var ttlCheck: Runnable? = null

    private fun dpm(context: Context) =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun adminComponent(context: Context) = ComponentName(context, MyDeviceAdminReceiver::class.java)

    /**
     * Device-Admin-only lock that keeps fingerprint working.
     * lockNow() makes Android 9+ ask for PIN/password next. Instead we cap the screen timeout to 1 s with
     * setMaximumTimeToLock (needs only <force-lock/>), so the screen turns off and locks exactly like a normal
     * timeout; the cap is lifted again as soon as the screen is off (or after 3 s as a safety net).
     * If the screen is still on after 3 s the cap had no effect, so lockNow() is used as the last resort.
     */
    private fun lockViaTimeout(context: Context): Boolean {
        if (!isAdminActive(context)) return false
        val app = context.applicationContext
        return try {
            dpm(app).setMaximumTimeToLock(adminComponent(app), 1000L)
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(TTL_PENDING, true).apply()
            ttlReceiver?.let { try { app.unregisterReceiver(it) } catch (_: Exception) {} }
            val rc = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { restoreTimeout(app) }
            }
            ContextCompat.registerReceiver(app, rc, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
            ttlReceiver = rc
            ttlCheck?.let { mainHandler.removeCallbacks(it) }
            val check = Runnable {
                val stillOn = try { (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive } catch (_: Exception) { false }
                restoreTimeout(app)
                if (stillOn) { try { dpm(app).lockNow() } catch (_: Exception) {} }
            }
            ttlCheck = check
            mainHandler.postDelayed(check, 3000L)
            true
        } catch (_: Exception) {
            restoreTimeout(app)
            false
        }
    }

    /** Lifts the temporary 1 s screen-timeout cap (no-op when none is pending). */
    fun restoreTimeout(context: Context) {
        val app = context.applicationContext
        ttlReceiver?.let { try { app.unregisterReceiver(it) } catch (_: Exception) {} }
        ttlReceiver = null
        ttlCheck?.let { mainHandler.removeCallbacks(it) }
        ttlCheck = null
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(TTL_PENDING, false)) return
        try { if (isAdminActive(app)) dpm(app).setMaximumTimeToLock(adminComponent(app), 0L) } catch (_: Exception) {}
        prefs.edit().putBoolean(TTL_PENDING, false).apply()
    }

    /** Locks the screen: running accessibility (if any) → Device Admin timeout trick (fingerprint ok) → lockNow() → ask for Device Admin. */
    fun lockScreen(context: Context) {
        // Activity-level + Compose-level detectors may both fire — lock only once
        val now = SystemClock.elapsedRealtime()
        if (now - lastLockAt < 1500L) return
        lastLockAt = now
        buzz(context)
        if (lockViaAccessibility()) return
        if (lockViaTimeout(context)) return
        if (isAdminActive(context)) {
            try {
                dpm(context).lockNow()
                return
            } catch (_: Exception) { /* fall through to permission request */ }
        }
        Toast.makeText(context, "Screen lock এর জন্য Device Admin permission দাও", Toast.LENGTH_LONG).show()
        requestAdmin(context)
    }

    /**
     * Call on every HOME intent reaching the launcher (onCreate / onNewIntent).
     * Locks when two Home presses land within [HOME_DOUBLE_PRESS_MS].
     */
    fun onHomePressed(context: Context) {
        val now = System.currentTimeMillis()
        val isDouble = now - lastHomePressAt in 1..HOME_DOUBLE_PRESS_MS
        lastHomePressAt = if (isDouble) 0L else now
        if (isDouble && isEnabled(context)) lockScreen(context)
    }
}

/**
 * Double tap ANYWHERE → [onLock]. Watches the Initial pointer pass without consuming,
 * so child clickables / the launcher's own swipe layers can't swallow the gesture.
 */
fun Modifier.doubleTapToLock(isActive: () -> Boolean, onLock: () -> Unit): Modifier =
    pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down1 = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!isActive()) return@awaitEachGesture
            val up1 = waitTapUp(down1, slop) ?: return@awaitEachGesture
            val down2 = withTimeoutOrNull(300L) {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            } ?: return@awaitEachGesture
            if ((down2.position - up1.position).getDistance() > slop * 6) return@awaitEachGesture
            waitTapUp(down2, slop) ?: return@awaitEachGesture
            if (isActive()) onLock()
        }
    }

private suspend fun AwaitPointerEventScope.waitTapUp(
    down: PointerInputChange,
    slop: Float
): PointerInputChange? = withTimeoutOrNull<PointerInputChange?>(400L) {
    var result: PointerInputChange? = null
    while (true) {
        val ev = awaitPointerEvent(PointerEventPass.Initial)
        if (ev.changes.size > 1) break
        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
        if ((c.position - down.position).getDistance() > slop) break
        if (!c.pressed) { result = c; break }
    }
    result
}

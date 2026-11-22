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
 * Lock via a 1 s screen-timeout (keeps fingerprint unlock, needs "Modify system settings"); Device Admin lockNow() is the last resort.
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

    /**
     * Once per install: ask for "Modify system settings" (needed for the fingerprint-friendly lock);
     * Device Admin is only the last-resort fallback and is asked for on a later launch.
     */
    fun ensureAdminOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        cleanupOldDpmCap(context)
        restoreTimeout(context)   // safety net: never leave the 1-second screen timeout behind
        if (!isEnabled(context) || canWriteSettings(context)) return
        if (!prefs.getBoolean("write_prompted", false)) {
            prefs.edit().putBoolean("write_prompted", true).apply()
            requestWriteSettings(context)
            return
        }
        if (isAdminActive(context) || prefs.getBoolean("admin_prompted", false)) return
        prefs.edit().putBoolean("admin_prompted", true).apply()
        requestAdmin(context)
    }

    fun canWriteSettings(context: Context): Boolean =
        try { android.provider.Settings.System.canWrite(context) } catch (_: Exception) { false }

    /** Opens the "Modify system settings" page for this app. */
    fun requestWriteSettings(context: Context) {
        try {
            Toast.makeText(context, "Fingerprint দিয়ে unlock এর জন্য \"Modify system settings\" চালু করো", Toast.LENGTH_LONG).show()
            context.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    .setData(android.net.Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {}
    }

    /** An earlier build capped the timeout via Device Admin; make sure no such cap is left behind (runs once). */
    private fun cleanupOldDpmCap(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("dpm_cap_cleaned", false)) return
        prefs.edit().putBoolean("dpm_cap_cleaned", true).apply()
        try { if (isAdminActive(context)) dpm(context).setMaximumTimeToLock(adminComponent(context), 0L) } catch (_: Exception) {}
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

    private const val KEY_ORIG_TIMEOUT = "orig_screen_timeout"   // -1 = nothing pending
    private const val TEMP_TIMEOUT_MS = 1000
    private val mainHandler = Handler(Looper.getMainLooper())
    private var ttlReceiver: BroadcastReceiver? = null
    private var ttlCheck: Runnable? = null

    private fun dpm(context: Context) =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun adminComponent(context: Context) = ComponentName(context, MyDeviceAdminReceiver::class.java)

    /**
     * Fingerprint-friendly lock without Accessibility.
     * DevicePolicyManager.lockNow() makes Android 9+ demand PIN/password next, so instead the system
     * screen-off timeout is lowered to 1 s (needs "Modify system settings"). The screen then turns off and
     * locks exactly like a normal timeout, and the original timeout is put back as soon as the screen is off.
     * Returns false when the permission is missing; the caller then falls back to lockNow().
     */
    private fun lockViaTimeout(context: Context): Boolean {
        if (!canWriteSettings(context)) return false
        val app = context.applicationContext
        return try {
            val cr = app.contentResolver
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getInt(KEY_ORIG_TIMEOUT, -1) < 0) {
                val orig = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, 60000)
                prefs.edit().putInt(KEY_ORIG_TIMEOUT, if (orig == TEMP_TIMEOUT_MS) 60000 else orig).apply()
            }
            if (!android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, TEMP_TIMEOUT_MS)) {
                restoreTimeout(app); return false
            }
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
                if (stillOn) {   // this phone ignored the 1 s timeout → last resort (PIN will be asked)
                    Toast.makeText(app, "Ei phone e timeout lock kaj korena, PIN lagbe", Toast.LENGTH_LONG).show()
                    try { dpm(app).lockNow() } catch (_: Exception) {}
                }
            }
            ttlCheck = check
            mainHandler.postDelayed(check, 4000L)
            true
        } catch (_: Exception) {
            restoreTimeout(app)
            false
        }
    }

    /** Puts the original screen timeout back (no-op when none is pending). Safe to call any time. */
    fun restoreTimeout(context: Context) {
        val app = context.applicationContext
        ttlReceiver?.let { try { app.unregisterReceiver(it) } catch (_: Exception) {} }
        ttlReceiver = null
        ttlCheck?.let { mainHandler.removeCallbacks(it) }
        ttlCheck = null
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val orig = prefs.getInt(KEY_ORIG_TIMEOUT, -1)
        if (orig < 0) return
        try {
            val cr = app.contentResolver
            // only undo our own change — if the user changed the timeout meanwhile, leave it alone
            if (android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, orig) == TEMP_TIMEOUT_MS)
                android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, orig)
        } catch (_: Exception) {}
        prefs.edit().putInt(KEY_ORIG_TIMEOUT, -1).apply()
    }

    /** Locks: running accessibility (if any) → 1 s timeout trick (fingerprint ok) → lockNow() (PIN) → ask for permission. */
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
                Toast.makeText(context, "Fingerprint এ খুলতে \"Modify system settings\" চালু করো (এখন PIN লাগবে)", Toast.LENGTH_LONG).show()
                return
            } catch (_: Exception) { /* fall through to permission request */ }
        }
        requestWriteSettings(context)
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

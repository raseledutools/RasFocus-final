package com.rasel.RasFocus.selfcontrol.launcher

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.accessibilityservice.AccessibilityService
import android.os.Build
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
 * Uses DevicePolicyManager.lockNow() — device_admin.xml already declares <force-lock />.
 */
object LauncherLock {
    const val PREFS = "launcher_prefs"
    const val KEY_DOUBLE_TAP_LOCK = "double_tap_lock"

    /** Max gap (ms) between two Home presses to count as a double press. */
    private const val HOME_DOUBLE_PRESS_MS = 450L
    private var lastHomePressAt = 0L

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DOUBLE_TAP_LOCK, false)

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

    /** Accessibility GLOBAL_ACTION_LOCK_SCREEN (API 28+) — needs no Device Admin. */
    private fun lockViaAccessibility(): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        val svc: AccessibilityService? =
            RasFocusAccessibilityService.blockingInstance
                ?: RasFocusAccessibilityService.deepStudyInstance
        return try {
            svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) == true
        } catch (_: Exception) { false }
    }

    /** Locks the screen: Device Admin first, Accessibility as fallback, else asks for permission. */
    fun lockScreen(context: Context) {
        if (isAdminActive(context)) {
            try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                dpm.lockNow()
                return
            } catch (_: Exception) { /* fall through to fallback */ }
        }
        if (lockViaAccessibility()) return
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

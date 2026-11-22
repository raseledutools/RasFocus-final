package com.rasel.RasFocus.selfcontrol.launcher

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.rasel.RasFocus.features.MyDeviceAdminReceiver

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

    /** Locks the screen. If admin isn't granted yet, asks for it instead. */
    fun lockScreen(context: Context) {
        if (!isAdminActive(context)) {
            Toast.makeText(context, "Screen lock এর জন্য Device Admin permission দাও", Toast.LENGTH_SHORT).show()
            requestAdmin(context)
            return
        }
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.lockNow()
        } catch (_: SecurityException) {
            requestAdmin(context)
        } catch (_: Exception) {}
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

package com.rasel.RasFocus.selfcontrol.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/**
 * Tiny accessibility service whose only job is GLOBAL_ACTION_LOCK_SCREEN (API 28+).
 * That action locks exactly like the power button, so fingerprint / face unlock keeps working —
 * unlike DevicePolicyManager.lockNow(), which makes Android demand PIN/pattern/password next.
 * It listens to nothing and reads no screen content.
 */
class LockScreenAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: LockScreenAccessibilityService? = null

        /** True once the user has switched this service on (it is bound and running). */
        fun isRunning(): Boolean = instance != null

        /** Lock like the power button. Returns false when the service is off or the OS is too old. */
        fun lock(): Boolean {
            if (Build.VERSION.SDK_INT < 28) return false
            val svc = instance ?: return false
            return try {
                svc.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            } catch (_: Exception) { false }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* intentionally empty */ }

    override fun onInterrupt() { /* nothing to interrupt */ }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}

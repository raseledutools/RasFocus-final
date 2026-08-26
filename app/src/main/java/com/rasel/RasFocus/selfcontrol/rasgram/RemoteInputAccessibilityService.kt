package com.rasel.RasFocus.selfcontrol.rasgram

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * RemoteInputAccessibilityService
 *
 * Screen share + input access ফিচারের জন্য।
 * Remote peer এর touch event receive করলে এই service সেটা inject করে।
 *
 * ব্যবহার:
 *   1. User যখন "Share Screen with Input Access" চাপে →
 *      isServiceEnabled(context) চেক করো
 *   2. না থাকলে → openAccessibilitySettings(context) → user manually enable করবে
 *   3. থাকলে → remote peer touch event পাঠালে injectTouch() call হয়
 *
 * injectTouch(normX, normY, action):
 *   - normX/normY: 0.0–1.0 (screen percentage, sender পাঠায়)
 *   - action: MotionEvent.ACTION_DOWN / ACTION_MOVE / ACTION_UP
 *   - API 24+ এ dispatchGesture() দিয়ে inject করা হয়
 *   - নিচের version এ GlobalAction দিয়ে limited support
 */
class RemoteInputAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RemoteInputA11y"

        // Singleton reference — inject করার জন্য
        @Volatile private var instance: RemoteInputAccessibilityService? = null

        private val _isActive = MutableStateFlow(false)
        val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

        /** Check whether the Accessibility Service is enabled in system settings */
        fun isServiceEnabled(context: Context): Boolean {
            val componentName = "${context.packageName}/${RemoteInputAccessibilityService::class.java.name}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.split(":").any { it.equals(componentName, ignoreCase = true) }
        }

        /** Opens Accessibility Settings so user can enable this service */
        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }

        /**
         * Called by ScreenShareManager when a remote touch event arrives.
         * @param normX  0.0–1.0 X (relative to sharer's screen width)
         * @param normY  0.0–1.0 Y (relative to sharer's screen height)
         * @param motionAction  MotionEvent.ACTION_DOWN / ACTION_MOVE / ACTION_UP
         */
        fun injectTouch(normX: Float, normY: Float, motionAction: Int) {
            val svc = instance ?: run {
                Log.w(TAG, "Service not running — cannot inject touch")
                return
            }
            svc.doInjectTouch(normX, normY, motionAction)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isActive.value = true
        Log.i(TAG, "RemoteInputAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
            _isActive.value = false
        }
        Log.i(TAG, "RemoteInputAccessibilityService destroyed")
    }

    private fun doInjectTouch(normX: Float, normY: Float, motionAction: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            // API 23 and below: only global actions available
            if (motionAction == MotionEvent.ACTION_UP) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            return
        }
        injectGestureApi24(normX, normY, motionAction)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun injectGestureApi24(normX: Float, normY: Float, motionAction: Int) {
        try {
            // Screen dimensions থেকে absolute coords বের করো
            val metrics = resources.displayMetrics
            val absX = normX * metrics.widthPixels
            val absY = normY * metrics.heightPixels

            when (motionAction) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP -> {
                    val path = Path().apply { moveTo(absX, absY) }
                    val stroke = GestureDescription.StrokeDescription(
                        path,
                        0L,
                        if (motionAction == MotionEvent.ACTION_DOWN) 100L else 50L
                    )
                    val gesture = GestureDescription.Builder().addStroke(stroke).build()
                    dispatchGesture(gesture, null, null)
                }
                MotionEvent.ACTION_MOVE -> {
                    // MOVE events: short stroke from last position
                    val path = Path().apply { moveTo(absX, absY); lineTo(absX + 1f, absY + 1f) }
                    val stroke = GestureDescription.StrokeDescription(path, 0L, 20L)
                    val gesture = GestureDescription.Builder().addStroke(stroke).build()
                    dispatchGesture(gesture, null, null)
                }
            }
            Log.d(TAG, "Injected touch: ($absX, $absY) action=$motionAction")
        } catch (e: Exception) {
            Log.e(TAG, "injectGesture error: ${e.message}")
        }
    }
}

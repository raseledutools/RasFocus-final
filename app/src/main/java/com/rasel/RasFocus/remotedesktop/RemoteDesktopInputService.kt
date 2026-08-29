package com.rasel.RasFocus.remotedesktop

/**
 * RemoteDesktopInputService
 * Inspired by RustDesk InputService.kt (MIT License)
 * https://github.com/rustdesk/rustdesk
 *
 * AccessibilityService দিয়ে PC-র mouse/touch/key inject করে phone-এ।
 * RustDesk: rustPointerInput() → dispatchGesture() / injectGesture()
 */

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RemoteDesktopInputService : AccessibilityService() {

    companion object {
        private const val TAG = "RDInputService"

        // ── pointer mask constants (RustDesk: POINTER_MASK_*) ──
        const val MASK_MOVE        = 0
        const val MASK_DOWN        = 1
        const val MASK_UP          = 2
        const val MASK_SCROLL_DOWN = 3
        const val MASK_SCROLL_UP   = 4
        const val MASK_LONG_PRESS  = 5

        private val _isEnabled = MutableStateFlow(false)
        val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

        private var instance: RemoteDesktopInputService? = null
        fun getInstance() = instance

        // Called from RemoteDesktopService WebSocket onMessage
        fun onPointer(mask: Int, x: Int, y: Int) {
            instance?.handlePointer(mask, x, y) ?: Log.w(TAG, "InputService not running")
        }
        fun onKey(keyCode: Int, action: Int) {
            instance?.handleKey(keyCode, action)
        }
        fun onScroll(x: Int, y: Int, dir: String) {
            instance?.handleScroll(x, y, dir)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // Track ongoing swipe for drag support (RustDesk: mMotionEventDown)
    private var isDown = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isEnabled.value = true
        Log.d(TAG, "RemoteDesktopInputService connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        _isEnabled.value = false
        Log.d(TAG, "RemoteDesktopInputService unbound")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ── Pointer handling (RustDesk: rustPointerInput) ────────────────────────
    private fun handlePointer(mask: Int, x: Int, y: Int) {
        mainHandler.post {
            when (mask) {
                MASK_DOWN -> {
                    isDown = true
                    downX = x.toFloat(); downY = y.toFloat()
                    downTime = SystemClock.uptimeMillis()
                    performTap(x.toFloat(), y.toFloat(), 1L)
                }
                MASK_UP -> {
                    if (isDown) {
                        val dx = x - downX; val dy = y - downY
                        val dist = Math.sqrt((dx * dx + dy * dy).toDouble())
                        if (dist > 10) {
                            // Swipe/drag
                            performSwipe(downX, downY, x.toFloat(), y.toFloat(),
                                SystemClock.uptimeMillis() - downTime)
                        }
                        isDown = false
                    }
                }
                MASK_MOVE -> {
                    // Ongoing drag — no action needed for simple tap navigation
                }
                MASK_SCROLL_DOWN -> performScroll(x.toFloat(), y.toFloat(), "down")
                MASK_SCROLL_UP   -> performScroll(x.toFloat(), y.toFloat(), "up")
                MASK_LONG_PRESS  -> performLongPress(x.toFloat(), y.toFloat())
            }
        }
    }

    // ── Tap (RustDesk: dispatchGesture click) ────────────────────────────────
    private fun performTap(x: Float, y: Float, durationMs: Long = 50L) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    // ── Long press ───────────────────────────────────────────────────────────
    private fun performLongPress(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 600L)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    // ── Swipe / drag ─────────────────────────────────────────────────────────
    private fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val dur = durationMs.coerceIn(50, 800)
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0, dur)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    // ── Scroll ───────────────────────────────────────────────────────────────
    private fun performScroll(x: Float, y: Float, dir: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val delta = 400f
        val (x1, y1, x2, y2) = when (dir) {
            "down" -> arrayOf(x, y, x, y - delta)
            "up"   -> arrayOf(x, y, x, y + delta)
            "left" -> arrayOf(x, y, x + delta, y)
            "right"-> arrayOf(x, y, x - delta, y)
            else   -> arrayOf(x, y, x, y - delta)
        }
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 200L)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun handleScroll(x: Int, y: Int, dir: String) {
        mainHandler.post { performScroll(x.toFloat(), y.toFloat(), dir) }
    }

    // ── Key events (RustDesk: rustKeyEventInput → injectKey) ─────────────────
    private fun handleKey(keyCode: Int, action: Int) {
        mainHandler.post {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK  -> performGlobalAction(GLOBAL_ACTION_BACK)
                KeyEvent.KEYCODE_HOME  -> performGlobalAction(GLOBAL_ACTION_HOME)
                KeyEvent.KEYCODE_APP_SWITCH -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                KeyEvent.KEYCODE_NOTIFICATION -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                }
                else -> Log.d(TAG, "Key: $keyCode action=$action (not mapped)")
            }
        }
    }
}

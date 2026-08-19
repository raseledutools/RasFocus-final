package com.rasel.RasFocus.selfcontrol.rasgram

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rasel.RasFocus.ui.theme.RasFocusAppTheme

class RasGramActivity : ComponentActivity() {

    // Incoming call extras (passed via FCM notification tap)
    var incomingCallId: String? = null
    var incomingCallerMobile: String? = null
    var incomingCallerName: String? = null
    var incomingCallType: String? = null
    var isIncomingCall: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent()

        // Lock screen bypass — show call UI over lock screen (WhatsApp style)
        if (isIncomingCall) {
            enableLockScreenDisplay()
        }

        setContent {
            RasFocusAppTheme {
                RasGramApp(
                    incomingCallId = if (isIncomingCall) incomingCallId else null,
                    incomingCallerMobile = if (isIncomingCall) incomingCallerMobile else null,
                    incomingCallerName = if (isIncomingCall) incomingCallerName else null,
                    incomingCallType = if (isIncomingCall) incomingCallType else null
                )
            }
        }
    }

    private fun enableLockScreenDisplay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // API 27+ — use Activity flags
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(KeyguardManager::class.java)
            km?.requestDismissKeyguard(this, null)
        } else {
            // API < 27 — use Window flags (deprecated but still needed for old devices)
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent()
        if (isIncomingCall) {
            enableLockScreenDisplay()
        }
    }

    private fun handleIncomingIntent() {
        val action = intent?.action
        if (action == "ACTION_INCOMING_CALL" || action == "ACTION_ANSWER_CALL") {
            isIncomingCall = true
            incomingCallId = intent.getStringExtra("callId")
            incomingCallerMobile = intent.getStringExtra("callerMobile")
            incomingCallerName = intent.getStringExtra("callerName")
            incomingCallType = intent.getStringExtra("callType") ?: "audio"
        } else {
            isIncomingCall = false
        }
    }
}


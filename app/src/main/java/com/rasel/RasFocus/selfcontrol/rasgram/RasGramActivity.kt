package com.rasel.RasFocus.selfcontrol.rasgram

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rasel.RasFocus.ui.theme.RasFocusAppTheme

class RasGramActivity : ComponentActivity() {

    var incomingCallId: String?       = null
    var incomingCallerMobile: String? = null
    var incomingCallerName: String?   = null
    var incomingCallType: String?     = null
    var isIncomingCall: Boolean       = false
    var openChatWith: String?         = null   // notification tap → direct chat open

    companion object {
        // RasgramMessagingService এই flag দেখে foreground check করে।
        // onResume → true, onStop → false।
        // এটাই একমাত্র reliable way — runningAppProcesses Android 11+ এ broken।
        @Volatile var isVisible: Boolean = false
    }

    override fun onResume() {
        super.onResume()
        isVisible = true
    }

    override fun onStop() {
        super.onStop()
        isVisible = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent()

        if (isIncomingCall) {
            enableLockScreenDisplay()
            stopOverlayService()
        }

        setContent {
            RasFocusAppTheme {
                RasGramApp(
                    incomingCallId       = if (isIncomingCall) incomingCallId       else null,
                    incomingCallerMobile = if (isIncomingCall) incomingCallerMobile else null,
                    incomingCallerName   = if (isIncomingCall) incomingCallerName   else null,
                    incomingCallType     = if (isIncomingCall) incomingCallType     else null,
                    openChatWithMobile   = openChatWith
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent()
        if (isIncomingCall) {
            enableLockScreenDisplay()
            stopOverlayService()
        }
    }

    // ── Lock screen bypass ────────────────────────────────────────────────────
    private fun enableLockScreenDisplay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)
                ?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED  or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON    or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD  or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    // ── Activity খুললে overlay service এর ring বন্ধ করো ─────────────────────
    // দুটো ক্ষেত্রে দরকার:
    //   1) Screen off/locked ছিল → launchFullScreenCallActivity() Activity খুলেছে
    //      → service এখনো চলছে, ring করছে → stop দরকার
    //   2) User notification tap করে এলে (fallback path) → service নাও থাকতে পারে
    //      → stopService নো-অপ, কোনো সমস্যা নেই
    private fun stopOverlayService() {
        IncomingCallOverlayService.stop(this)
    }

    private fun handleIncomingIntent() {
        val action = intent?.action
        if (action == "ACTION_INCOMING_CALL" || action == "ACTION_ANSWER_CALL") {
            isIncomingCall       = true
            incomingCallId       = intent.getStringExtra("callId")
            incomingCallerMobile = intent.getStringExtra("callerMobile")
            incomingCallerName   = intent.getStringExtra("callerName")
            incomingCallType     = intent.getStringExtra("callType") ?: "audio"
            openChatWith         = null
        } else {
            isIncomingCall = false
            // Message notification tap → direct chat open
            openChatWith = intent?.getStringExtra("openChatWith")
        }
    }
}

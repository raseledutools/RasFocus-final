package com.rasel.RasFocus.selfcontrol.rasgram

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import com.google.firebase.firestore.FirebaseFirestore
import com.rasel.RasFocus.ui.theme.RasFocusAppTheme

class RasGramActivity : ComponentActivity() {

    private var incomingCallId: String?     = null
    private var incomingCallerMobile: String? = null
    private var incomingCallerName: String? = null
    private var incomingCallType: String?   = null
    private var isIncomingCall: Boolean     = false
    private var openChatWith: String?       = null

    companion object {
        // RasgramMessagingService foreground check এর জন্য।
        // onResume → true, onStop → false।
        @Volatile var isVisible: Boolean = false
    }

    override fun onResume() { super.onResume(); isVisible = true }
    override fun onStop()   { super.onStop();   isVisible = false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        parseIntent(intent)

        if (isIncomingCall) {
            enableLockScreenDisplay()
            IncomingCallOverlayService.stop(this)
        }

        setContent {
            RasFocusAppTheme {
                if (isIncomingCall) {
                    // ── Lock screen / killed path ────────────────────────────
                    // Splash নেই, MainScreen নেই।
                    // SharedPreferences থেকে current user তুলে সরাসরি
                    // IncomingCallScreen দেখাও, তারপর accept হলে CallingScreen।
                    DirectCallUI(
                        callId       = incomingCallId       ?: "",
                        callerName   = incomingCallerName   ?: "Unknown",
                        callerMobile = incomingCallerMobile ?: "",
                        callType     = incomingCallType     ?: "audio",
                        onCallEnded  = { finish() }
                    )
                } else {
                    // ── Normal app open / message notification tap ───────────
                    RasGramApp(openChatWithMobile = openChatWith)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseIntent(intent)
        if (isIncomingCall) {
            enableLockScreenDisplay()
            IncomingCallOverlayService.stop(this)
        }
    }

    private fun parseIntent(i: Intent?) {
        val action = i?.action
        if (action == "ACTION_INCOMING_CALL" || action == "ACTION_ANSWER_CALL") {
            isIncomingCall       = true
            incomingCallId       = i.getStringExtra("callId")
            incomingCallerMobile = i.getStringExtra("callerMobile")
            incomingCallerName   = i.getStringExtra("callerName")
            incomingCallType     = i.getStringExtra("callType") ?: "audio"
            openChatWith         = null
        } else {
            isIncomingCall = false
            openChatWith   = i?.getStringExtra("openChatWith")
        }
    }

    // Lock screen উপরে Activity দেখানো
    private fun enableLockScreenDisplay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)
                ?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON   or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }
}

// ── DirectCallUI ──────────────────────────────────────────────────────────────
// App open / splash ছাড়াই সরাসরি IncomingCallScreen → CallingScreen।
// SharedPreferences থেকে logged-in user তুলে নেয়।
@Composable
private fun DirectCallUI(
    callId: String,
    callerName: String,
    callerMobile: String,
    callType: String,
    onCallEnded: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs   = remember { context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) }

    // SharedPreferences থেকে current user — Firestore round-trip ছাড়াই instant
    val currentUser = remember {
        val mobile = prefs.getString(PREF_MOBILE, "") ?: ""
        val name   = prefs.getString(PREF_NAME_KEY, "") ?: ""
        val uid    = prefs.getString(PREF_UID, "") ?: ""
        val avatar = prefs.getString(PREF_AVATAR, "") ?: ""
        User(uid = uid, name = name, mobile = mobile, avatarUrl = avatar)
    }

    // State machine: incoming → calling → ended
    var phase by remember { mutableStateOf(if (callId.isNotEmpty()) "incoming" else "ended") }
    var acceptedCallId by remember { mutableStateOf(callId) }

    when (phase) {
        "incoming" -> IncomingCallScreen(
            currentUser  = currentUser,
            callerName   = callerName,
            callerMobile = callerMobile,
            callType     = callType,
            callId       = acceptedCallId,
            onAccept     = {
                IncomingCallOverlayService.stop(context)
                FirebaseFirestore.getInstance()
                    .collection("calls").document(acceptedCallId)
                    .update("status", "answered")
                phase = "calling"
            },
            onDecline    = {
                IncomingCallOverlayService.stop(context)
                phase = "ended"
            }
        )

        "calling" -> CallingScreen(
            currentUser    = currentUser,
            contact        = User(name = callerName, mobile = callerMobile),
            callType       = callType,
            isReceiver     = true,
            existingCallId = acceptedCallId,
            onEndCall      = { phase = "ended" }
        )

        else -> {
            // Call ended / declined → Activity বন্ধ করো
            LaunchedEffect(Unit) { onCallEnded() }
        }
    }
}

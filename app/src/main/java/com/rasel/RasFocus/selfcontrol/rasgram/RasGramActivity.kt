package com.rasel.RasFocus.selfcontrol.rasgram

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

    private var incomingCallId: String?       = null
    private var incomingCallerMobile: String? = null
    private var incomingCallerName: String?   = null
    private var incomingCallType: String?     = null
    private var isIncomingCall: Boolean       = false
    // ACTION_ANSWER_CALL = overlay থেকে ইউজার accept করেছে → IncomingCallScreen skip, সরাসরি CallingScreen
    private var isAlreadyAnswered: Boolean    = false
    private var openChatWith: String?         = null
    // File shared from FileManager (ACTION_SEND) — opens contact picker then attaches file
    private var sharedFileUri: android.net.Uri? = null
    private var sharedFileName: String?          = null

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

        // ── Daily archive scheduler — প্রতি app open এ check করে।
        // WorkManager KEEP policy: already scheduled থাকলে নতুন schedule হয় না।
        RasGramArchiveScheduler.schedule(this)
        // Daily Drive sync — all chats + media → user's Google Drive
        RasGramDriveSyncScheduler.schedule(this)

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
                    // ACTION_ANSWER_CALL এ alreadyAnswered=true → IncomingCallScreen skip।
                    DirectCallUI(
                        callId          = incomingCallId       ?: "",
                        callerName      = incomingCallerName   ?: "Unknown",
                        callerMobile    = incomingCallerMobile ?: "",
                        callType        = incomingCallType     ?: "audio",
                        alreadyAnswered = isAlreadyAnswered,
                        onCallEnded     = { finish() }
                    )
                } else {
                    // ── Normal app open / message notification tap / file share ──
                    RasGramApp(
                        openChatWithMobile = openChatWith,
                        sharedFileUri      = sharedFileUri,
                        sharedFileName     = sharedFileName
                    )
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
        when (action) {
            "ACTION_INCOMING_CALL" -> {
                isIncomingCall       = true
                isAlreadyAnswered    = false
                incomingCallId       = i.getStringExtra("callId")
                incomingCallerMobile = i.getStringExtra("callerMobile")
                incomingCallerName   = i.getStringExtra("callerName")
                incomingCallType     = i.getStringExtra("callType") ?: "audio"
                openChatWith         = null
            }
            "ACTION_ANSWER_CALL" -> {
                // Overlay থেকে accept করা হয়েছে — IncomingCallScreen দেখানো দরকার নেই
                isIncomingCall       = true
                isAlreadyAnswered    = true
                incomingCallId       = i.getStringExtra("callId")
                incomingCallerMobile = i.getStringExtra("callerMobile")
                incomingCallerName   = i.getStringExtra("callerName")
                incomingCallType     = i.getStringExtra("callType") ?: "audio"
                openChatWith         = null
            }
            Intent.ACTION_SEND -> {
                // File shared from FileManager → open contact picker in RasGram
                isIncomingCall    = false
                isAlreadyAnswered = false
                openChatWith      = null
                sharedFileUri  = i.getParcelableExtra(Intent.EXTRA_STREAM)
                sharedFileName = i.getStringExtra("fileName")
                    ?: sharedFileUri?.lastPathSegment
            }
            else -> {
                isIncomingCall    = false
                isAlreadyAnswered = false
                openChatWith      = i?.getStringExtra("openChatWith")
                sharedFileUri     = null
                sharedFileName    = null
            }
        }
    }

    // Lock screen উপরে Activity দেখানো — unlock না করেই
    // Android 14/15 FIX: requestDismissKeyguard() বাদ দেওয়া হয়েছে।
    // requestDismissKeyguard() = device কে unlock করতে বলা → user কে PIN/pattern দিতে হত।
    // WhatsApp style: lock screen এর উপরে call UI দেখায়, unlock করতে বলে না।
    // setShowWhenLocked(true) + setTurnScreenOn(true) = screen জ্বলে + call UI lockscreen এর উপরে।
    private fun enableLockScreenDisplay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // Android 8.1+ API — lockscreen এর উপরে Activity দেখাও, dismiss করো না
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            // NOTE: requestDismissKeyguard() intentionally removed.
            // এটা unlock prompt দেখাত — WhatsApp এর মতো behavior না।
            // Call screen লক স্ক্রিনের উপরে দেখাবে, lock সরাবে না।
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON   or
                // FLAG_DISMISS_KEYGUARD removed — unlock prompt দেখাত
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        // Android 15 extra: keep screen bright during call
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

// ── DirectCallUI ──────────────────────────────────────────────────────────────
// App open / splash ছাড়াই সরাসরি IncomingCallScreen → CallingScreen।
// SharedPreferences থেকে logged-in user তুলে নেয়।
// alreadyAnswered=true হলে IncomingCallScreen skip করে সরাসরি CallingScreen।
@Composable
private fun DirectCallUI(
    callId: String,
    callerName: String,
    callerMobile: String,
    callType: String,
    alreadyAnswered: Boolean = false,
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
    // alreadyAnswered=true (ACTION_ANSWER_CALL) → overlay এ ইউজার accept করেছে,
    // IncomingCallScreen দেখানো দরকার নেই, সরাসরি "calling" phase।
    val initialPhase = when {
        callId.isEmpty()   -> "ended"
        alreadyAnswered    -> "calling"
        else               -> "incoming"
    }
    var phase by remember { mutableStateOf(initialPhase) }
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
                // NOTE: status="answered" এখানে লেখা হচ্ছে না।
                // CallingScreen receiver path এ setLocalDescription.onSetSuccess এ
                // status + answer SDP একসাথে atomically লেখা হয়।
                // এখানে আগে লিখলে caller SDP ছাড়াই "answered" দেখে → audio fail।
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

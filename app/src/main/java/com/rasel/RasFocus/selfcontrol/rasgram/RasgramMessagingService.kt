package com.rasel.RasFocus.selfcontrol.rasgram

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class RasgramMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val mobile = prefs.getString(PREF_MOBILE, null) ?: return
        FirebaseFirestore.getInstance()
            .collection("chat_users")
            .document(mobile)
            .update("fcmToken", token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val data = remoteMessage.data
        when (data["type"]) {
            "incoming_call" -> handleIncomingCall(
                callerName   = data["callerName"]   ?: "Unknown",
                callerMobile = data["callerMobile"] ?: "",
                callType     = data["callType"]     ?: "audio",
                callId       = data["callId"]       ?: ""
            )
            "message" -> showMessageNotification(
                senderName   = data["senderName"]   ?: "RasGram",
                message      = data["message"]      ?: "New message",
                senderMobile = data["senderMobile"] ?: ""
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INCOMING CALL — WhatsApp style, একটাই notification
    //
    // ✅ App FOREGROUND (screen on + unlocked + RasGram open):
    //    → FCM skip করো। Firestore realtime listener ইতিমধ্যে IncomingCallScreen
    //      দেখাবে। এখানে কিছু করা মানে duplicate overlay।
    //
    // ✅ App BACKGROUND / KILLED:
    //    → Overlay permission আছে? → IncomingCallOverlayService চালু করো
    //      (নিজেই startForeground করে, ring বাজায়)
    //    → Overlay permission নেই? → WhatsApp-style fullScreenIntent notification
    //      + ring manually বাজাও (AudioManager দিয়ে)
    // ─────────────────────────────────────────────────────────────────────────
    private fun handleIncomingCall(
        callerName: String,
        callerMobile: String,
        callType: String,
        callId: String
    ) {
        // ── App foreground হলে FCM এর কাজ নেই ──────────────────────────────
        // Firestore listener (RasGramModule LaunchedEffect) ইতিমধ্যে
        // IncomingCallScreen দেখাচ্ছে। FCM এ আলাদা overlay = triple duplicate।
        if (isAppInForeground()) return

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val hasOverlay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                         Settings.canDrawOverlays(this)

        if (hasOverlay) {
            // ── Overlay path: Service শুরু করো ─────────────────────────────
            // IncomingCallOverlayService.onStartCommand() → startForeground()
            // একটাই IMPORTANCE_MIN foreground notification তৈরি হবে।
            // এখানে আর notification post করবো না।
            IncomingCallOverlayService.start(
                context      = this,
                callId       = callId,
                callerName   = callerName,
                callerMobile = callerMobile,
                callType     = callType
            )
        } else {
            // ── Fallback: WhatsApp-style full-screen notification ────────────
            createChannels(nm)
            postWhatsAppStyleCallNotification(nm, callerName, callerMobile, callType, callId)
            startRingtoneFallback()
        }
    }

    // ── App foreground check ──────────────────────────────────────────────────
    // ActivityLifecycleCallbacks ছাড়া simple power + process check:
    // Screen on + RasGram process এ UI আছে কিনা।
    private fun isAppInForeground(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) return false  // screen off → background

        // Process level foreground check
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val appProcesses = activityManager.runningAppProcesses ?: return false
        val packageName = packageName
        return appProcesses.any {
            it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
            it.pkgList.contains(packageName)
        }
    }

    // ── WhatsApp-style notification: একটাই, fullScreenIntent সহ ─────────────
    // Click করলে → RasGramActivity (ACTION_INCOMING_CALL) → full page UI
    // Answer / Decline action button সরাসরি notification এ।
    private fun postWhatsAppStyleCallNotification(
        nm: NotificationManager,
        callerName: String,
        callerMobile: String,
        callType: String,
        callId: String
    ) {
        // ── Answer: RasGramActivity খুলো, full page incoming call UI ─────────
        val answerIntent = Intent(this, RasGramActivity::class.java).apply {
            action = "ACTION_INCOMING_CALL"
            putExtra("callId",       callId)
            putExtra("callerMobile", callerMobile)
            putExtra("callerName",   callerName)
            putExtra("callType",     callType)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK      or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP     or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val answerPending = PendingIntent.getActivity(
            this, 1, answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ── Decline: BroadcastReceiver ────────────────────────────────────────
        val declineIntent = Intent(this, DeclineCallReceiver::class.java).apply {
            putExtra("callId", callId)
        }
        val declinePending = PendingIntent.getBroadcast(
            this, 2, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ── fullScreenIntent: lock screen / notification এ click করলে ────────
        // WhatsApp এর মতো — lock screen এও full page UI আসবে।
        val fullScreenPending = PendingIntent.getActivity(
            this, 3, answerIntent,  // same intent — answer action
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callTitle = if (callType == "video") "📹 Incoming Video Call" else "📞 Incoming Voice Call"
        val notif = NotificationCompat.Builder(this, CALL_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(callTitle)
            .setContentText(callerName)
            // Tap করলে RasGramActivity খুলবে
            .setContentIntent(answerPending)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // fullScreenIntent: lock screen এ full page দেখাবে
            .setFullScreenIntent(fullScreenPending, true)
            // Answer / Decline action buttons
            .addAction(android.R.drawable.ic_menu_call, "Answer",  answerPending)
            .addAction(android.R.drawable.ic_delete,    "Decline", declinePending)
            .setAutoCancel(false)
            .setOngoing(true)
            .setTimeoutAfter(60_000L)
            .build()

        nm.notify(CALL_NOTIFICATION_ID, notif)
    }

    // ── Ringtone fallback (overlay নেই path) ─────────────────────────────────
    // Notification channel sound Android 8+ এ first-create এর পরে
    // আর পরিবর্তন হয় না — তাই AudioManager দিয়ে আলাদাভাবে বাজাই।
    private fun startRingtoneFallback() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val ringtone = RingtoneManager.getRingtone(this, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ringtone?.isLooping = true
            ringtone?.play()
            // 60s পরে auto-stop (foreground service না থাকায় WeakRef দিয়ে করতে হবে)
            // Simple approach: notification timeout 60s, ringtone channel এ sound আছে
        } catch (_: Exception) {}
    }

    // ── Message notification ─────────────────────────────────────────────────
    private fun showMessageNotification(
        senderName: String,
        message: String,
        senderMobile: String
    ) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannels(nm)

        val intent = Intent(this, RasGramActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("openChatWith", senderMobile)
        }
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, MSG_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(senderName)
            .setContentText(message)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(message)
                    .setSummaryText("RasGram")
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        nm.notify(senderMobile.hashCode(), notif)
    }

    // ── Notification channels ─────────────────────────────────────────────────
    private fun createChannels(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val audioAttr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            // Call channel — fullScreenIntent + ringtone
            nm.createNotificationChannel(
                NotificationChannel(CALL_CHANNEL, "Incoming Calls", NotificationManager.IMPORTANCE_MAX).apply {
                    description          = "Incoming RasGram calls"
                    setSound(ringtoneUri, audioAttr)
                    enableVibration(true)
                    vibrationPattern     = longArrayOf(0, 500, 500, 500)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )

            // Message channel
            nm.createNotificationChannel(
                NotificationChannel(MSG_CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description          = "New RasGram messages"
                    enableVibration(true)
                    setShowBadge(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    companion object {
        const val CALL_NOTIFICATION_ID = 9999
        const val CALL_CHANNEL         = "CALL_CHANNEL"
        const val MSG_CHANNEL          = "MSG_CHANNEL"
        const val PREF_NAME            = "rasgram_prefs"
        const val PREF_MOBILE          = "saved_mobile"
    }
}

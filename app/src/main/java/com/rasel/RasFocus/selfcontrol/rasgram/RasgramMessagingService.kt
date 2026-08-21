package com.rasel.RasFocus.selfcontrol.rasgram

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
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
    // INCOMING CALL — একটাই flow, duplicate বন্ধ
    //
    // App FOREGROUND (screen on + app visible):
    //   → FCM skip করো। Firestore realtime listener IncomingCallScreen দেখাবে।
    //     সেখানে ring আছে।
    //
    // App BACKGROUND / KILLED:
    //   Overlay permission আছে?
    //     → IncomingCallOverlayService চালু করো
    //       (startForeground করে, ring বাজায়, overlay/activity দেখায়)
    //       এখানে আর কোনো notification post করা যাবে না।
    //   Overlay permission নেই?
    //     → WhatsApp-style fullScreenIntent notification post করো + ringtone channel
    // ─────────────────────────────────────────────────────────────────────────
    private fun handleIncomingCall(
        callerName: String,
        callerMobile: String,
        callType: String,
        callId: String
    ) {
        // ── App foreground হলে FCM এর কাজ নেই ──────────────────────────────
        // "Foreground" = app এর process UI-foreground importance তে আছে
        // এবং screen interactive।
        if (isAppForeground()) return

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val hasOverlay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                         Settings.canDrawOverlays(this)

        if (hasOverlay) {
            // ── Overlay path: Service শুরু করো, notification পোস্ট করবে না ──
            // IncomingCallOverlayService.onStartCommand() → startForeground()
            // একটাই IMPORTANCE_MIN foreground notification তৈরি হবে।
            IncomingCallOverlayService.start(
                context      = this,
                callId       = callId,
                callerName   = callerName,
                callerMobile = callerMobile,
                callType     = callType
            )
        } else {
            // ── Fallback path: full-screen notification ───────────────────────
            // Overlay permission নেই তাই notification দিয়ে কাজ চালাতে হবে।
            // Channel এ ringtone সেট আছে — notification নিজেই ring করবে।
            createChannels(nm)
            postFullScreenCallNotification(nm, callerName, callerMobile, callType, callId)
        }
    }

    // ── App foreground check ──────────────────────────────────────────────────
    // ActivityManager IMPORTANCE_FOREGROUND মানে app এর UI thread active।
    // PowerManager.isInteractive() না করলে screen-off এ সঠিক result পাওয়া যায়।
    // Screen off হলেও process FOREGROUND হতে পারে — তাই শুধু process check।
    private fun isAppForeground(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val processes = am.runningAppProcesses ?: return false
        val pkgName = packageName
        return processes.any {
            it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
            it.pkgList.contains(pkgName)
        }
    }

    // ── Full-screen notification: overlay নেই path ───────────────────────────
    // Lock screen এ full page দেখাবে + Answer/Decline button।
    // Ring: channel IMPORTANCE_MAX + ringtone আছে।
    private fun postFullScreenCallNotification(
        nm: NotificationManager,
        callerName: String,
        callerMobile: String,
        callType: String,
        callId: String
    ) {
        // ── Answer intent ────────────────────────────────────────────────────
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

        // ── Decline intent ───────────────────────────────────────────────────
        val declineIntent = Intent(this, DeclineCallReceiver::class.java).apply {
            putExtra("callId", callId)
        }
        val declinePending = PendingIntent.getBroadcast(
            this, 2, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callTitle = if (callType == "video") "📹 Incoming Video Call" else "📞 Incoming Voice Call"
        val notif = NotificationCompat.Builder(this, CALL_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(callTitle)
            .setContentText("$callerName ($callerMobile)")
            .setSubText("RasGram")
            .setContentIntent(answerPending)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // fullScreenIntent: lock screen এ full page দেখাবে + ring বাজবে
            .setFullScreenIntent(answerPending, true)
            // Answer / Decline action buttons
            .addAction(android.R.drawable.ic_menu_call, "Answer",  answerPending)
            .addAction(android.R.drawable.ic_delete,    "Decline", declinePending)
            .setAutoCancel(false)
            .setOngoing(true)
            .setTimeoutAfter(60_000L)
            .build()

        nm.notify(CALL_NOTIFICATION_ID, notif)
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

            // Call channel — fullScreenIntent + ringtone, IMPORTANCE_MAX
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

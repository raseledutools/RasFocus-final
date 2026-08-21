package com.rasel.RasFocus.selfcontrol.rasgram

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
    // INCOMING CALL
    //
    // Overlay permission আছে:
    //   → IncomingCallOverlayService চালু করো
    //   → এই FCM handler কোনো notification post করে না
    //     (Service নিজেই startForeground() করে — একটাই notification, double হবে না)
    //
    // Overlay permission নেই:
    //   → fullScreenIntent সহ ringtone notification post করো (fallback)
    // ─────────────────────────────────────────────────────────────────────────
    private fun handleIncomingCall(
        callerName: String,
        callerMobile: String,
        callType: String,
        callId: String
    ) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val hasOverlay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                         Settings.canDrawOverlays(this)

        if (hasOverlay) {
            // Overlay path: Service শুরু করো, notification post করো না।
            // IncomingCallOverlayService.onCreate() → startForeground() এ
            // একটাই IMPORTANCE_MIN notification তৈরি হবে।
            IncomingCallOverlayService.start(
                context      = this,
                callId       = callId,
                callerName   = callerName,
                callerMobile = callerMobile,
                callType     = callType
            )
        } else {
            // Fallback: overlay নেই → ringtone + fullScreenIntent notification
            createChannels(nm)
            postFallbackCallNotification(nm, callerName, callerMobile, callType, callId)
        }
    }

    // ── Fallback call notification (overlay NOT available) ────────────────────
    private fun postFallbackCallNotification(
        nm: NotificationManager,
        callerName: String,
        callerMobile: String,
        callType: String,
        callId: String
    ) {
        val answerIntent = Intent(this, RasGramActivity::class.java).apply {
            action = "ACTION_ANSWER_CALL"
            putExtra("callId",       callId)
            putExtra("callerMobile", callerMobile)
            putExtra("callerName",   callerName)
            putExtra("callType",     callType)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val answerPending = PendingIntent.getActivity(
            this, 1, answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(this, DeclineCallReceiver::class.java).apply {
            putExtra("callId", callId)
        }
        val declinePending = PendingIntent.getBroadcast(
            this, 2, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fullScreenIntent = Intent(this, RasGramActivity::class.java).apply {
            action = "ACTION_INCOMING_CALL"
            putExtra("callId",       callId)
            putExtra("callerMobile", callerMobile)
            putExtra("callerName",   callerName)
            putExtra("callType",     callType)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val fullScreenPending = PendingIntent.getActivity(
            this, 3, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callTitle = if (callType == "video") "Incoming Video Call" else "Incoming Voice Call"
        val notif = NotificationCompat.Builder(this, CALL_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(callTitle)
            .setContentText(callerName)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPending, true)
            .setContentIntent(answerPending)
            .addAction(android.R.drawable.ic_menu_call, "Answer",  answerPending)
            .addAction(android.R.drawable.ic_delete,    "Decline", declinePending)
            .setAutoCancel(false)
            .setOngoing(true)
            .setTimeoutAfter(60_000L)
            .build()
        nm.notify(CALL_NOTIFICATION_ID, notif)
    }

    // ── Message notification — preview সহ ────────────────────────────────────
    // Fix: BigTextStyle যোগ করা হয়েছে → message টা notification এ পুরো দেখাবে।
    // subText এ sender name, contentText এ message — lock screen এও দেখাবে।
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
            // contentTitle = sender name (bold হেডার)
            .setContentTitle(senderName)
            // contentText = message preview (collapsed এ দেখাবে)
            .setContentText(message)
            // BigTextStyle = expanded এ পুরো message দেখাবে
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(message)
                    .setSummaryText("RasGram")
            )
            // VISIBILITY_PRIVATE → lock screen এ "সামগ্রী লুকানো" দেখাবে না,
            // PUBLIC করলে lock screen এও পুরো message দেখাবে
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

            // Call channel (fallback — overlay নেই)
            nm.createNotificationChannel(
                NotificationChannel(CALL_CHANNEL, "Incoming Calls", NotificationManager.IMPORTANCE_MAX).apply {
                    description      = "Incoming RasGram calls (no overlay)"
                    setSound(ringtoneUri, audioAttr)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 500, 500)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )

            // Message channel
            nm.createNotificationChannel(
                NotificationChannel(MSG_CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "New RasGram messages"
                    enableVibration(true)
                    setShowBadge(true)
                    // lock screen এ message preview দেখাবে
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

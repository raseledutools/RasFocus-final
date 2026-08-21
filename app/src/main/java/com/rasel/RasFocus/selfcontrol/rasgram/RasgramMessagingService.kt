package com.rasel.RasFocus.selfcontrol.rasgram

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
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
    // INCOMING CALL — WhatsApp-style:
    //   ✅ SYSTEM_ALERT_WINDOW granted  → start IncomingCallOverlayService
    //                                     (floating overlay over any app/lock screen)
    //   ✅ Fallback (no overlay perm)   → fire fullScreenIntent via notification
    //                                     (old behaviour, still works)
    // ─────────────────────────────────────────────────────────────────────────
    private fun handleIncomingCall(
        callerName: String,
        callerMobile: String,
        callType: String,
        callId: String
    ) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannels(nm)

        // Always post the backup fullScreen notification (needed on Android 14+
        // where overlay permission alone may not wake the screen from deep sleep)
        postCallNotification(nm, callerName, callerMobile, callType, callId)

        // Start overlay service if permission is granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            Settings.canDrawOverlays(this)
        ) {
            // ✅ Overlay path — floating card over any app
            IncomingCallOverlayService.start(
                context      = this,
                callId       = callId,
                callerName   = callerName,
                callerMobile = callerMobile,
                callType     = callType
            )
        }
        // If no overlay permission the notification's fullScreenIntent fires instead
        // (already posted above) — still shows call UI on lock screen.
    }

    // ── Full-screen notification (fallback + Android 14+ wake signal) ────────
    private fun postCallNotification(
        nm: NotificationManager,
        callerName: String,
        callerMobile: String,
        callType: String,
        callId: String
    ) {
        // Answer → opens call screen
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

        // Decline → broadcast
        val declineIntent = Intent(this, DeclineCallReceiver::class.java).apply {
            putExtra("callId", callId)
        }
        val declinePending = PendingIntent.getBroadcast(
            this, 2, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // fullScreenIntent — triggers lock-screen call UI
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

        val notification = NotificationCompat.Builder(this, "CALL_CHANNEL")
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

        nm.notify(CALL_NOTIFICATION_ID, notification)
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
        }
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "MSG_CHANNEL")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(senderName)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        nm.notify(senderMobile.hashCode(), notification)
    }

    // ── Notification channels ─────────────────────────────────────────────────
    private fun createChannels(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ringtoneUri  = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val audioAttr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            // CALL_CHANNEL — IMPORTANCE_MAX so fullScreenIntent fires on lock screen
            val callCh = NotificationChannel(
                "CALL_CHANNEL", "Incoming Calls", NotificationManager.IMPORTANCE_MAX
            ).apply {
                description = "Incoming RasGram calls"
                setSound(ringtoneUri, audioAttr)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 500, 500)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(callCh)

            // MSG_CHANNEL
            nm.createNotificationChannel(
                NotificationChannel("MSG_CHANNEL", "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "New RasGram messages"
                    enableVibration(true)
                    setShowBadge(true)
                }
            )
        }
    }

    companion object {
        const val CALL_NOTIFICATION_ID = 9999
        const val PREF_NAME   = "rasgram_prefs"
        const val PREF_MOBILE = "saved_mobile"
    }
}

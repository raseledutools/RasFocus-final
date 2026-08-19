package com.rasel.RasFocus.selfcontrol.rasgram

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.firebase.firestore.FirebaseFirestore

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
            "incoming_call" -> showIncomingCallNotification(
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

    private fun showIncomingCallNotification(
        callerName: String, callerMobile: String, callType: String, callId: String
    ) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannels(nm)

        val answerIntent = Intent(this, RasGramActivity::class.java).apply {
            action = "ACTION_ANSWER_CALL"
            putExtra("callId", callId)
            putExtra("callerMobile", callerMobile)
            putExtra("callerName", callerName)
            putExtra("callType", callType)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
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
            putExtra("callId", callId)
            putExtra("callerMobile", callerMobile)
            putExtra("callerName", callerName)
            putExtra("callType", callType)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPending = PendingIntent.getActivity(
            this, 3, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = androidx.core.app.NotificationCompat.Builder(this, "CALL_CHANNEL")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(if (callType == "video") "Incoming Video Call" else "Incoming Voice Call")
            .setContentText(callerName)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPending, true)
            .addAction(android.R.drawable.ic_menu_call, "Answer", answerPending)
            .addAction(android.R.drawable.ic_delete, "Decline", declinePending)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()

        nm.notify(CALL_NOTIFICATION_ID, notification)
    }

    private fun showMessageNotification(senderName: String, message: String, senderMobile: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannels(nm)

        val intent = Intent(this, RasGramActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = androidx.core.app.NotificationCompat.Builder(this, "MSG_CHANNEL")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(senderName)
            .setContentText(message)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        nm.notify(senderMobile.hashCode(), notification)
    }

    private fun createChannels(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel("CALL_CHANNEL", "Incoming Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Notifications for incoming RasGram calls"
                    setSound(null, null)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel("MSG_CHANNEL", "Messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Notifications for new RasGram messages"
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

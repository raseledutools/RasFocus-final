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
    // দুটো path:
    //   A) Overlay permission আছে  → IncomingCallOverlayService চালু করো
    //                                 (overlay নিজেই ring + vibrate করে)
    //                                 backup notification = SILENT (শুধু Android 14+
    //                                 screen wake signal হিসেবে দরকার, ring না)
    //
    //   B) Overlay permission নেই  → fullScreenIntent সহ ringtone notification
    //                                 (পুরনো fallback, এটাই ring করে)
    //
    // এভাবে double ring সম্পূর্ণ বন্ধ হয়।
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

        // Overlay path: overlay service নিজেই ring করবে।
        // Notification শুধু silent foreground anchor — notification shade এ দেখাবে না।
        if (hasOverlay) {
            createChannels(nm, overlayActive = true)
            postCallNotification(nm, callerName, callerMobile, callType, callId, silent = true)
            IncomingCallOverlayService.start(
                context      = this,
                callId       = callId,
                callerName   = callerName,
                callerMobile = callerMobile,
                callType     = callType
            )
        } else {
            // Fallback path: overlay নেই, notification নিজেই ring + fullScreenIntent
            createChannels(nm, overlayActive = false)
            postCallNotification(nm, callerName, callerMobile, callType, callId, silent = false)
        }
    }

    // ── Full-screen notification ──────────────────────────────────────────────
    // silent = true  → overlay চালু, এই notification শুধু Android 14+ wake signal,
    //                  IMPORTANCE_MIN চ্যানেলে যাবে — shade এ দেখাবে না, ring করবে না
    // silent = false → overlay নেই, CALL_CHANNEL এ যাবে — ring + fullScreenIntent
    private fun postCallNotification(
        nm: NotificationManager,
        callerName: String,
        callerMobile: String,
        callType: String,
        callId: String,
        silent: Boolean
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

        if (silent) {
            // ── Silent anchor notification (overlay active) ──────────────────
            // IMPORTANCE_MIN → shade এ দেখাবে না, ring করবে না।
            // শুধু overlay service dismiss হওয়ার পর auto-cancel এর জন্য।
            val notif = NotificationCompat.Builder(this, CALL_SILENT_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle(if (callType == "video") "Incoming Video Call" else "Incoming Voice Call")
                .setContentText(callerName)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setContentIntent(answerPending)
                .setAutoCancel(true)
                .setOngoing(false)
                .setTimeoutAfter(65_000L)
                .build()
            nm.notify(CALL_NOTIFICATION_ID, notif)
        } else {
            // ── Full ring notification (overlay NOT available) ────────────────
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
    }

    // ── Message notification ──────────────────────────────────────────────────
    private fun showMessageNotification(
        senderName: String,
        message: String,
        senderMobile: String
    ) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannels(nm, overlayActive = false)

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
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        nm.notify(senderMobile.hashCode(), notif)
    }

    // ── Notification channels ─────────────────────────────────────────────────
    // overlayActive = true  → CALL_CHANNEL কে silent করো (overlay ring করবে)
    // overlayActive = false → CALL_CHANNEL এ ringtone রাখো (fallback)
    private fun createChannels(nm: NotificationManager, overlayActive: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val audioAttr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            // CALL_CHANNEL — overlay চালু থাকলে silent, নইলে ring
            // NOTE: NotificationChannel একবার তৈরি হলে sound পরে পরিবর্তন হয় না।
            // তাই দুটো আলাদা channel ব্যবহার করছি।
            if (!overlayActive) {
                // Full-ring channel (fallback path)
                val callCh = NotificationChannel(
                    CALL_CHANNEL, "Incoming Calls", NotificationManager.IMPORTANCE_MAX
                ).apply {
                    description       = "Incoming RasGram calls (no overlay)"
                    setSound(ringtoneUri, audioAttr)
                    enableVibration(true)
                    vibrationPattern  = longArrayOf(0, 500, 500, 500)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(callCh)
            }

            // Silent anchor channel (overlay path) — IMPORTANCE_MIN
            val silentCh = NotificationChannel(
                CALL_SILENT_CHANNEL, "Call Anchor (Silent)", NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Silent anchor for overlay calls — not visible to user"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
                setShowBadge(false)
            }
            nm.createNotificationChannel(silentCh)

            // MSG_CHANNEL
            nm.createNotificationChannel(
                NotificationChannel(MSG_CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "New RasGram messages"
                    enableVibration(true)
                    setShowBadge(true)
                }
            )
        }
    }

    companion object {
        const val CALL_NOTIFICATION_ID  = 9999
        const val CALL_CHANNEL          = "CALL_CHANNEL"
        const val CALL_SILENT_CHANNEL   = "CALL_SILENT_CHANNEL"
        const val MSG_CHANNEL           = "MSG_CHANNEL"
        const val PREF_NAME             = "rasgram_prefs"
        const val PREF_MOBILE           = "saved_mobile"
    }
}

package com.rasel.RasFocus.selfcontrol.rasgram

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.rasel.RasFocus.R

class RasgramMessagingService : FirebaseMessagingService() {

    // WhatsApp green
    private val RASGRAM_GREEN = Color.parseColor("#25D366")

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val mobile = try {
            getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(PREF_MOBILE, null)
        } catch (_: Exception) { null }
            ?: try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                    createDeviceProtectedStorageContext()
                        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                        .getString(PREF_MOBILE, null)
                else null
            } catch (_: Exception) { null }
            ?: return

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
                callerName    = data["callerName"]    ?: "Unknown",
                callerMobile  = data["callerMobile"]  ?: "",
                callType      = data["callType"]      ?: "audio",
                callId        = data["callId"]        ?: "",
                calleeMobile  = data["calleeMobile"]  ?: ""
            )
            "message" -> {
                val senderMobile = data["senderMobile"] ?: ""
                // FIX: mark all messages from this sender as delivered=true when FCM arrives.
                // Before: delivered was only set when recipient OPENED the chat (same time as read).
                // Now: FCM delivery = grey double tick, opening chat = blue double tick.
                if (senderMobile.isNotEmpty()) {
                    val myMobile = getMobileFromStorage()
                    if (myMobile != null) {
                        val chatId = if (myMobile < senderMobile) "${myMobile}_${senderMobile}"
                                     else "${senderMobile}_${myMobile}"
                        FirebaseFirestore.getInstance()
                            .collection("pvt_msg_${chatId}")
                            .whereEqualTo("senderMobile", senderMobile)
                            .whereEqualTo("delivered", false)
                            .get()
                            .addOnSuccessListener { snap ->
                                snap.documents.forEach { doc ->
                                    doc.reference.update("delivered", true)
                                }
                            }
                    }
                }
                showMessageNotification(
                    senderName   = data["senderName"]   ?: "RasGram",
                    message      = data["message"]      ?: "New message",
                    senderMobile = senderMobile
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INCOMING CALL
    // ─────────────────────────────────────────────────────────────────────────
    private fun handleIncomingCall(
        callerName: String, callerMobile: String, callType: String, callId: String,
        calleeMobile: String = ""
    ) {
        // BUG FIX: callee validation — এই device এ যে logged in আছে সে-ই callee কিনা check।
        // FCM token কখনো একাধিক device এ বা ভুল user এর কাছে যেতে পারে।
        val myMobile = getMobileFromStorage()
        if (myMobile == null || myMobile.isEmpty()) {
            // Not logged in — ignore call
            return
        }

        // FIX v8: calleeMobile match — Android 15 এ number format issue এর robust fix।
        // normalizeMobile() last suffix (10 digits) বের করে compare করে।
        // এতে +880, 880, 0, raw format সব কিছু match করে।
        if (calleeMobile.isNotEmpty() && !mobilesMatch(calleeMobile, myMobile)) {
            // এই call আমার জন্য না — ignore করো।
            android.util.Log.d("RasGram", "Call ignored: callee=$calleeMobile myMobile=$myMobile")
            return
        }

        var serviceStarted = false
        try {
            IncomingCallOverlayService.start(this, callId, callerName, callerMobile, callType)
            serviceStarted = true
        } catch (e: Exception) {
            android.util.Log.e("RasGram", "IncomingCallOverlayService start failed: ${e.message}")
        }
        if (!serviceStarted) {
            // Service start হয়নি — plain notification fallback
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            createChannels(nm)
            postFullScreenCallNotification(nm, callerName, callerMobile, callType, callId)
        }
    }

    private fun isAppForeground(): Boolean = RasGramActivity.isVisible

    private fun getMobileFromStorage(): String? =
        try { getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(PREF_MOBILE, null) }
        catch (_: Exception) { null }
        ?: try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                createDeviceProtectedStorageContext()
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .getString(PREF_MOBILE, null)
            else null
        } catch (_: Exception) { null }

    /**
     * FIX v8: Mobile number matching — suffix-based comparison।
     *
     * কেন আগেরটা ভেঙেছিল (Android 15):
     * normalizeMobile("01711223344") → "8801711223344"  (880 + 10 digits)
     * normalizeMobile("1711223344")  → "1711223344"     (কোনো prefix নেই, 10 digits)
     * এই দুটো match হয় না → call ignore হয়।
     *
     * Fix: দুটো number এর শেষ 10 digit compare করো।
     * BD number এর last 10 digit সবসময় unique identifier।
     * "01711223344".last10 = "1711223344"
     * "+8801711223344".last10 = "1711223344"
     * "8801711223344".last10 = "1711223344"
     * সব case এ match হয়।
     */
    private fun mobilesMatch(a: String, b: String): Boolean {
        val aDigits = a.trim().replace(Regex("[^0-9]"), "")
        val bDigits = b.trim().replace(Regex("[^0-9]"), "")
        if (aDigits.isEmpty() || bDigits.isEmpty()) return false
        // শেষ 10 digit compare — BD mobile number এর subscriber number part
        val aSuffix = aDigits.takeLast(10)
        val bSuffix = bDigits.takeLast(10)
        return aSuffix == bSuffix
    }

    /**
     * Legacy: normalizeMobile — এখনো ব্যবহার নেই, backward compat এর জন্য রাখা।
     */
    private fun normalizeMobile(raw: String): String {
        val digits = raw.trim().replace(Regex("[^0-9]"), "")
        return when {
            digits.startsWith("880") && digits.length >= 12 -> digits
            digits.startsWith("0") && digits.length == 11  -> "880${digits.substring(1)}"
            digits.length == 10 && digits.startsWith("1")  -> "880$digits"
            else -> digits
        }
    }

    private fun postFullScreenCallNotification(
        nm: NotificationManager,
        callerName: String, callerMobile: String, callType: String, callId: String
    ) {
        val answerIntent = Intent(this, RasGramActivity::class.java).apply {
            action = "ACTION_INCOMING_CALL"
            putExtra("callId", callId)
            putExtra("callerMobile", callerMobile)
            putExtra("callerName", callerName)
            putExtra("callType", callType)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
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

        val callTitle = if (callType == "video") "📹 Incoming Video Call" else "📞 Incoming Voice Call"
        val notif = NotificationCompat.Builder(this, CALL_CHANNEL)
            .setSmallIcon(R.drawable.ic_rasgram_notif)
            .setColor(RASGRAM_GREEN)
            .setColorized(true)
            .setContentTitle(callTitle)
            .setContentText("$callerName ($callerMobile)")
            .setSubText("RasGram")
            .setContentIntent(answerPending)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(answerPending, true)
            .addAction(android.R.drawable.ic_menu_call, "✅  Answer",  answerPending)
            .addAction(android.R.drawable.ic_delete,    "❌  Decline", declinePending)
            .setAutoCancel(false)
            .setOngoing(true)
            .setTimeoutAfter(60_000L)
            .build()

        nm.notify(CALL_NOTIFICATION_ID, notif)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MESSAGE NOTIFICATION — WhatsApp-style design + inline Reply
    // ─────────────────────────────────────────────────────────────────────────
    private fun showMessageNotification(
        senderName: String,
        message: String,
        senderMobile: String
    ) {
        if (isAppForeground()) return

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannels(nm)

        val displayName = senderName.ifBlank { "RasGram" }
        val notifId     = senderMobile.hashCode()

        // ── Tap → open RasGram on the correct chat ──────────────────────────
        val openIntent = Intent(this, RasGramActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("openChatWith", senderMobile)
        }
        val openPending = PendingIntent.getActivity(
            this, notifId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ── Inline Reply action ─────────────────────────────────────────────
        val remoteInput = RemoteInput.Builder(RasgramReplyReceiver.KEY_REPLY_TEXT)
            .setLabel("Reply to $displayName…")
            .build()

        val replyIntent = Intent(this, RasgramReplyReceiver::class.java).apply {
            putExtra(RasgramReplyReceiver.EXTRA_SENDER_MOBILE, senderMobile)
            putExtra(RasgramReplyReceiver.EXTRA_SENDER_NAME,   displayName)
            putExtra(RasgramReplyReceiver.EXTRA_NOTIF_ID,      notifId)
        }
        val replyPending = PendingIntent.getBroadcast(
            this,
            notifId + 1,     // unique request code per conversation
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_rasgram_notif,
            "Reply",
            replyPending
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()

        // ── "Mark as read" action ───────────────────────────────────────────
        val markReadPending = PendingIntent.getActivity(
            this, notifId + 2, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val markReadAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_view,
            "Mark as read",
            markReadPending
        ).build()

        // ── MessagingStyle — WhatsApp conversation bubbles ──────────────────
        val mePerson = Person.Builder()
            .setName("You")
            .setImportant(false)
            .build()
        val senderPerson = Person.Builder()
            .setName(displayName)
            .setImportant(true)
            .build()

        val msgStyle = NotificationCompat.MessagingStyle(mePerson)
            .setConversationTitle(null)   // 1-to-1 chat: no group title
            .addMessage(
                NotificationCompat.MessagingStyle.Message(
                    message,
                    System.currentTimeMillis(),
                    senderPerson
                )
            )

        // ── Unread badge count ──────────────────────────────────────────────
        val badgeCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            nm.activeNotifications.count {
                it.notification.category == android.app.Notification.CATEGORY_MESSAGE
            } + 1
        } else 1

        // ── Build the notification ──────────────────────────────────────────
        val notif = NotificationCompat.Builder(this, MSG_CHANNEL)
            // ── Icon & color ──────────────────────────────────────────
            .setSmallIcon(R.drawable.ic_rasgram_notif)
            .setColor(RASGRAM_GREEN)
            .setColorized(true)
            // ── Content ───────────────────────────────────────────────
            .setStyle(msgStyle)
            .setContentTitle(displayName)
            .setContentText(message)
            .setSubText("RasGram")
            // ── Behaviour ─────────────────────────────────────────────
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setNumber(badgeCount)           // launcher icon badge
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            // ── Vibration & light ─────────────────────────────────────
            .setVibrate(longArrayOf(0, 400, 200, 400))
            .setLights(RASGRAM_GREEN, 500, 1000)
            // ── Actions ───────────────────────────────────────────────
            .addAction(replyAction)
            .addAction(markReadAction)
            .build()

        nm.notify(notifId, notif)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification Channels
    // ─────────────────────────────────────────────────────────────────────────
    private fun createChannels(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val audioAttr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        // ── Call channel ──────────────────────────────────────────────────────
        nm.createNotificationChannel(
            NotificationChannel(CALL_CHANNEL, "Incoming Calls", NotificationManager.IMPORTANCE_MAX).apply {
                description          = "Incoming RasGram calls"
                setSound(ringtoneUri, audioAttr)
                enableVibration(true)
                vibrationPattern     = longArrayOf(0, 800, 600)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                enableLights(true)
                lightColor           = Color.parseColor("#25D366")
                setBypassDnd(true)
            }
        )

        // ── Message channel ───────────────────────────────────────────────────
        nm.createNotificationChannel(
            NotificationChannel(MSG_CHANNEL, "RasGram Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description          = "New RasGram messages"
                enableVibration(true)
                vibrationPattern     = longArrayOf(0, 400, 200, 400)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                enableLights(true)
                lightColor           = Color.parseColor("#25D366")
            }
        )
    }

    companion object {
        const val CALL_NOTIFICATION_ID = 9999
        const val CALL_CHANNEL         = "CALL_CHANNEL"
        const val MSG_CHANNEL          = "MSG_CHANNEL"
        const val PREF_NAME            = "rasgram_prefs"
        const val PREF_MOBILE          = "saved_mobile"
    }
}

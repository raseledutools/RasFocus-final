package com.rasel.RasFocus.selfcontrol.rasgram

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.google.firebase.firestore.FirebaseFirestore
import com.rasel.RasFocus.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Handles inline "Reply" action from the notification shade.
 *
 * Flow:
 *  1. User swipes down, types reply in notification, taps Send.
 *  2. Android fires this receiver with the typed text in RemoteInput extras.
 *  3. We read the text, encrypt it, push to Firestore (pvt_msg_<chatId>).
 *  4. Update the notification to show "✓ Sent" so the user gets feedback.
 */
class RasgramReplyReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        // ── 1. Extract typed text from RemoteInput ──────────────────────────
        val bundle = RemoteInput.getResultsFromIntent(intent) ?: return
        val replyText = bundle.getCharSequence(KEY_REPLY_TEXT)?.toString()?.trim()
        if (replyText.isNullOrEmpty()) return

        // ── 2. Extract sender info passed via intent extras ─────────────────
        val senderMobile   = intent.getStringExtra(EXTRA_SENDER_MOBILE)   ?: return
        val senderName     = intent.getStringExtra(EXTRA_SENDER_NAME)      ?: "RasGram"
        val notifId        = intent.getIntExtra(EXTRA_NOTIF_ID, senderMobile.hashCode())

        // ── 3. Read my own mobile from SharedPreferences ────────────────────
        val myMobile = getMyMobile(context) ?: return
        val myName   = getMyName(context)

        val chatId = generateChatId(myMobile, senderMobile)

        // ── 4. Update notification immediately — "Sending…" state ──────────
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        markSending(context, nm, notifId, senderName, replyText)

        // ── 5. Encrypt & push to Firestore ──────────────────────────────────
        scope.launch {
            try {
                val encryptedText = AESCrypto.encrypt(chatId, replyText)
                val now = System.currentTimeMillis()

                val msgDoc = hashMapOf(
                    "text"           to encryptedText,
                    "senderMobile"   to myMobile,
                    "senderName"     to myName,
                    "receiverMobile" to senderMobile,
                    "timestamp"      to now,
                    "timeString"     to formatTime(now),
                    "read"           to false,
                    "delivered"      to false,
                    "isPending"      to false,
                    "isDeleted"      to false
                )

                FirebaseFirestore.getInstance()
                    .collection("pvt_msg_$chatId")
                    .add(msgDoc)
                    .addOnSuccessListener {
                        // ── 6. Update notification to "Sent ✓" ──────────────
                        markSent(context, nm, notifId, senderName, replyText)

                        // Also update chat_users lastSeen / typing cleared
                        FirebaseFirestore.getInstance()
                            .collection("chat_users")
                            .document(myMobile)
                            .update("typingTo", null)
                    }
                    .addOnFailureListener {
                        markFailed(context, nm, notifId, senderName)
                    }
            } catch (e: Exception) {
                markFailed(context, nm, notifId, senderName)
            }
        }
    }

    // ── SharedPreference helpers ─────────────────────────────────────────────
    private fun getMyMobile(context: Context): String? =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(PREF_MOBILE, null)

    private fun getMyName(context: Context): String =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(PREF_NAME_KEY, "Me") ?: "Me"

    // ── Notification state helpers ───────────────────────────────────────────
    private fun markSending(
        context: Context, nm: NotificationManager, id: Int,
        senderName: String, draft: String
    ) {
        nm.notify(id, buildStatusNotif(context, senderName, "⏳  Sending…", draft))
    }

    private fun markSent(
        context: Context, nm: NotificationManager, id: Int,
        senderName: String, sent: String
    ) {
        nm.notify(id, buildStatusNotif(context, senderName, "✓  Sent: $sent", null))
        // Auto-dismiss after 3 s
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { nm.cancel(id) }, 3_000L
        )
    }

    private fun markFailed(
        context: Context, nm: NotificationManager, id: Int, senderName: String
    ) {
        nm.notify(id, buildStatusNotif(context, senderName, "❌  Send failed — tap to retry", null))
    }

    private fun buildStatusNotif(
        context: Context,
        senderName: String,
        statusText: String,
        @Suppress("UNUSED_PARAMETER") draft: String?
    ): android.app.Notification {
        val openIntent = Intent(context, RasGramActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, RasgramMessagingService.MSG_CHANNEL)
            .setSmallIcon(R.drawable.ic_rasgram_notif)
            .setColor(0xFF25D366.toInt())
            .setColorized(true)
            .setContentTitle(senderName)
            .setContentText(statusText)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ── Time formatter ───────────────────────────────────────────────────────
    private fun formatTime(ms: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ms
        val now = Calendar.getInstance()
        return when {
            cal.get(Calendar.DATE) == now.get(Calendar.DATE) ->
                SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
            else ->
                SimpleDateFormat("dd/MM/yy h:mm a", Locale.getDefault()).format(Date(ms))
        }
    }

    companion object {
        const val KEY_REPLY_TEXT      = "key_reply_text"
        const val EXTRA_SENDER_MOBILE = "extra_sender_mobile"
        const val EXTRA_SENDER_NAME   = "extra_sender_name"
        const val EXTRA_NOTIF_ID      = "extra_notif_id"

        const val PREF_NAME     = "rasgram_prefs"
        const val PREF_MOBILE   = "saved_mobile"
        const val PREF_NAME_KEY = "saved_name"
    }
}

package com.rasel.RasFocus.selfcontrol.rasgram

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Missed call notification এর "Call Back" button এর BroadcastReceiver।
 * RasGramActivity তে CallingScreen খোলে — caller হিসেবে outgoing call শুরু করে।
 */
class CallbackReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val calleeMobile = intent.getStringExtra("calleeMobile") ?: return
        val calleeName   = intent.getStringExtra("calleeName")   ?: calleeMobile
        val callType     = intent.getStringExtra("callType")     ?: "audio"

        // Missed call notification cancel করো
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(IncomingCallOverlayService.MISSED_CALL_NOTIF_ID)

        // RasGramActivity তে outgoing call শুরু করো
        val callIntent = Intent(context, RasGramActivity::class.java).apply {
            action = "ACTION_OUTGOING_CALL"
            putExtra("calleeMobile", calleeMobile)
            putExtra("calleeName",   calleeName)
            putExtra("callType",     callType)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        ContextCompat.startActivity(context, callIntent, null)
    }
}

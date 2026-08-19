package com.rasel.RasFocus.selfcontrol.rasgram

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.firebase.firestore.FirebaseFirestore

class DeclineCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra("callId") ?: return
        FirebaseFirestore.getInstance()
            .collection("calls")
            .document(callId)
            .update("status", "declined")
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(RasgramMessagingService.CALL_NOTIFICATION_ID)
    }
}

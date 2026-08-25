package com.rasel.RasFocus.selfcontrol.rasgram

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.firebase.firestore.FirebaseFirestore

class DeclineCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra("callId") ?: return

        // Firestore এ status update করো — overlay service এর listener এটা দেখে stopSelf() করবে
        FirebaseFirestore.getInstance()
            .collection("calls")
            .document(callId)
            .update("status", "declined")

        // Notification বন্ধ করো (fallback path এর notification)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(RasgramMessagingService.CALL_NOTIFICATION_ID)
        nm.cancel(IncomingCallOverlayService.OVERLAY_NOTIF_ID)

        // Overlay service directly বন্ধ করো — Firestore network delay থাকলেও ring থামবে
        IncomingCallOverlayService.stop(context)
    }
}

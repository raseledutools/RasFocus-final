package com.rasel.RasFocus.selfcontrol.rasgram

import android.content.Context
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore

/**
 * RasGramPresenceService (এখন আর Service নয় — plain object)
 *
 * Foreground Service সরানো হয়েছে → notification centre এ আর কিছু দেখাবে না।
 *
 * কীভাবে কাজ করে:
 *   • Firebase RTDB নিজেই TCP connection alive রাখে (FCM এর মতোই)
 *   • ".info/connected" watch করে online/offline set করে
 *   • onDisconnect: phone বন্ধ/data গেলে Firebase server নিজেই offline লিখবে
 *   • কোনো Service, কোনো Notification, কোনো Wakelock নেই
 *
 * WhatsApp এও same — FCM connection দিয়েই presence কাজ করে।
 */
object RasGramPresenceService {

    private var currentMobile: String? = null
    private var connectedListener: ValueEventListener? = null

    fun start(context: Context, mobile: String) {
        if (mobile.isBlank()) return
        if (currentMobile == mobile) return  // already running for this user

        currentMobile = mobile
        setupPresence(mobile)
    }

    fun stop(context: Context) {
        val mob = currentMobile ?: return
        try {
            val rtdb = FirebaseDatabase.getInstance()
            val presenceRef = rtdb.getReference("presence").child(mob)
            val connectedRef = rtdb.getReference(".info/connected")

            connectedListener?.let { connectedRef.removeEventListener(it) }
            connectedListener = null

            presenceRef.setValue(
                mapOf("online" to false, "lastActive" to ServerValue.TIMESTAMP)
            )
            FirebaseFirestore.getInstance()
                .collection("chat_users")
                .document(mob)
                .update("lastActive", System.currentTimeMillis())
        } catch (_: Exception) {}

        currentMobile = null
    }

    private fun setupPresence(mob: String) {
        val rtdb = FirebaseDatabase.getInstance()
        val presenceRef = rtdb.getReference("presence").child(mob)
        val connectedRef = rtdb.getReference(".info/connected")

        connectedListener?.let { connectedRef.removeEventListener(it) }

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                if (connected) {
                    presenceRef.onDisconnect().setValue(
                        mapOf("online" to false, "lastActive" to ServerValue.TIMESTAMP)
                    )
                    presenceRef.setValue(
                        mapOf("online" to true, "lastActive" to ServerValue.TIMESTAMP)
                    )
                    FirebaseFirestore.getInstance()
                        .collection("chat_users")
                        .document(mob)
                        .update("lastActive", System.currentTimeMillis())
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        connectedRef.addValueEventListener(listener)
        connectedListener = listener
    }

    /** Legacy compat: RasGramModule এ PREF_MOBILE use হয় */
    const val PREF_NAME   = "rasgram_prefs"
    const val PREF_MOBILE = "saved_mobile"
}

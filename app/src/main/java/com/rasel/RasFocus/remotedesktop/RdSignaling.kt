package com.rasel.RasFocus.remotedesktop

/**
 * RdSignaling — Firebase Firestore based ID↔IP signaling
 *
 * RustDesk uses its own relay server. আমরা Firebase ব্যবহার করি।
 *
 * Phone চালু হলে → Firestore এ নিজের {id, ip, port, ts} save করে
 * User remote ID দিলে → Firestore থেকে সেই ID এর IP lookup করে connect করে
 *
 * Collection: "rd_devices"
 * Document:   deviceId (9-digit)
 * Fields:     id, ip, port, ts, name, platform
 */

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object RdSignaling {

    private const val TAG        = "RdSignaling"
    private const val COLLECTION = "rd_devices"

    data class DeviceInfo(
        val id: String,
        val ip: String,
        val port: Int,
        val name: String,
        val platform: String   // "android" or "windows"
    )

    // ── Register this device on Firestore ─────────────────────────
    fun register(ctx: Context, id: String, port: Int) {
        val ip = RemoteDesktopService.getLocalIp(ctx)
        if (ip == "0.0.0.0" || ip.isEmpty()) {
            Log.w(TAG, "No IP — skipping register")
            return
        }
        val doc = mapOf(
            "id"       to id,
            "ip"       to ip,
            "port"     to port,
            "name"     to android.os.Build.MODEL,
            "platform" to "android",
            "ts"       to System.currentTimeMillis()
        )
        FirebaseFirestore.getInstance()
            .collection(COLLECTION)
            .document(id)
            .set(doc, SetOptions.merge())
            .addOnSuccessListener { Log.d(TAG, "Registered: $id @ $ip:$port") }
            .addOnFailureListener { Log.e(TAG, "Register failed: ${it.message}") }
    }

    // ── Unregister (app stop) ─────────────────────────────────────
    fun unregister(id: String) {
        FirebaseFirestore.getInstance()
            .collection(COLLECTION)
            .document(id)
            .delete()
    }

    // ── Lookup device by ID → returns DeviceInfo or null ──────────
    suspend fun lookup(id: String): DeviceInfo? =
        suspendCancellableCoroutine { cont ->
            FirebaseFirestore.getInstance()
                .collection(COLLECTION)
                .document(id)
                .get()
                .addOnSuccessListener { doc ->
                    if (doc.exists()) {
                        val info = DeviceInfo(
                            id       = doc.getString("id")   ?: id,
                            ip       = doc.getString("ip")   ?: "",
                            port     = (doc.getLong("port")  ?: 9224).toInt(),
                            name     = doc.getString("name") ?: "Unknown",
                            platform = doc.getString("platform") ?: "unknown"
                        )
                        Log.d(TAG, "Lookup $id → ${info.ip}:${info.port}")
                        cont.resume(info)
                    } else {
                        Log.w(TAG, "ID not found: $id")
                        cont.resume(null)
                    }
                }
                .addOnFailureListener {
                    Log.e(TAG, "Lookup failed: ${it.message}")
                    cont.resume(null)
                }
        }

    // ── Format 9-digit ID for display (123 456 789) ───────────────
    fun formatId(id: String) = RemoteDesktopService.formatId(id)
}

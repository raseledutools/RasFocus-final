package com.rasel.RasFocus.remotedesktop

/**
 * RdSignaling — Firebase Firestore ID lookup + Relay server fallback
 *
 * PC side (tab_phone_remote.cpp) registers:
 *   Firestore "rd_sessions/<code>" = {code, ip, port, hostname, platform, ts}
 *
 * Phone side:
 *   1. User types 6-digit code
 *   2. Firestore lookup → get PC's local IP + port
 *   3. Try direct WebSocket: ws://pc-ip:9224
 *   4. If fails (different network) → relay: wss://relay.rasfocus.com/relay/<code>
 *
 * Relay server bridges the two WebSocket connections:
 *   PC connects to relay as "host" using same code
 *   Phone connects to relay as "client" using same code
 *   Relay pipes data bidirectionally — transparent to app layer
 */

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object RdSignaling {

    private const val TAG             = "RdSignaling"
    private const val SESSION_COL     = "rd_sessions"   // PC registers codes here
    private const val DEVICE_COL      = "rd_devices"    // Phone shares its own ID here
    private const val RELAY_WS_URL    = "wss://relay.rasfocus.com"  // your relay server

    // ── Device info returned from lookup ────────────────────────────
    data class DeviceInfo(
        val id: String,
        val ip: String,
        val port: Int,
        val name: String,
        val platform: String   // "android" or "windows"
    )

    // ── Register this phone device on Firestore ──────────────────────
    // Called when RemoteDesktopService starts (phone can be a host too)
    fun register(ctx: Context, id: String, port: Int) {
        val ip = RemoteDesktopService.getLocalIp(ctx)
        if (ip == "0.0.0.0" || ip.isEmpty()) {
            Log.w(TAG, "No local IP — skipping register")
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
            .collection(DEVICE_COL)
            .document(id)
            .set(doc, SetOptions.merge())
            .addOnSuccessListener { Log.d(TAG, "Phone registered: $id @ $ip:$port") }
            .addOnFailureListener { Log.e(TAG, "Register failed: ${it.message}") }
    }

    // ── Unregister ────────────────────────────────────────────────────
    fun unregister(id: String) {
        FirebaseFirestore.getInstance()
            .collection(DEVICE_COL)
            .document(id)
            .delete()
    }

    // ── Lookup by 9-digit ID (phone ID lookup — existing feature) ─────
    suspend fun lookup(id: String): DeviceInfo? =
        suspendCancellableCoroutine { cont ->
            // Try rd_devices first (for phone-to-phone)
            FirebaseFirestore.getInstance()
                .collection(DEVICE_COL)
                .document(id)
                .get()
                .addOnSuccessListener { doc ->
                    if (doc.exists()) {
                        cont.resume(parseDeviceDoc(doc, id))
                    } else {
                        // Try rd_sessions (for phone-to-PC by 6-digit code)
                        lookupSession(id, cont)
                    }
                }
                .addOnFailureListener {
                    Log.w(TAG, "rd_devices lookup failed, trying rd_sessions: ${it.message}")
                    lookupSession(id, cont)
                }
        }

    // ── Lookup a 6-digit PC session code ─────────────────────────────
    suspend fun lookupSession(code: String): DeviceInfo? =
        suspendCancellableCoroutine { cont ->
            lookupSession(code, cont)
        }

    private fun lookupSession(
        code: String,
        cont: kotlin.coroutines.Continuation<DeviceInfo?>
    ) {
        FirebaseFirestore.getInstance()
            .collection(SESSION_COL)
            .document(code)
            .get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val info = DeviceInfo(
                        id       = doc.getString("code")    ?: code,
                        ip       = doc.getString("ip")      ?: "",
                        port     = (doc.getLong("port")     ?: 9224).toInt(),
                        name     = doc.getString("hostname") ?: "Windows PC",
                        platform = doc.getString("platform") ?: "windows"
                    )
                    Log.d(TAG, "Session $code → ${info.ip}:${info.port} (${info.name})")
                    cont.resume(info)
                } else {
                    Log.w(TAG, "Session code not found: $code")
                    cont.resume(null)
                }
            }
            .addOnFailureListener {
                Log.e(TAG, "Session lookup failed: ${it.message}")
                cont.resume(null)
            }
    }

    private fun parseDeviceDoc(
        doc: com.google.firebase.firestore.DocumentSnapshot,
        fallbackId: String
    ): DeviceInfo = DeviceInfo(
        id       = doc.getString("id")       ?: fallbackId,
        ip       = doc.getString("ip")       ?: "",
        port     = (doc.getLong("port")      ?: 9224).toInt(),
        name     = doc.getString("name")     ?: "Unknown",
        platform = doc.getString("platform") ?: "unknown"
    )

    // ── Relay WebSocket URL for a given code ─────────────────────────
    // Use this when direct LAN connect fails.
    // PC must also connect to this same URL as host.
    fun relayUrl(code: String): String = "$RELAY_WS_URL/relay/$code"

    // ── Format ID for display (123 456 789 or 123 456) ───────────────
    fun formatId(id: String) = RemoteDesktopService.formatId(id)
}

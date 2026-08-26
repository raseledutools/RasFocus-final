package com.rasel.RasFocus.selfcontrol.rasgram

// ============================================================
// RasGramDriveSyncManager.kt  — v3 (bi-directional sync)
//
// Flow:
//   UPLOAD side (Room → Drive):
//     - Room এ আছে কিন্তু Drive এ নেই  → Drive এ upload
//     - প্রতিটা chat এর সব messages JSON এ merge করে upsert
//     - Media files → Drive copy (না থাকলেই)
//     - Drive confirm হলে → Cloudinary delete (safe: Drive এ না থাকলে delete হবে না)
//     - Drive confirm হলে → Firestore delete (safe: same condition)
//
//   DOWNLOAD side (Drive → Room):
//     - Drive এ JSON আছে কিন্তু Room এ নেই → Room এ import
//     - অন্য phone এ same account → সব chat চলে আসবে
//
//   PROGRESS:
//     - Notification এ percentage (X/Y chats)
//     - Background WorkManager (foreground service চাইলে চলে)
//
// Scheduler:
//   RasGramDriveSyncScheduler.schedule()  — 24h periodic
//   RasGramDriveSyncScheduler.runNow()    — manual Sync Now
// ============================================================

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val TAG            = "RasGramDriveSync"
private const val SYNC_WORK_NAME = "RasGramDriveSync"
private const val NOTIF_CHANNEL  = "rasgram_drive_sync"
private const val NOTIF_ID       = 9902

// Drive folder structure
private const val ROOT_FOLDER    = "RasFocus+"
private const val RASGRAM_FOLDER = "RasGram"

// SharedPreference keys
private const val PREF_FILE             = "rasgram_drive_sync_prefs"
private const val KEY_SYNC_ACCOUNTS     = "sync_accounts"
private const val KEY_DEFAULT_ACCOUNT   = "default_account"
private const val KEY_LAST_SYNC         = "last_sync_time"
private const val KEY_RASGRAM_FOLDER_ID = "rasgram_folder_id"

// Cloudinary credentials
private const val CLD_CLOUD   = "de2w78yxh"
private const val CLD_API_KEY = "292749814534824"
private const val CLD_SECRET  = "EEYmph3nZLR8Modypt0J7eH--58"

// ============================================================
// MULTI-ACCOUNT MANAGER
// ============================================================

object RasGramDriveAccountManager {

    fun getRasFocusAccount(context: Context): GoogleSignInAccount? =
        try { GoogleSignIn.getLastSignedInAccount(context) } catch (_: Exception) { null }

    fun getSyncAccounts(context: Context): List<String> {
        val set = prefs(context).getStringSet(KEY_SYNC_ACCOUNTS, emptySet())?.toMutableSet() ?: mutableSetOf()
        getRasFocusAccount(context)?.email?.let { set.add(it) }
        return set.toList().sorted()
    }

    fun addSyncAccount(context: Context, email: String) {
        val p   = prefs(context)
        val cur = p.getStringSet(KEY_SYNC_ACCOUNTS, emptySet())?.toMutableSet() ?: mutableSetOf()
        cur.add(email)
        p.edit().putStringSet(KEY_SYNC_ACCOUNTS, cur).apply()
        if (getDefaultSyncAccount(context) == null) setDefaultSyncAccount(context, email)
    }

    fun removeSyncAccount(context: Context, email: String) {
        if (email == getRasFocusAccount(context)?.email) return
        val p   = prefs(context)
        val cur = p.getStringSet(KEY_SYNC_ACCOUNTS, emptySet())?.toMutableSet() ?: mutableSetOf()
        cur.remove(email)
        p.edit().putStringSet(KEY_SYNC_ACCOUNTS, cur).apply()
        if (getDefaultSyncAccount(context) == email) p.edit().remove(KEY_DEFAULT_ACCOUNT).apply()
    }

    fun getDefaultSyncAccount(context: Context): String? {
        val saved = prefs(context).getString(KEY_DEFAULT_ACCOUNT, null)
        return if (!saved.isNullOrEmpty()) saved else getRasFocusAccount(context)?.email
    }

    fun setDefaultSyncAccount(context: Context, email: String) =
        prefs(context).edit().putString(KEY_DEFAULT_ACCOUNT, email).apply()

    fun getLastSyncTime(context: Context): Long = prefs(context).getLong(KEY_LAST_SYNC, 0L)

    fun recordSyncTime(context: Context) =
        prefs(context).edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()

    fun isDriveAvailable(context: Context): Boolean = getDefaultSyncAccount(context) != null

    fun buildDriveForAccount(context: Context, accountEmail: String): Drive? = try {
        val cred = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE_FILE))
            .apply { selectedAccountName = accountEmail }
        Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), cred)
            .setApplicationName("RasFocus+").build()
    } catch (e: Exception) { Log.e(TAG, "buildDrive($accountEmail): ${e.message}"); null }

    fun buildDefaultDrive(context: Context): Drive? {
        val email = getDefaultSyncAccount(context) ?: return null
        return buildDriveForAccount(context, email)
    }

    fun driveSignInOptions(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail().requestScopes(Scope(DriveScopes.DRIVE_FILE)).build()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
}

// ============================================================
// SYNC RESULT
// ============================================================

data class DriveSyncResult(
    val success: Boolean,
    val syncedChats: Int = 0,
    val syncedMessages: Int = 0,
    val syncedMediaFiles: Int = 0,
    val deletedFromCloudinary: Int = 0,
    val deletedFromFirestore: Int = 0,
    val downloadedFromDrive: Int = 0,
    val errorMessage: String? = null,
    val accountUsed: String? = null,
    val durationMs: Long = 0
)

// ============================================================
// CLOUDINARY HELPERS
// ============================================================

private fun extractCloudinaryPublicId(url: String): String? = try {
    val parts = url.split("/upload/")
    if (parts.size < 2) null
    else {
        val after = parts[1]
        val noVer = if (after.startsWith("v") && after.contains("/")) after.substringAfter("/") else after
        noVer.substringBeforeLast(".")
    }
} catch (_: Exception) { null }

private fun sha1Hex(input: String): String {
    val md = MessageDigest.getInstance("SHA-1")
    return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

private val httpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
}

private fun deleteFromCloudinary(publicId: String, resourceType: String): Boolean = try {
    val ts  = (System.currentTimeMillis() / 1000).toString()
    val sig = sha1Hex("public_id=${publicId}&timestamp=${ts}${CLD_SECRET}")
    val body = FormBody.Builder()
        .add("public_id", publicId).add("timestamp", ts)
        .add("api_key", CLD_API_KEY).add("signature", sig)
        .build()
    val req  = Request.Builder()
        .url("https://api.cloudinary.com/v1_1/$CLD_CLOUD/$resourceType/destroy")
        .post(body).build()
    val resp = httpClient.newCall(req).execute()
    val result = JSONObject(resp.body?.string() ?: "{}").optString("result")
    Log.d(TAG, "Cloudinary delete $publicId → $result")
    result == "ok" || result == "not found"
} catch (e: Exception) { Log.w(TAG, "Cloudinary delete failed $publicId: ${e.message}"); false }

// ============================================================
// DRIVE HELPERS
// ============================================================

private suspend fun getOrCreateFolder(
    drive: Drive, name: String, parentId: String = "root"
): String = withContext(Dispatchers.IO) {
    val q = "'$parentId' in parents and name = '$name' " +
            "and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
    drive.files().list().setQ(q).setFields("files(id)").execute()
        .files?.firstOrNull()?.id
        ?: run {
            val meta = DriveFile().apply {
                this.name = name
                mimeType  = "application/vnd.google-apps.folder"
                parents   = listOf(parentId)
            }
            drive.files().create(meta).setFields("id").execute().id
        }
}

private suspend fun findExistingJsonFile(
    drive: Drive, chatFolderId: String, chatId: String
): String? = withContext(Dispatchers.IO) {
    val q = "'$chatFolderId' in parents and name = 'messages_$chatId.json' and trashed = false"
    drive.files().list().setQ(q).setFields("files(id)").execute()
        .files?.firstOrNull()?.id
}

/** Upsert: আছে → update (merge), নেই → create */
private suspend fun upsertJsonToDrive(
    drive: Drive, chatFolderId: String, chatId: String,
    messages: List<CachedMessage>
): String = withContext(Dispatchers.IO) {
    val fileName = "messages_$chatId.json"
    val existingId = findExistingJsonFile(drive, chatFolderId, chatId)

    // Drive এ আগের data থাকলে merge করো — পুরানো message হারাবে না
    val merged: List<CachedMessage> = if (existingId != null) {
        try {
            val out = ByteArrayOutputStream()
            drive.files().get(existingId).executeMediaAndDownloadTo(out)
            val driveMessages = jsonToMessages(out.toString(Charsets.UTF_8.name()), chatId)
            // Room এরটা + Drive এরটা merge, id দিয়ে deduplicate, timestamp sort
            val byId = LinkedHashMap<String, CachedMessage>()
            driveMessages.forEach { byId[it.id] = it }
            messages.forEach { byId[it.id] = it }  // Room এর version জয়ী (latest)
            byId.values.sortedBy { it.timestamp }
        } catch (e: Exception) {
            Log.w(TAG, "Drive read for merge failed, overwriting: ${e.message}")
            messages
        }
    } else messages

    val json    = messagesToJson(merged)
    val bytes   = json.toByteArray(Charsets.UTF_8)
    val content = ByteArrayContent("application/json", bytes)

    if (existingId != null) {
        drive.files().update(existingId, null, content).execute()
        existingId
    } else {
        val meta = DriveFile().apply { name = fileName; parents = listOf(chatFolderId) }
        drive.files().create(meta, content).setFields("id").execute().id
    }
}

private suspend fun driveFileExists(drive: Drive, folderId: String, name: String): Boolean =
    withContext(Dispatchers.IO) {
        val q = "'$folderId' in parents and name = '$name' and trashed = false"
        (drive.files().list().setQ(q).setFields("files(id)").execute().files?.size ?: 0) > 0
    }

private suspend fun getRasGramRootFolderId(context: Context, drive: Drive): String =
    withContext(Dispatchers.IO) {
        val prefs  = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_RASGRAM_FOLDER_ID, null)
        if (!cached.isNullOrEmpty()) return@withContext cached
        val rootId    = getOrCreateFolder(drive, ROOT_FOLDER)
        val rasGramId = getOrCreateFolder(drive, RASGRAM_FOLDER, rootId)
        prefs.edit().putString(KEY_RASGRAM_FOLDER_ID, rasGramId).apply()
        rasGramId
    }

/** Drive এর RasGram folder এর সব chat subfolder list */
private suspend fun listDriveChatFolders(drive: Drive, rasGramFolderId: String): List<DriveFile> =
    withContext(Dispatchers.IO) {
        val q = "'$rasGramFolderId' in parents and " +
                "mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        drive.files().list().setQ(q).setFields("files(id,name)").execute().files ?: emptyList()
    }

// ============================================================
// JSON HELPERS
// ============================================================

private fun messagesToJson(messages: List<CachedMessage>): String {
    val arr = JSONArray()
    messages.forEach { m ->
        arr.put(JSONObject().apply {
            put("id", m.id); put("text", m.text)
            put("senderMobile", m.senderMobile); put("receiverMobile", m.receiverMobile)
            put("timestamp", m.timestamp); put("timeString", m.timeString)
            put("fileUrl", m.fileUrl ?: ""); put("fileName", m.fileName ?: "")
            put("fileType", m.fileType ?: ""); put("fileSizeBytes", m.fileSizeBytes)
            put("thumbnailUrl", m.thumbnailUrl ?: "")
            put("reaction", m.reaction ?: ""); put("read", m.read); put("delivered", m.delivered)
            put("isCallLog", m.isCallLog); put("callStatus", m.callStatus ?: "")
            put("callType", m.callType ?: ""); put("replyToId", m.replyToId ?: "")
            put("replyToText", m.replyToText ?: ""); put("replyToSender", m.replyToSender ?: "")
            put("isDeleted", m.isDeleted); put("isForwarded", m.isForwarded)
            put("isStarred", m.isStarred); put("duration", m.duration)
        })
    }
    return JSONObject().apply {
        put("version", 4)
        put("exportTime", System.currentTimeMillis())
        put("messages", arr)
    }.toString(2)
}

private fun jsonToMessages(json: String, chatId: String): List<CachedMessage> = try {
    val obj = JSONObject(json)
    val arr = obj.optJSONArray("messages") ?: return emptyList()
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        CachedMessage(
            id             = o.optString("id"),
            chatId         = chatId,
            text           = o.optString("text"),
            senderMobile   = o.optString("senderMobile"),
            receiverMobile = o.optString("receiverMobile"),
            timestamp      = o.optLong("timestamp"),
            timeString     = o.optString("timeString"),
            fileUrl        = o.optString("fileUrl").ifBlank { null },
            fileName       = o.optString("fileName").ifBlank { null },
            fileType       = o.optString("fileType").ifBlank { null },
            fileSizeBytes  = o.optLong("fileSizeBytes"),
            thumbnailUrl   = o.optString("thumbnailUrl").ifBlank { null },
            reaction       = o.optString("reaction").ifBlank { null },
            read           = o.optBoolean("read"),
            delivered      = o.optBoolean("delivered"),
            isCallLog      = o.optBoolean("isCallLog"),
            callStatus     = o.optString("callStatus").ifBlank { null },
            callType       = o.optString("callType").ifBlank { null },
            replyToId      = o.optString("replyToId").ifBlank { null },
            replyToText    = o.optString("replyToText").ifBlank { null },
            replyToSender  = o.optString("replyToSender").ifBlank { null },
            isDeleted      = o.optBoolean("isDeleted"),
            isForwarded    = o.optBoolean("isForwarded"),
            isStarred      = o.optBoolean("isStarred"),
            duration       = o.optInt("duration"),
            isArchived     = true
        )
    }
} catch (e: Exception) { Log.e(TAG, "jsonToMessages failed: ${e.message}"); emptyList() }

// ============================================================
// MAIN SYNC ENGINE
// ============================================================

object RasGramDriveSyncEngine {

    /**
     * UPLOAD: একটা chat এর সব messages → Drive upsert + media upload
     * Safe delete: Drive এ confirm হলে তবেই Firestore/Cloudinary থেকে মুছবে
     * Returns: Triple(msgs uploaded, media uploaded, cloudinary deleted)
     */
    private suspend fun uploadChat(
        drive: Drive,
        rasGramFolderId: String,
        chatId: String,
        contactMobile: String,
        repo: RasGramRepository,
        db: FirebaseFirestore,
        onProgress: suspend (String) -> Unit
    ): Triple<Int, Int, Int> = withContext(Dispatchers.IO) {

        // Room থেকে সব messages (archived + active, সব)
        val allMessages = repo.messageDao.getAllMessagesForChat(chatId)
        if (allMessages.isEmpty()) return@withContext Triple(0, 0, 0)

        onProgress("$chatId uploading ${allMessages.size} msgs…")

        // ── Drive folder ─────────────────────────────────────────────────
        val chatFolderId = getOrCreateFolder(drive, chatId, rasGramFolderId)

        // ── JSON upsert (merge with existing Drive data) ─────────────────
        val driveFileId = upsertJsonToDrive(drive, chatFolderId, chatId, allMessages)
        Log.i(TAG, "JSON upserted $chatId → $driveFileId (${allMessages.size} msgs)")

        // Drive confirm হলে archived mark
        repo.messageDao.markAllMessagesArchived(chatId, driveFileId)

        // ── Media → Drive (না থাকলেই upload) ────────────────────────────
        val mediaFolderId = getOrCreateFolder(drive, "media", chatFolderId)
        var mediaUploaded = 0
        var cldDeleted    = 0

        val mediaMessages = allMessages.filter { !it.fileUrl.isNullOrEmpty() && !it.isDeleted }
        mediaMessages.forEach { msg ->
            val url  = msg.fileUrl ?: return@forEach
            val name = msg.fileName ?: "media_${msg.timestamp}"
            try {
                val alreadyInDrive = driveFileExists(drive, mediaFolderId, name)
                if (!alreadyInDrive) {
                    val conn = URL(url).openConnection().apply { connect() }
                    val mime = msg.fileType ?: "application/octet-stream"
                    val meta = DriveFile().apply { this.name = name; parents = listOf(mediaFolderId) }
                    drive.files().create(meta, InputStreamContent(mime, conn.getInputStream()))
                        .setFields("id").execute()
                    mediaUploaded++
                    Log.d(TAG, "Media uploaded: $name")
                }

                // ── SAFE DELETE: Drive এ আছে confirm → তারপর Cloudinary delete ──
                val confirmedInDrive = alreadyInDrive || driveFileExists(drive, mediaFolderId, name)
                if (confirmedInDrive) {
                    val pubId = extractCloudinaryPublicId(url)
                    if (pubId != null) {
                        val resType = cloudinaryResourceType(msg.fileType)
                        if (deleteFromCloudinary(pubId, resType)) cldDeleted++
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Media processing failed for $name: ${e.message}")
            }
        }

        // ── SAFE DELETE: Drive JSON confirm → Firestore delete ───────────
        // Drive এ file আছে নিশ্চিত (আমরাই মাত্র upsert করলাম), তাই safe
        try {
            var hasMore = true
            while (hasMore) {
                val snap = db.collection("pvt_msg_$chatId")
                    .limit(400).get().await()
                if (snap.isEmpty) break
                val batch = db.batch()
                snap.documents.forEach { batch.delete(it.reference) }
                batch.commit().await()
                Log.d(TAG, "Firestore: deleted ${snap.size()} msgs from pvt_msg_$chatId")
                if (snap.size() < 400) hasMore = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firestore delete non-critical: ${e.message}")
        }

        // ChatPreview Drive info update
        repo.chatPreviewDao.updateDriveInfo(contactMobile, chatFolderId, System.currentTimeMillis())

        Triple(allMessages.size, mediaUploaded, cldDeleted)
    }

    /**
     * DOWNLOAD: Drive এ যে chat folders আছে, Room এ না থাকলে import করো
     * অন্য phone এ same account login করলে সব chat চলে আসবে
     */
    private suspend fun downloadMissingChats(
        drive: Drive,
        rasGramFolderId: String,
        repo: RasGramRepository,
        onProgress: suspend (String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        var imported = 0
        val driveFolders = listDriveChatFolders(drive, rasGramFolderId)
        driveFolders.forEach { folder ->
            val chatId = folder.name ?: return@forEach
            val folderId = folder.id ?: return@forEach

            // Room এ এই chat এর কোনো message আছে কিনা দেখো
            val roomCount = repo.messageDao.getMessageCountForChat(chatId)
            if (roomCount > 0) return@forEach  // আছে, skip

            onProgress("Importing $chatId from Drive…")

            // Drive থেকে JSON download
            val fileId = findExistingJsonFile(drive, folderId, chatId) ?: return@forEach
            try {
                val out = ByteArrayOutputStream()
                drive.files().get(fileId).executeMediaAndDownloadTo(out)
                val messages = jsonToMessages(out.toString(Charsets.UTF_8.name()), chatId)
                if (messages.isNotEmpty()) {
                    repo.messageDao.upsertMessages(messages)
                    imported += messages.size
                    Log.i(TAG, "Imported ${messages.size} msgs for $chatId from Drive")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Download failed for $chatId: ${e.message}")
            }
        }
        imported
    }

    /**
     * Full bi-directional sync:
     *   1. Room → Drive (upload all chats)
     *   2. Drive → Room (download missing chats)
     * Progress callback → Worker notification এ percentage দেখায়
     */
    suspend fun performSync(
        context: Context,
        onProgress: suspend (current: Int, total: Int, label: String) -> Unit = { _, _, _ -> }
    ): DriveSyncResult = withContext(Dispatchers.IO) {
        val startTime    = System.currentTimeMillis()
        val accountEmail = RasGramDriveAccountManager.getDefaultSyncAccount(context)
            ?: return@withContext DriveSyncResult(false, errorMessage = "Google Drive account সংযুক্ত নেই।")

        val drive = RasGramDriveAccountManager.buildDriveForAccount(context, accountEmail)
            ?: return@withContext DriveSyncResult(
                false, errorMessage = "Drive সংযোগ ব্যর্থ। আবার sign-in করুন.",
                accountUsed = accountEmail)

        try {
            val repo          = RasGramRepository.getInstance(context)
            val db            = FirebaseFirestore.getInstance()
            val rasGramFolder = getRasGramRootFolderId(context, drive)
            val previews      = repo.chatPreviewDao.getAllPreviews()
            val total         = previews.size + 1  // +1 for download phase

            var totalChats = 0; var totalMsgs = 0; var totalMedia = 0
            var totalCld   = 0; var totalDownloaded = 0

            // ── Phase 1: UPLOAD (Room → Drive) ────────────────────────────
            previews.forEachIndexed { idx, preview ->
                val chatId = repo.messageDao.getChatIdByContactMobile(preview.contactMobile)
                    ?: return@forEachIndexed
                onProgress(idx + 1, total, "Uploading: ${preview.contactName.ifBlank { preview.contactMobile }}")
                val (msgs, media, cld) = uploadChat(
                    drive, rasGramFolder, chatId, preview.contactMobile, repo, db
                ) { label -> Log.d(TAG, label) }
                if (msgs > 0) { totalChats++; totalMsgs += msgs; totalMedia += media; totalCld += cld }
            }

            // ── Phase 2: DOWNLOAD (Drive → Room, missing chats only) ──────
            onProgress(total, total, "Checking Drive for missing chats…")
            totalDownloaded = downloadMissingChats(drive, rasGramFolder, repo) { label ->
                Log.d(TAG, label)
            }

            RasGramDriveAccountManager.recordSyncTime(context)
            DriveSyncResult(
                success               = true,
                syncedChats           = totalChats,
                syncedMessages        = totalMsgs,
                syncedMediaFiles      = totalMedia,
                deletedFromCloudinary = totalCld,
                downloadedFromDrive   = totalDownloaded,
                accountUsed           = accountEmail,
                durationMs            = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "performSync failed: ${e.message}", e)
            DriveSyncResult(false, errorMessage = e.message, accountUsed = accountEmail)
        }
    }

    /**
     * READ: একটা chat এর archived messages Drive থেকে Room এ load করো।
     * "Load older messages" button থেকে call করো।
     */
    suspend fun loadArchivedMessages(context: Context, chatId: String): List<CachedMessage> =
        withContext(Dispatchers.IO) {
            try {
                val drive = RasGramDriveAccountManager.buildDefaultDrive(context)
                    ?: return@withContext emptyList<CachedMessage>()
                        .also { Log.w(TAG, "loadArchived: no drive available") }

                val repo          = RasGramRepository.getInstance(context)
                val rasGramFolder = getRasGramRootFolderId(context, drive)
                val chatFolderId  = getOrCreateFolder(drive, chatId, rasGramFolder)
                val fileId        = findExistingJsonFile(drive, chatFolderId, chatId)
                    ?: return@withContext emptyList<CachedMessage>()
                        .also { Log.i(TAG, "loadArchived: no archive for $chatId") }

                val out      = ByteArrayOutputStream()
                drive.files().get(fileId).executeMediaAndDownloadTo(out)
                val messages = jsonToMessages(out.toString(Charsets.UTF_8.name()), chatId)
                repo.messageDao.upsertMessages(messages)
                Log.i(TAG, "Loaded ${messages.size} archived msgs for $chatId")
                messages
            } catch (e: Exception) {
                Log.e(TAG, "loadArchivedMessages failed: ${e.message}")
                emptyList()
            }
        }
}

// ============================================================
// WORKMANAGER WORKER — percentage progress notification
// ============================================================

class RasGramDriveSyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    private val notifMgr by lazy {
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Drive sync worker starting…")
        ensureChannel()

        // Android 14+ foreground service type required for long-running background work
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            setForeground(
                ForegroundInfo(
                    NOTIF_ID,
                    buildProgressNotif("Drive sync শুরু হচ্ছে…", 0, 0),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            )
        } else {
            setForeground(ForegroundInfo(NOTIF_ID, buildProgressNotif("Drive sync শুরু হচ্ছে…", 0, 0)))
        }

        val result = RasGramDriveSyncEngine.performSync(applicationContext) { current, total, label ->
            val pct = if (total > 0) (current * 100 / total) else 0
            notifMgr.notify(NOTIF_ID, buildProgressNotif("$pct% — $label", current, total))
        }

        if (result.success) {
            val summary = buildString {
                append("✅ Sync সম্পন্ন")
                if (result.syncedMessages > 0)
                    append(" • ${result.syncedMessages} msgs uploaded")
                if (result.syncedMediaFiles > 0)
                    append(" • ${result.syncedMediaFiles} media")
                if (result.deletedFromCloudinary > 0)
                    append(" • ${result.deletedFromCloudinary} Cloudinary freed")
                if (result.downloadedFromDrive > 0)
                    append(" • ${result.downloadedFromDrive} msgs imported from Drive")
                result.durationMs.let { if (it > 0) append(" (${it / 1000}s)") }
            }
            notifMgr.notify(
                NOTIF_ID,
                NotificationCompat.Builder(applicationContext, NOTIF_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("RasGram Sync সম্পন্ন")
                    .setContentText(summary)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()
            )
        } else {
            notifMgr.cancel(NOTIF_ID)
        }

        return if (result.success) Result.success() else Result.retry()
    }

    private fun buildProgressNotif(label: String, current: Int, total: Int) =
        NotificationCompat.Builder(applicationContext, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("RasGram Drive Sync")
            .setContentText(label)
            .apply {
                if (total > 0) setProgress(total, current, false)
                else setProgress(0, 0, true)
            }
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifMgr.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "Drive Sync", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}

// ============================================================
// SCHEDULER
// ============================================================

object RasGramDriveSyncScheduler {

    fun schedule(context: Context) {
        if (!RasGramDriveAccountManager.isDriveAvailable(context)) return
        val req = PeriodicWorkRequestBuilder<RasGramDriveSyncWorker>(24, TimeUnit.HOURS, 6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(30, TimeUnit.MINUTES)
            .addTag(SYNC_WORK_NAME)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(SYNC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        Log.i(TAG, "Daily Drive sync scheduled")
    }

    fun runNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<RasGramDriveSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag("$SYNC_WORK_NAME.manual")
            .build()
        WorkManager.getInstance(context).enqueue(req)
        Log.i(TAG, "Manual Drive sync triggered")
    }

    fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME)
}

// Backward-compat
object RasGramArchiveScheduler {
    fun schedule(context: Context) = RasGramDriveSyncScheduler.schedule(context)
    fun runNow(context: Context)   = RasGramDriveSyncScheduler.runNow(context)
    fun cancel(context: Context)   = RasGramDriveSyncScheduler.cancel(context)
}

package com.rasel.RasFocus.selfcontrol.rasgram

// ============================================================
// RasGramDriveSyncManager.kt  — v2 (unified engine)
//
// পুরনো RasGramArchiveWorker + RasGramDriveSyncWorker merge করা হয়েছে।
//
// Flow (একটা Worker, একটাই job):
//   1. প্রতিটা chat এর 7-day-old messages বের করো
//   2. Drive এ upsert করো (নতুন file নয়, একটাই file update)
//   3. Media → Drive copy (না থাকলেই শুধু upload)
//   4. Drive confirm → Cloudinary থেকে delete
//   5. Drive confirm → Firestore থেকে delete
//   6. Room এ archived mark + পুরানো rows delete
//
// Read flow:
//   loadArchivedMessages(chatId) → Drive থেকে JSON নামাও → Room cache
//
// Scheduler:
//   RasGramDriveSyncScheduler.schedule()  — 24h periodic
//   RasGramDriveSyncScheduler.runNow()    — manual Sync Now
//   RasGramArchiveScheduler → এখন এই scheduler কে delegate করে (backward compat)
// ============================================================

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
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
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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

private const val TAG             = "RasGramDriveSync"
private const val SYNC_WORK_NAME  = "RasGramDriveSync"
private const val NOTIF_CHANNEL   = "rasgram_drive_sync"
private const val NOTIF_ID        = 9902
private const val ARCHIVE_DAYS    = 7L
private const val ARCHIVE_MS      = ARCHIVE_DAYS * 24 * 60 * 60 * 1000L

// Drive folder structure
private const val ROOT_FOLDER    = "RasFocus+"
private const val RASGRAM_FOLDER = "RasGram"

// SharedPreference keys
private const val PREF_FILE              = "rasgram_drive_sync_prefs"
private const val KEY_SYNC_ACCOUNTS      = "sync_accounts"
private const val KEY_DEFAULT_ACCOUNT    = "default_account"
private const val KEY_LAST_SYNC          = "last_sync_time"
private const val KEY_RASGRAM_FOLDER_ID  = "rasgram_folder_id"

// Cloudinary credentials
private const val CLD_CLOUD   = "de2w78yxh"
private const val CLD_API_KEY = "292749814534824"
private const val CLD_SECRET  = "EEYmph3nZLR8Modypt0J7eH--58"

// ============================================================
// MULTI-ACCOUNT MANAGER (পুরনো interface অপরিবর্তিত)
// ============================================================

object RasGramDriveAccountManager {

    fun getRasFocusAccount(context: Context): GoogleSignInAccount? =
        try { GoogleSignIn.getLastSignedInAccount(context) } catch (_: Exception) { null }

    fun getSyncAccounts(context: Context): List<String> {
        val prefs = prefs(context)
        val set   = prefs.getStringSet(KEY_SYNC_ACCOUNTS, emptySet()) ?: emptySet()
        val all   = set.toMutableSet()
        getRasFocusAccount(context)?.email?.let { all.add(it) }
        return all.toList().sorted()
    }

    fun addSyncAccount(context: Context, email: String) {
        val p   = prefs(context)
        val cur = p.getStringSet(KEY_SYNC_ACCOUNTS, emptySet())?.toMutableSet() ?: mutableSetOf()
        cur.add(email)
        p.edit().putStringSet(KEY_SYNC_ACCOUNTS, cur).apply()
        if (getDefaultSyncAccount(context) == null) setDefaultSyncAccount(context, email)
    }

    fun removeSyncAccount(context: Context, email: String) {
        val rasFocusEmail = getRasFocusAccount(context)?.email
        if (email == rasFocusEmail) return
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
        .readTimeout(30, TimeUnit.SECONDS)
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

/** chatId এর messages JSON file ID — Drive에 있으면 반환, 없으면 null */
private suspend fun findExistingJsonFile(
    drive: Drive, chatFolderId: String, chatId: String
): String? = withContext(Dispatchers.IO) {
    val q = "'$chatFolderId' in parents and name = 'messages_$chatId.json' and trashed = false"
    drive.files().list().setQ(q).setFields("files(id)").execute()
        .files?.firstOrNull()?.id
}

/** Upsert: file আগে থাকলে update, না থাকলে create */
private suspend fun upsertJsonToDrive(
    drive: Drive, chatFolderId: String, chatId: String, jsonContent: String
): String = withContext(Dispatchers.IO) {
    val bytes   = jsonContent.toByteArray(Charsets.UTF_8)
    val content = ByteArrayContent("application/json", bytes)
    val fileName = "messages_$chatId.json"

    val existingId = findExistingJsonFile(drive, chatFolderId, chatId)
    if (existingId != null) {
        // Update existing — নতুন file বানাবে না, duplicate হবে না
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
        val rootId     = getOrCreateFolder(drive, ROOT_FOLDER)
        val rasGramId  = getOrCreateFolder(drive, RASGRAM_FOLDER, rootId)
        prefs.edit().putString(KEY_RASGRAM_FOLDER_ID, rasGramId).apply()
        rasGramId
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
        put("version", 3)
        put("exportTime", System.currentTimeMillis())
        put("archiveDays", ARCHIVE_DAYS)
        put("messages", arr)
    }.toString(2)
}

private fun jsonToMessages(json: String, chatId: String): List<CachedMessage> {
    return try {
    val obj  = JSONObject(json)
    val arr  = obj.optJSONArray("messages") ?: return emptyList()
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
}

// ============================================================
// MAIN SYNC ENGINE
// ============================================================

object RasGramDriveSyncEngine {

    /**
     * একটা chat এর full archive + cleanup cycle।
     * Returns: Triple(messages archived, media uploaded, cloudinary deleted)
     */
    private suspend fun processChat(
        drive: Drive,
        rasGramFolderId: String,
        chatId: String,
        contactMobile: String,
        repo: RasGramRepository,
        db: FirebaseFirestore,
        cutoff: Long
    ): Triple<Int, Int, Int> = withContext(Dispatchers.IO) {

        // ── Step 1: পুরানো, un-archived messages ────────────────────────
        val messages = repo.messageDao.getMessagesForArchive(chatId, cutoff)
        if (messages.isEmpty()) return@withContext Triple(0, 0, 0)

        // ── Step 2: Drive folder ─────────────────────────────────────────
        val chatFolderId = getOrCreateFolder(drive, chatId, rasGramFolderId)

        // ── Step 3: JSON upsert (একটাই file, duplicate নেই) ─────────────
        val json        = messagesToJson(messages)
        val driveFileId = upsertJsonToDrive(drive, chatFolderId, chatId, json)
        Log.i(TAG, "JSON upserted for $chatId → $driveFileId (${messages.size} msgs)")

        // ── Step 4: Room এ archived mark ─────────────────────────────────
        repo.messageDao.markMessagesArchived(chatId, cutoff, driveFileId)

        // ── Step 5: Media → Drive copy + Cloudinary delete ───────────────
        val mediaFolderId = getOrCreateFolder(drive, "media", chatFolderId)
        var mediaUploaded = 0
        var cldDeleted    = 0

        messages.filter { !it.fileUrl.isNullOrEmpty() && !it.isDeleted }.forEach { msg ->
            val url  = msg.fileUrl ?: return@forEach
            val name = msg.fileName ?: "media_${msg.timestamp}"
            try {
                // Drive এ না থাকলেই upload
                if (!driveFileExists(drive, mediaFolderId, name)) {
                    val conn  = URL(url).openConnection().apply { connect() }
                    val mime  = msg.fileType ?: "application/octet-stream"
                    val meta  = DriveFile().apply { this.name = name; parents = listOf(mediaFolderId) }
                    drive.files().create(meta, InputStreamContent(mime, conn.getInputStream()))
                        .setFields("id").execute()
                    mediaUploaded++
                    Log.d(TAG, "Media uploaded: $name")
                }
                // Drive তে আছে নিশ্চিত → Cloudinary থেকে delete
                val pubId = extractCloudinaryPublicId(url)
                if (pubId != null) {
                    val resType = cloudinaryResourceType(msg.fileType)
                    if (deleteFromCloudinary(pubId, resType)) cldDeleted++
                }
            } catch (e: Exception) {
                Log.w(TAG, "Media processing failed for $name: ${e.message}")
            }
        }

        // ── Step 6: Firestore থেকে delete (batch, 400 limit) ────────────
        try {
            var remaining = true
            while (remaining) {
                val snap = db.collection("pvt_msg_$chatId")
                    .whereLessThan("timestamp", cutoff)
                    .limit(400)
                    .get().await()
                if (snap.isEmpty) break
                val batch = db.batch()
                snap.documents.forEach { batch.delete(it.reference) }
                batch.commit().await()
                Log.d(TAG, "Firestore: deleted ${snap.size()} msgs from pvt_msg_$chatId")
                if (snap.size() < 400) remaining = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firestore delete non-critical: ${e.message}")
        }

        // ── Step 7: Room থেকে delete (starred বাদে) ─────────────────────
        repo.messageDao.deleteChatMessagesBefore(chatId, cutoff)

        // ── Step 8: ChatPreview এ Drive info update ──────────────────────
        repo.chatPreviewDao.updateDriveInfo(contactMobile, chatFolderId, System.currentTimeMillis())

        Triple(messages.size, mediaUploaded, cldDeleted)
    }

    /**
     * Full sync without age cutoff — "Sync Now" button এ ব্যবহার হয়।
     * Worker এর মতোই, কিন্তু cutoff = 0L মানে সব messages (নতুন সহ) upload হবে।
     */
    suspend fun performFullSync(context: Context): DriveSyncResult = withContext(Dispatchers.IO) {
        val startTime    = System.currentTimeMillis()
        val accountEmail = RasGramDriveAccountManager.getDefaultSyncAccount(context)
            ?: return@withContext DriveSyncResult(false, errorMessage = "Google Drive account সংযুক্ত নেই।")

        val drive = RasGramDriveAccountManager.buildDriveForAccount(context, accountEmail)
            ?: return@withContext DriveSyncResult(false,
                errorMessage = "Drive সংযোগ ব্যর্থ। আবার sign-in করুন।",
                accountUsed = accountEmail)

        try {
            val repo           = RasGramRepository.getInstance(context)
            val db             = FirebaseFirestore.getInstance()
            val rasGramFolder  = getRasGramRootFolderId(context, drive)
            val previews       = repo.chatPreviewDao.getAllPreviews()
            val cutoff         = Long.MAX_VALUE  // সব messages — কোনো age filter নেই

            var totalChats = 0; var totalMsgs = 0; var totalMedia = 0; var totalCld = 0

            previews.forEach { preview ->
                val chatId = repo.messageDao.getChatIdByContactMobile(preview.contactMobile)
                    ?: return@forEach
                val (msgs, media, cld) = processChat(
                    drive, rasGramFolder, chatId,
                    preview.contactMobile, repo, db, cutoff
                )
                if (msgs > 0) { totalChats++; totalMsgs += msgs; totalMedia += media; totalCld += cld }
            }

            RasGramDriveAccountManager.recordSyncTime(context)
            DriveSyncResult(
                success               = true,
                syncedChats           = totalChats,
                syncedMessages        = totalMsgs,
                syncedMediaFiles      = totalMedia,
                deletedFromCloudinary = totalCld,
                accountUsed           = accountEmail,
                durationMs            = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "performFullSync failed: ${e.message}", e)
            DriveSyncResult(false, errorMessage = e.message, accountUsed = accountEmail)
        }
    }

    /** সব chats sync করে — Worker থেকে call হয় */
    suspend fun performSync(context: Context): DriveSyncResult = withContext(Dispatchers.IO) {
        val startTime    = System.currentTimeMillis()
        val accountEmail = RasGramDriveAccountManager.getDefaultSyncAccount(context)
            ?: return@withContext DriveSyncResult(false, errorMessage = "Google Drive account সংযুক্ত নেই।")

        val drive = RasGramDriveAccountManager.buildDriveForAccount(context, accountEmail)
            ?: return@withContext DriveSyncResult(false,
                errorMessage = "Drive সংযোগ ব্যর্থ। আবার sign-in করুন।",
                accountUsed = accountEmail)

        try {
            val repo           = RasGramRepository.getInstance(context)
            val db             = FirebaseFirestore.getInstance()
            val rasGramFolder  = getRasGramRootFolderId(context, drive)
            val previews       = repo.chatPreviewDao.getAllPreviews()
            val cutoff         = System.currentTimeMillis() - ARCHIVE_MS

            var totalChats = 0; var totalMsgs = 0; var totalMedia = 0; var totalCld = 0

            previews.forEach { preview ->
                val chatId = repo.messageDao.getChatIdByContactMobile(preview.contactMobile)
                    ?: return@forEach
                val (msgs, media, cld) = processChat(
                    drive, rasGramFolder, chatId,
                    preview.contactMobile, repo, db, cutoff
                )
                if (msgs > 0) { totalChats++; totalMsgs += msgs; totalMedia += media; totalCld += cld }
            }

            RasGramDriveAccountManager.recordSyncTime(context)
            DriveSyncResult(
                success              = true,
                syncedChats          = totalChats,
                syncedMessages       = totalMsgs,
                syncedMediaFiles     = totalMedia,
                deletedFromCloudinary = totalCld,
                accountUsed          = accountEmail,
                durationMs           = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "performSync failed: ${e.message}", e)
            DriveSyncResult(false, errorMessage = e.message, accountUsed = accountEmail)
        }
    }

    /**
     * READ: পুরানো archived messages Drive থেকে load করে Room এ cache করো।
     * UI: "Load older messages" বাটন থেকে call করো।
     */
    suspend fun loadArchivedMessages(
        context: Context,
        chatId: String
    ): List<CachedMessage> = withContext(Dispatchers.IO) {
        try {
            val drive = RasGramDriveAccountManager.buildDefaultDrive(context)
                ?: return@withContext emptyList<CachedMessage>()
                    .also { Log.w(TAG, "loadArchived: no drive available") }

            val repo          = RasGramRepository.getInstance(context)
            val rasGramFolder = getRasGramRootFolderId(context, drive)
            val chatFolderId  = getOrCreateFolder(drive, chatId, rasGramFolder)

            // Drive এ এই chat এর JSON file খোঁজো
            val fileId = findExistingJsonFile(drive, chatFolderId, chatId)
                ?: return@withContext emptyList<CachedMessage>()
                    .also { Log.i(TAG, "loadArchived: no archive found for $chatId") }

            // Download করো
            val out    = ByteArrayOutputStream()
            drive.files().get(fileId).executeMediaAndDownloadTo(out)
            val json   = out.toString(Charsets.UTF_8.name())

            // Parse করো
            val messages = jsonToMessages(json, chatId)

            // Room এ cache করো (পরে আর Drive এ যেতে হবে না)
            repo.messageDao.upsertMessages(messages)
            Log.i(TAG, "Loaded ${messages.size} archived msgs for $chatId from Drive")
            messages
        } catch (e: Exception) {
            Log.e(TAG, "loadArchivedMessages failed: ${e.message}")
            emptyList()
        }
    }
}

// ============================================================
// WORKMANAGER WORKER
// ============================================================

class RasGramDriveSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    // inputData key — runNow এ true পাঠালে full sync (no cutoff)
    private val isManual get() = inputData.getBoolean("is_manual", false)

    override suspend fun doWork(): Result {
        Log.i(TAG, "Drive sync worker starting... manual=$isManual")
        showNotification("Drive sync শুরু হচ্ছে…")
        val result = if (isManual)
            RasGramDriveSyncEngine.performFullSync(applicationContext)
        else
            RasGramDriveSyncEngine.performSync(applicationContext)
        if (result.success) {
            val summary = buildString {
                if (result.syncedMessages > 0) {
                    append("✅ ${result.syncedMessages} messages archived")
                    if (result.syncedMediaFiles > 0) append(", ${result.syncedMediaFiles} media uploaded")
                    if (result.deletedFromCloudinary > 0) append(", ${result.deletedFromCloudinary} Cloudinary files freed")
                } else {
                    append("✅ Sync সম্পন্ন — নতুন archive নেই")
                }
                result.durationMs.let { if (it > 0) append(" (${it / 1000}s)") }
            }
            showCompleteNotification(summary)
        } else {
            cancelNotification()
        }
        return if (result.success) Result.success() else Result.retry()
    }

    private fun mgr() =
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr().createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "Drive Sync", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun showNotification(text: String) {
        ensureChannel()
        mgr().notify(NOTIF_ID,
            NotificationCompat.Builder(applicationContext, NOTIF_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("RasGram Drive Sync").setContentText(text)
                .setProgress(0, 0, true).setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW).build()
        )
    }

    private fun showCompleteNotification(text: String) {
        ensureChannel()
        mgr().notify(NOTIF_ID,
            NotificationCompat.Builder(applicationContext, NOTIF_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("RasGram Sync সম্পন্ন").setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
        )
    }

    private fun cancelNotification() = mgr().cancel(NOTIF_ID)
}

// ============================================================
// SCHEDULER — একটাই, পুরানো RasGramArchiveScheduler এখন এখানে delegate করে
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
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SYNC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req
        )
        Log.i(TAG, "Daily Drive sync scheduled")
    }

    fun runNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<RasGramDriveSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(androidx.work.workDataOf("is_manual" to true))
            .addTag("$SYNC_WORK_NAME.manual").build()
        WorkManager.getInstance(context).enqueue(req)
        Log.i(TAG, "Manual Drive sync triggered (full sync, no age filter)")
    }

    fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME)
}

// ── Backward-compat: পুরানো RasGramArchiveScheduler call → নতুন scheduler তে delegate ──
object RasGramArchiveScheduler {
    fun schedule(context: Context) = RasGramDriveSyncScheduler.schedule(context)
    fun runNow(context: Context)   = RasGramDriveSyncScheduler.runNow(context)
    fun cancel(context: Context)   = RasGramDriveSyncScheduler.cancel(context)
}

package com.rasel.RasFocus.selfcontrol.rasgram

// ============================================================
// RasGramDriveArchive.kt
//
// WhatsApp-style message offload to user's own Google Drive.
//
// Flow:
//   1. App open / manual trigger → checkAndArchive()
//   2. 7-day পুরানো, read messages → JSON batch → Drive upload
//   3. Cloudinary media (যদি থাকে) → Drive এ copy → Cloudinary delete
//   4. Firestore + Room থেকে পুরানো messages delete
//   5. Read করতে চাইলে → Drive থেকে fetch → Room এ cache → UI show
//
// Drive folder structure:
//   RasFocus+/
//     RasGram/
//       <chatId>/
//         messages_<timestamp>.json   ← text + metadata
//         media_<filename>            ← audio, doc files (images Cloudinary এ থাকে)
// ============================================================

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

private const val TAG = "RasGramDriveArchive"

// ── কত দিন পুরানো messages archive করবো ──────────────────────
private const val ARCHIVE_AFTER_DAYS = 7L
private const val ARCHIVE_THRESHOLD_MS = ARCHIVE_AFTER_DAYS * 24 * 60 * 60 * 1000L

// ── Drive এ RasGram folder এর নাম ────────────────────────────
private const val RASFOCUS_FOLDER = "RasFocus+"
private const val RASGRAM_FOLDER  = "RasGram"

// ── SharedPreference key — Drive folder IDs cache ─────────────
private const val PREF_DRIVE_ARCHIVE = "rasgram_drive_archive_prefs"
private const val KEY_ROOT_FOLDER    = "rasgram_drive_folder_id"

// =============================================================
// RESULT TYPE
// =============================================================

data class ArchiveResult(
    val success: Boolean,
    val archivedChats: Int = 0,
    val archivedMessages: Int = 0,
    val errorMessage: String? = null
)

// =============================================================
// MAIN ARCHIVE OBJECT
// =============================================================

object RasGramDriveArchive {

    // ── Drive service build ──────────────────────────────────
    private fun buildDrive(context: Context): Drive? {
        return try {
            val account = GoogleSignIn.getLastSignedInAccount(context) ?: run {
                Log.w(TAG, "No Google account signed in")
                return null
            }
            val credential = GoogleAccountCredential.usingOAuth2(
                context, listOf(DriveScopes.DRIVE_FILE)
            ).apply { selectedAccountName = account.email }

            Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            ).setApplicationName("RasFocus+").build()
        } catch (e: Exception) {
            Log.e(TAG, "buildDrive failed: ${e.message}")
            null
        }
    }

    // ── Drive এ folder খোঁজো অথবা বানাও ────────────────────
    private suspend fun getOrCreateFolder(
        drive: Drive,
        name: String,
        parentId: String = "root"
    ): String = withContext(Dispatchers.IO) {
        // খোঁজো
        val query = "'$parentId' in parents and name = '$name' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val result = drive.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id,name)")
            .execute()

        result.files?.firstOrNull()?.id ?: run {
            // না পেলে বানাও
            val meta = DriveFile().apply {
                this.name = name
                mimeType = "application/vnd.google-apps.folder"
                parents = listOf(parentId)
            }
            drive.files().create(meta).setFields("id").execute().id
        }
    }

    // ── RasGram root folder ID পাও (cached) ─────────────────
    private suspend fun getRasGramFolderId(context: Context, drive: Drive): String {
        val prefs = context.getSharedPreferences(PREF_DRIVE_ARCHIVE, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_ROOT_FOLDER, null)
        if (!cached.isNullOrEmpty()) return cached

        val rasFocusId  = getOrCreateFolder(drive, RASFOCUS_FOLDER)
        val rasGramId   = getOrCreateFolder(drive, RASGRAM_FOLDER, rasFocusId)
        prefs.edit().putString(KEY_ROOT_FOLDER, rasGramId).apply()
        return rasGramId
    }

    // ── Messages → JSON string ───────────────────────────────
    private fun messagesToJson(messages: List<CachedMessage>): String {
        val arr = JSONArray()
        messages.forEach { msg ->
            arr.put(JSONObject().apply {
                put("id", msg.id)
                put("text", msg.text)
                put("senderMobile", msg.senderMobile)
                put("receiverMobile", msg.receiverMobile)
                put("timestamp", msg.timestamp)
                put("timeString", msg.timeString)
                put("fileUrl", msg.fileUrl ?: "")
                put("fileName", msg.fileName ?: "")
                put("fileType", msg.fileType ?: "")
                put("fileSizeBytes", msg.fileSizeBytes)
                put("reaction", msg.reaction ?: "")
                put("read", msg.read)
                put("delivered", msg.delivered)
                put("isCallLog", msg.isCallLog)
                put("callStatus", msg.callStatus ?: "")
                put("callType", msg.callType ?: "")
                put("replyToId", msg.replyToId ?: "")
                put("replyToText", msg.replyToText ?: "")
                put("replyToSender", msg.replyToSender ?: "")
                put("isDeleted", msg.isDeleted)
                put("isForwarded", msg.isForwarded)
                put("isStarred", msg.isStarred)
                put("duration", msg.duration)
            })
        }
        return JSONObject().apply {
            put("version", 1)
            put("exportTime", System.currentTimeMillis())
            put("messages", arr)
        }.toString(2)
    }

    // ── JSON string → CachedMessage list ────────────────────
    fun jsonToMessages(json: String, chatId: String): List<CachedMessage> {
        return try {
            val root = JSONObject(json)
            val arr  = root.getJSONArray("messages")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CachedMessage(
                    id            = o.getString("id"),
                    chatId        = chatId,
                    text          = o.optString("text"),
                    senderMobile  = o.optString("senderMobile"),
                    receiverMobile= o.optString("receiverMobile"),
                    timestamp     = o.optLong("timestamp"),
                    timeString    = o.optString("timeString"),
                    fileUrl       = o.optString("fileUrl").ifEmpty { null },
                    fileName      = o.optString("fileName").ifEmpty { null },
                    fileType      = o.optString("fileType").ifEmpty { null },
                    fileSizeBytes = o.optLong("fileSizeBytes"),
                    reaction      = o.optString("reaction").ifEmpty { null },
                    read          = o.optBoolean("read", true),
                    delivered     = o.optBoolean("delivered", true),
                    isCallLog     = o.optBoolean("isCallLog"),
                    callStatus    = o.optString("callStatus").ifEmpty { null },
                    callType      = o.optString("callType").ifEmpty { null },
                    replyToId     = o.optString("replyToId").ifEmpty { null },
                    replyToText   = o.optString("replyToText").ifEmpty { null },
                    replyToSender = o.optString("replyToSender").ifEmpty { null },
                    isDeleted     = o.optBoolean("isDeleted"),
                    isForwarded   = o.optBoolean("isForwarded"),
                    isStarred     = o.optBoolean("isStarred"),
                    duration      = o.optInt("duration"),
                    isArchived    = true
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "jsonToMessages parse error: ${e.message}")
            emptyList()
        }
    }

    // ── Drive এ JSON upload ──────────────────────────────────
    private suspend fun uploadJsonToDrive(
        drive: Drive,
        folderId: String,
        fileName: String,
        jsonContent: String
    ): String = withContext(Dispatchers.IO) {
        val bytes   = jsonContent.toByteArray(Charsets.UTF_8)
        val content = ByteArrayContent("application/json", bytes)
        val meta    = DriveFile().apply {
            name    = fileName
            parents = listOf(folderId)
        }
        drive.files().create(meta, content).setFields("id").execute().id
    }

    // ── Drive থেকে JSON download ─────────────────────────────
    suspend fun downloadJsonFromDrive(context: Context, driveFileId: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val drive = buildDrive(context) ?: return@withContext null
                val out   = ByteArrayOutputStream()
                drive.files().get(driveFileId).executeMediaAndDownloadTo(out)
                out.toString(Charsets.UTF_8.name())
            } catch (e: Exception) {
                Log.e(TAG, "downloadJsonFromDrive failed: ${e.message}")
                null
            }
        }

    // ── একটি chat archive করো ───────────────────────────────
    private suspend fun archiveChat(
        context: Context,
        drive: Drive,
        rasGramFolderId: String,
        chatId: String,
        contactMobile: String,
        repo: RasGramRepository,
        db: FirebaseFirestore
    ): Int {
        val cutoff   = System.currentTimeMillis() - ARCHIVE_THRESHOLD_MS
        val messages = repo.messageDao.getMessagesForArchive(chatId, cutoff)

        if (messages.isEmpty()) return 0

        // Chat এর Drive folder পাও
        val chatFolderId = getOrCreateFolder(drive, chatId, rasGramFolderId)

        // JSON বানাও
        val timestamp = System.currentTimeMillis()
        val jsonContent = messagesToJson(messages)
        val fileName    = "messages_${timestamp}.json"

        // Drive এ upload করো
        val driveFileId = uploadJsonToDrive(drive, chatFolderId, fileName, jsonContent)

        // Room এ archived mark করো
        repo.messageDao.markMessagesArchived(chatId, cutoff, driveFileId)

        // Room থেকে delete করো (starred বাদে)
        repo.messageDao.deleteChatMessagesBefore(chatId, cutoff)

        // Firestore থেকেও delete করো (cost কমাতে)
        try {
            val snapshot = db.collection("pvt_msg_$chatId")
                .whereLessThan("timestamp", cutoff)
                .get()
                .await()
            val batch = db.batch()
            snapshot.documents.forEach { doc ->
                batch.delete(doc.reference)
            }
            batch.commit().await()
        } catch (e: Exception) {
            Log.w(TAG, "Firestore delete failed (non-critical): ${e.message}")
        }

        // Chat preview এ Drive info update করো
        repo.chatPreviewDao.updateDriveInfo(contactMobile, chatFolderId, timestamp)

        Log.i(TAG, "Archived ${messages.size} messages from chat $chatId → Drive file $driveFileId")
        return messages.size
    }

    // ── Main: সব chats check করে archive trigger করো ───────
    suspend fun checkAndArchive(context: Context): ArchiveResult = withContext(Dispatchers.IO) {
        try {
            val drive = buildDrive(context) ?: return@withContext ArchiveResult(
                success = false,
                errorMessage = "Google Drive connected নেই। Settings → Storage → Drive Connect করুন।"
            )

            val repo             = RasGramRepository.getInstance(context)
            val db               = FirebaseFirestore.getInstance()
            val rasGramFolderId  = getRasGramFolderId(context, drive)
            val previews         = repo.chatPreviewDao.getAllPreviews()

            var totalArchivedChats    = 0
            var totalArchivedMessages = 0

            previews.forEach { preview ->
                // এই chat এর chatId বের করতে হবে — messages থেকে নিই
                val latestMsg = repo.messageDao.getLatestMessage(preview.contactMobile) ?: return@forEach
                val chatId    = latestMsg.chatId

                val archivedCount = archiveChat(
                    context, drive, rasGramFolderId,
                    chatId, preview.contactMobile, repo, db
                )
                if (archivedCount > 0) {
                    totalArchivedChats++
                    totalArchivedMessages += archivedCount
                }
            }

            ArchiveResult(
                success          = true,
                archivedChats    = totalArchivedChats,
                archivedMessages = totalArchivedMessages
            )
        } catch (e: Exception) {
            Log.e(TAG, "checkAndArchive failed: ${e.message}", e)
            ArchiveResult(success = false, errorMessage = e.message)
        }
    }

    // ── Archived messages পড়ো (Drive থেকে fetch করে Room এ cache) ──
    suspend fun loadArchivedMessages(
        context: Context,
        chatId: String,
        driveFileId: String
    ): List<CachedMessage> = withContext(Dispatchers.IO) {
        try {
            val repo = RasGramRepository.getInstance(context)

            // Drive থেকে JSON নামাও
            val json = downloadJsonFromDrive(context, driveFileId)
                ?: return@withContext emptyList()

            // Parse করো
            val messages = jsonToMessages(json, chatId)

            // Room এ cache করো (পরে আবার Drive যেতে না হয়)
            repo.messageDao.upsertMessages(messages)

            messages
        } catch (e: Exception) {
            Log.e(TAG, "loadArchivedMessages failed: ${e.message}")
            emptyList()
        }
    }

    // ── Drive connected কিনা check ────────────────────────────
    fun isDriveConnected(context: Context): Boolean {
        return try {
            val account = GoogleSignIn.getLastSignedInAccount(context)
            account != null
        } catch (e: Exception) { false }
    }

    // ── Storage usage info ────────────────────────────────────
    suspend fun getDriveStorageInfo(context: Context): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        try {
            val drive = buildDrive(context) ?: return@withContext null
            val about = drive.about().get().setFields("storageQuota").execute()
            val quota = about.storageQuota
            val used  = quota.usage ?: 0L
            val total = quota.limit ?: (15L * 1024 * 1024 * 1024) // 15 GB default free
            Pair(used, total)
        } catch (e: Exception) {
            Log.e(TAG, "getDriveStorageInfo failed: ${e.message}")
            null
        }
    }
}

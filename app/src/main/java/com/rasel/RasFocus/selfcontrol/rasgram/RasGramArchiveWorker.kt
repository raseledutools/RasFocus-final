package com.rasel.RasFocus.selfcontrol.rasgram

// ============================================================
// RasGramArchiveWorker.kt
//
// Daily automatic archive — WorkManager দিয়ে প্রতিদিন চলে।
// কাজ:
//   1. ৭ দিনের পুরানো messages → Drive এ JSON archive
//   2. Media files → Drive এ copy (Cloudinary থেকে stream)
//   3. Drive copy নিশ্চিত হলে → Cloudinary থেকে signed DELETE
//   4. Firebase Firestore থেকে পুরানো messages batch delete
//   5. Room থেকে পুরানো messages delete
//
// Cloudinary signed delete:
//   - API Key: 292749814534824
//   - Secret: EEYmph3nZLR8Modypt0J7eH--58
//   - Signature = SHA1(public_id=<id>&timestamp=<ts><secret>)
// ============================================================

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
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
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val WORKER_TAG    = "RasGramArchiveWorker"
private const val NOTIF_CHANNEL = "rasgram_archive"
private const val NOTIF_ID      = 9901
private const val ARCHIVE_DAYS  = 7L
private const val ARCHIVE_MS    = ARCHIVE_DAYS * 24 * 60 * 60 * 1000L

// ── Cloudinary credentials ────────────────────────────────────
private const val CLD_CLOUD    = "de2w78yxh"
private const val CLD_API_KEY  = "292749814534824"
private const val CLD_SECRET   = "EEYmph3nZLR8Modypt0J7eH--58"

// ── Cloudinary URL থেকে public_id বের করো ───────────────────
// https://res.cloudinary.com/<cloud>/<type>/upload/v123/folder/file.jpg
// public_id = "folder/file" (extension ছাড়া, version ছাড়া)
private fun extractCloudinaryPublicId(url: String): String? {
    return try {
        val parts = url.split("/upload/")
        if (parts.size < 2) return null
        val afterUpload = parts[1]
        val withoutVersion = if (afterUpload.startsWith("v") && afterUpload.contains("/")) {
            afterUpload.substringAfter("/")
        } else afterUpload
        withoutVersion.substringBeforeLast(".")
    } catch (e: Exception) { null }
}

// ── Cloudinary resource_type বের করো ─────────────────────────
// image/jpeg → "image", audio/mp3 → "video" (Cloudinary audio = video type), application/* → "raw"
private fun cloudinaryResourceType(mimeType: String?): String {
    return when {
        mimeType == null                      -> "raw"
        mimeType.startsWith("image/")         -> "image"
        mimeType.startsWith("video/")         -> "video"
        mimeType.startsWith("audio/")         -> "video"   // Cloudinary audio = video resource type
        else                                  -> "raw"
    }
}

// ── SHA1 HMAC for Cloudinary signed delete ───────────────────
private fun sha1Hex(input: String): String {
    val md = MessageDigest.getInstance("SHA-1")
    val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

// ── Cloudinary signed delete ─────────────────────────────────
// Returns true on success
private fun deleteFromCloudinary(publicId: String, resourceType: String): Boolean {
    return try {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        // Signature string: alphabetically sorted params + secret
        val sigStr    = "public_id=${publicId}&timestamp=${timestamp}${CLD_SECRET}"
        val signature = sha1Hex(sigStr)

        val body = FormBody.Builder()
            .add("public_id", publicId)
            .add("timestamp", timestamp)
            .add("api_key", CLD_API_KEY)
            .add("signature", signature)
            .build()

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url("https://api.cloudinary.com/v1_1/$CLD_CLOUD/$resourceType/destroy")
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        val respBody = response.body?.string() ?: ""
        val json     = JSONObject(respBody)
        val result   = json.optString("result")
        val ok       = result == "ok" || result == "not found"
        Log.d(WORKER_TAG, "Cloudinary delete $publicId → $result")
        ok
    } catch (e: Exception) {
        Log.w(WORKER_TAG, "Cloudinary delete failed for $publicId: ${e.message}")
        false
    }
}

// ============================================================
// WORKER
// ============================================================

class RasGramArchiveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(WORKER_TAG, "Starting daily archive job...")
        showProgressNotification("RasGram archive শুরু হচ্ছে...")

        try {
            if (!RasGramDriveArchive.isDriveConnected(applicationContext)) {
                Log.w(WORKER_TAG, "Google Drive connected নেই — archive skip")
                cancelNotification()
                return@withContext Result.success()
            }

            val drive = buildDrive(applicationContext) ?: run {
                cancelNotification()
                return@withContext Result.retry()
            }

            val repo            = RasGramRepository.getInstance(applicationContext)
            val db              = FirebaseFirestore.getInstance()
            val cutoff          = System.currentTimeMillis() - ARCHIVE_MS
            val previews        = repo.chatPreviewDao.getAllPreviews()
            val rasGramFolderId = getRasGramFolderIdLocal(applicationContext, drive)

            var totalMessages  = 0
            var totalMedia     = 0
            var processedChats = 0

            previews.forEach { preview ->
                val latestMsg = repo.messageDao.getLatestMessage(preview.contactMobile)
                    ?: return@forEach
                val chatId = latestMsg.chatId

                showProgressNotification(
                    "Archive হচ্ছে: ${preview.contactName.ifEmpty { preview.contactMobile }} ($processedChats/${previews.size})"
                )

                // ── Step 1: পুরানো messages পাও ──────────────────
                val oldMessages = repo.messageDao.getMessagesForArchive(chatId, cutoff)
                if (oldMessages.isEmpty()) return@forEach

                // ── Step 2: Chat এর Drive folder পাও/বানাও ───────
                val chatFolderId = getOrCreateFolderLocal(drive, chatId, rasGramFolderId)

                // ── Step 3: Messages → JSON → Drive upload ────────
                val jsonContent = messagesToJson(oldMessages)
                val timestamp   = System.currentTimeMillis()
                val driveFileId = uploadJsonToDrive(
                    drive, chatFolderId,
                    "messages_${timestamp}.json",
                    jsonContent
                )

                // ── Step 4: Media files → Drive copy → Cloudinary delete ──
                val (mediaCopied, cloudinaryDeleted) = copyMediaAndCleanCloudinary(
                    drive, chatFolderId, oldMessages
                )
                totalMedia += mediaCopied

                // ── Step 5: Room এ archived mark + delete ─────────
                repo.messageDao.markMessagesArchived(chatId, cutoff, driveFileId)
                repo.messageDao.deleteChatMessagesBefore(chatId, cutoff)

                // ── Step 6: Firestore থেকে batch delete ──────────
                deleteFromFirestore(db, chatId, cutoff)

                // ── Step 7: Chat preview এ Drive info আপডেট ──────
                repo.chatPreviewDao.updateDriveInfo(preview.contactMobile, chatFolderId, timestamp)

                totalMessages += oldMessages.size
                processedChats++
                Log.i(WORKER_TAG, "Chat $chatId: ${oldMessages.size} msgs, $mediaCopied media copied, $cloudinaryDeleted Cloudinary deleted")
            }

            if (totalMessages > 0) {
                showCompleteNotification(
                    "✅ $totalMessages টি message ও $totalMedia টি media Drive এ archive হয়েছে। Firebase ও Cloudinary পরিষ্কার।"
                )
            } else {
                cancelNotification()
            }

            Log.i(WORKER_TAG, "Archive complete: $totalMessages messages, $totalMedia media from $processedChats chats")
            Result.success()

        } catch (e: Exception) {
            Log.e(WORKER_TAG, "Archive worker failed: ${e.message}", e)
            cancelNotification()
            Result.retry()
        }
    }

    // ── Media: Drive তে copy করো, সফল হলে Cloudinary থেকে delete ──
    private suspend fun copyMediaAndCleanCloudinary(
        drive: Drive,
        chatFolderId: String,
        messages: List<CachedMessage>
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var copied  = 0
        var deleted = 0

        val mediaMessages = messages.filter {
            !it.fileUrl.isNullOrEmpty() && !it.isDeleted &&
            it.fileType?.let { ft ->
                ft.startsWith("image/") || ft.startsWith("audio/") ||
                ft.startsWith("video/") || ft.startsWith("application/")
            } == true
        }

        val mediaFolderId = getOrCreateFolderLocal(drive, "media", chatFolderId)

        mediaMessages.forEach { msg ->
            val url      = msg.fileUrl ?: return@forEach
            val fileName = msg.fileName ?: "media_${msg.timestamp}"
            val mimeType = msg.fileType ?: "application/octet-stream"

            try {
                // Drive এ already আছে কিনা check
                val existing = drive.files().list()
                    .setQ("'$mediaFolderId' in parents and name = '$fileName' and trashed = false")
                    .setFields("files(id)")
                    .execute()

                val driveFileId = if (existing.files?.isNotEmpty() == true) {
                    existing.files[0].id // already আছে
                } else {
                    // Cloudinary থেকে stream করে Drive এ upload
                    val conn = URL(url).openConnection().apply { connect() }
                    val input = conn.getInputStream()
                    val meta  = DriveFile().apply {
                        name    = fileName
                        parents = listOf(mediaFolderId)
                    }
                    val content = InputStreamContent(mimeType, input)
                    val id = drive.files().create(meta, content).setFields("id").execute().id
                    input.close()
                    id
                }

                copied++

                // Drive copy confirm হলে Cloudinary থেকে delete
                if (driveFileId != null) {
                    val publicId      = extractCloudinaryPublicId(url)
                    val resourceType  = cloudinaryResourceType(mimeType)
                    if (publicId != null) {
                        val ok = deleteFromCloudinary(publicId, resourceType)
                        if (ok) deleted++
                    }
                }

            } catch (e: Exception) {
                Log.w(WORKER_TAG, "Media copy/delete failed for ${msg.fileName}: ${e.message}")
            }
        }
        Pair(copied, deleted)
    }

    // ── Firestore batch delete (400 at a time, Firestore limit 500) ──
    private suspend fun deleteFromFirestore(db: FirebaseFirestore, chatId: String, cutoff: Long) {
        try {
            // Loop until all old messages deleted (might need multiple batches)
            while (true) {
                val snapshot = db.collection("pvt_msg_$chatId")
                    .whereLessThan("timestamp", cutoff)
                    .limit(400)
                    .get()
                    .await()

                if (snapshot.isEmpty) break

                val batch = db.batch()
                snapshot.documents.forEach { doc -> batch.delete(doc.reference) }
                batch.commit().await()

                Log.d(WORKER_TAG, "Firestore: deleted ${snapshot.size()} messages from pvt_msg_$chatId")

                if (snapshot.size() < 400) break // last batch
            }
        } catch (e: Exception) {
            Log.w(WORKER_TAG, "Firestore delete failed (non-critical): ${e.message}")
        }
    }

    // ── Messages → JSON ──────────────────────────────────────────
    private fun messagesToJson(messages: List<CachedMessage>): String {
        val arr = JSONArray()
        messages.forEach { msg ->
            arr.put(JSONObject().apply {
                put("id",            msg.id)
                put("text",          msg.text)
                put("senderMobile",  msg.senderMobile)
                put("receiverMobile",msg.receiverMobile)
                put("timestamp",     msg.timestamp)
                put("timeString",    msg.timeString)
                put("fileUrl",       msg.fileUrl ?: "")
                put("fileName",      msg.fileName ?: "")
                put("fileType",      msg.fileType ?: "")
                put("fileSizeBytes", msg.fileSizeBytes)
                put("reaction",      msg.reaction ?: "")
                put("read",          msg.read)
                put("delivered",     msg.delivered)
                put("isCallLog",     msg.isCallLog)
                put("callStatus",    msg.callStatus ?: "")
                put("callType",      msg.callType ?: "")
                put("replyToId",     msg.replyToId ?: "")
                put("replyToText",   msg.replyToText ?: "")
                put("replyToSender", msg.replyToSender ?: "")
                put("isDeleted",     msg.isDeleted)
                put("isForwarded",   msg.isForwarded)
                put("isStarred",     msg.isStarred)
                put("duration",      msg.duration)
                put("mediaCopiedToDrive", !msg.fileUrl.isNullOrEmpty())
            })
        }
        return JSONObject().apply {
            put("version",     2)
            put("exportTime",  System.currentTimeMillis())
            put("archiveDays", ARCHIVE_DAYS)
            put("messages",    arr)
        }.toString(2)
    }

    // ── Drive helpers ────────────────────────────────────────────
    private fun buildDrive(context: Context): Drive? {
        return try {
            val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
            val credential = GoogleAccountCredential.usingOAuth2(
                context, listOf(DriveScopes.DRIVE_FILE)
            ).apply { selectedAccountName = account.email }
            Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
                .setApplicationName("RasFocus+").build()
        } catch (e: Exception) { null }
    }

    private suspend fun getRasGramFolderIdLocal(context: Context, drive: Drive): String {
        val prefs  = context.getSharedPreferences("rasgram_drive_archive_prefs", Context.MODE_PRIVATE)
        val cached = prefs.getString("rasgram_drive_folder_id", null)
        if (!cached.isNullOrEmpty()) return cached
        val rasFocusId = getOrCreateFolderLocal(drive, "RasFocus+")
        val rasGramId  = getOrCreateFolderLocal(drive, "RasGram", rasFocusId)
        prefs.edit().putString("rasgram_drive_folder_id", rasGramId).apply()
        return rasGramId
    }

    private suspend fun getOrCreateFolderLocal(
        drive: Drive, name: String, parentId: String = "root"
    ): String = withContext(Dispatchers.IO) {
        val q = "'$parentId' in parents and name = '$name' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val result = drive.files().list().setQ(q).setFields("files(id)").execute()
        result.files?.firstOrNull()?.id ?: run {
            val meta = DriveFile().apply {
                this.name = name
                mimeType  = "application/vnd.google-apps.folder"
                parents   = listOf(parentId)
            }
            drive.files().create(meta).setFields("id").execute().id
        }
    }

    private suspend fun uploadJsonToDrive(
        drive: Drive, folderId: String, fileName: String, jsonContent: String
    ): String = withContext(Dispatchers.IO) {
        val bytes   = jsonContent.toByteArray(Charsets.UTF_8)
        val content = com.google.api.client.http.ByteArrayContent("application/json", bytes)
        val meta    = DriveFile().apply {
            name    = fileName
            parents = listOf(folderId)
        }
        drive.files().create(meta, content).setFields("id").execute().id
    }

    // ── Notifications ────────────────────────────────────────────
    private fun showProgressNotification(message: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL, "RasGram Archive", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Daily message archive to Google Drive" }
            manager.createNotificationChannel(channel)
        }
        val notif = NotificationCompat.Builder(applicationContext, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("RasGram Archive")
            .setContentText(message)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        manager.notify(NOTIF_ID, notif)
    }

    private fun showCompleteNotification(message: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(applicationContext, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("RasGram Archive সম্পন্ন")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        manager.notify(NOTIF_ID, notif)
    }

    private fun cancelNotification() {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIF_ID)
    }
}

// ============================================================
// SCHEDULER
// ============================================================

object RasGramArchiveScheduler {

    private const val WORK_NAME = "RasGramDailyArchive"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<RasGramArchiveWorker>(
            24, TimeUnit.HOURS,
            6,  TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setInitialDelay(1, TimeUnit.HOURS)
            .addTag(WORK_NAME)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        Log.i("RasGramArchiveScheduler", "Daily archive scheduled (every 24h)")
    }

    fun runNow(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<RasGramArchiveWorker>()
            .setConstraints(constraints)
            .addTag("$WORK_NAME.manual")
            .build()
        WorkManager.getInstance(context).enqueue(request)
        Log.i("RasGramArchiveScheduler", "Manual archive triggered")
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        Log.i("RasGramArchiveScheduler", "Daily archive cancelled")
    }
}

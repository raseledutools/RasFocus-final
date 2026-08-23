package com.rasel.RasFocus.selfcontrol.rasgram

// ============================================================
// RasGramArchiveWorker.kt
//
// Daily automatic archive — WorkManager দিয়ে প্রতিদিন রাত ২টায়
// চলে। কাজ:
//   1. ৭ দিনের পুরানো messages → Drive এ JSON archive
//   2. সেই messages এর media files (Cloudinary URL) → Drive এ copy
//   3. Firebase Firestore থেকে পুরানো messages delete
//   4. Room থেকে পুরানো messages delete
//
// Schedule: Daily, রাত ২:০০ AM এ (user ঘুমের সময়)
// Constraints: Network connected, charging বা ≥30% battery
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
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.TimeUnit

private const val WORKER_TAG      = "RasGramArchiveWorker"
private const val NOTIF_CHANNEL   = "rasgram_archive"
private const val NOTIF_ID        = 9901
private const val ARCHIVE_DAYS    = 7L
private const val ARCHIVE_MS      = ARCHIVE_DAYS * 24 * 60 * 60 * 1000L

// ── Cloudinary URL থেকে public_id বের করো ──────────────────
// URL format: https://res.cloudinary.com/<cloud>/image/upload/v123/folder/filename.jpg
// public_id = "folder/filename" (extension ছাড়া)
private fun extractCloudinaryPublicId(url: String): String? {
    return try {
        val parts = url.split("/upload/")
        if (parts.size < 2) return null
        val afterUpload = parts[1]  // "v1234/filename.jpg" অথবা "filename.jpg"
        val withoutVersion = if (afterUpload.startsWith("v") && afterUpload.contains("/")) {
            afterUpload.substringAfter("/")
        } else afterUpload
        // Extension সরাও
        withoutVersion.substringBeforeLast(".")
    } catch (e: Exception) { null }
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

        // Progress notification দেখাও
        showProgressNotification("RasGram archive শুরু হচ্ছে...")

        try {
            // Drive connected কিনা দেখো
            if (!RasGramDriveArchive.isDriveConnected(applicationContext)) {
                Log.w(WORKER_TAG, "Google Drive connected নেই — archive skip")
                cancelNotification()
                return@withContext Result.success()
            }

            val drive  = buildDrive(applicationContext)
            if (drive == null) {
                Log.w(WORKER_TAG, "Drive service build failed")
                cancelNotification()
                return@withContext Result.retry()
            }

            val repo   = RasGramRepository.getInstance(applicationContext)
            val db     = FirebaseFirestore.getInstance()
            val cutoff = System.currentTimeMillis() - ARCHIVE_MS
            val previews = repo.chatPreviewDao.getAllPreviews()

            var totalMessages = 0
            var totalMediaFiles = 0
            var processedChats = 0

            // RasGram Drive root folder পাও
            val rasGramFolderId = getRasGramFolderIdLocal(applicationContext, drive)

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
                val jsonContent = messagesToJsonWithMedia(oldMessages)
                val timestamp   = System.currentTimeMillis()
                val driveFileId = uploadJsonToDrive(
                    drive, chatFolderId,
                    "messages_${timestamp}.json",
                    jsonContent
                )

                // ── Step 4: Media files → Drive copy ─────────────
                val mediaCount = copyMediaFilesToDrive(
                    drive, chatFolderId, oldMessages
                )
                totalMediaFiles += mediaCount

                // ── Step 5: Room এ archived mark + delete ─────────
                repo.messageDao.markMessagesArchived(chatId, cutoff, driveFileId)
                repo.messageDao.deleteChatMessagesBefore(chatId, cutoff)

                // ── Step 6: Firestore থেকে delete ────────────────
                deleteFromFirestore(db, chatId, cutoff)

                // ── Step 7: Chat preview এ Drive info আপডেট ──────
                repo.chatPreviewDao.updateDriveInfo(
                    preview.contactMobile, chatFolderId, timestamp
                )

                totalMessages += oldMessages.size
                processedChats++
                Log.i(WORKER_TAG, "Chat $chatId: archived ${oldMessages.size} msgs, $mediaCount media files")
            }

            // শেষ notification
            if (totalMessages > 0) {
                showCompleteNotification(
                    "✅ $totalMessages টি পুরানো message ও $totalMediaFiles টি media file Drive এ archive হয়েছে।"
                )
            } else {
                cancelNotification()
            }

            Log.i(WORKER_TAG, "Archive complete: $totalMessages messages, $totalMediaFiles media from $processedChats chats")
            Result.success()

        } catch (e: Exception) {
            Log.e(WORKER_TAG, "Archive worker failed: ${e.message}", e)
            cancelNotification()
            Result.retry()
        }
    }

    // ── Media files Cloudinary থেকে download করে Drive এ copy ──
    private suspend fun copyMediaFilesToDrive(
        drive: Drive,
        chatFolderId: String,
        messages: List<CachedMessage>
    ): Int = withContext(Dispatchers.IO) {
        var count = 0
        val mediaMessages = messages.filter {
            !it.fileUrl.isNullOrEmpty() &&
            !it.isDeleted &&
            it.fileType?.let { ft ->
                ft.startsWith("image/") || ft.startsWith("audio/") ||
                ft.startsWith("video/") || ft.startsWith("application/")
            } == true
        }

        mediaMessages.forEach { msg ->
            try {
                val url      = msg.fileUrl ?: return@forEach
                val fileName = msg.fileName ?: "media_${msg.timestamp}"
                val mimeType = msg.fileType ?: "application/octet-stream"

                // Media folder পাও
                val mediaFolderId = getOrCreateFolderLocal(drive, "media", chatFolderId)

                // Drive এ ইতিমধ্যে আছে কিনা check
                val existing = drive.files().list()
                    .setQ("'$mediaFolderId' in parents and name = '$fileName' and trashed = false")
                    .setFields("files(id)")
                    .execute()
                if (existing.files?.isNotEmpty() == true) {
                    count++ // already আছে, skip
                    return@forEach
                }

                // Cloudinary থেকে stream করে Drive এ upload
                val connection = URL(url).openConnection()
                connection.connect()
                val inputStream = connection.getInputStream()

                val meta = DriveFile().apply {
                    name    = fileName
                    parents = listOf(mediaFolderId)
                }
                val content = InputStreamContent(mimeType, inputStream)

                drive.files().create(meta, content)
                    .setFields("id")
                    .execute()

                inputStream.close()
                count++
                Log.d(WORKER_TAG, "Media archived: $fileName")

            } catch (e: Exception) {
                Log.w(WORKER_TAG, "Media copy failed for ${msg.fileName}: ${e.message}")
            }
        }
        count
    }

    // ── Firestore থেকে পুরানো messages delete ─────────────────
    private suspend fun deleteFromFirestore(
        db: FirebaseFirestore,
        chatId: String,
        cutoff: Long
    ) {
        try {
            val snapshot = db.collection("pvt_msg_$chatId")
                .whereLessThan("timestamp", cutoff)
                .limit(400) // Firestore batch limit 500
                .get()
                .await()

            if (snapshot.isEmpty) return

            // Batch delete (max 500 per batch)
            val batch = db.batch()
            snapshot.documents.forEach { doc -> batch.delete(doc.reference) }
            batch.commit().await()

            Log.d(WORKER_TAG, "Firestore: deleted ${snapshot.size()} messages from pvt_msg_$chatId")
        } catch (e: Exception) {
            Log.w(WORKER_TAG, "Firestore delete failed (non-critical): ${e.message}")
        }
    }

    // ── Drive helpers ──────────────────────────────────────────
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
        val prefs = context.getSharedPreferences("rasgram_drive_archive_prefs", Context.MODE_PRIVATE)
        val cached = prefs.getString("rasgram_drive_folder_id", null)
        if (!cached.isNullOrEmpty()) return cached

        val rasFocusId = getOrCreateFolderLocal(drive, "RasFocus+")
        val rasGramId  = getOrCreateFolderLocal(drive, "RasGram", rasFocusId)
        prefs.edit().putString("rasgram_drive_folder_id", rasGramId).apply()
        return rasGramId
    }

    private suspend fun getOrCreateFolderLocal(
        drive: Drive,
        name: String,
        parentId: String = "root"
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
        drive: Drive,
        folderId: String,
        fileName: String,
        jsonContent: String
    ): String = withContext(Dispatchers.IO) {
        val bytes   = jsonContent.toByteArray(Charsets.UTF_8)
        val content = com.google.api.client.http.ByteArrayContent("application/json", bytes)
        val meta    = DriveFile().apply {
            name    = fileName
            parents = listOf(folderId)
        }
        drive.files().create(meta, content).setFields("id").execute().id
    }

    private fun messagesToJsonWithMedia(messages: List<CachedMessage>): String {
        val arr = JSONArray()
        messages.forEach { msg ->
            arr.put(JSONObject().apply {
                put("id",             msg.id)
                put("text",           msg.text)
                put("senderMobile",   msg.senderMobile)
                put("receiverMobile", msg.receiverMobile)
                put("timestamp",      msg.timestamp)
                put("timeString",     msg.timeString)
                put("fileUrl",        msg.fileUrl ?: "")
                put("fileName",       msg.fileName ?: "")
                put("fileType",       msg.fileType ?: "")
                put("fileSizeBytes",  msg.fileSizeBytes)
                put("reaction",       msg.reaction ?: "")
                put("read",           msg.read)
                put("delivered",      msg.delivered)
                put("isCallLog",      msg.isCallLog)
                put("callStatus",     msg.callStatus ?: "")
                put("callType",       msg.callType ?: "")
                put("replyToId",      msg.replyToId ?: "")
                put("replyToText",    msg.replyToText ?: "")
                put("replyToSender",  msg.replyToSender ?: "")
                put("isDeleted",      msg.isDeleted)
                put("isForwarded",    msg.isForwarded)
                put("isStarred",      msg.isStarred)
                put("duration",       msg.duration)
                // Media note — Drive folder এ আলাদাভাবে আছে
                put("mediaCopiedToDrive", !msg.fileUrl.isNullOrEmpty())
            })
        }
        return JSONObject().apply {
            put("version",    2)
            put("exportTime", System.currentTimeMillis())
            put("archiveDays", ARCHIVE_DAYS)
            put("messages",   arr)
        }.toString(2)
    }

    // ── Notifications ──────────────────────────────────────────
    private fun showProgressNotification(message: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL, "RasGram Archive",
                NotificationManager.IMPORTANCE_LOW
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
// SCHEDULER — App start এ একবার call করো
// ============================================================

object RasGramArchiveScheduler {

    private const val WORK_NAME = "RasGramDailyArchive"

    // RasGramActivity.onCreate() থেকে call করো
    fun schedule(context: Context) {
        // Constraints: network দরকার, battery ৩০% এর বেশি থাকলে ভালো
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // প্রতিদিন একবার — প্রথমবার ১ ঘণ্টা পর শুরু হবে, তারপর ২৪ ঘণ্টা পর পর
        val request = PeriodicWorkRequestBuilder<RasGramArchiveWorker>(
            24, TimeUnit.HOURS,
            // Flex window: এই সময়ের মধ্যে যেকোনো সময় চলতে পারে (OS decide করে)
            6, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setInitialDelay(1, TimeUnit.HOURS)  // install এর পর ১ ঘণ্টা দেরিতে শুরু
            .addTag(WORK_NAME)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,  // already scheduled থাকলে নতুন schedule করবে না
            request
        )

        Log.i("RasGramArchiveScheduler", "Daily archive scheduled (every 24h)")
    }

    // Manual trigger — "এখনই Archive করো" button থেকে
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

    // Cancel করো (settings থেকে disable করলে)
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        Log.i("RasGramArchiveScheduler", "Daily archive cancelled")
    }
}

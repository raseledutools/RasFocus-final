package com.rasel.RasFocus.selfcontrol.rasgram

// ============================================================
// RasGramDriveSyncManager.kt
//
// Features implemented:
//  1. RasFocus → daily all chat & file auto-upload to Drive
//     (default = RasFocus signed-in account)
//  2. RasGram → multiple Google Drive account sign-in
//  3. RasGram → daily auto-sync + manual "Sync Now" button
//  4. Read+Write: sync করলে messages + media Drive এ save হয়
// ============================================================

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.*
import com.google.android.gms.auth.api.signin.GoogleSignIn
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
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.TimeUnit

private const val TAG = "RasGramDriveSync"
private const val SYNC_WORK_NAME = "RasGramDriveSync"
private const val NOTIF_CHANNEL = "rasgram_drive_sync"
private const val NOTIF_ID = 9902

// SharedPreference keys
private const val PREF_SYNC_ACCOUNTS = "rasgram_sync_accounts"
private const val PREF_SYNC_DEFAULT_ACCOUNT = "rasgram_sync_default_account"
private const val PREF_LAST_SYNC_TIME = "rasgram_last_sync_time"
private const val PREF_SYNC_PREFS_FILE = "rasgram_drive_sync_prefs"

// Drive folder structure: RasFocus+/RasGram/<chatId>/
private const val ROOT_FOLDER = "RasFocus+"
private const val RASGRAM_FOLDER = "RasGram"

// ============================================================
// MULTI-ACCOUNT MANAGER
// ============================================================

object RasGramDriveAccountManager {

    /** RasFocus sign-in account (default drive) */
    fun getRasFocusAccount(context: Context): GoogleSignInAccount? {
        return try { GoogleSignIn.getLastSignedInAccount(context) } catch (e: Exception) { null }
    }

    /** Get all configured sync accounts (email list) */
    fun getSyncAccounts(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREF_SYNC_PREFS_FILE, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(PREF_SYNC_ACCOUNTS, emptySet()) ?: emptySet()
        // Always include RasFocus account
        val rasFocusEmail = getRasFocusAccount(context)?.email
        val all = set.toMutableSet()
        if (!rasFocusEmail.isNullOrEmpty()) all.add(rasFocusEmail)
        return all.toList().sorted()
    }

    /** Add a new sync account */
    fun addSyncAccount(context: Context, email: String) {
        val prefs = context.getSharedPreferences(PREF_SYNC_PREFS_FILE, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(PREF_SYNC_ACCOUNTS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(email)
        prefs.edit().putStringSet(PREF_SYNC_ACCOUNTS, current).apply()
        // If first account, make it default
        if (getDefaultSyncAccount(context) == null) setDefaultSyncAccount(context, email)
    }

    /** Remove a sync account */
    fun removeSyncAccount(context: Context, email: String) {
        // Don't remove the RasFocus account
        val rasFocusEmail = getRasFocusAccount(context)?.email
        if (email == rasFocusEmail) return
        val prefs = context.getSharedPreferences(PREF_SYNC_PREFS_FILE, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(PREF_SYNC_ACCOUNTS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.remove(email)
        prefs.edit().putStringSet(PREF_SYNC_ACCOUNTS, current).apply()
        // If removed the default, reset to RasFocus account
        if (getDefaultSyncAccount(context) == email) {
            prefs.edit().remove(PREF_SYNC_DEFAULT_ACCOUNT).apply()
        }
    }

    /** Get the default sync account email */
    fun getDefaultSyncAccount(context: Context): String? {
        val prefs = context.getSharedPreferences(PREF_SYNC_PREFS_FILE, Context.MODE_PRIVATE)
        val saved = prefs.getString(PREF_SYNC_DEFAULT_ACCOUNT, null)
        if (!saved.isNullOrEmpty()) return saved
        // Fallback: RasFocus account
        return getRasFocusAccount(context)?.email
    }

    /** Set the default sync account */
    fun setDefaultSyncAccount(context: Context, email: String) {
        val prefs = context.getSharedPreferences(PREF_SYNC_PREFS_FILE, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_SYNC_DEFAULT_ACCOUNT, email).apply()
    }

    /** Get last sync time */
    fun getLastSyncTime(context: Context): Long {
        val prefs = context.getSharedPreferences(PREF_SYNC_PREFS_FILE, Context.MODE_PRIVATE)
        return prefs.getLong(PREF_LAST_SYNC_TIME, 0L)
    }

    /** Record sync completed */
    fun recordSyncTime(context: Context) {
        val prefs = context.getSharedPreferences(PREF_SYNC_PREFS_FILE, Context.MODE_PRIVATE)
        prefs.edit().putLong(PREF_LAST_SYNC_TIME, System.currentTimeMillis()).apply()
    }

    /** Build a Google Drive service for a specific account email */
    fun buildDriveForAccount(context: Context, accountEmail: String): Drive? {
        return try {
            val credential = GoogleAccountCredential.usingOAuth2(
                context, listOf(DriveScopes.DRIVE_FILE)
            ).apply { selectedAccountName = accountEmail }
            Drive.Builder(
                NetHttpTransport(), GsonFactory.getDefaultInstance(), credential
            ).setApplicationName("RasFocus+").build()
        } catch (e: Exception) {
            Log.e(TAG, "buildDriveForAccount($accountEmail) failed: ${e.message}")
            null
        }
    }

    /** Build Drive service for the default account */
    fun buildDefaultDrive(context: Context): Drive? {
        val email = getDefaultSyncAccount(context) ?: return null
        return buildDriveForAccount(context, email)
    }

    /** GoogleSignInOptions with Drive read+write scope */
    fun driveSignInOptions(): GoogleSignInOptions {
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()
    }

    /** Check if any drive is available */
    fun isDriveAvailable(context: Context): Boolean {
        return getDefaultSyncAccount(context) != null
    }
}

// ============================================================
// SYNC RESULT
// ============================================================

data class DriveSyncResult(
    val success: Boolean,
    val syncedChats: Int = 0,
    val syncedMessages: Int = 0,
    val syncedMediaFiles: Int = 0,
    val errorMessage: String? = null,
    val accountUsed: String? = null,
    val durationMs: Long = 0
)

// ============================================================
// CORE SYNC ENGINE
// ============================================================

object RasGramDriveSyncEngine {

    // ── Folder helpers ────────────────────────────────────────
    private suspend fun getOrCreateFolder(
        drive: Drive, name: String, parentId: String = "root"
    ): String = withContext(Dispatchers.IO) {
        val escapedName = name.replace("'", "\\'")
        val q = "'$parentId' in parents and name = '$escapedName' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val existing = drive.files().list().setQ(q).setSpaces("drive").setFields("files(id)").execute()
        existing.files?.firstOrNull()?.id ?: run {
            val meta = DriveFile().apply {
                this.name = name
                mimeType = "application/vnd.google-apps.folder"
                parents = listOf(parentId)
            }
            drive.files().create(meta).setFields("id").execute().id
        }
    }

    private suspend fun getRasGramRootFolderId(context: Context, drive: Drive, accountEmail: String): String {
        val prefs = context.getSharedPreferences(PREF_SYNC_PREFS_FILE, Context.MODE_PRIVATE)
        val cacheKey = "rasgram_root_${accountEmail.replace("@", "_").replace(".", "_")}"
        val cached = prefs.getString(cacheKey, null)
        if (!cached.isNullOrEmpty()) return cached
        val rootId = getOrCreateFolder(drive, ROOT_FOLDER)
        val rasGramId = getOrCreateFolder(drive, RASGRAM_FOLDER, rootId)
        prefs.edit().putString(cacheKey, rasGramId).apply()
        return rasGramId
    }

    // ── Upload helpers ────────────────────────────────────────
    private suspend fun uploadJsonToDrive(
        drive: Drive, folderId: String, fileName: String, json: String
    ): String = withContext(Dispatchers.IO) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val content = ByteArrayContent("application/json", bytes)
        val meta = DriveFile().apply { name = fileName; parents = listOf(folderId) }
        drive.files().create(meta, content).setFields("id").execute().id
    }

    private suspend fun driveFileExists(drive: Drive, folderId: String, fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val escapedName = fileName.replace("'", "\\'")
                val q = "'$folderId' in parents and name = '$escapedName' and trashed = false"
                val r = drive.files().list().setQ(q).setFields("files(id)").execute()
                r.files?.isNotEmpty() == true
            } catch (e: Exception) { false }
        }

    // ── Message → JSON ────────────────────────────────────────
    private fun messagesToJson(messages: List<CachedMessage>): String {
        val arr = JSONArray()
        messages.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id); put("text", m.text)
                put("senderMobile", m.senderMobile); put("receiverMobile", m.receiverMobile)
                put("timestamp", m.timestamp); put("timeString", m.timeString)
                put("fileUrl", m.fileUrl ?: ""); put("fileName", m.fileName ?: "")
                put("fileType", m.fileType ?: ""); put("fileSizeBytes", m.fileSizeBytes)
                put("reaction", m.reaction ?: ""); put("read", m.read); put("delivered", m.delivered)
                put("isCallLog", m.isCallLog); put("isDeleted", m.isDeleted)
                put("isForwarded", m.isForwarded); put("isStarred", m.isStarred)
                put("duration", m.duration)
            })
        }
        return JSONObject().apply {
            put("version", 1); put("exportTime", System.currentTimeMillis()); put("messages", arr)
        }.toString(2)
    }

    // ── Sync a single chat ─────────────────────────────────────
    private suspend fun syncChat(
        drive: Drive, rasGramFolderId: String,
        chatId: String, repo: RasGramRepository
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val messages = repo.messageDao.getMessages(chatId).first()
        if (messages.isEmpty()) return@withContext Pair(0, 0)

        val chatFolderId = getOrCreateFolder(drive, chatId, rasGramFolderId)
        val timestamp = System.currentTimeMillis()

        // Upload messages JSON (timestamped so each daily sync is its own file)
        val jsonFileName = "messages_${timestamp}.json"
        val json = messagesToJson(messages)
        uploadJsonToDrive(drive, chatFolderId, jsonFileName, json)

        // Upload media files
        val mediaFolderId = getOrCreateFolder(drive, "media", chatFolderId)
        var mediaCount = 0
        messages.filter { !it.fileUrl.isNullOrEmpty() && !it.isDeleted }.forEach { msg ->
            val url = msg.fileUrl ?: return@forEach
            val name = msg.fileName ?: "media_${msg.timestamp}"
            val mimeType = msg.fileType ?: "application/octet-stream"
            try {
                if (!driveFileExists(drive, mediaFolderId, name)) {
                    val conn = URL(url).openConnection().apply { connect() }
                    val input = conn.getInputStream()
                    val meta = DriveFile().apply { this.name = name; parents = listOf(mediaFolderId) }
                    drive.files().create(meta, InputStreamContent(mimeType, input)).setFields("id").execute()
                    input.close()
                    mediaCount++
                }
            } catch (e: Exception) {
                Log.w(TAG, "Media upload failed for $name: ${e.message}")
            }
        }
        Pair(messages.size, mediaCount)
    }

    // ── Main sync ─────────────────────────────────────────────
    suspend fun performSync(context: Context): DriveSyncResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val accountEmail = RasGramDriveAccountManager.getDefaultSyncAccount(context)
            ?: return@withContext DriveSyncResult(false, errorMessage = "কোনো Google Drive account সংযুক্ত নেই।")

        val drive = RasGramDriveAccountManager.buildDriveForAccount(context, accountEmail)
            ?: return@withContext DriveSyncResult(false, errorMessage = "Drive সংযোগ ব্যর্থ। আবার sign-in করুন।", accountUsed = accountEmail)

        try {
            val repo = RasGramRepository.getInstance(context)
            val rasGramFolderId = getRasGramRootFolderId(context, drive, accountEmail)
            val previews = repo.chatPreviewDao.getAllPreviews()

            var totalChats = 0; var totalMessages = 0; var totalMedia = 0

            previews.forEach { preview ->
                val latestMsg = repo.messageDao.getLatestMessage(preview.contactMobile) ?: return@forEach
                val chatId = latestMsg.chatId
                val (msgs, media) = syncChat(drive, rasGramFolderId, chatId, repo)
                if (msgs > 0) { totalChats++; totalMessages += msgs; totalMedia += media }
            }

            RasGramDriveAccountManager.recordSyncTime(context)
            DriveSyncResult(
                success = true, syncedChats = totalChats, syncedMessages = totalMessages,
                syncedMediaFiles = totalMedia, accountUsed = accountEmail,
                durationMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "performSync failed: ${e.message}", e)
            DriveSyncResult(false, errorMessage = e.message, accountUsed = accountEmail)
        }
    }
}

// ============================================================
// WORKMANAGER WORKER
// ============================================================

class RasGramDriveSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "Daily drive sync starting...")
        showNotification("Drive Sync শুরু হচ্ছে...")
        val result = RasGramDriveSyncEngine.performSync(applicationContext)
        if (result.success) {
            val msg = if (result.syncedMessages > 0)
                "✅ ${result.syncedMessages} messages, ${result.syncedMediaFiles} media files Drive এ sync হয়েছে"
            else "✅ Sync সম্পন্ন — নতুন data নেই"
            showCompleteNotification(msg)
        } else cancelNotification()
        return if (result.success) Result.success() else Result.retry()
    }

    private fun mgr() = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr().createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "Drive Sync", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun showNotification(text: String) {
        ensureChannel()
        val n = NotificationCompat.Builder(applicationContext, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("RasGram Drive Sync").setContentText(text)
            .setProgress(0, 0, true).setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW).build()
        mgr().notify(NOTIF_ID, n)
    }

    private fun showCompleteNotification(text: String) {
        ensureChannel()
        val n = NotificationCompat.Builder(applicationContext, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("RasGram Sync সম্পন্ন").setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
        mgr().notify(NOTIF_ID, n)
    }

    private fun cancelNotification() { mgr().cancel(NOTIF_ID) }
}

// ============================================================
// SCHEDULER
// ============================================================

object RasGramDriveSyncScheduler {

    fun schedule(context: Context) {
        if (!RasGramDriveAccountManager.isDriveAvailable(context)) return
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<RasGramDriveSyncWorker>(24, TimeUnit.HOURS, 6, TimeUnit.HOURS)
            .setConstraints(constraints).setInitialDelay(30, TimeUnit.MINUTES)
            .addTag(SYNC_WORK_NAME)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SYNC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
        )
        Log.i(TAG, "Daily Drive sync scheduled")
    }

    fun runNow(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<RasGramDriveSyncWorker>()
            .setConstraints(constraints).addTag("$SYNC_WORK_NAME.manual").build()
        WorkManager.getInstance(context).enqueue(request)
        Log.i(TAG, "Manual Drive sync triggered")
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME)
    }
}

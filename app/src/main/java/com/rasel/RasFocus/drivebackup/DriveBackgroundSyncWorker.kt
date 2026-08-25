package com.rasel.RasFocus.drivebackup

import android.content.Context
import android.util.Log
import androidx.work.*
import com.rasel.RasFocus.filemanager.DriveCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Background sync worker — refreshes cached folder listings + re-downloads
 * all "pinned offline" files so they stay up to date without the user
 * having to open the app.
 *
 * Schedule: every 6 hours, WiFi only (mimics Google Drive desktop behaviour
 * without draining mobile data).
 */
class DriveBackgroundSyncWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    companion object {
        private const val TAG       = "DriveBackgroundSync"
        private const val WORK_NAME = "drive_background_sync"

        // Input data keys
        const val KEY_ACCOUNT = "account_name"

        fun schedule(context: Context, accountName: String) {
            val req = PeriodicWorkRequestBuilder<DriveBackgroundSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInputData(workDataOf(KEY_ACCOUNT to accountName))
                .setInitialDelay(15, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
            Log.d(TAG, "Background sync scheduled for $accountName")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Background sync cancelled")
        }

        /** One-shot manual sync (e.g. pull-to-refresh while offline) */
        fun runNow(context: Context, accountName: String) {
            val req = OneTimeWorkRequestBuilder<DriveBackgroundSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInputData(workDataOf(KEY_ACCOUNT to accountName))
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context     = applicationContext
        val accountName = inputData.getString(KEY_ACCOUNT) ?: run {
            Log.w(TAG, "No account name provided — skipping")
            return@withContext Result.success()
        }

        DriveCacheManager.init(context)

        if (!DriveCacheManager.isOnline(context)) {
            Log.d(TAG, "Offline — skipping background sync")
            return@withContext Result.retry()
        }

        Log.d(TAG, "Starting background sync for $accountName")
        var anyError = false

        // ── 1. Re-sync every cached folder listing ─────────────────────────────
        // We walk all cached folders (identified by their SharedPreferences keys)
        // and pull fresh listings from Drive.
        val prefs       = context.getSharedPreferences("DriveCachePrefs", Context.MODE_PRIVATE)
        val folderKeys  = prefs.all.keys.filter { it.startsWith("filelist_${accountName}_") }
        val folderIds   = folderKeys.map { it.removePrefix("filelist_${accountName}_") }

        for (folderId in folderIds) {
            try {
                val fresh = DriveFileManager.listFiles(context, accountName, folderId)
                if (fresh != null) {
                    val sorted = fresh.sortedWith(
                        compareBy({ it.mimeType != "application/vnd.google-apps.folder" },
                                  { it.name?.lowercase() ?: "" })
                    )
                    DriveCacheManager.saveFileList(context, accountName, folderId, sorted)
                    Log.d(TAG, "Refreshed folder $folderId (${sorted.size} files)")
                } else {
                    Log.w(TAG, "Could not refresh folder $folderId: ${DriveFileManager.lastError}")
                    anyError = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing folder $folderId", e)
                anyError = true
            }
        }

        // ── 2. Re-download all pinned files ────────────────────────────────────
        // "Pinned" = user explicitly chose "Make available offline"
        // We download again only if the cached file is missing or stale (>24h).
        val pinnedIds = DriveCacheManager.getPinnedFileIds()
        Log.d(TAG, "Pinned files to refresh: ${pinnedIds.size}")

        for (fileId in pinnedIds) {
            try {
                // Find the file metadata from any cached folder
                val meta = findFileMetaFromCache(context, accountName, fileId) ?: continue
                val fileName = meta.name ?: continue
                val dest     = DriveCacheManager.getCacheDir(context)

                val existing = DriveCacheManager.getCachedFile(context, fileId, fileName)
                val ageMs    = if (existing != null)
                    System.currentTimeMillis() - existing.lastModified() else Long.MAX_VALUE

                if (existing == null || ageMs > 24 * 60 * 60_000L) {
                    // Re-download
                    val downloaded = if (meta.mimeType == "application/vnd.google-apps.folder") {
                        val ok = DriveFileManager.downloadFolder(
                            context, accountName, fileId, fileName, dest)
                        ok
                    } else {
                        val f = DriveFileManager.downloadFile(
                            context, accountName, fileId, fileName, dest)
                        if (f != null) DriveCacheManager.markFileDownloaded(
                            context, fileId, fileName)
                        f != null
                    }
                    Log.d(TAG, "Re-downloaded pinned $fileName: $downloaded")
                } else {
                    Log.d(TAG, "Pinned $fileName still fresh (${ageMs/3600000}h old), skipping")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing pinned file $fileId", e)
                anyError = true
            }
        }

        Log.d(TAG, "Background sync complete. Errors: $anyError")
        if (anyError) Result.retry() else Result.success()
    }

    /** Scan all cached folder listings to find metadata for a given fileId */
    private fun findFileMetaFromCache(
        context: Context, accountName: String, fileId: String
    ): com.google.api.services.drive.model.File? {
        val prefs      = context.getSharedPreferences("DriveCachePrefs", Context.MODE_PRIVATE)
        val folderKeys = prefs.all.keys.filter { it.startsWith("filelist_${accountName}_") }
        for (key in folderKeys) {
            val folderId = key.removePrefix("filelist_${accountName}_")
            val list     = DriveCacheManager.loadFileList(context, accountName, folderId) ?: continue
            val found    = list.find { it.id == fileId }
            if (found != null) return found
        }
        return null
    }
}

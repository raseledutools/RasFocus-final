package com.rasel.RasFocus.drivebackup

import android.content.Context
import android.util.Log
import androidx.work.*
import com.rasel.RasFocus.filemanager.DriveCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * DriveMetadataSyncWorker
 *
 * PC Google Drive এর মতো behavior:
 *  - শুধু file/folder NAME + structure cache হয় (actual file content না)
 *  - Offline এ পুরো folder tree browse করা যাবে
 *  - App sign-in বা launch এ auto trigger হয়
 *  - WiFi বা mobile data — দুটোতেই চলে (metadata = tiny, no bandwidth concern)
 *  - Recursive — root থেকে সব nested folder পর্যন্ত যায়
 *  - Max depth: 8 (infinite loop protection)
 */
class DriveMetadataSyncWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    companion object {
        private const val TAG              = "DriveMetaSync"
        private const val WORK_NAME        = "drive_metadata_sync"
        private const val WORK_NAME_ONCE   = "drive_metadata_sync_once"
        const val KEY_ACCOUNT              = "account_name"
        private const val MAX_DEPTH        = 8
        private const val MAX_FOLDERS      = 500 // safety cap

        /**
         * Periodic schedule — every 3 hours, any network
         * (metadata is tiny — no need to restrict to WiFi)
         */
        fun schedule(context: Context, accountName: String) {
            val req = PeriodicWorkRequestBuilder<DriveMetadataSyncWorker>(3, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInputData(workDataOf(KEY_ACCOUNT to accountName))
                .setInitialDelay(5, TimeUnit.SECONDS) // first run almost immediately
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE, // update if account changes
                req
            )
            Log.d(TAG, "Periodic metadata sync scheduled for $accountName")
        }

        /**
         * One-shot — call on sign-in / first open / manual refresh
         * Runs as soon as network is available
         */
        fun runNow(context: Context, accountName: String) {
            val req = OneTimeWorkRequestBuilder<DriveMetadataSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInputData(workDataOf(KEY_ACCOUNT to accountName))
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_ONCE,
                ExistingWorkPolicy.REPLACE,
                req
            )
            Log.d(TAG, "One-shot metadata sync queued for $accountName")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_ONCE)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context     = applicationContext
        val accountName = inputData.getString(KEY_ACCOUNT) ?: run {
            Log.w(TAG, "No account name — skipping")
            return@withContext Result.success()
        }

        DriveCacheManager.init(context)

        if (!DriveCacheManager.isOnline(context)) {
            Log.d(TAG, "Offline — retry later")
            return@withContext Result.retry()
        }

        Log.d(TAG, "Starting recursive metadata sync for $accountName")
        val totalFoldersSynced = syncFolderRecursive(
            context     = context,
            accountName = accountName,
            folderId    = "root",
            folderName  = "My Drive",
            depth       = 0,
            visited     = mutableSetOf()
        )
        Log.d(TAG, "Metadata sync complete — $totalFoldersSynced folders cached")

        Result.success()
    }

    /**
     * Recursive folder sync — BFS style with depth + visited protection
     * Returns total number of folders synced
     */
    private suspend fun syncFolderRecursive(
        context: Context,
        accountName: String,
        folderId: String,
        folderName: String,
        depth: Int,
        visited: MutableSet<String>
    ): Int {
        if (depth > MAX_DEPTH)        { Log.d(TAG, "Max depth reached at $folderName"); return 0 }
        if (visited.size > MAX_FOLDERS) { Log.d(TAG, "Max folders cap reached"); return 0 }
        if (folderId in visited)       { return 0 } // circular reference protection
        visited.add(folderId)

        return try {
            val files = DriveFileManager.listFiles(context, accountName, folderId)
            if (files == null) {
                Log.w(TAG, "Failed to list $folderName ($folderId)")
                return 0
            }

            // Sort: folders first, then files alphabetically
            val sorted = files.sortedWith(
                compareBy(
                    { it.mimeType != "application/vnd.google-apps.folder" },
                    { it.name?.lowercase() ?: "" }
                )
            )

            // Save this folder's listing to cache
            DriveCacheManager.saveFileList(context, accountName, folderId, sorted)
            Log.d(TAG, "[$depth] Cached '$folderName' — ${sorted.size} items")

            var count = 1

            // Recurse into sub-folders
            val subFolders = sorted.filter {
                it.mimeType == "application/vnd.google-apps.folder" && it.id != null
            }
            for (sub in subFolders) {
                if (visited.size > MAX_FOLDERS) break
                count += syncFolderRecursive(
                    context     = context,
                    accountName = accountName,
                    folderId    = sub.id!!,
                    folderName  = sub.name ?: "Folder",
                    depth       = depth + 1,
                    visited     = visited
                )
            }
            count
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing folder $folderName ($folderId)", e)
            0
        }
    }
}

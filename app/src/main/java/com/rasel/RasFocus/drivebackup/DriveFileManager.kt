package com.rasel.RasFocus.drivebackup

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DriveFileManager {

    private const val TAG = "DriveFileManager"
    var lastError: String? = null
        private set
    var lastRecoveryIntent: Intent? = null
        private set

    private fun recordFailure(tag: String, e: Exception) {
        if (e is UserRecoverableAuthIOException) {
            lastError = "Full Drive permission is required. Please grant access."
            lastRecoveryIntent = e.intent
            Log.w(TAG, "$tag: UserRecoverableAuthIOException", e)
        } else {
            lastError = e.toString() // Show full exception class and message
            lastRecoveryIntent = null
            Log.e(TAG, "$tag failed: ${e.message}", e)
        }
    }

    private fun buildDriveService(context: Context, accountName: String): Drive? {
        return try {
            // Request FULL DRIVE scope for the file manager and set explicit account email
            val credential = GoogleAccountCredential.usingOAuth2(
                context, listOf(DriveScopes.DRIVE)
            ).apply { selectedAccountName = accountName }
            
            Drive.Builder(
                NetHttpTransport(), GsonFactory.getDefaultInstance(), credential
            ).setApplicationName("RasFocus+ File Manager").build()
        } catch (e: Exception) {
            recordFailure("buildDriveService", e)
            null
        }
    }

    // List files inside a specific folder ID, or 'root' if null
    suspend fun listFiles(context: Context, accountName: String, folderId: String = "root"): List<File>? = withContext(Dispatchers.IO) {
        val driveService = buildDriveService(context, accountName) ?: return@withContext null
        try {
            val query = "'$folderId' in parents and trashed = false"
            val result = driveService.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("nextPageToken, files(id, name, mimeType, size, modifiedTime, thumbnailLink, hasThumbnail)")
                .execute()
            
            lastError = null
            lastRecoveryIntent = null
            result.files
        } catch (e: Exception) {
            recordFailure("listFiles", e)
            null
        }
    }

    // Fetch ALL images from Drive (for Photo Gallery screen)
    suspend fun listImages(context: Context, accountName: String): List<File>? = withContext(Dispatchers.IO) {
        val driveService = buildDriveService(context, accountName) ?: return@withContext null
        try {
            val query = "mimeType contains 'image/' and trashed = false"
            val allImages = mutableListOf<File>()
            var pageToken: String? = null
            do {
                val result = driveService.files().list()
                    .setQ(query)
                    .setSpaces("drive")
                    .setFields("nextPageToken, files(id, name, mimeType, size, modifiedTime, thumbnailLink, hasThumbnail)")
                    .setOrderBy("modifiedTime desc")
                    .setPageSize(100)
                    .setPageToken(pageToken)
                    .execute()
                allImages.addAll(result.files)
                pageToken = result.nextPageToken
            } while (pageToken != null && allImages.size < 500)
            lastError = null
            lastRecoveryIntent = null
            allImages
        } catch (e: Exception) {
            recordFailure("listImages", e)
            null
        }
    }

    suspend fun downloadFile(
        context: Context, 
        accountName: String, 
        fileId: String, 
        fileName: String, 
        outputDir: java.io.File = context.cacheDir,
        progressListener: ((bytesDownloaded: Long) -> Unit)? = null
    ): java.io.File? = withContext(Dispatchers.IO) {
        val driveService = buildDriveService(context, accountName) ?: return@withContext null
        try {
            val destination = java.io.File(outputDir, fileName)
            destination.outputStream().use { out ->
                val request = driveService.files().get(fileId)
                if (progressListener != null) {
                    request.mediaHttpDownloader.setProgressListener { downloader ->
                        progressListener(downloader.numBytesDownloaded)
                    }
                }
                request.executeMediaAndDownloadTo(out)
            }
            destination
        } catch (e: Exception) {
            recordFailure("downloadFile", e)
            null
        }
    }

    suspend fun uploadFile(
        context: Context, 
        accountName: String, 
        localFile: java.io.File, 
        parentFolderId: String = "root",
        progressListener: ((bytesUploaded: Long) -> Unit)? = null
    ): com.google.api.services.drive.model.File? = withContext(Dispatchers.IO) {
        val driveService = buildDriveService(context, accountName) ?: return@withContext null
        try {
            val fileMetadata = com.google.api.services.drive.model.File()
            fileMetadata.name = localFile.name
            fileMetadata.parents = listOf(parentFolderId)
            
            val mediaContent = com.google.api.client.http.FileContent(null, localFile)
            val request = driveService.files().create(fileMetadata, mediaContent)
                .setFields("id, name")
            
            if (progressListener != null) {
                request.mediaHttpUploader.setProgressListener { uploader ->
                    progressListener(uploader.numBytesUploaded)
                }
            }
            
            val file = request.execute()
            
            lastError = null
            lastRecoveryIntent = null
            file
        } catch (e: Exception) {
            recordFailure("uploadFile", e)
            null
        }
    }

    suspend fun createFolder(context: Context, accountName: String, folderName: String, parentFolderId: String = "root"): File? = withContext(Dispatchers.IO) {
        val driveService = buildDriveService(context, accountName) ?: return@withContext null
        try {
            val fileMetadata = File().apply {
                name = folderName
                mimeType = "application/vnd.google-apps.folder"
                parents = listOf(parentFolderId)
            }
            val folder = driveService.files().create(fileMetadata)
                .setFields("id, name")
                .execute()
            
            lastError = null
            lastRecoveryIntent = null
            folder
        } catch (e: Exception) {
            recordFailure("createFolder", e)
            null
        }
    }

    suspend fun uploadFolder(context: Context, accountName: String, localFolder: java.io.File, parentFolderId: String = "root"): Boolean = withContext(Dispatchers.IO) {
        val createdFolder = createFolder(context, accountName, localFolder.name, parentFolderId) ?: return@withContext false
        val children = localFolder.listFiles() ?: return@withContext true
        
        var success = true
        for (child in children) {
            if (child.isDirectory) {
                if (!uploadFolder(context, accountName, child, createdFolder.id)) {
                    success = false
                }
            } else {
                if (uploadFile(context, accountName, child, createdFolder.id) == null) {
                    success = false
                }
            }
        }
        success
    }

    suspend fun downloadFolder(context: Context, accountName: String, folderId: String, folderName: String, localDestDir: java.io.File): Boolean = withContext(Dispatchers.IO) {
        val targetLocalFolder = java.io.File(localDestDir, folderName)
        if (!targetLocalFolder.exists()) {
            targetLocalFolder.mkdirs()
        }

        val driveService = buildDriveService(context, accountName) ?: return@withContext false
        try {
            val query = "'$folderId' in parents and trashed = false"
            var pageToken: String? = null
            var success = true
            do {
                val result = driveService.files().list()
                    .setQ(query)
                    .setSpaces("drive")
                    .setFields("nextPageToken, files(id, name, mimeType)")
                    .setPageToken(pageToken)
                    .execute()

                for (file in result.files) {
                    if (file.mimeType == "application/vnd.google-apps.folder") {
                        if (!downloadFolder(context, accountName, file.id, file.name, targetLocalFolder)) {
                            success = false
                        }
                    } else {
                        if (downloadFile(context, accountName, file.id, file.name, targetLocalFolder) == null) {
                            success = false
                        }
                    }
                }
                pageToken = result.nextPageToken
            } while (pageToken != null)
            
            success
        } catch (e: Exception) {
            recordFailure("downloadFolder", e)
            false
        }
    }

    /**
     * Copy a single FILE on Drive (same account).
     * For folders use copyFolderRecursive().
     */
    suspend fun copyFile(context: Context, accountName: String, fileId: String, newParentId: String): File? = withContext(Dispatchers.IO) {
        val driveService = buildDriveService(context, accountName) ?: return@withContext null
        try {
            val file = driveService.files().get(fileId).setFields("name").execute()
            val copiedFile = File().apply {
                name = file.name
                parents = listOf(newParentId)
            }
            driveService.files().copy(fileId, copiedFile).execute()
        } catch (e: Exception) {
            recordFailure("copyFile", e)
            null
        }
    }

    /**
     * Recursively copy a FOLDER on Drive (same account).
     * Drive API does not support folder copy natively —
     * we recreate the tree: create folder → copy each child.
     */
    suspend fun copyFolderRecursive(
        context: Context,
        accountName: String,
        sourceFolderId: String,
        folderName: String,
        destParentId: String
    ): Boolean = withContext(Dispatchers.IO) {
        val driveService = buildDriveService(context, accountName) ?: return@withContext false
        try {
            // 1. Create destination folder
            val newFolder = File().apply {
                name = folderName
                mimeType = "application/vnd.google-apps.folder"
                parents = listOf(destParentId)
            }
            val created = driveService.files().create(newFolder).setFields("id").execute()
                ?: return@withContext false
            val newFolderId = created.id ?: return@withContext false

            // 2. List all children in source folder
            var pageToken: String? = null
            var success = true
            do {
                val result = driveService.files().list()
                    .setQ("'$sourceFolderId' in parents and trashed = false")
                    .setSpaces("drive")
                    .setFields("nextPageToken, files(id, name, mimeType)")
                    .setPageToken(pageToken)
                    .execute()

                for (child in result.files) {
                    if (child.mimeType == "application/vnd.google-apps.folder") {
                        // Recurse into sub-folder
                        if (!copyFolderRecursive(context, accountName, child.id, child.name, newFolderId)) {
                            success = false
                        }
                    } else {
                        // Copy file into new folder
                        if (copyFile(context, accountName, child.id, newFolderId) == null) {
                            success = false
                        }
                    }
                }
                pageToken = result.nextPageToken
            } while (pageToken != null)

            success
        } catch (e: Exception) {
            recordFailure("copyFolderRecursive", e)
            false
        }
    }

    /**
     * Cross-account Drive→Drive copy.
     * Downloads file from srcAccount, uploads to destAccount.
     * For folders: recursive download+upload via temp dir.
     */
    suspend fun crossAccountCopyFile(
        context: Context,
        srcAccount: String,
        destAccount: String,
        fileId: String,
        fileName: String,
        destParentId: String,
        isFolder: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val tmpDir = java.io.File(context.cacheDir, "drive_xfer_${System.currentTimeMillis()}")
        tmpDir.mkdirs()
        return@withContext try {
            if (isFolder) {
                val ok = downloadFolder(context, srcAccount, fileId, fileName, tmpDir)
                if (!ok) return@withContext false
                uploadFolder(context, destAccount, java.io.File(tmpDir, fileName), destParentId)
            } else {
                val downloaded = downloadFile(context, srcAccount, fileId, fileName, tmpDir)
                    ?: return@withContext false
                uploadFile(context, destAccount, downloaded, destParentId) != null
            }
        } catch (e: Exception) {
            recordFailure("crossAccountCopyFile", e)
            false
        } finally {
            try { tmpDir.deleteRecursively() } catch (_: Exception) {}
        }
    }



    suspend fun moveFile(context: Context, accountName: String, fileId: String, newParentId: String, oldParentId: String): File? = withContext(Dispatchers.IO) {
        val driveService = buildDriveService(context, accountName) ?: return@withContext null
        try {
            val file = driveService.files().get(fileId).setFields("parents").execute()
            val previousParents = file.parents?.joinToString(",") ?: oldParentId

            driveService.files().update(fileId, null)
                .setAddParents(newParentId)
                .setRemoveParents(previousParents)
                .setFields("id, parents")
                .execute()
        } catch (e: Exception) {
            recordFailure("moveFile", e)
            null
        }
    }

    /**
     * Permanently deletes a file or folder from Google Drive.
     * Returns true on success, false on failure.
     */
    suspend fun deleteFile(context: Context, accountName: String, fileId: String): Boolean = withContext(Dispatchers.IO) {
        val driveService = buildDriveService(context, accountName) ?: return@withContext false
        return@withContext try {
            driveService.files().delete(fileId).execute()
            true
        } catch (e: Exception) {
            recordFailure("deleteFile", e)
            false
        }
    }

    /**
     * Renames a file or folder in Google Drive.
     * Returns the updated File metadata on success, null on failure.
     */
    suspend fun renameFile(context: Context, accountName: String, fileId: String, newName: String): File? = withContext(Dispatchers.IO) {
        val driveService = buildDriveService(context, accountName) ?: return@withContext null
        return@withContext try {
            val updatedMeta = File().apply { name = newName }
            driveService.files().update(fileId, updatedMeta).setFields("id, name").execute()
        } catch (e: Exception) {
            recordFailure("renameFile", e)
            null
        }
    }
}

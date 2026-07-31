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
                .setFields("nextPageToken, files(id, name, mimeType, size, modifiedTime)")
                .execute()
            
            lastError = null
            lastRecoveryIntent = null
            result.files
        } catch (e: Exception) {
            recordFailure("listFiles", e)
            null
        }
    }

    suspend fun downloadFile(context: Context, accountName: String, fileId: String, fileName: String, outputDir: java.io.File = context.cacheDir): java.io.File? = withContext(Dispatchers.IO) {
        val driveService = buildDriveService(context, accountName) ?: return@withContext null
        try {
            val destination = java.io.File(outputDir, fileName)
            destination.outputStream().use { out ->
                driveService.files().get(fileId).executeMediaAndDownloadTo(out)
            }
            destination
        } catch (e: Exception) {
            recordFailure("downloadFile", e)
            null
        }
    }

    suspend fun uploadFile(context: Context, accountName: String, localFile: java.io.File, parentFolderId: String = "root"): com.google.api.services.drive.model.File? = withContext(Dispatchers.IO) {
        val driveService = buildDriveService(context, accountName) ?: return@withContext null
        try {
            val fileMetadata = com.google.api.services.drive.model.File()
            fileMetadata.name = localFile.name
            fileMetadata.parents = listOf(parentFolderId)
            
            val mediaContent = com.google.api.client.http.FileContent(null, localFile)
            val file = driveService.files().create(fileMetadata, mediaContent)
                .setFields("id, name")
                .execute()
            
            lastError = null
            lastRecoveryIntent = null
            file
        } catch (e: Exception) {
            recordFailure("uploadFile", e)
            null
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

    suspend fun copyFile(context: Context, accountName: String, fileId: String, newParentId: String): File? = withContext(Dispatchers.IO) {
        val driveService = buildDriveService(context, accountName) ?: return@withContext null
        try {
            val file = driveService.files().get(fileId).setFields("name").execute()
            val copiedFile = File().apply {
                name = file.name // Keep original name
                parents = listOf(newParentId)
            }
            driveService.files().copy(fileId, copiedFile).execute()
        } catch (e: Exception) {
            recordFailure("copyFile", e)
            null
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
}

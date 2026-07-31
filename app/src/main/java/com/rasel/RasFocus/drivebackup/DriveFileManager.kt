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
            lastError = e.message ?: e.javaClass.simpleName
            lastRecoveryIntent = null
            Log.e(TAG, "$tag failed: ${e.message}", e)
        }
    }

    private fun buildDriveService(context: Context): Drive? {
        return try {
            val account = GoogleSignIn.getLastSignedInAccount(context)
            if (account?.account == null) {
                lastError = "No Google account signed in."
                return null
            }
            // Request FULL DRIVE scope for the file manager
            val credential = GoogleAccountCredential.usingOAuth2(
                context, listOf(DriveScopes.DRIVE)
            ).also { it.selectedAccount = account.account }
            
            Drive.Builder(
                NetHttpTransport(), GsonFactory.getDefaultInstance(), credential
            ).setApplicationName("RasFocus+ File Manager").build()
        } catch (e: Exception) {
            recordFailure("buildDriveService", e)
            null
        }
    }

    // List files inside a specific folder ID, or 'root' if null
    suspend fun listFiles(context: Context, folderId: String = "root"): List<File>? = withContext(Dispatchers.IO) {
        val driveService = buildDriveService(context) ?: return@withContext null
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

    suspend fun downloadFile(context: Context, fileId: String, fileName: String): java.io.File? = withContext(Dispatchers.IO) {
        val driveService = buildDriveService(context) ?: return@withContext null
        try {
            val destination = java.io.File(context.cacheDir, fileName)
            destination.outputStream().use { out ->
                driveService.files().get(fileId).executeMediaAndDownloadTo(out)
            }
            destination
        } catch (e: Exception) {
            recordFailure("downloadFile", e)
            null
        }
    }
}

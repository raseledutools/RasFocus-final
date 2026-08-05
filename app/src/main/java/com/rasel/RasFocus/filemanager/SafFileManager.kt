package com.rasel.RasFocus.filemanager

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.io.OutputStream

data class SafFile(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)

object SafFileManager {

    // Store tree URIs granted by the user (usually just one for the SD card)
    val grantedUris = mutableListOf<Uri>()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
        val uris = prefs.getStringSet("granted_uris", emptySet()) ?: emptySet()
        grantedUris.clear()
        for (uriStr in uris) {
            try {
                val uri = Uri.parse(uriStr)
                // Take persistable permission just in case
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                grantedUris.add(uri)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun addUri(context: Context, uri: Uri) {
        if (!grantedUris.contains(uri)) {
            grantedUris.add(uri)
            val prefs = context.getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
            val uris = prefs.getStringSet("granted_uris", emptySet())?.toMutableSet() ?: mutableSetOf()
            uris.add(uri.toString())
            prefs.edit().putStringSet("granted_uris", uris).apply()
            
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun listFiles(context: Context, uri: Uri): List<SafFile> {
        val root = DocumentFile.fromTreeUri(context, uri) ?: return emptyList()
        val files = mutableListOf<SafFile>()
        for (file in root.listFiles()) {
            files.add(
                SafFile(
                    uri = file.uri,
                    name = file.name ?: "Unknown",
                    isDirectory = file.isDirectory,
                    size = file.length(),
                    lastModified = file.lastModified()
                )
            )
        }
        return files.sortedBy { !it.isDirectory }
    }

    // Advanced: To list files in a sub-document, you would need its DocumentFile.
    fun listFilesFromDocument(documentFile: DocumentFile): List<SafFile> {
        val files = mutableListOf<SafFile>()
        for (file in documentFile.listFiles()) {
            files.add(
                SafFile(
                    uri = file.uri,
                    name = file.name ?: "Unknown",
                    isDirectory = file.isDirectory,
                    size = file.length(),
                    lastModified = file.lastModified()
                )
            )
        }
        return files.sortedBy { !it.isDirectory }
    }
}

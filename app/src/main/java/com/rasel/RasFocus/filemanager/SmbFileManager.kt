package com.rasel.RasFocus.filemanager

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SmbFile(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)

object SmbFileManager {

    suspend fun listShares(server: RemoteServer): List<String> = withContext(Dispatchers.IO) {
        val client = SMBClient()
        var connection: Connection? = null
        try {
            connection = client.connect(server.host)
            val auth = if (server.user.isNotBlank() && server.user.lowercase() != "anonymous") {
                AuthenticationContext(server.user, server.pass.toCharArray(), "")
            } else {
                AuthenticationContext.anonymous()
            }
            val session = connection.authenticate(auth)
            val shares = client.shareLocator.getShares(session, server.host)
            shares.map { it.smbNetworkResource.name }.filter { !it.endsWith("$") } // hide administrative shares
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        } finally {
            try { connection?.close() } catch (e: Exception) { }
            try { client.close() } catch (e: Exception) { }
        }
    }

    suspend fun listFiles(server: RemoteServer, shareName: String, path: String): List<SmbFile> = withContext(Dispatchers.IO) {
        val client = SMBClient()
        var connection: Connection? = null
        try {
            connection = client.connect(server.host)
            val auth = if (server.user.isNotBlank() && server.user.lowercase() != "anonymous") {
                AuthenticationContext(server.user, server.pass.toCharArray(), "")
            } else {
                AuthenticationContext.anonymous()
            }
            val session = connection.authenticate(auth)
            val share = session.connectShare(shareName) as DiskShare
            val internalPath = if (path.startsWith("/")) path.substring(1) else path
            val results = share.list(internalPath.ifEmpty { "" })
            results.filter { it.fileName != "." && it.fileName != ".." }.map { fileIdBothDirectoryInfo ->
                val name = fileIdBothDirectoryInfo.fileName
                val isDir = (fileIdBothDirectoryInfo.fileAttributes and com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                val size = fileIdBothDirectoryInfo.endOfFile
                val lastModified = fileIdBothDirectoryInfo.lastWriteTime.toEpochMillis()
                SmbFile(name, isDir, size, lastModified)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        } finally {
            try { connection?.close() } catch (e: Exception) { }
            try { client.close() } catch (e: Exception) { }
        }
    }
}

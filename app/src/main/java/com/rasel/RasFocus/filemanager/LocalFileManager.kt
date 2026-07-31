package com.rasel.RasFocus.filemanager

import android.os.Environment
import java.io.File

object LocalFileManager {

    val mainStoragePath: String
        get() = Environment.getExternalStorageDirectory().absolutePath

    fun getMainStorageInfo(): String {
        return try {
            val stat = android.os.StatFs(mainStoragePath)
            val total = stat.totalBytes
            val available = stat.availableBytes
            val used = total - available
            "${formatFileSize(used)} / ${formatFileSize(total)}"
        } catch (e: Exception) {
            ""
        }
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "kB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return java.text.DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }

    fun listFiles(path: String): List<File> {
        return try {
            File(path).listFiles()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}

package com.rasel.RasFocus.filemanager

import android.content.Context
import android.os.Environment
import java.io.File
import androidx.core.content.ContextCompat

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

    fun getSdCardPath(context: Context): String? {
        val dirs = ContextCompat.getExternalFilesDirs(context, null)
        for (dir in dirs) {
            if (dir != null && Environment.isExternalStorageRemovable(dir)) {
                val path = dir.absolutePath
                val split = path.split("/Android/")
                if (split.isNotEmpty()) {
                    return split[0]
                }
            }
        }
        // Fallback for older devices or specific manufacturers
        val fallback = System.getenv("SECONDARY_STORAGE")?.split(":")?.firstOrNull()
        if (fallback != null && File(fallback).exists()) {
            return fallback
        }
        return null
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

    fun hasStorageAccess(context: Context): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
}

package com.rasel.RasFocus.filemanager

import android.content.Context
import android.os.Environment
import java.io.File
import androidx.core.content.ContextCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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
        val fallback = System.getenv("SECONDARY_STORAGE")?.split(":")?.firstOrNull()
        if (fallback != null && File(fallback).exists()) {
            return fallback
        }
        return null
    }

    fun listFiles(path: String, showHidden: Boolean = false): List<File> {
        return try {
            File(path).listFiles()
                ?.filter { showHidden || !it.name.startsWith(".") || path.contains(".vault") || path.contains(".trash") }
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

    // --- Premium Features ---

    val trashPath: String
        get() = "$mainStoragePath/.trash"

    val vaultPath: String
        get() = "$mainStoragePath/.vault"

    fun initPremiumFolders() {
        val trash = File(trashPath)
        if (!trash.exists()) trash.mkdirs()
        File(trash, ".nomedia").createNewFile()

        val vault = File(vaultPath)
        if (!vault.exists()) vault.mkdirs()
        File(vault, ".nomedia").createNewFile()
    }

    fun moveToTrash(file: File): Boolean {
        initPremiumFolders()
        val dest = File(trashPath, file.name)
        return file.renameTo(dest)
    }

    fun restoreFromTrash(file: File, originalParent: String): Boolean {
        val dest = File(originalParent, file.name)
        return file.renameTo(dest)
    }

    fun moveToVault(file: File): Boolean {
        initPremiumFolders()
        val dest = File(vaultPath, file.name)
        return file.renameTo(dest)
    }

    fun restoreFromVault(file: File, destParent: String): Boolean {
        val dest = File(destParent, file.name)
        return file.renameTo(dest)
    }

    fun zipFiles(files: List<File>, zipFile: File): Boolean {
        return try {
            FileOutputStream(zipFile).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    for (file in files) {
                        addToZip(file, file.name, zos)
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun addToZip(file: File, fileName: String, zos: ZipOutputStream) {
        if (file.isHidden) return
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children != null) {
                for (child in children) {
                    addToZip(child, "$fileName/${child.name}", zos)
                }
            }
        } else {
            FileInputStream(file).use { fis ->
                val zipEntry = ZipEntry(fileName)
                zos.putNextEntry(zipEntry)
                fis.copyTo(zos)
                zos.closeEntry()
            }
        }
    }

    fun unzipFile(zipFile: File, targetDirectory: File): Boolean {
        return try {
            if (!targetDirectory.exists()) targetDirectory.mkdirs()
            FileInputStream(zipFile).use { fis ->
                ZipInputStream(fis).use { zis ->
                    var zipEntry = zis.nextEntry
                    while (zipEntry != null) {
                        val newFile = File(targetDirectory, zipEntry.name)
                        if (zipEntry.isDirectory) {
                            newFile.mkdirs()
                        } else {
                            newFile.parentFile?.mkdirs()
                            FileOutputStream(newFile).use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                        zis.closeEntry()
                        zipEntry = zis.nextEntry
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}

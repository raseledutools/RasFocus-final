package com.rasel.RasFocus.filemanager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ZipHelper {
    
    suspend fun zipFiles(filesToZip: List<File>, destZipFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            FileOutputStream(destZipFile).use { fos ->
                ZipOutputStream(BufferedOutputStream(fos)).use { zos ->
                    for (file in filesToZip) {
                        if (file.isDirectory) {
                            zipDirectory(file, file.name, zos)
                        } else {
                            zipSingleFile(file, file.name, zos)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun zipDirectory(dirToZip: File, parentName: String, zos: ZipOutputStream) {
        val files = dirToZip.listFiles()
        if (files == null || files.isEmpty()) {
            val entry = ZipEntry("$parentName/")
            zos.putNextEntry(entry)
            zos.closeEntry()
            return
        }
        for (file in files) {
            if (file.isDirectory) {
                zipDirectory(file, "$parentName/${file.name}", zos)
            } else {
                zipSingleFile(file, "$parentName/${file.name}", zos)
            }
        }
    }

    private fun zipSingleFile(file: File, entryName: String, zos: ZipOutputStream) {
        val entry = ZipEntry(entryName)
        zos.putNextEntry(entry)
        FileInputStream(file).use { fis ->
            BufferedInputStream(fis).use { bis ->
                bis.copyTo(zos)
            }
        }
        zos.closeEntry()
    }

    suspend fun unzipFile(zipFile: File, destDir: File): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!destDir.exists()) {
                destDir.mkdirs()
            }
            FileInputStream(zipFile).use { fis ->
                ZipInputStream(BufferedInputStream(fis)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val newFile = File(destDir, entry.name)
                        // Prevent Zip Slip vulnerability
                        if (!newFile.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) {
                            throw SecurityException("Entry is outside of the target dir: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            newFile.mkdirs()
                        } else {
                            newFile.parentFile?.mkdirs()
                            FileOutputStream(newFile).use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

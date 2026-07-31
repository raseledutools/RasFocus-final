package com.rasel.RasFocus.filemanager

import android.os.Environment
import java.io.File

object LocalFileManager {

    val mainStoragePath: String
        get() = Environment.getExternalStorageDirectory().absolutePath

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

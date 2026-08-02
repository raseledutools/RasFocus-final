package com.rasel.RasFocus.filemanager

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

data class CachedFileMetadata(
    val id: String,
    val name: String,
    val size: Long?,
    val isDirectory: Boolean,
    val modifiedTime: Long,
    val parentId: String = "root"
)

object DriveCacheManager {
    private const val PREFS_NAME = "DriveCachePrefs"
    private const val KEY_CACHED_FILES = "cached_files"
    private const val CACHE_DIR_NAME = ".drive_cache"

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    fun init(context: Context) {
        if (!this::prefs.isInitialized) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun getCacheDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), CACHE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getCachedFile(context: Context, fileId: String, fileName: String): File? {
        val dir = getCacheDir(context)
        val safeName = "${fileId}_$fileName"
        val file = File(dir, safeName)
        return if (file.exists() && file.length() > 0) file else null
    }

    fun markFileDownloaded(context: Context, fileId: String, fileName: String) {
        val cachedMap = getCachedMap()
        cachedMap[fileId] = System.currentTimeMillis() // store download timestamp
        saveCachedMap(cachedMap)
    }
    
    fun isFileCached(context: Context, fileId: String, fileName: String): Boolean {
        // Double check if file physically exists too
        return getCachedFile(context, fileId, fileName) != null
    }

    fun clearCache(context: Context) {
        val dir = getCacheDir(context)
        dir.deleteRecursively()
        dir.mkdirs()
        saveCachedMap(emptyMap())
    }

    private fun getCachedMap(): MutableMap<String, Long> {
        val json = prefs.getString(KEY_CACHED_FILES, null)
        return if (json != null) {
            val type = object : TypeToken<MutableMap<String, Long>>() {}.type
            gson.fromJson(json, type)
        } else {
            mutableMapOf()
        }
    }

    private fun saveCachedMap(map: Map<String, Long>) {
        prefs.edit().putString(KEY_CACHED_FILES, gson.toJson(map)).apply()
    }
}

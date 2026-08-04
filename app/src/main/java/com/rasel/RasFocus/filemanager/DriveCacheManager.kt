package com.rasel.RasFocus.filemanager

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
    private const val PREFS_NAME        = "DriveCachePrefs"
    private const val KEY_CACHED_FILES  = "cached_files"
    private const val KEY_CACHE_TIME    = "cache_time"
    private const val CACHE_DIR_NAME    = ".drive_cache"
    private const val THUMB_DIR_NAME    = ".drive_thumbs"
    // "Make available offline" — explicitly pinned file IDs
    private const val KEY_PINNED_FILES  = "pinned_offline_files"

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    fun init(context: Context) {
        if (!this::prefs.isInitialized) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    // ── Network check ──────────────────────────────────────────────────────────
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // ── File content cache ─────────────────────────────────────────────────────
    fun getCacheDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), CACHE_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getThumbDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), THUMB_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
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
        cachedMap[fileId] = System.currentTimeMillis()
        saveCachedMap(cachedMap)
    }

    fun isFileCached(context: Context, fileId: String, fileName: String): Boolean =
        getCachedFile(context, fileId, fileName) != null

    // ── Thumbnail cache ────────────────────────────────────────────────────────
    // Save a scaled-down thumbnail bitmap for a drive image file
    fun saveThumbnail(context: Context, fileId: String, bitmap: Bitmap) {
        val thumbFile = File(getThumbDir(context), "${fileId}.jpg")
        try {
            thumbFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)
            }
        } catch (_: Exception) {}
    }

    fun getThumbnail(context: Context, fileId: String): Bitmap? {
        val thumbFile = File(getThumbDir(context), "${fileId}.jpg")
        return if (thumbFile.exists()) {
            try { BitmapFactory.decodeFile(thumbFile.absolutePath) } catch (_: Exception) { null }
        } else null
    }

    fun hasThumbnail(context: Context, fileId: String): Boolean =
        File(getThumbDir(context), "${fileId}.jpg").exists()

    // ── "Make Available Offline" — Pinned files ────────────────────────────────
    // Pin = user explicitly requested offline access for this file/folder
    fun pinFileOffline(fileId: String) {
        val pinned = getPinnedSet().toMutableSet()
        pinned.add(fileId)
        prefs.edit().putStringSet(KEY_PINNED_FILES, pinned).apply()
    }

    fun unpinFileOffline(fileId: String) {
        val pinned = getPinnedSet().toMutableSet()
        pinned.remove(fileId)
        prefs.edit().putStringSet(KEY_PINNED_FILES, pinned).apply()
    }

    fun isFilePinned(fileId: String): Boolean = getPinnedSet().contains(fileId)

    fun getPinnedFileIds(): Set<String> = getPinnedSet()

    private fun getPinnedSet(): Set<String> =
        prefs.getStringSet(KEY_PINNED_FILES, emptySet()) ?: emptySet()

    fun clearCache(context: Context) {
        getCacheDir(context).deleteRecursively()
        getCacheDir(context).mkdirs()
        getThumbDir(context).deleteRecursively()
        getThumbDir(context).mkdirs()
        saveCachedMap(emptyMap())
        prefs.edit().also { ed ->
            prefs.all.keys.filter { it.startsWith("filelist_") || it.startsWith("cachetime_") }
                .forEach { ed.remove(it) }
        }.apply()
        // Note: pinned list is intentionally kept — user's explicit choice survives cache clear
    }

    // ── Folder listing cache (per account + folderId) ─────────────────────────
    fun saveFileList(
        context: Context,
        accountName: String,
        folderId: String,
        files: List<com.google.api.services.drive.model.File>
    ) {
        val key     = "filelist_${accountName}_$folderId"
        val timeKey = "cachetime_${accountName}_$folderId"
        val json    = gson.toJson(files)
        prefs.edit()
            .putString(key, json)
            .putLong(timeKey, System.currentTimeMillis())
            .apply()
    }

    fun loadFileList(
        context: Context,
        accountName: String,
        folderId: String
    ): List<com.google.api.services.drive.model.File>? {
        val key  = "filelist_${accountName}_$folderId"
        val json = prefs.getString(key, null) ?: return null
        return try {
            val type = object : TypeToken<List<com.google.api.services.drive.model.File>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) { null }
    }

    fun cacheAgeMinutes(accountName: String, folderId: String): Long? {
        val timeKey = "cachetime_${accountName}_$folderId"
        val t = prefs.getLong(timeKey, 0L)
        if (t == 0L) return null
        return (System.currentTimeMillis() - t) / 60_000L
    }

    fun hasCachedList(accountName: String, folderId: String): Boolean {
        val key = "filelist_${accountName}_$folderId"
        return prefs.contains(key)
    }

    // ── Internal map of file-content cache ────────────────────────────────────
    private fun getCachedMap(): MutableMap<String, Long> {
        val json = prefs.getString(KEY_CACHED_FILES, null)
        return if (json != null) {
            val type = object : TypeToken<MutableMap<String, Long>>() {}.type
            gson.fromJson(json, type)
        } else mutableMapOf()
    }

    private fun saveCachedMap(map: Map<String, Long>) {
        prefs.edit().putString(KEY_CACHED_FILES, gson.toJson(map)).apply()
    }
}

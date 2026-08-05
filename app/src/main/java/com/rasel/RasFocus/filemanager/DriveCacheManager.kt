package com.rasel.RasFocus.filemanager

import android.content.Context
import android.content.SharedPreferences
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
    private const val PREFS_NAME       = "DriveCachePrefs"
    private const val KEY_CACHED_FILES = "cached_files"
    private const val KEY_PINNED_FILES = "pinned_file_ids"   // NEW: "Make available offline" pins
    private const val CACHE_DIR_NAME   = ".drive_cache"

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    fun init(context: Context) {
        if (!this::prefs.isInitialized) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    // ── Network check ──────────────────────────────────────────────────────────
    fun isOnline(context: Context): Boolean {
        val cm   = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val net  = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // ── File content cache dir ─────────────────────────────────────────────────
    fun getCacheDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), CACHE_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getCachedFile(context: Context, fileId: String, fileName: String): File? {
        val dir      = getCacheDir(context)
        val safeName = "${fileId}_$fileName"
        val file     = File(dir, safeName)
        return if (file.exists() && file.length() > 0) file else null
    }

    fun markFileDownloaded(context: Context, fileId: String, fileName: String) {
        val map = getCachedMap().toMutableMap()
        map[fileId] = System.currentTimeMillis()
        saveCachedMap(map)
    }

    fun isFileCached(context: Context, fileId: String, fileName: String): Boolean =
        getCachedFile(context, fileId, fileName) != null

    fun clearCache(context: Context) {
        getCacheDir(context).deleteRecursively()
        getCacheDir(context).mkdirs()
        saveCachedMap(emptyMap())
        prefs.edit().also { ed ->
            prefs.all.keys
                .filter { it.startsWith("filelist_") || it.startsWith("cachetime_") }
                .forEach { ed.remove(it) }
        }.apply()
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
        
        val metadataList = files.map { file ->
            CachedFileMetadata(
                id = file.id ?: "",
                name = file.name ?: "",
                size = file.getSize(),
                isDirectory = file.mimeType == "application/vnd.google-apps.folder",
                modifiedTime = file.modifiedTime?.value ?: 0L,
                parentId = folderId
            )
        }
        
        prefs.edit()
            .putString(key, gson.toJson(metadataList))
            .putLong(timeKey, System.currentTimeMillis())
            .apply()
    }

    fun loadFileList(
        context: Context,
        accountName: String,
        folderId: String
    ): List<com.google.api.services.drive.model.File>? {
        val json = prefs.getString("filelist_${accountName}_$folderId", null) ?: return null
        return try {
            val type = object : TypeToken<List<CachedFileMetadata>>() {}.type
            val metadataList: List<CachedFileMetadata> = gson.fromJson(json, type)
            metadataList.map { meta ->
                com.google.api.services.drive.model.File().apply {
                    id = meta.id ?: ""
                    name = meta.name ?: "Unknown"
                    size = meta.size ?: 0L
                    mimeType = if (meta.isDirectory == true) "application/vnd.google-apps.folder" else "*/*"
                    if (meta.modifiedTime != null && meta.modifiedTime > 0) {
                        modifiedTime = com.google.api.client.util.DateTime(meta.modifiedTime)
                    }
                }
            }
        } catch (_: Exception) { null }
    }

    fun cacheAgeMinutes(accountName: String, folderId: String): Long? {
        val t = prefs.getLong("cachetime_${accountName}_$folderId", 0L)
        if (t == 0L) return null
        return (System.currentTimeMillis() - t) / 60_000L
    }

    fun hasCachedList(accountName: String, folderId: String): Boolean =
        prefs.contains("filelist_${accountName}_$folderId")

    // ── "Make available offline" PIN system ────────────────────────────────────
    // pin()   → marks a file/folder to always keep offline (synced by background worker)
    // unpin() → removes the pin (cache file stays until clearCache)
    // isPinned() → used by UI to show green ✓ badge

    fun pin(fileId: String) {
        val set = getPinnedSet().toMutableSet()
        set.add(fileId)
        prefs.edit().putStringSet(KEY_PINNED_FILES, set).apply()
    }

    fun unpin(fileId: String) {
        val set = getPinnedSet().toMutableSet()
        set.remove(fileId)
        prefs.edit().putStringSet(KEY_PINNED_FILES, set).apply()
    }

    fun isPinned(fileId: String): Boolean = getPinnedSet().contains(fileId)

    fun getPinnedFileIds(): Set<String> = getPinnedSet()

    private fun getPinnedSet(): Set<String> =
        prefs.getStringSet(KEY_PINNED_FILES, emptySet()) ?: emptySet()

    // ── Internal download-time cache map ──────────────────────────────────────
    private fun getCachedMap(): Map<String, Long> {
        val json = prefs.getString(KEY_CACHED_FILES, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<MutableMap<String, Long>>() {}.type
            gson.fromJson(json, type)
        } catch (_: Exception) { emptyMap() }
    }

    private fun saveCachedMap(map: Map<String, Long>) {
        prefs.edit().putString(KEY_CACHED_FILES, gson.toJson(map)).apply()
    }
}

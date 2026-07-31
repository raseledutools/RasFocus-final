package com.rasel.RasFocus.drivebackup

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File as JFile

object DriveBackupManager {

    // ── Public error/recovery state (set after each operation) ───────────────
    private const val TAG              = "DriveBackupManager"
    private const val APP_FOLDER_NAME  = "RasFocus+"
    private const val FOLDER_PREF_KEY  = "drive_app_folder_id"
    private const val FOLDER_PREF_FILE = "drive_prefs"
    private const val DIARY_JSON_NAME  = "RasFocus_Diary_Backup.json"
    private const val DIARY_PDF_NAME   = "RasFocus_Diary_AllEntries.pdf"
    private const val SETTINGS_NAME    = "rasfocus_settings_backup.json"

    // SharedPreference files to include in settings backup
    private val SETTINGS_PREF_NAMES = listOf(
        "browser_settings", "focus_settings", "parental_settings",
        "app_settings", "blocking_settings", "user_prefs"
    )

    // ✅ DIAGNOSTIC: UI-এ আগে শুধু "Upload failed" দেখানো হতো, আসল কারণ কখনো
    // দেখানো হতো না (শুধু Log.e/Log.w তে যেত, যেটা user দেখতে পায় না)।
    // এখন প্রতিটা ব্যর্থতার real reason এখানে জমা থাকে, UI সেটা toast এ দেখাতে পারে।
    var lastError: String? = null
        private set

    // ✅ যদি failure এর কারণ হয় Drive scope/permission missing (সবচেয়ে common
    // কারণ — বিশেষত যারা এই Drive feature আসার আগে sign-in করেছিল), তাহলে
    // Android নিজেই একটা resolution Intent দেয় যেটা launch করলে user সরাসরি
    // permission grant করতে পারে, sign-out/sign-in করা লাগে না।
    var lastRecoveryIntent: Intent? = null
        private set

    private fun recordFailure(tag: String, e: Exception) {
        if (e is UserRecoverableAuthIOException) {
            lastError = "Drive permission দেওয়া হয়নি — নিচের 'Fix Drive Access' বাটনে ট্যাপ করুন।"
            lastRecoveryIntent = e.intent
            Log.w(TAG, "$tag: Drive permission not granted, recovery intent available", e)
        } else {
            lastError = e.message ?: e.javaClass.simpleName
            lastRecoveryIntent = null
            Log.e(TAG, "$tag failed: ${e.message}", e)
        }
    }

    // ── Public check ─────────────────────────────────────────────────────────
    // ✅ FIX: DRIVE_FILE scope না থাকলেও account আছে কিনা দেখো — Drive service
    //         build করার সময় scope automatically request হবে। শুধু account null
    //         হলে unavailable বলো।
    fun isAvailable(context: Context): Boolean {
        return try {
            val account = GoogleSignIn.getLastSignedInAccount(context) ?: return false
            // account আছে মানে sign-in হয়েছে। DRIVE_FILE scope check
            // করি কিন্তু না থাকলেও true return করি — buildDriveService এ
            // credential এ scope add করা হবে, তাই upload try করতে পারবে।
            val hasDriveScope = GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_FILE))
            if (!hasDriveScope) {
                Log.w(TAG, "isAvailable: DRIVE_FILE scope missing — Drive buttons এ click করলে error toast দেখাবে")
            }
            true  // ✅ account থাকলেই Drive section দেখাও; actual failure upload সময় handle হবে
        } catch (e: Exception) {
            Log.w(TAG, "isAvailable check failed: ${e.message}")
            false
        }
    }

    // ── Build Drive service safely on IO thread ───────────────────────────────
    private fun buildDriveService(context: Context): Drive? {
        return try {
            val account = GoogleSignIn.getLastSignedInAccount(context)
            if (account == null) {
                lastError = "কোনো Google account সাইন-ইন করা নেই।"
                Log.w(TAG, "buildDriveService: no signed-in account")
                return null
            }
            val androidAccount = account.account
            if (androidAccount == null) {
                lastError = "Google account পাওয়া যায়নি — আবার sign-in করুন।"
                Log.w(TAG, "buildDriveService: account.account is null")
                return null
            }
            // ✅ FIX: DRIVE_FILE scope না থাকলেও credential এ scope দাও।
            // GoogleAccountCredential OAuth2 scope automatically token request করে।
            // পুরনো code DRIVE_FILE scope না থাকলে null return করত — সেটাই upload fail করাত।
            val credential = GoogleAccountCredential.usingOAuth2(
                context, listOf(DriveScopes.DRIVE_FILE)
            ).also { it.selectedAccount = androidAccount }
            Drive.Builder(
                NetHttpTransport(), GsonFactory.getDefaultInstance(), credential
            ).setApplicationName("RasFocus+").build()
        } catch (e: Exception) {
            recordFailure("buildDriveService", e)
            null
        }
    }

    // ── Get or create the "RasFocus+" folder in Drive ────────────────────────
    private fun getOrCreateFolderId(context: Context, drive: Drive): String? = try {
        val prefs = context.getSharedPreferences(FOLDER_PREF_FILE, Context.MODE_PRIVATE)
        val cached = prefs.getString(FOLDER_PREF_KEY, null)
        // ✅ FIX: cached folder ID আগে কখনো verify করা হতো না — folder delete হয়ে
        // গেলে, account switch হলে, বা drive.file scope এর কারণে আর access না
        // থাকলে, একই ভাঙা ID বারবার ব্যবহার হতো এবং নতুন folder কখনো তৈরি হতো
        // না। এটাই সম্ভবত "যতবারই try করি একইভাবে fail করে" এর আসল কারণ।
        val verifiedCached = cached?.let { id ->
            try {
                val f = drive.files().get(id).setFields("id,trashed").execute()
                if (f.trashed == true) null else id
            } catch (e: Exception) {
                Log.w(TAG, "getOrCreateFolderId: cached folder $id no longer valid (${e.message}) — creating fresh one")
                null
            }
        }
        if (verifiedCached != null) {
            verifiedCached
        } else {
            val query = "name='$APP_FOLDER_NAME' and mimeType='application/vnd.google-apps.folder' and trashed=false"
            val existing = drive.files().list().setQ(query).setSpaces("drive")
                .setFields("files(id)").execute().files?.firstOrNull()
            val folderId = existing?.id ?: run {
                val meta = DriveFile().apply {
                    name     = APP_FOLDER_NAME
                    mimeType = "application/vnd.google-apps.folder"
                }
                drive.files().create(meta).setFields("id").execute().id
            }
            folderId?.also { prefs.edit().putString(FOLDER_PREF_KEY, it).apply() }
        }
    } catch (e: Exception) {
        recordFailure("getOrCreateFolderId", e)
        null
    }

    // ── Generic upload (create or update named file in Drive folder) ──────────
    private suspend fun uploadNamedFile(
        context: Context, localFile: JFile, name: String, mime: String
    ): Boolean = withContext(Dispatchers.IO) {
        lastError = null
        lastRecoveryIntent = null
        try {
            val drive    = buildDriveService(context) ?: return@withContext false
            val folderId = getOrCreateFolderId(context, drive) ?: return@withContext false
            val content  = FileContent(mime, localFile)
            val query    = "name='$name' and '$folderId' in parents and trashed=false"
            val existing = drive.files().list().setQ(query).setSpaces("drive")
                .setFields("files(id)").execute().files?.firstOrNull()
            if (existing != null) {
                drive.files().update(existing.id, DriveFile(), content).execute()
            } else {
                val meta = DriveFile().apply {
                    this.name    = name
                    parents      = listOf(folderId)
                }
                drive.files().create(meta, content).setFields("id").execute()
            }
            true
        } catch (e: Exception) {
            recordFailure("uploadNamedFile($name)", e)
            false
        }
    }

    // ── Settings backup ───────────────────────────────────────────────────────
    private suspend fun backupSettings(context: Context, drive: Drive, folderId: String) {
        try {
            val root = JSONObject()
            for (prefName in SETTINGS_PREF_NAMES) {
                val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                val obj   = JSONObject()
                prefs.all.forEach { (k, v) ->
                    when (v) {
                        is Boolean -> obj.put(k, v)
                        is Int     -> obj.put(k, v)
                        is Long    -> obj.put(k, v)
                        is Float   -> obj.put(k, v)
                        is String  -> obj.put(k, v)
                        is Set<*>  -> obj.put(k, v.joinToString(","))
                        else       -> obj.put(k, v.toString())
                    }
                }
                root.put(prefName, obj)
            }
            val file = JFile(context.cacheDir, SETTINGS_NAME)
            file.writeText(root.toString(2))
            uploadNamedFile(context, file, SETTINGS_NAME, "application/json")
            file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "backupSettings failed", e)
        }
    }

    // ── Public: full sync (settings) ─────────────────────────────────────────
    suspend fun syncNow(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val drive    = buildDriveService(context) ?: return@withContext false
            val folderId = getOrCreateFolderId(context, drive) ?: return@withContext false
            backupSettings(context, drive, folderId)
            true
        } catch (e: Exception) {
            Log.e(TAG, "syncNow failed", e)
            false
        }
    }

    // ── Public: upload diary JSON ─────────────────────────────────────────────
    suspend fun uploadDiaryJson(context: Context, file: JFile): Boolean =
        uploadNamedFile(context, file, DIARY_JSON_NAME, "application/json")

    // ── Public: upload diary PDF ──────────────────────────────────────────────
    suspend fun uploadDiaryPdf(context: Context, file: JFile): Boolean =
        uploadNamedFile(context, file, DIARY_PDF_NAME, "application/pdf")

    // ── Public: import settings from Drive ───────────────────────────────────
    suspend fun importSettings(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val drive    = buildDriveService(context) ?: return@withContext false
            val folderId = getOrCreateFolderId(context, drive) ?: return@withContext false
            val query    = "name='$SETTINGS_NAME' and '$folderId' in parents and trashed=false"
            val found    = drive.files().list().setQ(query).setSpaces("drive")
                .setFields("files(id)").execute().files?.firstOrNull()
                ?: return@withContext false
            val json = JSONObject(
                drive.files().get(found.id).executeMediaAsInputStream()
                    .bufferedReader().readText()
            )
            for (prefName in SETTINGS_PREF_NAMES) {
                if (!json.has(prefName)) continue
                val obj  = json.getJSONObject(prefName)
                val edit = context.getSharedPreferences(prefName, Context.MODE_PRIVATE).edit()
                obj.keys().forEach { key ->
                    when (val v = obj.get(key)) {
                        is Boolean -> edit.putBoolean(key, v)
                        is Int     -> edit.putInt(key, v)
                        is Long    -> edit.putLong(key, v)
                        is Float   -> edit.putFloat(key, v)
                        else       -> edit.putString(key, v.toString())
                    }
                }
                edit.apply()
            }
            true
        } catch (e: Exception) {
            recordFailure("importSettings", e)
            false
        }
    }

    // ── Public: download diary JSON string from Drive ─────────────────────────
    suspend fun downloadDiaryJson(context: Context): String? = withContext(Dispatchers.IO) {
        lastError = null
        lastRecoveryIntent = null
        try {
            val drive    = buildDriveService(context) ?: return@withContext null
            val folderId = getOrCreateFolderId(context, drive) ?: return@withContext null
            val query    = "name='$DIARY_JSON_NAME' and '$folderId' in parents and trashed=false"
            val found    = drive.files().list().setQ(query).setSpaces("drive")
                .setFields("files(id)").execute().files?.firstOrNull()
                ?: return@withContext null
            drive.files().get(found.id).executeMediaAsInputStream()
                .bufferedReader().readText()
        } catch (e: Exception) {
            recordFailure("downloadDiaryJson", e)
            null
        }
    }
    // ── Public: upload all media files (photos + voice notes) for diary entries ─
    // Creates a "RasFocus+/diary_media/" subfolder and uploads each file there.
    // Returns a map of localPath -> driveFileId so caller can update mediaPaths if needed.
    suspend fun uploadDiaryMedia(context: Context, mediaPaths: List<String>): Map<String, String> =
        withContext(Dispatchers.IO) {
            val result = mutableMapOf<String, String>()
            try {
                val drive    = buildDriveService(context) ?: return@withContext result
                val folderId = getOrCreateFolderId(context, drive) ?: return@withContext result

                // Get or create diary_media sub-folder
                val subFolderQuery = "name='diary_media' and '$folderId' in parents and mimeType='application/vnd.google-apps.folder' and trashed=false"
                val existing = drive.files().list().setQ(subFolderQuery).setSpaces("drive")
                    .setFields("files(id)").execute().files?.firstOrNull()
                val mediaFolderId = if (existing != null) {
                    existing.id
                } else {
                    val folderMeta = DriveFile().apply {
                        name = "diary_media"
                        mimeType = "application/vnd.google-apps.folder"
                        parents = listOf(folderId)
                    }
                    drive.files().create(folderMeta).setFields("id").execute().id
                }

                for (path in mediaPaths) {
                    val stripped = when {
                        path.startsWith("image:") -> path.removePrefix("image:")
                        path.startsWith("voice:") -> path.removePrefix("voice:")
                        else -> continue
                    }
                    val localFile = JFile(stripped)
                    if (!localFile.exists()) continue

                    // Check if already uploaded (same name)
                    val fileQuery = "name='${localFile.name}' and '$mediaFolderId' in parents and trashed=false"
                    val existingFile = drive.files().list().setQ(fileQuery).setSpaces("drive")
                        .setFields("files(id)").execute().files?.firstOrNull()

                    val driveFileId = if (existingFile != null) {
                        // Update existing
                        drive.files().update(existingFile.id, null, FileContent(null, localFile)).execute().id
                        existingFile.id
                    } else {
                        // Create new
                        val fileMeta = DriveFile().apply {
                            name = localFile.name
                            parents = listOf(mediaFolderId)
                        }
                        drive.files().create(fileMeta, FileContent(null, localFile)).setFields("id").execute().id
                    }
                    if (driveFileId != null) {
                        result[stripped] = driveFileId
                    }
                }
            } catch (e: Exception) {
                recordFailure("uploadDiaryMedia", e)
            }
            result
        }

    // ── Public: download diary media files from Drive back to local ──────────────
    // Downloads missing files to context.filesDir/diary_media/ based on their filenames.
    suspend fun downloadDiaryMedia(context: Context, fileNames: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            val downloaded = mutableListOf<String>()
            try {
                if (fileNames.isEmpty()) return@withContext downloaded
                val drive = buildDriveService(context) ?: return@withContext downloaded
                val folderId = getOrCreateFolderId(context, drive) ?: return@withContext downloaded
                
                // Get diary_media sub-folder ID
                val subFolderQuery = "name='diary_media' and '$folderId' in parents and mimeType='application/vnd.google-apps.folder' and trashed=false"
                val mediaFolderId = drive.files().list().setQ(subFolderQuery).setSpaces("drive")
                    .setFields("files(id)").execute().files?.firstOrNull()?.id ?: return@withContext downloaded

                val destDir = JFile(context.filesDir, "diary_media")
                destDir.mkdirs()
                
                // List all files in the Drive media folder to match against requested fileNames
                val filesQuery = "'$mediaFolderId' in parents and trashed=false"
                val driveFiles = drive.files().list().setQ(filesQuery).setSpaces("drive")
                    .setFields("files(id, name)").execute().files ?: emptyList()
                    
                val nameToId = driveFiles.associate { it.name to it.id }

                for (fileName in fileNames) {
                    val fileId = nameToId[fileName] ?: continue
                    try {
                        val destFile = JFile(destDir, fileName)
                        if (!destFile.exists()) {
                            destFile.outputStream().use { out ->
                                drive.files().get(fileId).executeMediaAndDownloadTo(out)
                            }
                        }
                        // Determine prefix
                        val prefix = if (fileName.endsWith(".m4a") || fileName.endsWith(".mp3") || fileName.endsWith(".ogg")) "voice" else "image"
                        downloaded.add("$prefix:${destFile.absolutePath}")
                    } catch (e: Exception) {
                        Log.e(TAG, "downloadDiaryMedia: failed for $fileName: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                recordFailure("downloadDiaryMedia", e)
            }
            downloaded
        }
}
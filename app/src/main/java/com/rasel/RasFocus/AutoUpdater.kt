package com.rasel.RasFocus

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val tagName: String,
    val publishedAt: String,
    val downloadUrl: String
)

class AutoUpdateWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        // Background 6-hourly check — update থাকলে notification পাঠাও
        AutoUpdater.checkForUpdates(applicationContext)
        return Result.success()
    }
}

object AutoUpdater {
    private const val GITHUB_OWNER = "raseledutools"
    private const val GITHUB_REPO  = "RasFocus-final"
    private const val TAG = "AutoUpdater"

    const val PREFS_NAME            = "AutoUpdaterPrefs"
    const val LAST_TAG_KEY          = "last_installed_tag"
    const val LAST_NOTIFIED_TAG_KEY = "last_notified_tag"
    const val NOTIFICATION_ID       = 554433
    const val NOTIF_UPDATE_AVAIL   = 554434
    const val CHANNEL_ID           = "update_channel"

    // GitHub Actions এ যে APK নাম দিয়ে publish হয়
    const val APK_UNIVERSAL = "app-full-universal-release.apk"
    const val APK_ARMEABI   = "app-full-armeabi-v7a-release.apk"

    // Intent extras — DownloadReceiver এ pass করা হয়
    const val EXTRA_APK_URL  = "extra_apk_url"
    const val EXTRA_TAG_NAME = "extra_tag_name"

    // ── Background periodic check setup ───────────────────────────────────
    fun setupBackgroundAutoUpdate(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val req = PeriodicWorkRequestBuilder<AutoUpdateWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "AutoUpdateCheck", ExistingPeriodicWorkPolicy.KEEP, req
        )
    }

    // ── GitHub API → latest release info ──────────────────────────────────
    suspend fun fetchLatestReleaseInfoSync(context: Context): ReleaseInfo? {
        return try {
            val apiUrl = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                connectTimeout = 8000
                readTimeout    = 8000
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null

            val json    = JSONObject(conn.inputStream.bufferedReader().readText())
            val tagName = json.getString("tag_name")

            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            parser.timeZone = TimeZone.getTimeZone("UTC")
            val date = parser.parse(json.getString("published_at"))
            val publishedAt = if (date != null)
                SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(date)
            else json.getString("published_at")

            // Device ABI অনুযায়ী সঠিক APK বেছে নাও
            val preferredApk = getPreferredApkName()
            val assets = json.getJSONArray("assets")
            var downloadUrl = ""
            var fallbackUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name  = asset.getString("name")
                if (name == preferredApk) downloadUrl = asset.getString("browser_download_url")
                if (name == APK_UNIVERSAL) fallbackUrl = asset.getString("browser_download_url")
            }
            if (downloadUrl.isEmpty()) downloadUrl = fallbackUrl
            if (downloadUrl.isEmpty()) return null

            ReleaseInfo(tagName, publishedAt, downloadUrl)
        } catch (e: Exception) {
            Log.e(TAG, "fetchLatestReleaseInfoSync failed", e)
            null
        }
    }

    fun fetchLatestReleaseInfo(context: Context, onResult: (ReleaseInfo?) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val info = fetchLatestReleaseInfoSync(context)
            withContext(Dispatchers.Main) { onResult(info) }
        }
    }

    // ── App launch / background check এ update detect → notification ──────
    fun checkForUpdates(context: Context, onUpdateAvailable: ((ReleaseInfo) -> Unit)? = null) {
        fetchLatestReleaseInfo(context) { info ->
            if (info == null) return@fetchLatestReleaseInfo
            val currentTag      = "v" + BuildConfig.VERSION_NAME
            val prefs           = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastNotifiedTag = prefs.getString(LAST_NOTIFIED_TAG_KEY, "") ?: ""

            if (info.tagName != currentTag && info.tagName != lastNotifiedTag) {
                if (onUpdateAvailable != null) {
                    onUpdateAvailable(info)
                } else {
                    showUpdateNotification(context, info)
                }
            }
        }
    }

    // ── Notification: tap করলেই download শুরু (Rajib Tutorial style) ──────
    fun showUpdateNotification(context: Context, info: ReleaseInfo) {
        ensureChannel(context)

        val prefs           = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastNotifiedTag = prefs.getString(LAST_NOTIFIED_TAG_KEY, "") ?: ""
        if (info.tagName == lastNotifiedTag) return   // এই version এর notification আগেই গেছে

        // Tap → DownloadReceiver → DownloadManager APK নামাবে → install prompt
        val downloadIntent = Intent(context, UpdateDownloadReceiver::class.java).apply {
            putExtra(EXTRA_APK_URL,  info.downloadUrl)
            putExtra(EXTRA_TAG_NAME, info.tagName)
        }
        val pi = PendingIntent.getBroadcast(
            context, 0, downloadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("🆕 RasFocus আপডেট পাওয়া গেছে! (${info.tagName})")
            .setContentText("ট্যাপ করুন — ডাউনলোড করে ইনস্টল হবে।")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("নতুন ভার্সন ${info.tagName} পাওয়া গেছে।\nট্যাপ করলে ডাউনলোড শুরু হবে এবং ইনস্টল হয়ে যাবে।"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(android.R.drawable.stat_sys_download, "এখনই আপডেট করুন", pi)
            .build()
        nm.notify(NOTIF_UPDATE_AVAIL, notif)

        prefs.edit().putString(LAST_NOTIFIED_TAG_KEY, info.tagName).apply()
    }

    // Legacy alias — পুরনো call sites এর জন্য
    fun showUpdateAvailableNotification(context: Context, info: ReleaseInfo) =
        showUpdateNotification(context, info)

    // ── BroadcastReceiver: notification tap → DownloadManager দিয়ে APK নামায় ──
    class UpdateDownloadReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val apkUrl   = intent.getStringExtra(EXTRA_APK_URL)  ?: return
            val tagName  = intent.getStringExtra(EXTRA_TAG_NAME) ?: "update"
            val fileName = "RasFocus-$tagName.apk"

            val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
                setTitle("RasFocus $tagName")
                setDescription("আপডেট ডাউনলোড হচ্ছে…")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setMimeType("application/vnd.android.package-archive")
                setAllowedNetworkTypes(
                    DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
                )
            }
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)

            // Download complete হলে install prompt দেখাবে
            val receiver = InstallOnCompleteReceiver(fileName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(
                    receiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            }
        }
    }

    // ── BroadcastReceiver: download complete → install trigger ────────────
    class InstallOnCompleteReceiver(private val fileName: String) : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val apkFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName
            )
            if (!apkFile.exists()) return

            val apkUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apkFile)
                } catch (e: Exception) {
                    Log.e("AutoUpdater", "FileProvider failed, using direct URI", e)
                    Uri.fromFile(apkFile)
                }
            } else {
                Uri.fromFile(apkFile)
            }

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { ctx.startActivity(installIntent) } catch (e: Exception) {
                Log.e("AutoUpdater", "Install launch failed", e)
            }
        }
    }

    // ── DownloadManager download (manual / in-app use) ────────────────────
    fun downloadWithProgress(context: Context, info: ReleaseInfo, onDownloadId: (Long) -> Unit) {
        ensureChannel(context)
        val apkName = "RasFocus_${info.tagName}.apk"
        val request = DownloadManager.Request(Uri.parse(info.downloadUrl)).apply {
            setTitle("RasFocus Update ${info.tagName}")
            setDescription("Downloading...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, apkName)
            setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
            )
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        getDownloadedFile(context, info.tagName)?.delete()   // পুরনো file মুছে দাও
        val downloadId = dm.enqueue(request)
        onDownloadId(downloadId)
    }

    fun getDownloadedFile(context: Context, tag: String): File? {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        val f   = File(dir, "RasFocus_$tag.apk")
        return if (f.exists() && f.length() > 0) f else null
    }

    fun queryProgress(context: Context, downloadId: Long): Pair<Int, Int> {
        val dm     = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query  = DownloadManager.Query().setFilterById(downloadId)
        val cursor: Cursor = dm.query(query)
        if (!cursor.moveToFirst()) { cursor.close(); return Pair(0, DownloadManager.STATUS_FAILED) }
        val total      = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
        val status     = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        cursor.close()
        val percent = if (total > 0) ((downloaded * 100) / total).toInt() else 0
        return Pair(percent, status)
    }

    fun installApk(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "installApk failed", e)
        }
    }

    fun saveTag(context: Context, tag: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(LAST_TAG_KEY, tag).apply()
    }

    // Legacy aliases
    fun installDownloadedUpdate(context: Context, file: File) = installApk(context, file)

    fun silentDownloadUpdate(context: Context, apkName: String, tag: String) {
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            val info = fetchLatestReleaseInfoSync(context) ?: return@launch
            downloadWithProgress(context, info) {}
        }
    }

    fun getDownloadedUpdateFile(context: Context, tag: String): File? =
        getDownloadedFile(context, tag)

    private fun getPreferredApkName(): String {
        val abis = Build.SUPPORTED_ABIS
        return if (abis.any { it.contains("armeabi-v7a") && !it.contains("arm64") })
            APK_ARMEABI else APK_UNIVERSAL
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "App Updates", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "RasFocus update notifications" }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }
}

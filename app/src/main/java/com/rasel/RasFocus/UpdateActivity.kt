package com.rasel.RasFocus

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Update notification tap করলে এই screen খোলে:
 * app এর ভেতরেই APK download (progress সহ) → শেষ হলে সরাসরি install popup।
 * বাইরের Downloads/browser/DownloadManager ব্যবহার হয় না।
 */
class UpdateActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var titleTv: TextView
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var action: Button
    private var apk: File? = null
    private var job: Job? = null
    private var waitingForPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tag = intent.getStringExtra(AutoUpdater.EXTRA_TAG_NAME) ?: "update"
        val fallbackUrl = intent.getStringExtra(AutoUpdater.EXTRA_APK_URL)

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(AutoUpdater.NOTIF_UPDATE_AVAIL)

        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((28 * dp).toInt(), 0, (28 * dp).toInt(), 0)
            setBackgroundColor(0xFF101820.toInt())
        }
        titleTv = TextView(this).apply {
            text = "RasFocus $tag"
            textSize = 22f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "আপডেট ডাউনলোড শুরু হচ্ছে…"
            textSize = 15f; setTextColor(0xFFB0BEC5.toInt()); gravity = Gravity.CENTER
            setPadding(0, (14 * dp).toInt(), 0, (14 * dp).toInt())
        }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = true
        }
        action = Button(this).apply {
            visibility = android.view.View.GONE
        }
        root.addView(titleTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (20 * dp).toInt() })
        setContentView(root)

        startDownload(tag, fallbackUrl)
    }

    private fun startDownload(tag: String, fallbackUrl: String?) {
        job?.cancel()
        action.visibility = android.view.View.GONE
        bar.isIndeterminate = true
        status.text = "আপডেট ডাউনলোড শুরু হচ্ছে…"
        job = scope.launch {
            try {
                // signed CDN link তাড়াতাড়ি expire হয় — তাই tap এর সময় নতুন করে link নিই
                val fresh = withContext(Dispatchers.IO) { AutoUpdater.fetchLatestReleaseInfoSync(this@UpdateActivity) }
                val url = fresh?.downloadUrl ?: fallbackUrl ?: throw IllegalStateException("download link নেই")
                val realTag = fresh?.tagName ?: tag
                val dir = File(cacheDir, "update").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, "RasFocus-$realTag.apk")
                withContext(Dispatchers.IO) { download(url, out) }
                apk = out
                installNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                bar.isIndeterminate = false
                status.text = "ডাউনলোড হয়নি: ${e.message ?: "network সমস্যা"}"
                action.text = "আবার চেষ্টা করুন"
                action.visibility = android.view.View.VISIBLE
                action.setOnClickListener { startDownload(tag, fallbackUrl) }
            }
        }
    }

    private suspend fun download(url: String, out: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15000; readTimeout = 30000
        }
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            val tmp = File(out.parentFile, out.name + ".part")
            conn.inputStream.use { input ->
                tmp.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L; var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n); done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                withContext(Dispatchers.Main) {
                                    bar.isIndeterminate = false; bar.progress = pct
                                    status.text = "ডাউনলোড হচ্ছে… $pct%  (${done / 1048576} / ${total / 1048576} MB)"
                                }
                            }
                        }
                        yield()
                    }
                }
            }
            if (!tmp.renameTo(out)) throw IllegalStateException("file save হয়নি")
        } finally { conn.disconnect() }
    }

    private fun installNow() {
        val f = apk ?: return
        bar.isIndeterminate = false; bar.progress = 100
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            waitingForPermission = true
            status.text = "ইনস্টল করতে \"এই app থেকে install করার অনুমতি\" দিন, তারপর ফিরে আসুন।"
            action.text = "অনুমতি দিন"
            action.visibility = android.view.View.VISIBLE
            action.setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            }
            return
        }
        waitingForPermission = false
        status.text = "ডাউনলোড শেষ — ইনস্টল পপআপ দেখুন।"
        action.text = "ইনস্টল করুন"
        action.visibility = android.view.View.VISIBLE
        action.setOnClickListener { installNow() }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            status.text = "Install চালু করা যায়নি: ${e.message}"
        }
    }

    override fun onResume() {
        super.onResume()
        // অনুমতি দিয়ে ফিরলে নিজে থেকেই install popup
        if (waitingForPermission && apk != null &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls())) installNow()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

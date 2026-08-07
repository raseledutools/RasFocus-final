package com.rasel.RasFocus.selfcontrol.study_tools

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import com.rasel.RasFocus.filemanager.FMPdfViewerActivity

// ─────────────────────────────────────────────────────────────────────────────
// UniversalViewerActivity — pure synchronous dispatcher, NO Compose/setContent
//
// Single "Open with RasFocus" entry point for ALL file types:
//   PDF             → FMPdfViewerActivity
//   DOCX / DOC      → DocxViewerActivity
//   PPTX / PPT      → PptxViewerActivity
//   XLSX / XLS      → XlsxViewerActivity
//   Images          → ImageViewerActivity
//   TXT / MD / code → TextViewerActivity
//   Unknown         → FMPdfViewerActivity
//
// WHY NO COMPOSE:
//   The previous setContent{} + LaunchedEffect + finish() pattern created a
//   race condition. Compose recomposition and finish() ran concurrently; on
//   slow devices or during task-stack re-entry (singleTop), the URI permission
//   grant expired before FMPdfViewerActivity could open the file descriptor,
//   causing intermittent "Failed to open PDF" errors from third-party file
//   managers. Synchronous dispatch in onCreate/onNewIntent eliminates the race.
// ─────────────────────────────────────────────────────────────────────────────

class UniversalViewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        dispatchIntent(intent)
    }

    // singleTop re-entry — a second "Open with" from a file manager while
    // this activity is already in the back-stack
    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        dispatchIntent(newIntent)
    }

    // ── Synchronous dispatch: URI → MIME → target Activity → finish() ─────────
    private fun dispatchIntent(src: Intent) {
        val uri: Uri? = src.data
            ?: @Suppress("DEPRECATION") src.getParcelableExtra(Intent.EXTRA_STREAM)

        // Take persistable grant up-front so the URI stays valid even after
        // this activity finishes and is garbage-collected.
        if (uri != null && uri.scheme == "content") {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Source didn't offer a persistable grant — the inline
                // FLAG_GRANT_READ_URI_PERMISSION on the forwarded intent
                // will carry the grant to the child activity directly.
            } catch (_: Exception) { /* ignore */ }
        }

        if (uri == null) {
            Toast.makeText(this, "ফাইল পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val fileName = getFileName(uri)
        val mimeType = src.type
            ?: uri.let { contentResolver.getType(it) }
            ?: run {
                val ext = fileName.substringAfterLast('.', "").lowercase()
                android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: ""
            }

        val targetCls: Class<*> = when (detectType(fileName, mimeType)) {
            FileType.PDF     -> FMPdfViewerActivity::class.java
            FileType.DOCX    -> DocxViewerActivity::class.java
            FileType.PPTX    -> PptxViewerActivity::class.java
            FileType.XLSX    -> XlsxViewerActivity::class.java
            FileType.IMAGE   -> ImageViewerActivity::class.java
            FileType.TEXT    -> TextViewerActivity::class.java
            FileType.UNKNOWN -> FMPdfViewerActivity::class.java
        }

        val effectiveMime = mimeType.ifEmpty {
            when (targetCls) {
                FMPdfViewerActivity::class.java  -> "application/pdf"
                DocxViewerActivity::class.java   ->
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                PptxViewerActivity::class.java   ->
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                XlsxViewerActivity::class.java   ->
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                ImageViewerActivity::class.java  -> "image/*"
                TextViewerActivity::class.java   -> "text/plain"
                else                             -> "application/pdf"
            }
        }

        try {
            startActivity(Intent(this, targetCls).apply {
                action = Intent.ACTION_VIEW
                setDataAndType(uri, effectiveMime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // ClipData required on Android 12+ to transfer the URI grant
                // when this activity finishes before the child reads the fd.
                if (uri.scheme == "content") {
                    clipData = ClipData.newRawUri("", uri)
                }
                // NO FLAG_ACTIVITY_NEW_TASK — keeps viewer in the same task so
                // back-press returns to the file manager, not the launcher.
            })
        } catch (e: Exception) {
            Toast.makeText(this, "খোলা যায়নি: ${e.message}", Toast.LENGTH_SHORT).show()
        }

        // No animation — user should see the viewer appear instantly.
        // overridePendingTransition BEFORE finish() so the OS uses it.
        overridePendingTransition(0, 0)
        finish()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun getFileName(uri: Uri): String {
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) return c.getString(idx) ?: ""
                    }
                }
            } catch (_: Exception) {}
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: ""
    }

    private enum class FileType { PDF, DOCX, PPTX, XLSX, IMAGE, TEXT, UNKNOWN }

    private fun detectType(fileName: String, mimeType: String): FileType {
        val ext  = fileName.substringAfterLast('.', "").lowercase()
        val mime = mimeType.lowercase()
        return when {
            ext == "pdf"  || mime == "application/pdf"         -> FileType.PDF
            ext in setOf("docx", "doc")
                || mime.contains("wordprocessingml")
                || mime == "application/msword"                -> FileType.DOCX
            ext in setOf("pptx", "ppt")
                || mime.contains("presentationml")
                || mime == "application/vnd.ms-powerpoint"    -> FileType.PPTX
            ext in setOf("xlsx", "xls")
                || mime.contains("spreadsheetml")
                || mime == "application/vnd.ms-excel"         -> FileType.XLSX
            ext in setOf("jpg","jpeg","png","gif","webp",
                         "bmp","heic","heif")
                || mime.startsWith("image/")                   -> FileType.IMAGE
            ext in setOf("txt","md","markdown","kt","java","py",
                         "js","ts","html","css","xml","json",
                         "yaml","yml","csv","sh","bat","c",
                         "cpp","h","rs","go","rb")
                || mime.startsWith("text/")                    -> FileType.TEXT
            else                                               -> FileType.UNKNOWN
        }
    }
}

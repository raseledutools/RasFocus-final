package com.rasel.RasFocus.selfcontrol.study_tools

import android.content.Intent

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// ─────────────────────────────────────────────────────────────────────────────
// UniversalViewerActivity
//
// Single "Open with RasFocus" entry point for ALL file types:
//   PDF, DOCX, PPTX, XLSX, XLS, JPG, PNG, WEBP, GIF, TXT, MD, and more.
//
// Strategy:
//   • PDF             → PdfViewerActivity (direct)
//   • DOCX / DOC      → DocxViewerActivity (converts to PDF internally)
//   • PPTX / PPT      → PptxViewerActivity (converts to PDF internally)
//   • XLSX / XLS      → XlsxViewerActivity (converts to PDF internally)
//   • Images          → wrap in a 1-page PDF → PdfViewerActivity
//   • TXT / MD / code → TextViewerActivity
//   • Unknown         → try PdfViewerActivity, fall back to TextViewerActivity
//
// Having ONE activity declared in the manifest means Android shows
// "RasFocus" exactly once in the "Open with" picker regardless of file type.
// ─────────────────────────────────────────────────────────────────────────────

class UniversalViewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Dispatch the launching intent through the shared handler.
        // If this is a cold start the intent comes via onCreate; if the
        // activity is already in the back-stack (singleTop) a subsequent
        // "Open with" arrives in onNewIntent — both paths call dispatchIntent.
        dispatchIntent(intent)
    }

    // ── singleTop re-entry: external file manager sends a new ACTION_VIEW ──
    // Without this override the old intent (and its URI) stays active and
    // the viewer either crashes or silently opens the wrong / null file.
    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)        // keep getIntent() in sync for any later code
        dispatchIntent(newIntent)
    }

    // ── Core dispatch: resolve URI → MIME → FileType → target Activity ──
    private fun dispatchIntent(src: Intent) {
        val uri: Uri? = src.data
            ?: src.getParcelableExtra(Intent.EXTRA_STREAM)

        if (uri != null && uri.scheme == "content") {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
        }

        val fileName = getFileName(uri)
        val mimeType = src.type
            ?: uri?.let { contentResolver.getType(it) }
            ?: run {
                val ext = fileName.substringAfterLast('.', "").lowercase()
                android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: ""
            }

        val fileType = detectType(fileName, mimeType)

        setContent {
            val status = remember { mutableStateOf("") }

            LaunchedEffect(uri) {
                if (uri == null) {
                    status.value = "ফাইল পাওয়া যায়নি"
                    return@LaunchedEffect
                }
                when (fileType) {
                    FileType.PDF -> {
                        openByClassName("${packageName.replace(".combo","")}.selfcontrol.study_tools.PdfViewerActivity", uri, "application/pdf")
                    }
                    FileType.DOCX -> {
                        openDirect(DocxViewerActivity::class.java, uri, mimeType.ifEmpty {
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                        })
                    }
                    FileType.PPTX -> {
                        openDirect(PptxViewerActivity::class.java, uri, mimeType.ifEmpty {
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                        })
                    }
                    FileType.XLSX -> {
                        openDirect(XlsxViewerActivity::class.java, uri, mimeType.ifEmpty {
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        })
                    }
                    FileType.IMAGE -> {
                        openDirect(ImageViewerActivity::class.java, uri,
                            mimeType.ifEmpty { "image/*" })
                    }
                    FileType.TEXT -> {
                        openDirect(TextViewerActivity::class.java, uri, mimeType.ifEmpty { "text/plain" })
                    }
                    FileType.UNKNOWN -> {
                        openByClassName("${packageName.replace(".combo","")}.selfcontrol.study_tools.PdfViewerActivity", uri, "application/pdf")
                    }
                }
            }

            MaterialTheme {
                Box(
                    Modifier.fillMaxSize().background(ComposeColor(0xFF111111)),
                    contentAlignment = Alignment.Center
                ) {
                    if (status.value.isNotEmpty()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)) {
                            Text("⚠️", fontSize = 36.sp)
                            Spacer(Modifier.height(12.dp))
                            Text(status.value, color = ComposeColor(0xFFFF5C5C), fontSize = 13.sp)
                            Spacer(Modifier.height(20.dp))
                            Button(onClick = { finish() }) { Text("← ফিরে যান") }
                        }
                    } else {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    private fun openDirect(cls: Class<*>, uri: Uri, mimeType: String) {
        try {
            startActivity(Intent(this, cls).apply {
                action = Intent.ACTION_VIEW
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                // Forward the original URI grant so the target Activity
                // can open it even after process death/recreation
                if (uri.scheme == "content") {
                    clipData = android.content.ClipData.newRawUri("", uri)
                }
            })
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                this,
                "Cannot open: ${e.message}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        finish()
    }

    // Use class name string so PdfViewerActivity (flavor-only) resolves at runtime
    private fun openByClassName(className: String, uri: Uri, mimeType: String) {
        try {
            val cls = Class.forName(className)
            openDirect(cls, uri, mimeType)
        } catch (e: ClassNotFoundException) {
            android.widget.Toast.makeText(this, "Viewer not available in this version.", android.widget.Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun getFileName(uri: Uri?): String {
        if (uri == null) return ""
        var name: String? = null
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) name = c.getString(idx)
                    }
                }
            } catch (e: Exception) {
                // Ignore query exceptions
            }
        }
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: ""
    }

    // ── File type detection ────────────────────────────────────────────────
    private enum class FileType { PDF, DOCX, PPTX, XLSX, IMAGE, TEXT, UNKNOWN }

    private fun detectType(fileName: String, mimeType: String): FileType {
        val ext  = fileName.substringAfterLast('.', "").lowercase()
        val mime = mimeType.lowercase()
        return when {
            ext == "pdf"  || mime == "application/pdf" -> FileType.PDF
            ext in setOf("docx", "doc") ||
                mime.contains("wordprocessingml") ||
                mime == "application/msword" -> FileType.DOCX
            ext in setOf("pptx", "ppt") ||
                mime.contains("presentationml") ||
                mime == "application/vnd.ms-powerpoint" -> FileType.PPTX
            ext in setOf("xlsx", "xls") ||
                mime.contains("spreadsheetml") ||
                mime == "application/vnd.ms-excel" -> FileType.XLSX
            ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif") ||
                mime.startsWith("image/") -> FileType.IMAGE
            ext in setOf("txt", "md", "markdown", "kt", "java", "py", "js",
                "ts", "html", "css", "xml", "json", "yaml", "yml", "csv",
                "sh", "bat", "c", "cpp", "h", "rs", "go", "rb") ||
                mime.startsWith("text/") -> FileType.TEXT
            else -> FileType.UNKNOWN
        }
    }

}

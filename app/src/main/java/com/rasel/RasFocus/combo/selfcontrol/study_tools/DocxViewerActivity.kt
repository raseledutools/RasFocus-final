package com.rasel.RasFocus.combo.selfcontrol.study_tools

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.zip.ZipInputStream

// ─────────────────────────────────────────────────────────────────────────────
// DocxViewerActivity
// Opens .docx files by extracting paragraph text from the internal XML.
// No external library — DOCX is just a ZIP of XML files.
// ─────────────────────────────────────────────────────────────────────────────

class DocxViewerActivity : ComponentActivity() {

    private val uriState      = mutableStateOf<Uri?>(null)
    private val fileNameState = mutableStateOf("Document")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        loadFromIntent(intent)
        setContent {
            MaterialTheme {
                DocxViewerScreen(
                    uri      = uriState.value,
                    fileName = fileNameState.value,
                    onClose  = { finish() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); loadFromIntent(intent)
    }

    private fun loadFromIntent(intent: Intent?) {
        val uri: Uri? = when {
            intent?.action == Intent.ACTION_VIEW && intent.data != null -> intent.data
            else -> null
        }
        if (uri != null && uri.scheme == "content") {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) {}
        }
        uriState.value = uri
        fileNameState.value = uri?.let { u ->
            var nm: String? = null
            if (u.scheme == "content") contentResolver.query(u, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) { val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (i >= 0) nm = c.getString(i) }
            }
            nm ?: u.lastPathSegment?.substringAfterLast('/') ?: "Document"
        } ?: "Document"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// COLORS
// ─────────────────────────────────────────────────────────────────────────────
private val DOC_BG      = Color(0xFFFAFAFA)
private val DOC_TEXT    = Color(0xFF1A1A1A)
private val DOC_MUTED   = Color(0xFF666666)
private val DOC_TOPBAR  = Color(0xFF1E3A5F)
private val DOC_ACCENT  = Color(0xFF2B6CB0)

// ─────────────────────────────────────────────────────────────────────────────
// MAIN SCREEN
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun DocxViewerScreen(uri: Uri?, fileName: String, onClose: () -> Unit) {
    val context   = LocalContext.current

    data class DocBlock(val text: String, val isBold: Boolean, val isHeading: Boolean, val indent: Int)

    var blocks    by remember { mutableStateOf<List<DocBlock>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg  by remember { mutableStateOf("") }

    LaunchedEffect(uri) {
        if (uri == null) { isLoading = false; errorMsg = "ফাইল পাওয়া যায়নি"; return@LaunchedEffect }
        withContext(Dispatchers.IO) {
            try {
                val result = mutableListOf<DocBlock>()
                val stream: InputStream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("ফাইল খুলতে পারিনি")

                ZipInputStream(stream.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name == "word/document.xml") {
                            val factory = XmlPullParserFactory.newInstance()
                            val parser  = factory.newPullParser()
                            parser.setInput(zip, "UTF-8")

                            var inParagraph = false
                            var inRun       = false
                            var isBold      = false
                            var indentLevel = 0
                            val paraText    = StringBuilder()
                            var hasStyle    = false
                            var isHeadingStyle = false

                            var eventType = parser.eventType
                            while (eventType != XmlPullParser.END_DOCUMENT) {
                                val tagName = parser.name ?: ""
                                when (eventType) {
                                    XmlPullParser.START_TAG -> when (tagName) {
                                        "w:p"  -> { inParagraph = true; paraText.clear(); isBold = false; hasStyle = false; isHeadingStyle = false; indentLevel = 0 }
                                        "w:r"  -> inRun = true
                                        "w:b"  -> if (inRun) isBold = true
                                        "w:pStyle" -> {
                                            val styleId = parser.getAttributeValue(null, "w:val") ?: ""
                                            isHeadingStyle = styleId.startsWith("Heading") || styleId.startsWith("heading")
                                            hasStyle = true
                                        }
                                        "w:ind" -> {
                                            val left = parser.getAttributeValue(null, "w:left")?.toIntOrNull() ?: 0
                                            indentLevel = (left / 720).coerceIn(0, 6)
                                        }
                                    }
                                    XmlPullParser.END_TAG -> when (tagName) {
                                        "w:p"  -> {
                                            val t = paraText.toString().trim()
                                            if (t.isNotEmpty()) result.add(DocBlock(t, isBold, isHeadingStyle, indentLevel))
                                            inParagraph = false; inRun = false
                                        }
                                        "w:r"  -> inRun = false
                                    }
                                    XmlPullParser.TEXT -> {
                                        if (inParagraph) paraText.append(parser.text)
                                    }
                                }
                                eventType = parser.next()
                            }
                            break
                        }
                        entry = zip.nextEntry
                    }
                }

                withContext(Dispatchers.Main) {
                    blocks    = result
                    isLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoading = false
                    errorMsg  = "DOCX পড়তে পারিনি: ${e.message}"
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(DOC_BG)) {

        // ── Top Bar ────────────────────────────────────────────────────────
        Surface(color = DOC_TOPBAR, shadowElevation = 4.dp) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding()
                    .height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(fileName, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Word Document · ${blocks.size} paragraphs",
                        fontSize = 11.sp, color = Color.White.copy(alpha = 0.65f))
                }
                // Open in external app fallback
                uri?.let { u ->
                    IconButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(u, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try { context.startActivity(Intent.createChooser(intent, "অন্য app দিয়ে খুলুন")) }
                        catch (_: Exception) {}
                    }) {
                        Icon(Icons.Default.OpenInNew, "Open externally", tint = Color.White.copy(0.8f))
                    }
                }
            }
        }

        // ── Content ────────────────────────────────────────────────────────
        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = DOC_ACCENT, strokeWidth = 2.5.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("Document পড়া হচ্ছে…", color = DOC_MUTED, fontSize = 13.sp)
                }
            }
            errorMsg.isNotEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)) {
                    Text("📄", fontSize = 48.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(errorMsg, color = Color(0xFFCC3333), fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    uri?.let { u ->
                        OutlinedButton(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(u, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            try { context.startActivity(Intent.createChooser(intent, "অন্য app দিয়ে খুলুন")) }
                            catch (_: Exception) {}
                        }) { Text("অন্য app দিয়ে খুলুন") }
                    }
                }
            }
            blocks.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Document খালি", color = DOC_MUTED, fontSize = 14.sp)
            }
            else -> SelectionContainer {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .navigationBarsPadding()
                ) {
                    blocks.forEach { block ->
                        val startPad = (block.indent * 16).dp
                        Spacer(Modifier.height(if (block.isHeading) 12.dp else 4.dp))
                        Text(
                            text  = block.text,
                            style = TextStyle(
                                fontSize   = if (block.isHeading) 17.sp else 15.sp,
                                fontWeight = if (block.isHeading || block.isBold) FontWeight.Bold else FontWeight.Normal,
                                lineHeight = if (block.isHeading) 24.sp else 23.sp,
                                color      = if (block.isHeading) DOC_ACCENT else DOC_TEXT
                            ),
                            modifier = Modifier.padding(start = startPad)
                        )
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
}

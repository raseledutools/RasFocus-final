package com.rasel.RasFocus.combo.selfcontrol.study_tools

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
// PptxViewerActivity
// Opens .pptx files by parsing the slide XML inside the ZIP.
// Each slide becomes a card with its title and bullet text.
// ─────────────────────────────────────────────────────────────────────────────

class PptxViewerActivity : ComponentActivity() {

    private val uriState      = mutableStateOf<Uri?>(null)
    private val fileNameState = mutableStateOf("Presentation")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        loadFromIntent(intent)
        setContent {
            MaterialTheme {
                PptxViewerScreen(
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
            nm ?: u.lastPathSegment?.substringAfterLast('/') ?: "Presentation"
        } ?: "Presentation"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// COLORS — dark presentation theme
// ─────────────────────────────────────────────────────────────────────────────
private val PPT_BG       = Color(0xFF1A1A2E)
private val PPT_CARD     = Color(0xFF16213E)
private val PPT_TOPBAR   = Color(0xFF0F3460)
private val PPT_TITLE    = Color(0xFFE94560)
private val PPT_BODY     = Color(0xFFE0E0E0)
private val PPT_SLIDE_NO = Color(0xFF888888)
private val PPT_ACCENT   = Color(0xFF533483)

data class PptSlide(val index: Int, val title: String, val bullets: List<String>)

// ─────────────────────────────────────────────────────────────────────────────
// MAIN SCREEN
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun PptxViewerScreen(uri: Uri?, fileName: String, onClose: () -> Unit) {
    val context = LocalContext.current

    var slides    by remember { mutableStateOf<List<PptSlide>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg  by remember { mutableStateOf("") }

    LaunchedEffect(uri) {
        if (uri == null) { isLoading = false; errorMsg = "ফাইল পাওয়া যায়নি"; return@LaunchedEffect }
        withContext(Dispatchers.IO) {
            try {
                val result = mutableListOf<PptSlide>()
                val stream: InputStream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("ফাইল খুলতে পারিনি")

                // Collect all slide XML entries, sort by slide number
                val slideEntries = mutableMapOf<Int, String>() // slideNum -> xmlContent
                val regex = Regex("ppt/slides/slide(\\d+)\\.xml")

                ZipInputStream(stream.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val match = regex.matchEntire(entry.name)
                        if (match != null) {
                            val num = match.groupValues[1].toInt()
                            slideEntries[num] = zip.bufferedReader(Charsets.UTF_8).readText()
                        }
                        entry = zip.nextEntry
                    }
                }

                // Parse each slide
                val factory = XmlPullParserFactory.newInstance()
                slideEntries.entries.sortedBy { it.key }.forEach { (num, xml) ->
                    val parser = factory.newPullParser()
                    parser.setInput(xml.reader())

                    val texts   = mutableListOf<String>()
                    val current = StringBuilder()
                    var inTxBody = false

                    var eventType = parser.eventType
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        val tag = parser.name ?: ""
                        when (eventType) {
                            XmlPullParser.START_TAG -> when (tag) {
                                "a:txBody" -> inTxBody = true
                                "a:p"      -> current.clear()
                            }
                            XmlPullParser.END_TAG -> when (tag) {
                                "a:txBody" -> inTxBody = false
                                "a:p"      -> {
                                    val t = current.toString().trim()
                                    if (t.isNotEmpty()) texts.add(t)
                                }
                            }
                            XmlPullParser.TEXT -> if (inTxBody) current.append(parser.text)
                        }
                        eventType = parser.next()
                    }

                    val title   = texts.firstOrNull() ?: "Slide $num"
                    val bullets = if (texts.size > 1) texts.drop(1) else emptyList()
                    result.add(PptSlide(num, title, bullets))
                }

                withContext(Dispatchers.Main) {
                    slides    = result
                    isLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoading = false
                    errorMsg  = "PPTX পড়তে পারিনি: ${e.message}"
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(PPT_BG)) {

        // ── Top Bar ────────────────────────────────────────────────────────
        Surface(color = PPT_TOPBAR, shadowElevation = 4.dp) {
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
                    Text("Presentation · ${slides.size} slides",
                        fontSize = 11.sp, color = Color.White.copy(0.6f))
                }
                uri?.let { u ->
                    IconButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(u, "application/vnd.openxmlformats-officedocument.presentationml.presentation")
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

        // ── Slide List ─────────────────────────────────────────────────────
        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = PPT_TITLE, strokeWidth = 2.5.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("Slides পড়া হচ্ছে…", color = PPT_SLIDE_NO, fontSize = 13.sp)
                }
            }
            errorMsg.isNotEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Text("📊", fontSize = 48.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(errorMsg, color = Color(0xFFFF5C5C), fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    uri?.let { u ->
                        OutlinedButton(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(u, "application/vnd.openxmlformats-officedocument.presentationml.presentation")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            try { context.startActivity(Intent.createChooser(intent, "অন্য app দিয়ে খুলুন")) }
                            catch (_: Exception) {}
                        }) { Text("অন্য app দিয়ে খুলুন", color = Color.White) }
                    }
                }
            }
            slides.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Slide পাওয়া যায়নি", color = PPT_SLIDE_NO, fontSize = 14.sp)
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(slides) { _, slide ->
                    SlideCard(slide)
                }
                item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
            }
        }
    }
}

@Composable
private fun SlideCard(slide: PptSlide) {
    Surface(
        modifier      = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
        color         = PPT_CARD,
        shadowElevation = 2.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            // Slide number badge
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = PPT_ACCENT,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text     = "${slide.index}",
                        color    = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("SLIDE", fontSize = 10.sp, color = PPT_SLIDE_NO, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(10.dp))

            // Title
            Text(
                text       = slide.title,
                style      = TextStyle(
                    fontSize   = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color      = PPT_TITLE,
                    lineHeight = 24.sp
                )
            )

            // Bullets
            if (slide.bullets.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = Color.White.copy(0.08f), thickness = 1.dp)
                Spacer(Modifier.height(10.dp))
                slide.bullets.forEach { bullet ->
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text("▸  ", color = PPT_ACCENT, fontSize = 13.sp)
                        Text(
                            text  = bullet,
                            style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, color = PPT_BODY)
                        )
                    }
                }
            }
        }
    }
}

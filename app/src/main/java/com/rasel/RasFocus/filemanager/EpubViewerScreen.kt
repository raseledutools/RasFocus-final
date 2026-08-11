package com.rasel.RasFocus.filemanager

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

// ─────────────────────────────────────────────────────────────────────────────
// EPUB Viewer
//
// Strategy:
//   • EPUB is a ZIP; unzip to a temp folder in cacheDir so WebView can load
//     images and CSS from file:// URLs without CORS issues.
//   • Parse OPF manifest → find spine order → concatenate XHTML chapters.
//   • Inject a clean reading stylesheet so even unstyled EPUBs look good.
//   • Support tap-to-toggle-header (same UX as PDF viewer).
//   • Chapter list via bottom sheet (optional).
// ─────────────────────────────────────────────────────────────────────────────

private val EPUB_BG      = Color(0xFF1A1A2E)
private val EPUB_HEADER  = Color(0xFF111122)
private val EPUB_ACCENT  = Color(0xFF6C63FF)

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubViewerScreen(path: String, onBack: () -> Unit) {
    val file = File(path)

    // ── state ─────────────────────────────────────────────────────────────────
    var extractDir  by remember { mutableStateOf<File?>(null) }
    var opfRoot     by remember { mutableStateOf("") }
    var spineUrls   by remember { mutableStateOf<List<String>>(emptyList()) }
    var chapters    by remember { mutableStateOf<List<Pair<String,String>>>(emptyList()) }   // label → url
    var currentIdx  by remember { mutableStateOf(0) }
    var isLoading   by remember { mutableStateOf(true) }
    var errorMsg    by remember { mutableStateOf<String?>(null) }
    var headerVisible by remember { mutableStateOf(true) }
    var showChapters  by remember { mutableStateOf(false) }
    var webViewRef  by remember { mutableStateOf<WebView?>(null) }

    // ── Extract EPUB to temp folder ───────────────────────────────────────────
    LaunchedEffect(path) {
        isLoading = true
        val result = withContext(Dispatchers.IO) {
            try {
                // Create per-book temp dir
                val dir = File(file.parent ?: "/tmp", ".epub_cache_${file.nameWithoutExtension}")
                if (!dir.exists()) {
                    dir.mkdirs()
                    ZipFile(file).use { zip ->
                        zip.entries().asSequence().forEach { entry ->
                            val dest = File(dir, entry.name)
                            if (entry.isDirectory) { dest.mkdirs(); return@forEach }
                            dest.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { input -> dest.outputStream().use { input.copyTo(it) } }
                        }
                    }
                }
                // Parse container.xml → OPF
                val containerFile = File(dir, "META-INF/container.xml")
                if (!containerFile.exists()) return@withContext null

                val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                val containerDoc = builder.parse(containerFile)
                val opfRelPath = containerDoc.getElementsByTagName("rootfile")
                    .item(0)?.attributes?.getNamedItem("full-path")?.nodeValue
                    ?: return@withContext null

                val opfFile = File(dir, opfRelPath)
                val opfDir  = opfRelPath.substringBeforeLast("/", "")
                val opfDoc  = builder.parse(opfFile)

                // Build manifest id→href map
                val manifest = mutableMapOf<String, String>()
                val items = opfDoc.getElementsByTagName("item")
                for (i in 0 until items.length) {
                    val item = items.item(i)
                    val id   = item.attributes.getNamedItem("id")?.nodeValue   ?: continue
                    val href = item.attributes.getNamedItem("href")?.nodeValue ?: continue
                    manifest[id] = href
                }

                // Spine order
                val spine = mutableListOf<String>()
                val ncxId  = opfDoc.getElementsByTagName("spine").item(0)
                    ?.attributes?.getNamedItem("toc")?.nodeValue
                val itemrefs = opfDoc.getElementsByTagName("itemref")
                for (i in 0 until itemrefs.length) {
                    val idref = itemrefs.item(i).attributes?.getNamedItem("idref")?.nodeValue ?: continue
                    val href  = manifest[idref] ?: continue
                    val full  = if (opfDir.isNotEmpty()) "$opfDir/$href" else href
                    spine.add(File(dir, full).toURI().toString())
                }

                // Chapter titles from NCX (optional)
                val chapterLabels = mutableListOf<Pair<String, String>>()
                val ncxHref = manifest[ncxId]
                if (ncxHref != null) {
                    val ncxPath = if (opfDir.isNotEmpty()) "$opfDir/$ncxHref" else ncxHref
                    val ncxFile = File(dir, ncxPath)
                    if (ncxFile.exists()) {
                        val ncxDoc  = builder.parse(ncxFile)
                        val navPts  = ncxDoc.getElementsByTagName("navPoint")
                        for (i in 0 until navPts.length) {
                            val np      = navPts.item(i)
                            val labelEl = (np as? org.w3c.dom.Element)
                                ?.getElementsByTagName("navLabel")?.item(0)
                            val label   = labelEl?.textContent?.trim() ?: "Chapter ${i+1}"
                            val contentEl = (np as? org.w3c.dom.Element)
                                ?.getElementsByTagName("content")?.item(0)
                            val srcAttr = (contentEl as? org.w3c.dom.Element)?.getAttribute("src") ?: continue
                            // Resolve href relative to ncx
                            val ncxDir = ncxPath.substringBeforeLast("/", "")
                            val resolved = if (ncxDir.isNotEmpty()) "$ncxDir/$srcAttr" else srcAttr
                            val absUrl = File(dir, resolved.substringBefore("#")).toURI().toString()
                            val spinIdx = spine.indexOfFirst { it.startsWith(absUrl) }
                            if (spinIdx >= 0) chapterLabels.add(Pair(label, spine[spinIdx]))
                        }
                    }
                }

                Triple(dir, spine, chapterLabels)
            } catch (e: Exception) {
                e.printStackTrace(); null
            }
        }
        if (result != null) {
            extractDir  = result.first
            spineUrls   = result.second
            chapters    = result.third.ifEmpty {
                result.second.mapIndexed { i, url -> "Chapter ${i+1}" to url }
            }
            currentIdx  = 0
            isLoading   = false
        } else {
            isLoading = false
            errorMsg  = "EPUB খুলতে পারিনি"
        }
    }

    // ── Current URL ───────────────────────────────────────────────────────────
    val currentUrl = spineUrls.getOrNull(currentIdx)

    // ── UI ────────────────────────────────────────────────────────────────────
    Box(Modifier.fillMaxSize().background(EPUB_BG)) {

        when {
            isLoading -> {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = EPUB_ACCENT, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("EPUB লোড হচ্ছে…", color = Color.White.copy(0.7f), fontSize = 14.sp)
                }
            }
            errorMsg != null -> {
                Column(Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📚", fontSize = 52.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(errorMsg!!, color = Color(0xFFFF5C5C), fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = onBack) { Text("ফিরে যান", color = Color.White) }
                }
            }
            currentUrl != null -> {
                // ── WebView ───────────────────────────────────────────────────
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory  = { ctx ->
                        WebView(ctx).also { wv ->
                            webViewRef = wv
                            wv.settings.apply {
                                javaScriptEnabled    = true
                                domStorageEnabled    = true
                                allowFileAccess      = true
                                @Suppress("DEPRECATION")
                                allowUniversalAccessFromFileURLs = true
                                @Suppress("DEPRECATION")
                                allowFileAccessFromFileURLs = true
                                builtInZoomControls  = true
                                displayZoomControls  = false
                                setSupportZoom(true)
                                loadWithOverviewMode = true
                                useWideViewPort      = false
                                textZoom             = 110   // slight size boost for readability
                            }
                            wv.webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView, request: WebResourceRequest
                                ): WebResourceResponse? {
                                    // Inject reading CSS into every HTML response
                                    val url = request.url.toString()
                                    if (url.endsWith(".xhtml") || url.endsWith(".html") || url.endsWith(".htm")) {
                                        try {
                                            val f    = File(java.net.URI(url))
                                            if (f.exists()) {
                                                val html = f.readText()
                                                val enhanced = injectReadingCss(html)
                                                return WebResourceResponse(
                                                    "text/html", "UTF-8",
                                                    ByteArrayInputStream(enhanced.toByteArray())
                                                )
                                            }
                                        } catch (_: Exception) {}
                                    }
                                    return super.shouldInterceptRequest(view, request)
                                }
                            }
                            wv.setBackgroundColor(android.graphics.Color.parseColor("#1A1A2E"))
                        }
                    },
                    update = { wv ->
                        val url = spineUrls.getOrNull(currentIdx) ?: return@AndroidView
                        if (wv.url != url) wv.loadUrl(url)
                    }
                )

                // ── Header ────────────────────────────────────────────────────
                AnimatedVisibility(
                    visible = headerVisible,
                    enter   = fadeIn(),
                    exit    = fadeOut()
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(EPUB_HEADER)
                            .statusBarsPadding()
                    ) {
                        Row(
                            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                            }
                            Text(
                                text     = file.nameWithoutExtension,
                                color    = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines  = 1,
                                overflow  = TextOverflow.Ellipsis,
                                modifier  = Modifier.weight(1f).padding(horizontal = 4.dp)
                            )
                            // Chapter list toggle
                            if (chapters.size > 1) {
                                IconButton(onClick = { showChapters = true }) {
                                    Icon(Icons.Default.List, "Chapters", tint = Color.White)
                                }
                            }
                        }
                    }
                }

                // ── Prev / Next page strip ─────────────────────────────────────
                if (spineUrls.size > 1) {
                    Row(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .background(Color(0xCC0D0D1A))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick  = { if (currentIdx > 0) currentIdx-- },
                            enabled  = currentIdx > 0
                        ) { Text("◀ আগে", color = if (currentIdx > 0) EPUB_ACCENT else Color.Gray, fontSize = 13.sp) }

                        Text(
                            "${currentIdx + 1} / ${spineUrls.size}",
                            color = Color.White.copy(0.6f), fontSize = 12.sp
                        )

                        TextButton(
                            onClick  = { if (currentIdx < spineUrls.lastIndex) currentIdx++ },
                            enabled  = currentIdx < spineUrls.lastIndex
                        ) { Text("পরে ▶", color = if (currentIdx < spineUrls.lastIndex) EPUB_ACCENT else Color.Gray, fontSize = 13.sp) }
                    }
                }
            }
        }
    }

    // ── Chapter sheet ─────────────────────────────────────────────────────────
    if (showChapters && chapters.isNotEmpty()) {
        ModalBottomSheet(
            onDismissRequest    = { showChapters = false },
            containerColor      = Color(0xFF111122),
            contentColor        = Color.White
        ) {
            Text(
                "অধ্যায়সমূহ",
                fontSize   = 16.sp,
                fontWeight = FontWeight.Bold,
                color      = Color.White,
                modifier   = Modifier.padding(start = 20.dp, bottom = 8.dp)
            )
            HorizontalDivider(color = Color.White.copy(0.1f))
            chapters.forEachIndexed { idx, (label, url) ->
                val isSelected = spineUrls.getOrNull(currentIdx) == url
                val spineIdx   = spineUrls.indexOf(url)
                ListItem(
                    headlineContent = {
                        Text(
                            label,
                            color      = if (isSelected) EPUB_ACCENT else Color.White,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize   = 14.sp
                        )
                    },
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .clickable(enabled = spineIdx >= 0) {
                            if (spineIdx >= 0) currentIdx = spineIdx
                            showChapters = false
                        },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                HorizontalDivider(color = Color.White.copy(0.06f))
            }
            Spacer(Modifier.navigationBarsPadding().height(8.dp))
        }
    }
}

// ── Inject reader CSS so any EPUB chapter looks clean ────────────────────────

private fun injectReadingCss(html: String): String {
    val css = """
<style>
:root { --bg: #1a1a2e; --fg: #e8e6f0; --accent: #9d91ff; --link: #6c63ff; }
html, body {
    background-color: var(--bg) !important;
    color: var(--fg) !important;
    font-family: 'Georgia', serif !important;
    font-size: 18px !important;
    line-height: 1.75 !important;
    padding: 16px 20px 80px !important;
    margin: 0 !important;
    max-width: 100% !important;
    word-break: break-word !important;
    overflow-x: hidden !important;
}
h1,h2,h3,h4,h5,h6 {
    color: #ffffff !important;
    font-family: sans-serif !important;
    margin-top: 1.4em !important;
    line-height: 1.3 !important;
}
p { margin: 0.8em 0 !important; }
a { color: var(--link) !important; }
img { max-width: 100% !important; height: auto !important; display: block !important; margin: 12px auto !important; }
blockquote { border-left: 3px solid var(--accent) !important; padding-left: 12px !important; color: #aaa !important; margin: 1em 0 !important; }
code, pre { background: #2a2a3e !important; color: #c8f5a0 !important; padding: 2px 6px !important; border-radius: 4px !important; font-size: 0.9em !important; }
table { width: 100% !important; border-collapse: collapse !important; }
td, th { border: 1px solid #3a3a5e !important; padding: 6px 8px !important; }
</style>
""".trimIndent()

    // Insert before </head> if present, else prepend
    val headClose = html.indexOf("</head>", ignoreCase = true)
    return if (headClose >= 0) {
        html.substring(0, headClose) + css + html.substring(headClose)
    } else {
        css + html
    }
}

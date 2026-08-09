package com.rasel.RasFocus.filemanager

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubViewerScreen(path: String, onBack: () -> Unit) {
    var htmlContent by remember { mutableStateOf("Loading EPUB...") }
    val file = File(path)

    LaunchedEffect(path) {
        htmlContent = withContext(Dispatchers.IO) {
            try {
                parseEpubSimple(file)
            } catch (e: Exception) {
                e.printStackTrace()
                "Error loading EPUB: ${e.message}"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFF7F9FC)
                )
            )
        }
    ) { padding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            factory = { context ->
                WebView(context).apply {
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                }
            },
            update = { webView ->
                if (htmlContent.startsWith("Error") || htmlContent.startsWith("Loading")) {
                    webView.loadDataWithBaseURL(null, "<html><body style='padding:20px; font-family:sans-serif;'><h3>$htmlContent</h3></body></html>", "text/html", "UTF-8", null)
                } else {
                    webView.loadDataWithBaseURL("file://${file.parent}/", htmlContent, "text/html", "UTF-8", null)
                }
            }
        )
    }
}

private fun parseEpubSimple(file: File): String {
    val zip = ZipFile(file)
    var opfPath = ""

    // 1. Find container.xml to get OPF path
    val containerEntry = zip.getEntry("META-INF/container.xml")
        ?: return "Error: Not a valid EPUB (missing META-INF/container.xml)"

    val factory = DocumentBuilderFactory.newInstance()
    val builder = factory.newDocumentBuilder()
    
    zip.getInputStream(containerEntry).use { input ->
        val doc = builder.parse(input)
        val rootfiles = doc.getElementsByTagName("rootfile")
        if (rootfiles.length > 0) {
            val rootfile = rootfiles.item(0)
            opfPath = rootfile.attributes.getNamedItem("full-path")?.nodeValue ?: ""
        }
    }

    if (opfPath.isEmpty()) return "Error: Could not find OPF path in container.xml"

    val opfEntry = zip.getEntry(opfPath) ?: return "Error: Could not find OPF file"
    val opfDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""

    // 2. Parse OPF
    val manifestMap = mutableMapOf<String, String>()
    val spineList = mutableListOf<String>()

    zip.getInputStream(opfEntry).use { input ->
        val doc = builder.parse(input)
        
        // Manifest
        val itemNodes = doc.getElementsByTagName("item")
        for (i in 0 until itemNodes.length) {
            val item = itemNodes.item(i)
            val id = item.attributes.getNamedItem("id")?.nodeValue
            val href = item.attributes.getNamedItem("href")?.nodeValue
            if (id != null && href != null) {
                manifestMap[id] = href
            }
        }
        
        // Spine
        val itemrefNodes = doc.getElementsByTagName("itemref")
        for (i in 0 until itemrefNodes.length) {
            val itemref = itemrefNodes.item(i)
            val idref = itemref.attributes.getNamedItem("idref")?.nodeValue
            if (idref != null) {
                spineList.add(idref)
            }
        }
    }

    // 3. Extract HTML from spine
    val sb = java.lang.StringBuilder()
    sb.append("<html><head><style>body { font-family: sans-serif; padding: 16px; line-height: 1.6; font-size: 18px; color: #333; max-width: 800px; margin: 0 auto; overflow-x: hidden; width: 100%; box-sizing: border-box; } img { max-width: 100%; height: auto; }</style></head><body>")
    
    for (idref in spineList) {
        val href = manifestMap[idref] ?: continue
        val fullPath = opfDir + href
        val htmlEntry = zip.getEntry(fullPath) ?: continue
        
        zip.getInputStream(htmlEntry).use { input ->
            val text = input.reader().readText()
            // Very basic cleanup to extract body content (not perfect but works for simple epubs)
            val bodyStart = text.indexOf("<body", ignoreCase = true)
            val bodyEnd = text.lastIndexOf("</body>", ignoreCase = true)
            
            if (bodyStart != -1 && bodyEnd != -1 && bodyEnd > bodyStart) {
                // Find the closing bracket of the body tag
                val bodyTagEnd = text.indexOf(">", bodyStart)
                if (bodyTagEnd != -1 && bodyTagEnd < bodyEnd) {
                    sb.append(text.substring(bodyTagEnd + 1, bodyEnd))
                } else {
                    sb.append(text)
                }
            } else {
                sb.append(text)
            }
            sb.append("<hr style='margin: 40px 0; border: 0; border-top: 1px dashed #ccc;'/>")
        }
    }
    sb.append("</body></html>")
    zip.close()
    
    return sb.toString()
}

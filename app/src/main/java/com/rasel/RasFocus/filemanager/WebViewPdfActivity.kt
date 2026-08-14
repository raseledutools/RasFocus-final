package com.rasel.RasFocus.filemanager

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WebViewPdfActivity : ComponentActivity() {
    companion object {
        const val EXTRA_LAYER_LABEL = "LAYER_LABEL"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pdfUri: Uri? = intent.data
        val label: String = intent.getStringExtra(EXTRA_LAYER_LABEL) ?: "PDF Viewer"
        setContent {
            WebViewPdfScreen(
                pdfUri          = pdfUri,
                label           = label,
                onBack          = { finish() },
                contentResolver = contentResolver,
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebViewPdfScreen(
    pdfUri: Uri?,
    label: String,
    onBack: () -> Unit,
    contentResolver: android.content.ContentResolver,
) {
    val BG     = Color(0xFF0A0A0F)
    val INDIGO = Color(0xFF6C63FF)
    val scope  = rememberCoroutineScope()

    var loadProgress  by remember { mutableIntStateOf(0) }
    var webViewRef    by remember { mutableStateOf<WebView?>(null) }
    var pdfBase64     by remember { mutableStateOf<String?>(null) }
    var readError     by remember { mutableStateOf(false) }
    var isReading     by remember { mutableStateOf(true) }

    // ── Step 1: Read PDF bytes on IO thread ──────────────────────────────────
    LaunchedEffect(pdfUri) {
        if (pdfUri == null) { readError = true; isReading = false; return@LaunchedEffect }
        scope.launch {
            val b64 = withContext(Dispatchers.IO) {
                try {
                    val bytes = when (pdfUri.scheme) {
                        "content" -> contentResolver.openInputStream(pdfUri)?.use { it.readBytes() }
                        "file"    -> java.io.File(pdfUri.path!!).readBytes()
                        else      -> null
                    }
                    bytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
                } catch (e: Exception) { null }
            }
            if (b64 == null) { readError = true } else { pdfBase64 = b64 }
            isReading = false
        }
    }

    // ── Step 2: Inject into WebView once both b64 + webview are ready ────────
    LaunchedEffect(pdfBase64, webViewRef) {
        val b64 = pdfBase64 ?: return@LaunchedEffect
        val wv  = webViewRef ?: return@LaunchedEffect
        wv.post {
            // Pass base64 to the waiting JS function
            wv.evaluateJavascript("renderPdf('$b64');", null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(label, fontSize = 14.sp, color = Color.White, maxLines = 1)
                        Text("⚡ WebView engine", fontSize = 11.sp, color = INDIGO)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        pdfBase64?.let { b64 ->
                            webViewRef?.evaluateJavascript("renderPdf('$b64');", null)
                        }
                    }) {
                        Icon(Icons.Default.Refresh, "Reload", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF111118))
            )
        },
        containerColor = BG
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(BG)
        ) {
            when {
                isReading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = INDIGO, strokeWidth = 3.dp)
                            Spacer(Modifier.height(12.dp))
                            Text("PDF পড়া হচ্ছে...", color = Color(0xFF888899), fontSize = 13.sp)
                        }
                    }
                }

                readError || pdfUri == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("PDF খোলা যায়নি", color = Color(0xFFFF5C5C), fontSize = 14.sp)
                    }
                }

                else -> {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory  = { ctx ->
                            WebView(ctx).also { wv ->
                                webViewRef = wv
                                wv.settings.apply {
                                    javaScriptEnabled    = true
                                    domStorageEnabled    = true
                                    allowFileAccess      = true
                                    builtInZoomControls  = true
                                    displayZoomControls  = false
                                    loadWithOverviewMode = true
                                    useWideViewPort      = true
                                    setSupportZoom(true)
                                }
                                wv.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

                                wv.webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        loadProgress = newProgress
                                    }
                                }
                                wv.webViewClient = WebViewClient()

                                // Load HTML shell — JS waits for renderPdf() call
                                wv.loadDataWithBaseURL(
                                    "file:///android_asset/",
                                    buildHtmlShell(),
                                    "text/html",
                                    "UTF-8",
                                    null
                                )
                            }
                        }
                    )

                    if (loadProgress < 100) {
                        LinearProgressIndicator(
                            progress   = { loadProgress / 100f },
                            modifier   = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .align(Alignment.TopCenter),
                            color      = INDIGO,
                            trackColor = Color.Transparent,
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Minimal HTML — renders PDF via <embed> with base64 data URI.
// No CDN, no internet needed. Works fully offline.
// ─────────────────────────────────────────────────────────────────────────────
private fun buildHtmlShell(): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=yes">
<style>
  * { margin:0; padding:0; box-sizing:border-box; }
  html, body { width:100%; height:100%; background:#0a0a0f; overflow:hidden; }
  #frame {
    width:100%; height:100%;
    border:none; display:none;
    background:#fff;
  }
  #loader {
    display:flex; flex-direction:column;
    align-items:center; justify-content:center;
    height:100vh; color:#888; font-family:sans-serif; font-size:14px;
  }
  .dot { animation: blink 1.2s infinite; }
  .dot:nth-child(2) { animation-delay:.2s; }
  .dot:nth-child(3) { animation-delay:.4s; }
  @keyframes blink { 0%,80%,100%{opacity:0} 40%{opacity:1} }
</style>
</head>
<body>
<div id="loader">
  রেন্ডার হচ্ছে<span class="dot">.</span><span class="dot">.</span><span class="dot">.</span>
</div>
<iframe id="frame"></iframe>

<script>
function renderPdf(b64) {
  var frame = document.getElementById('frame');
  var loader = document.getElementById('loader');
  // Use data URI — works completely offline
  frame.src = 'data:application/pdf;base64,' + b64;
  frame.onload = function() {
    loader.style.display = 'none';
    frame.style.display = 'block';
  };
}
</script>
</body>
</html>
""".trimIndent()

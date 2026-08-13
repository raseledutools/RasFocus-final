package com.rasel.RasFocus.filemanager

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import java.io.ByteArrayInputStream
import java.io.InputStream

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
                pdfUri        = pdfUri,
                label         = label,
                onBack        = { finish() },
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

    var loadProgress by remember { mutableIntStateOf(0) }
    var webViewRef   by remember { mutableStateOf<WebView?>(null) }
    var errorMsg     by remember { mutableStateOf<String?>(null) }
    // pdfBytes: loaded once on IO thread, then injected into WebView
    var pdfBytes     by remember { mutableStateOf<ByteArray?>(null) }
    var readError    by remember { mutableStateOf(false) }

    // ── Read PDF bytes on IO thread ──────────────────────────────────────────
    LaunchedEffect(pdfUri) {
        if (pdfUri == null) { readError = true; return@LaunchedEffect }
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                try {
                    val stream: InputStream? = when {
                        pdfUri.scheme == "content" -> contentResolver.openInputStream(pdfUri)
                        pdfUri.scheme == "file"    -> java.io.FileInputStream(pdfUri.path!!)
                        else                       -> null
                    }
                    stream?.use { it.readBytes() }
                } catch (e: Exception) { null }
            }
            if (bytes == null) { readError = true } else { pdfBytes = bytes }
        }
    }

    // ── Inject PDF into WebView once bytes are ready ─────────────────────────
    LaunchedEffect(pdfBytes, webViewRef) {
        val bytes = pdfBytes ?: return@LaunchedEffect
        val wv    = webViewRef ?: return@LaunchedEffect
        val b64   = Base64.encodeToString(bytes, Base64.NO_WRAP)
        wv.post {
            wv.evaluateJavascript("window.injectPdfBase64('$b64')", null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(label, fontSize = 14.sp, color = Color.White, maxLines = 1)
                        Text("⚡ PDF.js engine", fontSize = 11.sp, color = INDIGO)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { webViewRef?.reload() }) {
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
                pdfUri == null || readError -> {
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
                                    cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                                }
                                wv.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

                                wv.webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        loadProgress = newProgress
                                    }
                                }

                                wv.webViewClient = object : WebViewClient() {
                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: android.webkit.WebResourceError?
                                    ) {
                                        // Only show error for main frame failures
                                        if (request?.isForMainFrame == true) {
                                            errorMsg = "লোড হয়নি: ${error?.description}"
                                        }
                                    }

                                    // Intercept PDF.js CDN requests and serve from cache
                                    override fun shouldInterceptRequest(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): WebResourceResponse? = null // let cache handle it
                                }

                                // Load the PDF.js HTML shell — PDF bytes injected later via JS
                                wv.loadDataWithBaseURL(
                                    "https://appassets.androidplatform.net/",
                                    buildPdfJsHtml(),
                                    "text/html",
                                    "UTF-8",
                                    null
                                )
                            }
                        }
                    )

                    // Loading bar
                    if (loadProgress < 100) {
                        LinearProgressIndicator(
                            progress        = { loadProgress / 100f },
                            modifier        = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .align(Alignment.TopCenter),
                            color           = INDIGO,
                            trackColor      = Color.Transparent,
                        )
                    }

                    // Error overlay
                    errorMsg?.let { msg ->
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(BG.copy(alpha = 0.90f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("⚠️", fontSize = 40.sp)
                                Spacer(Modifier.height(8.dp))
                                Text(msg, color = Color(0xFFFF5C5C), fontSize = 13.sp)
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = { errorMsg = null; webViewRef?.reload() },
                                    colors  = ButtonDefaults.buttonColors(containerColor = INDIGO)
                                ) { Text("আবার চেষ্টা করুন") }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PDF.js HTML shell
// Uses PDF.js 4.x from cdnjs — cached by WebView after first load.
// injectPdfBase64(b64) is called from Kotlin once bytes are ready.
// ─────────────────────────────────────────────────────────────────────────────
private fun buildPdfJsHtml(): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes">
<style>
  * { margin:0; padding:0; box-sizing:border-box; }
  html, body {
    width:100%; height:100%;
    background:#1a1a2e;
    overflow-x:hidden;
  }
  #loading {
    display:flex; flex-direction:column;
    align-items:center; justify-content:center;
    height:100vh; color:#aaa; font-family:sans-serif; font-size:14px;
  }
  .spinner {
    width:36px; height:36px; border:3px solid #333;
    border-top-color:#6C63FF; border-radius:50%;
    animation:spin .8s linear infinite; margin-bottom:12px;
  }
  @keyframes spin { to { transform:rotate(360deg); } }
  #viewer {
    display:none;
    width:100%;
    padding:8px 0;
    background:#1a1a2e;
    overflow-y:auto;
  }
  .page-wrapper {
    display:flex; justify-content:center;
    margin: 4px 0;
  }
  canvas {
    display:block;
    max-width:100%;
    box-shadow: 0 2px 8px rgba(0,0,0,0.5);
    background:#fff;
  }
  #error {
    display:none; color:#ff5c5c;
    text-align:center; padding:32px;
    font-family:sans-serif; font-size:14px;
  }
</style>
</head>
<body>
<div id="loading"><div class="spinner"></div><span>লোড হচ্ছে...</span></div>
<div id="viewer"></div>
<div id="error">PDF রেন্ডার করা যায়নি।</div>

<script src="https://cdnjs.cloudflare.com/ajax/libs/pdf.js/4.4.168/pdf.min.mjs" type="module">
</script>
<script type="module">
  // Import PDF.js
  const { getDocument, GlobalWorkerOptions } =
    await import('https://cdnjs.cloudflare.com/ajax/libs/pdf.js/4.4.168/pdf.min.mjs');

  GlobalWorkerOptions.workerSrc =
    'https://cdnjs.cloudflare.com/ajax/libs/pdf.js/4.4.168/pdf.worker.min.mjs';

  const SCALE = window.devicePixelRatio > 1 ? 1.8 : 1.5;

  async function renderPdf(data) {
    try {
      const loadingTask = getDocument({ data });
      const pdf = await loadingTask.promise;
      const viewer = document.getElementById('viewer');
      viewer.innerHTML = '';
      document.getElementById('loading').style.display = 'none';
      viewer.style.display = 'block';

      for (let i = 1; i <= pdf.numPages; i++) {
        const page    = await pdf.getPage(i);
        const vp      = page.getViewport({ scale: SCALE });
        const wrapper = document.createElement('div');
        wrapper.className = 'page-wrapper';
        const canvas  = document.createElement('canvas');
        canvas.width  = vp.width;
        canvas.height = vp.height;
        canvas.style.width  = Math.floor(vp.width  / window.devicePixelRatio) + 'px';
        canvas.style.height = Math.floor(vp.height / window.devicePixelRatio) + 'px';
        wrapper.appendChild(canvas);
        viewer.appendChild(wrapper);
        await page.render({ canvasContext: canvas.getContext('2d'), viewport: vp }).promise;
      }
    } catch(e) {
      document.getElementById('loading').style.display = 'none';
      document.getElementById('error').style.display = 'block';
    }
  }

  // Called from Kotlin with base64 PDF data
  window.injectPdfBase64 = function(b64) {
    const binary = atob(b64);
    const bytes  = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    renderPdf(bytes.buffer);
  };
</script>
</body>
</html>
""".trimIndent()

package com.rasel.RasFocus.filemanager

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.util.Base64
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
                activity        = this,
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
    activity: ComponentActivity,
) {
    val BG     = Color(0xFF0A0A0F)
    val INDIGO = Color(0xFF6C63FF)
    val scope  = rememberCoroutineScope()

    var isReading    by remember { mutableStateOf(true) }
    var readError    by remember { mutableStateOf(false) }
    var pdfB64       by remember { mutableStateOf<String?>(null) }
    var webViewRef   by remember { mutableStateOf<WebView?>(null) }
    var loadProgress by remember { mutableIntStateOf(0) }

    // ── Step 1: Read PDF bytes on IO, encode to base64 ────────────────────────
    LaunchedEffect(pdfUri) {
        if (pdfUri == null) { readError = true; isReading = false; return@LaunchedEffect }
        scope.launch {
            val b64 = withContext(Dispatchers.IO) {
                try {
                    val bytes = when (pdfUri.scheme) {
                        "content" -> activity.contentResolver
                            .openInputStream(pdfUri)?.use { it.readBytes() }
                        "file"    -> java.io.File(pdfUri.path!!).readBytes()
                        else      -> null
                    }
                    bytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
                } catch (e: Exception) { null }
            }
            if (b64 == null) readError = true else pdfB64 = b64
            isReading = false
        }
    }

    // ── Step 2: Load HTML into WebView once both ready ────────────────────────
    LaunchedEffect(pdfB64, webViewRef) {
        val b64 = pdfB64 ?: return@LaunchedEffect
        val wv  = webViewRef ?: return@LaunchedEffect
        wv.post {
            // BASE URL = https://appassets.androidplatform.net/
            // WebViewClient intercepts /pdfjs/* → serves from assets
            // type="module" works because origin is https://
            wv.loadDataWithBaseURL(
                "https://appassets.androidplatform.net/",
                buildHtml(b64),
                "text/html",
                "UTF-8",
                null
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(label, fontSize = 14.sp, color = Color.White, maxLines = 1)
                        Text("⚡ PDF.js (offline)", fontSize = 11.sp, color = INDIGO)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        pdfB64?.let { b64 ->
                            webViewRef?.loadDataWithBaseURL(
                                "https://appassets.androidplatform.net/",
                                buildHtml(b64),
                                "text/html", "UTF-8", null
                            )
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
                                    allowContentAccess   = true
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

                                // ── Intercept /pdfjs/* → serve from assets ────
                                wv.webViewClient = object : WebViewClient() {
                                    override fun shouldInterceptRequest(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): WebResourceResponse? {
                                        val path = request?.url?.path ?: return null
                                        if (!path.startsWith("/pdfjs/")) return null
                                        val assetName = path.removePrefix("/")
                                        return try {
                                            val stream = activity.assets.open(assetName)
                                            WebResourceResponse("text/javascript", "UTF-8", stream)
                                        } catch (e: Exception) {
                                            null
                                        }
                                    }
                                }
                            }
                        }
                    )

                    if (loadProgress in 1..99) {
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

private fun buildHtml(b64: String): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes">
<style>
  * { margin:0; padding:0; box-sizing:border-box; }
  html, body { width:100%; background:#1a1a2e; }
  #loader {
    display:flex; flex-direction:column; align-items:center;
    justify-content:center; height:100vh;
    color:#aaa; font-family:sans-serif; font-size:14px;
  }
  .spinner {
    width:36px; height:36px; border:3px solid #333;
    border-top-color:#6C63FF; border-radius:50%;
    animation:spin .8s linear infinite; margin-bottom:12px;
  }
  @keyframes spin { to{transform:rotate(360deg);} }
  #viewer { width:100%; padding:8px 0; background:#1a1a2e; }
  .page-wrap { display:flex; justify-content:center; margin:4px 0; }
  canvas { display:block; max-width:100%; background:#fff; box-shadow:0 2px 8px rgba(0,0,0,.5); }
  #err { display:none; color:#ff5c5c; text-align:center; padding:32px; font-family:sans-serif; font-size:13px; }
</style>
</head>
<body>
<div id="loader"><div class="spinner"></div><span>PDF লোড হচ্ছে...</span></div>
<div id="viewer"></div>
<div id="err"></div>
<script type="module">
  // /pdfjs/ path → WebViewClient intercepts → serves from assets (offline, instant)
  import { getDocument, GlobalWorkerOptions }
    from '/pdfjs/pdf.min.mjs';

  GlobalWorkerOptions.workerSrc = '/pdfjs/pdf.worker.min.mjs';

  const DPR   = window.devicePixelRatio || 1;
  const SCALE = DPR > 1 ? 2.0 : 1.5;

  try {
    const b64 = `$b64`;
    const bin = atob(b64);
    const arr = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);

    const pdf    = await getDocument({ data: arr.buffer }).promise;
    const viewer = document.getElementById('viewer');
    document.getElementById('loader').style.display = 'none';

    // Render all pages sequentially
    for (let i = 1; i <= pdf.numPages; i++) {
      const page = await pdf.getPage(i);
      const vp   = page.getViewport({ scale: SCALE });
      const wrap = document.createElement('div');
      wrap.className = 'page-wrap';
      const c = document.createElement('canvas');
      c.width  = vp.width;
      c.height = vp.height;
      c.style.width  = Math.floor(vp.width  / DPR) + 'px';
      c.style.height = Math.floor(vp.height / DPR) + 'px';
      wrap.appendChild(c);
      viewer.appendChild(wrap);
      await page.render({ canvasContext: c.getContext('2d'), viewport: vp }).promise;
    }
  } catch(e) {
    document.getElementById('loader').style.display = 'none';
    const err = document.getElementById('err');
    err.style.display   = 'block';
    err.textContent     = 'Error: ' + e.message;
  }
</script>
</body>
</html>
""".trimIndent()

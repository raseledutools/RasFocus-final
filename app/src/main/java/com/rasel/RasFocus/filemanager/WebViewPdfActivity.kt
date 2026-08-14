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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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

// ── Render mode ───────────────────────────────────────────────────────────────
private enum class PdfMode { NONE, NATIVE, PDFJS }

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebViewPdfScreen(
    pdfUri: Uri?,
    label: String,
    onBack: () -> Unit,
    contentResolver: android.content.ContentResolver,
) {
    val BG      = Color(0xFF0A0A0F)
    val INDIGO  = Color(0xFF6C63FF)
    val RED     = Color(0xFFE53935)
    val scope   = rememberCoroutineScope()

    var isReading    by remember { mutableStateOf(true) }
    var readError    by remember { mutableStateOf(false) }
    var pdfBytes     by remember { mutableStateOf<ByteArray?>(null) }
    var webViewRef   by remember { mutableStateOf<WebView?>(null) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var mode         by remember { mutableStateOf(PdfMode.NONE) }   // which button pressed

    // ── Read bytes once ───────────────────────────────────────────────────────
    LaunchedEffect(pdfUri) {
        if (pdfUri == null) { readError = true; isReading = false; return@LaunchedEffect }
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                try {
                    when (pdfUri.scheme) {
                        "content" -> contentResolver.openInputStream(pdfUri)?.use { it.readBytes() }
                        "file"    -> java.io.File(pdfUri.path!!).readBytes()
                        else      -> null
                    }
                } catch (e: Exception) { null }
            }
            if (bytes == null) readError = true else pdfBytes = bytes
            isReading = false
        }
    }

    // ── Load into WebView when mode or webview changes ────────────────────────
    LaunchedEffect(mode, webViewRef) {
        val bytes = pdfBytes ?: return@LaunchedEffect
        val wv    = webViewRef ?: return@LaunchedEffect
        if (mode == PdfMode.NONE) return@LaunchedEffect

        wv.post {
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            when (mode) {
                PdfMode.NATIVE -> {
                    // Direct loadData — Chrome built-in PDF renderer
                    wv.loadData(b64, "application/pdf", "base64")
                }
                PdfMode.PDFJS -> {
                    // PDF.js via CDN — JS renders page-by-page on canvas
                    wv.loadDataWithBaseURL(
                        "https://appassets.androidplatform.net/",
                        buildPdfJsHtml(b64),
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
                else -> {}
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(label, fontSize = 14.sp, color = Color.White, maxLines = 1)
                        Text(
                            when (mode) {
                                PdfMode.NATIVE -> "🔵 Native WebView"
                                PdfMode.PDFJS  -> "🟣 PDF.js engine"
                                PdfMode.NONE   -> "⚡ WebView engine"
                            },
                            fontSize = 11.sp, color = INDIGO
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        // Re-trigger current mode
                        val prev = mode
                        mode = PdfMode.NONE
                        mode = prev
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
                // ── Loading bytes ─────────────────────────────────────────────
                isReading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = INDIGO, strokeWidth = 3.dp)
                            Spacer(Modifier.height(12.dp))
                            Text("PDF পড়া হচ্ছে...", color = Color(0xFF888899), fontSize = 13.sp)
                        }
                    }
                }

                // ── Error ─────────────────────────────────────────────────────
                readError || pdfUri == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("PDF খোলা যায়নি", color = Color(0xFFFF5C5C), fontSize = 14.sp)
                    }
                }

                // ── Chooser buttons (no mode selected yet) ────────────────────
                mode == PdfMode.NONE -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("📄", fontSize = 48.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            label,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2
                        )
                        Spacer(Modifier.height(32.dp))
                        Text(
                            "Render engine বেছে নাও",
                            color = Color(0xFF888899),
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(16.dp))

                        // Native button
                        Button(
                            onClick = { mode = PdfMode.NATIVE },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = RED)
                        ) {
                            Text("🔵  Native WebView", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }

                        Spacer(Modifier.height(12.dp))

                        // PDF.js button
                        Button(
                            onClick = { mode = PdfMode.PDFJS },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = INDIGO)
                        ) {
                            Text("🟣  PDF.js (JavaScript)", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                // ── WebView (shown after mode selected) ───────────────────────
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
                                wv.webViewClient = WebViewClient()
                            }
                        }
                    )

                    // Switch engine buttons (top-right overlay)
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xCC111118))
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SmallEngineChip(
                            label     = "🔵 Native",
                            selected  = mode == PdfMode.NATIVE,
                            color     = RED,
                            onClick   = { if (mode != PdfMode.NATIVE) mode = PdfMode.NATIVE }
                        )
                        SmallEngineChip(
                            label     = "🟣 PDF.js",
                            selected  = mode == PdfMode.PDFJS,
                            color     = INDIGO,
                            onClick   = { if (mode != PdfMode.PDFJS) mode = PdfMode.PDFJS }
                        )
                    }

                    if (loadProgress in 1..99) {
                        LinearProgressIndicator(
                            progress   = { loadProgress / 100f },
                            modifier   = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .align(Alignment.TopCenter),
                            color      = if (mode == PdfMode.PDFJS) INDIGO else RED,
                            trackColor = Color.Transparent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallEngineChip(
    label: String,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit,
) {
    Button(
        onClick  = onClick,
        modifier = Modifier.height(32.dp),
        shape    = RoundedCornerShape(16.dp),
        colors   = ButtonDefaults.buttonColors(
            containerColor = if (selected) color else color.copy(alpha = 0.18f),
            contentColor   = if (selected) Color.White else color,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
        elevation = ButtonDefaults.buttonElevation(0.dp)
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PDF.js HTML — b64 directly embedded, no CDN wait needed after first load
// ─────────────────────────────────────────────────────────────────────────────
private fun buildPdfJsHtml(b64: String): String = """
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
  @keyframes spin { to { transform:rotate(360deg); } }
  #viewer { width:100%; padding:8px 0; background:#1a1a2e; }
  .page-wrap { display:flex; justify-content:center; margin:4px 0; }
  canvas { display:block; max-width:100%; background:#fff; box-shadow:0 2px 8px rgba(0,0,0,.5); }
  #err { display:none; color:#ff5c5c; text-align:center; padding:32px; font-family:sans-serif; }
</style>
</head>
<body>
<div id="loader"><div class="spinner"></div><span>PDF.js দিয়ে রেন্ডার হচ্ছে...</span></div>
<div id="viewer"></div>
<div id="err">PDF.js রেন্ডার করতে পারেনি।</div>
<script type="module">
  import { getDocument, GlobalWorkerOptions }
    from 'https://cdnjs.cloudflare.com/ajax/libs/pdf.js/4.4.168/pdf.min.mjs';

  GlobalWorkerOptions.workerSrc =
    'https://cdnjs.cloudflare.com/ajax/libs/pdf.js/4.4.168/pdf.worker.min.mjs';

  const DPR   = window.devicePixelRatio || 1;
  const SCALE = DPR > 1 ? 2.0 : 1.5;

  try {
    // b64 injected directly — no JS call needed, instant start
    const b64    = `$b64`;
    const bin    = atob(b64);
    const arr    = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);

    const pdf    = await getDocument({ data: arr.buffer }).promise;
    const viewer = document.getElementById('viewer');
    document.getElementById('loader').style.display = 'none';

    for (let i = 1; i <= pdf.numPages; i++) {
      const page = await pdf.getPage(i);
      const vp   = page.getViewport({ scale: SCALE });
      const wrap = document.createElement('div');
      wrap.className = 'page-wrap';
      const c    = document.createElement('canvas');
      c.width    = vp.width;
      c.height   = vp.height;
      c.style.width  = Math.floor(vp.width  / DPR) + 'px';
      c.style.height = Math.floor(vp.height / DPR) + 'px';
      wrap.appendChild(c);
      viewer.appendChild(wrap);
      await page.render({ canvasContext: c.getContext('2d'), viewport: vp }).promise;
    }
  } catch(e) {
    document.getElementById('loader').style.display = 'none';
    document.getElementById('err').style.display = 'block';
    document.getElementById('err').textContent = 'Error: ' + e.message;
  }
</script>
</body>
</html>
""".trimIndent()

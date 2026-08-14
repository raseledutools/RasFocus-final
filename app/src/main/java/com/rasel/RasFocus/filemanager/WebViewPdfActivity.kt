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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

    var isReading     by remember { mutableStateOf(true) }
    var readError     by remember { mutableStateOf(false) }
    var pdfB64        by remember { mutableStateOf<String?>(null) }
    var webViewRef    by remember { mutableStateOf<WebView?>(null) }
    var loadProgress  by remember { mutableIntStateOf(0) }
    var headerVisible by remember { mutableStateOf(true) }

    // Read PDF bytes once → encode to base64
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
            if (bytes == null) {
                readError = true
            } else {
                pdfB64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
            isReading = false
        }
    }

    // Load into WebView once b64 and webview are ready
    LaunchedEffect(pdfB64, webViewRef) {
        val b64 = pdfB64 ?: return@LaunchedEffect
        val wv  = webViewRef ?: return@LaunchedEffect
        wv.post {
            wv.loadDataWithBaseURL(
                "file:///android_asset/pdfjs/",
                buildPdfJsHtml(b64),
                "text/html", "UTF-8", null
            )
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(BG)
    ) {
        when {
            // Loading
            isReading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = INDIGO, strokeWidth = 3.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("PDF পড়া হচ্ছে...", color = Color(0xFF888899), fontSize = 13.sp)
                    }
                }
            }

            // Error
            readError || pdfUri == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("❌", fontSize = 40.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("PDF খোলা যায়নি", color = Color(0xFFFF5C5C), fontSize = 14.sp)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onBack) { Text("ফিরে যাও") }
                    }
                }
            }

            // Viewer — full screen WebView
            else -> {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { headerVisible = !headerVisible })
                        },
                    factory = { ctx ->
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
                                @Suppress("DEPRECATION")
                                textZoom = 100
                            }
                            wv.isLongClickable = true
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

                // Progress bar (top)
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

                // Compact header — tap to hide/show
                AnimatedVisibility(
                    visible  = headerVisible,
                    modifier = Modifier.align(Alignment.TopCenter),
                    enter    = fadeIn() + slideInVertically(),
                    exit     = fadeOut() + slideOutVertically(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xEE0A0A0F))
                            .statusBarsPadding()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                            Icon(
                                Icons.Default.ArrowBack, "Back",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            label,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp)
                        )
                        Text(
                            "🟣 PDF.js",
                            color = INDIGO,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── PDF.js HTML ───────────────────────────────────────────────────────────────
// • Opens directly — no chooser screen
// • First page renders first (fast start)
// • Full screen width like WPS
// • Text layer → long press to select/copy
// • High quality DPR-aware rendering
// ─────────────────────────────────────────────────────────────────────────────
private fun buildPdfJsHtml(b64: String): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes">
<style>
  * { margin:0; padding:0; box-sizing:border-box; }
  html, body {
    width:100%; background:#424242;
    -webkit-user-select:text; user-select:text;
  }
  #loader {
    display:flex; flex-direction:column; align-items:center;
    justify-content:center; height:100vh;
    color:#ccc; font-family:sans-serif; font-size:14px; background:#424242;
  }
  .spinner {
    width:40px; height:40px; border:3px solid #555;
    border-top-color:#6C63FF; border-radius:50%;
    animation:spin .7s linear infinite; margin-bottom:14px;
  }
  @keyframes spin { to { transform:rotate(360deg); } }
  #viewer { width:100%; background:#424242; }

  .page-block {
    width:100%; position:relative;
    margin-bottom:6px; background:#fff; overflow:hidden;
  }
  .page-block canvas {
    display:block; width:100% !important; height:auto !important;
  }
  .textLayer {
    position:absolute; top:0; left:0; right:0; bottom:0;
    overflow:hidden; line-height:1; pointer-events:auto;
  }
  .textLayer > span {
    color:transparent; position:absolute; white-space:pre;
    cursor:text; transform-origin:0% 0%;
    -webkit-user-select:text; user-select:text;
  }
  .textLayer ::selection { background:rgba(108,99,255,0.35); }

  #err {
    display:none; color:#ff5c5c;
    text-align:center; padding:32px;
    font-family:sans-serif; background:#1a1a2e;
    min-height:100vh; align-items:center; justify-content:center;
  }
</style>
</head>
<body>
<div id="loader"><div class="spinner"></div><span>PDF খুলছে...</span></div>
<div id="viewer"></div>
<div id="err"></div>

<script type="module">
import { getDocument, GlobalWorkerOptions } from 'file:///android_asset/pdfjs/pdf.min.mjs';
GlobalWorkerOptions.workerSrc = 'file:///android_asset/pdfjs/pdf.worker.min.mjs';

const DPR    = Math.min(window.devicePixelRatio || 1, 3);
const VW     = window.innerWidth || screen.width;
const loader = document.getElementById('loader');
const viewer = document.getElementById('viewer');
const errDiv = document.getElementById('err');

// base64 → Uint8Array
const b64str = `$b64`;
const bin    = atob(b64str);
const buf    = new Uint8Array(bin.length);
for (let i = 0; i < bin.length; i++) buf[i] = bin.charCodeAt(i);

function matMul(m1, m2) {
  return [
    m1[0]*m2[0]+m1[2]*m2[1], m1[1]*m2[0]+m1[3]*m2[1],
    m1[0]*m2[2]+m1[2]*m2[3], m1[1]*m2[2]+m1[3]*m2[3],
    m1[0]*m2[4]+m1[2]*m2[5]+m1[4],
    m1[1]*m2[4]+m1[3]*m2[5]+m1[5]
  ];
}

async function renderPage(pdf, num) {
  const page    = await pdf.getPage(num);
  const natVP   = page.getViewport({ scale: 1 });
  const scale   = (VW / natVP.width) * DPR;
  const vp      = page.getViewport({ scale });

  const block   = document.createElement('div');
  block.className = 'page-block';
  block.style.height = Math.round(vp.height / DPR) + 'px';
  viewer.appendChild(block);

  const canvas  = document.createElement('canvas');
  canvas.width  = Math.round(vp.width);
  canvas.height = Math.round(vp.height);
  block.appendChild(canvas);

  await page.render({ canvasContext: canvas.getContext('2d'), viewport: vp }).promise;

  // Text layer for selection
  const textDiv = document.createElement('div');
  textDiv.className = 'textLayer';
  block.appendChild(textDiv);

  const tc = await page.getTextContent();
  tc.items.forEach(item => {
    if (!item.str || !item.str.trim()) return;
    const span = document.createElement('span');
    span.textContent = item.str;
    const tx = matMul(item.transform, vp.transform);
    span.style.left     = (tx[4] / DPR) + 'px';
    span.style.top      = ((vp.height - tx[5]) / DPR) + 'px';
    span.style.fontSize = (Math.abs(item.transform[3]) * scale / DPR) + 'px';
    textDiv.appendChild(span);
  });
}

try {
  const pdf = await getDocument({ data: buf.buffer, cMapPacked: true }).promise;
  loader.style.display = 'none';

  // First page immediately
  await renderPage(pdf, 1);

  // Rest in background
  for (let i = 2; i <= pdf.numPages; i++) {
    await renderPage(pdf, i);
  }
} catch(e) {
  loader.style.display = 'none';
  errDiv.style.display = 'flex';
  errDiv.textContent   = 'Error: ' + e.message;
}
</script>
</body>
</html>
""".trimIndent()

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
    val BG     = Color(0xFF0A0A0F)
    val INDIGO = Color(0xFF6C63FF)
    val scope  = rememberCoroutineScope()

    var isReading    by remember { mutableStateOf(true) }
    var readError    by remember { mutableStateOf(false) }
    var pdfBytes     by remember { mutableStateOf<ByteArray?>(null) }
    var webViewRef   by remember { mutableStateOf<WebView?>(null) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var mode         by remember { mutableStateOf(PdfMode.NONE) }
    var headerVisible by remember { mutableStateOf(true) }  // tap to hide/show

    // Read PDF bytes once
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

    // Load into WebView on mode change
    LaunchedEffect(mode, webViewRef) {
        val bytes = pdfBytes ?: return@LaunchedEffect
        val wv    = webViewRef ?: return@LaunchedEffect
        if (mode == PdfMode.NONE) return@LaunchedEffect

        wv.post {
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            when (mode) {
                PdfMode.NATIVE -> wv.loadData(b64, "application/pdf", "base64")
                PdfMode.PDFJS  -> wv.loadDataWithBaseURL(
                    "file:///android_asset/pdfjs/",
                    buildPdfJsHtml(b64),
                    "text/html", "UTF-8", null
                )
                else -> {}
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(BG)
    ) {
        when {
            // ── Loading ───────────────────────────────────────────────────────
            isReading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = INDIGO, strokeWidth = 3.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("PDF পড়া হচ্ছে...", color = Color(0xFF888899), fontSize = 13.sp)
                    }
                }
            }

            // ── Error ─────────────────────────────────────────────────────────
            readError || pdfUri == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("PDF খোলা যায়নি", color = Color(0xFFFF5C5C), fontSize = 14.sp)
                }
            }

            // ── Engine chooser ────────────────────────────────────────────────
            mode == PdfMode.NONE -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Back button top-left
                    Box(Modifier.fillMaxWidth()) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Text("📄", fontSize = 56.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        label,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("একটি Viewer বেছে নাও", color = Color(0xFF888899), fontSize = 12.sp)
                    Spacer(Modifier.height(32.dp))

                    // PDF.js — recommended (local, fast, text select)
                    Button(
                        onClick = { mode = PdfMode.PDFJS },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = INDIGO)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🟣  PDF.js  ⭐ Recommended", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text("High quality · Text select · Fast", fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f))
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Native
                    OutlinedButton(
                        onClick = { mode = PdfMode.NATIVE },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF888899))
                    ) {
                        Text("🔵  Native WebView", fontSize = 14.sp)
                    }
                }
            }

            // ── Viewer ────────────────────────────────────────────────────────
            else -> {
                // WebView — full screen, tap to toggle header
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
                                javaScriptEnabled        = true
                                domStorageEnabled        = true
                                allowFileAccess          = true
                                allowContentAccess       = true
                                builtInZoomControls      = true
                                displayZoomControls      = false
                                loadWithOverviewMode     = true
                                useWideViewPort          = true
                                setSupportZoom(true)
                                // Text selection support
                                @Suppress("DEPRECATION")
                                textZoom                 = 100
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

                // Progress bar
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

                // ── Compact header — tap viewer to toggle ─────────────────────
                AnimatedVisibility(
                    visible = headerVisible,
                    modifier = Modifier.align(Alignment.TopCenter),
                    enter = fadeIn() + slideInVertically(),
                    exit  = fadeOut() + slideOutVertically(),
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
                            Icon(Icons.Default.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        Text(
                            label,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                        )
                        // Engine chips
                        SmallEngineChip("🟣", mode == PdfMode.PDFJS, INDIGO) {
                            if (mode != PdfMode.PDFJS) mode = PdfMode.PDFJS
                        }
                        SmallEngineChip("🔵", mode == PdfMode.NATIVE, Color(0xFF2196F3)) {
                            if (mode != PdfMode.NATIVE) mode = PdfMode.NATIVE
                        }
                        IconButton(
                            onClick = {
                                val prev = mode; mode = PdfMode.NONE; mode = prev
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.Refresh, "Reload", tint = Color(0xFF888899), modifier = Modifier.size(18.dp))
                        }
                    }
                }

                // Hint: tap to show header (when hidden)
                if (!headerVisible) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(24.dp)
                            .background(Color.Transparent)
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallEngineChip(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    val bg = if (selected) color else color.copy(alpha = 0.15f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 14.sp)
    }
    Spacer(Modifier.width(2.dp))
}

// ── PDF.js HTML ───────────────────────────────────────────────────────────────
// Features:
//   • First page renders immediately (fast start)
//   • Remaining pages render progressively in background
//   • Full-width fit like WPS Office
//   • Text layer → text selection enabled
//   • High DPR-aware quality
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
  #viewer { width:100%; background:#424242; padding:0; }

  /* Each page block */
  .page-block {
    width:100%;
    position:relative;
    margin-bottom:6px;
    background:#fff;
    overflow:hidden;
  }
  .page-block canvas {
    display:block;
    width:100% !important;
    height:auto !important;
  }

  /* Text layer — enables copy/select */
  .textLayer {
    position:absolute;
    top:0; left:0; right:0; bottom:0;
    overflow:hidden;
    line-height:1;
  }
  .textLayer > span {
    color:transparent;
    position:absolute;
    white-space:pre;
    cursor:text;
    transform-origin:0% 0%;
    -webkit-user-select:text;
    user-select:text;
  }
  .textLayer ::selection {
    background:rgba(108,99,255,0.35);
  }
  #err {
    display:none; color:#ff5c5c;
    text-align:center; padding:32px;
    font-family:sans-serif; background:#1a1a2e;
  }
</style>
</head>
<body>
<div id="loader"><div class="spinner"></div><span>Loading PDF...</span></div>
<div id="viewer"></div>
<div id="err"></div>
<script type="module">
import { getDocument, GlobalWorkerOptions } from 'file:///android_asset/pdfjs/pdf.min.mjs';
GlobalWorkerOptions.workerSrc = 'file:///android_asset/pdfjs/pdf.worker.min.mjs';

const DPR    = Math.min(window.devicePixelRatio || 1, 3);
const VW     = window.innerWidth || screen.width;

// Decode base64 → Uint8Array
const b64str = `$b64`;
const bin    = atob(b64str);
const arr    = new Uint8Array(bin.length);
for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);

const loader = document.getElementById('loader');
const viewer = document.getElementById('viewer');
const errDiv = document.getElementById('err');

async function renderPage(pdf, pageNum) {
  const page  = await pdf.getPage(pageNum);

  // Scale so page fills screen width exactly
  const vpNatural = page.getViewport({ scale: 1 });
  const scale     = (VW / vpNatural.width) * DPR;
  const vp        = page.getViewport({ scale });

  // Block container
  const block = document.createElement('div');
  block.className = 'page-block';
  block.style.height = Math.round(vp.height / DPR) + 'px';
  viewer.appendChild(block);

  // Canvas
  const canvas = document.createElement('canvas');
  canvas.width  = Math.round(vp.width);
  canvas.height = Math.round(vp.height);
  block.appendChild(canvas);

  // Render canvas
  await page.render({ canvasContext: canvas.getContext('2d'), viewport: vp }).promise;

  // Text layer for selection
  const textDiv = document.createElement('div');
  textDiv.className = 'textLayer';
  block.appendChild(textDiv);
  const textContent = await page.getTextContent();
  textContent.items.forEach(item => {
    if (!item.str) return;
    const span = document.createElement('span');
    span.textContent = item.str;
    const tx = pdfjsLib_transform(item.transform, vp.transform);
    span.style.left     = (tx[4] / DPR) + 'px';
    span.style.top      = (tx[5] / DPR) + 'px';
    span.style.fontSize = Math.abs(tx[0] / DPR) + 'px';
    span.style.transform = 'scaleX(' + (tx[0] !== 0 ? tx[2] / tx[0] : 1) + ')';
    textDiv.appendChild(span);
  });
}

function pdfjsLib_transform(m1, m2) {
  return [
    m1[0]*m2[0] + m1[2]*m2[1],
    m1[1]*m2[0] + m1[3]*m2[1],
    m1[0]*m2[2] + m1[2]*m2[3],
    m1[1]*m2[2] + m1[3]*m2[3],
    m1[0]*m2[4] + m1[2]*m2[5] + m1[4],
    m1[1]*m2[4] + m1[3]*m2[5] + m1[5]
  ];
}

try {
  const pdf = await getDocument({ data: arr.buffer, cMapPacked: true }).promise;
  loader.style.display = 'none';

  // Render first page immediately → fast start
  await renderPage(pdf, 1);

  // Render remaining pages progressively
  for (let i = 2; i <= pdf.numPages; i++) {
    await renderPage(pdf, i);
  }
} catch(e) {
  loader.style.display = 'none';
  errDiv.style.display = 'block';
  errDiv.textContent   = 'Error: ' + e.message;
}
</script>
</body>
</html>
""".trimIndent()

package com.rasel.RasFocus.filemanager

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
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

class WebViewPdfActivity : ComponentActivity() {
    companion object {
        const val EXTRA_LAYER_LABEL = "LAYER_LABEL"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pdfUri: Uri? = intent.data
        val label = intent.getStringExtra(EXTRA_LAYER_LABEL) ?: "PDF Viewer"
        setContent {
            WebViewPdfScreen(
                pdfUri   = pdfUri,
                label    = label,
                onBack   = { finish() },
                activity = this,
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

    var loadProgress by remember { mutableIntStateOf(0) }
    var webViewRef   by remember { mutableStateOf<WebView?>(null) }

    val pdfUriString = pdfUri?.toString() ?: ""

    LaunchedEffect(webViewRef, pdfUriString) {
        val wv = webViewRef ?: return@LaunchedEffect
        if (pdfUriString.isEmpty()) return@LaunchedEffect
        wv.post {
            val encodedUri = android.util.Base64.encodeToString(
                pdfUriString.toByteArray(), android.util.Base64.NO_WRAP
            )
            wv.loadDataWithBaseURL(
                "https://appassets.androidplatform.net/",
                buildHtml(encodedUri),
                "text/html", "UTF-8", null
            )
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
            if (pdfUri == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("PDF খোলা যায়নি", color = Color(0xFFFF5C5C))
                }
            } else {
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
                                // initial scale = 100% — zoom হয়ে থাকবে না
                                textZoom             = 100
                                cacheMode            = android.webkit.WebSettings.LOAD_DEFAULT
                            }
                            wv.setInitialScale(0) // system decides — fit to screen
                            wv.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

                            wv.webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    loadProgress = newProgress
                                }
                            }

                            wv.webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val url = request?.url ?: return null
                                    if (url.path?.startsWith("/pdf-stream/") == true) {
                                        val b64 = url.path!!.removePrefix("/pdf-stream/")
                                        return try {
                                            val uriStr = String(
                                                android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                                            )
                                            val uri = Uri.parse(uriStr)
                                            val stream = when (uri.scheme) {
                                                "content" -> activity.contentResolver.openInputStream(uri)
                                                "file"    -> java.io.FileInputStream(uri.path!!)
                                                else      -> null
                                            }
                                            stream?.let {
                                                WebResourceResponse("application/pdf", null, it)
                                            }
                                        } catch (e: Exception) { null }
                                    }
                                    return null
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

private fun buildHtml(encodedUri: String): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, minimum-scale=1.0, maximum-scale=8.0, user-scalable=yes">
<style>
  * { margin:0; padding:0; box-sizing:border-box; }

  html, body {
    width:100%;
    overflow-x:hidden;
    background:#1C1C1E;
  }

  #loader {
    display:flex; flex-direction:column; align-items:center;
    justify-content:center; height:100vh;
    color:#aaa; font-family:sans-serif; font-size:14px; gap:12px;
  }
  .spinner {
    width:40px; height:40px;
    border:3px solid #333; border-top-color:#6C63FF;
    border-radius:50%; animation:spin .7s linear infinite;
  }
  @keyframes spin { to { transform:rotate(360deg); } }

  #viewer {
    width:100%;
    display:flex;
    flex-direction:column;
    align-items:center;
    gap:6px;
    padding:6px 0;
    background:#1C1C1E;
  }

  .page-slot {
    width:100%;
    display:flex;
    justify-content:center;
    background:#1C1C1E;
  }

  /* placeholder shown before render */
  .page-placeholder {
    width:100%;
    background:#2A2A2E;
    display:flex;
    align-items:center;
    justify-content:center;
    color:#555;
    font-family:sans-serif;
    font-size:12px;
  }

  canvas {
    display:block;
    /* width fills screen exactly — no left/right cut */
    width:100% !important;
    height:auto !important;
    background:#fff;
    box-shadow:0 1px 4px rgba(0,0,0,0.5);
  }

  #err {
    display:none; color:#ff5c5c; padding:32px;
    font-family:sans-serif; font-size:13px; text-align:center;
  }
</style>
</head>
<body>

<div id="loader">
  <div class="spinner"></div>
  <span id="loader-text">PDF লোড হচ্ছে...</span>
</div>
<div id="viewer" style="display:none"></div>
<div id="err"></div>

<script type="module">
import { getDocument, GlobalWorkerOptions }
  from 'https://cdnjs.cloudflare.com/ajax/libs/pdf.js/4.4.168/pdf.min.mjs';

GlobalWorkerOptions.workerSrc =
  'https://cdnjs.cloudflare.com/ajax/libs/pdf.js/4.4.168/pdf.worker.min.mjs';

const loaderEl   = document.getElementById('loader');
const loaderText = document.getElementById('loader-text');
const viewerEl   = document.getElementById('viewer');
const errEl      = document.getElementById('err');

function showError(msg) {
  loaderEl.style.display = 'none';
  errEl.style.display    = 'block';
  errEl.textContent      = msg;
}

// ── IntersectionObserver for lazy render ─────────────────────────────────────
// Each page slot is rendered only when it enters the viewport (±300px margin)
const renderQueue = new Map(); // slotIndex → renderFn
const observer    = new IntersectionObserver((entries) => {
  for (const entry of entries) {
    if (entry.isIntersecting) {
      const idx = parseInt(entry.target.dataset.page);
      const fn  = renderQueue.get(idx);
      if (fn) {
        renderQueue.delete(idx);
        observer.unobserve(entry.target);
        fn();
      }
    }
  }
}, { rootMargin: '400px 0px' }); // pre-render 400px before entering viewport

try {
  const b64Uri  = `$encodedUri`;
  const pdfUrl  = '/pdf-stream/' + b64Uri;

  const loadTask = getDocument({
    url:              pdfUrl,
    rangeChunkSize:   65536,
    disableAutoFetch: false,
    disableStream:    false,
  });

  loadTask.onProgress = ({ loaded, total }) => {
    if (total > 0)
      loaderText.textContent = 'লোড হচ্ছে... ' + Math.round(loaded / total * 100) + '%';
  };

  const pdf = await loadTask.promise;

  loaderEl.style.display  = 'none';
  viewerEl.style.display  = 'flex';

  const DPR = window.devicePixelRatio || 1;

  // ── Build all page slots first (instant skeleton) ─────────────────────────
  for (let i = 1; i <= pdf.numPages; i++) {
    const slot       = document.createElement('div');
    slot.className   = 'page-slot';
    slot.dataset.page = i;

    // Placeholder height — A4 ratio 1:1.414
    const ph         = document.createElement('div');
    ph.className     = 'page-placeholder';
    ph.style.height  = Math.round(window.innerWidth * 1.414) + 'px';
    ph.textContent   = i + ' / ' + pdf.numPages;
    slot.appendChild(ph);
    viewerEl.appendChild(slot);

    // Queue lazy render
    const pageNum = i;
    renderQueue.set(i, async () => {
      try {
        const page     = await pdf.getPage(pageNum);

        // Scale so page width = screen width (no left/right cut)
        const vp0      = page.getViewport({ scale: 1 });
        const scale    = (window.innerWidth * DPR) / vp0.width;
        const viewport = page.getViewport({ scale });

        const canvas   = document.createElement('canvas');
        canvas.width   = viewport.width;
        canvas.height  = viewport.height;
        // CSS: width=100% set via stylesheet, height=auto

        await page.render({
          canvasContext: canvas.getContext('2d'),
          viewport,
        }).promise;

        page.cleanup();
        slot.innerHTML = '';
        slot.appendChild(canvas);
      } catch(e) {
        slot.innerHTML = '<div style="color:#f66;padding:8px;font-size:12px">Page ' + pageNum + ' error</div>';
      }
    });

    observer.observe(slot);
  }

  // ── Render first 2 pages immediately (no wait for scroll) ─────────────────
  for (let i = 1; i <= Math.min(2, pdf.numPages); i++) {
    const fn = renderQueue.get(i);
    if (fn) {
      renderQueue.delete(i);
      const slot = viewerEl.querySelector('[data-page="' + i + '"]');
      if (slot) observer.unobserve(slot);
      fn();
    }
  }

} catch(e) {
  showError('PDF খোলা যায়নি: ' + e.message);
}
</script>
</body>
</html>
""".trimIndent()

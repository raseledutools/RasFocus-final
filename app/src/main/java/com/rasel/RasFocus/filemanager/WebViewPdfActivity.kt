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

    // PDF URI কে stable string হিসেবে রাখি
    val pdfUriString = pdfUri?.toString() ?: ""

    // WebView ready হলেই load — কোনো IO wait নেই, stream করবে
    LaunchedEffect(webViewRef, pdfUriString) {
        val wv = webViewRef ?: return@LaunchedEffect
        if (pdfUriString.isEmpty()) return@LaunchedEffect
        wv.post {
            // PDF URI টা একটা fake https URL হিসেবে pass করছি JS-এ
            // shouldInterceptRequest সেটা intercept করে ContentResolver দিয়ে stream দেবে
            val encodedUri = android.util.Base64.encodeToString(
                pdfUriString.toByteArray(), android.util.Base64.NO_WRAP
            )
            wv.loadDataWithBaseURL(
                "https://appassets.androidplatform.net/",
                buildHtml(encodedUri),
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
                                // Cache PDF.js CDN — দ্বিতীয়বার instant
                                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                            }
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

                                    // /pdf-stream/ path → decode URI → stream from ContentResolver
                                    if (url.path?.startsWith("/pdf-stream/") == true) {
                                        val b64segment = url.path!!.removePrefix("/pdf-stream/")
                                        return try {
                                            val uriStr = String(
                                                android.util.Base64.decode(b64segment, android.util.Base64.NO_WRAP)
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

                                    // /pdfjs/ → assets (offline fallback)
                                    if (url.path?.startsWith("/pdfjs/") == true) {
                                        val asset = url.path!!.removePrefix("/")
                                        return try {
                                            WebResourceResponse(
                                                "text/javascript", "UTF-8",
                                                activity.assets.open(asset)
                                            )
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
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=8.0, user-scalable=yes">
<style>
  * { margin:0; padding:0; box-sizing:border-box; }
  html, body { width:100%; background:#1C1C1E; }

  #loader {
    display:flex; flex-direction:column; align-items:center;
    justify-content:center; height:100vh;
    color:#aaa; font-family:sans-serif; font-size:14px; gap:12px;
  }
  .spinner {
    width:40px; height:40px; border:3px solid #333;
    border-top-color:#6C63FF; border-radius:50%;
    animation:spin .7s linear infinite;
  }
  @keyframes spin { to { transform:rotate(360deg); } }

  #viewer {
    width:100%;
    display:flex;
    flex-direction:column;
    align-items:center;
    padding: 8px 0;
    gap: 6px;
    background:#1C1C1E;
  }

  .page-container {
    width: 100%;
    display:flex;
    justify-content:center;
  }

  canvas {
    display:block;
    /* CSS size = physical pixels / DPR → sharp on all screens */
    box-shadow: 0 1px 6px rgba(0,0,0,0.6);
    background:#fff;
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
  <span id="loader-text">PDF.js লোড হচ্ছে...</span>
</div>
<div id="viewer" style="display:none"></div>
<div id="err"></div>

<script type="module">
  // CDN — cached by WebView after first load, instant on repeat opens
  import { getDocument, GlobalWorkerOptions }
    from 'https://cdnjs.cloudflare.com/ajax/libs/pdf.js/4.4.168/pdf.min.mjs';

  GlobalWorkerOptions.workerSrc =
    'https://cdnjs.cloudflare.com/ajax/libs/pdf.js/4.4.168/pdf.worker.min.mjs';

  const DPR        = window.devicePixelRatio || 1;
  // Use 1.5x CSS pixel scale — canvas physical pixels = 1.5 * DPR
  // Gives sharp crisp text on all screen densities
  const CSS_SCALE  = 1.5;
  const PHYS_SCALE = CSS_SCALE * DPR;

  const loaderText = document.getElementById('loader-text');
  const viewer     = document.getElementById('viewer');
  const loader     = document.getElementById('loader');
  const errDiv     = document.getElementById('err');

  function showError(msg) {
    loader.style.display   = 'none';
    viewer.style.display   = 'none';
    errDiv.style.display   = 'block';
    errDiv.textContent     = msg;
  }

  try {
    // Decode the URI passed from Kotlin
    const b64Uri  = `$encodedUri`;
    const pdfUrl  = '/pdf-stream/' + b64Uri;

    loaderText.textContent = 'PDF খোলা হচ্ছে...';

    const loadingTask = getDocument({
      url:              pdfUrl,
      rangeChunkSize:   65536,   // 64KB chunks — fast range loading
      disableAutoFetch: false,
      disableStream:    false,
    });

    // Show page count while loading
    loadingTask.onProgress = function(data) {
      if (data.total > 0) {
        const pct = Math.round(data.loaded / data.total * 100);
        loaderText.textContent = 'লোড হচ্ছে... ' + pct + '%';
      }
    };

    const pdf = await loadingTask.promise;
    loaderText.textContent = 'রেন্ডার হচ্ছে... (মোট ' + pdf.numPages + ' পাতা)';

    // Show viewer, hide loader
    loader.style.display = 'none';
    viewer.style.display = 'flex';

    // Render page-by-page — first page first for instant preview
    for (let pageNum = 1; pageNum <= pdf.numPages; pageNum++) {
      const page       = await pdf.getPage(pageNum);
      const viewport   = page.getViewport({ scale: PHYS_SCALE });

      const container  = document.createElement('div');
      container.className = 'page-container';

      const canvas     = document.createElement('canvas');
      const ctx        = canvas.getContext('2d');

      // Physical pixel size
      canvas.width     = viewport.width;
      canvas.height    = viewport.height;

      // CSS display size — browser scales down by DPR → sharp
      canvas.style.width  = (viewport.width  / DPR) + 'px';
      canvas.style.height = (viewport.height / DPR) + 'px';

      container.appendChild(canvas);
      viewer.appendChild(container);

      await page.render({ canvasContext: ctx, viewport: viewport }).promise;
      page.cleanup();  // free memory after each page render
    }

  } catch(e) {
    showError('PDF খোলা যায়নি: ' + e.message);
  }
</script>
</body>
</html>
""".trimIndent()

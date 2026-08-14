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

    var isReading  by remember { mutableStateOf(true) }
    var readError  by remember { mutableStateOf(false) }
    var pdfBytes   by remember { mutableStateOf<ByteArray?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var loadProgress by remember { mutableIntStateOf(0) }

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

    // Once bytes ready + webview ready → load PDF directly
    LaunchedEffect(pdfBytes, webViewRef) {
        val bytes = pdfBytes ?: return@LaunchedEffect
        val wv    = webViewRef ?: return@LaunchedEffect
        wv.post {
            // loadData with application/pdf — Android WebView handles this natively
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            wv.loadData(b64, "application/pdf", "base64")
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
                        pdfBytes?.let { bytes ->
                            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            webViewRef?.loadData(b64, "application/pdf", "base64")
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
                                wv.webViewClient = WebViewClient()
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

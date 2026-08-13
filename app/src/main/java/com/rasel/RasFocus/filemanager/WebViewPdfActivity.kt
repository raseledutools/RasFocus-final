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

    var loadProgress by remember { mutableIntStateOf(0) }
    var webViewRef   by remember { mutableStateOf<WebView?>(null) }
    var errorMsg     by remember { mutableStateOf<String?>(null) }

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
                    Text("PDF খোলা যায়নি", color = Color(0xFFFF5C5C), fontSize = 14.sp)
                }
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory  = { ctx ->
                        WebView(ctx).also { wv ->
                            webViewRef = wv
                            wv.settings.apply {
                                javaScriptEnabled       = true
                                domStorageEnabled       = true
                                allowFileAccess         = true
                                allowContentAccess      = true
                                builtInZoomControls     = true
                                displayZoomControls     = false
                                loadWithOverviewMode    = true
                                useWideViewPort         = true
                                setSupportZoom(true)
                                // Hardware acceleration for smooth scroll/zoom
                            }
                            wv.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

                            wv.webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    loadProgress = newProgress
                                }
                            }

                            wv.webViewClient = object : WebViewClient() {
                                // Intercept content:// URI — serve PDF bytes directly to WebView
                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val url = request?.url ?: return null
                                    if (url.scheme == "content") {
                                        return try {
                                            val stream: InputStream? =
                                                contentResolver.openInputStream(url)
                                            WebResourceResponse(
                                                "application/pdf",
                                                null,
                                                stream
                                            )
                                        } catch (e: Exception) { null }
                                    }
                                    return null
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: android.webkit.WebResourceError?
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        errorMsg = "লোড হয়নি: ${error?.description}"
                                    }
                                }
                            }

                            // Load directly — WebView Chrome engine handles PDF natively
                            wv.loadUrl(pdfUri.toString())
                        }
                    }
                )

                // Progress bar
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

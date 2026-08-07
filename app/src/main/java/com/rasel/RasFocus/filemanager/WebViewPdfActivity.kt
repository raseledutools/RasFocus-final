package com.rasel.RasFocus.filemanager

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
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

/**
 * PDF Layer 3 – WebView (Google Docs embedded viewer).
 * Receives intent with ACTION_VIEW + data URI (content:// or file://).
 * Falls back gracefully if no network.
 *
 * Extras accepted:
 *   • Intent.data (Uri) — content:// or file:// URI of the PDF
 *   • "LAYER_LABEL" (String) — optional display label shown in toolbar
 */
class WebViewPdfActivity : ComponentActivity() {

    companion object {
        const val EXTRA_LAYER_LABEL = "LAYER_LABEL"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pdfUri: Uri? = intent.data
        val label: String = intent.getStringExtra(EXTRA_LAYER_LABEL) ?: "WebView PDF"

        setContent {
            WebViewPdfScreen(
                pdfUri = pdfUri,
                label  = label,
                onBack = { finish() }
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebViewPdfScreen(
    pdfUri: Uri?,
    label:  String,
    onBack: () -> Unit,
) {
    val BG    = Color(0xFF0A0A0F)
    val INDIGO = Color(0xFF6C63FF)

    var loadProgress by remember { mutableIntStateOf(0) }
    var webViewRef   by remember { mutableStateOf<WebView?>(null) }
    var errorMsg     by remember { mutableStateOf<String?>(null) }

    // Build Google Docs viewer URL
    val viewerUrl = remember(pdfUri) {
        when {
            pdfUri == null -> null
            pdfUri.scheme == "content" || pdfUri.scheme == "file" -> {
                // For local content:// URIs we use the direct Google Docs viewer
                // (requires internet; offline PDFs are served via content resolver)
                "https://docs.google.com/gview?embedded=true&url=" +
                        Uri.encode(pdfUri.toString())
            }
            else -> pdfUri.toString()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(label, fontSize = 14.sp, color = Color.White)
                        Text("🌐 WebView Layer", fontSize = 11.sp, color = INDIGO)
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
            if (viewerUrl == null) {
                // No URI provided
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("PDF URI পাওয়া যায়নি", color = Color(0xFFFF5C5C), fontSize = 14.sp)
                }
            } else {
                // WebView
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).also { wv ->
                            webViewRef = wv
                            wv.settings.apply {
                                javaScriptEnabled     = true
                                domStorageEnabled     = true
                                builtInZoomControls   = true
                                displayZoomControls   = false
                                loadWithOverviewMode  = true
                                useWideViewPort       = true
                                setSupportZoom(true)
                            }
                            wv.webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    loadProgress = newProgress
                                }
                            }
                            wv.webViewClient = object : WebViewClient() {
                                override fun onReceivedError(
                                    view: WebView?,
                                    errorCode: Int,
                                    description: String?,
                                    failingUrl: String?
                                ) {
                                    errorMsg = "লোড হয়নি: $description"
                                }
                            }
                            wv.loadUrl(viewerUrl)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Loading bar
                if (loadProgress < 100) {
                    LinearProgressIndicator(
                        progress = { loadProgress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .align(Alignment.TopCenter),
                        color    = INDIGO
                    )
                }

                // Error overlay
                errorMsg?.let { msg ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(BG.copy(alpha = 0.85f)),
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

package com.rasel.RasFocus.tutorial

// ============================================================
//  Rajib Tutorial — Website inside the app
//
//  Loads the live Rajib Tutorial website so the app always shows
//  exactly what the website shows: sidebar, Live Class, Live Exam,
//  course catalogue, Premium login/payment, Admin panel.
//  Any change published to the website appears in the app instantly.
//
//  - FLAG_SECURE is on while this screen is open (no screenshots/recording)
//  - Back button: closes popup -> closes sidebar -> goes Home -> leaves screen
//  - External links (Google Meet, Zoom, YouTube...) open outside the app
//  - Fullscreen video is supported
// ============================================================

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat

private const val TUTORIAL_URL  = "https://raseledutools.github.io/rajib-tutorial.html"
private const val TUTORIAL_HOST = "raseledutools.github.io"
private val WebBrandPink = Color(0xFFE8365D)

// Runs inside the website. Returns "handled" if it closed something, else "exit".
private const val BACK_JS = """
(function(){
  try{
    var open=[].slice.call(document.querySelectorAll('.modal-bg')).filter(function(m){return !m.classList.contains('hidden')});
    if(open.length){open.forEach(function(m){m.classList.add('hidden')});return 'handled';}
    var sb=document.getElementById('sidebar');
    if(sb&&sb.classList.contains('open')){if(window.closeSidebar){window.closeSidebar();}return 'handled';}
    var v=document.querySelector('.view.active');
    if(v&&v.id!=='viewHome'&&window.goHome){window.goHome();return 'handled';}
  }catch(e){}
  return 'exit';
})()
"""

/** Opens a link outside the app (browser, Meet, Zoom, YouTube, dialer...). */
private fun openOutside(ctx: Context, uri: Uri) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        // no app can handle this link
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RajibTutorialWebView(onBack: () -> Unit) {
    val context = LocalContext.current
    val hostView = LocalView.current

    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var hasError by remember { mutableStateOf(false) }
    var customView by remember { mutableStateOf<View?>(null) }
    var customCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    // Protect paid videos (no screenshots / screen recording) + dark status-bar icons on the white top bar
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val controller = window?.let { WindowCompat.getInsetsController(it, hostView) }
        val previousLight = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = true
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (controller != null && previousLight != null) {
                controller.isAppearanceLightStatusBars = previousLight
            }
            webView?.destroy()
            webView = null
        }
    }

    BackHandler {
        val wv = webView
        when {
            customView != null -> {
                customCallback?.onCustomViewHidden()
                customView = null
                customCallback = null
            }
            wv == null || hasError -> onBack()
            else -> {
                val current = wv.url ?: ""
                if (current.startsWith("http") && !current.contains(TUTORIAL_HOST) && wv.canGoBack()) {
                    wv.goBack() // e.g. leaving the bKash payment page
                } else {
                    wv.evaluateJavascript(BACK_JS) { result ->
                        if (result == null || result.contains("exit")) onBack()
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(android.graphics.Color.WHITE)

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        javaScriptCanOpenWindowsAutomatically = true
                        setSupportMultipleWindows(true)
                        allowFileAccess = false
                        allowContentAccess = false
                        useWideViewPort = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        userAgentString = userAgentString + " RasFocusApp/1"
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            hasError = false
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) hasError = true
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val uri = request?.url ?: return false
                            val scheme = uri.scheme?.lowercase() ?: return false
                            if (scheme == "http" || scheme == "https") return false // stay in the app
                            openOutside(ctx, uri) // tel:, mailto:, whatsapp:, market: ...
                            return true
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }

                        override fun onShowCustomView(
                            view: View?,
                            callback: WebChromeClient.CustomViewCallback?
                        ) {
                            customView = view
                            customCallback = callback
                        }

                        override fun onHideCustomView() {
                            customView = null
                            customCallback = null
                        }

                        // target="_blank" links (Join Live Class, etc.) open outside the app
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message?
                        ): Boolean {
                            val msg = resultMsg ?: return false
                            val transport = msg.obj as? WebView.WebViewTransport ?: return false
                            val temp = WebView(ctx)
                            var opened = false
                            temp.webViewClient = object : WebViewClient() {
                                private fun handOff(uri: Uri?) {
                                    if (!opened && uri != null) {
                                        opened = true
                                        openOutside(ctx, uri)
                                    }
                                }

                                override fun shouldOverrideUrlLoading(
                                    v: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    handOff(request?.url)
                                    return true
                                }

                                override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                                    handOff(url?.let { Uri.parse(it) })
                                    v?.stopLoading()
                                }
                            }
                            transport.webView = temp
                            msg.sendToTarget()
                            return true
                        }
                    }

                    loadUrl(TUTORIAL_URL)
                    webView = this
                }
            }
        )

        if (progress in 1..99 && !hasError) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
                color = WebBrandPink,
                trackColor = Color.Transparent
            )
        }

        if (hasError) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = WebBrandPink,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "No internet connection",
                    color = Color(0xFF14121A),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Please check your connection and try again.",
                    color = Color(0xFF4D4858),
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        hasError = false
                        val wv = webView
                        val u = wv?.url
                        if (u.isNullOrBlank() || u == "about:blank") wv?.loadUrl(TUTORIAL_URL) else wv?.reload()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WebBrandPink)
                ) {
                    Text("Retry", color = Color.White, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onBack) {
                    Text("Go back", color = Color(0xFF4D4858))
                }
            }
        }

        // Fullscreen video (YouTube etc.)
        customView?.let { cv ->
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                factory = {
                    (cv.parent as? ViewGroup)?.removeView(cv)
                    cv
                }
            )
        }
    }
}

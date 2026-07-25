package com.rasel.RasFocus.combo.selfcontrol.familybrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.ByteArrayInputStream

/**
 * YoutubeActivity — পুরো native YouTube app এর মতো অভিজ্ঞতা
 */
class YoutubeActivity : ComponentActivity() {

    private var webView: WebView? = null

    // ★ Mini Player Home WebView: back press বা swipe down এ video mini player এ গেলে
    // Activity টা বন্ধ হয় না — এই WebView YouTube home page দেখায় (footer সহ)।
    // Mini player tap করলে এটা লুকিয়ে video WebView ফিরে আসে।
    private var homeWebView: WebView? = null

    // rootFrame reference — mini player mode এ home WebView attach/detach করতে
    private var rootFrameRef: FrameLayout? = null

    // Feed/search এ visible content (thumbnails, titles, alt-text) scan করে
    // adult content ধরার জন্য — AdBlocker.kt এর existing multi-layer scanner
    private val adBlocker by lazy { com.rasel.RasFocus.combo.selfcontrol.familybrowser.AdBlocker(this) }
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // FIX: shouldOverrideUrlLoading/shouldInterceptRequest এ URL-level check এ
    // adult content block হয়ে যাওয়ার পরেও onPageFinished এর title-check safety
    // net সেই একই navigation এ আবার নিজে থেকে block page বসিয়ে দিত — ফলে ইউজার
    // পরপর দুইবার black block screen দেখতো। এই flag দিয়ে ট্র্যাক করি যে এই
    // navigation-এ ইতিমধ্যে একবার URL-level এ block হয়েছে কিনা; হলে
    // onPageFinished এর দ্বিতীয় check স্কিপ করে দেয়।
    private var adultBlockAlreadyShownForThisLoad = false

    // Mini player চালু আছে কিনা track করার জন্য
    private var isMiniPlayerActive = false

    // ── LAYER 2: Wake Lock ─────────────────────────────────────────────────────
    private var wakeLock: PowerManager.WakeLock? = null

    // ── Notification Controls Receiver ────────────────────────────────────────
    // BackgroundAudioService থেকে broadcast এসে WebView-এ JS inject করে
    private val playbackControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != com.rasel.RasFocus.combo.selfcontrol.familybrowser.service.BackgroundAudioService.BROADCAST_PLAYBACK_ACTION) return
            val wv = webView ?: return
            when (intent.getStringExtra(com.rasel.RasFocus.combo.selfcontrol.familybrowser.service.BackgroundAudioService.EXTRA_PLAYBACK_CMD)) {
                "play"    -> wv.evaluateJavascript("(function(){ try{ var v=document.querySelector('video'); if(v) v.play().catch(function(){}); }catch(e){} })()", null)
                "pause"   -> wv.evaluateJavascript("(function(){ try{ var v=document.querySelector('video'); if(v) v.pause(); }catch(e){} })()", null)
                "stop"    -> wv.evaluateJavascript("(function(){ try{ var v=document.querySelector('video'); if(v) v.pause(); }catch(e){} })()", null)
                "rewind"  -> wv.evaluateJavascript("(function(){ try{ var v=document.querySelector('video'); if(v) v.currentTime=Math.max(0,v.currentTime-10); }catch(e){} })()", null)
                "forward" -> wv.evaluateJavascript("(function(){ try{ var v=document.querySelector('video'); if(v) v.currentTime=Math.min(v.duration,v.currentTime+10); }catch(e){} })()", null)
                "prev"    -> wv.evaluateJavascript("""
                    (function(){
                        try{
                            var btn = document.querySelector('.ytp-prev-button, [aria-label="Previous video"], .ytm-prev-button');
                            if(btn){ btn.click(); return; }
                            history.back();
                        }catch(e){}
                    })()""".trimIndent(), null)
                "next"    -> wv.evaluateJavascript("""
                    (function(){
                        try{
                            var btn = document.querySelector('.ytp-next-button, [aria-label="Next video"], .ytm-next-button');
                            if(btn){ btn.click(); return; }
                        }catch(e){}
                    })()""".trimIndent(), null)
            }
        }
    }

    // ── LAYER 1: Screen Off Receiver ──────────────────────────────────────────
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    val wv = webView
                    if (wv != null && !isMiniPlayerActive) {
                        // ★ FIX: Lock button চাপার আগেই visibility spoof inject করো
                        // যাতে YouTube pause না করে
                        injectVisibilitySpoofBeforeLeave(wv)
                        injectYoutubeHacksForced(wv)
                        wv.resumeTimers()
                        wv.onResume()

                        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                            android.provider.Settings.canDrawOverlays(ctx)
                        else true

                        if (hasOverlay) {
                            // ★ FIX: JS check skip করো — সরাসরি floating launch করো
                            // JS async হওয়ায় screen off এর পরে result আসে না নিশ্চিতভাবে
                            // তাই সবসময় floating এ দাও; audio চলতে থাকবে
                            launchFloatingOnLock(wv)
                        } else {
                            // Overlay permission নেই — শুধু audio service
                            startBgAudioService()
                        }
                    } else if (isMiniPlayerActive) {
                        // ★ FIX: Mini player চলাকালীন lock — audio চলতে থাকবে,
                        // floating service ইতিমধ্যে WebView ধরে রেখেছে
                        startBgAudioService()
                    }
                }

                Intent.ACTION_SCREEN_ON -> {
                    // Screen on — user unlock না করা পর্যন্ত কিছু করবো না
                }

                Intent.ACTION_USER_PRESENT -> {
                    // ★ FIX: Unlock করলে floating থেকে WebView ফিরিয়ে আনো
                    // isMiniPlayerActive = true মানে WebView এখন floating service এ আছে
                    // onResume() এ সঠিকভাবে WebView re-attach হবে
                    // এখানে শুধু service থামানো ও flag set করলেই onResume() বাকি কাজ করবে
                    if (isMiniPlayerActive) {
                        // onResume() call হবে যখন activity visible হবে — সেখানেই WebView re-attach হয়
                        // এখানে কিছু করার দরকার নেই; onResume() এ isMiniPlayerActive check করা আছে
                    } else {
                        // Floating ছাড়াই lock হয়েছিল (overlay ছিল না বা video চলছিল না)
                        val wv = webView
                        if (wv != null) {
                            stopBgAudioService()
                            wv.resumeTimers()
                            wv.onResume()
                            wv.visibility = View.VISIBLE
                            wv.alpha = 1f
                        }
                    }
                }
            }
        }
    }

    /**
     * ★ নতুন: Lock button এ floating launch।
     * launchFloatingDirectly এর মতো কিন্তু JS async result এর উপর নির্ভর করে না।
     * WebView সরাসরি service এ পাঠায়, activity background এ যায়।
     */
    private fun launchFloatingOnLock(wv: WebView) {
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            android.provider.Settings.canDrawOverlays(this)
        else true
        if (!hasOverlay) {
            startBgAudioService()
            return
        }

        val currentUrl   = wv.url   ?: "https://m.youtube.com"
        val currentTitle = wv.title ?: "YouTube"

        injectVisibilitySpoofBeforeLeave(wv)

        // WebView service এ দাও — reload হবে না
        com.rasel.RasFocus.combo.selfcontrol.familybrowser.service.YoutubeFloatingWindowService.pendingWebView = wv
        com.rasel.RasFocus.combo.selfcontrol.familybrowser.service.YoutubeFloatingWindowService.launchNoReload(
            this, currentUrl, currentTitle
        )

        webView = null         // Activity তে reference রাখো না
        isMiniPlayerActive = true

        // Activity কে background এ পাঠাও
        moveTaskToBack(true)

        startBgAudioService()
    }

    companion object {
        private const val YT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.6367.82 Mobile Safari/537.36"

        // FIX: এই list আগে এখানেই hardcoded ছিল, তাই নতুন keyword যোগ করতে হলে
        // app update লাগতো। এখন এটা FirebaseKeywordSync (Firebase Realtime DB এর
        // keyword_data/adult_keywords node) থেকে আসে — main browser এর AdBlocker
        // এবং FacebookActivity এর সাথেও এখন একই central list শেয়ার হয়, তাই আলাদা
        // আলাদা জায়গায় sync রাখার ঝামেলা নেই। Firebase console এ keyword যোগ/বাদ
        // দিলেই — কোনো app update ছাড়াই — YouTube search bar এ সাথে সাথে reflect হয়।
        private val ADULT_SEARCH_KEYWORDS: Set<String>
            get() = com.rasel.RasFocus.selfcontrol.FirebaseKeywordSync.getAdultKeywords()

        private val AD_SERVERS = setOf(
            "googleads.g.doubleclick.net", "pagead2.googlesyndication.com",
            "pubads.g.doubleclick.net", "adservice.google.com",
            "googleadservices.com", "ad.doubleclick.net",
            "amazon-adsystem.com", "adsystem.amazon.com",
            "moatads.com", "adsafeprotected.com",
            "securepubads.g.doubleclick.net"
        )

        private val YT_AD_ENDPOINTS = listOf(
            "/api/stats/ads", "/pagead/adview", "/ptracking",
            "/api/stats/qoe?", "/pagead/paralleladload",
            "/pagead/viewthroughconversion", "/pagead/interaction",
            "/pagead/adformat", "/annotations_auth", "/get_midroll_info",
            "/api/stats/delayplay", "/api/stats/atr",
            "youtubei/v1/player/ad_break", "youtubei/v1/ad_break",
            "imasdk.googleapis.com/js/sdkloader",
            "imasdk.googleapis.com/admob"
        )

        private val AD_URL_PATTERNS = listOf(
            "/pagead/", "/ads/", "/adview/", "adformat=",
            "//ad.", "//ads.", "//adserver.", "//adservice.",
            "tracking_pixel", "track/click", "ad_impression",
            "affiliates/", "click.php?aff", "bannerfarm",
            "adrotate", "sponsored_links"
        )

        fun launch(activity: Activity) {
            val intent = Intent(activity, YoutubeActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )
        window.statusBarColor  = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        // FIX (startup speed): black frame টা আগে setContentView দিয়ে স্ক্রিনে
        // বসিয়ে দিচ্ছি, তারপর receiver registration ও wakelock acquire করছি।
        // আগে এই non-UI কাজগুলো setContentView এর আগে হতো, ফলে প্রথম ফ্রেম
        // আঁকতে বাড়তি সময় লাগতো এবং app খুলতে ধীর মনে হতো — এখন ইউজার সাথে
        // সাথেই কালো স্ক্রিন দেখে (blank/white flash এর বদলে), আর WebView এর
        // load শুরু হয় পরের লাইনেই। কোনো ফিচার সরানো হয়নি, শুধু order পাল্টানো।
        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(0, systemBars.top, 0, systemBars.bottom)
                insets
            }
        }
        setContentView(rootFrame)
        rootFrameRef = rootFrame  // mini player home WebView এর জন্য

        // ★ Swipe down gesture → mini player
        // Video দেখার সময় নিচে swipe করলে corner mini player হবে
        setupSwipeDownGesture(rootFrame)

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenOffReceiver, screenFilter)

        // Notification controls receiver register করো
        val playbackFilter = IntentFilter(
            com.rasel.RasFocus.combo.selfcontrol.familybrowser.service.BackgroundAudioService.BROADCAST_PLAYBACK_ACTION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(playbackControlReceiver, playbackFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(playbackControlReceiver, playbackFilter)
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("WakelockTimeout")
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RasFocus:YoutubeAudioWakeLock"
        ).apply { acquire() }

        webView = object : WebView(this) {
            override fun onPause() { /* suppress */ }
            override fun pauseTimers() { /* suppress */ }
            override fun onResume() { super.onResume() }
        }.apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Android version অনুযায়ী সঠিক layer type:
            // Android 10 (API 29) এ inline <video> TextureView দিয়ে render হয়,
            // তাই LAYER_TYPE_HARDWARE লাগে — না হলে video frame black থাকে,
            // শুধু audio চলে। Android 11+ এ Chromium নিজেই SurfaceControl দিয়ে
            // compositor bypass করে, তাই LAYER_TYPE_NONE সেখানে সঠিক।
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
            } else {
                setLayerType(View.LAYER_TYPE_NONE, null)
            }
            setBackgroundColor(Color.BLACK)

            settings.apply {
                javaScriptEnabled                = true
                domStorageEnabled                = true
                databaseEnabled                  = true
                loadWithOverviewMode             = true
                useWideViewPort                  = true
                builtInZoomControls              = true
                displayZoomControls              = false
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess                  = true
                allowContentAccess               = true
                loadsImagesAutomatically         = true
                mixedContentMode                 = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                cacheMode                        = WebSettings.LOAD_DEFAULT
                userAgentString                  = YT_USER_AGENT
            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            // Block page এর "ফিরে যান" বাটন যাতে সত্যিকারের youtube.com এ ফিরে
            // যেতে পারে — এটা না থাকলে block page দেখানোর পর WebView চিরকালের
            // জন্য আটকে থাকতো, কোনো navigation/back কাজ করতো না।
            addJavascriptInterface(YtBlockBridge(this), "RasYtBlockBridge")
            addJavascriptInterface(
                com.rasel.RasFocus.combo.selfcontrol.familybrowser.AdBlocker.BlockOverlayBridge(
                    this, "https://m.youtube.com/", { block -> runOnUiThread(block) }
                ),
                "RasBlockBridge"
            )

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    // নতুন navigation শুরু — আগের load এর block-flag রিসেট করি
                    adultBlockAlreadyShownForThisLoad = false
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    injectVisibilitySpoof(view)
                    injectYoutubeHacks(view)
                    injectRemoveOpenInAppButton(view)

                    val prefs = getSharedPreferences("browser_settings", Context.MODE_PRIVATE)
                    // Layer 2: JS skip button + banner hide
                    if (prefs.getBoolean("yt_ad_layer2", true)) {
                        injectAdBlocker(view)
                    }
                    injectSettingsRemover(view)
                    // Layer 3: YouTube player এলাকায় ad DOM element forcefully hide করো।
                    // injectContentScanner() ডাকা হয় না (false-positive block হয়)।
                    // এর বদলে player এর ভেতরের ad overlay/banner/countdown সরানো হয়।
                    if (prefs.getBoolean("yt_ad_layer3", true)) {
                        injectYtAdLayerThree(view)
                    }

                    // FIX: এই navigation এ shouldOverrideUrlLoading/shouldInterceptRequest
                    // এ URL-level check করে ইতিমধ্যে একবার block page দেখানো হয়ে থাকলে,
                    // নিচের title-check আর চালানো হয় না — নাহলে একই block পরপর দুইবার
                    // (double black screen) দেখা যেত।
                    if (adultBlockAlreadyShownForThisLoad) return

                    // Second-layer safety net: shouldInterceptRequest only sees the
                    // request URL, not POST body — and YouTube's internal search
                    // sometimes sends the query inside a POST body rather than as a
                    // URL query param, which the network-level check above can't see.
                    // Re-checking the rendered page title after load catches that case,
                    // since a search-results page's title reliably reflects the query
                    // once YouTube's own JS has rendered it.
                    view.evaluateJavascript("(function(){return document.title;})();") { titleResult ->
                        val title = titleResult?.trim('"')?.lowercase() ?: return@evaluateJavascript
                        val matched = ADULT_SEARCH_KEYWORDS.any { title.contains(it.lowercase()) }
                        if (matched && !adultBlockAlreadyShownForThisLoad) {
                            adultBlockAlreadyShownForThisLoad = true
                            val blockedHtml = buildAdultSearchBlockedPage(title)
                            webView?.loadDataWithBaseURL(
                                "https://m.youtube.com/", blockedHtml, "text/html", "UTF-8", null
                            )
                        }
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val url  = request.url.toString()
                    val host = request.url?.host?.lowercase() ?: ""

                    val prefs = getSharedPreferences("browser_settings", Context.MODE_PRIVATE)

                    if (prefs.getBoolean("yt_ad_layer1", true)) {
                        // ── Check 1: Ad domain block ──────────────────────────
                        if (AD_SERVERS.any { host == it || host.endsWith(".$it") }) {
                            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                        }

                        // ── Check 2: YouTube-specific ad endpoints ────────────
                        if (host.contains("youtube.com") || host.contains("imasdk.googleapis.com")) {
                            if (YT_AD_ENDPOINTS.any { url.contains(it) }) {
                                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                            }
                        }

                        // ── Check 3: googlevideo.com ad stream detection ───────
                        if (url.contains("googlevideo.com/videoplayback")) {
                            val isAdStream =
                                url.contains("&oad=")        ||
                                url.contains("ctier=A")       ||
                                url.contains("&adformat=")    ||
                                url.contains("&ad_type=")     ||
                                url.contains("&source=ytads") ||
                                url.contains("&adsid=")       ||
                                (url.contains("&pot=") && url.contains("&c=WEB") && !url.contains("&id="))
                            if (isAdStream) {
                                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                            }
                        }

                        // ── Check 4: Generic ad URL patterns ─────────────────
                        if (AD_URL_PATTERNS.any { url.contains(it) } &&
                            !url.contains("youtube.com/watch") &&
                            !url.contains("googleapis.com/youtube") &&
                            !url.contains("youtube.com/results")) {
                            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                        }

                        // ── Check 5: googlevideo.com tracking/ping requests ───
                        if (host.contains("googlevideo.com")) {
                            val isGvAd =
                                url.contains("initplayback")    ||
                                url.contains("/pcs/activeview") ||
                                url.contains("ctier=SA")        ||
                                url.contains("ctier=SR")        ||
                                (url.contains("initplayback") && url.contains("adformat"))
                            if (isGvAd) {
                                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                            }
                        }
                    }

                    // ── Adult site block (domain + keyword) ───────────────────
                    val isDomainBlocked  = AdBlocker.isAdultSite(url)
                    val isKeywordBlocked = com.rasel.RasFocus.selfcontrol.FirebaseKeywordSync.containsAdultKeyword(url)
                    if (isDomainBlocked || isKeywordBlocked) {
                        adultBlockAlreadyShownForThisLoad = true
                        if (request.isForMainFrame) {
                            val blockedHtml = AdBlocker.buildBlockedPage(url, BlockReason.ADULT)
                            return WebResourceResponse("text/html", "UTF-8", blockedHtml.byteInputStream())
                        }
                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    }

                    // ── YouTube search keyword block (XHR) ────────────────────
                    val adultBlockHtml = checkAdultSearchKeyword(url)
                    if (adultBlockHtml != null) {
                        adultBlockAlreadyShownForThisLoad = true
                        runOnUiThread {
                            webView?.loadDataWithBaseURL(
                                "https://m.youtube.com/", adultBlockHtml, "text/html", "UTF-8", null
                            )
                        }
                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    }

                    return super.shouldInterceptRequest(view, request)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val url = request.url.toString()

                    // intent:// বা youtube:// দিয়ে YouTube app খুলতে দেবো না
                    if (url.startsWith("intent://") ||
                        url.startsWith("youtube://") ||
                        url.startsWith("vnd.youtube://") ||
                        url.startsWith("market://")) {
                        return true  // block — কিছুই করবো না
                    }

                    if (!url.startsWith("http://") && !url.startsWith("https://")) return true

                    val adultBlockHtml = checkAdultSearchKeyword(url)
                    if (adultBlockHtml != null) {
                        adultBlockAlreadyShownForThisLoad = true
                        view.loadDataWithBaseURL(
                            "https://m.youtube.com/", adultBlockHtml, "text/html", "UTF-8", null
                        )
                        return true
                    }

                    val safeUrl = buildYoutubeSafeSearchUrl(url)
                    if (safeUrl != null && safeUrl != url) {
                        view.loadUrl(safeUrl)
                        return true
                    }
                    return false
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (customView != null) {
                        rootFrame.removeView(customView)
                        callback.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customViewCallback = callback

                    val ctrl = WindowInsetsControllerCompat(window, window.decorView)
                    ctrl.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
                    ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    rootFrame.setPadding(0, 0, 0, 0)

                    rootFrame.addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    webView?.visibility = View.GONE
                }

                override fun onHideCustomView() {
                    webView?.visibility = View.VISIBLE
                    customView?.let { rootFrame.removeView(it) }
                    customView = null
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null

                    val ctrl = WindowInsetsControllerCompat(window, window.decorView)
                    ctrl.show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
                    ViewCompat.requestApplyInsets(rootFrame)
                }
            }
            rootFrame.addView(this)
            // ══════════════════════════════════════════════════════════════
            // ★ Low-RAM device fix: process kill এর পর cold-start হলে
            // YoutubeFloatingWindowService এ save করা শেষ URL এ ফেরাও,
            // সবসময় default youtube.com এ না গিয়ে।
            // ══════════════════════════════════════════════════════════════
            val recoveryPrefs = getSharedPreferences("yt_float_recovery", Context.MODE_PRIVATE)
            val wasOpen = recoveryPrefs.getBoolean("was_open", false)
            val recoveredUrl = if (wasOpen) recoveryPrefs.getString("last_url", null) else null
            if (recoveredUrl != null) {
                recoveryPrefs.edit().putBoolean("was_open", false).apply()
                loadUrl(buildYoutubeSafeSearchUrl(recoveredUrl) ?: recoveredUrl)
            } else {
                loadUrl(buildYoutubeSafeSearchUrl("https://m.youtube.com") ?: "https://m.youtube.com")
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // ★ CASE 1: Home WebView দেখাচ্ছে (mini player active, video corner এ চলছে)
        // Back press → video mini player বন্ধ করে activity finish
        if (isMiniPlayerActive) {
            val hWv = homeWebView
            if (hWv != null && hWv.visibility == View.VISIBLE) {
                if (hWv.canGoBack()) {
                    hWv.goBack()
                    return
                }
                // Home এ আর back নেই → service বন্ধ করো + activity close
                returnFromMiniPlayer(null)
                rootFrameRef?.postDelayed({ stopFloatingAndDestroy() }, 100)
                return
            }
            stopFloatingAndDestroy()
            return
        }

        // ★ CASE 2: Normal video WebView — canGoBack আছে?
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
            return
        }

        if (wv == null) {
            super.onBackPressed()
            return
        }

        // ★ CASE 3 (FIX): Overlay permission আছে কিনা সরাসরি check করো।
        // আগে JS async দিয়ে video চলছে কিনা দেখা হতো — কিন্তু evaluateJavascript
        // callback আসার আগেই activity finish হয়ে যেত কারণ কোনো fallback ছিল না।
        // এখন: overlay আছে → সবসময় mini player launch করো (video না চললেও
        // launchMiniPlayer() ভেতরে handle করে); overlay নেই → normally close।
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            android.provider.Settings.canDrawOverlays(this)
        else true

        if (hasOverlay) {
            // Overlay আছে — mini player চালু করো
            // launchMiniPlayer() ভেতরে JS দিয়ে video check করে:
            // video না চললে সে নিজেই normally close করবে
            launchMiniPlayer(wv)
        } else {
            // Overlay নেই — normally close
            @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    /**
     * ★ Mini Player Launch — Native YouTube এর মতো behavior।
     *
     * Back press বা swipe down এ:
     * 1. Video চলছে কিনা JS দিয়ে check — না চললে normally close
     * 2. Video WebView → floating mini player service এ যায় (corner এ চলে)
     * 3. Activity বন্ধ হয় না → YouTube home page দেখায় নতুন homeWebView দিয়ে
     * 4. Home WebView এ footer আছে (Home / Shorts / Account)
     * 5. Mini player tap করলে homeWebView লুকিয়ে video WebView ফিরে আসে
     *
     * FIX: আগে onBackPressed() এ JS check ছিল — কিন্তু async callback আসার
     * আগেই fallback না থাকায় activity close হয়ে যেত। এখন check এখানে রাখা
     * হয়েছে যাতে "overlay আছে → launchMiniPlayer() call করো" সবসময় নিরাপদ।
     */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun launchMiniPlayer(wv: WebView) {
        val hasOverlay = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
            android.provider.Settings.canDrawOverlays(this)
        else true

        if (!hasOverlay) {
            // Overlay permission নেই → normally close
            runOnUiThread { @Suppress("DEPRECATION") super.onBackPressed() }
            return
        }

        // ★ FIX: Video চলছে কিনা এখানে check করো।
        // onBackPressed() এ async JS দিয়ে check করলে callback আসার আগেই
        // activity finish হয়ে যেত — তাই এখানে JS check করে তারপর launch।
        // video না চললে → normally close করো।
        wv.evaluateJavascript("""
            (function() {
                try {
                    var v = document.querySelector('video');
                    return (v && !v.paused && !v.ended && v.readyState > 2) ? 'playing' : 'not_playing';
                } catch(e) { return 'playing'; }
            })();
        """.trimIndent()) { result ->
            if (result?.contains("not_playing") == true) {
                // Video চলছে না — normally close করো
                runOnUiThread { @Suppress("DEPRECATION") super.onBackPressed() }
                return@evaluateJavascript
            }
            // Video চলছে — mini player launch করো
            runOnUiThread { doLaunchMiniPlayer(wv) }
        }
    }

    /** launchMiniPlayer() এর actual implementation — video check পাস করার পরে call হয় */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun doLaunchMiniPlayer(wv: WebView) {
        val currentUrl   = wv.url   ?: "https://m.youtube.com"
        val currentTitle = wv.title ?: "YouTube"

        injectVisibilitySpoofBeforeLeave(wv)

        val frame = rootFrameRef ?: return

        // ★ Native YouTube animation: Activity এর ভেতরেই WebView কে
        // ধীরে ধীরে corner এ shrink করে দেখাও, তারপর service launch করো।
        // এতে user দেখবে video টা "সংকুচিত হয়ে" corner এ চলে যাচ্ছে —
        // ঠিক native YouTube এর মতো।
        animateMiniPlayerLaunch(wv, frame) {
            // Animation শেষে service এ পাঠাও
            injectVisibilitySpoofBeforeLeave(wv)

            // ── Step 1: Video WebView → mini player service ─────────────────
            com.rasel.RasFocus.combo.selfcontrol.familybrowser.service.YoutubeFloatingWindowService.pendingWebView = wv
            com.rasel.RasFocus.combo.selfcontrol.familybrowser.service.YoutubeFloatingWindowService.launchMiniPlayer(
                this, currentUrl, currentTitle
            )

            webView = null
            isMiniPlayerActive = true
            startBgAudioService()

            // ── Step 2: Home page দেখাও ────────────────────────────────────
            showHomeWebView(frame)
        }
    }

    /**
     * ★ Native YouTube "shrink to corner" animation।
     * WebView কে Activity এর ভেতরেই animate করো:
     * Full-screen → bottom-right corner এ ছোট করো।
     * Animation শেষে onComplete() callback call হয়।
     */
    private fun animateMiniPlayerLaunch(wv: WebView, frame: FrameLayout, onComplete: () -> Unit) {
        val dm      = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        // Target size: 240×135dp (16:9 ratio) — service mini player এর same size
        val targetW = (240 * dm.density).toInt()
        val targetH = (135 * dm.density).toInt()

        // শুরুতে WebView full-screen — frame এ already আছে
        // FrameLayout.LayoutParams দিয়ে size ও position animate করবো

        // Home WebView আগে load করতে শুরু করো (background এ)
        val hWv = homeWebView ?: buildHomeWebView().also { homeWebView = it }
        if (hWv.url == null || hWv.url == "about:blank") {
            hWv.loadUrl("https://m.youtube.com/")
        }
        // Home WebView পিছনে রাখো (এখনো invisible)
        (hWv.parent as? ViewGroup)?.removeView(hWv)
        hWv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        hWv.visibility = View.VISIBLE
        hWv.alpha = 0f  // এখনো দেখা যাবে না
        frame.addView(hWv, 0)  // WebView এর নিচে

        // WebView কে absolute positioning এর মতো animate করার জন্য
        // translationX/Y + scaleX/Y ব্যবহার করো
        val startScaleX = 1f
        val startScaleY = 1f
        val targetScaleX = targetW.toFloat() / screenW.toFloat()
        val targetScaleY = targetH.toFloat() / screenH.toFloat()

        // ★ Pivot: bottom-right corner তে anchor করো।
        // এতে scale করলে video bottom-right corner এ "সংকুচিত হয়" —
        // ঠিক native YouTube এর মতো।
        wv.pivotX = screenW.toFloat()
        wv.pivotY = screenH.toFloat()

        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = 350L
            interpolator = android.view.animation.DecelerateInterpolator(2f)

            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val scaleX = startScaleX + (targetScaleX - startScaleX) * t
                val scaleY = startScaleY + (targetScaleY - startScaleY) * t
                wv.scaleX = scaleX
                wv.scaleY = scaleY

                // Home WebView ধীরে ধীরে fade in করো (animation এর শেষ ৪০% এ)
                val fadeT = ((t - 0.6f) / 0.4f).coerceIn(0f, 1f)
                hWv.alpha = fadeT
            }

            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // Animation শেষ — cleanup করো
                    wv.scaleX = 1f
                    wv.scaleY = 1f
                    wv.pivotX = wv.width / 2f
                    wv.pivotY = wv.height / 2f

                    // WebView frame থেকে সরাও
                    (wv.parent as? ViewGroup)?.removeView(wv)

                    // Home WebView fully visible করো
                    hWv.alpha = 1f
                    hWv.bringToFront()

                    onComplete()
                }
            })
        }
        animator.start()
    }

    /**
     * ★ Helper: Home WebView frame এ দেখাও (animation ছাড়া direct call এর জন্য)
     */
    private fun showHomeWebView(frame: FrameLayout) {

        runOnUiThread {
            // ── Home WebView build/reuse ────────────────────────────────────
            val hWv = homeWebView ?: buildHomeWebView().also { homeWebView = it }

            // পুরনো parent থেকে সরাও
            (hWv.parent as? ViewGroup)?.removeView(hWv)

            hWv.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            hWv.visibility = View.VISIBLE
            hWv.alpha = 1f
            frame.addView(hWv)
            hWv.bringToFront()

            // এখনও home এ না থাকলে load করো
            val homeUrl = "https://m.youtube.com/"
            val existingUrl = hWv.url
            if (existingUrl == null || existingUrl == "about:blank") {
                hWv.loadUrl(homeUrl)
            }
        }
    }

    /**
     * ★ Home WebView builder — YouTube home page with native footer।
     * Full ad-blocking + adult content blocking সহ।
     * Video play করলে → mini player এ পাঠায়, home দেখায়।
     */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun buildHomeWebView(): WebView {
        return object : WebView(this) {
            override fun onPause() { /* suppress */ }
            override fun pauseTimers() { /* suppress */ }
            override fun onResume() { super.onResume() }
        }.apply {
            if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.Q) {
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
            } else {
                setLayerType(View.LAYER_TYPE_NONE, null)
            }
            setBackgroundColor(android.graphics.Color.BLACK)

            settings.apply {
                javaScriptEnabled                = true
                domStorageEnabled                = true
                databaseEnabled                  = true
                loadWithOverviewMode             = true
                useWideViewPort                  = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess                  = true
                allowContentAccess               = true
                loadsImagesAutomatically         = true
                mixedContentMode                 = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                cacheMode                        = WebSettings.LOAD_DEFAULT
                userAgentString                  = YT_USER_AGENT
            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: android.webkit.WebResourceRequest
                ): android.webkit.WebResourceResponse? {
                    val url  = request.url.toString()
                    val host = request.url?.host?.lowercase() ?: ""
                    // Adult block
                    if (AdBlocker.isAdultSite(url) ||
                        com.rasel.RasFocus.selfcontrol.FirebaseKeywordSync.containsAdultKeyword(url)) {
                        if (request.isForMainFrame) {
                            val html = AdBlocker.buildBlockedPage(url, BlockReason.ADULT)
                            return android.webkit.WebResourceResponse("text/html", "UTF-8", html.byteInputStream())
                        }
                        return android.webkit.WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                    // Basic ad block
                    if (AD_SERVERS.any { host == it || host.endsWith(".$it") }) {
                        return android.webkit.WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    injectVisibilitySpoof(view)
                    injectRemoveOpenInAppButton(view)
                    // Home page এ footer inject করো (YouTube native এর মতো)
                    injectHomeFooterEnhancement(view)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: android.webkit.WebResourceRequest
                ): Boolean {
                    val url = request.url.toString()
                    // Watch page → main webView এ open করো (mini player dismiss)
                    if (url.contains("youtube.com/watch") || url.contains("youtu.be/")) {
                        returnFromMiniPlayer(url)
                        return true
                    }
                    if (url.startsWith("intent://") || url.startsWith("youtube://")) return true
                    if (!url.startsWith("http://") && !url.startsWith("https://")) return true
                    return false
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    // Home WebView এ fullscreen চাওয়া → mini player dismiss করে main এ যাও
                }
                override fun onHideCustomView() {}
            }
        }
    }

    /**
     * ★ Home WebView এ YouTube native footer ঠিকমতো দেখানোর জন্য JS inject।
     * YouTube mobile এ footer bar (Home/Shorts/Account) native এ আছে — এটা
     * শুধু ensure করে যে সেটা দৃশ্যমান এবং সঠিকভাবে কাজ করছে।
     */
    private fun injectHomeFooterEnhancement(view: WebView) {
        view.evaluateJavascript("""
            (function() {
                if (window.__rasHomeEnhanced__) return;
                window.__rasHomeEnhanced__ = true;
                
                // YouTube mobile footer navigation bar ensure visible
                function ensureFooter() {
                    try {
                        // YouTube mobile এর bottom nav bar
                        var navBar = document.querySelector(
                            'ytm-pivot-bar-renderer, ' +
                            '.pivot-bar, ' +
                            '[id="tabsContent"], ' +
                            'ytm-mobile-topbar-renderer'
                        );
                        if (navBar) {
                            navBar.style.display = '';
                            navBar.style.visibility = 'visible';
                            navBar.style.opacity = '1';
                        }
                        // Bottom nav items (Home, Shorts, Subscriptions, Account)
                        document.querySelectorAll('ytm-pivot-bar-item-renderer').forEach(function(item) {
                            item.style.display = '';
                        });
                    } catch(e) {}
                }
                
                ensureFooter();
                // YouTube SPA navigation এ পরেও ensure করো
                var observer = new MutationObserver(function() { ensureFooter(); });
                observer.observe(document.documentElement, { childList: true, subtree: true });
            })();
        """.trimIndent(), null)
    }

    /**
     * ★ Home WebView এ video link click করলে — mini player dismiss, main WebView এ open।
     * Service বন্ধ → pendingWebView থেকে video WebView নিয়ে re-attach → নতুন URL load।
     */
    private fun returnFromMiniPlayer(videoUrl: String? = null) {
        if (!isMiniPlayerActive) return
        isMiniPlayerActive = false
        stopBgAudioService()

        // Floating service বন্ধ করো
        try {
            stopService(Intent(
                this,
                com.rasel.RasFocus.combo.selfcontrol.familybrowser.service.YoutubeFloatingWindowService::class.java
            ))
        } catch (_: Exception) {}

        val frame = rootFrameRef ?: return

        // Home WebView লুকাও
        homeWebView?.visibility = View.GONE

        // Video WebView re-attach
        fun reattach(wv: WebView) {
            reattachWebView(wv, frame)
            // নতুন URL থাকলে সেটা load করো
            if (videoUrl != null) {
                wv.postDelayed({
                    wv.loadUrl(buildYoutubeSafeSearchUrl(videoUrl) ?: videoUrl)
                }, 300)
            }
        }

        val immediateWv = com.rasel.RasFocus.combo.selfcontrol.familybrowser.service.YoutubeFloatingWindowService.pendingWebView
        if (immediateWv != null) {
            reattach(immediateWv)
        } else {
            frame.postDelayed({
                val pendingWv = com.rasel.RasFocus.combo.selfcontrol.familybrowser.service.YoutubeFloatingWindowService.pendingWebView
                if (pendingWv != null) {
                    reattach(pendingWv)
                }
            }, 200)
        }
    }

    /**
     * ★ Swipe down → Mini Player gesture।
     * Video দেখার সময় উপর থেকে নিচে 120dp+ swipe করলে mini player হবে।
     * WebView এর scroll এর সাথে conflict না করতে — শুধু top 30% থেকে
     * শুরু হওয়া downward swipe ধরা হয় (YouTube এর নিজের gesture এর মতো)।
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    // ★ FIX: Swipe down gesture — dispatchTouchEvent দিয়ে করা হচ্ছে।
    // আগে Activity.onTouchEvent() use করা হতো — কিন্তু WebView সব touch নিজে
    // consume করে, তাই Activity.onTouchEvent() কখনো call হতো না।
    // dispatchTouchEvent() সব touch এর আগে call হয় — WebView consume করুক বা না করুক।
    // Manual tracking: ACTION_DOWN এ start point save করি, ACTION_UP এ distance check করি।
    // GestureDetector.onFling() ব্যবহার করা হচ্ছে না কারণ WebView ACTION_CANCEL inject
    // করে fling শেষ হওয়ার আগেই, ফলে onFling() callback কখনো আসে না।
    private var swipeTouchDownY = 0f
    private var swipeTouchDownX = 0f
    private var swipeStartedFromTop = false
    private var swipeConsumedThisGesture = false

    private fun setupSwipeDownGesture(rootFrame: FrameLayout) {
        // কিছু করার দরকার নেই — dispatchTouchEvent এ সব tracking হয়।
        // এই function শুধু onCreate() এর call site টা রাখার জন্য আছে।
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // ★ Swipe down → mini player detection
        // শুধু যখন: mini player নেই + video WebView আছে + overlay permission আছে
        if (!isMiniPlayerActive && webView != null) {
            val screenH = resources.displayMetrics.heightPixels.toFloat()
            val SWIPE_MIN_PX    = (100 * resources.displayMetrics.density)  // ন্যূনতম 100dp
            val TOP_AREA_RATIO  = 0.40f  // screen এর উপরের 40% থেকে শুরু হলে ধরবে

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeTouchDownY = ev.rawY
                    swipeTouchDownX = ev.rawX
                    // শুধু screen এর উপরের 40% থেকে শুরু হওয়া swipe ধরবো
                    swipeStartedFromTop = (swipeTouchDownY < screenH * TOP_AREA_RATIO)
                    swipeConsumedThisGesture = false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    if (swipeStartedFromTop && !swipeConsumedThisGesture) {
                        val dy = ev.rawY - swipeTouchDownY
                        val dx = ev.rawX - swipeTouchDownX
                        // নিচের দিকে + vertical বেশি + minimum distance
                        if (dy > SWIPE_MIN_PX && dy > Math.abs(dx) * 1.2f) {
                            swipeConsumedThisGesture = true
                            swipeStartedFromTop = false
                            val wv = webView
                            if (wv != null) {
                                // overlay check
                                val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                                    android.provider.Settings.canDrawOverlays(this)
                                else true
                                if (hasOverlay) {
                                    launchMiniPlayer(wv)
                                }
                            }
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    swipeStartedFromTop = false
                    swipeConsumedThisGesture = false
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun launchFloatingDirectly(wv: WebView, moveActivityToBack: Boolean) {
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            android.provider.Settings.canDrawOverlays(this)
        else true

        if (!hasOverlay) {
            if (moveActivityToBack) runOnUiThread { @Suppress("DEPRECATION") super.onBackPressed() }
            return
        }

        val currentUrl   = wv.url   ?: "https://m.youtube.com"
        val currentTitle = wv.title ?: "YouTube"

        injectVisibilitySpoofBeforeLeave(wv)

        com.rasel.RasFocus.combo.selfcontrol.familybrowser.service.YoutubeFloatingWindowService.pendingWebView = wv
        com.rasel.RasFocus.combo.selfcontrol.familybrowser.service.YoutubeFloatingWindowService.launchNoReload(this, currentUrl, currentTitle)

        webView = null
        isMiniPlayerActive = true

        if (moveActivityToBack) {
            moveTaskToBack(true)
        }
        startBgAudioService()
    }

    private fun stopFloatingAndDestroy() {
        isMiniPlayerActive = false
        stopBgAudioService()
        try {
            stopService(Intent(
                this,
                com.rasel.RasFocus.combo.selfcontrol.familybrowser.service.YoutubeFloatingWindowService::class.java
            ))
        } catch (_: Exception) {}
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Mini player tap করলে YoutubeActivity FLAG_ACTIVITY_REORDER_TO_FRONT দিয়ে
        // resume হয় — তখন onNewIntent call হয়। isMiniPlayerActive true থাকলে
        // onResume() এ WebView re-attach হবে।
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (isMiniPlayerActive) {
            // ★ Mini Player tap → Activity resume flow
            // Service বন্ধ করো → onDestroy() এ WebView pendingWebView এ যাবে
            returnFromMiniPlayer(videoUrl = null)
            return
        }

        // Normal resume (mini player নেই)
        // homeWebView লুকাও যদি কোনো কারণে দৃশ্যমান থাকে
        homeWebView?.visibility = View.GONE

        webView?.resumeTimers()
        webView?.onResume()
        webView?.apply {
            visibility = View.VISIBLE
            alpha = 1f
            bringToFront()
        }
    }

    /**
     * ★ নতুন helper: rootFrame reference বের করো।
     */
    private fun getRootFrame(): FrameLayout? {
        val contentView = window.decorView.findViewById<ViewGroup>(android.R.id.content)
        return contentView?.getChildAt(0) as? FrameLayout
            ?: contentView as? FrameLayout
    }

    /**
     * ★ নতুন helper: Floating থেকে ফেরত আসা WebView কে Activity তে re-attach করো।
     * Black screen যাতে না আসে সেটা এখানেই নিশ্চিত করা হয়।
     */
    private fun reattachWebView(returnedWv: WebView, rootFrame: FrameLayout?) {
        webView = returnedWv
        com.rasel.RasFocus.combo.selfcontrol.familybrowser.service.YoutubeFloatingWindowService.pendingWebView = null

        // পুরনো parent থেকে সরাও
        (returnedWv.parent as? ViewGroup)?.removeView(returnedWv)

        // ★ Home WebView লুকাও — video ফিরে আসছে
        homeWebView?.visibility = View.GONE

        if (rootFrame != null) {
            returnedWv.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // ★ FIX: Black screen → WebView আগে invisible রাখো, content load হলে visible করো
            returnedWv.visibility = View.INVISIBLE
            returnedWv.alpha = 1f
            rootFrame.setBackgroundColor(android.graphics.Color.BLACK)
            rootFrame.addView(returnedWv)
            rootFrame.bringToFront()
            rootFrame.requestLayout()
        }

        returnedWv.resumeTimers()
        returnedWv.onResume()
        injectVisibilitySpoof(returnedWv)
        injectYoutubeHacksForced(returnedWv)

        // ★ FIX: 150ms পরে visible করো — WebView render হওয়ার পরে
        // এতে black flash দেখা যাবে না
        returnedWv.postDelayed({
            returnedWv.visibility = View.VISIBLE
            returnedWv.alpha = 1f
            returnedWv.bringToFront()
            returnedWv.invalidate()
        }, 150)

        // ★ FIX: Video unmute + play ensure
        returnedWv.postDelayed({
            injectVisibilitySpoof(returnedWv)
            returnedWv.evaluateJavascript("""
                (function() {
                    try {
                        var videos = document.querySelectorAll('video');
                        for (var i = 0; i < videos.length; i++) {
                            try {
                                videos[i].muted = false;
                                if (videos[i].paused && !videos[i].ended) {
                                    videos[i].play().catch(function(){});
                                }
                            } catch(e) {}
                        }
                    } catch(e) {}
                })();
            """.trimIndent(), null)
        }, 300)
    }

    private fun injectVisibilitySpoofBeforeLeave(wv: WebView) {
        wv.evaluateJavascript("""
            (function() {
                try {
                    Object.defineProperty(document, 'hidden', { get: function(){ return false; }, configurable: true });
                    Object.defineProperty(document, 'visibilityState', { get: function(){ return 'visible'; }, configurable: true });
                    Object.defineProperty(document, 'webkitHidden', { get: function(){ return false; }, configurable: true });
                    Object.defineProperty(document, 'webkitVisibilityState', { get: function(){ return 'visible'; }, configurable: true });
                } catch(e) {}
            })();
        """.trimIndent(), null)
    }

    private fun buildYoutubeSafeSearchUrl(url: String): String? {
        return try {
            val uri = android.net.Uri.parse(url)
            val host = uri.host?.lowercase() ?: return null
            if (!host.contains("youtube.com") && !host.contains("youtu.be")) return null
            val path = uri.path ?: ""
            if (!path.contains("/results") && uri.getQueryParameter("search_query") == null && uri.getQueryParameter("q") == null) return null
            if (uri.getQueryParameter("safe") == "strict") return null
            uri.buildUpon().appendQueryParameter("safe", "strict").build().toString()
        } catch (e: Exception) { null }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isMiniPlayerActive) return
        val wv = webView ?: return
        // Home button চাপলে floating window — same logic
        launchFloatingOnLock(wv)
    }

    override fun onPause() {
        webView?.resumeTimers()
        webView?.onResume()
        webView?.let {
            injectVisibilitySpoof(it)
            injectYoutubeHacksForced(it)
        }
        super.onPause()
        
        // অ্যাপ থেকে অন্য কোথাও গেলে অডিও প্লে হবে
        startBgAudioService()
        if (wakeLock?.isHeld == false) wakeLock?.acquire()
    }

    override fun onRestart() {
        super.onRestart()
        if (!isFinishing) stopBgAudioService()
    }

    override fun onStop() {
        webView?.resumeTimers()
        super.onStop()
        if (webView != null && !isFinishing) startBgAudioService()
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenOffReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(playbackControlReceiver) } catch (_: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        if (webView != null) {
            stopBgAudioService()
            webView?.destroy()
            webView = null
        }
        // ★ Home WebView cleanup
        try {
            homeWebView?.stopLoading()
            homeWebView?.destroy()
            homeWebView = null
        } catch (_: Exception) {}
        rootFrameRef = null
        super.onDestroy()
    }

    private fun injectVisibilitySpoof(view: WebView) {
        view.evaluateJavascript("""
            (function() {
                try {
                    Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                    Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                } catch(e) {}
            })();
        """.trimIndent(), null)
    }

    private fun injectYoutubeHacks(view: WebView) {
        view.evaluateJavascript("""
            (function() {
                if (window.__rasBgAudioInjected__) return;
                window.__rasBgAudioInjected__ = true;
                try {
                    Object.defineProperty(document, 'hidden', { get: function(){ return false; }, configurable: true });
                    Object.defineProperty(document, 'visibilityState', { get: function(){ return 'visible'; }, configurable: true });
                    
                    var _origAdd = EventTarget.prototype.addEventListener;
                    EventTarget.prototype.addEventListener = function(type, fn, opts) {
                        if (type === 'visibilitychange' || type === 'webkitvisibilitychange' ||
                            type === 'pagehide' || type === 'blur') return;
                        return _origAdd.call(this, type, fn, opts);
                    };

                    if (!window.__rasVideoKeepAlive__) {
                        window.__rasVideoKeepAlive__ = true;
                        setInterval(function() {
                            try {
                                var v = document.querySelector('video');
                                if (v && v.paused && !v.ended && v.readyState > 1) {
                                    v.play().catch(function(){});
                                }
                            } catch(e) {}
                        }, 2000);
                    }
                } catch(e) {}
            })();
        """.trimIndent(), null)
    }

    private fun injectRemoveOpenInAppButton(view: WebView) {
        view.evaluateJavascript("""
            (function() {
                if (window.__rasOpenAppRemoverActive__) return;
                window.__rasOpenAppRemoverActive__ = true;

                // YouTube mobile "Open App" banner এর সব known selectors
                // (YouTube নতুন class দিলেও text/href দিয়ে ধরা হবে)
                var SELECTORS = [
                    // পুরনো class-based
                    '.ytm-action-button',
                    '[class*="open-in-app"]',
                    '[class*="openInApp"]',
                    '.external-app-banner',
                    '.app-badge-container',
                    // নতুন YouTube mobile UI
                    'ytm-app-banner-link-renderer',
                    'ytm-interstitial-ad-renderer',
                    'ytm-open-in-app-banner',
                    '.ytp-ae-banner',
                    '.ytp-chrome-top-buttons',
                    'ytm-companion-ad-renderer',
                    // data attribute based
                    '[data-type="open-app"]',
                    // intent:// link যুক্ত যেকোনো element
                ];

                function removeOpenAppElements() {
                    try {
                        // Selector দিয়ে remove
                        document.querySelectorAll(SELECTORS.join(','))
                            .forEach(function(el) {
                                el.style.display = 'none';
                                el.remove();
                            });

                        // Intent link এবং "Watch on app" text দিয়ে ধরো
                        document.querySelectorAll('a[href^="intent://"], a[href*="youtube://"]')
                            .forEach(function(el) {
                                var parent = el.closest('[class*="banner"], [class*="Banner"], [class*="interstitial"], [class*="Interstitial"], ytm-app-banner-link-renderer');
                                if (parent) {
                                    parent.style.display = 'none';
                                    parent.remove();
                                } else {
                                    el.style.display = 'none';
                                    el.remove();
                                }
                            });

                        // "Open app" / "Watch in app" / "Get apps for a faster
                        // experience" ইত্যাদি phrase যুক্ত banner ধরার জন্য
                        // exact-match এর বদলে substring match ব্যবহার করা হচ্ছে,
                        // কারণ YouTube বিভিন্ন variant text ব্যবহার করে।
                        var ytBannerPhrases = [
                            'open app', 'watch in app', 'use the app', 'open in app',
                            'get the app', 'open youtube', 'get apps for', 'faster experience',
                            'for a faster experience', 'download the app', 'try the app',
                            'switch to the app', 'view in app', 'continue in app'
                        ];
                        document.querySelectorAll('button, a, [role="button"], div, span')
                            .forEach(function(el) {
                                var txt = (el.innerText || el.textContent || '').toLowerCase().trim();
                                if (!txt || txt.length > 60) return;
                                var hit = ytBannerPhrases.some(function(p) { return txt.indexOf(p) !== -1; });
                                if (hit) {
                                    var parent = el.closest('[class*="banner"], [class*="Banner"], [class*="interstitial"], ytm-app-banner-link-renderer') || el.parentElement;
                                    if (parent) { parent.style.display = 'none'; parent.remove(); }
                                    else { el.style.display = 'none'; el.remove(); }
                                }
                            });
                    } catch(e) {}
                }

                // প্রথমেই চালাও
                removeOpenAppElements();

                // MutationObserver — YouTube SPA navigation এ নতুন element এলেই ধরবে
                try {
                    var observer = new MutationObserver(function(mutations) {
                        removeOpenAppElements();
                    });
                    observer.observe(document.body || document.documentElement, {
                        childList: true,
                        subtree: true
                    });
                } catch(e) {}

                // Fallback interval (observer fail হলে)
                setInterval(removeOpenAppElements, 1500);
            })();
        """.trimIndent(), null)
    }

    private fun injectAdBlocker(view: WebView) {
        view.evaluateJavascript("""
            (function() {
                if (window.__rasAdBlockerActive__) return;
                window.__rasAdBlockerActive__ = true;

                // ═══════════════════════════════════════════════════════════════
                // LAYER 2 — Mobile YouTube Ad Skipper (m.youtube.com)
                //
                // Bug fix history:
                // ✗ OLD: '#movie_player', '.html5-video-player' → desktop YouTube
                //        selector, m.youtube.com এ exist করে না, কখনো কাজ করেনি।
                // ✓ NEW: mobile YouTube এর actual DOM structure:
                //   - ad-showing class: <ytm-player> বা body.ad-showing
                //   - skip button: .ytm-skip-button-renderer, [data-skip-ad-button]
                //   - ad video: src এ 'ctier=A' / '&oad=' / '&adformat=' আছে
                //   - promoted card: ytm-promoted-sparkles-web-renderer,
                //                    ytm-promoted-video-renderer
                // ═══════════════════════════════════════════════════════════════

                // ── Ad video চেনার helper ──
                function isAdVideo(v) {
                    try {
                        var src = v.src || '';
                        if (src.indexOf('ctier=A')    !== -1) return true;
                        if (src.indexOf('&oad=')      !== -1) return true;
                        if (src.indexOf('&adformat=') !== -1) return true;
                        if (src.indexOf('&source=ytads') !== -1) return true;
                        // mobile YouTube: ad video element closest ancestor
                        if (v.closest) {
                            if (v.closest('.ad-showing'))        return true;
                            if (v.closest('[class*="ad-slot"]')) return true;
                        }
                        return false;
                    } catch(e) { return false; }
                }

                // ── Ad শেষ হওয়ার পরে main video জাগানো ──
                function wakeMainVideo() {
                    try {
                        var allVideos = document.querySelectorAll('video');
                        var mainVideo = null;
                        for (var i = 0; i < allVideos.length; i++) {
                            if (!isAdVideo(allVideos[i])) { mainVideo = allVideos[i]; break; }
                        }
                        if (!mainVideo && allVideos.length > 0) {
                            mainVideo = allVideos[allVideos.length - 1];
                        }
                        if (!mainVideo) return;

                        mainVideo.style.visibility = 'visible';
                        mainVideo.style.display    = 'block';
                        mainVideo.style.opacity    = '1';
                        if (mainVideo.muted) mainVideo.muted = false;
                        mainVideo.play().catch(function(){});

                        setTimeout(function() {
                            try {
                                if (mainVideo.paused && !mainVideo.ended) {
                                    mainVideo.play().catch(function(){});
                                }
                            } catch(e) {}
                        }, 400);
                    } catch(e) {}
                }

                // ── Ad video skip করা ──
                function skipAdVideo() {
                    try {
                        var allVideos = document.querySelectorAll('video');
                        for (var i = 0; i < allVideos.length; i++) {
                            var v = allVideos[i];
                            if (isAdVideo(v) && v.duration > 0 && !v.ended) {
                                v.currentTime = v.duration;
                                return true;
                            }
                        }
                        // Fallback: সব video এর মধ্যে সবচেয়ে ছোট duration টাই ad
                        if (allVideos.length > 1) {
                            var shortest = null;
                            var shortestDur = Infinity;
                            for (var j = 0; j < allVideos.length; j++) {
                                var dur = allVideos[j].duration || 0;
                                if (dur > 0 && dur < shortestDur) {
                                    shortestDur = dur;
                                    shortest    = allVideos[j];
                                }
                            }
                            // শুধু skip করো যদি duration ≤ 60s (ad এর মতো)
                            if (shortest && shortestDur <= 60 && !shortest.ended) {
                                shortest.currentTime = shortest.duration;
                                return true;
                            }
                        }
                        return false;
                    } catch(e) { return false; }
                }

                // ── body বা player এ ad-showing class আছে কিনা ──
                function isAdShowingNow() {
                    try {
                        // mobile YouTube: body.ad-showing বা ytm-player.ad-showing
                        if (document.body && document.body.classList.contains('ad-showing')) return true;
                        // player element এ
                        var players = document.querySelectorAll(
                            'ytm-player, ytm-shorts-player, .player-container, [data-player-type]'
                        );
                        for (var i = 0; i < players.length; i++) {
                            if (players[i].classList.contains('ad-showing')) return true;
                        }
                        // ytm-paid-content-overlay বা ytm-ad-slot দেখা যাচ্ছে কিনা
                        var adSlot = document.querySelector('ytm-paid-content-overlay, ytm-ad-slot-renderer');
                        if (adSlot && adSlot.offsetParent !== null) return true;
                        return false;
                    } catch(e) { return false; }
                }

                var wasAdShowing = false;
                var skipAttempts = 0;

                setInterval(function() {
                    try {
                        // ── Step 1: Skip button — mobile YouTube এর selectors ──
                        var skipBtn = document.querySelector(
                            // mobile YouTube skip button variants
                            '.ytm-skip-button-renderer button, ' +
                            '[data-skip-ad-button] button, ' +
                            'ytm-skip-button-renderer button, ' +
                            // desktop-style যদি থাকে
                            '.ytp-ad-skip-button, ' +
                            '.ytp-ad-skip-button-modern, ' +
                            '.ytp-skip-ad-button, ' +
                            // aria-label based (language-agnostic)
                            'button[aria-label*="Skip"], ' +
                            'button[aria-label*="skip"], ' +
                            'button[aria-label*="Ad"], ' +
                            '.skip-button'
                        );
                        if (skipBtn && skipBtn.offsetParent !== null) {
                            skipBtn.click();
                            wasAdShowing = false;
                            return;
                        }

                        // ── Step 2: Banner / card ads hide ──
                        document.querySelectorAll(
                            'ytm-promoted-sparkles-web-renderer, ' +
                            'ytm-promoted-video-renderer, ' +
                            'ytm-paid-content-overlay, ' +
                            'ytm-ad-slot-renderer, ' +
                            '.ytm-promoted-sparkles-text-search-ad-renderer, ' +
                            '.ytp-ad-overlay-container, ' +
                            '.ytp-ad-text-overlay, ' +
                            '.ytp-ad-image-overlay, ' +
                            '.ytp-ad-progress-list'
                        ).forEach(function(ad) {
                            ad.style.display = 'none';
                        });

                        // ── Step 3: Video ad — currentTime → duration ──
                        var adNow = isAdShowingNow();

                        if (adNow) {
                            wasAdShowing = true;
                            skipAttempts++;
                            var skipped = skipAdVideo();

                            // Extra: ad overlay element এও click করো
                            if (!skipped) {
                                var overlay = document.querySelector(
                                    'ytm-paid-content-overlay, .ad-showing .ytp-ad-player-overlay'
                                );
                                if (overlay) overlay.click();
                            }
                        } else if (wasAdShowing) {
                            // Ad সবে শেষ — main video জাগাও
                            wasAdShowing = false;
                            skipAttempts = 0;
                            wakeMainVideo();
                        }

                    } catch(e) {}
                }, 250);

                // ── Extra: MutationObserver দিয়ে skip button আসামাত্র click ──
                // interval এ 250ms delay আছে — observer instantaneous
                try {
                    var skipObserver = new MutationObserver(function() {
                        try {
                            var btn = document.querySelector(
                                '.ytm-skip-button-renderer button, ' +
                                '[data-skip-ad-button] button, ' +
                                'ytm-skip-button-renderer button, ' +
                                '.ytp-ad-skip-button, .ytp-ad-skip-button-modern'
                            );
                            if (btn && btn.offsetParent !== null) btn.click();
                        } catch(e) {}
                    });
                    skipObserver.observe(document.documentElement, {
                        childList: true,
                        subtree: true,
                        attributes: false
                    });
                } catch(e) {}

            })();
        """.trimIndent(), null)
    }

    /**
     * Layer 3 — YouTube player DOM এর ভেতরের ad overlay/banner/countdown forcefully hide।
     * injectContentScanner() এর মতো পুরো page scan নয় — শুধু player container।
     * false-positive block হয় না কারণ thumbnail/title text touch করা হয় না।
     */
    private fun injectYtAdLayerThree(view: WebView) {
        view.evaluateJavascript("""
            (function() {
                if (window.__rasL3Active__) return;
                window.__rasL3Active__ = true;

                function removeYtAds() {
                    try {
                        var adSelectors = [
                            'ytm-paid-content-overlay',
                            'ytm-ad-slot-renderer',
                            'ytm-display-ad-renderer',
                            'ytm-companion-slot',
                            'ytm-banner-promo-renderer',
                            '.ytp-ad-progress-list',
                            '.ytp-ad-text-overlay',
                            '.ytp-ad-image-overlay',
                            '.ytp-ad-overlay-container',
                            '.ytm-promoted-sparkles-text-search-ad-renderer',
                            'ytm-promoted-sparkles-web-renderer',
                            'ytm-promoted-video-renderer',
                            '.ytp-ad-info-dialog-ad-reasons',
                            '.ytp-ad-button',
                            '.ytp-ad-duration-remaining',
                            '.ytp-ad-simple-ad-badge',
                            '.ytp-ad-preview-container',
                            '[class*="ad-badge"]',
                            '[class*="AdBadge"]'
                        ];
                        adSelectors.forEach(function(sel) {
                            try {
                                document.querySelectorAll(sel).forEach(function(el) {
                                    el.style.setProperty('display', 'none', 'important');
                                });
                            } catch(e) {}
                        });
                    } catch(e) {}
                }

                removeYtAds();

                try {
                    var obs = new MutationObserver(function() { removeYtAds(); });
                    obs.observe(document.documentElement, {
                        childList: true, subtree: true, attributes: false
                    });
                } catch(e) {}

                setInterval(removeYtAds, 800);
            })();
        """.trimIndent(), null)
    }

        private fun injectYoutubeHacksForced(view: WebView) {
        view.evaluateJavascript("""
            (function() {
                try {
                    Object.defineProperty(document, 'hidden', { get: function(){ return false; }, configurable: true });
                    var videos = document.querySelectorAll('video');
                    for (var i = 0; i < videos.length; i++) {
                        try { if (videos[i].paused) videos[i].play().catch(function(){}); } catch(e) {}
                    }
                } catch(e) {}
            })();
        """.trimIndent(), null)
    }

    private fun checkAdultSearchKeyword(url: String): String? {
        return try {
            val uri = android.net.Uri.parse(url)
            val host = uri.host?.lowercase() ?: return null
            if (!host.contains("youtube.com")) return null
            val query = (uri.getQueryParameter("search_query") ?: uri.getQueryParameter("q") ?: "").lowercase().trim()
            if (query.isEmpty()) return null
            val matched = ADULT_SEARCH_KEYWORDS.any { query.contains(it.lowercase()) }
            if (matched) buildAdultSearchBlockedPage(query) else null
        } catch (e: Exception) { null }
    }

    private fun buildAdultSearchBlockedPage(query: String): String {
        return """
            <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
                body { background:#0f0f0f; display:flex; flex-direction:column; align-items:center;
                       justify-content:center; height:100vh; margin:0; color:#fff; font-family:sans-serif;
                       text-align:center; padding:24px; box-sizing:border-box; }
                h2 { color:#ff4d4d; }
                button { margin-top:18px; padding:14px 28px; border:none; border-radius:24px;
                         background:#ff0000; color:#fff; font-size:15px; font-weight:700;
                         -webkit-tap-highlight-color: transparent; }
            </style></head>
            <body><h2>🔒 Adult Content Blocked</h2>
            <p>RasFocus Safe Mode এ এই কনটেন্ট দেখানো যাবে না।</p>
            <button onclick="if(window.RasYtBlockBridge){RasYtBlockBridge.onGoHome();}">🏠 YouTube হোমে ফিরে যান</button>
            </body></html>
        """.trimIndent()
    }

    /**
     * Adult-blocked page দেখানোর পর WebView এর URL stuck হয়ে যেত এবং ফেরার
     * কোনো উপায় ছিল না — এই bridge টা attach করা থাকলে block page এর "ফিরে
     * যান" বাটন সরাসরি Kotlin থেকে youtube.com এ loadUrl করে দেয়।
     */
    inner class YtBlockBridge(private val wv: WebView) {
        @android.webkit.JavascriptInterface
        fun onGoHome() {
            runOnUiThread { wv.loadUrl("https://m.youtube.com/") }
        }
    }

    private fun startBgAudioService() {
        webView?.evaluateJavascript("(function() { return document.title; })();") { titleResult ->
            val rawTitle = titleResult?.replace("\"", "")?.takeIf { it.isNotBlank() && it != "null" } ?: webView?.title ?: "YouTube — Playing"
            val title = rawTitle.removeSuffix(" - YouTube").removeSuffix(" – YouTube").trim()
            val url   = webView?.url ?: ""

            val videoId = try {
                val uri = android.net.Uri.parse(url)
                // watch?v=ID এবং youtu.be/ID দুটোই handle করো
                uri.getQueryParameter("v")
                    ?: if (uri.host?.contains("youtu.be") == true) uri.pathSegments.firstOrNull()
                    else uri.pathSegments.firstOrNull { it.length == 11 }
            } catch (_: Exception) { null }

            // hqdefault (480px) ব্যবহার করো — mqdefault মাঝে মাঝে না থাকলে blank আসে
            val thumbUrl = if (videoId != null)
                "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
            else null

            val svc = Intent(
                this,
                com.rasel.RasFocus.combo.selfcontrol.familybrowser.service.BackgroundAudioService::class.java
            ).apply {
                putExtra(com.rasel.RasFocus.combo.selfcontrol.familybrowser.service.BackgroundAudioService.EXTRA_TITLE, title)
                putExtra("extra_video_url", url)
                if (thumbUrl != null) putExtra("extra_thumb_url", thumbUrl)
                if (videoId != null) putExtra("extra_video_id", videoId)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
                else startService(svc)
            } catch (_: Exception) {}
        }
    }

    private fun stopBgAudioService() {
        try {
            stopService(Intent(this, com.rasel.RasFocus.combo.selfcontrol.familybrowser.service.BackgroundAudioService::class.java))
        } catch (_: Exception) {}
    }

    private fun injectSettingsRemover(view: WebView) {
        val prefs = getSharedPreferences("browser_settings", Context.MODE_PRIVATE)
        val hideShorts = prefs.getBoolean("yt_hide_shorts", false)
        val hideComments = prefs.getBoolean("yt_hide_comments", false)
        val grayscale = prefs.getBoolean("yt_grayscale", false)
        
        if (!hideShorts && !hideComments && !grayscale) return
        
        val js = """
            (function() {
                if (window.__rasYtSettingsRemover__) return;
                window.__rasYtSettingsRemover__ = true;
                
                if ($grayscale) {
                    document.documentElement.style.filter = 'grayscale(100%)';
                }
                
                function applySettings() {
                    try {
                        if ($hideShorts) {
                            // Remove Shorts bottom navigation tab
                            var shortsTabs = document.querySelectorAll('ytm-pivot-bar-item-renderer');
                            shortsTabs.forEach(function(tab) {
                                var text = (tab.innerText || '').toLowerCase();
                                if (text.indexOf('shorts') !== -1) tab.style.display = 'none';
                            });
                            
                            // Remove Shorts shelf in home feed
                            var shelves = document.querySelectorAll('ytm-rich-section-renderer, ytm-reel-shelf-renderer');
                            shelves.forEach(function(shelf) {
                                var text = (shelf.innerText || '').toLowerCase();
                                if (text.indexOf('shorts') !== -1) shelf.style.display = 'none';
                            });
                        }
                        
                        if ($hideComments) {
                            var comments = document.querySelectorAll('ytm-item-section-renderer[section-identifier="comment-item-section"], ytm-comments-entry-point-header-renderer');
                            comments.forEach(function(comment) {
                                comment.style.display = 'none';
                            });
                        }
                    } catch(e) {}
                }
                applySettings();
                setInterval(applySettings, 1000);
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }
}

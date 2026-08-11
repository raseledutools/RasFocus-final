package com.rasel.RasFocus.filemanager

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────
// Constants
// ─────────────────────────────────────────────────────────────────────────────
private const val BASE_SCALE        = 3f      // render resolution multiplier
private const val MIN_ZOOM          = 1f
private const val MAX_ZOOM          = 6f
private const val DOUBLE_TAP_ZOOM   = 2.8f   // zoom level when double-tapping
private const val RERENDER_DEBOUNCE = 250L   // ms — skip renders mid-pinch (longer = smoother)
private const val DOUBLE_TAP_MS     = 280L   // max ms between two taps
private const val SCROLL_HIDE_DELAY = 80L    // ms before hiding header on scroll start
private const val MAX_BITMAP_DIM    = 4096   // safety cap for bitmap dimensions
private const val ZOOM_SMOOTH_FLING = 0.88f  // inertia damping for pinch-end momentum

class FMPdfViewerActivity : ComponentActivity() {
    private var pfd: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = intent.data ?: intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
        openPdf(uri)
        setContent {
            MaterialTheme {
                if (pdfRenderer != null) {
                    RobustPdfViewer(pdfRenderer = pdfRenderer!!, onBack = { finish() })
                } else {
                    ErrorScreen("PDF খোলা যায়নি।")
                }
            }
        }
    }

    private fun openPdf(uri: Uri?) {
        pdfRenderer?.close(); pfd?.close()
        pdfRenderer = null; pfd = null
        if (uri == null) return
        try {
            pfd = contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) pdfRenderer = PdfRenderer(pfd!!)
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onNewIntent(newIntent: android.content.Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        val uri: Uri? = newIntent.data ?: newIntent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
        openPdf(uri)
    }

    override fun onDestroy() {
        super.onDestroy()
        pdfRenderer?.close()
        pfd?.close()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Main Viewer Composable
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RobustPdfViewer(pdfRenderer: PdfRenderer, onBack: () -> Unit, title: String? = null) {
    val pageCount   = pdfRenderer.pageCount
    val listState   = rememberLazyListState()
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }
    val scope       = rememberCoroutineScope()

    // ── Zoom / Pan ─────────────────────────────────────────────────────────
    // Use raw (non-animated) for gesture math, animated for rendering
    var zoom    by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    // Smooth zoom: spring for pinch feel, tween for double-tap snap
    var useSpring by remember { mutableStateOf(false) }
    val animZoom by animateFloatAsState(
        targetValue    = zoom,
        animationSpec  = if (useSpring)
            spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioMediumBouncy)
        else
            tween(durationMillis = 180),
        label = "zoom"
    )
    // Pan always uses tween for smooth glide feel
    val animOffsetX by animateFloatAsState(offsetX, animationSpec = tween(120), label = "ox")
    val animOffsetY by animateFloatAsState(offsetY, animationSpec = tween(120), label = "oy")

    // ── Header visibility — track scroll direction for hide/show ──────────
    var headerVisible    by remember { mutableStateOf(true) }
    var lastScrollOffset by remember { mutableStateOf(0) }
    // We use a stable "scroll is happening" signal to avoid jitter
    var scrollHideJob    by remember { mutableStateOf<Job?>(null) }

    // ── Container size ────────────────────────────────────────────────────
    var containerW by remember { mutableStateOf(1f) }
    var containerH by remember { mutableStateOf(1f) }

    // Clamp pan so page never flies off screen
    fun clampPan(ox: Float, oy: Float, z: Float): Pair<Float, Float> {
        val maxX = containerW * (z - 1f) / 2f
        val maxY = containerH * (z - 1f) / 2f
        return Pair(ox.coerceIn(-maxX, maxX), oy.coerceIn(-maxY, maxY))
    }

    // ── Header smart auto-hide: hide when scrolling down, show when up ────
    // This runs whenever scroll position changes
    LaunchedEffect(listState.firstVisibleItemScrollOffset, listState.firstVisibleItemIndex) {
        if (zoom > 1.05f) return@LaunchedEffect  // zoomed: don't auto-show header from scroll

        val currentOffset = listState.firstVisibleItemIndex * 10000 + listState.firstVisibleItemScrollOffset
        val delta         = currentOffset - lastScrollOffset
        lastScrollOffset  = currentOffset

        if (listState.isScrollInProgress) {
            scrollHideJob?.cancel()
            scrollHideJob = scope.launch {
                delay(SCROLL_HIDE_DELAY)
                if (delta > 30) {
                    // Scrolling DOWN → hide header
                    headerVisible = false
                }
                // Scrolling UP → show header (handled below)
                if (delta < -30) {
                    headerVisible = true
                }
            }
        }
    }

    // When scroll stops, don't auto-show header (user must tap to show)
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            scrollHideJob?.cancel()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D1A))
            .onGloballyPositioned {
                containerW = it.size.width.toFloat().coerceAtLeast(1f)
                containerH = it.size.height.toFloat().coerceAtLeast(1f)
            }
            // ── Unified gesture detector (outer Box layer) ────────────────
            .pointerInput(Unit) {
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    val downTime  = System.currentTimeMillis()
                    val downPos   = firstDown.position

                    var dirLocked = false
                    var isHoriz   = false

                    do {
                        val event      = awaitPointerEvent()
                        val fingers    = event.changes.count { it.pressed }
                        val anyConsumed = event.changes.any { it.isConsumed }

                        if (!anyConsumed) {
                            when {
                                // ── PINCH: 2+ fingers — smooth zoom ──────────
                                fingers >= 2 -> {
                                    dirLocked = false; isHoriz = false
                                    useSpring = false  // tween during active pinch for directness

                                    val zChange = event.calculateZoom()
                                    val pan     = event.calculatePan()

                                    // Apply zoom centered on pinch midpoint for natural feel
                                    val newZ = (zoom * zChange).coerceIn(MIN_ZOOM, MAX_ZOOM)

                                    // Smooth pan during pinch
                                    val (cx, cy) = clampPan(
                                        offsetX + pan.x * 0.9f,  // slight damping for smoothness
                                        offsetY + pan.y * 0.9f,
                                        newZ
                                    )
                                    zoom    = newZ
                                    offsetX = cx
                                    offsetY = cy

                                    if (newZ > 1.1f) headerVisible = false
                                    event.changes.forEach { it.consume() }
                                }

                                // ── SINGLE FINGER while zoomed — pan ─────────
                                fingers == 1 && zoom > 1.05f -> {
                                    val ch    = event.changes.firstOrNull() ?: break
                                    val delta = ch.position - ch.previousPosition

                                    if (!dirLocked && (abs(delta.x) > 3f || abs(delta.y) > 3f)) {
                                        isHoriz   = abs(delta.x) >= abs(delta.y)
                                        dirLocked = true
                                    }
                                    if (dirLocked) {
                                        if (isHoriz) {
                                            val (cx, cy) = clampPan(offsetX + delta.x, offsetY, zoom)
                                            offsetX = cx; offsetY = cy
                                            ch.consume()
                                        }
                                        // Vertical → pass through to LazyColumn for natural scroll
                                    }
                                }
                                // zoom==1, single finger → LazyColumn scrolls freely (no interference)
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    // After pinch ends — apply spring for bouncy settle
                    if (zoom > 1.02f) {
                        useSpring = true
                    }
                }
            }
    ) {
        // ── Scrollable PDF pages ──────────────────────────────────────────
        // NOTE: graphicsLayer is applied to the LazyColumn so the zoom
        // transform is outside the scroll mechanism — this is the key fix
        // for Android 10 smooth scrolling. The LazyColumn itself is never
        // scaled; only its visual output is transformed.
        LazyColumn(
            state               = listState,
            modifier            = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Use animated values for smooth visual interpolation
                    scaleX          = animZoom
                    scaleY          = animZoom
                    translationX    = animOffsetX
                    translationY    = animOffsetY
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    clip            = false
                    // Android 10 hardware layer hint for smoother rendering
                    renderEffect    = null
                }
                // Double-tap & single-tap detection on list
                .pointerInput(Unit) {
                    var lastTapTime = 0L
                    var lastTapPos  = Offset.Zero

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val now  = System.currentTimeMillis()
                        val pos  = down.position

                        // Await lift
                        do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                        val tapDur = System.currentTimeMillis() - now

                        if (tapDur < 300) {
                            val sinceLast = now - lastTapTime
                            val nearPos   = abs(pos.x - lastTapPos.x) < 80f && abs(pos.y - lastTapPos.y) < 80f

                            if (sinceLast < DOUBLE_TAP_MS && nearPos) {
                                // ── DOUBLE TAP ────────────────────────────────
                                useSpring = true  // spring animation for satisfying snap
                                if (zoom > 1.5f) {
                                    zoom    = 1f
                                    offsetX = 0f
                                    offsetY = 0f
                                    headerVisible = true
                                } else {
                                    val newZ = DOUBLE_TAP_ZOOM
                                    val tapX = pos.x - containerW / 2f
                                    val tapY = pos.y - containerH / 2f
                                    zoom    = newZ
                                    val (cx, cy) = clampPan(
                                        -tapX * (newZ - 1f),
                                        -tapY * (newZ - 1f) * 0.25f,
                                        newZ
                                    )
                                    offsetX = cx; offsetY = cy
                                    headerVisible = false
                                }
                                lastTapTime = 0L
                            } else {
                                // ── SINGLE TAP — toggle header after double-tap timeout ──
                                lastTapTime = now
                                lastTapPos  = pos
                                scope.launch {
                                    delay(DOUBLE_TAP_MS + 30)
                                    if (lastTapTime == now) {
                                        headerVisible = !headerVisible
                                    }
                                }
                            }
                        }
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding      = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(pageCount) { idx ->
                RobustPdfPage(
                    pdfRenderer = pdfRenderer,
                    pageIndex   = idx,
                    zoom        = zoom
                )
            }
        }

        // ── Header (smooth slide-in/out animation) ────────────────────────
        val headerAlpha by animateFloatAsState(
            if (headerVisible) 1f else 0f,
            animationSpec = tween(200),
            label = "headerAlpha"
        )
        val headerSlide by animateFloatAsState(
            if (headerVisible) 0f else -160f,
            animationSpec = spring(stiffness = Spring.StiffnessMedium),
            label = "headerSlide"
        )

        // Only compose header when needed (avoid always-composing invisible header)
        if (headerAlpha > 0.01f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha        = headerAlpha
                        translationY = headerSlide
                    }
                    .background(Color(0xEE1A1A2E))  // slightly more opaque for readability
                    .statusBarsPadding()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                    Text(
                        text = if (title != null) "$title  ·  $currentPage / $pageCount"
                               else "PDF Viewer  ($pageCount pages)",
                        color    = Color.White,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Per-page renderer — no black/white flash on zoom
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun RobustPdfPage(
    pdfRenderer: PdfRenderer,
    pageIndex:   Int,
    zoom:        Float
) {
    val mutex = remember { Mutex() }
    val scope = rememberCoroutineScope()

    // Keep the previous bitmap visible while new one renders — eliminates flash
    var bitmap        by remember { mutableStateOf<Bitmap?>(null) }
    var pendingBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var renderedScale by remember { mutableStateOf(0f) }
    var debounceJob   by remember { mutableStateOf<Job?>(null) }

    val targetScale = (BASE_SCALE * zoom.coerceIn(MIN_ZOOM, MAX_ZOOM))
        .coerceIn(BASE_SCALE, BASE_SCALE * MAX_ZOOM)

    // Fast initial render at BASE_SCALE
    LaunchedEffect(pageIndex) {
        if (bitmap != null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                mutex.withLock {
                    val page = pdfRenderer.openPage(pageIndex)
                    val (w, h) = scaledDims(page.width, page.height, BASE_SCALE)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    bitmap        = bmp
                    renderedScale = BASE_SCALE
                }
            } catch (_: Exception) {}
        }
    }

    // Debounced re-render at higher zoom — KEEP old bitmap visible until new is ready
    LaunchedEffect(targetScale) {
        if (bitmap == null) return@LaunchedEffect
        if (abs(targetScale - renderedScale) < 0.15f) return@LaunchedEffect

        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(RERENDER_DEBOUNCE)
            withContext(Dispatchers.IO) {
                try {
                    mutex.withLock {
                        val page = pdfRenderer.openPage(pageIndex)
                        val (w, h) = scaledDims(page.width, page.height, targetScale)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        // Atomically swap — old bitmap stays visible until this line
                        bitmap        = bmp
                        renderedScale = targetScale
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // Always show something — old bitmap stays while re-rendering (no flash!)
    if (bitmap != null) {
        Image(
            bitmap             = bitmap!!.asImageBitmap(),
            contentDescription = "Page ${pageIndex + 1}",
            contentScale       = ContentScale.FillWidth,
            modifier           = Modifier
                .fillMaxWidth()
                .background(Color.White)
        )
    } else {
        // First load placeholder — A4 ratio
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.707f)
                .background(Color(0xFFF0F0F5)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color       = Color(0xFF6C63FF),
                strokeWidth = 2.dp,
                modifier    = Modifier.size(32.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────
private fun scaledDims(pageW: Int, pageH: Int, scale: Float): Pair<Int, Int> {
    var w = (pageW * scale).roundToInt()
    var h = (pageH * scale).roundToInt()
    if (w > MAX_BITMAP_DIM || h > MAX_BITMAP_DIM) {
        val ratio = MAX_BITMAP_DIM.toFloat() / maxOf(w, h)
        w = (w * ratio).roundToInt()
        h = (h * ratio).roundToInt()
    }
    return Pair(w.coerceAtLeast(1), h.coerceAtLeast(1))
}

@Composable
fun ErrorScreen(msg: String) {
    Box(
        Modifier.fillMaxSize().background(Color(0xFF0D0D1A)),
        contentAlignment = Alignment.Center
    ) { Text(msg, color = Color(0xFFFF5C5C), fontSize = 15.sp) }
}

// ─────────────────────────────────────────────────────────────────────────────
// FMPdfViewerScreen — used from NavState (file path entry point)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun FMPdfViewerScreen(filePath: String, onBack: () -> Unit, title: String? = null) {
    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var loadError   by remember { mutableStateOf(false) }

    DisposableEffect(filePath) {
        var pfd: ParcelFileDescriptor? = null
        try {
            pfd         = ParcelFileDescriptor.open(java.io.File(filePath), ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(pfd!!)
        } catch (e: Exception) {
            e.printStackTrace(); loadError = true
        }
        onDispose {
            pdfRenderer?.close()
            pfd?.close()
        }
    }

    when {
        loadError           -> ErrorScreen("PDF খোলা যায়নি।")
        pdfRenderer != null -> RobustPdfViewer(pdfRenderer = pdfRenderer!!, onBack = onBack, title = title)
        else -> Box(
            Modifier.fillMaxSize().background(Color(0xFF0D0D1A)),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator(color = Color(0xFF6C63FF)) }
    }
}

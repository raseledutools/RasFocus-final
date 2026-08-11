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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
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
private const val BASE_SCALE          = 3f      // render resolution multiplier
private const val MIN_ZOOM            = 1f
private const val MAX_ZOOM            = 6f
private const val DOUBLE_TAP_ZOOM     = 2.8f    // zoom level when double-tapping
private const val RERENDER_DEBOUNCE   = 180L    // ms — skip renders mid-pinch
private const val DOUBLE_TAP_MS       = 260L    // max ms between two taps
private const val SCROLL_HIDE_DELAY   = 100L    // ms before hiding header on scroll
private const val MAX_BITMAP_DIM      = 4096    // safety cap for bitmap dimensions

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
    val pageCount      = pdfRenderer.pageCount
    val listState      = rememberLazyListState()
    val currentPage    by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }
    val scope          = rememberCoroutineScope()

    // ── Zoom / Pan ────────────────────────────────────────────────────────────
    var zoom    by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    // Animated values for smooth double-tap snap
    val animZoom    by animateFloatAsState(zoom,    animationSpec = spring(stiffness = Spring.StiffnessMediumLow), label = "zoom")
    val animOffsetX by animateFloatAsState(offsetX, animationSpec = tween(220), label = "ox")
    val animOffsetY by animateFloatAsState(offsetY, animationSpec = tween(220), label = "oy")

    // ── Header visibility ─────────────────────────────────────────────────────
    var headerVisible by remember { mutableStateOf(true) }

    // ── Container size ────────────────────────────────────────────────────────
    var containerW by remember { mutableStateOf(1f) }
    var containerH by remember { mutableStateOf(1f) }

    // Clamp pan so page never flies off screen
    fun clampPan(ox: Float, oy: Float, z: Float): Pair<Float, Float> {
        val maxX = containerW * (z - 1f) / 2f
        val maxY = containerH * (z - 1f) / 2f
        return Pair(ox.coerceIn(-maxX, maxX), oy.coerceIn(-maxY, maxY))
    }

    // Hide header when scrolling
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && zoom <= 1.05f) {
            delay(SCROLL_HIDE_DELAY)
            headerVisible = false
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
            // ── Unified gesture detector ──────────────────────────────────────
            .pointerInput(Unit) {
                awaitEachGesture {
                    // Wait for first contact
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    val downTime  = System.currentTimeMillis()
                    val downPos   = firstDown.position

                    var currentZoom = zoom
                    var dirLocked   = false
                    var isHoriz     = false

                    // Track for double-tap detection
                    var liftTime  = 0L
                    var liftCount = 0

                    do {
                        val event      = awaitPointerEvent()
                        val fingers    = event.changes.count { it.pressed }
                        val anyConsumed = event.changes.any { it.isConsumed }

                        // Count lifts for double-tap
                        event.changes.filter { !it.pressed }.forEach { _ -> liftCount++ }

                        if (!anyConsumed) {
                            when {
                                // ── PINCH: 2+ fingers ─────────────────────────
                                fingers >= 2 -> {
                                    dirLocked = false; isHoriz = false
                                    val zChange = event.calculateZoom()
                                    val pan     = event.calculatePan()
                                    val newZ    = (currentZoom * zChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                    currentZoom = newZ; zoom = newZ
                                    val (cx, cy) = clampPan(offsetX + pan.x, offsetY + pan.y, newZ)
                                    offsetX = cx; offsetY = cy
                                    if (newZ > 1.02f) headerVisible = false
                                    event.changes.forEach { it.consume() }
                                }

                                // ── SINGLE FINGER while zoomed ────────────────
                                fingers == 1 && zoom > 1.05f -> {
                                    val ch    = event.changes.firstOrNull() ?: break
                                    val delta = ch.position - ch.previousPosition
                                    if (!dirLocked && (abs(delta.x) > 4f || abs(delta.y) > 4f)) {
                                        isHoriz   = abs(delta.x) >= abs(delta.y)
                                        dirLocked = true
                                    }
                                    if (dirLocked) {
                                        if (isHoriz) {
                                            val (cx, cy) = clampPan(offsetX + delta.x, offsetY, zoom)
                                            offsetX = cx; offsetY = cy
                                            ch.consume()
                                        }
                                        // vertical → let LazyColumn handle
                                    }
                                }
                                // zoom==1, single finger → LazyColumn scrolls freely
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    // ── TAP analysis after all fingers lift ───────────────────
                    val elapsed = System.currentTimeMillis() - downTime
                    if (elapsed < DOUBLE_TAP_MS) {
                        // Could be a tap; check if it's double-tap
                        // We use a simple approach: launch a coroutine, wait for possible second tap
                        scope.launch {
                            delay(DOUBLE_TAP_MS)
                            // By now, if user double-tapped, gesture system would have fired again
                            // We track via a shared mutable — simpler: just toggle on second down
                        }
                    }
                }
            }
    ) {
        // ── Actual scrollable PDF content ─────────────────────────────────────
        LazyColumn(
            state                = listState,
            modifier             = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX          = animZoom
                    scaleY          = animZoom
                    translationX    = animOffsetX
                    translationY    = animOffsetY
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    clip            = false
                }
                // Double-tap & single-tap on the list itself
                .pointerInput(Unit) {
                    var lastTapTime = 0L
                    var lastTapPos  = Offset.Zero
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val now  = System.currentTimeMillis()
                        val pos  = down.position

                        // Wait for lift
                        do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })

                        val liftNow = System.currentTimeMillis()
                        val tapDur  = liftNow - now

                        if (tapDur < 300) {
                            val sinceLast = now - lastTapTime
                            if (sinceLast < DOUBLE_TAP_MS && abs(pos.x - lastTapPos.x) < 80f && abs(pos.y - lastTapPos.y) < 80f) {
                                // ── DOUBLE TAP ────────────────────────────────
                                if (zoom > 1.5f) {
                                    // Reset to fit
                                    zoom    = 1f
                                    offsetX = 0f
                                    offsetY = 0f
                                    headerVisible = true
                                } else {
                                    // Zoom into tap position
                                    val newZ   = DOUBLE_TAP_ZOOM
                                    val tapX   = pos.x - containerW / 2f
                                    val tapY   = pos.y - containerH / 2f
                                    zoom    = newZ
                                    val (cx, cy) = clampPan(-tapX * (newZ - 1f), -tapY * (newZ - 1f) * 0.3f, newZ)
                                    offsetX = cx; offsetY = cy
                                    headerVisible = false
                                }
                                lastTapTime = 0L
                            } else {
                                // ── SINGLE TAP — toggle header ────────────────
                                lastTapTime = now
                                lastTapPos  = pos
                                scope.launch {
                                    delay(DOUBLE_TAP_MS + 20)
                                    if (lastTapTime == now) {  // no second tap came
                                        headerVisible = !headerVisible
                                    }
                                }
                            }
                        }
                    }
                },
            horizontalAlignment  = Alignment.CenterHorizontally,
            contentPadding       = PaddingValues(vertical = 6.dp),
            verticalArrangement  = Arrangement.spacedBy(4.dp)
        ) {
            items(pageCount) { idx ->
                RobustPdfPage(
                    pdfRenderer = pdfRenderer,
                    pageIndex   = idx,
                    zoom        = zoom
                )
            }
        }

        // ── Header (animated hide/show) ───────────────────────────────────────
        val headerAlpha by animateFloatAsState(
            if (headerVisible) 1f else 0f,
            animationSpec = tween(250),
            label = "headerAlpha"
        )
        val headerOffset by animateFloatAsState(
            if (headerVisible) 0f else -200f,
            animationSpec = tween(250),
            label = "headerOffset"
        )

        if (headerAlpha > 0.01f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha        = headerAlpha
                        translationY = headerOffset
                    }
                    .background(Color(0xF01A1A2E))
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
                        text     = if (title != null) "$title  ·  Slide $currentPage / $pageCount"
                                   else "PDF Viewer  ($pageCount pages)",
                        color    = Color.White,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f).padding(start = 4.dp)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Per-page renderer — fast first render, debounced high-quality re-render
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun RobustPdfPage(
    pdfRenderer: PdfRenderer,
    pageIndex:   Int,
    zoom:        Float
) {
    val mutex = remember { Mutex() }
    val scope = rememberCoroutineScope()

    var bitmap        by remember { mutableStateOf<Bitmap?>(null) }
    var renderedScale by remember { mutableStateOf(0f) }
    var debounceJob   by remember { mutableStateOf<Job?>(null) }

    val targetScale = (BASE_SCALE * zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)).coerceIn(BASE_SCALE, BASE_SCALE * MAX_ZOOM)

    // Fast initial render at BASE_SCALE (no delay)
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

    // Debounced re-render at higher zoom
    LaunchedEffect(targetScale) {
        if (bitmap == null) return@LaunchedEffect
        if (abs(targetScale - renderedScale) < 0.12f) return@LaunchedEffect
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
                        bitmap        = bmp
                        renderedScale = targetScale
                    }
                } catch (_: Exception) {}
            }
        }
    }

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
        // Placeholder — A4 ratio, no black flash
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.707f)
                .background(Color(0xFFF5F5F5)),
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
        loadError             -> ErrorScreen("PDF খোলা যায়নি।")
        pdfRenderer != null   -> RobustPdfViewer(pdfRenderer = pdfRenderer!!, onBack = onBack, title = title)
        else -> Box(
            Modifier.fillMaxSize().background(Color(0xFF0D0D1A)),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator(color = Color(0xFF6C63FF)) }
    }
}

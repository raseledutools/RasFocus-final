package com.rasel.RasFocus.filemanager

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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

class FMPdfViewerActivity : ComponentActivity() {
    private var pfd: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = intent.data ?: intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)

        if (uri != null) {
            try {
                pfd = contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) pdfRenderer = PdfRenderer(pfd!!)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        setContent {
            MaterialTheme {
                if (pdfRenderer != null) {
                    PdfZoomViewer(
                        pdfRenderer = pdfRenderer!!,
                        onBack = { finish() }
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(Color(0xFF0D0D1A)),
                        contentAlignment = Alignment.Center) {
                        Text("Failed to open PDF.", color = Color.Red)
                    }
                }
            }
        }
    }

    override fun onNewIntent(newIntent: android.content.Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        pdfRenderer?.close(); pfd?.close()
        val uri: Uri? = newIntent.data ?: newIntent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
        if (uri != null) {
            try {
                pfd = contentResolver.openFileDescriptor(uri, "r")
                pdfRenderer = if (pfd != null) PdfRenderer(pfd!!) else null
            } catch (e: Exception) { e.printStackTrace(); pdfRenderer = null }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pdfRenderer?.close()
        pfd?.close()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BASE RENDER SCALE — 3× native page width for crisp text at normal zoom
// ─────────────────────────────────────────────────────────────────────────────
private const val BASE_SCALE = 3f
private const val MIN_ZOOM   = 1f
private const val MAX_ZOOM   = 5f
// After the user stops gesturing, wait this long before re-rendering at the
// new zoom level (avoids re-rendering on every frame while pinching)
private const val RERENDER_DEBOUNCE_MS = 200L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfZoomViewer(pdfRenderer: PdfRenderer, onBack: () -> Unit) {
    val pageCount = pdfRenderer.pageCount

    // ── Global zoom / pan state — shared across ALL pages ────────────────────
    // zoom: two-finger pinch scales ALL pages simultaneously
    // offsetX: horizontal pan applied to the whole document (only when zoomed)
    var zoom    by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    val listState = rememberLazyListState()

    // Container width — needed to clamp horizontal pan so page never disappears
    var containerWidthPx by remember { mutableStateOf(0f) }

    // Clamp offsetX so the page never goes fully off-screen
    fun clampOffsetX(ox: Float, z: Float): Float {
        val maxPan = containerWidthPx * (z - 1f) / 2f
        return ox.coerceIn(-maxPan, maxPan)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PDF Viewer", color = Color.White, fontSize = 16.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A2E))
            )
        },
        containerColor = Color(0xFF1A1A2E)
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .onGloballyPositioned { coords ->
                    containerWidthPx = coords.size.width.toFloat()
                }
                // ── PINCH ZOOM + SINGLE-FINGER HORIZONTAL PAN WHEN ZOOMED ────
                // Two fingers  → zoom all pages + optional horizontal pan
                // One finger   → if zoomed: horizontal drag pans; vertical drag scrolls list
                // One finger   → if zoom==1: LazyColumn handles everything normally
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)

                        var currentZoom = zoom
                        // Track whether first-event direction is decided
                        var directionLocked = false   // true once we know H vs V
                        var isHorizontal    = false

                        do {
                            val event = awaitPointerEvent()
                            val canceled = event.changes.any { it.isConsumed }
                            if (canceled) break

                            val fingerCount = event.changes.count { it.pressed }

                            if (fingerCount >= 2) {
                                // ── PINCH: zoom + horizontal pan ─────────────────
                                directionLocked = false   // reset after pinch
                                isHorizontal    = false

                                val zoomChange = event.calculateZoom()
                                val pan        = event.calculatePan()

                                val newZoom = (currentZoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                currentZoom = newZoom
                                zoom        = newZoom

                                // Allow panning horizontally while pinching
                                offsetX = clampOffsetX(offsetX + pan.x, newZoom)

                                event.changes.forEach { it.consume() }

                            } else if (fingerCount == 1 && zoom > 1.01f) {
                                // ── SINGLE FINGER while zoomed ───────────────────
                                val change = event.changes.firstOrNull() ?: break
                                val delta  = change.position - change.previousPosition

                                if (!directionLocked) {
                                    // Decide direction on first meaningful move
                                    if (abs(delta.x) > 3f || abs(delta.y) > 3f) {
                                        isHorizontal    = abs(delta.x) >= abs(delta.y)
                                        directionLocked = true
                                    }
                                }

                                if (directionLocked && isHorizontal) {
                                    // Horizontal → pan the document
                                    offsetX = clampOffsetX(offsetX + delta.x, zoom)
                                    change.consume()
                                }
                                // Vertical → do NOT consume; LazyColumn scrolls naturally
                            }
                            // zoom == 1 with single finger → LazyColumn handles everything
                        } while (event.changes.any { it.pressed })
                    }
                }
                // Double-tap: toggle 1× ↔ 2.5× and reset pan
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        zoom    = if (zoom < 1.5f) 2.5f else 1f
                        offsetX = 0f
                    })
                }
        ) {
            // ── LazyColumn wrapped in graphicsLayer so ALL pages zoom together ──
            // scaleX/scaleY on the LazyColumn = every page scales as one surface.
            // translationX = horizontal pan applied to the whole document.
            // transformOrigin(0.5, 0) = scale from top-center so pages stay
            //   anchored at the top while zooming (no jump when pinching).
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX          = zoom
                        scaleY          = zoom
                        translationX    = offsetX
                        // No translationY — LazyColumn handles vertical scroll natively
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                        clip            = false  // let zoomed content exceed screen bounds
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(pageCount) { index ->
                    ZoomablePdfPage(
                        pdfRenderer = pdfRenderer,
                        pageIndex   = index,
                        zoom        = zoom
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Per-page composable: renders at (BASE_SCALE × zoom) resolution, debounced.
// While the new bitmap is loading the old one stays visible — no flash.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ZoomablePdfPage(
    pdfRenderer: PdfRenderer,
    pageIndex:   Int,
    zoom:        Float
) {
    val mutex  = remember { Mutex() }
    val scope  = rememberCoroutineScope()

    // Current visible bitmap (may be from a lower zoom level while re-rendering)
    var bitmap        by remember { mutableStateOf<Bitmap?>(null) }
    var renderedScale by remember { mutableStateOf(0f) }
    var debounceJob   by remember { mutableStateOf<Job?>(null) }

    // Target render scale = BASE_SCALE × zoom, clamped to a safe ceiling
    val targetScale = (BASE_SCALE * zoom).coerceIn(BASE_SCALE, BASE_SCALE * MAX_ZOOM)

    // Re-render when zoom changes — debounced so we skip mid-pinch frames
    LaunchedEffect(targetScale) {
        if (bitmap != null && kotlin.math.abs(targetScale - renderedScale) < 0.15f) return@LaunchedEffect
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(RERENDER_DEBOUNCE_MS)
            withContext(Dispatchers.IO) {
                try {
                    mutex.withLock {
                        val page = pdfRenderer.openPage(pageIndex)
                        val w = (page.width  * targetScale).roundToInt()
                        val h = (page.height * targetScale).roundToInt()
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

    // Initial render at BASE_SCALE on first composition
    LaunchedEffect(pageIndex) {
        if (bitmap != null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                mutex.withLock {
                    val page = pdfRenderer.openPage(pageIndex)
                    val w = (page.width  * BASE_SCALE).roundToInt()
                    val h = (page.height * BASE_SCALE).roundToInt()
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

    if (bitmap != null) {
        Image(
            bitmap             = bitmap!!.asImageBitmap(),
            contentDescription = "Page ${pageIndex + 1}",
            modifier           = Modifier
                .fillMaxWidth()
                .background(Color.White),
            contentScale = ContentScale.FillWidth
        )
    } else {
        // Placeholder while loading — preserves A4-ish aspect ratio
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.707f)
                .background(Color(0xFF2A2A3E)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = Color(0xFF6C63FF), strokeWidth = 2.dp)
        }
    }
}

// ── FMPdfViewerScreen ─────────────────────────────────────────────────────────
// Composable wrapper for PdfZoomViewer — opens a file path directly.
// Used from HomeScreen's NavState.PdfViewer so back returns to the folder.
@Composable
fun FMPdfViewerScreen(filePath: String, onBack: () -> Unit) {
    val file = java.io.File(filePath)
    var pdfRenderer by remember { mutableStateOf<android.graphics.pdf.PdfRenderer?>(null) }
    var loadError   by remember { mutableStateOf(false) }

    DisposableEffect(filePath) {
        var pfd: android.os.ParcelFileDescriptor? = null
        try {
            pfd = android.os.ParcelFileDescriptor.open(
                file, android.os.ParcelFileDescriptor.MODE_READ_ONLY
            )
            pdfRenderer = android.graphics.pdf.PdfRenderer(pfd!!)
        } catch (e: Exception) {
            e.printStackTrace()
            loadError = true
        }
        onDispose {
            pdfRenderer?.close()
            pfd?.close()
        }
    }

    when {
        loadError -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0D0D1A)),
                contentAlignment = Alignment.Center
            ) {
                Text("Failed to open PDF.", color = Color.Red)
            }
        }
        pdfRenderer != null -> {
            PdfZoomViewer(pdfRenderer = pdfRenderer!!, onBack = onBack)
        }
        else -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0D0D1A)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color(0xFF6C63FF))
            }
        }
    }
}

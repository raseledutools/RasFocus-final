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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    // ── Global zoom / pan state ───────────────────────────────────────────────
    var zoom   by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newZoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        zoom   = newZoom
        offset = offset + panChange
    }

    // Double-tap: toggle between 1× and 2.5×
    val doubleTapZoom = 2.5f

    val listState = rememberLazyListState()

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
                // transformable handles pinch-zoom + pan
                .transformable(state = transformState)
                // double-tap to zoom in/out
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        zoom   = if (zoom < 1.5f) doubleTapZoom else 1f
                        offset = Offset.Zero
                    })
                }
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX        = zoom
                        scaleY        = zoom
                        translationX  = offset.x
                        translationY  = offset.y
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
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
// While the new bitmap is being rendered the old one stays visible — no flash.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ZoomablePdfPage(
    pdfRenderer: PdfRenderer,
    pageIndex:   Int,
    zoom:        Float
) {
    val mutex    = remember { Mutex() }
    val scope    = rememberCoroutineScope()

    // Current visible bitmap (may be from a lower zoom level while re-rendering)
    var bitmap   by remember { mutableStateOf<Bitmap?>(null) }
    // Track which scale the current bitmap was rendered at
    var renderedScale by remember { mutableStateOf(0f) }
    var debounceJob   by remember { mutableStateOf<Job?>(null) }

    // Target render scale: BASE_SCALE × zoom, clamped to reasonable max
    val targetScale = (BASE_SCALE * zoom).coerceIn(BASE_SCALE, BASE_SCALE * MAX_ZOOM)

    // Re-render whenever zoom changes — debounced so we don't render every frame
    LaunchedEffect(targetScale) {
        // If the difference from the currently-rendered scale is small, skip
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
            bitmap           = bitmap!!.asImageBitmap(),
            contentDescription = "Page ${pageIndex + 1}",
            modifier         = Modifier
                .fillMaxWidth()
                .background(Color.White),
            contentScale     = ContentScale.FillWidth
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
    var loadError by remember { mutableStateOf(false) }

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
            androidx.compose.foundation.layout.Box(
                modifier = androidx.compose.ui.Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color(0xFF0D0D1A)),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                androidx.compose.material3.Text("Failed to open PDF.", color = androidx.compose.ui.graphics.Color.Red)
            }
        }
        pdfRenderer != null -> {
            PdfZoomViewer(pdfRenderer = pdfRenderer!!, onBack = onBack)
        }
        else -> {
            androidx.compose.foundation.layout.Box(
                modifier = androidx.compose.ui.Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color(0xFF0D0D1A)),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                androidx.compose.material3.CircularProgressIndicator(color = androidx.compose.ui.graphics.Color(0xFF6C63FF))
            }
        }
    }
}

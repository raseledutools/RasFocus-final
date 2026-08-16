package com.rasel.RasFocus.filemanager

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
private const val BASE_SCALE        = 2f   // 3→2: প্রতিটা page এর initial bitmap ছোট, OOM কমে
private const val MIN_ZOOM          = 1f
private const val MAX_ZOOM          = 6f
private const val DOUBLE_TAP_ZOOM   = 2.8f
private const val RERENDER_DEBOUNCE = 250L
private const val DOUBLE_TAP_MS     = 280L
private const val MAX_BITMAP_DIM    = 4096
private const val ZOOM_SMOOTH_FLING = 0.88f

class FMPdfViewerActivity : ComponentActivity() {
    private var pfd: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null

    companion object {
        /** Internal launches pass this extra so we know NOT to finishAndRemoveTask on back */
        const val EXTRA_INTERNAL_LAUNCH = "rasfocus_internal_launch"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isInternal = intent.getBooleanExtra(EXTRA_INTERNAL_LAUNCH, false)
        val uri: Uri? = intent.data ?: intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
        openPdf(uri)
        setContent {
            MaterialTheme {
                if (pdfRenderer != null) {
                    RobustPdfViewer(
                        pdfRenderer = pdfRenderer!!,
                        title       = uri?.let { getFileNameFromUri(it) },
                        onBack = {
                            if (isInternal) finish()
                            else finishAndRemoveTask()
                        }
                    )
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
            // Persist read permission when delivered via external "Open with" intent
            if (uri.scheme == "content") {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) { /* non-persistable URI — fine */ }
            }
            pfd = if (uri.scheme == "file") {
                val path = uri.path ?: return
                ParcelFileDescriptor.open(
                    java.io.File(path), ParcelFileDescriptor.MODE_READ_ONLY
                )
            } else {
                contentResolver.openFileDescriptor(uri, "r")
            }
            if (pfd != null) pdfRenderer = PdfRenderer(pfd!!)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) return cursor.getString(idx)
                    }
                }
            } catch (_: Exception) {}
        }
        return uri.lastPathSegment?.substringAfterLast('/')
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
@Composable
fun RobustPdfViewer(pdfRenderer: PdfRenderer, onBack: () -> Unit, title: String? = null) {
    val pageCount = pdfRenderer.pageCount
    val listState = rememberLazyListState()
    val scope     = rememberCoroutineScope()
    val context   = LocalContext.current
    val view      = LocalView.current

    // ── SINGLE shared Mutex for ALL pages ─────────────────────────────────
    // PdfRenderer is NOT thread-safe: only one page can be open at a time.
    // প্রতিটা page এর আলাদা mutex থাকলে race condition হয় → crash।
    // এই একটা mutex সব RobustPdfPage share করে।
    val pdfMutex = remember { Mutex() }

    BackHandler { onBack() }

    // ── WPS-style fullscreen controls ──────────────────────────────────────
    var controlsVisible by remember { mutableStateOf(true) }
    var autoHideJob     by remember { mutableStateOf<Job?>(null) }

    fun scheduleHide(delayMs: Long = 3_500L) {
        autoHideJob?.cancel()
        autoHideJob = scope.launch {
            delay(delayMs)
            controlsVisible = false
        }
    }

    fun toggleControls() {
        if (controlsVisible) {
            autoHideJob?.cancel()
            controlsVisible = false
        } else {
            controlsVisible = true
            scheduleHide()
        }
    }

    LaunchedEffect(Unit) { scheduleHide(5_000L) }

    val activityWindow = (context as? Activity)?.window
    DisposableEffect(activityWindow) {
        val win = activityWindow ?: return@DisposableEffect onDispose {}
        WindowCompat.setDecorFitsSystemWindows(win, false)
        onDispose {
            WindowInsetsControllerCompat(win, view).show(WindowInsetsCompat.Type.systemBars())
            WindowCompat.setDecorFitsSystemWindows(win, true)
        }
    }
    LaunchedEffect(controlsVisible, activityWindow) {
        val win = activityWindow ?: return@LaunchedEffect
        val ctrl = WindowInsetsControllerCompat(win, view)
        if (controlsVisible) {
            ctrl.show(WindowInsetsCompat.Type.systemBars())
        } else {
            ctrl.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ── Zoom / Pan ─────────────────────────────────────────────────────────
    var zoom    by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var pivotFractionX by remember { mutableStateOf(0.5f) }
    var pivotFractionY by remember { mutableStateOf(0.0f) }

    var useSpring by remember { mutableStateOf(false) }
    val animZoom by animateFloatAsState(
        targetValue   = zoom,
        animationSpec = if (useSpring)
            spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioMediumBouncy)
        else tween(durationMillis = 180),
        label = "zoom"
    )
    val animOffsetX by animateFloatAsState(offsetX, animationSpec = tween(120), label = "ox")
    val animOffsetY by animateFloatAsState(offsetY, animationSpec = tween(120), label = "oy")
    val animPivotX  by animateFloatAsState(pivotFractionX, animationSpec = tween(120), label = "px")
    val animPivotY  by animateFloatAsState(pivotFractionY, animationSpec = tween(120), label = "py")

    var containerW by remember { mutableStateOf(1f) }
    var containerH by remember { mutableStateOf(1f) }

    fun clampPan(ox: Float, oy: Float, z: Float): Pair<Float, Float> {
        val maxX = containerW * (z - 1f) / 2f
        val maxY = containerH * (z - 1f) / 2f
        return Pair(ox.coerceIn(-maxX, maxX), oy.coerceIn(-maxY, maxY))
    }

    // ── Scrollbar ──────────────────────────────────────────────────────────
    var scrollbarVisible by remember { mutableStateOf(false) }
    var scrollbarHideJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(listState.firstVisibleItemScrollOffset, listState.firstVisibleItemIndex) {
        if (listState.isScrollInProgress) {
            scrollbarVisible = true
            scrollbarHideJob?.cancel()
            scrollbarHideJob = scope.launch {
                delay(1200L)
                scrollbarVisible = false
            }
        }
    }

    val scrollbarAlpha by animateFloatAsState(
        targetValue   = if (scrollbarVisible) 0.55f else 0f,
        animationSpec = tween(durationMillis = 300),
        label         = "scrollbarAlpha"
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D1A))
            .onGloballyPositioned {
                containerW = it.size.width.toFloat().coerceAtLeast(1f)
                containerH = it.size.height.toFloat().coerceAtLeast(1f)
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)

                    do {
                        val event   = awaitPointerEvent(PointerEventPass.Initial)
                        val fingers = event.changes.count { it.pressed }

                        when {
                            fingers >= 2 -> {
                                useSpring = false
                                val zChange = event.calculateZoom()
                                val pan     = event.calculatePan()

                                val activeChanges = event.changes.filter { it.pressed }
                                val centroid = activeChanges.fold(Offset.Zero) { acc, c ->
                                    acc + c.position
                                } / activeChanges.size.toFloat()

                                pivotFractionX = (centroid.x / containerW).coerceIn(0f, 1f)
                                pivotFractionY = (centroid.y / containerH).coerceIn(0f, 1f)

                                val newZ = (zoom * zChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                val (cx, cy) = clampPan(
                                    offsetX + pan.x * 0.9f,
                                    offsetY + pan.y * 0.9f,
                                    newZ
                                )
                                zoom    = newZ
                                offsetX = cx
                                offsetY = cy
                                event.changes.forEach { it.consume() }
                            }

                            fingers == 1 && zoom > 1.05f -> {
                                val ch  = event.changes.firstOrNull() ?: break
                                val dx  = ch.position.x - ch.previousPosition.x
                                val dy  = ch.position.y - ch.previousPosition.y
                                val maxX = containerW * (zoom - 1f) / 2f
                                offsetX = (offsetX + dx).coerceIn(-maxX, maxX)

                                if (abs(dx) > abs(dy) * 1.5f) {
                                    ch.consume()
                                }
                            }

                            else -> { /* pass to LazyColumn */ }
                        }
                    } while (event.changes.any { it.pressed })

                    if (zoom > 1.02f) useSpring = true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val downTime = System.currentTimeMillis()
                    var fingers  = 1
                    var lastEvent = awaitPointerEvent()
                    fingers = maxOf(fingers, lastEvent.changes.count { it.pressed })
                    while (lastEvent.changes.any { it.pressed }) {
                        lastEvent = awaitPointerEvent()
                        fingers   = maxOf(fingers, lastEvent.changes.count { it.pressed })
                    }
                    val dur = System.currentTimeMillis() - downTime
                    if (dur < 250 && fingers == 1) toggleControls()
                }
            }
    ) {
        LazyColumn(
            state               = listState,
            modifier            = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX          = animZoom
                    scaleY          = animZoom
                    translationX    = animOffsetX
                    translationY    = animOffsetY
                    transformOrigin = TransformOrigin(animPivotX, animPivotY)
                    clip            = false
                }
                .pointerInput(Unit) {
                    var lastTapTime = 0L
                    var lastTapPos  = Offset.Zero

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val now  = System.currentTimeMillis()
                        val pos  = down.position

                        do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                        val tapDur = System.currentTimeMillis() - now

                        if (tapDur < 300) {
                            val sinceLast = now - lastTapTime
                            val nearPos   = abs(pos.x - lastTapPos.x) < 80f &&
                                           abs(pos.y - lastTapPos.y) < 80f

                            if (sinceLast < DOUBLE_TAP_MS && nearPos) {
                                useSpring = true
                                if (zoom > 1.5f) {
                                    zoom = 1f; offsetX = 0f; offsetY = 0f
                                    pivotFractionX = 0.5f; pivotFractionY = 0.0f
                                } else {
                                    val newZ = DOUBLE_TAP_ZOOM
                                    pivotFractionX = (pos.x / containerW).coerceIn(0f, 1f)
                                    pivotFractionY = (pos.y / containerH).coerceIn(0f, 1f)
                                    zoom = newZ
                                    val tapX = pos.x - containerW / 2f
                                    val tapY = pos.y - containerH / 2f
                                    val (cx, cy) = clampPan(
                                        -tapX * (newZ - 1f),
                                        -tapY * (newZ - 1f) * 0.25f,
                                        newZ
                                    )
                                    offsetX = cx; offsetY = cy
                                }
                                lastTapTime = 0L
                            } else {
                                lastTapTime = now
                                lastTapPos  = pos
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
                    zoom        = zoom,
                    pdfMutex    = pdfMutex  // ← shared mutex passed to every page
                )
            }
        }

        // ── Thin right-side scrollbar ─────────────────────────────────────
        if (scrollbarAlpha > 0.01f && pageCount > 1) {
            val layoutInfo   = listState.layoutInfo
            val totalItems   = layoutInfo.totalItemsCount.coerceAtLeast(1)
            val visibleItems = layoutInfo.visibleItemsInfo
            val firstIdx     = listState.firstVisibleItemIndex
            val firstOff     = listState.firstVisibleItemScrollOffset
            val avgItemH     = if (visibleItems.isNotEmpty())
                visibleItems.sumOf { it.size } / visibleItems.size.toFloat()
            else containerH

            val totalH     = totalItems * avgItemH
            val scrolledH  = firstIdx * avgItemH + firstOff
            val thumbRatio = (containerH / totalH).coerceIn(0.04f, 0.8f)
            val thumbH     = (containerH * thumbRatio).coerceAtLeast(24f)
            val trackH     = containerH - thumbH
            val thumbTop   = (scrolledH / (totalH - containerH)).coerceIn(0f, 1f) * trackH

            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .width(3.dp)
                    .graphicsLayer { alpha = scrollbarAlpha }
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(thumbH.dp)
                        .offset(y = thumbTop.dp)
                        .background(
                            color = Color(0xFFAAAAAA),
                            shape = RoundedCornerShape(2.dp)
                        )
                )
            }
        }

        // ── WPS-style top bar ─────────────────────────────────────────────
        AnimatedVisibility(
            visible  = controlsVisible,
            enter    = slideInVertically { -it } + fadeIn(),
            exit     = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC0A0A14))
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint               = Color(0xFFF0EFFF),
                        modifier           = Modifier.size(22.dp)
                    )
                }
                if (title != null) {
                    Text(
                        text       = title,
                        color      = Color(0xFFF0EFFF),
                        fontSize   = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        modifier   = Modifier.weight(1f).padding(end = 12.dp)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Per-page renderer — pdfMutex shared across ALL pages (thread safety fix)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun RobustPdfPage(
    pdfRenderer: PdfRenderer,
    pageIndex:   Int,
    zoom:        Float,
    pdfMutex:    Mutex          // single shared mutex — prevents concurrent openPage() calls
) {
    val scope = rememberCoroutineScope()

    var bitmap        by remember { mutableStateOf<Bitmap?>(null) }
    var renderedScale by remember { mutableStateOf(0f) }
    var debounceJob   by remember { mutableStateOf<Job?>(null) }

    val targetScale = (BASE_SCALE * zoom.coerceIn(MIN_ZOOM, MAX_ZOOM))
        .coerceIn(BASE_SCALE, BASE_SCALE * MAX_ZOOM)

    // Initial render at BASE_SCALE
    LaunchedEffect(pageIndex) {
        if (bitmap != null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                pdfMutex.withLock {
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

    // Re-render at higher resolution when zoom changes (debounced)
    LaunchedEffect(targetScale) {
        if (bitmap == null) return@LaunchedEffect
        if (abs(targetScale - renderedScale) < 0.15f) return@LaunchedEffect

        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(RERENDER_DEBOUNCE)
            withContext(Dispatchers.IO) {
                try {
                    pdfMutex.withLock {
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

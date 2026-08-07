package com.rasel.RasFocus.filemanager

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfiumCore
import kotlinx.coroutines.*
import kotlin.math.roundToInt

// ─── Colors ───────────────────────────────────────────────────────────────────
private val BG      = Color(0xFF111111)
private val BG2     = Color(0xFF1A1A1A)
private val WHITE   = Color(0xFFF0EFFF)
private val MUTED   = Color(0xFF888888)
private val INDIGO  = Color(0xFF6C63FF)
private val INDIGO2 = Color(0xFF8B83FF)

// ─────────────────────────────────────────────────────────────────────────────
class FMPdfViewerActivity : ComponentActivity() {

    private val uriState      = mutableStateOf<Uri?>(null)
    private val fileNameState = mutableStateOf("PDF")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        loadIntent(intent)
        setContent { FMPdfViewer(uri = uriState.value, fileName = fileNameState.value, onClose = { finish() }) }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadIntent(intent)
    }

    private fun loadIntent(src: android.content.Intent?) {
        val uri: Uri? = src?.data ?: src?.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
        if (uri != null && uri.scheme == "content") {
            try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: Exception) {}
        }
        uriState.value      = uri
        fileNameState.value = uri?.let { resolveFileName(it) } ?: "PDF"
    }

    private fun resolveFileName(uri: Uri): String {
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) return c.getString(idx) ?: ""
                    }
                }
            } catch (_: Exception) {}
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "PDF"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Main Composable
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun FMPdfViewer(uri: Uri?, fileName: String, onClose: () -> Unit) {
    val context  = LocalContext.current
    val scope    = rememberCoroutineScope()
    val density  = LocalDensity.current
    val screenW  = context.resources.displayMetrics.widthPixels

    // Page bitmaps — null = not yet rendered
    val pages      = remember { mutableStateListOf<Bitmap?>() }
    var totalPages by remember { mutableIntStateOf(0) }
    var currentPage by remember { mutableIntStateOf(1) }
    var isLoading  by remember { mutableStateOf(true) }
    var errorMsg   by remember { mutableStateOf("") }

    // Controls
    var controlsVisible by remember { mutableStateOf(false) }
    var autoHideJob     by remember { mutableStateOf<Job?>(null) }

    // Zoom/pan — shared across all pages
    var scale   by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }

    val pdfCore  = remember { PdfiumCore(context) }
    var pdfDoc   by remember { mutableStateOf<PdfDocument?>(null) }
    var activePfd by remember { mutableStateOf<android.os.ParcelFileDescriptor?>(null) }

    val listState   = rememberLazyListState()
    val renderJobs  = remember { mutableMapOf<Int, Job>() }

    // ── Auto-hide ─────────────────────────────────────────────────────────────
    fun scheduleHide() {
        autoHideJob?.cancel()
        controlsVisible = true
        autoHideJob = scope.launch { delay(3_500); controlsVisible = false }
    }
    fun toggleControls() {
        if (controlsVisible) { autoHideJob?.cancel(); controlsVisible = false }
        else scheduleHide()
    }

    // ── Render one page ───────────────────────────────────────────────────────
    fun renderPage(doc: PdfDocument, i: Int) {
        if (i < 0 || i >= pages.size) return
        if (pages.getOrNull(i) != null) return
        if (renderJobs[i]?.isActive == true) return
        renderJobs[i] = scope.launch(Dispatchers.IO) {
            try {
                val page = doc.openPage(i)
                val dpi  = context.resources.displayMetrics.densityDpi
                val origW = page.getPageWidth(dpi).coerceAtLeast(1)
                val origH = page.getPageHeight(dpi).coerceAtLeast(1)
                val scale = screenW.toFloat() / origW
                val bmpW  = screenW
                val bmpH  = (origH * scale).roundToInt().coerceAtLeast(1)
                val bmp   = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.RGB_565)
                bmp.eraseColor(AColor.WHITE)
                page.renderPageBitmap(bmp, 0, 0, bmpW, bmpH, true)
                page.close()
                withContext(Dispatchers.Main) { if (i < pages.size) pages[i] = bmp }
            } catch (_: Exception) {}
        }
    }

    // ── Load PDF ──────────────────────────────────────────────────────────────
    LaunchedEffect(uri) {
        if (uri == null) { isLoading = false; errorMsg = "ফাইল পাওয়া যায়নি"; return@LaunchedEffect }
        isLoading = true
        renderJobs.values.forEach { it.cancel() }; renderJobs.clear()
        withContext(Dispatchers.IO) {
            try {
                val tmp = java.io.File(context.cacheDir, "fm_pdf_${System.currentTimeMillis()}.pdf")
                context.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    ?: throw Exception("ফাইল খুলতে পারিনি")
                val pfd = android.os.ParcelFileDescriptor.open(tmp, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                val doc = pdfCore.newDocument(pfd)
                try { tmp.delete() } catch (_: Exception) {}
                pdfDoc    = doc
                activePfd = pfd
                val count = doc.getPageCount()
                withContext(Dispatchers.Main) {
                    pages.clear(); repeat(count) { pages.add(null) }
                    totalPages = count; currentPage = 1; isLoading = false
                    for (i in 0 until minOf(3, count)) renderPage(doc, i)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { isLoading = false; errorMsg = "খোলা যায়নি: ${e.message}" }
            }
        }
    }

    // ── Scroll → page counter + preload ──────────────────────────────────────
    val visibleIdx by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    LaunchedEffect(visibleIdx) { if (totalPages > 0) currentPage = visibleIdx + 1 }
    LaunchedEffect(visibleIdx, pdfDoc) {
        val doc = pdfDoc ?: return@LaunchedEffect
        for (i in (visibleIdx - 1).coerceAtLeast(0)..(visibleIdx + 2).coerceAtMost(totalPages - 1))
            renderPage(doc, i)
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────
    DisposableEffect(Unit) {
        onDispose {
            renderJobs.values.forEach { it.cancel() }
            pages.forEach { it?.recycle() }
            pdfDoc?.let { pdfCore.closeDocument(it) }
            try { activePfd?.close() } catch (_: Exception) {}
        }
    }

    // ── System bars ───────────────────────────────────────────────────────────
    val window = (context as? android.app.Activity)?.window
    val view   = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(window) {
        window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
        onDispose { window?.let { WindowInsetsControllerCompat(it, view).show(WindowInsetsCompat.Type.systemBars()) } }
    }
    LaunchedEffect(controlsVisible, window) {
        val w = window ?: return@LaunchedEffect
        val c = WindowInsetsControllerCompat(w, view)
        if (controlsVisible) c.show(WindowInsetsCompat.Type.systemBars())
        else { c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; c.hide(WindowInsetsCompat.Type.systemBars()) }
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    Box(Modifier.fillMaxSize().background(BG)) {
        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = INDIGO2, strokeWidth = 2.5.dp, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("PDF লোড হচ্ছে...", color = MUTED, fontSize = 13.sp)
                }
            }
            errorMsg.isNotEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Text("⚠️", fontSize = 40.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(errorMsg, color = Color(0xFFFF5C5C), fontSize = 13.sp)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onClose, colors = ButtonDefaults.buttonColors(containerColor = INDIGO)) {
                        Text("← ফিরে যান", color = Color.White)
                    }
                }
            }
            else -> {
                // ── Pinch zoom wrapper ────────────────────────────────────────
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.size >= 2) {
                                        val zoom = event.calculateZoom()
                                        val pan  = event.calculatePan()
                                        val newScale = (scale * zoom).coerceIn(1f, 8f)
                                        offsetX = if (newScale > 1f) offsetX + pan.x else 0f
                                        val maxX = (size.width * newScale - size.width) / 2f
                                        offsetX = offsetX.coerceIn(-maxX, maxX)
                                        scale   = newScale
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    } else if (event.changes.size == 1 && scale > 1f) {
                                        val pan = event.calculatePan()
                                        offsetX += pan.x
                                        val maxX = (size.width * scale - size.width) / 2f
                                        offsetX = offsetX.coerceIn(-maxX, maxX)
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { toggleControls() },
                                onDoubleTap = { tap ->
                                    if (scale > 1.2f) { scale = 1f; offsetX = 0f }
                                    else { scale = 2.5f; offsetX = (size.width / 2f - tap.x) * 1.5f }
                                }
                            )
                        }
                ) {
                    Box(
                        Modifier
                            .wrapContentSize(Alignment.Center, unbounded = true)
                            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, clip = false)
                    ) {
                        LazyColumn(
                            state               = listState,
                            modifier            = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            contentPadding      = PaddingValues(bottom = 72.dp)
                        ) {
                            itemsIndexed(pages) { _, bmp ->
                                if (bmp == null) {
                                    Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(Color(0xFF1A1A1A)),
                                        contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(modifier = Modifier.size(26.dp), color = INDIGO2, strokeWidth = 2.dp)
                                    }
                                } else {
                                    Image(
                                        bitmap             = bmp.asImageBitmap(),
                                        contentDescription = null,
                                        contentScale       = ContentScale.FillWidth,
                                        modifier           = Modifier.fillMaxWidth().wrapContentHeight()
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Top bar ───────────────────────────────────────────────────
                AnimatedVisibility(
                    visible  = controlsVisible,
                    enter    = slideInVertically { -it } + fadeIn(),
                    exit     = slideOutVertically { -it } + fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(BG.copy(alpha = 0.93f))
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Default.ArrowBack, null, tint = WHITE, modifier = Modifier.size(22.dp))
                        }
                        Text(
                            fileName,
                            color     = WHITE,
                            fontSize  = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines  = 1,
                            overflow  = TextOverflow.Ellipsis,
                            modifier  = Modifier.weight(1f).padding(start = 2.dp)
                        )
                        // Page counter
                        if (totalPages > 0) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(INDIGO.copy(alpha = 0.25f))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("$currentPage / $totalPages", color = INDIGO2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                }

                // ── Zoom reset button (bottom right, shows when zoomed) ───────
                AnimatedVisibility(
                    visible  = scale > 1.05f,
                    enter    = fadeIn(),
                    exit     = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                ) {
                    FloatingActionButton(
                        onClick        = { scale = 1f; offsetX = 0f },
                        containerColor = BG2,
                        contentColor   = INDIGO2,
                        modifier       = Modifier.size(42.dp),
                        shape          = CircleShape
                    ) {
                        Icon(Icons.Default.ZoomOut, null, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

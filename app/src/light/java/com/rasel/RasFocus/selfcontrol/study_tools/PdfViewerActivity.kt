package com.rasel.RasFocus.selfcontrol.study_tools

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────────────────────
// Light-flavour PDF Viewer — uses android.graphics.pdf.PdfRenderer (no pdfium)
// Available on all Android 5+ devices. No native .so deps needed.
// ─────────────────────────────────────────────────────────────────────────────

class PdfViewerActivity : ComponentActivity() {

    private val uriState      = mutableStateOf<Uri?>(null)
    private val fileNameState = mutableStateOf("PDF")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        loadFromIntent(intent)
        setContent {
            MaterialTheme {
                LightPdfViewer(
                    uri      = uriState.value,
                    fileName = fileNameState.value,
                    onClose  = { finish() }
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent); setIntent(intent); loadFromIntent(intent)
    }

    private fun loadFromIntent(intent: android.content.Intent?) {
        val uri: Uri? = when {
            intent?.action == android.content.Intent.ACTION_VIEW && intent.data != null -> intent.data
            intent?.hasExtra("pdf_uri") == true -> Uri.parse(intent.getStringExtra("pdf_uri"))
            else -> null
        }
        if (uri != null && uri.scheme == "content") {
            try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) {}
            catch (_: Exception) {} // IllegalArgumentException on some ROMs — never crash
        }
        uriState.value = uri
        fileNameState.value = uri?.let { getFileNameFromUri(it) } ?: "PDF"
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var name: String? = null
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) name = cursor.getString(idx)
                    }
                }
            } catch (e: Exception) {
                // Ignore query exceptions
            }
        }
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "PDF"
    }
}

@Composable
fun LightPdfViewer(uri: Uri?, fileName: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    // Nullable list — null slot = not rendered yet (placeholder shown instead)
    val bitmaps  = remember { mutableStateListOf<Bitmap?>() }
    var total    by remember { mutableIntStateOf(0) }
    var current  by remember { mutableIntStateOf(1) }
    var loading  by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf("") }
    var controls by remember { mutableStateOf(false) }
    var scale    by remember { mutableFloatStateOf(1f) }
    var offsetX  by remember { mutableFloatStateOf(0f) }

    val listState  = rememberLazyListState()
    val screenW    = context.resources.displayMetrics.widthPixels

    // Renderer held in a ref so viewport watcher can access it
    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }
    val renderJobs  = remember { mutableMapOf<Int, kotlinx.coroutines.Job>() }

    fun renderPage(renderer: PdfRenderer, i: Int) {
        if (i < 0 || i >= bitmaps.size) return
        if (bitmaps[i] != null) return
        if (renderJobs[i]?.isActive == true) return
        renderJobs[i] = scope.launch(Dispatchers.IO) {
            try {
                val page  = renderer.openPage(i)
                val ratio = page.height.toFloat() / page.width.toFloat()
                val bmpW  = screenW
                val bmpH  = (screenW * ratio).toInt().coerceAtLeast(1)
                val bmp   = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.RGB_565) // half memory
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                withContext(Dispatchers.Main) { if (i < bitmaps.size) bitmaps[i] = bmp }
            } catch (_: Exception) { /* page stays placeholder */ }
        }
    }

    LaunchedEffect(uri) {
        if (uri == null) { loading = false; errorMsg = "ফাইল পাওয়া যায়নি"; return@LaunchedEffect }
        renderJobs.values.forEach { it.cancel() }; renderJobs.clear()
        withContext(Dispatchers.IO) {
            try {
                val pfd      = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("File খুলতে পারিনি")
                val renderer = PdfRenderer(pfd)
                val count    = renderer.pageCount
                withContext(Dispatchers.Main) {
                    bitmaps.clear()
                    repeat(count) { bitmaps.add(null) }
                    total   = count
                    loading = false
                    pdfRenderer = renderer
                    // Render first 3 pages immediately for instant feel
                    for (i in 0 until minOf(3, count)) renderPage(renderer, i)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { loading = false; errorMsg = "PDF খোলা যায়নি: ${e.message}" }
            }
        }
    }

    val visibleIdx by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    LaunchedEffect(visibleIdx) { current = visibleIdx + 1 }

    // Render pages near viewport on-demand
    LaunchedEffect(visibleIdx, pdfRenderer) {
        val renderer = pdfRenderer ?: return@LaunchedEffect
        for (i in (visibleIdx - 1).coerceAtLeast(0)
                  ..(visibleIdx + 2).coerceAtMost(total - 1)) {
            renderPage(renderer, i)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            renderJobs.values.forEach { it.cancel() }
            pdfRenderer?.close()
        }
    }

    val VA_BG = ComposeColor(0xFF111111); val VA_WHITE = ComposeColor(0xFFF5F5F5)

    Box(Modifier.fillMaxSize().background(VA_BG).systemBarsPadding()) {
        when {
            // Removed loading indicator to reduce visual transitions
            loading -> Box(Modifier.fillMaxSize())
            errorMsg.isNotEmpty() -> Text(errorMsg, color = ComposeColor.Red, modifier = Modifier.align(Alignment.Center))
            total == 0 -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = ComposeColor(0xFF6C63FF))
            else -> {
                // FIX: graphicsLayer on LazyColumn itself clips content — horizontal
                // pan after zoom gets cut off at screen edge. Move graphicsLayer to a
                // wrapper Box with wrapContentSize(unbounded=true) so translated content
                // can extend past screen bounds without clipping.
                val transformState = rememberTransformableState { zc, pc, _ ->
                    scale   = (scale * zc).coerceIn(1f, 5f)
                    offsetX = if (scale > 1f) offsetX + pc.x else 0f
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .transformable(transformState)
                        .pointerInput(Unit) { detectTapGestures(onTap = { controls = !controls },
                            onDoubleTap = { if (scale > 1f) { scale = 1f; offsetX = 0f } else scale = 2.5f }) }
                ) {
                Box(
                    Modifier
                        .wrapContentSize(Alignment.Center, unbounded = true)
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, clip = false)
                ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(bitmaps) { _, bmp ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(ComposeColor.White)
                                // Approximate A4 aspect ratio placeholder so scroll height stays stable
                                .then(if (bmp == null) Modifier.aspectRatio(0.707f) else Modifier)
                        ) {
                            if (bmp != null) {
                                Image(bitmap = bmp.asImageBitmap(), contentDescription = null,
                                    contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                            } else {
                                // Loading placeholder
                                Box(Modifier.fillMaxSize().background(ComposeColor(0xFFEEEEEE))) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.align(Alignment.Center).size(32.dp),
                                        color = ComposeColor(0xFF6C63FF), strokeWidth = 2.dp
                                    )
                                }
                            }
                        }
                    }
                }
                } // close inner graphicsLayer Box
                } // close outer touch Box

                androidx.compose.animation.AnimatedVisibility(visible = controls, enter = fadeIn(), exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter)) {
                    Row(Modifier.fillMaxWidth().background(VA_BG.copy(0.93f)).padding(horizontal = 6.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose, modifier = Modifier.size(42.dp)) {
                            Icon(Icons.Default.ArrowBack, null, tint = VA_WHITE, modifier = Modifier.size(22.dp))
                        }
                        Text(fileName, color = VA_WHITE, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("$current/$total", color = VA_WHITE.copy(0.6f), fontSize = 11.sp,
                            modifier = Modifier.padding(end = 12.dp))
                    }
                }
            }
        }
    }
}

package com.rasel.RasFocus.selfcontrol.study_tools

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PdfViewerActivity : ComponentActivity() {

    private val uriState = mutableStateOf<Uri?>(null)
    private val fileNameState = mutableStateOf("PDF")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        loadFromIntent(intent)
        setContent {
            MaterialTheme {
                PdfViewer(
                    uri = uriState.value,
                    fileName = fileNameState.value,
                    onClose = { finish() }
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadFromIntent(intent)
    }

    private fun loadFromIntent(intent: android.content.Intent?) {
        val uri: Uri? = when {
            intent?.action == android.content.Intent.ACTION_VIEW && intent.data != null -> intent.data
            intent?.hasExtra("pdf_uri") == true -> Uri.parse(intent.getStringExtra("pdf_uri"))
            else -> null
        }
        if (uri != null && uri.scheme == "content") {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
        }
        uriState.value = uri
        fileNameState.value = uri?.let { getFileName(it) } ?: "PDF"
    }

    private fun getFileName(uri: Uri): String {
        var name: String? = null
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) name = c.getString(idx)
                    }
                }
            } catch (_: Exception) {}
        }
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "PDF"
    }
}

@Composable
fun PdfViewer(uri: Uri?, fileName: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    val bitmaps     = remember { mutableStateListOf<Bitmap?>() }
    var total       by remember { mutableIntStateOf(0) }
    var current     by remember { mutableIntStateOf(1) }
    var loading     by remember { mutableStateOf(true) }
    var errorMsg    by remember { mutableStateOf("") }
    var showControls by remember { mutableStateOf(true) }
    var scale       by remember { mutableFloatStateOf(1f) }
    var offsetX     by remember { mutableFloatStateOf(0f) }

    val listState = rememberLazyListState()
    val screenW   = context.resources.displayMetrics.widthPixels
    var renderer  by remember { mutableStateOf<PdfRenderer?>(null) }
    val renderJobs = remember { mutableMapOf<Int, Job>() }

    fun renderPage(r: PdfRenderer, idx: Int) {
        if (idx < 0 || idx >= bitmaps.size) return
        if (bitmaps[idx] != null) return
        if (renderJobs[idx]?.isActive == true) return
        renderJobs[idx] = scope.launch(Dispatchers.IO) {
            try {
                val page  = r.openPage(idx)
                val ratio = page.height.toFloat() / page.width.toFloat()
                val bmpW  = screenW
                val bmpH  = (screenW * ratio).toInt().coerceAtLeast(1)
                val bmp   = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.RGB_565)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                withContext(Dispatchers.Main) { if (idx < bitmaps.size) bitmaps[idx] = bmp }
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(uri) {
        if (uri == null) { loading = false; errorMsg = "ফাইল পাওয়া যায়নি"; return@LaunchedEffect }
        renderJobs.values.forEach { it.cancel() }; renderJobs.clear(); bitmaps.clear()
        withContext(Dispatchers.IO) {
            try {
                // Copy to cache → sidesteps ALL URI permission / scheme issues
                val tmp = File(context.cacheDir, "pv_${System.currentTimeMillis()}.pdf")
                context.contentResolver.openInputStream(uri)?.use { it.copyTo(tmp.outputStream()) }
                    ?: throw IllegalStateException("Cannot read URI")
                val pfd = android.os.ParcelFileDescriptor.open(
                    tmp, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(pfd)
                try { tmp.delete() } catch (_: Exception) {}   // FD keeps data alive on Linux
                val count = r.pageCount
                withContext(Dispatchers.Main) {
                    repeat(count) { bitmaps.add(null) }
                    total = count; renderer = r; loading = false
                    for (i in 0 until minOf(3, count)) renderPage(r, i)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { loading = false; errorMsg = "PDF খোলা যায়নি: ${e.message}" }
            }
        }
    }

    val visibleIdx by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    LaunchedEffect(visibleIdx) { current = visibleIdx + 1 }
    LaunchedEffect(visibleIdx, renderer) {
        val r = renderer ?: return@LaunchedEffect
        for (i in (visibleIdx - 1).coerceAtLeast(0)..(visibleIdx + 3).coerceAtMost(total - 1))
            renderPage(r, i)
    }
    DisposableEffect(Unit) { onDispose { renderJobs.values.forEach { it.cancel() }; renderer?.close() } }

    val BG    = ComposeColor(0xFF0D0D14)
    val WHITE = ComposeColor(0xFFF0EFFF)

    Box(Modifier.fillMaxSize().background(BG).systemBarsPadding()) {
        when {
            loading -> CircularProgressIndicator(
                Modifier.align(Alignment.Center), color = ComposeColor(0xFF6C63FF))

            errorMsg.isNotEmpty() -> Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("⚠️", fontSize = 40.sp)
                Spacer(Modifier.height(12.dp))
                Text(errorMsg, color = ComposeColor(0xFFFF5C5C), fontSize = 14.sp)
                Spacer(Modifier.height(20.dp))
                Button(onClick = onClose) { Text("← ফিরে যান") }
            }

            else -> {
                val ts = rememberTransformableState { zc, pc, _ ->
                    scale   = (scale * zc).coerceIn(1f, 5f)
                    offsetX = if (scale > 1f) offsetX + pc.x else 0f
                }
                Box(
                    Modifier.fillMaxSize().transformable(ts)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { showControls = !showControls },
                                onDoubleTap = { if (scale > 1f) { scale = 1f; offsetX = 0f } else scale = 2.5f }
                            )
                        }
                ) {
                    Box(Modifier.wrapContentSize(Alignment.Center, unbounded = true)
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, clip = false)
                    ) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            itemsIndexed(bitmaps) { _, bmp ->
                                Box(
                                    Modifier.fillMaxWidth().background(ComposeColor.White)
                                        .then(if (bmp == null) Modifier.aspectRatio(0.707f) else Modifier)
                                ) {
                                    if (bmp != null) {
                                        Image(bmp.asImageBitmap(), null,
                                            contentScale = ContentScale.FillWidth,
                                            modifier = Modifier.fillMaxWidth())
                                    } else {
                                        Box(Modifier.fillMaxSize().background(ComposeColor(0xFFEEEEEE))) {
                                            CircularProgressIndicator(
                                                Modifier.align(Alignment.Center).size(28.dp),
                                                color = ComposeColor(0xFF6C63FF), strokeWidth = 2.dp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(showControls, enter = fadeIn(), exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter)) {
                    Row(Modifier.fillMaxWidth().background(BG.copy(0.92f))
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Default.ArrowBack, null, tint = WHITE, modifier = Modifier.size(22.dp))
                        }
                        Text(fileName, color = WHITE, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (total > 0)
                            Text("$current / $total", color = WHITE.copy(0.6f), fontSize = 11.sp,
                                modifier = Modifier.padding(end = 12.dp))
                    }
                }
            }
        }
    }
}

package com.rasel.RasFocus.filemanager

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

// ── Image Viewer ───────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    imagePath: String,
    onBack: () -> Unit
) {
    val file = File(imagePath)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name, maxLines = 1, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.6f))
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(file)
                    .crossfade(true)
                    .build(),
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// ── PDF Viewer — delegates to FMUnifiedPdfViewer (Pdfium, full-screen, gesture zoom) ──
// Local file: path → Uri.fromFile → FMUnifiedPdfViewer (same engine as Drive PDFs)
@Composable
fun PdfViewerScreen(
    pdfPath: String,
    onBack: () -> Unit
) {
    val uri      = remember(pdfPath) { android.net.Uri.fromFile(java.io.File(pdfPath)) }
    val fileName = remember(pdfPath) { java.io.File(pdfPath).name }
    FMUnifiedPdfViewer(
        uri      = uri,
        fileName = fileName,
        onClose  = onBack
    )
}

// ── Single PDF page — per-page pinch zoom + pan ────────────────────────────────
@Composable
fun ZoomablePdfPage(
    pdfRenderer: PdfRenderer,
    pageIndex: Int,
    mutex: kotlinx.coroutines.sync.Mutex,
    globalZoom: Float
) {
    var bitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }

    // Per-page pinch state
    var localScale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Reset per-page zoom when globalZoom changes to 1 (reset button)
    LaunchedEffect(globalZoom) {
        if (globalZoom == 1f) {
            localScale = 1f
            offset = Offset.Zero
        }
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        localScale = (localScale * zoomChange).coerceIn(0.5f, 5f)
        offset += panChange
    }

    // Render bitmap
    LaunchedEffect(pageIndex) {
        withContext(Dispatchers.IO) {
            try {
                mutex.withLock {
                    val page = pdfRenderer.openPage(pageIndex)
                    val renderWidth = page.width * 3   // 3× for sharp text
                    val renderHeight = page.height * 3
                    val newBitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                    newBitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(newBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    bitmap = newBitmap
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val effectiveScale = localScale * globalZoom

    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "Page ${pageIndex + 1}",
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .transformable(transformState)
                .graphicsLayer {
                    scaleX = effectiveScale
                    scaleY = effectiveScale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentScale = ContentScale.FillWidth
        )
    } else {
        // Placeholder while rendering
        val context = LocalContext.current
        val rendererRef = pdfRenderer
        val approxHeightPx = remember(pageIndex) {
            try {
                val page = rendererRef.openPage(pageIndex)
                val h = page.height
                page.close()
                h
            } catch (_: Exception) { 1400 }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height((approxHeightPx * 0.38f).dp)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = Color(0xFF00796B), modifier = Modifier.size(32.dp))
        }
    }
}

// ── PdfPage (legacy — kept for FMPdfViewerActivity) ───────────────────────────
@Composable
fun PdfPage(pdfRenderer: PdfRenderer, pageIndex: Int, mutex: kotlinx.coroutines.sync.Mutex) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(pageIndex) {
        withContext(Dispatchers.IO) {
            try {
                mutex.withLock {
                    val page = pdfRenderer.openPage(pageIndex)
                    val renderWidth = page.width * 2
                    val renderHeight = page.height * 2
                    val newBitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                    newBitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(newBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    bitmap = newBitmap
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "Page $pageIndex",
            modifier = Modifier.fillMaxWidth().background(Color.White),
            contentScale = ContentScale.FillWidth
        )
    } else {
        Box(
            modifier = Modifier.fillMaxWidth().height(400.dp).background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }
}

// ── Audio/Video Player ─────────────────────────────────────────────────────────
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioVideoPlayerScreen(
    mediaPath: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val exoPlayer = remember {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(java.io.File(mediaPath))))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    val file = java.io.File(mediaPath)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name, maxLines = 1, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.6f))
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    androidx.media3.ui.PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

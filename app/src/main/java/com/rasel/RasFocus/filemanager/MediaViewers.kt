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

// ── PDF Viewer — per-page pinch zoom + page counter + zoom buttons ─────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    pdfPath: String,
    onBack: () -> Unit
) {
    val file = File(pdfPath)
    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var fileDescriptor by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    val pdfMutex = remember { kotlinx.coroutines.sync.Mutex() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Global zoom — applies to all pages uniformly
    var globalZoom by remember { mutableFloatStateOf(1f) }

    // Current visible page (1-based for display)
    val currentPage by remember {
        derivedStateOf { listState.firstVisibleItemIndex + 1 }
    }
    val totalPages by remember(pdfRenderer) {
        derivedStateOf { pdfRenderer?.pageCount ?: 0 }
    }

    // Show/hide top bar on scroll
    var barsVisible by remember { mutableStateOf(true) }
    var lastScrollOffset by remember { mutableIntStateOf(0) }
    LaunchedEffect(listState.firstVisibleItemScrollOffset) {
        val delta = listState.firstVisibleItemScrollOffset - lastScrollOffset
        if (delta > 30) barsVisible = false
        else if (delta < -30) barsVisible = true
        lastScrollOffset = listState.firstVisibleItemScrollOffset
    }

    DisposableEffect(pdfPath) {
        try {
            fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(fileDescriptor!!)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        onDispose {
            pdfRenderer?.close()
            fileDescriptor?.close()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF3A3A3A))) {

        // ── Page list ─────────────────────────────────────────────────────────
        if (pdfRenderer != null) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(pdfRenderer!!.pageCount) { index ->
                    ZoomablePdfPage(
                        pdfRenderer = pdfRenderer!!,
                        pageIndex = index,
                        mutex = pdfMutex,
                        globalZoom = globalZoom
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("PDF লোড করা সম্ভব হয়নি", color = Color.Red, fontSize = 14.sp)
            }
        }

        // ── Top bar — back + filename ─────────────────────────────────────────
        AnimatedVisibility(
            visible = barsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                color = Color(0xE6000000),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Text(
                        text = file.name,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // ── Bottom bar — page counter + zoom controls ─────────────────────────
        AnimatedVisibility(
            visible = barsVisible && totalPages > 0,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                color = Color(0xE6000000),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Page counter
                    Text(
                        text = "$currentPage / $totalPages",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )

                    // Zoom controls
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Zoom out
                        IconButton(
                            onClick = { globalZoom = (globalZoom - 0.25f).coerceAtLeast(0.5f) },
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color.White.copy(alpha = 0.15f), CircleShape)
                        ) {
                            Icon(Icons.Default.ZoomOut, contentDescription = "Zoom Out",
                                tint = Color.White, modifier = Modifier.size(20.dp))
                        }

                        // Zoom percentage
                        Surface(
                            color = Color.White.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "${(globalZoom * 100).toInt()}%",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }

                        // Zoom in
                        IconButton(
                            onClick = { globalZoom = (globalZoom + 0.25f).coerceAtMost(4f) },
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color.White.copy(alpha = 0.15f), CircleShape)
                        ) {
                            Icon(Icons.Default.ZoomIn, contentDescription = "Zoom In",
                                tint = Color.White, modifier = Modifier.size(20.dp))
                        }

                        // Reset zoom
                        if (globalZoom != 1f) {
                            TextButton(onClick = { globalZoom = 1f }) {
                                Text("Reset", color = Color(0xFF80CBC4), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Tap center to toggle bars
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { barsVisible = !barsVisible })
                }
        )
    }
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

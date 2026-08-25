package com.rasel.RasFocus.selfcontrol.study_tools

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ─────────────────────────────────────────────────────────────────────────────
// ImageViewerActivity
//
// Full-screen image viewer with:
//   • Pinch-to-zoom (up to 5×) + pan
//   • Double-tap to zoom / reset
//   • Horizontal swipe to browse sibling images in the same folder
//   • Share button
//   • Immersive (edge-to-edge) UI
// ─────────────────────────────────────────────────────────────────────────────

class ImageViewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val uri: Uri? = intent?.data
            ?: intent?.getParcelableExtra(Intent.EXTRA_STREAM)

        val fileName = getFileName(uri)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ImageViewerScreen(
                    uri = uri,
                    fileName = fileName,
                    onClose = { finish() },
                    onShare = { shareImage(uri) }
                )
            }
        }
    }

    private fun getFileName(uri: Uri?): String {
        if (uri == null) return "Image"
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.let { return it }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Image"
    }

    private fun shareImage(uri: Uri?) {
        if (uri == null) return
        try {
            val shareUri = if (uri.scheme == "file") {
                FileProvider.getUriForFile(
                    this, "${packageName}.fileprovider",
                    File(uri.path!!)
                )
            } else uri
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share image"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Cannot share: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ImageViewerScreen(
    uri: Uri?,
    fileName: String,
    onClose: () -> Unit,
    onShare: () -> Unit
) {
    val context = LocalContext.current

    // Load sibling images from the same folder (for swipe browsing)
    var siblings by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var initialPage by remember { mutableIntStateOf(0) }
    var uiVisible by remember { mutableStateOf(true) }

    LaunchedEffect(uri) {
        if (uri == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val loaded = loadSiblingImages(context, uri)
            val idx = loaded.indexOfFirst { it.toString() == uri.toString() }
                .takeIf { it >= 0 } ?: 0
            withContext(Dispatchers.Main) {
                siblings = loaded
                initialPage = idx
            }
        }
    }

    val displayUris = siblings.ifEmpty { uri?.let { listOf(it) } ?: emptyList() }
    val pagerState = rememberPagerState(initialPage = initialPage) { displayUris.size }

    // Current file name from pager position
    val currentFileName = remember(pagerState.currentPage) {
        displayUris.getOrNull(pagerState.currentPage)?.lastPathSegment
            ?.substringAfterLast('/') ?: fileName
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (displayUris.isEmpty()) {
            // Error state
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⚠️", fontSize = 40.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("Image খুলতে পারেনি", color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onClose) { Text("← ফিরে যান") }
                }
            }
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                ZoomableImage(
                    uri = displayUris[page],
                    onTap = { uiVisible = !uiVisible }
                )
            }

            // ── Top bar (fades on tap) ────────────────────────────────────
            val topAlpha by animateFloatAsState(
                targetValue = if (uiVisible) 1f else 0f,
                animationSpec = tween(200), label = "topbar"
            )
            if (topAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = topAlpha }
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)
                            )
                        )
                        .statusBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onClose) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                        Column(modifier = Modifier.weight(1f).padding(start = 2.dp)) {
                            Text(
                                text = currentFileName,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (displayUris.size > 1) {
                                Text(
                                    text = "${pagerState.currentPage + 1} / ${displayUris.size}",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 12.sp
                                )
                            }
                        }
                        IconButton(onClick = onShare) {
                            Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ZoomableImage — pinch-to-zoom + pan + double-tap to zoom/reset
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ZoomableImage(
    uri: Uri,
    onTap: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Reset when page changes (uri key)
    LaunchedEffect(uri) {
        scale = 1f
        offset = Offset.Zero
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
        scale = newScale
        offset += panChange
    }

    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .transformable(transformState)
            .pointerInput(uri) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            // Reset
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            // Zoom in to tapped point
                            scale = 2.5f
                        }
                    },
                    onTap = { onTap() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(uri)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            loading = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(40.dp)
                    )
                }
            },
            error = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("⚠️", fontSize = 32.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Image লোড হয়নি",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Load sibling images from the same folder for swipe navigation
// ─────────────────────────────────────────────────────────────────────────────

private fun loadSiblingImages(context: android.content.Context, currentUri: Uri): List<Uri> {
    return try {
        // For file:// URIs — list the parent directory
        if (currentUri.scheme == "file") {
            val currentFile = File(currentUri.path ?: return listOf(currentUri))
            val parent = currentFile.parentFile ?: return listOf(currentUri)
            parent.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
                ?.sortedBy { it.name.lowercase() }
                ?.map { Uri.fromFile(it) }
                ?: listOf(currentUri)
        } else if (currentUri.scheme == "content") {
            // Try to get the file path from MediaStore
            var filePath: String? = null
            context.contentResolver.query(
                currentUri,
                arrayOf(MediaStore.Images.Media.DATA),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(MediaStore.Images.Media.DATA)
                    if (idx >= 0) filePath = c.getString(idx)
                }
            }

            if (filePath != null) {
                val currentFile = File(filePath!!)
                val parent = currentFile.parentFile
                if (parent != null && parent.exists()) {
                    parent.listFiles()
                        ?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
                        ?.sortedBy { it.name.lowercase() }
                        ?.map { file ->
                            try {
                                FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                            } catch (_: Exception) {
                                Uri.fromFile(file)
                            }
                        }
                        ?: listOf(currentUri)
                } else {
                    listOf(currentUri)
                }
            } else {
                // Cannot resolve folder — just show current image
                listOf(currentUri)
            }
        } else {
            listOf(currentUri)
        }
    } catch (_: Exception) {
        listOf(currentUri)
    }
}

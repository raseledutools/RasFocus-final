package com.rasel.RasFocus.selfcontrol.study_tools

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.rasel.RasFocus.filemanager.FMPdfViewerScreen
import com.rasel.RasFocus.filemanager.convertPptxToPdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ─────────────────────────────────────────────────────────────────────────────
// PptxViewerActivity — silently converts PPTX → PDF then shows PDF viewer
// The user just sees a brief "Processing…" overlay, then the slides appear.
// ─────────────────────────────────────────────────────────────────────────────

class PptxViewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            else               -> null
        }

        setContent {
            MaterialTheme {
                PptxToPdfViewerScreen(
                    uri    = uri,
                    onBack = { finish() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen — convert then view
// ─────────────────────────────────────────────────────────────────────────────

private val DARK_BG = Color(0xFF1A1A2E)
private val ACCENT  = Color(0xFF6C63FF)

@Composable
fun PptxToPdfViewerScreen(uri: Uri?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var pdfPath   by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg  by remember { mutableStateOf<String?>(null) }

    // Copy URI to a temp file, then convert
    LaunchedEffect(uri) {
        if (uri == null) { isLoading = false; errorMsg = "ফাইল পাওয়া যায়নি"; return@LaunchedEffect }
        withContext(Dispatchers.IO) {
            try {
                // 1. Copy content URI → temp file (converter needs a file path)
                val pptxFile = File(context.cacheDir, "tmp_pptx_${System.currentTimeMillis()}.pptx")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    pptxFile.outputStream().use { input.copyTo(it) }
                }
                // 2. Convert to PDF
                val pdf = convertPptxToPdf(context, pptxFile.absolutePath)
                pptxFile.delete()   // clean up temp pptx

                withContext(Dispatchers.Main) {
                    if (pdf != null && pdf.exists()) {
                        pdfPath   = pdf.absolutePath
                        isLoading = false
                    } else {
                        isLoading = false
                        errorMsg  = "Slides render করতে পারিনি"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoading = false
                    errorMsg  = "Error: ${e.message}"
                }
            }
        }
    }

    when {
        isLoading -> PptxLoadingScreen(onBack = onBack)
        errorMsg != null -> PptxErrorScreen(msg = errorMsg!!, onBack = onBack)
        pdfPath != null -> FMPdfViewerScreen(filePath = pdfPath!!, onBack = onBack)
        else -> PptxErrorScreen(msg = "অজানা সমস্যা", onBack = onBack)
    }
}

// Composable wrapper for use directly inside FileManagerPlusActivity nav
@Composable
fun PptxViewerScreen(uri: Uri?, fileName: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var pdfPath   by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg  by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uri) {
        if (uri == null) { isLoading = false; errorMsg = "ফাইল পাওয়া যায়নি"; return@LaunchedEffect }
        withContext(Dispatchers.IO) {
            try {
                // Resolve file path from URI
                val filePath = when (uri.scheme) {
                    "file"    -> uri.path
                    "content" -> {
                        val tmp = File(context.cacheDir, "tmp_pptx_${System.currentTimeMillis()}.pptx")
                        context.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                        tmp.absolutePath
                    }
                    else -> null
                }

                if (filePath == null) {
                    withContext(Dispatchers.Main) { isLoading = false; errorMsg = "ফাইল পথ বের করা যায়নি" }
                    return@withContext
                }

                val pdf = convertPptxToPdf(context, filePath)
                // clean up temp if we copied
                if (uri.scheme == "content") File(filePath).delete()

                withContext(Dispatchers.Main) {
                    if (pdf != null && pdf.exists()) { pdfPath = pdf.absolutePath; isLoading = false }
                    else { isLoading = false; errorMsg = "Slides render করতে পারিনি" }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { isLoading = false; errorMsg = "Error: ${e.message}" }
            }
        }
    }

    when {
        isLoading -> PptxLoadingScreen(onBack = onClose)
        errorMsg  != null -> PptxErrorScreen(msg = errorMsg!!, onBack = onClose)
        pdfPath   != null -> FMPdfViewerScreen(filePath = pdfPath!!, onBack = onClose)
        else -> PptxErrorScreen(msg = "অজানা সমস্যা", onBack = onClose)
    }
}

@Composable
private fun PptxLoadingScreen(onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(DARK_BG)) {
        // minimal back button top-left
        IconButton(
            onClick  = onBack,
            modifier = Modifier.statusBarsPadding().padding(4.dp)
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }

        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(color = ACCENT, strokeWidth = 3.dp, modifier = Modifier.size(52.dp))
            Spacer(Modifier.height(20.dp))
            Text("Slides লোড হচ্ছে…", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text("একটু অপেক্ষা করুন", color = Color.White.copy(0.5f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun PptxErrorScreen(msg: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(DARK_BG)) {
        IconButton(
            onClick  = onBack,
            modifier = Modifier.statusBarsPadding().padding(4.dp)
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }

        Column(
            Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("📊", fontSize = 52.sp)
            Spacer(Modifier.height(16.dp))
            Text(msg, color = Color(0xFFFF5C5C), fontSize = 13.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = onBack) { Text("ফিরে যান", color = Color.White) }
        }
    }
}

package com.rasel.RasFocus.selfcontrol.study_tools

import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.rasel.RasFocus.filemanager.RobustPdfViewer

class PdfViewerActivity : ComponentActivity() {
    private var pfd: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = intent.data ?: intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)

        if (uri != null) {
            try {
                pfd = contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    pdfRenderer = PdfRenderer(pfd!!)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        setContent {
            MaterialTheme {
                if (pdfRenderer != null) {
                    // ── Pinch-to-zoom + all-page zoom + horizontal pan ──
                    RobustPdfViewer(
                        pdfRenderer = pdfRenderer!!,
                        onBack = { finish() }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0D0D1A)),
                        contentAlignment = Alignment.Center
                    ) {
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
            } catch (e: Exception) {
                e.printStackTrace()
                pdfRenderer = null
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pdfRenderer?.close()
        pfd?.close()
    }
}


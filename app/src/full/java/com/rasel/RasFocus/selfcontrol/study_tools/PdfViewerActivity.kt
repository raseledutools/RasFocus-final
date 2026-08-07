package com.rasel.RasFocus.selfcontrol.study_tools

import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.sync.Mutex
import androidx.compose.ui.unit.dp
import com.rasel.RasFocus.filemanager.PdfPage

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
            val mutex = remember { Mutex() }
            Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF5F5F5)) {
                if (pdfRenderer != null) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        contentPadding = PaddingValues(vertical = 16.dp)
                    ) {
                        items(pdfRenderer!!.pageCount) { index ->
                            PdfPage(pdfRenderer = pdfRenderer!!, pageIndex = index, mutex = mutex)
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Failed to open PDF.", color = Color.Red)
                    }
                }
            }
        }
    }

    override fun onNewIntent(newIntent: android.content.Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        // Close old renderer before opening new URI
        pdfRenderer?.close()
        pfd?.close()
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

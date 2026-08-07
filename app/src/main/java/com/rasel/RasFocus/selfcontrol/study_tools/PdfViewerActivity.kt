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
            Surface(modifier = Modifier.fillMaxSize(), color = Color.LightGray) {
                if (pdfRenderer != null) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(pdfRenderer!!.pageCount) { index ->
                            PdfPage(pdfRenderer = pdfRenderer!!, pageIndex = index, mutex = mutex)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Failed to load PDF", color = Color.Red)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pdfRenderer?.close()
        pfd?.close()
    }
}

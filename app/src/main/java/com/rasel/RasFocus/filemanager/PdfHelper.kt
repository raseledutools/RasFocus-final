package com.rasel.RasFocus.filemanager

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object PdfHelper {

    private var initialized = false

    private fun initPdfBox(context: Context) {
        if (!initialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            initialized = true
        }
    }

    suspend fun mergePdfs(context: Context, sourceFiles: List<File>, destFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            initPdfBox(context)
            val merger = PDFMergerUtility()
            merger.destinationFileName = destFile.absolutePath
            for (file in sourceFiles) {
                merger.addSource(file)
            }
            merger.mergeDocuments(null)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

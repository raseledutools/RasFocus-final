package com.rasel.RasFocus.filemanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

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

    suspend fun pdfToImages(context: Context, pdfFile: File, outputFolder: File): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!outputFolder.exists()) outputFolder.mkdirs()
            val fileDescriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val pdfRenderer = PdfRenderer(fileDescriptor)
            for (i in 0 until pdfRenderer.pageCount) {
                val page = pdfRenderer.openPage(i)
                val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                
                val imageFile = File(outputFolder, "page_${i + 1}.jpg")
                FileOutputStream(imageFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                bitmap.recycle()
                page.close()
            }
            pdfRenderer.close()
            fileDescriptor.close()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun imagesToPdf(context: Context, imageFiles: List<File>, destFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val pdfDocument = PdfDocument()
            for ((index, imageFile) in imageFiles.withIndex()) {
                val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: continue
                val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                pdfDocument.finishPage(page)
                bitmap.recycle()
            }
            FileOutputStream(destFile).use { fos ->
                pdfDocument.writeTo(fos)
            }
            pdfDocument.close()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

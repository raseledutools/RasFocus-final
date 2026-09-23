package com.rasel.RasFocus.filemanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object PdfHelper {

    private var initialized = false

    private fun initPdfBox(context: Context) {
        if (!initialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            initialized = true
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers: detect if a path is on the SD card and get a SAF DocumentFile
    // for writing to it (Android 10+ can't write to SD card via java.io.File)
    // ─────────────────────────────────────────────────────────────────────────

    fun isSdCardPath(context: Context, path: String): Boolean {
        val sdPath = LocalFileManager.getSdCardPath(context) ?: return false
        return path.startsWith(sdPath)
    }

    /**
     * Find the SAF DocumentFile for [dirPath] using the granted tree URIs.
     * Returns null if no matching grant was found.
     */
    fun safDocumentFileForDir(context: Context, dirPath: String): DocumentFile? {
        val sdBase = LocalFileManager.getSdCardPath(context) ?: return null
        for (treeUri in SafFileManager.grantedUris) {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: continue
            // Compute the sub-path relative to the SD card root
            val rel = dirPath.removePrefix(sdBase).trimStart('/')
            if (rel.isEmpty()) return root
            var cur: DocumentFile = root
            for (seg in rel.split('/')) {
                if (seg.isEmpty()) continue
                cur = cur.findFile(seg) ?: cur.createDirectory(seg) ?: return null
            }
            return cur
        }
        return null
    }

    /**
     * Open an OutputStream for writing [fileName] inside [dirPath].
     * Uses SAF if the path is on the SD card; otherwise falls back to File I/O.
     */
    fun openOutputStream(context: Context, dirPath: String, fileName: String, mimeType: String): Pair<OutputStream, String>? {
        return if (isSdCardPath(context, dirPath)) {
            val dir = safDocumentFileForDir(context, dirPath) ?: return null
            // Delete any existing file with the same name first
            dir.findFile(fileName)?.delete()
            val newFile = dir.createFile(mimeType, fileName) ?: return null
            val os = context.contentResolver.openOutputStream(newFile.uri) ?: return null
            // Return the URI string as the "path" for display purposes
            Pair(os, newFile.uri.toString())
        } else {
            val dir = File(dirPath)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            Pair(FileOutputStream(file), file.absolutePath)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PDF Merge
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun mergePdfs(context: Context, sourceFiles: List<File>, destFile: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                initPdfBox(context)
                val merger = PDFMergerUtility()
                merger.destinationFileName = destFile.absolutePath
                for (file in sourceFiles) merger.addSource(file)
                merger.mergeDocuments(null)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

    /**
     * SAF-aware merge: writes the merged PDF via OutputStream so it works on
     * SD card paths where java.io.File writes are blocked.
     * Returns the saved file path/URI string, or null on failure.
     */
    suspend fun mergePdfsToDir(
        context: Context,
        sourceFiles: List<File>,
        destDir: String,
        fileName: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            initPdfBox(context)
            // PdfBox needs a temp file as intermediate
            val tmp = File(context.cacheDir, "merge_tmp_${System.currentTimeMillis()}.pdf")
            val merger = PDFMergerUtility()
            merger.destinationFileName = tmp.absolutePath
            for (file in sourceFiles) merger.addSource(file)
            merger.mergeDocuments(null)

            val (os, resultPath) = openOutputStream(context, destDir, fileName, "application/pdf")
                ?: return@withContext null
            os.use { tmp.inputStream().use { ins -> ins.copyTo(it) } }
            tmp.delete()
            resultPath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PDF → Images
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun pdfToImages(context: Context, pdfFile: File, outputFolder: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (!outputFolder.exists()) outputFolder.mkdirs()
                val fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(fd)
                for (i in 0 until renderer.pageCount) {
                    val page = renderer.openPage(i)
                    val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    canvas.drawColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val imgFile = File(outputFolder, "page_${i + 1}.jpg")
                    FileOutputStream(imgFile).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    bmp.recycle()
                    page.close()
                }
                renderer.close(); fd.close()
                true
            } catch (e: Exception) { e.printStackTrace(); false }
        }

    /**
     * SAF-aware PDF → images: saves pages into [destDirPath] (SD card safe).
     * Returns true on success.
     */
    suspend fun pdfToImagesToDir(
        context: Context,
        pdfFile: File,
        destDirPath: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.drawColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                val osResult = openOutputStream(context, destDirPath, "page_${i + 1}.jpg", "image/jpeg")
                if (osResult == null) {
                    bmp.recycle(); page.close()
                } else {
                    val (os, _) = osResult
                    os.use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    bmp.recycle(); page.close()
                }
            }
            renderer.close(); fd.close()
            true
        } catch (e: Exception) { e.printStackTrace(); false }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Images → PDF
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun imagesToPdf(context: Context, imageFiles: List<File>, destFile: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val doc = PdfDocument()
                for ((idx, img) in imageFiles.withIndex()) {
                    val bmp = BitmapFactory.decodeFile(img.absolutePath) ?: continue
                    val info = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, idx + 1).create()
                    val page = doc.startPage(info)
                    page.canvas.drawBitmap(bmp, 0f, 0f, null)
                    doc.finishPage(page); bmp.recycle()
                }
                FileOutputStream(destFile).use { doc.writeTo(it) }
                doc.close(); true
            } catch (e: Exception) { e.printStackTrace(); false }
        }

    /**
     * SAF-aware images → PDF: writes the output PDF via OutputStream.
     * Returns the saved file path/URI string, or null on failure.
     */
    suspend fun imagesToPdfToDir(
        context: Context,
        imageFiles: List<File>,
        destDir: String,
        fileName: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val doc = PdfDocument()
            for ((idx, img) in imageFiles.withIndex()) {
                val bmp = BitmapFactory.decodeFile(img.absolutePath) ?: continue
                val info = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, idx + 1).create()
                val page = doc.startPage(info)
                page.canvas.drawBitmap(bmp, 0f, 0f, null)
                doc.finishPage(page); bmp.recycle()
            }
            val (os, resultPath) = openOutputStream(context, destDir, fileName, "application/pdf")
                ?: run { doc.close(); return@withContext null }
            os.use { doc.writeTo(it) }
            doc.close()
            resultPath
        } catch (e: Exception) { e.printStackTrace(); null }
    }
}

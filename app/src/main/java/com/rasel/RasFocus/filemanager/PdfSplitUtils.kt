package com.rasel.RasFocus.filemanager

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.io.File

object PdfSplitUtils {

    /**
     * Parses a range string like "1-3,5,7-9" into list of (startPage, endPage) pairs (1-indexed).
     */
    fun parseRanges(rangeStr: String, totalPages: Int): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        val parts = rangeStr.split(",")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.contains("-")) {
                val bounds = trimmed.split("-")
                if (bounds.size == 2) {
                    val start = bounds[0].trim().toIntOrNull() ?: continue
                    val end = bounds[1].trim().toIntOrNull() ?: continue
                    if (start in 1..totalPages && end in start..totalPages) {
                        result.add(Pair(start, end))
                    }
                }
            } else {
                val page = trimmed.toIntOrNull() ?: continue
                if (page in 1..totalPages) {
                    result.add(Pair(page, page))
                }
            }
        }
        return result
    }

    /**
     * Splits [pdfFile] into multiple PDF files inside [outputDir], one per range.
     * Returns list of created files on success, empty list on failure.
     */
    fun splitPdf(pdfFile: File, outputDir: File, ranges: List<Pair<Int, Int>>): List<File> {
        if (ranges.isEmpty()) return emptyList()
        outputDir.mkdirs()
        val createdFiles = mutableListOf<File>()
        try {
            val doc = PDDocument.load(pdfFile)
            val baseName = pdfFile.nameWithoutExtension
            for ((index, range) in ranges.withIndex()) {
                val (start, end) = range
                val newDoc = PDDocument()
                for (pageNum in start..end) {
                    val page: PDPage = doc.getPage(pageNum - 1) // 0-indexed
                    newDoc.addPage(newDoc.importPage(page))
                }
                val rangeLabel = if (start == end) "p$start" else "p${start}-${end}"
                val outFile = File(outputDir, "${baseName}_${rangeLabel}.pdf")
                newDoc.save(outFile)
                newDoc.close()
                createdFiles.add(outFile)
            }
            doc.close()
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
        return createdFiles
    }
}

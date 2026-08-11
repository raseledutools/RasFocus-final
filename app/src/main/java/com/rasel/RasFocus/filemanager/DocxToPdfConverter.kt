package com.rasel.RasFocus.filemanager

import android.content.Context
import android.graphics.*
import android.text.StaticLayout
import android.text.TextPaint
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

// ─────────────────────────────────────────────────────────────────────────────
// DOCX → PDF Converter
//
// Strategy:
//   1. Unzip .docx (it's a ZIP containing word/document.xml)
//   2. Parse paragraphs, runs, bold/italic/heading/indent from XML
//   3. Render text onto A4 Bitmap pages using Android Canvas + StaticLayout
//   4. Embed Bitmaps as JPEG pages in a PDF via pdfbox-android
//   5. Write temp PDF to cache dir → return path to caller
//
// .doc (old binary) → fallback: show error, user opens externally
// ─────────────────────────────────────────────────────────────────────────────

// A4 at 96dpi
private const val PAGE_W = 794f
private const val PAGE_H = 1123f
private const val MARGIN  = 60f
private const val CONTENT_W = PAGE_W - MARGIN * 2

// ── Data ──────────────────────────────────────────────────────────────────────

private data class DocParagraph(
    val runs        : List<DocRun>,
    val isHeading   : Boolean = false,
    val headingLevel: Int     = 0,   // 1–6
    val indent      : Int     = 0,   // twips/720 → levels
    val isBullet    : Boolean = false,
    val alignment   : Align   = Align.LEFT,
    val spaceAfter  : Int     = 0,   // twips
)

private data class DocRun(
    val text    : String,
    val bold    : Boolean = false,
    val italic  : Boolean = false,
    val fontSize: Float   = 24f,    // half-points → divide by 2 when rendering
    val color   : Int     = Color.BLACK,
    val underline: Boolean = false,
)

private enum class Align { LEFT, CENTER, RIGHT, JUSTIFY }

// ── Public entry point ────────────────────────────────────────────────────────

suspend fun convertDocxToPdf(context: Context, docxPath: String): File? =
    withContext(Dispatchers.IO) {
        try {
            try { PDFBoxResourceLoader.init(context.applicationContext) } catch (_: Exception) {}

            val file = File(docxPath)
            if (!file.exists()) return@withContext null

            // .doc binary format — not supported
            if (file.extension.lowercase() == "doc") return@withContext null

            val paragraphs = parseDocx(file)
            if (paragraphs.isEmpty()) return@withContext null

            val pages   = renderToPages(paragraphs)
            val outFile = File(context.cacheDir, "docx_preview_${System.currentTimeMillis()}.pdf")
            pagesToPdf(pages, outFile)
            outFile

        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

// ── Step 1: Parse word/document.xml ──────────────────────────────────────────

private fun parseDocx(file: File): List<DocParagraph> {
    val result = mutableListOf<DocParagraph>()

    try {
        val stream: InputStream = file.inputStream()
        ZipInputStream(stream.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    result.addAll(parseDocumentXml(zip))
                    break
                }
                entry = zip.nextEntry
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    return result
}

private fun parseDocumentXml(stream: InputStream): List<DocParagraph> {
    val result  = mutableListOf<DocParagraph>()
    val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
    val parser  = factory.newPullParser()
    parser.setInput(stream, "UTF-8")

    // State
    var inBody      = false
    var inPara      = false
    var inRun       = false
    var inRPr       = false  // run properties
    var inPPr       = false  // paragraph properties

    // Paragraph-level
    var isHeading    = false
    var headingLevel = 0
    var indent       = 0
    var isBullet     = false
    var align        = Align.LEFT
    var spaceAfter   = 0

    // Run-level
    var runBold      = false
    var runItalic    = false
    var runUnderline = false
    var runFontSize  = 24f   // half-points
    var runColor     = Color.BLACK

    // Paragraph-level defaults (inherited from pPr)
    var paraFontSize = 24f
    var paraBold     = false

    val runs        = mutableListOf<DocRun>()
    val runText     = StringBuilder()

    fun flushRun() {
        val t = runText.toString()
        if (t.isNotEmpty()) {
            runs.add(DocRun(t, runBold || paraBold, runItalic, runFontSize, runColor, runUnderline))
        }
        runText.clear()
    }

    fun flushPara() {
        if (runs.isNotEmpty() || result.isEmpty()) {
            result.add(DocParagraph(runs.toList(), isHeading, headingLevel, indent, isBullet, align, spaceAfter))
        } else {
            // empty paragraph = blank line spacer
            result.add(DocParagraph(listOf(DocRun(""))))
        }
        runs.clear()
        isHeading    = false
        headingLevel = 0
        indent       = 0
        isBullet     = false
        align        = Align.LEFT
        spaceAfter   = 0
        paraFontSize = 24f
        paraBold     = false
    }

    var eventType = parser.eventType
    while (eventType != XmlPullParser.END_DOCUMENT) {
        val tag = parser.name?.substringAfterLast(':') ?: ""   // strip namespace

        when (eventType) {
            XmlPullParser.START_TAG -> {
                when (tag) {
                    "body"    -> inBody = true
                    "p"       -> if (inBody) { inPara = true }
                    "r"       -> if (inPara) {
                        inRun = true
                        // reset run props (inherit para defaults)
                        runBold      = paraBold
                        runItalic    = false
                        runUnderline = false
                        runFontSize  = paraFontSize
                        runColor     = Color.BLACK
                    }
                    "pPr"     -> if (inPara) inPPr = true
                    "rPr"     -> if (inRun || inPPr) inRPr = true

                    // Paragraph style → heading detection
                    "pStyle"  -> if (inPPr) {
                        val styleId = parser.getAttributeValue(null, "w:val")
                            ?: parser.getAttributeValue(null, "val") ?: ""
                        val lower = styleId.lowercase()
                        when {
                            lower.startsWith("heading") || lower.startsWith("kop") -> {
                                isHeading = true
                                headingLevel = lower.filter { it.isDigit() }.firstOrNull()
                                    ?.digitToInt() ?: 1
                                paraFontSize = when (headingLevel) {
                                    1 -> 52f; 2 -> 44f; 3 -> 36f; 4 -> 30f; else -> 26f
                                }
                                paraBold = headingLevel <= 3
                            }
                            lower == "title"    -> { isHeading = true; headingLevel = 0; paraFontSize = 64f; paraBold = true }
                            lower == "subtitle" -> { isHeading = true; headingLevel = 1; paraFontSize = 48f }
                        }
                    }

                    // Indentation
                    "ind"     -> if (inPPr) {
                        val left = (parser.getAttributeValue(null, "w:left")
                            ?: parser.getAttributeValue(null, "left"))?.toIntOrNull() ?: 0
                        indent = (left / 720).coerceIn(0, 8)
                    }

                    // Alignment
                    "jc"      -> if (inPPr) {
                        align = when ((parser.getAttributeValue(null, "w:val")
                            ?: parser.getAttributeValue(null, "val") ?: "").lowercase()) {
                            "center"  -> Align.CENTER
                            "right"   -> Align.RIGHT
                            "both", "distribute" -> Align.JUSTIFY
                            else      -> Align.LEFT
                        }
                    }

                    // Spacing
                    "spacing" -> if (inPPr) {
                        spaceAfter = (parser.getAttributeValue(null, "w:after")
                            ?: parser.getAttributeValue(null, "after"))?.toIntOrNull() ?: 0
                    }

                    // Bullet / numPr
                    "numPr"   -> if (inPPr) isBullet = true

                    // Run properties
                    "b"       -> if (inRPr) runBold      = true
                    "i"       -> if (inRPr) runItalic    = true
                    "u"       -> if (inRPr) runUnderline = true
                    "sz"      -> if (inRPr) {
                        runFontSize = (parser.getAttributeValue(null, "w:val")
                            ?: parser.getAttributeValue(null, "val"))?.toFloatOrNull() ?: paraFontSize
                    }
                    "color"   -> if (inRPr) {
                        val hex = parser.getAttributeValue(null, "w:val")
                            ?: parser.getAttributeValue(null, "val") ?: "000000"
                        runColor = if (hex == "auto") Color.BLACK
                                   else try { Color.parseColor("#$hex") } catch (_: Exception) { Color.BLACK }
                    }

                    // Actual text
                    "t"       -> { /* read in TEXT event */ }

                    // Line break inside run
                    "br"      -> if (inRun) runText.append('\n')

                    // Tab
                    "tab"     -> if (inRun) runText.append("    ")
                }
            }

            XmlPullParser.TEXT -> {
                if (inRun) runText.append(parser.text ?: "")
            }

            XmlPullParser.END_TAG -> {
                when (tag) {
                    "body" -> inBody = false
                    "p"    -> if (inBody) { flushRun(); flushPara(); inPara = false }
                    "r"    -> if (inPara) { flushRun(); inRun = false }
                    "pPr"  -> inPPr = false
                    "rPr"  -> inRPr = false
                }
            }
        }

        eventType = parser.next()
    }

    return result
}

// ── Step 2: Render paragraphs to A4 Bitmap pages ─────────────────────────────

private fun renderToPages(paragraphs: List<DocParagraph>): List<Bitmap> {
    val pages   = mutableListOf<Bitmap>()
    var bitmap  = newPage()
    var canvas  = Canvas(bitmap)
    var cursorY = MARGIN

    fun newPageIfNeeded(needed: Float): Boolean {
        if (cursorY + needed > PAGE_H - MARGIN) {
            pages.add(bitmap)
            bitmap  = newPage()
            canvas  = Canvas(bitmap)
            cursorY = MARGIN
            return true
        }
        return false
    }

    for (para in paragraphs) {
        val runs = para.runs
        if (runs.isEmpty() || runs.all { it.text.isEmpty() }) {
            // blank line
            cursorY += 14f
            continue
        }

        val startX = MARGIN + para.indent * 24f
        val availW  = (CONTENT_W - para.indent * 24f).toInt()

        // Build combined text + TextPaint for StaticLayout
        // We use the first (or dominant) run for paint, draw per-run later
        val fullText = runs.joinToString("") { it.text }

        // Use first non-empty run's style as base
        val base = runs.firstOrNull { it.text.isNotEmpty() } ?: runs.first()

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize    = (base.fontSize / 2f).coerceIn(10f, 48f)
            typeface    = when {
                base.bold && base.italic -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
                base.bold               -> Typeface.DEFAULT_BOLD
                base.italic             -> Typeface.defaultFromStyle(Typeface.ITALIC)
                else                    -> Typeface.DEFAULT
            }
            color       = base.color
            isAntiAlias = true
        }

        val layout = StaticLayout.Builder
            .obtain(fullText, 0, fullText.length, paint, availW)
            .setAlignment(when (para.alignment) {
                Align.CENTER  -> android.text.Layout.Alignment.ALIGN_CENTER
                Align.RIGHT   -> android.text.Layout.Alignment.ALIGN_OPPOSITE
                else          -> android.text.Layout.Alignment.ALIGN_NORMAL
            })
            .setLineSpacing(0f, 1.3f)
            .setIncludePad(false)
            .build()

        val blockH = layout.height.toFloat()

        // Bullet prefix
        val bulletPrefix = if (para.isBullet) "• " else ""
        val prefixPaint  = if (para.isBullet) TextPaint(paint).apply { textSize = paint.textSize } else null

        newPageIfNeeded(blockH + 8f)

        // Draw heading top-spacing
        if (para.isHeading) cursorY += 8f

        // Bullet dot
        if (para.isBullet && prefixPaint != null) {
            canvas.drawText(bulletPrefix, startX - 20f, cursorY + paint.textSize, prefixPaint)
        }

        // Draw text block
        canvas.save()
        canvas.translate(startX, cursorY)
        layout.draw(canvas)
        canvas.restore()

        // Underline (post-draw, line by line)
        if (runs.any { it.underline }) {
            val underlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color       = base.color
                strokeWidth = 1f
            }
            for (i in 0 until layout.lineCount) {
                val lineBaseline = cursorY + layout.getLineBaseline(i) + 2f
                val lineLeft     = startX + layout.getLineLeft(i)
                val lineRight    = startX + layout.getLineRight(i)
                canvas.drawLine(lineLeft, lineBaseline, lineRight, lineBaseline, underlinePaint)
            }
        }

        // Heading underline rule (h1, h2)
        if (para.isHeading && para.headingLevel <= 2) {
            val rulePaint = Paint().apply {
                color       = Color.parseColor("#CCCCCC")
                strokeWidth = 1.5f
            }
            canvas.drawLine(MARGIN, cursorY + blockH + 4f,
                PAGE_W - MARGIN, cursorY + blockH + 4f, rulePaint)
            cursorY += 6f
        }

        cursorY += blockH + (para.spaceAfter / 720f * 12f).coerceIn(4f, 24f)
    }

    // last page
    if (cursorY > MARGIN) pages.add(bitmap)

    return pages
}

private fun newPage(): Bitmap =
    Bitmap.createBitmap(PAGE_W.toInt(), PAGE_H.toInt(), Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawColor(Color.WHITE)
    }

// ── Step 3: Bitmaps → PDF via pdfbox ─────────────────────────────────────────

private fun pagesToPdf(pages: List<Bitmap>, out: File) {
    val doc = PDDocument()
    try {
        for (bmp in pages) {
            val jpgImage = JPEGFactory.createFromImage(doc, bmp, 0.88f)

            val page = PDPage(PDRectangle(PAGE_W, PAGE_H))
            doc.addPage(page)

            PDPageContentStream(doc, page).use { cs ->
                cs.drawImage(jpgImage, 0f, 0f, PAGE_W, PAGE_H)
            }

            bmp.recycle()
        }
        doc.save(out)
    } finally {
        doc.close()
    }
}

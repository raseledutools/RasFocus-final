package com.rasel.RasFocus.filemanager

import android.content.Context
import android.graphics.*
import android.os.Build
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
// DOCX → PDF Converter  (Bangla + Latin font fix)
//
// Key fix: use Typeface.createFromAsset / system Noto Bengali font so
// Bangla Unicode glyphs render correctly on Canvas instead of showing boxes.
// ─────────────────────────────────────────────────────────────────────────────

private const val PAGE_W    = 794f
private const val PAGE_H    = 1123f
private const val MARGIN    = 60f
private const val CONTENT_W = PAGE_W - MARGIN * 2

// ── Data ──────────────────────────────────────────────────────────────────────

private data class DocParagraph(
    val runs        : List<DocRun>,
    val isHeading   : Boolean = false,
    val headingLevel: Int     = 0,
    val indent      : Int     = 0,
    val isBullet    : Boolean = false,
    val alignment   : Align   = Align.LEFT,
    val spaceAfter  : Int     = 0,
)

private data class DocRun(
    val text     : String,
    val bold     : Boolean = false,
    val italic   : Boolean = false,
    val fontSize : Float   = 24f,
    val color    : Int     = Color.BLACK,
    val underline: Boolean = false,
)

private enum class Align { LEFT, CENTER, RIGHT, JUSTIFY }

// ── Font helper ───────────────────────────────────────────────────────────────
// Returns a Typeface that can render both Bangla and Latin characters.
// Android ships Noto Sans Bengali as a system font; we load it explicitly
// so Canvas/StaticLayout uses it instead of falling back to boxes.

private var cachedBanglaTypeface: Typeface? = null

private fun getBanglaTypeface(bold: Boolean, italic: Boolean): Typeface {
    val base = cachedBanglaTypeface ?: run {
        // Try system Noto Bengali paths (varies by Android version)
        val candidates = listOf(
            "/system/fonts/NotoSansBengali-Regular.ttf",
            "/system/fonts/NotoSansBengali.ttf",
            "/system/fonts/NotoSerifBengali-Regular.ttf",
            "/system/fonts/Roboto-Regular.ttf", // last fallback
        )
        val tf = candidates.firstNotNullOfOrNull { path ->
            try { Typeface.createFromFile(path) } catch (_: Exception) { null }
        } ?: Typeface.DEFAULT
        cachedBanglaTypeface = tf
        tf
    }
    return when {
        bold && italic -> Typeface.create(base, Typeface.BOLD_ITALIC)
        bold           -> Typeface.create(base, Typeface.BOLD)
        italic         -> Typeface.create(base, Typeface.ITALIC)
        else           -> base
    }
}

// ── Public entry point ────────────────────────────────────────────────────────

suspend fun convertDocxToPdf(context: Context, docxPath: String): File? =
    withContext(Dispatchers.IO) {
        try {
            try { PDFBoxResourceLoader.init(context.applicationContext) } catch (_: Exception) {}

            val file = File(docxPath)
            if (!file.exists()) return@withContext null
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
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    result.addAll(parseDocumentXml(zip))
                    break
                }
                entry = zip.nextEntry
            }
        }
    } catch (e: Exception) { e.printStackTrace() }
    return result
}

private fun parseDocumentXml(stream: InputStream): List<DocParagraph> {
    val result  = mutableListOf<DocParagraph>()
    val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
    val parser  = factory.newPullParser()
    parser.setInput(stream, "UTF-8")

    var inBody = false; var inPara = false; var inRun = false
    var inRPr  = false; var inPPr  = false

    var isHeading    = false; var headingLevel = 0; var indent = 0
    var isBullet     = false; var align = Align.LEFT; var spaceAfter = 0
    var runBold      = false; var runItalic    = false; var runUnderline = false
    var runFontSize  = 24f;   var runColor     = Color.BLACK
    var paraFontSize = 24f;   var paraBold     = false

    val runs    = mutableListOf<DocRun>()
    val runText = StringBuilder()

    fun flushRun() {
        val t = runText.toString()
        if (t.isNotEmpty())
            runs.add(DocRun(t, runBold || paraBold, runItalic, runFontSize, runColor, runUnderline))
        runText.clear()
    }

    fun flushPara() {
        if (runs.isNotEmpty()) {
            result.add(DocParagraph(runs.toList(), isHeading, headingLevel, indent, isBullet, align, spaceAfter))
        } else {
            result.add(DocParagraph(listOf(DocRun(""))))
        }
        runs.clear()
        isHeading = false; headingLevel = 0; indent = 0; isBullet = false
        align = Align.LEFT; spaceAfter = 0; paraFontSize = 24f; paraBold = false
    }

    var eventType = parser.eventType
    while (eventType != XmlPullParser.END_DOCUMENT) {
        val tag = parser.name?.substringAfterLast(':') ?: ""
        when (eventType) {
            XmlPullParser.START_TAG -> when (tag) {
                "body"    -> inBody = true
                "p"       -> if (inBody) inPara = true
                "r"       -> if (inPara) {
                    inRun = true
                    runBold = paraBold; runItalic = false; runUnderline = false
                    runFontSize = paraFontSize; runColor = Color.BLACK
                }
                "pPr"     -> if (inPara) inPPr = true
                "rPr"     -> if (inRun || inPPr) inRPr = true
                "pStyle"  -> if (inPPr) {
                    val v = (parser.getAttributeValue(null, "w:val")
                        ?: parser.getAttributeValue(null, "val") ?: "").lowercase()
                    when {
                        v.startsWith("heading") || v.startsWith("kop") -> {
                            isHeading = true
                            headingLevel = v.filter { it.isDigit() }.firstOrNull()?.digitToInt() ?: 1
                            paraFontSize = when (headingLevel) { 1 -> 52f; 2 -> 44f; 3 -> 36f; 4 -> 30f; else -> 26f }
                            paraBold = headingLevel <= 3
                        }
                        v == "title"    -> { isHeading = true; headingLevel = 0; paraFontSize = 64f; paraBold = true }
                        v == "subtitle" -> { isHeading = true; headingLevel = 1; paraFontSize = 48f }
                    }
                }
                "ind"     -> if (inPPr) {
                    val left = (parser.getAttributeValue(null, "w:left")
                        ?: parser.getAttributeValue(null, "left"))?.toIntOrNull() ?: 0
                    indent = (left / 720).coerceIn(0, 8)
                }
                "jc"      -> if (inPPr) {
                    align = when ((parser.getAttributeValue(null, "w:val")
                        ?: parser.getAttributeValue(null, "val") ?: "").lowercase()) {
                        "center" -> Align.CENTER; "right" -> Align.RIGHT
                        "both", "distribute" -> Align.JUSTIFY; else -> Align.LEFT
                    }
                }
                "spacing" -> if (inPPr) {
                    spaceAfter = (parser.getAttributeValue(null, "w:after")
                        ?: parser.getAttributeValue(null, "after"))?.toIntOrNull() ?: 0
                }
                "numPr"   -> if (inPPr) isBullet = true
                "b"       -> if (inRPr) runBold = true
                "i"       -> if (inRPr) runItalic = true
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
                "br"      -> if (inRun) runText.append('\n')
                "tab"     -> if (inRun) runText.append("    ")
            }
            XmlPullParser.TEXT -> if (inRun) runText.append(parser.text ?: "")
            XmlPullParser.END_TAG -> when (tag) {
                "body" -> inBody = false
                "p"    -> if (inBody) { flushRun(); flushPara(); inPara = false }
                "r"    -> if (inPara) { flushRun(); inRun = false }
                "pPr"  -> inPPr = false
                "rPr"  -> inRPr = false
            }
        }
        eventType = parser.next()
    }
    return result
}

// ── Step 2: Render to Bitmap pages ───────────────────────────────────────────

private fun renderToPages(paragraphs: List<DocParagraph>): List<Bitmap> {
    val pages  = mutableListOf<Bitmap>()
    var bitmap = newPage()
    var canvas = Canvas(bitmap)
    var cursorY = MARGIN

    fun newPageIfNeeded(needed: Float) {
        if (cursorY + needed > PAGE_H - MARGIN) {
            pages.add(bitmap)
            bitmap  = newPage()
            canvas  = Canvas(bitmap)
            cursorY = MARGIN
        }
    }

    for (para in paragraphs) {
        val runs = para.runs
        if (runs.isEmpty() || runs.all { it.text.isEmpty() }) {
            cursorY += 14f; continue
        }

        val startX = MARGIN + para.indent * 24f
        val availW = (CONTENT_W - para.indent * 24f).toInt()
        val fullText = runs.joinToString("") { it.text }
        val base = runs.firstOrNull { it.text.isNotEmpty() } ?: runs.first()

        // ── KEY FIX: use Bangla-capable typeface ──────────────────────────────
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize    = (base.fontSize / 2f).coerceIn(10f, 48f)
            typeface    = getBanglaTypeface(base.bold, base.italic)
            color       = base.color
            isAntiAlias = true
            // Sub-pixel rendering for cleaner text
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isFilterBitmap = true
            }
        }

        val layout = StaticLayout.Builder
            .obtain(fullText, 0, fullText.length, paint, availW)
            .setAlignment(when (para.alignment) {
                Align.CENTER  -> android.text.Layout.Alignment.ALIGN_CENTER
                Align.RIGHT   -> android.text.Layout.Alignment.ALIGN_OPPOSITE
                else          -> android.text.Layout.Alignment.ALIGN_NORMAL
            })
            .setLineSpacing(2f, 1.4f)   // slightly more line spacing for Bangla
            .setIncludePad(false)
            .build()

        val blockH = layout.height.toFloat()
        newPageIfNeeded(blockH + 12f)

        if (para.isHeading) cursorY += 10f

        if (para.isBullet) {
            val bulletPaint = TextPaint(paint).apply { typeface = getBanglaTypeface(false, false) }
            canvas.drawText("•  ", startX - 24f, cursorY + paint.textSize, bulletPaint)
        }

        canvas.save()
        canvas.translate(startX, cursorY)
        layout.draw(canvas)
        canvas.restore()

        if (runs.any { it.underline }) {
            val ul = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = base.color; strokeWidth = 1f }
            for (i in 0 until layout.lineCount) {
                canvas.drawLine(
                    startX + layout.getLineLeft(i),
                    cursorY + layout.getLineBaseline(i) + 3f,
                    startX + layout.getLineRight(i),
                    cursorY + layout.getLineBaseline(i) + 3f,
                    ul
                )
            }
        }

        if (para.isHeading && para.headingLevel <= 2) {
            val rule = Paint().apply { color = Color.parseColor("#CCCCCC"); strokeWidth = 1.5f }
            canvas.drawLine(MARGIN, cursorY + blockH + 5f, PAGE_W - MARGIN, cursorY + blockH + 5f, rule)
            cursorY += 8f
        }

        cursorY += blockH + (para.spaceAfter / 720f * 12f).coerceIn(4f, 28f)
    }

    if (cursorY > MARGIN) pages.add(bitmap)
    return pages
}

private fun newPage(): Bitmap =
    Bitmap.createBitmap(PAGE_W.toInt(), PAGE_H.toInt(), Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawColor(Color.WHITE)
    }

// ── Step 3: Bitmaps → PDF ─────────────────────────────────────────────────────

private fun pagesToPdf(pages: List<Bitmap>, out: File) {
    val doc = PDDocument()
    try {
        for (bmp in pages) {
            val img  = JPEGFactory.createFromImage(doc, bmp, 0.92f)
            val page = PDPage(PDRectangle(PAGE_W, PAGE_H))
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.drawImage(img, 0f, 0f, PAGE_W, PAGE_H)
            }
            bmp.recycle()
        }
        doc.save(out)
    } finally {
        doc.close()
    }
}

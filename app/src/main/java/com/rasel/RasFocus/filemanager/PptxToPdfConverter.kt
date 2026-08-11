package com.rasel.RasFocus.filemanager

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
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
import java.util.zip.ZipFile
import kotlin.math.min
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────
// PPTX → PDF Converter
//
// Strategy:
//   1. Unzip .pptx (it's a ZIP)
//   2. Parse each ppt/slides/slideN.xml → extract text, positions, colors,
//      font sizes, background, and embedded images
//   3. Draw each slide on an Android Canvas (960 × 540 px, 16:9)
//   4. Encode each Bitmap as JPEG → embed in a PDF via pdfbox-android
//   5. Write temp PDF to cache dir → return path
//
// The caller (PptxViewerScreen) shows a "Processing…" overlay, then opens
// the resulting PDF through FMPdfViewerScreen — the user never sees the
// intermediate step.
// ─────────────────────────────────────────────────────────────────────────────

private const val SLIDE_W = 960f
private const val SLIDE_H = 540f

// ── Data classes ─────────────────────────────────────────────────────────────

data class SlideData(
    val bgColor:     Int              = Color.WHITE,
    val shapes:      List<SlideShape> = emptyList(),
    val images:      List<SlideImage> = emptyList()
)

data class SlideShape(
    val texts:    List<TextRun>,
    val x: Float, val y: Float,
    val w: Float, val h: Float,
    val fillColor: Int?  = null,   // null = transparent
    val isTitlePh: Boolean = false
)

data class TextRun(
    val text:     String,
    val fontSize: Float  = 18f,
    val bold:     Boolean = false,
    val italic:   Boolean = false,
    val color:    Int     = Color.BLACK
)

data class SlideImage(
    val bytes: ByteArray,
    val x: Float, val y: Float,
    val w: Float, val h: Float
)

// ── Public entry point ────────────────────────────────────────────────────────

suspend fun convertPptxToPdf(context: Context, pptxPath: String): File? =
    withContext(Dispatchers.IO) {
        try {
            // Ensure PDFBox is initialised
            try { PDFBoxResourceLoader.init(context.applicationContext) } catch (_: Exception) {}

            val zipFile   = ZipFile(pptxPath)
            val slideData = parsePptx(zipFile)
            zipFile.close()

            if (slideData.isEmpty()) return@withContext null

            val bitmaps = slideData.map { drawSlide(it) }
            val outFile = File(context.cacheDir, "pptx_preview_${System.currentTimeMillis()}.pdf")
            bitmapsToPdf(context, bitmaps, outFile)
            bitmaps.forEach { it.recycle() }
            outFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

// ── Step 1: Parse all slides ─────────────────────────────────────────────────

private fun parsePptx(zip: ZipFile): List<SlideData> {
    // Collect slide entries sorted by number
    val regex  = Regex("ppt/slides/slide(\\d+)\\.xml")
    val entries = zip.entries().asSequence()
        .mapNotNull { e ->
            val m = regex.matchEntire(e.name)
            if (m != null) m.groupValues[1].toInt() to e.name else null
        }
        .sortedBy { it.first }
        .map { it.second }
        .toList()

    return entries.map { entryName ->
        parseSlide(zip, entryName)
    }
}

// ── Step 2: Parse a single slide XML ─────────────────────────────────────────

private fun parseSlide(zip: ZipFile, slidePath: String): SlideData {
    val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
    val xml     = zip.getInputStream(zip.getEntry(slidePath)).reader().readText()

    // ── Background colour ─────────────────────────────────────────────────────
    // Look for <p:bg><p:bgPr><a:solidFill><a:srgbClr val="RRGGBB"/>
    val bgColor = extractBgColor(xml)

    // ── Parse shapes & images via pull parser ─────────────────────────────────
    val shapes  = mutableListOf<SlideShape>()
    val images  = mutableListOf<SlideImage>()

    val parser  = factory.newPullParser()
    parser.setInput(xml.reader())

    // State
    data class ShapeCtx(
        var x: Float = 0f, var y: Float = 0f,
        var w: Float = SLIDE_W, var h: Float = SLIDE_H * 0.6f,
        var fillColor: Int? = null,
        var isTitlePh: Boolean = false
    )

    var inSpTree   = false
    var inSp       = false
    var inPic      = false
    var inNvSpPr   = false
    var inTxBody   = false
    var inParagraph = false
    var inRun      = false
    var inRPr      = false
    var currentCtx = ShapeCtx()
    var currentFontSize = 18f
    var currentBold     = false
    var currentItalic   = false
    var currentColor    = Color.BLACK
    var runText = StringBuilder()
    var paraRuns = mutableListOf<TextRun>()
    var shapeRuns = mutableListOf<List<TextRun>>()
    var imageRId = ""
    var inOff = false; var inExt = false
    var offInShape = false

    // Slide dimensions from sldSz (may appear in slide layout/master; default 9144000 × 5143500 EMUs)
    var slideWidthEmu  = 9144000f
    var slideHeightEmu = 5143500f

    fun emuToSlideX(emu: Long) = (emu / slideWidthEmu)  * SLIDE_W
    fun emuToSlideY(emu: Long) = (emu / slideHeightEmu) * SLIDE_H

    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        val tag = parser.name ?: ""
        val ns  = parser.namespace ?: ""
        when (event) {
            XmlPullParser.START_TAG -> when {
                tag == "sldSz" -> {
                    val cxAttr = parser.getAttributeValue(null, "cx")?.toLongOrNull()
                    val cyAttr = parser.getAttributeValue(null, "cy")?.toLongOrNull()
                    if (cxAttr != null) slideWidthEmu  = cxAttr.toFloat()
                    if (cyAttr != null) slideHeightEmu = cyAttr.toFloat()
                }
                tag == "spTree" -> inSpTree = true
                tag == "sp" && inSpTree -> {
                    inSp = true; currentCtx = ShapeCtx()
                    shapeRuns = mutableListOf()
                }
                tag == "pic" && inSpTree -> {
                    inPic = true; currentCtx = ShapeCtx(); imageRId = ""
                }
                tag == "nvSpPr" && inSp -> inNvSpPr = true
                tag == "ph" && inNvSpPr -> {
                    val typ = parser.getAttributeValue(null, "type") ?: "body"
                    currentCtx.isTitlePh = typ in listOf("title", "ctrTitle")
                }
                tag == "off" && inSp -> {
                    val xEmu = parser.getAttributeValue(null, "x")?.toLongOrNull() ?: 0L
                    val yEmu = parser.getAttributeValue(null, "y")?.toLongOrNull() ?: 0L
                    currentCtx.x = emuToSlideX(xEmu)
                    currentCtx.y = emuToSlideY(yEmu)
                }
                tag == "ext" && inSp -> {
                    val cxEmu = parser.getAttributeValue(null, "cx")?.toLongOrNull() ?: (slideWidthEmu.toLong())
                    val cyEmu = parser.getAttributeValue(null, "cy")?.toLongOrNull() ?: (slideHeightEmu.toLong() / 2)
                    currentCtx.w = emuToSlideX(cxEmu)
                    currentCtx.h = emuToSlideY(cyEmu)
                }
                tag == "off" && inPic -> {
                    val xEmu = parser.getAttributeValue(null, "x")?.toLongOrNull() ?: 0L
                    val yEmu = parser.getAttributeValue(null, "y")?.toLongOrNull() ?: 0L
                    currentCtx.x = emuToSlideX(xEmu)
                    currentCtx.y = emuToSlideY(yEmu)
                }
                tag == "ext" && inPic -> {
                    val cxEmu = parser.getAttributeValue(null, "cx")?.toLongOrNull() ?: (slideWidthEmu.toLong())
                    val cyEmu = parser.getAttributeValue(null, "cy")?.toLongOrNull() ?: (slideHeightEmu.toLong())
                    currentCtx.w = emuToSlideX(cxEmu)
                    currentCtx.h = emuToSlideY(cyEmu)
                }
                // solid fill for shape background
                tag == "solidFill" && inSp -> { /* handled below via srgbClr */ }
                tag == "srgbClr" && inSp && !inTxBody -> {
                    val hex = parser.getAttributeValue(null, "val")
                    if (hex != null && hex.length == 6) {
                        try { currentCtx.fillColor = Color.parseColor("#$hex") } catch (_: Exception) {}
                    }
                }
                tag == "txBody" && inSp -> { inTxBody = true }
                tag == "a:p"    && inTxBody -> { inParagraph = true; paraRuns = mutableListOf() }
                tag == "a:r"    && inParagraph -> { inRun = true; runText = StringBuilder() }
                tag == "a:rPr"  && inRun -> {
                    inRPr = true
                    val sz = parser.getAttributeValue(null, "sz")?.toFloatOrNull()
                    currentFontSize = if (sz != null) (sz / 100f).coerceIn(8f, 72f) else 18f
                    currentBold   = parser.getAttributeValue(null, "b") == "1"
                    currentItalic = parser.getAttributeValue(null, "i") == "1"
                    currentColor  = Color.BLACK   // reset; srgbClr inside rPr will override
                }
                tag == "srgbClr" && inRPr -> {
                    val hex = parser.getAttributeValue(null, "val")
                    if (hex != null && hex.length == 6) {
                        try { currentColor = Color.parseColor("#$hex") } catch (_: Exception) {}
                    }
                }
                tag == "a:t" && inRun -> { /* text content handled in TEXT event */ }
                // Image reference
                tag == "blip" && inPic -> {
                    imageRId = parser.getAttributeValue(
                        "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed"
                    ) ?: ""
                }
            }
            XmlPullParser.TEXT -> {
                if (inRun && inTxBody) runText.append(parser.text)
            }
            XmlPullParser.END_TAG -> when {
                tag == "sp" && inSp -> {
                    val allRuns = shapeRuns.flatten()
                    if (allRuns.any { it.text.isNotBlank() }) {
                        shapes.add(SlideShape(
                            texts     = allRuns,
                            x         = currentCtx.x,
                            y         = currentCtx.y,
                            w         = currentCtx.w,
                            h         = currentCtx.h,
                            fillColor = currentCtx.fillColor,
                            isTitlePh = currentCtx.isTitlePh
                        ))
                    }
                    inSp = false; inNvSpPr = false; inTxBody = false
                }
                tag == "pic" && inPic -> {
                    if (imageRId.isNotEmpty()) {
                        // Resolve rId → media path via slide rels
                        val relsPath = slidePath.replace("slides/slide", "slides/_rels/slide") + ".rels"
                        val mediaPath = resolveRId(zip, relsPath, imageRId)
                        if (mediaPath != null) {
                            try {
                                val bytes = zip.getInputStream(zip.getEntry(mediaPath)).readBytes()
                                images.add(SlideImage(bytes, currentCtx.x, currentCtx.y, currentCtx.w, currentCtx.h))
                            } catch (_: Exception) {}
                        }
                    }
                    inPic = false
                }
                tag == "nvSpPr"  -> inNvSpPr = false
                tag == "txBody"  && inSp -> inTxBody = false
                tag == "a:p"     && inParagraph -> {
                    shapeRuns.add(paraRuns.toList())
                    inParagraph = false
                }
                tag == "a:r"     && inRun -> {
                    val t = runText.toString()
                    if (t.isNotEmpty()) {
                        paraRuns.add(TextRun(t, currentFontSize, currentBold, currentItalic, currentColor))
                    }
                    inRun = false; inRPr = false
                }
                tag == "a:rPr"   -> inRPr = false
            }
        }
        event = parser.next()
    }

    return SlideData(bgColor = bgColor, shapes = shapes, images = images)
}

private fun extractBgColor(xml: String): Int {
    // Simple regex-free scan for background solid fill
    val bgIdx = xml.indexOf("<p:bg")
    if (bgIdx < 0) return Color.WHITE
    val chunk = xml.substring(bgIdx, minOf(bgIdx + 2000, xml.length))
    val hexMatch = Regex("<a:srgbClr val=\"([0-9A-Fa-f]{6})\"").find(chunk)
        ?: return Color.WHITE
    return try { Color.parseColor("#${hexMatch.groupValues[1]}") } catch (_: Exception) { Color.WHITE }
}

private fun resolveRId(zip: ZipFile, relsPath: String, rId: String): String? {
    val entry = zip.getEntry(relsPath) ?: return null
    val xml   = zip.getInputStream(entry).reader().readText()
    val regex = Regex("Id=\"$rId\"[^/]*/?>|Id=\"$rId\".*?Target=\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL)
    // More robust: find Target attribute for this Id
    val targetRegex = Regex("""Id="$rId"[^>]*Target="([^"]+)"""")
    val m = targetRegex.find(xml) ?: return null
    val target = m.groupValues[1]
    // Resolve relative to ppt/slides/
    return if (target.startsWith("../")) "ppt/${target.removePrefix("../")}" else "ppt/slides/$target"
}

// ── Step 3: Draw slide onto Bitmap ───────────────────────────────────────────

private fun drawSlide(data: SlideData): Bitmap {
    val bmp    = Bitmap.createBitmap(SLIDE_W.toInt(), SLIDE_H.toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    // Background
    canvas.drawColor(data.bgColor)

    // Images first (behind text)
    for (img in data.images) {
        try {
            val decoded = BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size) ?: continue
            val dst = RectF(img.x, img.y, img.x + img.w, img.y + img.h)
            canvas.drawBitmap(decoded, null, dst, null)
            decoded.recycle()
        } catch (_: Exception) {}
    }

    // Shapes (text boxes)
    for (shape in data.shapes) {
        // Optional fill
        if (shape.fillColor != null) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = shape.fillColor; style = Paint.Style.FILL }
            canvas.drawRect(shape.x, shape.y, shape.x + shape.w, shape.y + shape.h, paint)
        }

        if (shape.texts.isEmpty()) continue

        // Determine a good base font size
        val isTitleLike = shape.isTitlePh || shape.texts.firstOrNull()?.fontSize?.let { it >= 24f } == true
        val baseFontSize = when {
            isTitleLike -> 32f
            else        -> 18f
        }

        // Use StaticLayout for multi-line text wrapping
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = computeTextColor(shape.texts.firstOrNull()?.color ?: Color.BLACK, data.bgColor)
            textSize = (shape.texts.firstOrNull()?.fontSize ?: baseFontSize)
                .coerceIn(10f, 60f) * (SLIDE_W / 320f)   // scale for canvas size
            isFakeBoldText = shape.texts.firstOrNull()?.bold ?: isTitleLike
            isAntiAlias    = true
        }

        val maxW    = shape.w.coerceAtLeast(1f).toInt()
        val fullText = shape.texts.joinToString(" ") { it.text }

        val layout  = android.text.StaticLayout.Builder
            .obtain(fullText, 0, fullText.length, textPaint, maxW)
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2f, 1.1f)
            .setIncludePad(false)
            .build()

        // Clip to shape bounds and draw
        canvas.save()
        canvas.clipRect(shape.x, shape.y, shape.x + shape.w, shape.y + shape.h)
        canvas.translate(shape.x + 8f, shape.y + 8f)
        layout.draw(canvas)
        canvas.restore()
    }

    return bmp
}

/** Pick a foreground colour that's readable against the background */
private fun computeTextColor(requested: Int, bg: Int): Int {
    if (requested != Color.BLACK && requested != Color.WHITE) return requested
    val lum = 0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)
    return if (lum > 128) Color.BLACK else Color.WHITE
}

// ── Step 4: Bitmaps → PDF via pdfbox-android ─────────────────────────────────

private fun bitmapsToPdf(context: Context, bitmaps: List<Bitmap>, out: File) {
    val doc = PDDocument()
    for (bmp in bitmaps) {
        val page   = PDPage(PDRectangle(SLIDE_W, SLIDE_H))
        doc.addPage(page)
        val img    = JPEGFactory.createFromImage(doc, bmp, 0.88f)
        val stream = PDPageContentStream(doc, page)
        stream.drawImage(img, 0f, 0f, SLIDE_W, SLIDE_H)
        stream.close()
    }
    doc.save(out)
    doc.close()
}

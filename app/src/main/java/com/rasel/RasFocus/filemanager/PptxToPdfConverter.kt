package com.rasel.RasFocus.filemanager

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
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
import java.util.zip.ZipFile
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────
// PPTX → PDF Converter  (v3 — image + text fidelity fix)
//
// Strategy:
//   1. Unzip .pptx (it's a ZIP)
//   2. Parse each ppt/slides/slideN.xml with a namespace-correct pull parser:
//      - shapes (text boxes) with exact EMU → px conversion
//      - embedded images with rId resolution
//      - background colour from solid fill or theme
//   3. Draw each slide on an Android Canvas (1280 × 720 px, 16:9)
//      Images drawn first (behind), then text shapes on top
//   4. Encode each Bitmap as JPEG → embed in PDF via pdfbox-android
//   5. Write temp PDF to cache dir → return to PptxViewerScreen
// ─────────────────────────────────────────────────────────────────────────────

private const val SLIDE_W = 1280f
private const val SLIDE_H = 720f

// Standard EMU dimensions for a 10" × 5.625" widescreen slide
private const val DEFAULT_EMU_W = 9144000f
private const val DEFAULT_EMU_H = 5143500f

// ── Data classes ──────────────────────────────────────────────────────────────

data class SlideData(
    val bgColor : Int              = Color.WHITE,
    val shapes  : List<SlideShape> = emptyList(),
    val images  : List<SlideImage> = emptyList()
)

data class SlideShape(
    val paragraphs : List<List<TextRun>>,   // outer = paragraphs, inner = runs
    val x: Float, val y: Float,
    val w: Float, val h: Float,
    val fillColor  : Int?     = null,
    val isTitlePh  : Boolean  = false
)

data class TextRun(
    val text    : String,
    val fontSize: Float   = 18f,
    val bold    : Boolean = false,
    val italic  : Boolean = false,
    val color   : Int     = Color.BLACK
)

data class SlideImage(
    val bytes : ByteArray,
    val x: Float, val y: Float,
    val w: Float, val h: Float
)

// ── Public entry point ────────────────────────────────────────────────────────

suspend fun convertPptxToPdf(context: Context, pptxPath: String): File? =
    withContext(Dispatchers.IO) {
        try {
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

// ── Step 1: Parse all slides ──────────────────────────────────────────────────

private fun parsePptx(zip: ZipFile): List<SlideData> {
    val regex = Regex("ppt/slides/slide(\\d+)\\.xml")
    val entries = zip.entries().asSequence()
        .mapNotNull { e ->
            val m = regex.matchEntire(e.name)
            if (m != null) m.groupValues[1].toInt() to e.name else null
        }
        .sortedBy { it.first }
        .map { it.second }
        .toList()

    return entries.map { parseSlide(zip, it) }
}

// ── Step 2: Parse one slide XML ───────────────────────────────────────────────

private fun parseSlide(zip: ZipFile, slidePath: String): SlideData {

    val xml = zip.getInputStream(zip.getEntry(slidePath)).reader().readText()

    // ── Slide size ────────────────────────────────────────────────────────────
    var emuW = DEFAULT_EMU_W
    var emuH = DEFAULT_EMU_H
    Regex("""<p:sldSz[^>]+cx="(\d+)"[^>]+cy="(\d+)"""").find(xml)?.let {
        emuW = it.groupValues[1].toFloat()
        emuH = it.groupValues[2].toFloat()
    }

    fun ex(emu: Long) = (emu / emuW) * SLIDE_W
    fun ey(emu: Long) = (emu / emuH) * SLIDE_H

    // ── Background colour ─────────────────────────────────────────────────────
    val bgColor = extractBgColor(xml)

    // ── Pull-parser setup — namespace-aware ───────────────────────────────────
    val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
    val parser  = factory.newPullParser()
    parser.setInput(xml.reader())

    val shapes = mutableListOf<SlideShape>()
    val images = mutableListOf<SlideImage>()

    data class ShapeCtx(
        var x: Float = 0f, var y: Float = 0f,
        var w: Float = SLIDE_W, var h: Float = SLIDE_H * 0.5f,
        var fillColor: Int? = null,
        var isTitlePh: Boolean = false
    )

    var inSpTree    = false
    var inSp        = false
    var inPic       = false
    var inNvSpPr    = false
    var inTxBody    = false
    var inParagraph = false
    var inRun       = false
    var inRPr       = false
    var inSolidFill = false
    var solidFillTarget = ""

    var ctx             = ShapeCtx()
    var curFontSize     = 18f
    var curBold         = false
    var curItalic       = false
    var curColor        = Color.BLACK
    var runText         = StringBuilder()
    var paraRuns        = mutableListOf<TextRun>()
    var shapeParagraphs = mutableListOf<List<TextRun>>()
    var imageRId        = ""

    val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    val NS_P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        val localName = parser.name ?: ""
        val ns        = parser.namespace ?: ""

        when (event) {
            XmlPullParser.START_TAG -> {

                if (localName == "spTree" && ns == NS_P) { inSpTree = true }

                if (localName == "sp" && ns == NS_P && inSpTree) {
                    inSp = true; ctx = ShapeCtx(); shapeParagraphs = mutableListOf()
                }

                if (localName == "pic" && ns == NS_P && inSpTree) {
                    inPic = true; ctx = ShapeCtx(); imageRId = ""
                }

                if (localName == "nvSpPr" && inSp) inNvSpPr = true

                if (localName == "ph" && inNvSpPr) {
                    val typ = parser.getAttributeValue(null, "type") ?: "body"
                    ctx.isTitlePh = typ in listOf("title", "ctrTitle")
                }

                if (localName == "off" && (inSp || inPic)) {
                    val xEmu = parser.getAttributeValue(null, "x")?.toLongOrNull() ?: 0L
                    val yEmu = parser.getAttributeValue(null, "y")?.toLongOrNull() ?: 0L
                    ctx.x = ex(xEmu); ctx.y = ey(yEmu)
                }
                if (localName == "ext" && (inSp || inPic)) {
                    val cxEmu = parser.getAttributeValue(null, "cx")?.toLongOrNull() ?: emuW.toLong()
                    val cyEmu = parser.getAttributeValue(null, "cy")?.toLongOrNull() ?: (emuH / 2).toLong()
                    ctx.w = ex(cxEmu); ctx.h = ey(cyEmu)
                }

                if (localName == "solidFill" && ns == NS_A && inSp && !inTxBody) {
                    inSolidFill = true; solidFillTarget = "shape"
                }

                if (localName == "srgbClr" && ns == NS_A) {
                    val hex = parser.getAttributeValue(null, "val")
                    if (hex != null && hex.length == 6) {
                        try {
                            val c = Color.parseColor("#$hex")
                            when {
                                solidFillTarget == "shape" && inSolidFill && !inRPr -> ctx.fillColor = c
                                inRPr -> curColor = c
                            }
                        } catch (_: Exception) {}
                    }
                }

                if (localName == "txBody" && ns == NS_P && inSp) {
                    inTxBody = true; solidFillTarget = ""
                }

                if (localName == "p" && ns == NS_A && inTxBody) {
                    inParagraph = true; paraRuns = mutableListOf()
                }

                if (localName == "r" && ns == NS_A && inParagraph) {
                    inRun = true; runText = StringBuilder()
                    curFontSize = 18f; curBold = false; curItalic = false; curColor = Color.BLACK
                }

                if (localName == "rPr" && ns == NS_A && inRun) {
                    inRPr = true
                    val sz = parser.getAttributeValue(null, "sz")?.toFloatOrNull()
                    if (sz != null) curFontSize = (sz / 100f).coerceIn(6f, 96f)
                    curBold   = parser.getAttributeValue(null, "b") == "1"
                    curItalic = parser.getAttributeValue(null, "i") == "1"
                    curColor  = Color.BLACK
                }

                if (localName == "blip" && ns == NS_A && inPic) {
                    imageRId = parser.getAttributeValue(NS_R, "embed") ?: ""
                }
            }

            XmlPullParser.TEXT -> {
                if (inRun && inTxBody) runText.append(parser.text)
            }

            XmlPullParser.END_TAG -> {
                if (localName == "solidFill" && ns == NS_A) inSolidFill = false
                if (localName == "rPr" && ns == NS_A) inRPr = false

                if (localName == "r" && ns == NS_A && inRun) {
                    val t = runText.toString()
                    if (t.isNotBlank()) {
                        paraRuns.add(TextRun(t, curFontSize, curBold, curItalic, curColor))
                    }
                    inRun = false
                }

                if (localName == "p" && ns == NS_A && inParagraph) {
                    shapeParagraphs.add(paraRuns.toList())
                    inParagraph = false
                }

                if (localName == "txBody" && ns == NS_P) inTxBody = false
                if (localName == "nvSpPr") inNvSpPr = false

                if (localName == "sp" && ns == NS_P && inSp) {
                    val allRuns = shapeParagraphs.flatten()
                    if (allRuns.any { it.text.isNotBlank() }) {
                        shapes.add(
                            SlideShape(
                                paragraphs = shapeParagraphs.toList(),
                                x          = ctx.x, y = ctx.y,
                                w          = ctx.w.coerceAtLeast(40f),
                                h          = ctx.h.coerceAtLeast(20f),
                                fillColor  = ctx.fillColor,
                                isTitlePh  = ctx.isTitlePh
                            )
                        )
                    }
                    inSp = false; inNvSpPr = false; inTxBody = false
                }

                if (localName == "pic" && ns == NS_P && inPic) {
                    if (imageRId.isNotEmpty()) {
                        val relsPath = slidePath
                            .replace("ppt/slides/slide", "ppt/slides/_rels/slide") + ".rels"
                        val mediaPath = resolveRId(zip, relsPath, imageRId)
                        if (mediaPath != null) {
                            try {
                                val entry = zip.getEntry(mediaPath)
                                if (entry != null) {
                                    val bytes = zip.getInputStream(entry).readBytes()
                                    images.add(
                                        SlideImage(
                                            bytes,
                                            ctx.x, ctx.y,
                                            ctx.w.coerceAtLeast(1f),
                                            ctx.h.coerceAtLeast(1f)
                                        )
                                    )
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    inPic = false
                }

                if (localName == "spTree" && ns == NS_P) inSpTree = false
            }
        }
        event = parser.next()
    }

    return SlideData(bgColor = bgColor, shapes = shapes, images = images)
}

// ── Background colour extraction ──────────────────────────────────────────────

private fun extractBgColor(xml: String): Int {
    val bgIdx = xml.indexOf("<p:bg")
    if (bgIdx < 0) return Color.WHITE
    val chunk = xml.substring(bgIdx, minOf(bgIdx + 3000, xml.length))
    val hex   = Regex("""<a:srgbClr val="([0-9A-Fa-f]{6})"""").find(chunk)?.groupValues?.get(1)
        ?: return Color.WHITE
    return try { Color.parseColor("#$hex") } catch (_: Exception) { Color.WHITE }
}

// ── rId → media path resolver ─────────────────────────────────────────────────

private fun resolveRId(zip: ZipFile, relsPath: String, rId: String): String? {
    val entry = zip.getEntry(relsPath) ?: return null
    val xml   = zip.getInputStream(entry).reader().readText()
    val m = Regex("""Id="$rId"[^>]*Target="([^"]+)"""").find(xml) ?: return null
    val target = m.groupValues[1]
    return when {
        target.startsWith("../") -> "ppt/${target.removePrefix("../")}"
        target.startsWith("/")   -> target.trimStart('/')
        else                     -> "ppt/slides/$target"
    }
}

// ── Step 3: Draw slide onto Bitmap ────────────────────────────────────────────

private fun drawSlide(data: SlideData): Bitmap {
    val bmp    = Bitmap.createBitmap(SLIDE_W.toInt(), SLIDE_H.toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    canvas.drawColor(data.bgColor)

    for (img in data.images) {
        try {
            val decoded = BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size) ?: continue
            val dst     = RectF(img.x, img.y, img.x + img.w, img.y + img.h)
            canvas.drawBitmap(decoded, null, dst, null)
            decoded.recycle()
        } catch (_: Exception) {}
    }

    for (shape in data.shapes) {
        if (shape.fillColor != null) {
            val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = shape.fillColor; style = Paint.Style.FILL
            }
            canvas.drawRect(shape.x, shape.y, shape.x + shape.w, shape.y + shape.h, fp)
        }

        val paragraphs = shape.paragraphs
        if (paragraphs.isEmpty()) continue

        val scaleFactor = SLIDE_W / 960f
        val maxW = shape.w.coerceAtLeast(1f).toInt()
        var cursorY = shape.y + 6f

        canvas.save()
        canvas.clipRect(shape.x, shape.y, shape.x + shape.w, shape.y + shape.h)

        for (para in paragraphs) {
            if (para.isEmpty()) {
                cursorY += 14f * scaleFactor
                if (cursorY >= shape.y + shape.h) break
                continue
            }

            val dominant = para.maxByOrNull { it.text.length } ?: para.first()
            val isTitleLike = shape.isTitlePh || dominant.fontSize >= 24f

            val displayFontSize = (dominant.fontSize * scaleFactor)
                .coerceIn(if (isTitleLike) 18f else 10f, if (isTitleLike) 80f else 48f)

            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color          = pickReadableColor(dominant.color, data.bgColor, shape.fillColor)
                textSize       = displayFontSize
                isFakeBoldText = dominant.bold || isTitleLike
                isAntiAlias    = true
                typeface       = if (dominant.italic) Typeface.ITALIC_TYPEFACE else Typeface.DEFAULT
            }

            val paraText = para.joinToString("") { it.text }

            val layout = StaticLayout.Builder
                .obtain(paraText, 0, paraText.length, tp, maxW)
                .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
                .setIncludePad(false)
                .build()

            val drawY = cursorY
            if (drawY + layout.height > shape.y + shape.h + 4f) break

            canvas.translate(shape.x + 6f, drawY)
            layout.draw(canvas)
            canvas.translate(-(shape.x + 6f), -drawY)

            cursorY += layout.height + 4f * scaleFactor
        }

        canvas.restore()
    }

    return bmp
}

private fun pickReadableColor(requested: Int, bgColor: Int, fillColor: Int?): Int {
    if (requested != Color.BLACK && requested != Color.WHITE) return requested
    val bg  = fillColor ?: bgColor
    val lum = 0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)
    return if (lum > 140) Color.BLACK else Color.WHITE
}

// ── Step 4: Bitmaps → PDF ─────────────────────────────────────────────────────

private fun bitmapsToPdf(context: Context, bitmaps: List<Bitmap>, out: File) {
    val doc = PDDocument()
    for (bmp in bitmaps) {
        val page   = PDPage(PDRectangle(SLIDE_W, SLIDE_H))
        doc.addPage(page)
        val img    = JPEGFactory.createFromImage(doc, bmp, 0.92f)
        val stream = PDPageContentStream(doc, page)
        stream.drawImage(img, 0f, 0f, SLIDE_W, SLIDE_H)
        stream.close()
    }
    doc.save(out)
    doc.close()
}

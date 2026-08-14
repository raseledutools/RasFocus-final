package com.rasel.RasFocus.filemanager

import android.content.Context
import android.graphics.*
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.*
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
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
// DOCX → PDF  (TextView-based render — correct Bangla shaping for all fonts)
// Supports: Unicode Bangla, SutonnyMJ, Kalpurush, SiyamRupali, Latin
// ─────────────────────────────────────────────────────────────────────────────

private const val PAGE_W_PX = 1240   // ~A4 at 150dpi
private const val PAGE_H_PX = 1754
private const val MARGIN_PX = 90
private const val CONTENT_W = PAGE_W_PX - MARGIN_PX * 2

// ── Legacy font names ─────────────────────────────────────────────────────────

private val LEGACY_FONTS = setOf(
    "sutonnymj", "sutonny mj", "sutonny",
    "siyamrupali", "siyam rupali",
    "adarshalipi", "adarsha lipi",
    "boishakhi", "kalpurush", "mukti",
    "likhan", "bangla", "aparajita",
    "shonar bangla", "vrinda"
)

private fun isLegacy(font: String) =
    LEGACY_FONTS.any { font.lowercase().contains(it) }

// ── SutonnyMJ → Unicode map ───────────────────────────────────────────────────

private val SUTONNY_MAP: Map<Char, String> = mapOf(
    'A' to "আ", 'B' to "ব", 'C' to "ঈ", 'D' to "দ", 'E' to "ঐ",
    'F' to "ফ", 'G' to "গ", 'H' to "হ", 'I' to "ই", 'J' to "জ",
    'K' to "ক", 'L' to "ল", 'M' to "ম", 'N' to "ন", 'O' to "ও",
    'P' to "প", 'Q' to "ং", 'R' to "র", 'S' to "স", 'T' to "ত",
    'U' to "উ", 'V' to "ভ", 'W' to "ঊ", 'X' to "ক্ষ", 'Y' to "য",
    'Z' to "য়",
    'a' to "া", 'b' to "ব", 'c' to "ে", 'd' to "দ", 'e' to "ে",
    'f' to "ফ", 'g' to "গ", 'h' to "হ", 'i' to "ি", 'j' to "জ",
    'k' to "ক", 'l' to "ল", 'm' to "ম", 'n' to "ন", 'o' to "ো",
    'p' to "প", 'q' to "ক", 'r' to "র", 's' to "স", 't' to "ত",
    'u' to "ু", 'v' to "ভ", 'w' to "ূ", 'x' to "ক্ষ", 'y' to "য",
    'z' to "জ",
    '0' to "০", '1' to "১", '2' to "২", '3' to "৩", '4' to "৪",
    '5' to "৫", '6' to "৬", '7' to "৭", '8' to "৮", '9' to "৯",
    '@' to "ঁ", '^' to "ঃ", '|' to "।", '$' to "৳",
    '[' to "ড়", ']' to "ঢ়", '{' to "ড়", '}' to "ঢ়",
    ':' to "ঃ", '`' to "\u200C",
    '\u0080' to "ৎ", '\u0081' to "ঙ", '\u0082' to "ঞ", '\u0083' to "ণ",
    '\u0084' to "ষ", '\u0085' to "ঢ", '\u0086' to "ট", '\u0087' to "ঠ",
    '\u0088' to "ড", '\u0089' to "থ", '\u008A' to "ছ", '\u008B' to "চ",
    '\u008C' to "ঘ", '\u008D' to "ঝ",
    '\u00A4' to "্", '\u00A6' to "ঁ", '\u00A7' to "ঃ",
    '\u00AA' to "া", '\u00AB' to "ি", '\u00AC' to "ী",
    '\u00AD' to "ু", '\u00AE' to "ূ", '\u00AF' to "ৃ",
    '\u00B0' to "ে", '\u00B1' to "ৈ", '\u00B4' to "ো", '\u00B5' to "ৌ",
    '\u00B6' to "্র", '\u00B9' to "র্", '\u00BA' to "র্",
    '\u00C0' to "ক্ক", '\u00C1' to "ক্ট", '\u00C2' to "ক্ত",
    '\u00C3' to "ক্ন", '\u00C4' to "ক্ব", '\u00C5' to "ক্ম",
    '\u00C6' to "ক্র", '\u00C7' to "ক্ল", '\u00C8' to "ক্ষ",
    '\u00C9' to "ক্স", '\u00CA' to "গ্ন", '\u00CB' to "গ্ব",
    '\u00CC' to "গ্ম", '\u00CD' to "গ্র", '\u00CE' to "গ্ল",
    '\u00CF' to "ঘ্ন", '\u00D0' to "ঘ্র", '\u00D1' to "ঙ্ক",
    '\u00D2' to "ঙ্গ", '\u00D3' to "চ্চ", '\u00D4' to "চ্ছ",
    '\u00D5' to "চ্ন", '\u00D6' to "জ্জ", '\u00D7' to "জ্ঞ",
    '\u00D8' to "জ্ব", '\u00D9' to "জ্র", '\u00DA' to "ট্ট",
    '\u00DB' to "ড্ড", '\u00DC' to "ণ্ট", '\u00DD' to "ণ্ড",
    '\u00DE' to "ণ্ণ", '\u00DF' to "ত্ত", '\u00E0' to "ত্থ",
    '\u00E1' to "ত্ন", '\u00E2' to "ত্ব", '\u00E3' to "ত্ম",
    '\u00E4' to "ত্র", '\u00E5' to "থ্র", '\u00E6' to "দ্দ",
    '\u00E7' to "দ্ধ", '\u00E8' to "দ্ব", '\u00E9' to "দ্ভ",
    '\u00EA' to "দ্ম", '\u00EB' to "দ্র", '\u00EC' to "ধ্র",
    '\u00ED' to "ন্ট", '\u00EE' to "ন্ড", '\u00EF' to "ন্ত",
    '\u00F0' to "ন্থ", '\u00F1' to "ন্দ", '\u00F2' to "ন্ধ",
    '\u00F3' to "ন্ন", '\u00F4' to "ন্ব", '\u00F5' to "ন্ম",
    '\u00F6' to "ন্র", '\u00F7' to "ন্স", '\u00F8' to "প্ত",
    '\u00F9' to "প্ন", '\u00FA' to "প্ব", '\u00FB' to "প্ম",
    '\u00FC' to "প্র", '\u00FD' to "প্ল", '\u00FE' to "প্স",
)

private fun legacyToUnicode(text: String): String = buildString {
    for (ch in text) append(SUTONNY_MAP[ch] ?: ch.toString())
}

// ── Data model ────────────────────────────────────────────────────────────────

private data class DocParagraph(
    val runs        : List<DocRun>,
    val isHeading   : Boolean = false,
    val headingLevel: Int     = 0,
    val indent      : Int     = 0,
    val isBullet    : Boolean = false,
    val alignment   : Int     = android.view.Gravity.START,
    val spaceAfter  : Int     = 0,
    val isLegacyFont: Boolean = false,
)

private data class DocRun(
    val text     : String,
    val bold     : Boolean = false,
    val italic   : Boolean = false,
    val underline: Boolean = false,
    val fontSize : Float   = 16f,   // sp
    val color    : Int     = Color.BLACK,
)

// ── Public API ────────────────────────────────────────────────────────────────

suspend fun convertDocxToPdf(context: Context, docxPath: String): File? =
    withContext(Dispatchers.IO) {
        try {
            try { PDFBoxResourceLoader.init(context.applicationContext) } catch (_: Exception) {}
            val file = File(docxPath)
            if (!file.exists() || file.extension.lowercase() == "doc") return@withContext null
            val paragraphs = parseDocx(file)
            if (paragraphs.isEmpty()) return@withContext null
            val bitmaps = withContext(Dispatchers.Main) { renderToPages(context, paragraphs) }
            val out = File(context.cacheDir, "docx_${System.currentTimeMillis()}.pdf")
            bitmapsToPdf(bitmaps, out)
            out
        } catch (e: Exception) { e.printStackTrace(); null }
    }

// ── Step 1: Parse DOCX XML ────────────────────────────────────────────────────

private fun parseDocx(file: File): List<DocParagraph> {
    val result = mutableListOf<DocParagraph>()
    ZipInputStream(file.inputStream().buffered()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name == "word/document.xml") { result.addAll(parseXml(zip)); break }
            entry = zip.nextEntry
        }
    }
    return result
}

private fun parseXml(stream: InputStream): List<DocParagraph> {
    val result  = mutableListOf<DocParagraph>()
    val parser  = XmlPullParserFactory.newInstance()
        .apply { isNamespaceAware = false }.newPullParser()
    parser.setInput(stream, "UTF-8")

    var inBody = false; var inPara = false; var inRun = false
    var inRPr  = false; var inPPr  = false

    // Para-level state
    var isHeading = false; var headingLevel = 0; var indent = 0
    var isBullet  = false; var alignment = android.view.Gravity.START
    var spaceAfter = 0; var paraFontSize = 16f; var paraBold = false
    var paraFontName = ""

    // Run-level state
    var runBold = false; var runItalic = false; var runUnderline = false
    var runFontSize = 16f; var runColor = Color.BLACK; var runFontName = ""

    val runs    = mutableListOf<DocRun>()
    val runText = StringBuilder()

    fun flushRun() {
        val raw = runText.toString(); runText.clear()
        if (raw.isEmpty()) return
        val fontName = runFontName.ifEmpty { paraFontName }
        val text = if (isLegacy(fontName)) legacyToUnicode(raw) else raw
        runs.add(DocRun(text, runBold || paraBold, runItalic, runUnderline, runFontSize, runColor))
    }

    fun flushPara() {
        val fontName = paraFontName
        result.add(
            if (runs.isNotEmpty())
                DocParagraph(runs.toList(), isHeading, headingLevel, indent,
                    isBullet, alignment, spaceAfter, isLegacy(fontName))
            else DocParagraph(listOf(DocRun("")))
        )
        runs.clear()
        isHeading = false; headingLevel = 0; indent = 0; isBullet = false
        alignment = android.view.Gravity.START; spaceAfter = 0
        paraFontSize = 16f; paraBold = false; paraFontName = ""
    }

    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        val tag = parser.name?.substringAfterLast(':') ?: ""
        when (event) {
            XmlPullParser.START_TAG -> when (tag) {
                "body"   -> inBody = true
                "p"      -> if (inBody) inPara = true
                "r"      -> if (inPara) {
                    inRun = true
                    runBold = paraBold; runItalic = false; runUnderline = false
                    runFontSize = paraFontSize; runColor = Color.BLACK; runFontName = ""
                }
                "pPr"    -> if (inPara) inPPr = true
                "rPr"    -> if (inRun || inPPr) inRPr = true
                "rFonts" -> {
                    val fn = parser.getAttributeValue(null, "w:ascii")
                        ?: parser.getAttributeValue(null, "ascii")
                        ?: parser.getAttributeValue(null, "w:hAnsi") ?: ""
                    if (inRPr && inRun) runFontName = fn
                    else if (inRPr) paraFontName = fn
                }
                "pStyle" -> if (inPPr) {
                    val v = (parser.getAttributeValue(null, "w:val")
                        ?: parser.getAttributeValue(null, "val") ?: "").lowercase()
                    when {
                        v.startsWith("heading") -> {
                            isHeading = true
                            headingLevel = v.filter { it.isDigit() }.firstOrNull()?.digitToInt() ?: 1
                            paraFontSize = when (headingLevel) { 1->28f; 2->24f; 3->20f; else->18f }
                            paraBold = headingLevel <= 3
                        }
                        v == "title" -> { isHeading=true; headingLevel=0; paraFontSize=32f; paraBold=true }
                    }
                }
                "jc"     -> if (inPPr) {
                    val v = parser.getAttributeValue(null, "w:val")
                        ?: parser.getAttributeValue(null, "val") ?: ""
                    alignment = when (v.lowercase()) {
                        "center" -> android.view.Gravity.CENTER_HORIZONTAL
                        "right"  -> android.view.Gravity.END
                        else     -> android.view.Gravity.START
                    }
                }
                "ind"    -> if (inPPr) {
                    val l = (parser.getAttributeValue(null, "w:left")
                        ?: parser.getAttributeValue(null, "left"))?.toIntOrNull() ?: 0
                    indent = (l / 720).coerceIn(0, 6)
                }
                "spacing"-> if (inPPr) {
                    spaceAfter = (parser.getAttributeValue(null, "w:after")
                        ?: parser.getAttributeValue(null, "after"))?.toIntOrNull() ?: 0
                }
                "numPr"  -> if (inPPr) isBullet = true
                "b"      -> if (inRPr) runBold = true
                "i"      -> if (inRPr) runItalic = true
                "u"      -> if (inRPr) runUnderline = true
                "sz"     -> if (inRPr) {
                    runFontSize = ((parser.getAttributeValue(null, "w:val")
                        ?: parser.getAttributeValue(null, "val"))?.toFloatOrNull() ?: 32f) / 2f
                }
                "color"  -> if (inRPr) {
                    val h = parser.getAttributeValue(null, "w:val")
                        ?: parser.getAttributeValue(null, "val") ?: "000000"
                    runColor = if (h == "auto") Color.BLACK
                               else try { Color.parseColor("#$h") } catch (_: Exception) { Color.BLACK }
                }
                "br"     -> if (inRun) runText.append('\n')
                "tab"    -> if (inRun) runText.append("    ")
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
        event = parser.next()
    }
    return result
}

// ── Step 2: TextView → Bitmap pages (Main thread) ────────────────────────────
// TextView uses Android's full Harfbuzz shaping engine — Bangla conjuncts,
// matras, and all complex scripts render correctly on every font.

private fun renderToPages(context: Context, paragraphs: List<DocParagraph>): List<Bitmap> {
    val pages     = mutableListOf<Bitmap>()
    var pageCanvas: Canvas
    var pageBmp: Bitmap
    var cursorY = MARGIN_PX.toFloat()
    val density = context.resources.displayMetrics.density

    fun newPage(): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(PAGE_W_PX, PAGE_H_PX, Bitmap.Config.ARGB_8888)
        val cvs = Canvas(bmp).also { it.drawColor(Color.WHITE) }
        return bmp to cvs
    }

    var (bmp, cvs) = newPage()

    for (para in paragraphs) {
        val fullText = para.runs.joinToString("") { it.text }
        if (fullText.isBlank()) { cursorY += density * 8f; continue }

        // Build SpannableStringBuilder with per-run formatting
        val ssb = SpannableStringBuilder()
        var pos = 0
        for (run in para.runs) {
            if (run.text.isEmpty()) continue
            ssb.append(run.text)
            val end = pos + run.text.length
            if (run.bold)
                ssb.setSpan(StyleSpan(Typeface.BOLD), pos, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (run.italic)
                ssb.setSpan(StyleSpan(Typeface.ITALIC), pos, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (run.underline)
                ssb.setSpan(UnderlineSpan(), pos, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.setSpan(AbsoluteSizeSpan(run.fontSize.toInt(), true), pos, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.setSpan(ForegroundColorSpan(run.color), pos, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            pos = end
        }

        // Build TextView with exact content width
        val tv = TextView(context).apply {
            text      = ssb
            gravity   = para.alignment
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
            if (para.isBullet) {
                val bullet = SpannableStringBuilder("• ").also { it.append(ssb) }
                text = bullet
            }
        }

        val indentPx = para.indent * (density * 20f).toInt()
        val availW   = CONTENT_W - indentPx
        val wSpec    = View.MeasureSpec.makeMeasureSpec(availW, View.MeasureSpec.EXACTLY)
        val hSpec    = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        tv.measure(wSpec, hSpec)
        tv.layout(0, 0, availW, tv.measuredHeight)

        val blockH = tv.measuredHeight.toFloat()
        val spacer = (spaceAfter(para.spaceAfter, density)).coerceIn(4f, 32f)

        // New page if needed
        if (cursorY + blockH > PAGE_H_PX - MARGIN_PX) {
            pages.add(bmp)
            val p = newPage(); bmp = p.first; cvs = p.second
            cursorY = MARGIN_PX.toFloat()
        }

        // Draw heading rule above
        if (para.isHeading) {
            cursorY += density * 6f
            if (para.headingLevel <= 2) {
                val rulePaint = Paint().apply { color = Color.parseColor("#DDDDDD"); strokeWidth = 1.5f }
                cvs.drawLine(MARGIN_PX.toFloat(), cursorY - 4f, (PAGE_W_PX - MARGIN_PX).toFloat(), cursorY - 4f, rulePaint)
            }
        }

        // Render TextView to canvas
        cvs.save()
        cvs.translate((MARGIN_PX + indentPx).toFloat(), cursorY)
        tv.draw(cvs)
        cvs.restore()

        cursorY += blockH + spacer
    }

    if (cursorY > MARGIN_PX) pages.add(bmp)
    return pages
}

private fun spaceAfter(twips: Int, density: Float): Float =
    if (twips <= 0) density * 6f else (twips / 720f) * density * 12f

// ── Step 3: Bitmaps → PDF ─────────────────────────────────────────────────────

private fun bitmapsToPdf(pages: List<Bitmap>, out: File) {
    val doc = PDDocument()
    try {
        for (bmp in pages) {
            val img  = JPEGFactory.createFromImage(doc, bmp, 0.93f)
            val page = PDPage(PDRectangle(PAGE_W_PX.toFloat(), PAGE_H_PX.toFloat()))
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.drawImage(img, 0f, 0f, PAGE_W_PX.toFloat(), PAGE_H_PX.toFloat())
            }
            bmp.recycle()
        }
        doc.save(out)
    } finally { doc.close() }
}

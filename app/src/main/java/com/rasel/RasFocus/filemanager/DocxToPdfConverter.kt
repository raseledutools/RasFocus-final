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

// ── SutonnyMJ → Unicode mapping (verified from real DOCX analysis) ─────────────
// Key insight: SutonnyMJ stores ি-kar BEFORE its host consonant (visual encoding).
// legacyToUnicode() handles this with a post-processing reorder step.
//
// Verified test cases:
//   ZvwiLt  → তারিখঃ   ✓  (formal heading "তারিখঃ")
//   eivei   → বরাবর    ✓
//   mwPe    → সচিব     ✓
//   wLªóvã  → খ্রিস্টাব্দ ✓

private val SUTONNY_MAP: Map<Char, String> = mapOf(
    // ── Independent vowels ──
    'A' to "অ",
    'B' to "ই",
    'C' to "ঈ",
    'D' to "উ",
    'E' to "ঊ",
    'F' to "ঋ",
    'G' to "এ",
    'H' to "ঐ",
    'I' to "ও",
    'J' to "ঔ",

    // ── Consonants ──
    'K' to "ক",
    'L' to "খ",
    'M' to "গ",
    'N' to "ঘ",
    'O' to "ঙ",
    'P' to "চ",
    'Q' to "ছ",
    'R' to "জ",
    'S' to "ঝ",
    'T' to "ঞ",
    'U' to "ট",
    'V' to "ঠ",
    'W' to "ড",
    'X' to "ঢ",
    'Y' to "ণ",
    'Z' to "ত",
    '_' to "থ",
    'a' to "দ",
    'b' to "ধ",
    'c' to "ন",
    'd' to "প",
    'e' to "ব",
    'f' to "ভ",
    'g' to "ম",
    'h' to "য",
    'i' to "র",
    'j' to "ল",
    'k' to "শ",
    'l' to "ষ",
    'm' to "স",
    'n' to "হ",
    'o' to "ড়",
    'p' to "ঢ়",
    'q' to "য়",
    'r' to "ৎ",
    's' to "ং",
    't' to "ঃ",
    'u' to "ঁ",

    // ── Vowel signs (matras) ──
    // NOTE: 'w' = ি is stored BEFORE its host consonant in SutonnyMJ.
    //       legacyToUnicode() reorders it to AFTER the consonant (Unicode standard).
    'v' to "া",
    'w' to "ি",   // ← stored before consonant; reordered in legacyToUnicode()
    'x' to "ী",
    'y' to "ু",
    'z' to "ূ",
    '`' to "ে",
    '~' to "ৈ",
    '^' to "ো",

    // ── Hasanta / Virama ──
    '&' to "্",

    // ── Punctuation ──
    '|'  to "।",
    '\u00A4' to "।",  // ¤ alternate দাড়ি

    // ── Pre-composed conjuncts (Latin-1 supplement range) ──
    // These are single encoded chars representing common Bangla clusters.
    // Mapped from actual document analysis (যোগদান পত্র DOCX).
    '\u00A7' to "ক্ষ",   // §
    '\u00A8' to "জ্ঞ",   // ¨
    '\u00A9' to "্ক",    // © (virama+ক)
    '\u00AA' to "্র",    // ª (virama+র) ← confirmed: খ্রিস্টাব্দ
    '\u00AF' to "্ম",    // ¯
    '\u00B3' to "ত্ত",   // ³
    '\u00B5' to "ন্ত",   // µ
    '\u00B6' to "ন্থ",   // ¶
    '\u00BD' to "ক্ত",   // ½
    '\u00BF' to "স্ত",   // ¿
    '\u00C1' to "ন্ট",   // Á
    '\u00CE' to "ষ্ট",   // Î ← confirmed: খ্রিস্টাব্দ contains ষ্ট
    '\u00D6' to "ষ্ঠ",   // Ö
    '\u00E3' to "ব্দ",   // ã (া comes separately from 'v')
    '\u00F3' to "স্ট",   // ó (ি comes separately from 'w' reorder)
    '\u00F7' to "ন্ড",   // ÷
    '\u00FA' to "ন্ব",   // ú
    '\u00FD' to "হ্ম",   // ý
    '\u0160' to "ক্স",   // Š
    '\u0161' to "ঙ্গ",   // š
    '\u2019' to "\u2019", // ' (keep as-is)
    '\u2020' to "ত্র",   // †
    '\u2021' to "স্থ",   // ‡
    '\u2039' to "ক্র",   // ‹

    // ── Bengali digits (if DOCX uses ASCII digits, keep as-is; these handle edge cases) ──
    '0' to "০", '1' to "১", '2' to "২", '3' to "৩", '4' to "৪",
    '5' to "৫", '6' to "৬", '7' to "৭", '8' to "৮", '9' to "৯",
)

// Bangla consonant codepoints (single-char only, for ি reorder logic).
// Note: ড়/ঢ়/য় are multi-codepoint and handled separately in the reorder loop.
private val BANGLA_CONSONANTS: Set<Char> = setOf(
    'ক','খ','গ','ঘ','ঙ','চ','ছ','জ','ঝ','ঞ','ট','ঠ','ড','ঢ','ণ',
    'ত','থ','দ','ধ','ন','প','ফ','ব','ভ','ম','য','র','ল','শ','ষ',
    'স','হ','\u09CE'  // ৎ
)

private fun legacyToUnicode(text: String): String {
    // Step 1: Two-char combo "Av" → আ
    val mapped = buildString {
        var i = 0
        while (i < text.length) {
            if (i + 1 < text.length && text[i] == 'A' && text[i + 1] == 'v') {
                append("আ"); i += 2
            } else {
                append(SUTONNY_MAP[text[i]] ?: text[i].toString()); i++
            }
        }
    }

    // Step 2: Fix ি position.
    // SutonnyMJ stores ি BEFORE its host consonant/cluster.
    // Unicode requires ি AFTER the consonant (or full conjunct connected by ্).
    // Rule: when we encounter ি, look ahead for one consonant (+ ্ + consonant)*
    // cluster, output the cluster first, then ি.
    val chars = mapped.toList()
    return buildString {
        var j = 0
        while (j < chars.size) {
            if (chars[j] == 'ি') {
                // Collect the following consonant cluster (virama-connected only)
                val cluster = StringBuilder()
                var k = j + 1
                if (k < chars.size && chars[k] in BANGLA_CONSONANTS) {
                    cluster.append(chars[k]); k++
                    // Extend only through virama connections: ্ + consonant
                    while (k + 1 < chars.size && chars[k] == '্' && chars[k + 1] in BANGLA_CONSONANTS) {
                        cluster.append(chars[k]).append(chars[k + 1]); k += 2
                    }
                }
                if (cluster.isNotEmpty()) {
                    append(cluster); append('ি'); j = k
                } else {
                    append('ি'); j++
                }
            } else {
                append(chars[j]); j++
            }
        }
    }
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

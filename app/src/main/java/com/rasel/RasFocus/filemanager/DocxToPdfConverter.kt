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
// DOCX → PDF Converter
// Supports: Unicode Bangla, SutonnyMJ (ANSI legacy), Kalpurush, Latin
// ─────────────────────────────────────────────────────────────────────────────

private const val PAGE_W    = 794f
private const val PAGE_H    = 1123f
private const val MARGIN    = 60f
private const val CONTENT_W = PAGE_W - MARGIN * 2

// ── Legacy font detection ─────────────────────────────────────────────────────

private val LEGACY_BANGLA_FONTS = setOf(
    "sutonnymj", "sutonny mj", "sutonny", "siyamrupali", "siyam rupali",
    "adarshalipi", "adarsha lipi", "boishakhi", "kalpurush",
    "mukti", "likhan", "bangla", "aparajita"
)

private fun isLegacyBanglaFont(fontName: String): Boolean {
    val lower = fontName.lowercase().trim()
    return LEGACY_BANGLA_FONTS.any { lower.contains(it) }
}

// ── SutonnyMJ → Unicode conversion table ─────────────────────────────────────
// SutonnyMJ stores Bangla as ANSI ASCII characters with custom glyph mapping.
// This table maps each SutonnyMJ character to its Unicode Bangla equivalent.

private val SUTONNY_TO_UNICODE: Map<Char, String> = mapOf(
    // Vowels (independent)
    'A' to "আ",   'B' to "ব",   'C' to "ঈ",   'D' to "দ",
    'E' to "ঐ",   'F' to "ফ",   'G' to "গ",   'H' to "হ",
    'I' to "ই",   'J' to "জ",   'K' to "ক",   'L' to "ল",
    'M' to "ম",   'N' to "ন",   'O' to "ও",   'P' to "প",
    'Q' to "ং",   'R' to "র",   'S' to "স",   'T' to "ত",
    'U' to "উ",   'V' to "ভ",   'W' to "ঊ",   'X' to "ক্ষ",
    'Y' to "য",   'Z' to "য়",

    'a' to "া",   'b' to "ব",   'c' to "ে",   'd' to "দ",
    'e' to "ে",   'f' to "ফ",   'g' to "গ",   'h' to "হ",
    'i' to "ি",   'j' to "জ",   'k' to "ক",   'l' to "ল",
    'm' to "ম",   'n' to "ন",   'o' to "ো",   'p' to "প",
    'q' to "ক",   'r' to "র",   's' to "স",   't' to "ত",
    'u' to "ু",   'v' to "ভ",   'w' to "ূ",   'x' to "ক্ষ",
    'y' to "য",   'z' to "জ",

    // Digits
    '0' to "০",   '1' to "১",   '2' to "২",   '3' to "৩",
    '4' to "৪",   '5' to "৫",   '6' to "৬",   '7' to "৭",
    '8' to "৮",   '9' to "৯",

    // Special characters
    '!' to "!",   '@' to "ঁ",   '#' to "#",   '$' to "৳",
    '%' to "%",   '^' to "ঃ",   '&' to "&",   '*' to "*",
    '(' to "(",   ')' to ")",   '-' to "-",   '_' to "_",
    '+' to "+",   '=' to "=",   '[' to "ড়",   ']' to "ঢ়",
    '{' to "ড়",   '}' to "ঢ়",   '|' to "।",   '\\' to "\\",
    ':' to "ঃ",   ';' to ";",   '"' to "\"",  '\'' to "\'",
    '<' to "<",   '>' to ">",   ',' to ",",   '.' to ".",
    '?' to "?",   '/' to "/",   '`' to "‌",  '~' to "~",

    // Common Bangla consonants via special chars
    '\u0080' to "ৎ",  '\u0081' to "ঙ",  '\u0082' to "ঞ",
    '\u0083' to "ণ",  '\u0084' to "ষ",  '\u0085' to "ঢ",
    '\u0086' to "ট",  '\u0087' to "ঠ",  '\u0088' to "ড",
    '\u0089' to "থ",  '\u008A' to "ছ",  '\u008B' to "চ",
    '\u008C' to "ঘ",  '\u008D' to "ঝ",  '\u008E' to "ঠ",
    '\u008F' to "ট",

    // Hasanta, Anusvar, etc.
    '\u00A4' to "্",  '\u00A6' to "ঁ",  '\u00A7' to "ঃ",
    '\u00A8' to "ঃ",  '\u00AA' to "া",  '\u00AB' to "ি",
    '\u00AC' to "ী",  '\u00AD' to "ু",  '\u00AE' to "ূ",
    '\u00AF' to "ৃ",  '\u00B0' to "ে",  '\u00B1' to "ৈ",
    '\u00B4' to "ো",  '\u00B5' to "ৌ",  '\u00B6' to "্র",
    '\u00B9' to "র্",  '\u00BA' to "র্",

    // Conjuncts (common)
    '\u00C0' to "ক্ক",  '\u00C1' to "ক্ট",  '\u00C2' to "ক্ত",
    '\u00C3' to "ক্ন",  '\u00C4' to "ক্ব",  '\u00C5' to "ক্ম",
    '\u00C6' to "ক্র",  '\u00C7' to "ক্ল",  '\u00C8' to "ক্ষ",
    '\u00C9' to "ক্স",  '\u00CA' to "গ্ন",  '\u00CB' to "গ্ব",
    '\u00CC' to "গ্ম",  '\u00CD' to "গ্র",  '\u00CE' to "গ্ল",
    '\u00CF' to "ঘ্ন",  '\u00D0' to "ঘ্র",  '\u00D1' to "ঙ্ক",
    '\u00D2' to "ঙ্গ",  '\u00D3' to "চ্চ",  '\u00D4' to "চ্ছ",
    '\u00D5' to "চ্ন",  '\u00D6' to "জ্জ",  '\u00D7' to "জ্ঞ",
    '\u00D8' to "জ্ব",  '\u00D9' to "জ্র",  '\u00DA' to "ট্ট",
    '\u00DB' to "ড্ড",  '\u00DC' to "ণ্ট",  '\u00DD' to "ণ্ড",
    '\u00DE' to "ণ্ণ",  '\u00DF' to "ত্ত",  '\u00E0' to "ত্থ",
    '\u00E1' to "ত্ন",  '\u00E2' to "ত্ব",  '\u00E3' to "ত্ম",
    '\u00E4' to "ত্র",  '\u00E5' to "থ্র",  '\u00E6' to "দ্দ",
    '\u00E7' to "দ্ধ",  '\u00E8' to "দ্ব",  '\u00E9' to "দ্ভ",
    '\u00EA' to "দ্ম",  '\u00EB' to "দ্র",  '\u00EC' to "ধ্র",
    '\u00ED' to "ন্ট",  '\u00EE' to "ন্ড",  '\u00EF' to "ন্ত",
    '\u00F0' to "ন্থ",  '\u00F1' to "ন্দ",  '\u00F2' to "ন্ধ",
    '\u00F3' to "ন্ন",  '\u00F4' to "ন্ব",  '\u00F5' to "ন্ম",
    '\u00F6' to "ন্র",  '\u00F7' to "ন্স",  '\u00F8' to "প্ত",
    '\u00F9' to "প্ন",  '\u00FA' to "প্ব",  '\u00FB' to "প্ম",
    '\u00FC' to "প্র",  '\u00FD' to "প্ল",  '\u00FE' to "প্স",
    '\u00FF' to "ব্জ",
)

// Kalpurush uses same mapping as SutonnyMJ with minor differences
private val KALPURUSH_OVERRIDES: Map<Char, String> = mapOf(
    'q' to "ও",
    'Q' to "ও",
)

fun convertLegacyToUnicode(text: String, fontName: String): String {
    val lower = fontName.lowercase()
    val isKalpurush = lower.contains("kalpurush") || lower.contains("kalpur")

    return buildString {
        for (ch in text) {
            val mapped = if (isKalpurush) {
                KALPURUSH_OVERRIDES[ch] ?: SUTONNY_TO_UNICODE[ch]
            } else {
                SUTONNY_TO_UNICODE[ch]
            }
            append(mapped ?: ch.toString())
        }
    }
}

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

private var cachedBanglaTypeface: Typeface? = null

private fun getBanglaTypeface(bold: Boolean, italic: Boolean): Typeface {
    val base = cachedBanglaTypeface ?: run {
        val candidates = listOf(
            "/system/fonts/NotoSansBengali-Regular.ttf",
            "/system/fonts/NotoSansBengali.ttf",
            "/system/fonts/NotoSerifBengali-Regular.ttf",
            "/system/fonts/Roboto-Regular.ttf",
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
                    result.addAll(parseDocumentXml(zip)); break
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

    var isHeading = false; var headingLevel = 0; var indent = 0
    var isBullet  = false; var align = Align.LEFT; var spaceAfter = 0

    var runBold = false; var runItalic = false; var runUnderline = false
    var runFontSize = 24f; var runColor = Color.BLACK
    var runFontName = ""  // ← track font name for legacy detection

    var paraFontSize = 24f; var paraBold = false; var paraFontName = ""

    val runs    = mutableListOf<DocRun>()
    val runText = StringBuilder()

    fun flushRun() {
        val raw = runText.toString()
        if (raw.isNotEmpty()) {
            // Convert legacy font text → Unicode
            val text = if (isLegacyBanglaFont(runFontName)) {
                convertLegacyToUnicode(raw, runFontName)
            } else raw
            runs.add(DocRun(text, runBold || paraBold, runItalic, runFontSize, runColor, runUnderline))
        }
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
        paraFontName = ""
    }

    var eventType = parser.eventType
    while (eventType != XmlPullParser.END_DOCUMENT) {
        val tag = parser.name?.substringAfterLast(':') ?: ""
        when (eventType) {
            XmlPullParser.START_TAG -> when (tag) {
                "body"   -> inBody = true
                "p"      -> if (inBody) inPara = true
                "r"      -> if (inPara) {
                    inRun = true
                    runBold = paraBold; runItalic = false; runUnderline = false
                    runFontSize = paraFontSize; runColor = Color.BLACK
                    runFontName = paraFontName  // inherit para font
                }
                "pPr"    -> if (inPara) inPPr = true
                "rPr"    -> if (inRun || inPPr) inRPr = true

                // ── Font name ─────────────────────────────────────────────────
                "rFonts" -> {
                    val fn = parser.getAttributeValue(null, "w:ascii")
                        ?: parser.getAttributeValue(null, "ascii")
                        ?: parser.getAttributeValue(null, "w:hAnsi")
                        ?: parser.getAttributeValue(null, "hAnsi") ?: ""
                    if (inRPr && inRun) runFontName = fn
                    else if (inRPr && inPPr) paraFontName = fn
                }

                "pStyle" -> if (inPPr) {
                    val v = (parser.getAttributeValue(null, "w:val")
                        ?: parser.getAttributeValue(null, "val") ?: "").lowercase()
                    when {
                        v.startsWith("heading") || v.startsWith("kop") -> {
                            isHeading = true
                            headingLevel = v.filter { it.isDigit() }.firstOrNull()?.digitToInt() ?: 1
                            paraFontSize = when (headingLevel) { 1->52f; 2->44f; 3->36f; 4->30f; else->26f }
                            paraBold = headingLevel <= 3
                        }
                        v == "title"    -> { isHeading = true; headingLevel = 0; paraFontSize = 64f; paraBold = true }
                        v == "subtitle" -> { isHeading = true; headingLevel = 1; paraFontSize = 48f }
                    }
                }
                "ind"    -> if (inPPr) {
                    val left = (parser.getAttributeValue(null, "w:left")
                        ?: parser.getAttributeValue(null, "left"))?.toIntOrNull() ?: 0
                    indent = (left / 720).coerceIn(0, 8)
                }
                "jc"     -> if (inPPr) {
                    align = when ((parser.getAttributeValue(null, "w:val")
                        ?: parser.getAttributeValue(null, "val") ?: "").lowercase()) {
                        "center" -> Align.CENTER; "right" -> Align.RIGHT
                        "both", "distribute" -> Align.JUSTIFY; else -> Align.LEFT
                    }
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
                    runFontSize = (parser.getAttributeValue(null, "w:val")
                        ?: parser.getAttributeValue(null, "val"))?.toFloatOrNull() ?: paraFontSize
                }
                "color"  -> if (inRPr) {
                    val hex = parser.getAttributeValue(null, "w:val")
                        ?: parser.getAttributeValue(null, "val") ?: "000000"
                    runColor = if (hex == "auto") Color.BLACK
                               else try { Color.parseColor("#$hex") } catch (_: Exception) { Color.BLACK }
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
            pages.add(bitmap); bitmap = newPage(); canvas = Canvas(bitmap); cursorY = MARGIN
        }
    }

    for (para in paragraphs) {
        val runs = para.runs
        if (runs.isEmpty() || runs.all { it.text.isEmpty() }) { cursorY += 14f; continue }

        val startX   = MARGIN + para.indent * 24f
        val availW   = (CONTENT_W - para.indent * 24f).toInt()
        val fullText = runs.joinToString("") { it.text }
        val base     = runs.firstOrNull { it.text.isNotEmpty() } ?: runs.first()

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize    = (base.fontSize / 2f).coerceIn(10f, 48f)
            typeface    = getBanglaTypeface(base.bold, base.italic)
            color       = base.color
            isAntiAlias = true
        }

        val layout = StaticLayout.Builder
            .obtain(fullText, 0, fullText.length, paint, availW)
            .setAlignment(when (para.alignment) {
                Align.CENTER -> android.text.Layout.Alignment.ALIGN_CENTER
                Align.RIGHT  -> android.text.Layout.Alignment.ALIGN_OPPOSITE
                else         -> android.text.Layout.Alignment.ALIGN_NORMAL
            })
            .setLineSpacing(2f, 1.4f)
            .setIncludePad(false)
            .build()

        val blockH = layout.height.toFloat()
        newPageIfNeeded(blockH + 12f)
        if (para.isHeading) cursorY += 10f

        if (para.isBullet) {
            val bp = TextPaint(paint).apply { typeface = getBanglaTypeface(false, false) }
            canvas.drawText("•  ", startX - 24f, cursorY + paint.textSize, bp)
        }

        canvas.save(); canvas.translate(startX, cursorY); layout.draw(canvas); canvas.restore()

        if (runs.any { it.underline }) {
            val ul = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = base.color; strokeWidth = 1f }
            for (i in 0 until layout.lineCount) {
                canvas.drawLine(
                    startX + layout.getLineLeft(i), cursorY + layout.getLineBaseline(i) + 3f,
                    startX + layout.getLineRight(i), cursorY + layout.getLineBaseline(i) + 3f, ul
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

// ── Step 3: Bitmaps → PDF ────────────────────────────────────────────────────

private fun pagesToPdf(pages: List<Bitmap>, out: File) {
    val doc = PDDocument()
    try {
        for (bmp in pages) {
            val img  = JPEGFactory.createFromImage(doc, bmp, 0.92f)
            val page = PDPage(PDRectangle(PAGE_W, PAGE_H))
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs -> cs.drawImage(img, 0f, 0f, PAGE_W, PAGE_H) }
            bmp.recycle()
        }
        doc.save(out)
    } finally { doc.close() }
}

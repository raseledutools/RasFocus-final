package com.rasel.RasFocus.combo.selfcontrol.study_tools

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.zip.ZipInputStream

// ─────────────────────────────────────────────────────────────────────────────
// XlsxViewerActivity
// Opens .xlsx by parsing the shared strings table + first sheet XML.
// Renders as a scrollable table — no external deps.
// ─────────────────────────────────────────────────────────────────────────────

class XlsxViewerActivity : ComponentActivity() {

    private val uriState      = mutableStateOf<Uri?>(null)
    private val fileNameState = mutableStateOf("Spreadsheet")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        loadFromIntent(intent)
        setContent {
            MaterialTheme {
                XlsxViewerScreen(
                    uri      = uriState.value,
                    fileName = fileNameState.value,
                    onClose  = { finish() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); loadFromIntent(intent)
    }

    private fun loadFromIntent(intent: Intent?) {
        val uri: Uri? = when {
            intent?.action == Intent.ACTION_VIEW && intent.data != null -> intent.data
            else -> null
        }
        if (uri != null && uri.scheme == "content") {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) {}
        }
        uriState.value = uri
        fileNameState.value = uri?.let { u ->
            var nm: String? = null
            if (u.scheme == "content") contentResolver.query(u, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) { val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (i >= 0) nm = c.getString(i) }
            }
            nm ?: u.lastPathSegment?.substringAfterLast('/') ?: "Spreadsheet"
        } ?: "Spreadsheet"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// COLORS
// ─────────────────────────────────────────────────────────────────────────────
private val XL_BG       = Color(0xFF0F0F1A)
private val XL_TOPBAR   = Color(0xFF1A7431)
private val XL_HEADER   = Color(0xFF1F3A27)
private val XL_ROW_EVEN = Color(0xFF141421)
private val XL_ROW_ODD  = Color(0xFF1A1A2E)
private val XL_BORDER   = Color(0xFF2A2A3E)
private val XL_TEXT     = Color(0xFFDDDDDD)
private val XL_ACCENT   = Color(0xFF4CAF50)

// ─────────────────────────────────────────────────────────────────────────────
// PARSER HELPERS
// ─────────────────────────────────────────────────────────────────────────────

/** Parse sharedStrings.xml → list of strings */
private fun parseSharedStrings(xml: String): List<String> {
    val strings = mutableListOf<String>()
    val factory = XmlPullParserFactory.newInstance()
    val parser  = factory.newPullParser()
    parser.setInput(xml.reader())
    val current = StringBuilder()
    var inT     = false

    var e = parser.eventType
    while (e != XmlPullParser.END_DOCUMENT) {
        when (e) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "si" -> current.clear()
                "t"  -> { inT = true; current.clear() }
            }
            XmlPullParser.END_TAG   -> when (parser.name) {
                "t"  -> { inT = false }
                "si" -> strings.add(current.toString())
            }
            XmlPullParser.TEXT -> if (inT) current.append(parser.text)
        }
        e = parser.next()
    }
    return strings
}

/** Parse sheet1.xml → List of rows, each row is a map of colIndex -> cellValue */
private fun parseSheet(xml: String, sharedStrings: List<String>): List<List<String>> {
    val rows = mutableMapOf<Int, MutableMap<Int, String>>() // rowIdx -> colIdx -> value
    val factory = XmlPullParserFactory.newInstance()
    val parser  = factory.newPullParser()
    parser.setInput(xml.reader())

    var rowIdx   = -1
    var colIdx   = -1
    var cellType = ""
    val cellVal  = StringBuilder()
    var inV      = false

    fun colLetterToIndex(ref: String): Int {
        var idx = 0
        for (ch in ref) {
            if (!ch.isLetter()) break
            idx = idx * 26 + (ch.uppercaseChar() - 'A' + 1)
        }
        return idx - 1
    }

    var e = parser.eventType
    while (e != XmlPullParser.END_DOCUMENT) {
        when (e) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "row" -> {
                    rowIdx = (parser.getAttributeValue(null, "r")?.toIntOrNull() ?: (rowIdx + 2)) - 1
                    rows.getOrPut(rowIdx) { mutableMapOf() }
                }
                "c"   -> {
                    val ref = parser.getAttributeValue(null, "r") ?: ""
                    colIdx  = colLetterToIndex(ref)
                    cellType = parser.getAttributeValue(null, "t") ?: ""
                    cellVal.clear()
                }
                "v"   -> { inV = true; cellVal.clear() }
                "t"   -> { inV = true } // inline string
            }
            XmlPullParser.END_TAG -> when (parser.name) {
                "v", "t" -> inV = false
                "c"      -> {
                    val raw = cellVal.toString().trim()
                    val resolved = when (cellType) {
                        "s"    -> sharedStrings.getOrNull(raw.toIntOrNull() ?: -1) ?: raw
                        "b"    -> if (raw == "1") "TRUE" else "FALSE"
                        else   -> raw
                    }
                    if (resolved.isNotEmpty() && rowIdx >= 0 && colIdx >= 0) {
                        rows.getOrPut(rowIdx) { mutableMapOf() }[colIdx] = resolved
                    }
                }
            }
            XmlPullParser.TEXT -> if (inV) cellVal.append(parser.text)
        }
        e = parser.next()
    }

    if (rows.isEmpty()) return emptyList()
    val maxRow = rows.keys.max()
    val maxCol = rows.values.flatMap { it.keys }.maxOrNull() ?: 0
    return (0..maxRow).map { r ->
        (0..maxCol).map { c -> rows[r]?.get(c) ?: "" }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MAIN SCREEN
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun XlsxViewerScreen(uri: Uri?, fileName: String, onClose: () -> Unit) {
    val context = LocalContext.current

    var tableData by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg  by remember { mutableStateOf("") }

    LaunchedEffect(uri) {
        if (uri == null) { isLoading = false; errorMsg = "ফাইল পাওয়া যায়নি"; return@LaunchedEffect }
        withContext(Dispatchers.IO) {
            try {
                val stream: InputStream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("ফাইল খুলতে পারিনি")

                var sharedStringsXml = ""
                var sheet1Xml        = ""

                ZipInputStream(stream.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        when (entry.name) {
                            "xl/sharedStrings.xml" -> sharedStringsXml = zip.bufferedReader(Charsets.UTF_8).readText()
                            "xl/worksheets/sheet1.xml" -> sheet1Xml = zip.bufferedReader(Charsets.UTF_8).readText()
                        }
                        if (sharedStringsXml.isNotEmpty() && sheet1Xml.isNotEmpty()) break
                        entry = zip.nextEntry
                    }
                }

                if (sheet1Xml.isEmpty()) throw IllegalStateException("Sheet data পাওয়া যায়নি")

                val shared = if (sharedStringsXml.isNotEmpty()) parseSharedStrings(sharedStringsXml) else emptyList()
                val rows   = parseSheet(sheet1Xml, shared)

                withContext(Dispatchers.Main) {
                    tableData = rows
                    isLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoading = false
                    errorMsg  = "XLSX পড়তে পারিনি: ${e.message}"
                }
            }
        }
    }

    // Compute max col count
    val colCount = remember(tableData) { tableData.maxOfOrNull { it.size } ?: 0 }
    val cellW    = 110.dp

    Column(Modifier.fillMaxSize().background(XL_BG)) {

        // ── Top Bar ────────────────────────────────────────────────────────
        Surface(color = XL_TOPBAR, shadowElevation = 4.dp) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding()
                    .height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(fileName, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Spreadsheet · ${tableData.size} rows × $colCount cols",
                        fontSize = 11.sp, color = Color.White.copy(0.6f))
                }
                uri?.let { u ->
                    IconButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(u, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try { context.startActivity(Intent.createChooser(intent, "অন্য app দিয়ে খুলুন")) }
                        catch (_: Exception) {}
                    }) {
                        Icon(Icons.Default.OpenInNew, "Open externally", tint = Color.White.copy(0.8f))
                    }
                }
            }
        }

        // ── Table ──────────────────────────────────────────────────────────
        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = XL_ACCENT, strokeWidth = 2.5.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("Spreadsheet পড়া হচ্ছে…", color = XL_TEXT.copy(0.6f), fontSize = 13.sp)
                }
            }
            errorMsg.isNotEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Text("📊", fontSize = 48.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(errorMsg, color = Color(0xFFFF5C5C), fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    uri?.let { u ->
                        OutlinedButton(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(u, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            try { context.startActivity(Intent.createChooser(intent, "অন্য app দিয়ে খুলুন")) }
                            catch (_: Exception) {}
                        }) { Text("অন্য app দিয়ে খুলুন", color = Color.White) }
                    }
                }
            }
            tableData.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Spreadsheet খালি", color = XL_TEXT.copy(0.5f), fontSize = 14.sp)
            }
            else -> {
                val hScroll = rememberScrollState()
                val totalW  = cellW * colCount

                LazyColumn(
                    Modifier.fillMaxSize().horizontalScroll(hScroll),
                    contentPadding = PaddingValues(bottom = 40.dp)
                ) {
                    itemsIndexed(tableData) { rowIdx, row ->
                        val isHeader = rowIdx == 0
                        Row(
                            Modifier
                                .width(totalW)
                                .background(if (isHeader) XL_HEADER else if (rowIdx % 2 == 0) XL_ROW_EVEN else XL_ROW_ODD)
                                .border(0.5.dp, XL_BORDER)
                        ) {
                            // Row number
                            Box(
                                Modifier
                                    .width(36.dp)
                                    .defaultMinSize(minHeight = 36.dp)
                                    .border(0.5.dp, XL_BORDER)
                                    .padding(4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text  = "${rowIdx + 1}",
                                    style = TextStyle(fontSize = 10.sp, color = XL_TEXT.copy(0.35f),
                                        fontFamily = FontFamily.Monospace)
                                )
                            }
                            for (colIdx in 0 until colCount) {
                                val cell = row.getOrElse(colIdx) { "" }
                                Box(
                                    Modifier
                                        .width(cellW)
                                        .defaultMinSize(minHeight = 36.dp)
                                        .border(0.5.dp, XL_BORDER)
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(
                                        text  = cell,
                                        style = TextStyle(
                                            fontSize   = if (isHeader) 12.sp else 11.sp,
                                            fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                                            color      = if (isHeader) XL_ACCENT else XL_TEXT,
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        maxLines  = 3,
                                        overflow  = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.navigationBarsPadding()) }
                }
            }
        }
    }
}

package com.rasel.RasFocus.filemanager

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast




import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.provider.Settings
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext



import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.compose.runtime.collectAsState
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat


fun createLauncherShortcut(context: android.content.Context, path: String? = null, title: String? = null, iconRes: Int? = null) {
    if (ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
        val intent = Intent(context, FileManagerPlusActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (path != null) {
                putExtra("shortcut_path", path)
            }
        }
        val label = title ?: "File Manager"
        val shortcutInfo = ShortcutInfoCompat.Builder(context, path ?: "file_manager_main")
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(context, iconRes ?: com.rasel.RasFocus.R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()
        ShortcutManagerCompat.requestPinShortcut(context, shortcutInfo, null)
        Toast.makeText(context, "Shortcut requested", Toast.LENGTH_SHORT).show()
    } else {
        Toast.makeText(context, "Shortcuts not supported on this device", Toast.LENGTH_SHORT).show()
    }
}

sealed class NavState {
    object Home : NavState()
    data class Local(val path: String) : NavState()
    data class Category(val type: String) : NavState()
    object CloudAccounts : NavState()
    data class Cloud(val accountName: String, val folderId: String, val pathName: String) : NavState()
    object RemoteConnections : NavState()
    data class Remote(val serverId: String, val path: String) : NavState()
    data class P2PChat(val deviceName: String, val ip: String, val port: Int) : NavState()
    object RecycleBin : NavState()
    object SecureVault : NavState()
    object StorageAnalyzer : NavState()
    object AppManager : NavState()
    object DriveOfflineSettings : NavState()
    object FtpServer : NavState()
    object FMSettings : NavState()
    data class TextEditor(val path: String) : NavState()
    data class MarkdownViewer(val path: String) : NavState()
    data class DocxViewer(val path: String) : NavState()
    data class PptxViewer(val path: String) : NavState()
    data class XlsxViewer(val path: String) : NavState()
    data class EpubViewer(val path: String) : NavState()
    data class Saf(val uri: String) : NavState()
    data class ImageViewer(val path: String, val folderPath: String) : NavState()
    data class PdfViewer(val path: String, val folderPath: String) : NavState()
    data class MediaPlayer(val path: String, val folderPath: String) : NavState()
}

// ── Shared utility functions ────────────────────────────────────────────────
fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "kB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return java.text.DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
}

fun formatDate(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}

@Composable
fun BreadcrumbNavBar(currentPath: String, storageRootPath: String, onNavigate: (NavState) -> Unit) {
    val segments = mutableListOf<Pair<String, String>>() // label to full path

    // Build segments: Home → Memory → folder1 → folder2 ...
    val storageName = "Memory"
    val normalizedRoot = storageRootPath.trimEnd('/')
    val normalizedPath = currentPath.trimEnd('/')

    if (normalizedPath == normalizedRoot) {
        // At root: show Home > Memory
        segments.add(Pair("Home", ""))
        segments.add(Pair(storageName, normalizedRoot))
    } else if (normalizedPath.startsWith(normalizedRoot)) {
        segments.add(Pair("Home", ""))
        segments.add(Pair(storageName, normalizedRoot))
        val relative = normalizedPath.removePrefix(normalizedRoot).trimStart('/')
        val parts = relative.split("/").filter { it.isNotEmpty() }
        var builtPath = normalizedRoot
        for (part in parts) {
            builtPath = "$builtPath/$part"
            segments.add(Pair(part, builtPath))
        }
    } else {
        // Non-standard path fallback
        segments.add(Pair("Home", ""))
        val parts = normalizedPath.split("/").filter { it.isNotEmpty() }
        var builtPath = ""
        for (part in parts) {
            builtPath = "$builtPath/$part"
            segments.add(Pair(part, builtPath))
        }
    }

    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFEEF2F7))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(segments.size) { index ->
            val (label, path) = segments[index]
            val isLast = index == segments.size - 1

            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                color = if (isLast) Color(0xFF00796B) else Color(0xFF1565C0),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .then(
                        if (!isLast) Modifier.clickable {
                            if (path.isEmpty()) {
                                onNavigate(NavState.Home)
                            } else {
                                onNavigate(NavState.Local(path))
                            }
                        } else Modifier
                    )
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            )

            if (!isLast) {
                Text(
                    text = " › ",
                    fontSize = 12.sp,
                    color = Color(0xFF888888)
                )
            }
        }
    }
}

fun openLocalFile(context: android.content.Context, file: java.io.File, onNavigate: ((NavState) -> Unit)? = null) {
    try {
        val ext = file.extension.lowercase()
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val internalMime: String? = when (ext) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heic"
            "md", "markdown" -> "text/markdown"
            "docx", "doc" -> "application/docx"
            "pptx", "ppt" -> "application/pptx"
            "xlsx", "xls" -> "application/xlsx"
            "epub" -> "application/epub"
            "txt", "kt", "java", "py", "js", "ts", "json", "xml", "csv",
            "html", "css", "sh", "c", "cpp", "h", "rs", "go", "rb", "yaml", "yml" -> "text/plain"
            else -> null
        }

        if (internalMime != null) {
            if (internalMime == "text/markdown" && onNavigate != null) {
                onNavigate(NavState.MarkdownViewer(file.absolutePath))
                return
            }
            if (internalMime == "text/plain" && onNavigate != null) {
                onNavigate(NavState.TextEditor(file.absolutePath))
                return
            }

            val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
            if (ext in imageExts && onNavigate != null) {
                val folderPath = file.parent ?: file.absolutePath
                onNavigate(NavState.ImageViewer(file.absolutePath, folderPath))
                return
            }

            val mediaExts = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "mp3", "wav", "ogg", "flac", "aac", "m4a")
            if (ext in mediaExts && onNavigate != null) {
                val folderPath = file.parent ?: file.absolutePath
                onNavigate(NavState.MediaPlayer(file.absolutePath, folderPath))
                return
            }

            if (internalMime == "application/docx" && onNavigate != null) {
                onNavigate(NavState.DocxViewer(file.absolutePath))
                return
            }
            if (internalMime == "application/pptx" && onNavigate != null) {
                onNavigate(NavState.PptxViewer(file.absolutePath))
                return
            }
            if (internalMime == "application/xlsx" && onNavigate != null) {
                onNavigate(NavState.XlsxViewer(file.absolutePath))
                return
            }
            if (internalMime == "application/epub" && onNavigate != null) {
                onNavigate(NavState.EpubViewer(file.absolutePath))
                return
            }

            if (internalMime == "application/pdf") {
                val prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
                val engine = prefs.getString("pdf_engine", "pdfium_compose") ?: "pdfium_compose"
                when (engine) {
                    "webview" -> {
                        val wvIntent = android.content.Intent(context, com.rasel.RasFocus.filemanager.WebViewPdfActivity::class.java).apply {
                            action = android.content.Intent.ACTION_VIEW
                            data   = uri
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            putExtra(com.rasel.RasFocus.filemanager.WebViewPdfActivity.EXTRA_LAYER_LABEL, file.name)
                        }
                        context.startActivity(wvIntent)
                        return
                    }
                    "chooser" -> {
                    }
                    "pdfium_legacy" -> {
                        val pkg = context.packageName.replace(".combo", "")
                        val cls = try {
                            Class.forName("$pkg.selfcontrol.study_tools.UniversalViewerActivity",
                                true, context.classLoader)
                        } catch (_: ClassNotFoundException) { null }
                        if (cls != null) {
                            val legacyIntent = android.content.Intent(context, cls).apply {
                                action = android.content.Intent.ACTION_VIEW
                                setDataAndType(uri, internalMime)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(legacyIntent)
                            return
                        }
                    }
                }
                if (engine != "chooser") {
                    if (onNavigate != null) {
                        val folderPath = file.parent ?: file.absolutePath
                        onNavigate(NavState.PdfViewer(file.absolutePath, folderPath))
                        return
                    }
                    val intent = android.content.Intent(context, com.rasel.RasFocus.filemanager.FMPdfViewerActivity::class.java).apply {
                        action = android.content.Intent.ACTION_VIEW
                        setDataAndType(uri, internalMime)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(intent)
                    return
                }
            }

            val officeExts = setOf("docx","doc","pptx","ppt","xlsx","xls")
            if (ext in officeExts) {
                val prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
                if (prefs.getString("pdf_engine", "pdfium_compose") == "webview") {
                    val wvIntent = android.content.Intent(context, com.rasel.RasFocus.filemanager.WebViewPdfActivity::class.java).apply {
                        action = android.content.Intent.ACTION_VIEW
                        data   = uri
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        putExtra(com.rasel.RasFocus.filemanager.WebViewPdfActivity.EXTRA_LAYER_LABEL, file.name)
                    }
                    context.startActivity(wvIntent)
                    return
                }
            }

            val pkg = context.packageName.replace(".combo", "")
            val cls = try {
                Class.forName("$pkg.selfcontrol.study_tools.UniversalViewerActivity")
            } catch (_: ClassNotFoundException) { null }
            if (cls != null) {
                val intent = android.content.Intent(context, cls).apply {
                    action = android.content.Intent.ACTION_VIEW
                    setDataAndType(uri, internalMime)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
                return
            }
        }

        val mimeType = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext) ?: "*/*"
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Open with"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Cannot open file: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

data class ClipboardState(
    val sourceEnv: String, 
    val items: List<String>, 
    val itemNames: List<String> = emptyList(), 
    val itemMimeTypes: List<String> = emptyList(), 
    val isCut: Boolean = false,
    val accountName: String? = null
)

enum class SortMode { NAME_ASC, NAME_DESC, DATE_ASC, DATE_DESC, SIZE_ASC, SIZE_DESC }

class FileManagerPlusActivity : ComponentActivity() {
    var pendingSharedUris by mutableStateOf<List<android.net.Uri>>(emptyList())
    var pendingViewFile by mutableStateOf<java.io.File?>(null)

    private val legacyPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        android.util.Log.d("FileManagerPlusActivity", "Permission result: READ_EXTERNAL_STORAGE granted=$granted")
        if (granted) {
            recreate()
        }
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            if (uri != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val file = copyUriToCache(uri)
                    if (file != null) {
                        withContext(Dispatchers.Main) {
                            pendingViewFile = file
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@FileManagerPlusActivity, "Failed to open file", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        } else if (intent.action == Intent.ACTION_SEND) {
            val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            if (uri != null) {
                pendingSharedUris = listOf(uri)
            }
        } else if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val uris = intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            if (uris != null) {
                pendingSharedUris = uris.toList()
            }
        }
    }

    private fun copyUriToCache(uri: android.net.Uri): java.io.File? {
        try {
            var fileName = "shared_file"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        fileName = cursor.getString(nameIndex)
                    }
                }
            }
            val cacheDir = java.io.File(cacheDir, "external_view")
            cacheDir.mkdirs()
            val destFile = java.io.File(cacheDir, fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            return destFile
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialPath = intent.getStringExtra("shortcut_path")
        handleIntent(intent)
        SafFileManager.init(this)
        requestStoragePermissionIfNeeded()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9001)
            }
        }
        setContent {
            MaterialTheme {
                HomeScreen(initialPath, pendingSharedUris, { pendingSharedUris = emptyList() }, pendingViewFile, { pendingViewFile = null })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
        }
    }

    private fun requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                legacyPermLauncher.launch(arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(initialPath: String? = null, sharedUris: List<android.net.Uri> = emptyList(), onClearSharedUris: () -> Unit = {}, viewFile: java.io.File? = null, onClearViewFile: () -> Unit = {}) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var currentNavState by remember { 
        mutableStateOf<NavState>(
            if (initialPath != null) {
                val f = java.io.File(initialPath)
                if (f.exists() && f.isDirectory) NavState.Local(initialPath) else NavState.Home
            } else NavState.Home
        ) 
    }
    
    androidx.compose.runtime.LaunchedEffect(initialPath) {
        if (initialPath != null) {
            val f = java.io.File(initialPath)
            if (f.isFile) {
                openLocalFile(context, f) { currentNavState = it }
            }
        }
    }
    
    androidx.compose.runtime.LaunchedEffect(viewFile) {
        if (viewFile != null) {
            openLocalFile(context, viewFile) { currentNavState = it }
            onClearViewFile()
        }
    }
    
    var clipboard by remember { mutableStateOf<ClipboardState?>(null) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(SortMode.NAME_ASC) }
    var showSearchBar by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val cloudBackStack = remember { mutableStateListOf<Pair<String, String>>() }
    val safBackStack = remember { mutableStateListOf<String>() }

    val p2pDiscovery = remember { com.rasel.RasFocus.p2p.P2PDiscoveryManager(context) }
    val p2pConnection = remember { com.rasel.RasFocus.p2p.P2PConnectionManager(java.io.File(LocalFileManager.mainStoragePath, "Download")) }
    
    LaunchedEffect(Unit) {
        val port = (50000..60000).random()
        p2pConnection.startServer(port, this)
        p2pDiscovery.registerService(port)
        p2pDiscovery.discoverServices()
    }
    
    DisposableEffect(Unit) {
        onDispose {
            p2pDiscovery.stop()
            p2pConnection.stop()
        }
    }

    val activity = androidx.compose.ui.platform.LocalContext.current as? androidx.activity.ComponentActivity

    BackHandler(enabled = true) {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            showSearchBar -> {
                showSearchBar = false
                searchQuery = ""
            }
            currentNavState == NavState.RecycleBin ||
            currentNavState == NavState.SecureVault ||
            currentNavState == NavState.StorageAnalyzer ||
            currentNavState == NavState.AppManager ||
            currentNavState == NavState.DriveOfflineSettings ||
            currentNavState == NavState.FMSettings ||
            currentNavState is NavState.P2PChat ||
            currentNavState is NavState.Category -> {
                currentNavState = NavState.Home
            }
            currentNavState is NavState.TextEditor -> {
                val s = currentNavState as NavState.TextEditor
                val parent = java.io.File(s.path).parent
                if (parent != null && java.io.File(parent).exists()) {
                    currentNavState = NavState.Local(parent)
                } else {
                    currentNavState = NavState.Home
                }
            }
            currentNavState is NavState.MarkdownViewer -> {
                val s = currentNavState as NavState.MarkdownViewer
                val parent = java.io.File(s.path).parent
                if (parent != null && java.io.File(parent).exists()) {
                    currentNavState = NavState.Local(parent)
                } else {
                    currentNavState = NavState.Home
                }
            }
            currentNavState is NavState.ImageViewer -> {
                val s = currentNavState as NavState.ImageViewer
                currentNavState = NavState.Local(s.folderPath)
            }
            currentNavState is NavState.PdfViewer -> {
                val s = currentNavState as NavState.PdfViewer
                currentNavState = NavState.Local(s.folderPath)
            }
            currentNavState is NavState.MediaPlayer -> {
                val s = currentNavState as NavState.MediaPlayer
                currentNavState = NavState.Local(s.folderPath)
            }
            currentNavState is NavState.Cloud -> {
                val state = currentNavState as NavState.Cloud
                if (cloudBackStack.isNotEmpty()) {
                    val prev = cloudBackStack.removeAt(cloudBackStack.size - 1)
                    currentNavState = NavState.Cloud(state.accountName, prev.first, prev.second)
                } else if (state.folderId != "root") {
                    cloudBackStack.clear()
                    currentNavState = NavState.Cloud(state.accountName, "root", "My Drive")
                } else {
                    cloudBackStack.clear()
                    currentNavState = NavState.CloudAccounts
                }
            }
            currentNavState is NavState.CloudAccounts -> {
                currentNavState = NavState.Home
            }
            currentNavState is NavState.Local -> {
                val state = currentNavState as NavState.Local
                val currentFile = java.io.File(state.path)
                val parent = currentFile.parentFile
                val storageRoot = android.os.Environment.getExternalStorageDirectory().absolutePath
                val isAtRoot = parent == null ||
                    currentFile.absolutePath == storageRoot ||

                    parent.absolutePath == "/storage/emulated" ||
                    parent.absolutePath == "/storage" ||
                    parent.absolutePath == "/mnt" ||
                    !parent.exists()
                if (!isAtRoot) {
                    currentNavState = NavState.Local(parent!!.absolutePath)
                } else {
                    currentNavState = NavState.Home
                }
            }
            currentNavState is NavState.Saf -> {
                if (safBackStack.isNotEmpty()) {
                    currentNavState = NavState.Saf(safBackStack.removeAt(safBackStack.size - 1))
                } else {
                    safBackStack.clear()
                    currentNavState = NavState.Home
                }
            }
            currentNavState == NavState.Home && !drawerState.isOpen -> {
                activity?.finish()
            }
            else -> {
                currentNavState = NavState.Home
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = currentNavState !is NavState.PdfViewer && currentNavState !is NavState.ImageViewer && currentNavState !is NavState.MediaPlayer && currentNavState !is NavState.TextEditor,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                DrawerContent(
                    onNavigate = { newState ->
                        cloudBackStack.clear()
                        safBackStack.clear()
                        currentNavState = newState
                        scope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                val needsGlobalHeader = currentNavState !is NavState.Local &&
                    currentNavState !is NavState.Cloud &&
                    currentNavState !is NavState.Remote &&
                    currentNavState !is NavState.Saf &&
                    currentNavState !is NavState.ImageViewer &&
                    currentNavState !is NavState.PdfViewer &&
                    currentNavState !is NavState.MediaPlayer &&
                    currentNavState !is NavState.Category &&
                    currentNavState !is NavState.TextEditor &&
                    currentNavState !is NavState.MarkdownViewer &&
                    currentNavState !is NavState.DocxViewer &&
                    currentNavState !is NavState.PptxViewer &&
                    currentNavState !is NavState.XlsxViewer &&
                    currentNavState != NavState.RecycleBin &&
                    currentNavState != NavState.SecureVault &&
                    currentNavState != NavState.StorageAnalyzer &&
                    currentNavState != NavState.AppManager &&
                    currentNavState != NavState.DriveOfflineSettings &&
                    currentNavState != NavState.FMSettings &&
                    currentNavState != NavState.FtpServer &&
                    currentNavState != NavState.RemoteConnections
                if (needsGlobalHeader) {
                    if (showSearchBar) {
                    TopAppBar(
                        title = {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search...", color = Color.White.copy(alpha = 0.6f)) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    cursorColor = Color.White
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF7F9FC), titleContentColor = Color(0xFF1D1B20)),
                        navigationIcon = {
                            IconButton(onClick = {
                                showSearchBar = false
                                searchQuery = ""
                            }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Close search", tint = Color(0xFF49454F))
                            }
                        },
                        actions = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color(0xFF49454F))
                                }
                            }
                        }
                    )
                } else {
                    TopAppBar(
                        title = {
                            Text(
                                text = when (val state = currentNavState) {
                                    is NavState.Home -> "File Manager +"
                                    is NavState.Local -> state.path.substringAfterLast("/")
                                    is NavState.Category -> state.type
                                    is NavState.CloudAccounts -> "Cloud Locations"
                                    is NavState.Cloud -> state.pathName
                                    is NavState.Remote -> state.path
                                    is NavState.RecycleBin -> "Recycle Bin"
                                    is NavState.SecureVault -> "Secure Vault"
                                    is NavState.StorageAnalyzer -> "Storage Analyzer"
                                    is NavState.AppManager -> "App Manager"
                                    is NavState.DriveOfflineSettings -> "Offline Settings"
                                    is NavState.FMSettings -> "Settings"
                                    is NavState.FtpServer -> "Access from PC"
                                    is NavState.RemoteConnections -> "Remote Connections"
                                    is NavState.P2PChat -> "Chat with ${state.deviceName}"
                                    is NavState.TextEditor -> state.path.substringAfterLast("/")
                                    is NavState.MarkdownViewer -> state.path.substringAfterLast("/")
                                    is NavState.Saf -> state.uri.substringAfterLast("%2F").substringAfterLast("/")
                                    else -> ""
                                },
                                color = Color(0xFF1D1B20).copy(alpha = 0.9f), fontWeight = FontWeight.Bold, fontSize = 22.sp
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF7F9FC), titleContentColor = Color(0xFF1D1B20)),
                        navigationIcon = {
                            IconButton(onClick = {
                                if (currentNavState != NavState.Home) {
                                    when {
                                        currentNavState is NavState.Cloud -> {
                                            val state = currentNavState as NavState.Cloud
                                            if (cloudBackStack.isNotEmpty()) {
                                                val prev = cloudBackStack.removeAt(cloudBackStack.size - 1)
                                                currentNavState = NavState.Cloud(state.accountName, prev.first, prev.second)
                                            } else if (state.folderId != "root") {
                                                cloudBackStack.clear()
                                                currentNavState = NavState.Cloud(state.accountName, "root", "My Drive")
                                            } else {
                                                cloudBackStack.clear()
                                                currentNavState = NavState.CloudAccounts
                                            }
                                        }
                                        currentNavState is NavState.CloudAccounts -> {
                                            currentNavState = NavState.Home
                                        }
                                        currentNavState is NavState.Local -> {
                                            val state = currentNavState as NavState.Local
                                            val currentFile = java.io.File(state.path)
                                            val parent = currentFile.parentFile
                                            val storageRoot = android.os.Environment.getExternalStorageDirectory().absolutePath
                                            val isAtRoot = parent == null ||
                                                currentFile.absolutePath == storageRoot ||

                                                parent.absolutePath == "/storage/emulated" ||
                                                parent.absolutePath == "/storage" ||
                                                parent.absolutePath == "/mnt" ||
                                                !parent.exists()
                                            if (!isAtRoot) {
                                                currentNavState = NavState.Local(parent!!.absolutePath)
                                            } else {
                                                currentNavState = NavState.Home
                                            }
                                        }
                                        else -> currentNavState = NavState.Home
                                    }
                                } else {
                                    scope.launch { drawerState.open() }
                                }
                            }) {
                                Icon(
                                    imageVector = if (currentNavState == NavState.Home) Icons.Default.Menu else Icons.Default.ArrowBack,
                                    contentDescription = "Menu/Back",
                                    tint = Color(0xFF49454F)
                                )
                            }
                        },
                        actions = {
                            if (currentNavState != NavState.Home) {
                                IconButton(onClick = { showSearchBar = true }) {
                                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Color(0xFF49454F))
                                }
                            }
                            IconButton(onClick = {
                                android.widget.Toast.makeText(context, "Premium features coming soon", android.widget.Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.Star, contentDescription = "Premium", tint = Color(0xFFFFA500))
                            }
                            Box {
                                IconButton(onClick = { showMoreMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color(0xFF49454F))
                                }
                                DropdownMenu(
                                    expanded = showMoreMenu,
                                    onDismissRequest = { showMoreMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Sort by name ↑", fontWeight = if (sortMode == SortMode.NAME_ASC) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = { showMoreMenu = false; sortMode = SortMode.NAME_ASC }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sort by name ↓", fontWeight = if (sortMode == SortMode.NAME_DESC) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = { showMoreMenu = false; sortMode = SortMode.NAME_DESC }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sort by date ↑", fontWeight = if (sortMode == SortMode.DATE_ASC) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = { showMoreMenu = false; sortMode = SortMode.DATE_ASC }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sort by date ↓", fontWeight = if (sortMode == SortMode.DATE_DESC) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = { showMoreMenu = false; sortMode = SortMode.DATE_DESC }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sort by size ↑", fontWeight = if (sortMode == SortMode.SIZE_ASC) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = { showMoreMenu = false; sortMode = SortMode.SIZE_ASC }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sort by size ↓", fontWeight = if (sortMode == SortMode.SIZE_DESC) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = { showMoreMenu = false; sortMode = SortMode.SIZE_DESC }
                                    )
                                    Divider()
                                    DropdownMenuItem(
                                        text = { Text("Analyze Storage") },
                                        leadingIcon = { Icon(Icons.Default.PieChart, null) },
                                        onClick = {
                                            showMoreMenu = false
                                            currentNavState = NavState.StorageAnalyzer
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Refresh") },
                                        leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                        onClick = {
                                            showMoreMenu = false
                                            val cur = currentNavState
                                            currentNavState = NavState.Home
                                            currentNavState = cur
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Settings") },
                                        leadingIcon = { Icon(Icons.Default.Settings, null) },
                                        onClick = {
                                            showMoreMenu = false
                                            currentNavState = NavState.FMSettings
                                        }
                                    )
                                }
                            }
                        }
                    )
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            val globalOps by FileOperationManager.operations.collectAsState()
            Column(modifier = Modifier.padding(paddingValues)) {
                // ── Breadcrumb navigation bar ────────────────────────────────
                val showBreadcrumb = currentNavState is NavState.Local
                if (showBreadcrumb) {
                    val localPath = (currentNavState as NavState.Local).path
                    BreadcrumbNavBar(
                        currentPath = localPath,
                        storageRootPath = LocalFileManager.mainStoragePath,
                        onNavigate = { currentNavState = it }
                    )
                }
                // ─────────────────────────────────────────────────────────────
                Box(modifier = Modifier.weight(1f)) {
                Box(modifier = Modifier.fillMaxSize()) {
                    val baseState = when (val state = currentNavState) {
                        is NavState.ImageViewer -> NavState.Local(state.folderPath)
                        is NavState.PdfViewer -> NavState.Local(state.folderPath)
                        is NavState.MediaPlayer -> NavState.Local(state.folderPath)
                        is NavState.TextEditor -> {
                            val parent = java.io.File(state.path).parent
                            if (parent != null) NavState.Local(parent) else NavState.Home
                        }
                        is NavState.MarkdownViewer, is NavState.DocxViewer, is NavState.PptxViewer, is NavState.XlsxViewer, is NavState.EpubViewer -> {
                            val path = when (state) {
                                is NavState.MarkdownViewer -> state.path
                                is NavState.DocxViewer -> state.path
                                is NavState.PptxViewer -> state.path
                                is NavState.XlsxViewer -> state.path
                                is NavState.EpubViewer -> state.path
                                else -> ""
                            }
                            val parent = java.io.File(path).parent
                            if (parent != null) NavState.Local(parent) else NavState.Home
                        }
                        else -> currentNavState
                    }

                    when (val state = baseState) {
                    is NavState.Home -> MainGridContent(
                        onNavigate = { newState ->
                            cloudBackStack.clear()
                            currentNavState = newState
                        }
                    )
                    is NavState.Category -> CategoryScreen(
                        category = state.type,
                        onBack = { currentNavState = NavState.Home },
                        onFileClick = { file -> openLocalFile(context, file) { currentNavState = it } }
                    )
                    is NavState.Local -> LocalFileScreen(
                        path = state.path,
                        onNavigate = { currentNavState = it },
                        onBack = {
                            val currentFile = java.io.File(state.path)
                            val parent = currentFile.parentFile
                            val storageRoot = android.os.Environment.getExternalStorageDirectory().absolutePath
                            val isAtRoot = parent == null ||
                                currentFile.absolutePath == storageRoot ||

                                parent.absolutePath == "/storage/emulated" ||
                                parent.absolutePath == "/storage" ||
                                parent.absolutePath == "/mnt" ||
                                !parent.exists()
                            if (!isAtRoot) {
                                currentNavState = NavState.Local(parent!!.absolutePath)
                            } else {
                                currentNavState = NavState.Home
                            }
                        },
                        clipboard = clipboard,
                        onSetClipboard = { clipboard = it },
                        sortMode = sortMode,
                        searchQuery = if (showSearchBar) searchQuery else ""
                    )
                    is NavState.CloudAccounts -> CloudAccountsScreen(
                        onAccountSelected = { accountName ->
                            cloudBackStack.clear()
                            currentNavState = NavState.Cloud(accountName, "root", "My Drive")
                        }
                    )
                    is NavState.Cloud -> CloudFileScreen(
                        accountName = state.accountName,
                        folderId = state.folderId,
                        pathName = state.pathName,
                        onNavigate = { newState ->
                            if (newState is NavState.Cloud) {
                                val fresh1 = currentNavState as? NavState.Cloud
                                if (fresh1 != null) { cloudBackStack.add(Pair(fresh1.folderId, fresh1.pathName)) }
                            }
                            currentNavState = newState
                        },
                        onBack = {
                            val freshState = currentNavState as? NavState.Cloud ?: return@CloudFileScreen
                            if (cloudBackStack.isNotEmpty()) {
                                val prev = cloudBackStack.removeAt(cloudBackStack.size - 1)
                                currentNavState = NavState.Cloud(freshState.accountName, prev.first, prev.second)
                            } else if (freshState.folderId == "root") {
                                cloudBackStack.clear()
                                currentNavState = NavState.CloudAccounts
                            } else {
                                cloudBackStack.clear()
                                currentNavState = NavState.Cloud(freshState.accountName, "root", "My Drive")
                            }
                        },
                        clipboard = clipboard,
                        onSetClipboard = { clipboard = it },
                        sortMode = sortMode,
                        searchQuery = if (showSearchBar) searchQuery else ""
                    )
                    is NavState.RemoteConnections -> RemoteConnectionsScreen(
                        p2pDiscovery = p2pDiscovery,
                        p2pConnection = p2pConnection,
                        onNavigate = { newState -> currentNavState = newState },
                        onBack = { currentNavState = NavState.Home }
                    )
                    is NavState.P2PChat -> {
                        val device = com.rasel.RasFocus.p2p.DiscoveredDevice(state.deviceName, state.ip, state.port)
                        com.rasel.RasFocus.p2p.P2PChatScreen(
                            device = device,
                            connectionManager = p2pConnection,
                            onBack = { currentNavState = NavState.RemoteConnections },
                            onBrowseFolders = {
                                val tempServerId = "p2p_ftp_${state.ip}"
                                RemoteStore.servers.add(RemoteServer(id = tempServerId, name = state.deviceName, host = state.ip, port = 2121, user = "anonymous", pass = "", protocol = "FTP"))
                                currentNavState = NavState.Remote(tempServerId, "/")
                            }
                        )
                    }
                    is NavState.Remote -> RemoteFileScreen(
                        serverId = state.serverId,
                        initialPath = state.path,
                        onNavigate = { newState -> currentNavState = newState },
                        onBack = { currentNavState = NavState.Home }
                    )
                    is NavState.FtpServer -> FtpServerScreen(
                        onBack = { currentNavState = NavState.Home }
                    )
                    is NavState.RecycleBin -> RecycleBinScreen(
                        onBack = { currentNavState = NavState.Home }
                    )
                    is NavState.SecureVault -> SecureVaultScreen(
                        onBack = { currentNavState = NavState.Home }
                    )
                    is NavState.StorageAnalyzer -> StorageAnalyzerScreen(
                        onBack = { currentNavState = NavState.Home }
                    )
                    is NavState.AppManager -> AppManagerScreen(
                        onBack = { currentNavState = NavState.Home }
                    )
                    is NavState.DriveOfflineSettings -> DriveOfflineSettingsScreen(
                        onBack = { currentNavState = NavState.Home }
                    )

                    is NavState.FMSettings -> SettingsScreen(
                        onBack = { currentNavState = NavState.Home }
                    )

                    is NavState.Saf -> SafFileScreen(
                        uriString = state.uri,
                        onNavigate = { newState ->
                            if (newState is NavState.Saf) {
                                safBackStack.add(state.uri)
                            } else {
                                safBackStack.clear()
                            }
                            currentNavState = newState
                        },
                        onBack = {
                            if (safBackStack.isNotEmpty()) {
                                currentNavState = NavState.Saf(safBackStack.removeAt(safBackStack.size - 1))
                            } else {
                                safBackStack.clear()
                                currentNavState = NavState.Home
                            }
                        }
                    )
                    else -> {}
                    } 

                    when (val state = currentNavState) {
                        is NavState.ImageViewer -> ImageViewerScreen(
                            imagePath = state.path,
                            onBack = { currentNavState = NavState.Local(state.folderPath) }
                        )
                        is NavState.PdfViewer -> FMPdfViewerScreen(
                            filePath = state.path,
                            onBack = { currentNavState = NavState.Local(state.folderPath) }
                        )
                        is NavState.MediaPlayer -> AudioVideoPlayerScreen(
                            mediaPath = state.path,
                            onBack = { currentNavState = NavState.Local(state.folderPath) }
                        )
                        is NavState.TextEditor -> TextEditorScreen(
                            path = state.path,
                            onBack = { 
                                val parent = java.io.File(state.path).parent
                                if (parent != null) {
                                    currentNavState = NavState.Local(parent)
                                } else {
                                    currentNavState = NavState.Home 
                                }
                            }
                        )
                        is NavState.MarkdownViewer -> MarkdownViewerScreen(
                            path = state.path,
                            onBack = { 
                                val parent = java.io.File(state.path).parent
                                if (parent != null) {
                                    currentNavState = NavState.Local(parent)
                                } else {
                                    currentNavState = NavState.Home 
                                }
                            }
                        )
                        is NavState.DocxViewer -> {
                            val docxFile = java.io.File(state.path)
                            val folderPath = docxFile.parent ?: ""
                            val onBack: () -> Unit = {
                                currentNavState = if (folderPath.isNotEmpty()) NavState.Local(folderPath) else NavState.Home
                            }

                            // .doc binary → can't convert, show friendly error
                            if (docxFile.extension.lowercase() == "doc") {
                                androidx.compose.foundation.layout.Box(
                                    modifier = androidx.compose.ui.Modifier.fillMaxSize()
                                        .background(androidx.compose.ui.graphics.Color(0xFFFAFAFA)),
                                    contentAlignment = androidx.compose.ui.Alignment.Center
                                ) {
                                    androidx.compose.foundation.layout.Column(
                                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                                        modifier = androidx.compose.ui.Modifier.padding(32.dp)
                                    ) {
                                        Text("📄", fontSize = 52.sp)
                                        Spacer(modifier = androidx.compose.ui.Modifier.height(12.dp))
                                        Text(
                                            ".doc ফরম্যাট সাপোর্টেড না।\nফাইলটি .docx এ কনভার্ট করুন।",
                                            fontSize = 14.sp,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            color = androidx.compose.ui.graphics.Color(0xFF666666)
                                        )
                                        Spacer(modifier = androidx.compose.ui.Modifier.height(20.dp))
                                        OutlinedButton(onClick = onBack) { Text("ফিরে যাও") }
                                    }
                                }
                            } else {
                                // .docx → silently convert to PDF, then show PDF viewer
                                DocxToPdfViewerScreen(
                                    docxPath   = state.path,
                                    onBack     = onBack
                                )
                            }
                        }
                        is NavState.PptxViewer -> com.rasel.RasFocus.selfcontrol.study_tools.PptxViewerScreen(
                            uri = android.net.Uri.fromFile(java.io.File(state.path)),
                            fileName = java.io.File(state.path).name,
                            onClose = {
                                val parent = java.io.File(state.path).parent
                                currentNavState = if (parent != null) NavState.Local(parent) else NavState.Home 
                            }
                        )
                        is NavState.XlsxViewer -> com.rasel.RasFocus.selfcontrol.study_tools.XlsxViewerScreen(
                            uri = android.net.Uri.fromFile(java.io.File(state.path)),
                            fileName = java.io.File(state.path).name,
                            onClose = {
                                val parent = java.io.File(state.path).parent
                                currentNavState = if (parent != null) NavState.Local(parent) else NavState.Home 
                            }
                        )
                        is NavState.EpubViewer -> EpubViewerScreen(
                            path = state.path,
                            onBack = { 
                                val parent = java.io.File(state.path).parent
                                currentNavState = if (parent != null) NavState.Local(parent) else NavState.Home 
                            }
                        )
                        else -> {}
                    }
                } 
                } 
                
                if (sharedUris.isNotEmpty() && currentNavState is NavState.Local) {
                    val localState = currentNavState as NavState.Local
                    Surface(color = Color(0xFF1A6B6B), shadowElevation = 12.dp) {
                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Save ${sharedUris.size} shared files here?", color = Color.White)
                            Row {
                                TextButton(onClick = { onClearSharedUris() }) { Text("CANCEL", color = Color.LightGray) }
                                Button(
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1A6B6B)),
                                    onClick = { 
                                        val destDir = localState.path
                                        scope.launch(Dispatchers.IO) {
                                            var successCount = 0
                                            sharedUris.forEach { uri ->
                                                try {
                                                    var fileName = "shared_file_${System.currentTimeMillis()}"
                                                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                                        if (cursor.moveToFirst()) {
                                                            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                                            if (nameIndex != -1) {
                                                                fileName = cursor.getString(nameIndex)
                                                            }
                                                        }
                                                    }
                                                    val destFile = java.io.File(destDir, fileName)
                                                    context.contentResolver.openInputStream(uri)?.use { input ->
                                                        java.io.FileOutputStream(destFile).use { output ->
                                                            input.copyTo(output)
                                                        }
                                                    }
                                                    successCount++
                                                } catch (e: Exception) { e.printStackTrace() }
                                            }
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(context, "Saved $successCount files", Toast.LENGTH_SHORT).show()
                                                onClearSharedUris()
                                                // trigger refresh
                                                val cur = currentNavState
                                                currentNavState = NavState.Home
                                                currentNavState = cur
                                            }
                                        }
                                    }
                                ) { Text("SAVE HERE") }
                            }
                        }
                    }
                }
                ActiveOperationsBar(operations = globalOps)
            }
        }
    }
}

data class StorageInfo(val used: Long, val total: Long) {
    val usedText: String get() {
        if (total <= 0) return ""
        return "${fmtBytes(used)} / ${fmtBytes(total)}"
    }
    val progress: Float get() = if (total <= 0) 0f else (used.toFloat() / total).coerceIn(0f, 1f)
    private fun fmtBytes(b: Long): String {
        if (b <= 0) return "0 B"
        val u = arrayOf("B", "KB", "MB", "GB")
        val i = (Math.log10(b.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, 3)
        return String.format("%.1f %s", b / Math.pow(1024.0, i.toDouble()), u[i])
    }
}

fun getInternalStorageInfo(): StorageInfo {
    return try {
        val path = Environment.getDataDirectory()
        val stat = StatFs(path.path)
        val total = stat.blockCountLong * stat.blockSizeLong
        val avail = stat.availableBlocksLong * stat.blockSizeLong
        StorageInfo(used = total - avail, total = total)
    } catch (e: Exception) { StorageInfo(0, 0) }
}

fun getExternalStorageInfo(): StorageInfo {
    return try {
        val path = Environment.getExternalStorageDirectory()
        val stat = StatFs(path.path)
        val total = stat.blockCountLong * stat.blockSizeLong
        val avail = stat.availableBlocksLong * stat.blockSizeLong
        StorageInfo(used = total - avail, total = total)
    } catch (e: Exception) { StorageInfo(0, 0) }
}

fun getSdCardStorageInfo(context: android.content.Context): StorageInfo {
    return try {
        val pathStr = LocalFileManager.getSdCardPath(context)
        if (pathStr != null) {
            val path = java.io.File(pathStr)
            val stat = StatFs(path.path)
            val total = stat.blockCountLong * stat.blockSizeLong
            val avail = stat.availableBlocksLong * stat.blockSizeLong
            StorageInfo(used = total - avail, total = total)
        } else StorageInfo(0, 0)
    } catch (e: Exception) { StorageInfo(0, 0) }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MainGridContent(modifier: Modifier = Modifier, onNavigate: (NavState) -> Unit) {
    val context = LocalContext.current
    var internalInfo by remember { mutableStateOf(StorageInfo(0, 0)) }
    var externalInfo by remember { mutableStateOf(StorageInfo(0, 0)) }
    var sdInfo by remember { mutableStateOf(StorageInfo(0, 0)) }
    val scope = rememberCoroutineScope()

    // --- ML Kit Document Scanner (Home page) ---
    var mlKitResultImages by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    var isProcessingMagicPro by remember { mutableStateOf(false) }

    val homeScannerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            val scanResult = com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            scanResult?.pages?.let { pages ->
                mlKitResultImages = pages.map { it.imageUri }
            }
        }
    }

    fun launchHomeScanner() {
        val options = com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(50)
            .setResultFormats(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        val scanner = com.google.mlkit.vision.documentscanner.GmsDocumentScanning.getClient(options)
        val activity = context as? android.app.Activity
        if (activity != null) {
            scanner.getStartScanIntent(activity)
                .addOnSuccessListener { intentSender ->
                    homeScannerLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(intentSender).build())
                }
                .addOnFailureListener { e ->
                    android.widget.Toast.makeText(context, "Scanner failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
        } else {
            android.widget.Toast.makeText(context, "Activity context required", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    // ------------------------------------------

    val shortcutPrefs = remember { context.getSharedPreferences("HomeShortcutPrefs", android.content.Context.MODE_PRIVATE) }
    var shortcutKeys by remember {
        mutableStateOf(shortcutPrefs.getStringSet("shortcuts", emptySet()) ?: emptySet())
    }
    var showShortcutManager by remember { mutableStateOf(false) }

    val drivePrefs = remember { context.getSharedPreferences("MyDrivePrefs", android.content.Context.MODE_PRIVATE) }
    var selectedDriveAccount by remember {
        mutableStateOf(drivePrefs.getString("selected_account", null))
    }
    var showDrivePickerSheet by remember { mutableStateOf(false) }
    var driveAccounts by remember { mutableStateOf(CloudAccountManager.getAccounts(context)) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            internalInfo = getInternalStorageInfo()
            externalInfo = getExternalStorageInfo()
            sdInfo = getSdCardStorageInfo(context)
        }
    }

    val manageStorageLauncher = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            scope.launch(Dispatchers.IO) {
                internalInfo = getInternalStorageInfo()
                externalInfo = getExternalStorageInfo()
                sdInfo = getSdCardStorageInfo(context)
            }
        }
    } else null

    val legacyPermissionLauncher = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { _ ->
            scope.launch(Dispatchers.IO) {
                internalInfo = getInternalStorageInfo()
                externalInfo = getExternalStorageInfo()
                sdInfo = getSdCardStorageInfo(context)
            }
        }
    } else null

    fun openStoragePermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                manageStorageLauncher?.launch(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                manageStorageLauncher?.launch(intent)
            }
        } else {
            legacyPermissionLauncher?.launch(arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ))
        }
    }

    fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    val mainStorageInfo = externalInfo
    val sdCardPath = remember { LocalFileManager.getSdCardPath(context) }

    var downloadsCount by remember { mutableStateOf("") }
    var imagesCount   by remember { mutableStateOf("") }
    var audioCount    by remember { mutableStateOf("") }
    var videosCount   by remember { mutableStateOf("") }
    var docsCount     by remember { mutableStateOf("") }
    var appsCount     by remember { mutableStateOf("") }
    var newFilesCount by remember { mutableStateOf("") }
    var cloudCount    by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val base = LocalFileManager.mainStoragePath
            
            fun formatStats(stats: Pair<Int, Long>): String {
                val (count, size) = stats
                val sizeStr = if (size > 0) formatFileSize(size) else ""
                return if (sizeStr.isNotEmpty()) "$sizeStr ($count)" else if (count > 0) "($count)" else ""
            }

            downloadsCount = formatStats(CategoryUtils.getCategoryStats(context, "Downloads").let {
                val f = java.io.File("$base/Download")
                if (f.exists()) {
                    val files = f.listFiles() ?: emptyArray()
                    val c = files.size
                    val s = files.sumOf { if (it.isFile) it.length() else 0L }
                    Pair(c, s)
                } else Pair(0, 0L)
            })

            imagesCount    = formatStats(CategoryUtils.getCategoryStats(context, "Images"))
            audioCount     = formatStats(CategoryUtils.getCategoryStats(context, "Audio"))
            videosCount    = formatStats(CategoryUtils.getCategoryStats(context, "Videos"))
            docsCount      = formatStats(CategoryUtils.getCategoryStats(context, "Documents"))
            newFilesCount  = formatStats(CategoryUtils.getCategoryStats(context, "New files"))
            appsCount      = formatStats(CategoryUtils.getCategoryStats(context, "Apps"))
            cloudCount     = ""
        }
    }

    fun navigate(title: String) {
        val base = LocalFileManager.mainStoragePath
        when (title) {
            "Main storage" -> {
                if (hasStorageAccess()) onNavigate(NavState.Local(base))
                else openStoragePermissionSettings()
            }
            "SD card" -> {
                val sdPath = LocalFileManager.getSdCardPath(context)
                if (sdPath != null) {
                    if (hasStorageAccess()) onNavigate(NavState.Local(sdPath))
                    else openStoragePermissionSettings()
                } else android.widget.Toast.makeText(context, "No SD card found", android.widget.Toast.LENGTH_SHORT).show()
            }
            "Downloads" -> {
                if (hasStorageAccess()) onNavigate(NavState.Local("$base/Download"))
                else openStoragePermissionSettings()
            }
            "Images" -> {
                if (hasStorageAccess()) onNavigate(NavState.Category("Images"))
                else openStoragePermissionSettings()
            }
            "Audio"     -> { if (hasStorageAccess()) onNavigate(NavState.Category("Audio")) else openStoragePermissionSettings() }
            "Videos"    -> {
                if (hasStorageAccess()) onNavigate(NavState.Category("Videos"))
                else openStoragePermissionSettings()
            }
            "Documents" -> {
                if (hasStorageAccess()) onNavigate(NavState.Category("Documents"))
                else openStoragePermissionSettings()
            }
            "Apps" -> {
                onNavigate(NavState.AppManager)
            }
            "New files" -> {
                if (hasStorageAccess()) onNavigate(NavState.Category("New files"))
                else openStoragePermissionSettings()
            }
            "Cloud"     -> onNavigate(NavState.CloudAccounts)
            "Remote"    -> {
                onNavigate(NavState.RemoteConnections)
            }
            "Access from..." -> {
                onNavigate(NavState.FtpServer)
            }
            "Recycle Bin" -> {
                onNavigate(NavState.RecycleBin)
            }
        }
    }

    androidx.compose.foundation.lazy.LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White),
        contentPadding = PaddingValues(top = 6.dp, bottom = 8.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StorageTopCard(
                    label = "Main storage",
                    subtitle = mainStorageInfo.usedText,
                    icon = Icons.Default.Storage,
                    iconColor = Color(0xFF90A4AE),
                    modifier = Modifier.weight(1f),
                    onClick = { navigate("Main storage") }
                )
                StorageTopCard(
                    label = "SD card",
                    subtitle = if (sdInfo.total > 0) sdInfo.usedText else "Not found",
                    icon = Icons.Default.SdStorage,
                    iconColor = Color(0xFF5C6BC0),
                    modifier = Modifier.weight(1f),
                    onClick = { navigate("SD card") }
                )
                MyDriveTopCard(
                    selectedAccount = selectedDriveAccount,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        driveAccounts = CloudAccountManager.getAccounts(context)
                        if (driveAccounts.isEmpty()) {
                            onNavigate(NavState.CloudAccounts)
                        } else if (driveAccounts.size == 1) {
                            val acc = driveAccounts.first()
                            selectedDriveAccount = acc
                            drivePrefs.edit().putString("selected_account", acc).apply()
                            onNavigate(NavState.Cloud(acc, "root", "My Drive"))
                        } else {
                            showDrivePickerSheet = true
                        }
                    },
                    onLongClick = {
                        driveAccounts = CloudAccountManager.getAccounts(context)
                        if (driveAccounts.isNotEmpty()) showDrivePickerSheet = true
                        else onNavigate(NavState.CloudAccounts)
                    }
                )
            }
        }

        val categoryItems = listOf(
            GridItemData("Downloads", downloadsCount, Icons.Default.Download)    to Color(0xFFF57C00),
            GridItemData("Images",    imagesCount,   Icons.Default.Image)        to Color(0xFF8E24AA),
            GridItemData("Audio",     audioCount,    Icons.Default.Audiotrack)   to Color(0xFF00897B),
            GridItemData("Videos",    videosCount,   Icons.Default.VideoLibrary) to Color(0xFFE53935),
            GridItemData("Documents", docsCount,     Icons.Default.Description)  to Color(0xFF1976D2),
            GridItemData("Apps",      appsCount,     Icons.Default.Android)      to Color(0xFF8BC34A),
            GridItemData("New files", newFilesCount, Icons.Default.Schedule)     to Color(0xFF78909C),
            GridItemData("Cloud",     cloudCount,    Icons.Default.Cloud)        to Color(0xFF42A5F5),
            GridItemData("Remote",    "",            Icons.Default.Computer)     to Color(0xFF8D6E63),
            GridItemData("Access from...", "",       Icons.Default.Devices)      to Color(0xFF607D8B),
            GridItemData("Recycle Bin", "",          Icons.Default.Delete)       to Color(0xFF757575)
        )

        if (shortcutKeys.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "HOME SHORTCUTS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1976D2),
                        modifier = Modifier
                    )
                    Text(
                        text = "Edit",
                        fontSize = 13.sp,
                        color = Color(0xFF1976D2),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { showShortcutManager = true }
                    )
                }
                Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                    val shortcutItems = categoryItems.filter { (data, _) -> data.title in shortcutKeys }
                    for (row in shortcutItems.chunked(3)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            for ((data, color) in row) {
                                CategoryCard(
                                    label = data.title,
                                    subtitle = data.subtitle,
                                    icon = data.icon,
                                    iconColor = color,
                                    modifier = Modifier.weight(1f),
                                    onClick = { navigate(data.title) }
                                )
                            }
                            repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    color = Color(0xFFEEEEEE)
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(4.dp))
            Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                for (row in categoryItems.chunked(3)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        for ((data, color) in row) {
                            CategoryCard(
                                label = data.title,
                                subtitle = data.subtitle,
                                icon = data.icon,
                                iconColor = color,
                                modifier = Modifier.weight(1f),
                                onClick = { navigate(data.title) }
                            )
                        }
                        repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }

        // ── Scan Document button ─────────────────────────────────────────
        item {
            Button(
                onClick = { launchHomeScanner() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
            ) {
                Icon(
                    androidx.compose.material.icons.Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Scan Document",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
        }

        // ── Add Home Shortcut button ─────────────────────────────────────
        item {
            OutlinedButton(
                onClick = { createLauncherShortcut(context) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.5.dp, Color(0xFF1976D2))
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = Color(0xFF1976D2),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Add home shortcut",
                    color = Color(0xFF1976D2),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
        }
    }

    // ── Scanner Result Dialog (Home page) ────────────────────────────────
    if (mlKitResultImages.isNotEmpty()) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
        ModalBottomSheet(
            onDismissRequest = { if (!isProcessingMagicPro) mlKitResultImages = emptyList() },
            sheetState = sheetState,
            containerColor = Color.White,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Scanned ${mlKitResultImages.size} pages",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(24.dp))

                if (isProcessingMagicPro) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Processing images... Please wait.")
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(onClick = {
                            isProcessingMagicPro = true
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                try {
                                    val destDir = java.io.File(
                                        android.os.Environment.getExternalStoragePublicDirectory(
                                            android.os.Environment.DIRECTORY_DOCUMENTS
                                        ), "Scanned Documents"
                                    )
                                    if (!destDir.exists()) destDir.mkdirs()
                                    val destFile = java.io.File(destDir, "Scan_${System.currentTimeMillis()}.pdf")
                                    val tempFiles = mutableListOf<java.io.File>()
                                    for ((index, uri) in mlKitResultImages.withIndex()) {
                                        val tempFile = java.io.File(context.cacheDir, "home_scan_$index.jpg")
                                        context.contentResolver.openInputStream(uri)?.use { input ->
                                            java.io.FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                                        }
                                        tempFiles.add(tempFile)
                                    }
                                    val success = com.rasel.RasFocus.filemanager.PdfHelper.imagesToPdf(context, tempFiles, destFile)
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        Toast.makeText(context,
                                            if (success) "Saved to Documents/Scanned Documents" else "Failed to create PDF",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        mlKitResultImages = emptyList()
                                        isProcessingMagicPro = false
                                    }
                                } catch (e: Exception) {
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                        isProcessingMagicPro = false
                                    }
                                }
                            }
                        }) { Text("Save Original") }

                        Button(
                            onClick = {
                                isProcessingMagicPro = true
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    try {
                                        val destDir = java.io.File(
                                            android.os.Environment.getExternalStoragePublicDirectory(
                                                android.os.Environment.DIRECTORY_DOCUMENTS
                                            ), "Scanned Documents"
                                        )
                                        if (!destDir.exists()) destDir.mkdirs()
                                        val destFile = java.io.File(destDir, "Scan_MagicPro_${System.currentTimeMillis()}.pdf")
                                        val tempFiles = mutableListOf<java.io.File>()
                                        for ((index, uri) in mlKitResultImages.withIndex()) {
                                            val tempFile = java.io.File(context.cacheDir, "home_magic_$index.jpg")
                                            val bitmap = android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                                            val magicBitmap = com.rasel.RasFocus.filemanager.MagicProFilter.applyMagicProFilter(bitmap)
                                            java.io.FileOutputStream(tempFile).use { out ->
                                                magicBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                                            }
                                            tempFiles.add(tempFile)
                                            bitmap.recycle()
                                            magicBitmap.recycle()
                                        }
                                        val success = com.rasel.RasFocus.filemanager.PdfHelper.imagesToPdf(context, tempFiles, destFile)
                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            Toast.makeText(context,
                                                if (success) "Saved Magic Pro PDF to Documents" else "Failed to create PDF",
                                                Toast.LENGTH_LONG
                                            ).show()
                                            mlKitResultImages = emptyList()
                                            isProcessingMagicPro = false
                                        }
                                    } catch (e: Exception) {
                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                            isProcessingMagicPro = false
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0))
                        ) {
                            Icon(Icons.Default.AutoFixHigh, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Save Magic Pro")
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { mlKitResultImages = emptyList() }) {
                        Text("Discard All", color = Color.Red)
                    }
                }
            }
        }
    }

    // ── Home Shortcut Manager BottomSheet ─────────────────────────────────
    if (showShortcutManager) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showShortcutManager = false },
            sheetState = sheetState,
            containerColor = Color.White,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = "Manage Home Shortcuts",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "Tap to add or remove shortcuts from your home screen",
                    fontSize = 12.sp,
                    color = Color(0xFF9E9E9E),
                    modifier = Modifier.padding(bottom = 14.dp)
                )
                val allShortcutOptions = listOf(
                    GridItemData("Downloads", "", Icons.Default.Download)    to Color(0xFFF57C00),
                    GridItemData("Images",    "", Icons.Default.Image)       to Color(0xFF8E24AA),
                    GridItemData("Audio",     "", Icons.Default.Audiotrack)  to Color(0xFF00897B),
                    GridItemData("Videos",    "", Icons.Default.VideoLibrary)to Color(0xFFE53935),
                    GridItemData("Documents", "", Icons.Default.Description) to Color(0xFF1976D2),
                    GridItemData("Apps",      "", Icons.Default.Android)     to Color(0xFF8BC34A),
                    GridItemData("New files", "", Icons.Default.Schedule)    to Color(0xFF78909C),
                    GridItemData("Cloud",     "", Icons.Default.Cloud)       to Color(0xFF42A5F5),
                    GridItemData("Remote",    "", Icons.Default.Computer)    to Color(0xFF8D6E63),
                    GridItemData("Access from...", "", Icons.Default.Devices)to Color(0xFF607D8B),
                    GridItemData("Recycle Bin",   "", Icons.Default.Delete)  to Color(0xFF757575)
                )
                allShortcutOptions.forEach { (data, color) ->
                    val isActive = data.title in shortcutKeys
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val updated = if (isActive) {
                                    shortcutKeys - data.title
                                } else {
                                    shortcutKeys + data.title
                                }
                                shortcutKeys = updated
                                shortcutPrefs.edit().putStringSet("shortcuts", updated).apply()
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(color.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(data.icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(data.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isActive) Color(0xFF1976D2) else Color(0xFFE0E0E0)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isActive) {
                                Icon(Icons.Default.Check, contentDescription = "Selected", tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                    HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 0.5.dp)
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { showShortcutManager = false },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                ) {
                    Text("Done", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }

    // ── Drive Account Picker BottomSheet ──────────────────────────────────
    if (showDrivePickerSheet) {
        DriveAccountPickerSheet(
            accounts = driveAccounts,
            selectedAccount = selectedDriveAccount,
            onSelect = { account ->
                showDrivePickerSheet = false
                selectedDriveAccount = account
                drivePrefs.edit().putString("selected_account", account).apply()
                onNavigate(NavState.Cloud(account, "root", "My Drive"))
            },
            onAddAccount = {
                showDrivePickerSheet = false
                onNavigate(NavState.CloudAccounts)
            },
            onDismiss = { showDrivePickerSheet = false }
        )
    }
}

// ├óΓÇ¥Γé¼├óΓÇ¥Γé¼ Top storage card ├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼
// ΓöÇΓöÇ My Drive quick-access top card ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MyDriveTopCard(
    selectedAccount: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val driveBlue = Color(0xFF1A73E8)
    Column(
        modifier = modifier
            .combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = androidx.compose.material.ripple.rememberRipple(bounded = false),
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(top = 8.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.CloudQueue,
            contentDescription = "My Drive",
            tint = driveBlue,
            modifier = Modifier.size(52.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "My Drive",
            fontSize = 13.sp,
            color = Color(0xFF202020),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (selectedAccount != null) selectedAccount.substringBefore("@").take(12) else "Tap to connect",
            fontSize = 11.sp,
            color = Color(0xFF808080),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ΓöÇΓöÇ Drive Account Picker BottomSheet ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveAccountPickerSheet(
    accounts: List<String>,
    selectedAccount: String?,
    onSelect: (String) -> Unit,
    onAddAccount: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Select Drive Account",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            accounts.forEach { account ->
                val isSelected = account == selectedAccount
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(account) }
                        .background(
                            if (isSelected) Color(0xFF1A73E8).copy(alpha = 0.08f)
                            else Color.Transparent,
                            RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(19.dp))
                            .background(Color(0xFF1A73E8).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = account.firstOrNull()?.uppercaseChar()?.toString() ?: "G",
                            color = Color(0xFF1A73E8),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = account,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Google Drive",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                    if (isSelected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = Color(0xFF1A73E8),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                HorizontalDivider(color = Color(0xFFEEEEEE), thickness = 0.5.dp)
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAddAccount() }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .background(Color(0xFF43A047).copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Add account",
                        tint = Color(0xFF43A047),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Add another account",
                    fontSize = 14.sp,
                    color = Color(0xFF43A047),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun StorageTopCard(
    label: String,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = androidx.compose.material.ripple.rememberRipple(bounded = false),
                onClick = onClick
            )
            .padding(top = 8.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconColor,
            modifier = Modifier.size(42.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            color = Color(0xFF202020),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle.ifEmpty { " " },
            fontSize = 11.sp,
            color = Color(0xFF808080),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ├óΓÇ¥Γé¼├óΓÇ¥Γé¼ Category card ├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼├óΓÇ¥Γé¼
@Composable
fun CategoryCard(
    label: String,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = androidx.compose.material.ripple.rememberRipple(bounded = false),
                onClick = onClick
            )
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(Color.White, RoundedCornerShape(14.dp))
                .border(1.dp, Color(0xFFE8E8E8), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconColor,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            color = Color(0xFF202020),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle.ifEmpty { " " },
            fontSize = 11.sp,
            color = Color(0xFF808080),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// GridItemView legacy ├óΓé¼ΓÇ¥ replaced by CategoryCard and StorageTopCard
// GridItemView legacy ΓÇö replaced by CategoryCard and StorageTopCard

@Composable
fun DrawerContent(onNavigate: (NavState) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val safLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            SafFileManager.addUri(context, uri)
        }
    }
    
    // We want the UI to recompose when SafFileManager.grantedUris changes.
    // A simple way is to use a state that tracks its size.
    var safCount by remember { mutableStateOf(SafFileManager.grantedUris.size) }
    LaunchedEffect(SafFileManager.grantedUris.size) {
        safCount = SafFileManager.grantedUris.size
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF00796B))
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            Icon(Icons.Default.Folder, contentDescription = "Folder", tint = Color(0xFF49454F))
            Icon(Icons.Default.StarBorder, contentDescription = "Favorites", tint = Color.LightGray)
            Icon(Icons.Default.History, contentDescription = "History", tint = Color.LightGray)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            DrawerMenuItem(Icons.Default.Home, "Home", Color.Red, onClick = { onNavigate(NavState.Home) })

            val extInfo = remember { getExternalStorageInfo() }
            val sdCardInfo = remember { getSdCardStorageInfo(context) }
            DrawerStorageItem(
                Icons.Default.PhoneAndroid, "Main storage",
                if (extInfo.total > 0) "${(extInfo.progress * 100).toInt()}%" else "Ã¢â‚¬â€",
                extInfo.progress,
                onClick = { onNavigate(NavState.Local(LocalFileManager.mainStoragePath)) }
            )

            DrawerStorageItem(
                Icons.Default.SdStorage, "SD card",
                if (sdCardInfo.total > 0) "${(sdCardInfo.progress * 100).toInt()}%" else "Ã¢â‚¬â€",
                sdCardInfo.progress,
                onClick = {
                    val sdPath = LocalFileManager.getSdCardPath(context)
                    if (sdPath != null) {
                        onNavigate(NavState.Local(sdPath))
                    } else {
                        android.widget.Toast.makeText(context, "SD card not found", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            )

            DrawerMenuItem(Icons.Default.Delete, "Recycle Bin", Color.Gray, onClick = {
                onNavigate(NavState.RecycleBin)
            })
            DrawerMenuItem(Icons.Default.Lock, "Secure Vault", Color(0xFF7B1FA2), onClick = {
                onNavigate(NavState.SecureVault)
            })
            DrawerMenuItem(Icons.Default.PieChart, "Storage Analyzer", Color(0xFF00796B), onClick = {
                onNavigate(NavState.StorageAnalyzer)
            })
            DrawerMenuItem(Icons.Default.Apps, "App Manager", Color(0xFF1565C0), onClick = {
                onNavigate(NavState.AppManager)
            })

            Divider()

            DrawerMenuItem(Icons.Default.CloudQueue, "Google Drive", Color.Blue, isPinned = true, onClick = { onNavigate(NavState.CloudAccounts) })
            DrawerMenuItem(Icons.Default.CloudSync, "Manage Offline Files", Color(0xFF1A73E8), onClick = { onNavigate(NavState.DriveOfflineSettings) })
            DrawerMenuItem(Icons.Default.FolderOpen, "Main storage /Download", Color.Gray, isPinned = true, onClick = { onNavigate(NavState.Local(LocalFileManager.mainStoragePath + "/Download")) })

            Divider()

            DrawerMenuItem(Icons.Default.Schedule, "New files", Color.Gray, onClick = {
                onNavigate(NavState.Local(LocalFileManager.mainStoragePath + "/Download"))
            })
            DrawerMenuItem(Icons.Default.Download, "Downloads", Color(0xFFFFA500), onClick = {
                onNavigate(NavState.Local(LocalFileManager.mainStoragePath + "/Download"))
            })
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "UPGRADE TO PREMIUM",
                color = Color(0xFF00796B),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
fun DrawerMenuItem(
    icon: ImageVector,
    title: String,
    iconTint: Color,
    trailingText: String? = null,
    isPinned: Boolean = false,
    hasMoreVert: Boolean = false,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = title, tint = iconTint, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = title, modifier = Modifier.weight(1f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)

        if (trailingText != null) {
            Text(text = trailingText, color = Color.Gray, fontSize = 12.sp)
        }
        if (isPinned) {
            Icon(Icons.Default.PushPin, contentDescription = "Pinned", tint = Color(0xFF00796B), modifier = Modifier.size(18.dp))
        }
        if (hasMoreVert) {
            Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.Gray, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun DrawerStorageItem(icon: ImageVector, title: String, percentageText: String, progress: Float, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = title, tint = Color.DarkGray, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = title, fontSize = 15.sp)
                Text(text = percentageText, fontSize = 13.sp, color = Color.Gray)
            }
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = Color(0xFF1976D2),
                trackColor = Color.LightGray,
            )
        }
    }
}

data class GridItemData(
    val title: String,
    val subtitle: String,
    val icon: ImageVector
)

// ─────────────────────────────────────────────────────────────────────────────
// DocxToPdfViewerScreen
// Silently converts .docx → PDF in background, then opens PDF viewer.
// User only sees a loading spinner during conversion.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun DocxToPdfViewerScreen(docxPath: String, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var pdfPath   by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var hasError  by remember { mutableStateOf(false) }

    LaunchedEffect(docxPath) {
        val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
            convertDocxToPdf(context, docxPath)
        }
        if (result != null) {
            pdfPath = result.absolutePath
        } else {
            hasError = true
        }
        isLoading = false
    }

    when {
        isLoading -> {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize()
                    .background(Color(0xFFFAFAFA)),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFF1565C0),
                        strokeWidth = 2.5.dp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        "ডকুমেন্ট লোড হচ্ছে…",
                        fontSize = 13.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        }
        hasError || pdfPath == null -> {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize()
                    .background(Color(0xFFFAFAFA)),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Text("📄", fontSize = 52.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "ডকুমেন্ট খুলতে পারিনি।",
                        fontSize = 14.sp,
                        color = Color(0xFFCC3333)
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    OutlinedButton(onClick = onBack) { Text("ফিরে যাও") }
                }
            }
        }
        else -> {
            FMPdfViewerScreen(
                filePath = pdfPath!!,
                onBack   = onBack
            )
        }
    }
}



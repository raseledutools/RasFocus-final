package com.rasel.RasFocus.filemanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider

sealed class NavState {
    object Home : NavState()
    data class Local(val path: String) : NavState()
    data class Category(val type: String) : NavState()
    object CloudAccounts : NavState()
    data class Cloud(val accountName: String, val folderId: String, val pathName: String) : NavState()
    data class Remote(val serverId: String, val path: String) : NavState()
    object RecycleBin : NavState()
    object SecureVault : NavState()
    object StorageAnalyzer : NavState()
    object AppManager : NavState()
    object DriveOfflineSettings : NavState()
    data class TextEditor(val path: String) : NavState()
    data class Saf(val uri: String) : NavState()
}

// ── Shared utility functions ───────────────────────────────────────────────────
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

fun openLocalFile(context: android.content.Context, file: java.io.File, onNavigate: ((NavState) -> Unit)? = null) {
    try {
        val ext = file.extension.lowercase()
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        // ── Known types: directly launch RasFocus internal viewers ──
        // Skips the system "Open with" chooser → always gets in-app viewer
        // (pdfium for PDF, ImageViewerActivity for images, etc.)
        val internalMime: String? = when (ext) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heic"
            "docx", "doc" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "pptx", "ppt" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "xlsx", "xls" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "txt", "md", "kt", "java", "py", "js", "ts", "json", "xml", "csv",
            "html", "css", "sh", "c", "cpp", "h", "rs", "go", "rb", "yaml", "yml" -> "text/plain"
            else -> null
        }

        if (internalMime != null) {
            if (internalMime == "text/plain" && onNavigate != null) {
                onNavigate(NavState.TextEditor(file.absolutePath))
                return
            }

            // Route through UniversalViewerActivity → correct internal viewer
            val pkg = context.packageName.replace(".combo", "")
            val cls = try {
                Class.forName("$pkg.selfcontrol.study_tools.UniversalViewerActivity")
            } catch (_: ClassNotFoundException) { null }
            if (cls != null) {
                val intent = android.content.Intent(context, cls).apply {
                    action = android.content.Intent.ACTION_VIEW
                    setDataAndType(uri, internalMime)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            }
        }

        // ── Fallback: system chooser for unknown/unsupported types ──
        val mimeType = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext) ?: "*/*"
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Cannot open file: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

fun shareLocalFile(context: android.content.Context, file: java.io.File) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val ext = file.extension.lowercase()
        val mimeType = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext) ?: "*/*"
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Share ${file.name}"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Cannot share file: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

fun shareLocalFiles(context: android.content.Context, files: List<java.io.File>) {
    if (files.size == 1) {
        shareLocalFile(context, files.first())
        return
    }
    try {
        val uris = ArrayList(files.map { file ->
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        })
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Share ${files.size} files"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Cannot share files: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

data class ClipboardState(
    val sourceEnv: String, // "Local" or "Cloud"
    val items: List<String>, // paths or fileIds
    val itemNames: List<String> = emptyList(), // For Cloud files
    val itemMimeTypes: List<String> = emptyList(), // For Cloud: mimeType per item
    val isCut: Boolean = false,
    val accountName: String? = null
)

// Sort options
enum class SortMode { NAME_ASC, NAME_DESC, DATE_ASC, DATE_DESC, SIZE_ASC, SIZE_DESC }

class FileManagerPlusActivity : ComponentActivity() {

    private val legacyPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        android.util.Log.d("FileManagerPlusActivity", "Permission result: READ_EXTERNAL_STORAGE granted=$granted")
        if (granted) {
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SafFileManager.init(this)
        requestStoragePermissionIfNeeded()
        setContent {
            MaterialTheme {
                HomeScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            // permission à¦à¦–à¦¨ à¦†à¦›à§‡
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
fun HomeScreen() {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var currentNavState by remember { mutableStateOf<NavState>(NavState.Home) }
    var clipboard by remember { mutableStateOf<ClipboardState?>(null) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(SortMode.NAME_ASC) }
    var showSearchBar by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Cloud subfolder backstack: list of (folderId, pathName) pairs
    // â”€â”€â”€ Cloud backstack â€” subfolder à¦¥à§‡à¦•à§‡ proper back navigation à¦à¦° à¦œà¦¨à§à¦¯ â”€â”€â”€â”€â”€â”€
    val cloudBackStack = remember { mutableStateListOf<Pair<String, String>>() }

    // â”€â”€â”€ BackHandler â€” Android system back button â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
            currentNavState is NavState.TextEditor ||
            currentNavState is NavState.Category -> {
                currentNavState = NavState.Home
            }
            currentNavState is NavState.Cloud -> {
                val state = currentNavState as NavState.Cloud
                if (cloudBackStack.isNotEmpty()) {
                    val prev = cloudBackStack.removeLast()
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
                    parent.absolutePath == storageRoot ||
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
            currentNavState == NavState.Home && !drawerState.isOpen -> {
                // Home screen এ back করলে activity finish করো (app থেকে বের হওয়া)
                activity?.finish()
            }
            else -> {
                currentNavState = NavState.Home
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                DrawerContent(
                    onNavigate = { newState ->
                        cloudBackStack.clear()
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
                    currentNavState != NavState.RecycleBin &&
                    currentNavState != NavState.SecureVault &&
                    currentNavState != NavState.StorageAnalyzer &&
                    currentNavState != NavState.AppManager &&
                    currentNavState != NavState.DriveOfflineSettings
                if (needsGlobalHeader) {
                    if (showSearchBar) {
                    // â”€â”€ Search bar â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
                                    is NavState.TextEditor -> state.path.substringAfterLast("/")
                                    is NavState.Saf -> state.uri.substringAfterLast("%2F").substringAfterLast("/")
                                },
                                color = Color(0xFF1D1B20).copy(alpha = 0.9f), fontWeight = FontWeight.Bold, fontSize = 22.sp
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF7F9FC), titleContentColor = Color(0xFF1D1B20)),
                        navigationIcon = {
                            IconButton(onClick = {
                                if (currentNavState != NavState.Home) {
                                    // Back navigation â€” same as BackHandler
                                    when {
                                        currentNavState is NavState.Cloud -> {
                                            val state = currentNavState as NavState.Cloud
                                            if (cloudBackStack.isNotEmpty()) {
                                                val prev = cloudBackStack.removeLast()
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
                                            val parent = java.io.File(state.path).parent
                                            if (parent != null && parent.contains("0")) {
                                                currentNavState = NavState.Local(parent)
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
                            // Search icon â€” Home à¦›à¦¾à¦¡à¦¼à¦¾ à¦¸à¦¬ à¦œà¦¾à¦¯à¦¼à¦—à¦¾à¦¯à¦¼ à¦¦à§‡à¦–à¦¾à¦¬à§‡
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
                                        text = { Text("Sort by name â†‘", fontWeight = if (sortMode == SortMode.NAME_ASC) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = { showMoreMenu = false; sortMode = SortMode.NAME_ASC }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sort by name â†“", fontWeight = if (sortMode == SortMode.NAME_DESC) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = { showMoreMenu = false; sortMode = SortMode.NAME_DESC }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sort by date â†‘", fontWeight = if (sortMode == SortMode.DATE_ASC) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = { showMoreMenu = false; sortMode = SortMode.DATE_ASC }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sort by date â†“", fontWeight = if (sortMode == SortMode.DATE_DESC) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = { showMoreMenu = false; sortMode = SortMode.DATE_DESC }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sort by size â†‘", fontWeight = if (sortMode == SortMode.SIZE_ASC) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = { showMoreMenu = false; sortMode = SortMode.SIZE_ASC }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sort by size â†“", fontWeight = if (sortMode == SortMode.SIZE_DESC) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = { showMoreMenu = false; sortMode = SortMode.SIZE_DESC }
                                    )
                                    Divider()
                                    DropdownMenuItem(
                                        text = { Text("Settings") },
                                        onClick = {
                                            showMoreMenu = false
                                            android.widget.Toast.makeText(context, "Settings coming soon", android.widget.Toast.LENGTH_SHORT).show()
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
            Box(modifier = Modifier.padding(paddingValues)) {
                when (val state = currentNavState) {
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
                                parent.absolutePath == storageRoot ||
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
                            // subfolder navigate à¦•à¦°à¦¾à¦° à¦¸à¦®à¦¯à¦¼ current state à¦•à§‡ backstack à¦ push à¦•à¦°à§‹
                            if (newState is NavState.Cloud) {
                                cloudBackStack.add(Pair(state.folderId, state.pathName))
                            }
                            currentNavState = newState
                        },
                        onBack = {
                            if (cloudBackStack.isNotEmpty()) {
                                val prev = cloudBackStack.removeLast()
                                currentNavState = NavState.Cloud(state.accountName, prev.first, prev.second)
                            } else if (state.folderId == "root") {
                                cloudBackStack.clear()
                                currentNavState = NavState.CloudAccounts
                            } else {
                                cloudBackStack.clear()
                                currentNavState = NavState.Cloud(state.accountName, "root", "My Drive")
                            }
                        },
                        clipboard = clipboard,
                        onSetClipboard = { clipboard = it },
                        sortMode = sortMode,
                        searchQuery = if (showSearchBar) searchQuery else ""
                    )
                    is NavState.Remote -> RemoteFileScreen(
                        serverId = state.serverId,
                        initialPath = state.path,
                        onNavigate = { newState -> currentNavState = newState },
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
                    is NavState.TextEditor -> TextEditorScreen(
                        path = state.path,
                        onBack = { 
                            // Go back to the directory containing this file
                            val parent = java.io.File(state.path).parent
                            if (parent != null) {
                                currentNavState = NavState.Local(parent)
                            } else {
                                currentNavState = NavState.Home 
                            }
                        }
                    )
                    is NavState.Saf -> SafFileScreen(
                        uriString = state.uri,
                        onNavigate = { newState -> currentNavState = newState },
                        onBack = { currentNavState = NavState.Home }
                    )
                }
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
        // Environment.getDataDirectory() â†’ /data partition (true internal storage)
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

@Composable
fun MainGridContent(modifier: Modifier = Modifier, onNavigate: (NavState) -> Unit) {
    val context = LocalContext.current
    var internalInfo by remember { mutableStateOf(StorageInfo(0, 0)) }
    var externalInfo by remember { mutableStateOf(StorageInfo(0, 0)) }
    var sdInfo by remember { mutableStateOf(StorageInfo(0, 0)) }
    val scope = rememberCoroutineScope()

    // ── My Drive quick-access state ───────────────────────────────────────────
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
            
            // Format stat string
            fun formatStats(stats: Pair<Int, Long>): String {
                val (count, size) = stats
                val sizeStr = if (size > 0) formatFileSize(size) else ""
                return if (sizeStr.isNotEmpty()) "$sizeStr ($count)" else if (count > 0) "($count)" else ""
            }

            downloadsCount = formatStats(CategoryUtils.getCategoryStats(context, "Downloads").let {
                // For downloads, we still prefer direct file count from the Download directory
                // because MediaStore doesn't have a reliable single "Downloads" category on all OS versions.
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
                onNavigate(NavState.CloudAccounts)
                android.widget.Toast.makeText(context, "Use Cloud to access remote files", android.widget.Toast.LENGTH_SHORT).show()
            }
            "Access from..." -> {
                // Open Downloads as access point
                if (hasStorageAccess()) onNavigate(NavState.Local("$base/Download"))
                else openStoragePermissionSettings()
            }
        }
    }

    androidx.compose.foundation.lazy.LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF7F9FC)),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
                item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Storage Locations",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF1D1B20),
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                )

                StorageTopCard(
                    label = "Internal Storage",
                    subtitle = mainStorageInfo.usedText,
                    icon = Icons.Default.PhoneAndroid,
                    iconColor = Color(0xFF6750A4),
                    progress = mainStorageInfo.progress,
                    onClick = { navigate("Main storage") }
                )
                
                if (sdInfo.total > 0) {
                    StorageTopCard(
                        label = "SD Card",
                        subtitle = sdInfo.usedText,
                        icon = Icons.Default.SdStorage,
                        iconColor = Color(0xFF006874),
                        progress = sdInfo.progress,
                        onClick = { navigate("SD card") }
                    )
                }

                MyDriveTopCard(
                    selectedAccount = selectedDriveAccount,
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

        item {
            Text(
                text = "Categories",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF1D1B20),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp)
            )
        }

        val categoryItems = listOf(
            GridItemData("Images",    imagesCount,   Icons.Default.Image)        to Color(0xFFAD1457),
            GridItemData("Audio",     audioCount,    Icons.Default.Audiotrack)   to Color(0xFF1565C0),
            GridItemData("Videos",    videosCount,   Icons.Default.VideoLibrary) to Color(0xFF6A1B9A),
            GridItemData("Documents", docsCount,     Icons.Default.Description)  to Color(0xFF37474F),
            GridItemData("Apps",      appsCount,     Icons.Default.Android)      to Color(0xFF00838F),
            GridItemData("New files", newFilesCount, Icons.Default.Schedule)     to Color(0xFF4E342E),
        )

        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                for (row in categoryItems.chunked(2)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                        repeat(2 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }

                item {
            Text(
                text = "More Options",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF1D1B20),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp)
            )
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CategoryCard(
                    label = "Cloud",
                    subtitle = cloudCount,
                    icon = Icons.Default.Cloud,
                    iconColor = Color(0xFF006874),
                    modifier = Modifier.weight(1f),
                    onClick = { navigate("Cloud") }
                )
                CategoryCard(
                    label = "Remote",
                    subtitle = "",
                    icon = Icons.Default.Computer,
                    iconColor = Color(0xFF984061),
                    modifier = Modifier.weight(1f),
                    onClick = { navigate("Remote") }
                )
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CategoryCard(
                    label = "Access from...",
                    subtitle = "",
                    icon = Icons.Default.Devices,
                    iconColor = Color(0xFF4E444B),
                    modifier = Modifier.weight(1f),
                    onClick = { navigate("Access from...") }
                )
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }

    // ── Drive Account Picker BottomSheet ──────────────────────────────────────
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

// â”€â”€ Top storage card â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// ── My Drive quick-access top card ───────────────────────────────────────────
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MyDriveTopCard(
    selectedAccount: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val driveBlue = Color(0xFF1A73E8)
    Card(
        modifier = modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(driveBlue.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CloudQueue,
                    contentDescription = "My Drive",
                    tint = driveBlue,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "My Drive",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (selectedAccount != null)
                    selectedAccount.substringBefore("@").take(12)
                else
                    "Tap to connect",
                fontSize = 10.sp,
                color = if (selectedAccount != null) driveBlue else Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ── Drive Account Picker BottomSheet ──────────────────────────────────────────
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
    progress: Float,
    modifier: Modifier = Modifier,
    showProgress: Boolean = true,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable { onClick() }.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp) // Flat but distinct
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(iconColor.copy(alpha=0.2f), iconColor.copy(alpha=0.05f))
                        )),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = iconColor,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1D1B20),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            fontSize = 12.sp,
                            color = Color(0xFF49454F),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (showProgress && progress > 0f) {
                Spacer(Modifier.height(14.dp))
                // Custom gradient progress bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(iconColor.copy(alpha = 0.1f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(
                                listOf(iconColor.copy(alpha=0.6f), iconColor)
                            ))
                    )
                }
            }
        }
    }
}
// â”€â”€ Category card â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
@Composable
fun CategoryCard(
    label: String,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(iconColor.copy(alpha=0.15f), iconColor.copy(alpha=0.05f))
                    )),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = iconColor,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1D1B20),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = Color(0xFF49454F),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}


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
                if (extInfo.total > 0) "${(extInfo.progress * 100).toInt()}%" else "â€”",
                extInfo.progress,
                onClick = { onNavigate(NavState.Local(LocalFileManager.mainStoragePath)) }
            )

            DrawerStorageItem(
                Icons.Default.SdStorage, "SD card",
                if (sdCardInfo.total > 0) "${(sdCardInfo.progress * 100).toInt()}%" else "â€”",
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



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
    object RemoteConnections : NavState()
    data class Remote(val serverId: String, val path: String) : NavState()
    data class P2PChat(val deviceName: String, val ip: String, val port: Int) : NavState()
    object RecycleBin : NavState()
    object SecureVault : NavState()
    object StorageAnalyzer : NavState()
    object AppManager : NavState()
    object DriveOfflineSettings : NavState()
    object FtpServer : NavState()
    data class TextEditor(val path: String) : NavState()
    data class Saf(val uri: String) : NavState()
}

// â”€â”€ Shared utility functions â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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

        // Text/code files → in-app TextEditor (no Activity needed)
        if (ext in setOf("txt","md","kt","java","py","js","ts","json","xml","csv",
                         "html","css","sh","c","cpp","h","rs","go","rb","yaml","yml")) {
            if (onNavigate != null) {
                onNavigate(NavState.TextEditor(file.absolutePath))
                return
            }
        }

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        // Known viewer types → UniversalViewerActivity (which dispatches to the
        // correct internal viewer: PdfViewerActivity, ImageViewerActivity, etc.)
        // Using UniversalViewerActivity instead of Class.forName(PdfViewerActivity)
        // because PdfViewerActivity only exists in the `full` flavor source set and
        // Class.forName() fails at runtime when the classloader context is wrong.
        // UniversalViewerActivity is in main/ so it is always resolvable.
        //
        // taskAffinity="${applicationId}.filemanager" is set on BOTH
        // UniversalViewerActivity AND all downstream viewers in the manifest,
        // so the whole chain stays in the file manager task and back-press
        // correctly returns to the file browser.
        val mimeType: String? = when (ext) {
            "pdf"         -> "application/pdf"
            "jpg","jpeg"  -> "image/jpeg"
            "png"         -> "image/png"
            "webp"        -> "image/webp"
            "gif"         -> "image/gif"
            "bmp"         -> "image/bmp"
            "heic","heif" -> "image/heic"
            "docx","doc"  -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "pptx","ppt"  -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "xlsx","xls"  -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            else          -> null
        }

        if (mimeType != null) {
            val intent = android.content.Intent(context,
                com.rasel.RasFocus.selfcontrol.study_tools.UniversalViewerActivity::class.java).apply {
                action = android.content.Intent.ACTION_VIEW
                setDataAndType(uri, mimeType)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (uri.scheme == "content") {
                    clipData = android.content.ClipData.newRawUri("", uri)
                }
            }
            context.startActivity(intent)
            return
        }

        // Fallback: system chooser for unsupported types
        val fallbackMime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext) ?: "*/*"
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, fallbackMime)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Cannot open file: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
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
            // permission Ã Â¦ÂÃ Â¦â€“Ã Â¦Â¨ Ã Â¦â€ Ã Â¦â€ºÃ Â§â€¡
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
    // â”€â”€â”€ Cloud backstack â”€ subfolder à¦¥à§‡à¦•à§‡ proper back navigation à¦ à¦° à¦œà¦¨à§à¦¯ â”€â”€â”€â”€â”€â”€â”€
    val cloudBackStack = remember { mutableStateListOf<Pair<String, String>>() }

    // SAF/eDrive subfolder backstack â€” each entry is the parent-folder URI string
    val safBackStack = remember { mutableStateListOf<String>() }

    // P2P Auto-Discovery and Connection
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

    // â”€â”€â”€ BackHandler â”€ Android system back button â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
            currentNavState is NavState.P2PChat ||
            currentNavState is NavState.Category -> {
                currentNavState = NavState.Home
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
            currentNavState is NavState.Saf -> {
                // SAF/eDrive subfolder back — pop the SAF backstack instead of going to Home
                if (safBackStack.isNotEmpty()) {
                    currentNavState = NavState.Saf(safBackStack.removeAt(safBackStack.size - 1))
                } else {
                    safBackStack.clear()
                    currentNavState = NavState.Home
                }
            }
            currentNavState == NavState.Home && !drawerState.isOpen -> {
                // Home screen à¦ back à¦•à¦°à¦²à§‡ activity finish à¦•à¦°à§‹ (app à¦¥à§‡à¦•à§‡ à¦¬à§‡à¦° à¦¹à¦“à¦¯à¦¼à¦¾)
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
                    currentNavState != NavState.RecycleBin &&
                    currentNavState != NavState.SecureVault &&
                    currentNavState != NavState.StorageAnalyzer &&
                    currentNavState != NavState.AppManager &&
                    currentNavState != NavState.DriveOfflineSettings &&
                    currentNavState != NavState.FtpServer &&
                    currentNavState != NavState.RemoteConnections
                if (needsGlobalHeader) {
                    if (showSearchBar) {
                    // Ã¢â€ â‚¬Ã¢â€ â‚¬ Search bar Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬Ã¢â€ â‚¬
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
                                    is NavState.FtpServer -> "Access from PC"
                                    is NavState.RemoteConnections -> "Remote Connections"
                                    is NavState.P2PChat -> "Chat with ${state.deviceName}"
                                    is NavState.TextEditor -> state.path.substringAfterLast("/")
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
                                    // Back navigation Ã¢â‚¬â€  same as BackHandler
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
                            // Search icon Ã¢â‚¬â€  Home Ã Â¦â€ºÃ Â¦Â¾Ã Â¦Â¡Ã Â¦Â¼Ã Â¦Â¾ Ã Â¦Â¸Ã Â¦Â¬ Ã Â¦Å“Ã Â¦Â¾Ã Â¦Â¯Ã Â¦Â¼Ã Â¦â€”Ã Â¦Â¾Ã Â¦Â¯Ã Â¦Â¼ Ã Â¦Â¦Ã Â§â€¡Ã Â¦â€“Ã Â¦Â¾Ã Â¦Â¬Ã Â§â€¡
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
                                        text = { Text("Sort by name Ã¢â€ â€˜", fontWeight = if (sortMode == SortMode.NAME_ASC) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = { showMoreMenu = false; sortMode = SortMode.NAME_ASC }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sort by name Ã¢â€ â€œ", fontWeight = if (sortMode == SortMode.NAME_DESC) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = { showMoreMenu = false; sortMode = SortMode.NAME_DESC }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sort by date Ã¢â€ â€˜", fontWeight = if (sortMode == SortMode.DATE_ASC) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = { showMoreMenu = false; sortMode = SortMode.DATE_ASC }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sort by date Ã¢â€ â€œ", fontWeight = if (sortMode == SortMode.DATE_DESC) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = { showMoreMenu = false; sortMode = SortMode.DATE_DESC }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sort by size Ã¢â€ â€˜", fontWeight = if (sortMode == SortMode.SIZE_ASC) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = { showMoreMenu = false; sortMode = SortMode.SIZE_ASC }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Sort by size Ã¢â€ â€œ", fontWeight = if (sortMode == SortMode.SIZE_DESC) FontWeight.Bold else FontWeight.Normal) },
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
                            // subfolder navigate Ã Â¦â€¢Ã Â¦Â°Ã Â¦Â¾Ã Â¦Â° Ã Â¦Â¸Ã Â¦Â®Ã Â¦Â¯Ã Â¦Â¼ current state Ã Â¦â€¢Ã Â§â€¡ backstack Ã Â¦Â  push Ã Â¦â€¢Ã Â¦Â°Ã Â§â€¹
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
                                // Launch FTP browser to that device's IP (assuming they host FTP on 2121)
                                // We could use Smb or FTP. Assuming FTP on 2121 for now.
                                val tempServerId = "p2p_ftp_${state.ip}"
                                if (RemoteStore.servers.none { it.id == tempServerId }) {
                                    RemoteStore.servers.add(RemoteServer(tempServerId, state.deviceName, state.ip, 2121, "anonymous", ""))
                                }
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
                        onNavigate = { newState ->
                            // Push current URI onto backstack before navigating deeper
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
        // Environment.getDataDirectory() Ã¢â€ â€™ /data partition (true internal storage)
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

    // ΓöÇΓöÇ My Drive quick-access state ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ
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
                onNavigate(NavState.RemoteConnections)
            }
            "Access from..." -> {
                onNavigate(NavState.FtpServer)
            }
        }
    }

    androidx.compose.foundation.lazy.LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF2F2F7)),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StorageTopCard(
                    label = "Main storage",
                    subtitle = mainStorageInfo.usedText,
                    icon = Icons.Default.PhoneAndroid,
                    iconColor = Color(0xFF5C6BC0),
                    progress = mainStorageInfo.progress,
                    modifier = Modifier.weight(1f),
                    onClick = { navigate("Main storage") }
                )
                StorageTopCard(
                    label = "SD card",
                    subtitle = if (sdInfo.total > 0) sdInfo.usedText else "Not found",
                    icon = Icons.Default.SdStorage,
                    iconColor = Color(0xFF388E3C),
                    progress = sdInfo.progress,
                    modifier = Modifier.weight(1f),
                    onClick = { navigate("SD card") }
                )
                MyDriveTopCard(
                    selectedAccount = selectedDriveAccount,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        driveAccounts = CloudAccountManager.getAccounts(context)
                        if (driveAccounts.isEmpty()) {
                            // αªòαºïαª¿αºï account αª¿αºçαªç ΓåÆ add αªòαª░αªñαºç CloudAccounts αªÅ αª»αª╛αªô
                            onNavigate(NavState.CloudAccounts)
                        } else if (driveAccounts.size == 1) {
                            // Single account ΓåÆ directly open
                            val acc = driveAccounts.first()
                            selectedDriveAccount = acc
                            drivePrefs.edit().putString("selected_account", acc).apply()
                            onNavigate(NavState.Cloud(acc, "root", "My Drive"))
                        } else {
                            // Multiple accounts ΓåÆ show picker
                            showDrivePickerSheet = true
                        }
                    },
                    onLongClick = {
                        // Long press ΓåÆ always show picker to change account
                        driveAccounts = CloudAccountManager.getAccounts(context)
                        if (driveAccounts.isNotEmpty()) showDrivePickerSheet = true
                        else onNavigate(NavState.CloudAccounts)
                    }
                )
            }
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
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CategoryCard(
                    label = "Cloud",
                    subtitle = cloudCount,
                    icon = Icons.Default.Cloud,
                    iconColor = Color(0xFF1565C0),
                    modifier = Modifier.weight(1f),
                    onClick = { navigate("Cloud") }
                )
                CategoryCard(
                    label = "Remote",
                    subtitle = "",
                    icon = Icons.Default.Computer,
                    iconColor = Color(0xFF4E342E),
                    modifier = Modifier.weight(1f),
                    onClick = { navigate("Remote") }
                )
                CategoryCard(
                    label = "Access from...",
                    subtitle = "",
                    icon = Icons.Default.Devices,
                    iconColor = Color(0xFF37474F),
                    modifier = Modifier.weight(1f),
                    onClick = { navigate("Access from...") }
                )
            }
        }
    }

    // ΓöÇΓöÇ Drive Account Picker BottomSheet ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ
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
    progress: Float,
    modifier: Modifier = Modifier,
    showProgress: Boolean = true,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable { onClick() },
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
                    .background(iconColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = iconColor,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (showProgress && progress > 0f) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = if (progress > 0.9f) Color(0xFFE53935) else iconColor,
                    trackColor = iconColor.copy(alpha = 0.15f)
                )
            }
        }
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
    Card(
        modifier = modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = iconColor,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
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



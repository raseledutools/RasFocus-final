package com.rasel.RasFocus.filemanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.text.style.TextAlign
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

sealed class NavState {
    object Home : NavState()
    data class Local(val path: String) : NavState()
    object CloudAccounts : NavState()
    data class Cloud(val accountName: String, val folderId: String, val pathName: String) : NavState()
}

data class ClipboardState(
    val sourceEnv: String, // "Local" or "Cloud"
    val items: List<String>, // paths or fileIds
    val itemNames: List<String> = emptyList(), // For Cloud files
    val isCut: Boolean = false,
    val accountName: String? = null
)

class FileManagerPlusActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                HomeScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentNavState by remember { mutableStateOf<NavState>(NavState.Home) }
    var clipboard by remember { mutableStateOf<ClipboardState?>(null) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                DrawerContent(
                    onNavigate = { newState ->
                        currentNavState = newState
                        scope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { 
                        Text(
                            text = when (val state = currentNavState) {
                                is NavState.Home -> "File Manager +"
                                is NavState.Local -> state.path.substringAfterLast("/")
                                is NavState.CloudAccounts -> "Cloud Locations"
                                is NavState.Cloud -> state.pathName
                            }, 
                            color = Color.White
                        ) 
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF1E1E1E) // Dark background from image_5b8f74.jpg
                    ),
                    navigationIcon = {
                        IconButton(onClick = { 
                            if (currentNavState != NavState.Home) {
                                currentNavState = NavState.Home
                            } else {
                                scope.launch { drawerState.open() }
                            }
                        }) {
                            Icon(
                                imageVector = if (currentNavState == NavState.Home) Icons.Default.Menu else Icons.Default.ArrowBack, 
                                contentDescription = "Menu/Back", 
                                tint = Color.White
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { /* Premium Action */ }) {
                            Icon(Icons.Default.Star, contentDescription = "Premium", tint = Color(0xFFFFA500))
                        }
                        IconButton(onClick = { /* More Options */ }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White)
                        }
                    }
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            Box(modifier = Modifier.padding(paddingValues)) {
                when (val state = currentNavState) {
                    is NavState.Home -> MainGridContent(
                        onNavigate = { currentNavState = it }
                    )
                    is NavState.Local -> LocalFileScreen(
                        path = state.path, 
                        onNavigate = { currentNavState = it },
                        onBack = { 
                            val parent = java.io.File(state.path).parent
                            if (parent != null && parent.contains("0")) { // very basic check
                                currentNavState = NavState.Local(parent)
                            } else {
                                currentNavState = NavState.Home
                            }
                        },
                        clipboard = clipboard,
                        onSetClipboard = { clipboard = it }
                    )
                    is NavState.CloudAccounts -> CloudAccountsScreen(
                        onAccountSelected = { accountName ->
                            currentNavState = NavState.Cloud(accountName, "root", "My Drive")
                        }
                    )
                    is NavState.Cloud -> CloudFileScreen(
                        accountName = state.accountName,
                        folderId = state.folderId,
                        pathName = state.pathName,
                        onNavigate = { newState -> currentNavState = newState },
                        onBack = { 
                            if (state.folderId == "root") {
                                currentNavState = NavState.CloudAccounts
                            } else {
                                // Ideally we'd maintain a backstack of folderIds. For now, go back to root or Accounts.
                                currentNavState = NavState.Cloud(state.accountName, "root", "My Drive")
                            }
                        },
                        clipboard = clipboard,
                        onSetClipboard = { clipboard = it }
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
        ) { permissions ->
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

    // Colored icons matching common file managers
    val gridItems = listOf(
        Triple("Internal storage", internalInfo.usedText, Icons.Default.PhoneAndroid),
        Triple("Main storage", externalInfo.usedText, Icons.Default.Storage),
        Triple("SD card", sdInfo.usedText, Icons.Default.SdStorage),
        Triple("Downloads", "", Icons.Default.Download),
        Triple("Images", "", Icons.Default.Image),
        Triple("Audio", "", Icons.Default.Audiotrack),
        Triple("Videos", "", Icons.Default.VideoLibrary),
        Triple("Documents", "", Icons.Default.Description),
        Triple("Apps", "", Icons.Default.Android),
        Triple("New files", "", Icons.Default.Schedule),
        Triple("Cloud", "", Icons.Default.Cloud),
        Triple("Remote", "", Icons.Default.Computer)
    )

    val iconColors = listOf(
        Color(0xFF5C6BC0), // Internal storage — indigo
        Color(0xFF26A69A), // Main storage — teal
        Color(0xFF66BB6A), // SD card — green
        Color(0xFFFFA726), // Downloads — orange
        Color(0xFFEC407A), // Images — pink
        Color(0xFF42A5F5), // Audio — blue
        Color(0xFFAB47BC), // Videos — purple
        Color(0xFF78909C), // Documents — blue-grey
        Color(0xFF26C6DA), // Apps — cyan
        Color(0xFF8D6E63), // New files — brown
        Color(0xFF1E88E5), // Cloud — bright blue
        Color(0xFF546E7A), // Remote — slate
    )

    Column(modifier = modifier.fillMaxSize()) {
        // Storage summary cards at top
        if (internalInfo.total > 0 || externalInfo.total > 0) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (internalInfo.total > 0) {
                    StorageSummaryCard(
                        label = "Internal storage",
                        info = internalInfo,
                        color = Color(0xFF5C6BC0)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (externalInfo.total > 0 && externalInfo.total != internalInfo.total) {
                    StorageSummaryCard(
                        label = "Main storage (External)",
                        info = externalInfo,
                        color = Color(0xFF26A69A)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(gridItems.size) { index ->
                val (title, subtitle, icon) = gridItems[index]
                val color = iconColors[index]
                GridItemView(
                    item = GridItemData(title, subtitle, icon),
                    iconColor = color,
                    onClick = {
                        when (title) {
                            "Internal storage" -> {
                                if (hasStorageAccess()) {
                                    onNavigate(NavState.Local(Environment.getDataDirectory().absolutePath))
                                } else {
                                    openStoragePermissionSettings()
                                }
                            }
                            "Main storage" -> {
                                if (hasStorageAccess()) {
                                    onNavigate(NavState.Local(LocalFileManager.mainStoragePath))
                                } else {
                                    openStoragePermissionSettings()
                                }
                            }
                            "SD card" -> {
                                val sdPath = LocalFileManager.getSdCardPath(context)
                                if (sdPath != null) {
                                    if (hasStorageAccess()) {
                                        onNavigate(NavState.Local(sdPath))
                                    } else {
                                        openStoragePermissionSettings()
                                    }
                                } else {
                                    android.widget.Toast.makeText(context, "No SD card found", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                            "Downloads" -> onNavigate(NavState.Local(LocalFileManager.mainStoragePath + "/Download"))
                            "Cloud" -> onNavigate(NavState.CloudAccounts)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun StorageSummaryCard(label: String, info: StorageInfo, color: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = "${(info.progress * 100).toInt()}% used",
                    fontSize = 12.sp,
                    color = if (info.progress > 0.9f) Color(0xFFE53935) else color
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { info.progress },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = if (info.progress > 0.9f) Color(0xFFE53935) else color,
                trackColor = color.copy(alpha = 0.2f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = info.usedText, fontSize = 11.sp, color = Color.Gray)
        }
    }
}

@Composable
fun GridItemView(item: GridItemData, iconColor: Color = Color(0xFF26A69A), onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(iconColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.title,
                modifier = Modifier.size(34.dp),
                tint = iconColor
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = item.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (item.subtitle.isNotEmpty()) {
            Text(
                text = item.subtitle,
                fontSize = 10.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun DrawerContent(onNavigate: (NavState) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Drawer Tabs (Folder, Star, History)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF00796B)) // Dark teal header line
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            Icon(Icons.Default.Folder, contentDescription = "Folder", tint = Color.White)
            Icon(Icons.Default.StarBorder, contentDescription = "Favorites", tint = Color.LightGray)
            Icon(Icons.Default.History, contentDescription = "History", tint = Color.LightGray)
        }

        // Drawer Items
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            DrawerMenuItem(Icons.Default.Home, "Home", Color.Red, onClick = { onNavigate(NavState.Home) })
            
            // Main Storage with real Progress
            val extInfo = remember { getExternalStorageInfo() }
            val sdCardInfo = remember { getSdCardStorageInfo() }
            DrawerStorageItem(
                Icons.Default.PhoneAndroid, "Main storage",
                if (extInfo.total > 0) "${(extInfo.progress * 100).toInt()}%" else "—",
                extInfo.progress,
                onClick = { onNavigate(NavState.Local(LocalFileManager.mainStoragePath)) }
            )
            
            // SD Card with real Progress
            DrawerStorageItem(
                Icons.Default.SdStorage, "SD card",
                if (sdCardInfo.total > 0) "${(sdCardInfo.progress * 100).toInt()}%" else "—",
                sdCardInfo.progress,
                onClick = { /* Handle SD Card */ }
            )
            
            DrawerMenuItem(Icons.Default.Delete, "Recycle Bin", Color.Gray, trailingText = "0 B", onClick = {})
            
            Divider()
            
            DrawerMenuItem(Icons.Default.CloudQueue, "Google Drive", Color.Blue, isPinned = true, onClick = { onNavigate(NavState.Cloud("root", "My Drive")) })
            DrawerMenuItem(Icons.Default.FolderOpen, "Main storage /Download", Color.Gray, isPinned = true, onClick = { onNavigate(NavState.Local(LocalFileManager.mainStoragePath + "/Download")) })
            
            Divider()
            
            DrawerMenuItem(Icons.Default.Schedule, "New files", Color.Gray, hasMoreVert = true)
            DrawerMenuItem(Icons.Default.Download, "Downloads", Color(0xFFFFA500), hasMoreVert = true)
        }

        // Upgrade Banner
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
                color = Color(0xFF1976D2), // Blue progress
                trackColor = Color.LightGray,
            )
        }
    }
}

// Data class for grid items
data class GridItemData(
    val title: String,
    val subtitle: String,
    val icon: ImageVector
)

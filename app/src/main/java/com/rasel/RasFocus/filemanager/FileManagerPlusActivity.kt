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
import kotlinx.coroutines.launch

sealed class NavState {
    object Home : NavState()
    data class Local(val path: String) : NavState()
    data class Cloud(val folderId: String, val pathName: String) : NavState()
}

data class ClipboardState(
    val sourceEnv: String, // "Local" or "Cloud"
    val items: List<String>, // paths or fileIds
    val isCut: Boolean = false
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
                drawerContainerColor = Color.White
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
                                is NavState.Cloud -> state.pathName
                            }, 
                            color = Color.White
                        ) 
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF1E1E1E) // Dark background from image_5b8f74.jpg
                    ),
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
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
            containerColor = Color.White
        ) { paddingValues ->
            Box(modifier = Modifier.padding(paddingValues)) {
                when (val state = currentNavState) {
                    is NavState.Home -> MainGridContent(
                        onNavigate = { newState -> currentNavState = newState }
                    )
                    is NavState.Local -> LocalFileScreen(
                        path = state.path, 
                        onNavigate = { newState -> currentNavState = newState },
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
                    is NavState.Cloud -> CloudFileScreen(
                        folderId = state.folderId,
                        pathName = state.pathName,
                        onNavigate = { newState -> currentNavState = newState },
                        onBack = { 
                            // simplistic back, ideally we'd have a stack
                            currentNavState = NavState.Home 
                        },
                        clipboard = clipboard,
                        onSetClipboard = { clipboard = it }
                    )
                }
            }
        }
    }
}

@Composable
fun MainGridContent(modifier: Modifier = Modifier, onNavigate: (NavState) -> Unit) {
    val gridItems = listOf(
        GridItemData("Main storage", "31 GB / 32 GB", Icons.Default.PhoneAndroid),
        GridItemData("SD card", "1.1 GB / 8 GB", Icons.Default.SdStorage),
        GridItemData("Downloads", "35.2 MB (17)", Icons.Default.Download),
        GridItemData("Images", "1.2 GB (1417)", Icons.Default.Image),
        GridItemData("Audio", "717 kB (1)", Icons.Default.Audiotrack),
        GridItemData("Videos", "210 MB (12)", Icons.Default.VideoLibrary),
        GridItemData("Documents", "120 MB (66)", Icons.Default.Description),
        GridItemData("Apps", "2.6 GB (66)", Icons.Default.Android),
        GridItemData("New files", "189 MB (293)", Icons.Default.Schedule),
        GridItemData("Cloud", "(5)", Icons.Default.Cloud),
        GridItemData("Remote", "", Icons.Default.Computer),
        GridItemData("Access from...", "", Icons.Default.PhonelinkRing)
    )

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(gridItems) { item ->
            GridItemView(item, onClick = {
                when (item.title) {
                    "Main storage" -> onNavigate(NavState.Local(LocalFileManager.mainStoragePath))
                    "Cloud" -> onNavigate(NavState.Cloud("root", "My Drive"))
                }
            })
        }
    }
}

@Composable
fun GridItemView(item: GridItemData, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.title,
            modifier = Modifier
                .size(64.dp)
                .padding(bottom = 8.dp),
            tint = Color.Gray // Change to specific colors based on your app's theme
        )
        Text(
            text = item.title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (item.subtitle.isNotEmpty()) {
            Text(
                text = item.subtitle,
                fontSize = 11.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                maxLines = 1
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
            
            // Main Storage with Progress
            DrawerStorageItem(
                Icons.Default.PhoneAndroid, "Main storage", "96%", 0.96f,
                onClick = { onNavigate(NavState.Local(LocalFileManager.mainStoragePath)) }
            )
            
            // SD Card with Progress
            DrawerStorageItem(
                Icons.Default.SdStorage, "SD card", "13%", 0.13f,
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

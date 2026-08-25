package com.rasel.RasFocus.filemanager

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class AppInfo(
    val name: String,
    val packageName: String,
    val sourceDir: String,
    val icon: Drawable?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppManagerScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val appList = mutableListOf<AppInfo>()
            for (appInfo in packages) {
                // Only show user apps
                if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
                    val name = pm.getApplicationLabel(appInfo).toString()
                    val icon = pm.getApplicationIcon(appInfo)
                    appList.add(AppInfo(name, appInfo.packageName, appInfo.sourceDir, icon))
                }
            }
            apps = appList.sortedBy { it.name.lowercase() }
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Manager", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E1E))
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (apps.isEmpty()) {
                Text("No user apps found.", modifier = Modifier.align(Alignment.Center), color = Color.Gray)
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(apps) { app ->
                        AppListItem(app = app, onBackup = {
                            scope.launch(Dispatchers.IO) {
                                val sourceFile = File(app.sourceDir)
                                val backupDir = File(LocalFileManager.mainStoragePath, "AppBackups")
                                if (!backupDir.exists()) backupDir.mkdirs()
                                val destFile = File(backupDir, "${app.name}.apk")
                                try {
                                    sourceFile.copyTo(destFile, overwrite = true)
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(context, "Backed up to AppBackups/${app.name}.apk", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(context, "Backup failed", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        })
                        Divider()
                    }
                }
            }
        }
    }
}

@Composable
fun AppListItem(app: AppInfo, onBackup: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (app.icon != null) {
            Image(
                bitmap = app.icon.toBitmap().asImageBitmap(),
                contentDescription = app.name,
                modifier = Modifier.size(48.dp)
            )
        } else {
            Box(modifier = Modifier.size(48.dp).background(Color.LightGray))
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(app.name, style = MaterialTheme.typography.bodyLarge)
            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        IconButton(onClick = onBackup) {
            Icon(Icons.Default.FileDownload, contentDescription = "Backup APK", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

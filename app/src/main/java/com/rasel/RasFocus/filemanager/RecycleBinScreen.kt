package com.rasel.RasFocus.filemanager

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecycleBinScreen(onBack: () -> Unit) {
    var trashFiles by remember { mutableStateOf<List<File>>(emptyList()) }

    LaunchedEffect(Unit) {
        LocalFileManager.initPremiumFolders()
        trashFiles = File(LocalFileManager.trashPath).listFiles()?.filter { !it.name.equals(".nomedia") } ?: emptyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recycle Bin") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (trashFiles.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Recycle Bin is empty", color = Color.Gray)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(trashFiles) { file ->
                        ListItem(
                            headlineContent = { Text(file.name) },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = {
                                        LocalFileManager.restoreFromTrash(file, LocalFileManager.mainStoragePath)
                                        trashFiles = File(LocalFileManager.trashPath).listFiles()?.filter { !it.name.equals(".nomedia") } ?: emptyList()
                                    }) {
                                        Icon(Icons.Default.Restore, contentDescription = "Restore", tint = Color(0xFF4CAF50))
                                    }
                                    IconButton(onClick = {
                                        file.delete()
                                        trashFiles = File(LocalFileManager.trashPath).listFiles()?.filter { !it.name.equals(".nomedia") } ?: emptyList()
                                    }) {
                                        Icon(Icons.Default.DeleteForever, contentDescription = "Delete Forever", tint = Color.Red)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

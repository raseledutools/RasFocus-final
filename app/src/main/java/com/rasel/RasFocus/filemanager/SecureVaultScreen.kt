package com.rasel.RasFocus.filemanager

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecureVaultScreen(onBack: () -> Unit) {
    var isUnlocked by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var vaultFiles by remember { mutableStateOf<List<File>>(emptyList()) }

    LaunchedEffect(isUnlocked) {
        if (isUnlocked) {
            LocalFileManager.initPremiumFolders()
            vaultFiles = File(LocalFileManager.vaultPath).listFiles()?.filter { !it.name.equals(".nomedia") } ?: emptyList()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Secure Vault") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (!isUnlocked) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Lock, contentDescription = "Lock", modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(24.dp))
                Text("Enter PIN to unlock vault", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { pinInput = it },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    label = { Text("PIN") }
                )
                if (errorMessage.isNotEmpty()) {
                    Text(errorMessage, color = Color.Red, modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.height(24.dp))
                Button(onClick = {
                    if (pinInput == "1234") { // Default PIN for demonstration
                        isUnlocked = true
                        errorMessage = ""
                    } else {
                        errorMessage = "Incorrect PIN"
                    }
                }) {
                    Text("Unlock")
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (vaultFiles.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Vault is empty. Move files here to hide them.", color = Color.Gray)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(vaultFiles) { file ->
                            ListItem(
                                headlineContent = { Text(file.name) },
                                trailingContent = {
                                    Button(onClick = {
                                        LocalFileManager.restoreFromVault(file, LocalFileManager.mainStoragePath)
                                        vaultFiles = File(LocalFileManager.vaultPath).listFiles()?.filter { !it.name.equals(".nomedia") } ?: emptyList()
                                    }) {
                                        Text("Restore")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

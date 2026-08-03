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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecureVaultScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("VaultPrefs", android.content.Context.MODE_PRIVATE) }
    var savedPin by remember { mutableStateOf(prefs.getString("vault_pin", "1234") ?: "1234") }

    var isUnlocked by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var vaultFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var showChangePinDialog by remember { mutableStateOf(false) }

    fun refreshVault() {
        LocalFileManager.initPremiumFolders()
        vaultFiles = File(LocalFileManager.vaultPath).listFiles()
            ?.filter { !it.name.equals(".nomedia") } ?: emptyList()
    }

    LaunchedEffect(isUnlocked) {
        if (isUnlocked) refreshVault()
    }

    // ── Change PIN Dialog ──────────────────────────────────────────────────────
    if (showChangePinDialog) {
        var newPin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showChangePinDialog = false },
            title = { Text("Change PIN") },
            text = {
                OutlinedTextField(
                    value = newPin,
                    onValueChange = { if (it.length <= 8) newPin = it },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    label = { Text("New PIN (4–8 digits)") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        prefs.edit().putString("vault_pin", newPin).apply()
                        savedPin = newPin
                        showChangePinDialog = false
                        android.widget.Toast.makeText(context, "PIN updated successfully", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    enabled = newPin.length >= 4
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showChangePinDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Secure Vault") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isUnlocked) {
                        TextButton(onClick = { showChangePinDialog = true }) {
                            Text("Change PIN", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (!isUnlocked) {
            // ── Lock Screen ────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "Lock",
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(24.dp))
                Text("Enter PIN to unlock vault", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text("Default PIN: 1234", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { if (it.length <= 8) pinInput = it },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    label = { Text("PIN") }
                )
                if (errorMessage.isNotEmpty()) {
                    Text(errorMessage, color = Color.Red, modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.height(24.dp))
                Button(onClick = {
                    if (pinInput == savedPin) {
                        isUnlocked = true
                        errorMessage = ""
                    } else {
                        errorMessage = "Incorrect PIN"
                        pinInput = ""
                    }
                }) {
                    Text("Unlock")
                }
            }
        } else {
            // ── Vault Content ──────────────────────────────────────────────────
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (vaultFiles.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Vault is empty.\nSelect files in File Manager → Secure\nto move them here.",
                            color = Color.Gray,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(vaultFiles) { file ->
                            ListItem(
                                headlineContent = { Text(file.name) },
                                supportingContent = { Text(formatFileSize(file.length()), color = Color.Gray) },
                                trailingContent = {
                                    Button(onClick = {
                                        LocalFileManager.restoreFromVault(file, LocalFileManager.mainStoragePath)
                                        refreshVault()
                                    }) {
                                        Text("Restore")
                                    }
                                }
                            )
                            Divider()
                        }
                    }
                }
            }
        }
    }
}

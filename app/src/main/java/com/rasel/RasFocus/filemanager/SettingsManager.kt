package com.rasel.RasFocus.filemanager

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

object SettingsManager {
    private const val PREFS_NAME = "FileManagerSettings"
    
    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isDarkMode(context: Context): Boolean {
        return getPrefs(context).getBoolean("dark_mode", true)
    }

    fun setDarkMode(context: Context, isDark: Boolean) {
        getPrefs(context).edit().putBoolean("dark_mode", isDark).apply()
    }

    fun showHiddenFiles(context: Context): Boolean {
        return getPrefs(context).getBoolean("show_hidden", false)
    }

    fun setShowHiddenFiles(context: Context, show: Boolean) {
        getPrefs(context).edit().putBoolean("show_hidden", show).apply()
    }

    fun getPrimaryColor(context: Context): Int {
        return getPrefs(context).getInt("primary_color", 0xFF1565C0.toInt())
    }

    fun setPrimaryColor(context: Context, color: Int) {
        getPrefs(context).edit().putInt("primary_color", color).apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var isDark by remember { mutableStateOf(SettingsManager.isDarkMode(context)) }
    var showHidden by remember { mutableStateOf(SettingsManager.showHiddenFiles(context)) }
    
    // We will just show a UI representation of a theme picker. True dynamic theming 
    // requires a customized Compose theme wrapper throughout the app.
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = Color.White) },
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            item {
                Text(
                    text = "Appearance",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp)
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Dark Theme") },
                    supportingContent = { Text("Enable dark mode across the app") },
                    leadingContent = { Icon(Icons.Default.DarkMode, contentDescription = null, tint = Color.Gray) },
                    trailingContent = {
                        Switch(
                            checked = isDark,
                            onCheckedChange = {
                                isDark = it
                                SettingsManager.setDarkMode(context, it)
                                // In a full app, this state would be hoisted to the root AppTheme
                            }
                        )
                    },
                    modifier = Modifier.clickable {
                        isDark = !isDark
                        SettingsManager.setDarkMode(context, isDark)
                    }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Accent Color") },
                    supportingContent = { Text("Choose a custom accent color") },
                    leadingContent = { Icon(Icons.Default.ColorLens, contentDescription = null, tint = Color.Gray) },
                    modifier = Modifier.clickable {
                        android.widget.Toast.makeText(context, "Color picker coming soon", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
                Divider()
            }
            
            item {
                Text(
                    text = "File Browser",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp)
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Show Hidden Files") },
                    supportingContent = { Text("Display files starting with a dot (.)") },
                    leadingContent = { Icon(Icons.Default.Visibility, contentDescription = null, tint = Color.Gray) },
                    trailingContent = {
                        Switch(
                            checked = showHidden,
                            onCheckedChange = {
                                showHidden = it
                                SettingsManager.setShowHiddenFiles(context, it)
                            }
                        )
                    },
                    modifier = Modifier.clickable {
                        showHidden = !showHidden
                        SettingsManager.setShowHiddenFiles(context, showHidden)
                    }
                )
                Divider()
            }
        }
    }
}

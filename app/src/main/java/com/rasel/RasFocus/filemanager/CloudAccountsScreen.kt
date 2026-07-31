package com.rasel.RasFocus.filemanager

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.rasel.RasFocus.R

object CloudAccountManager {
    private const val PREFS_NAME = "CloudAccountsPrefs"
    private const val KEY_ACCOUNTS = "google_accounts"

    fun getAccounts(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val accountsSet = prefs.getStringSet(KEY_ACCOUNTS, emptySet()) ?: emptySet()
        return accountsSet.toList().sorted()
    }

    fun addAccount(context: Context, email: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val accountsSet = prefs.getStringSet(KEY_ACCOUNTS, emptySet())?.toMutableSet() ?: mutableSetOf()
        accountsSet.add(email)
        prefs.edit().putStringSet(KEY_ACCOUNTS, accountsSet).apply()
    }
}

@Composable
fun CloudAccountsScreen(onAccountSelected: (String) -> Unit) {
    val context = LocalContext.current
    var accounts by remember { mutableStateOf(CloudAccountManager.getAccounts(context)) }

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            account?.email?.let { email ->
                CloudAccountManager.addAccount(context, email)
                accounts = CloudAccountManager.getAccounts(context)
            }
        } catch (e: ApiException) {
            // Handle error silently or show toast
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f)
        ) {
            items(accounts) { email ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAccountSelected(email) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudQueue,
                        contentDescription = "Cloud location",
                        modifier = Modifier.size(40.dp),
                        tint = Color(0xFF1E88E5)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text("Google Drive", fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
                        Text(email, fontSize = 14.sp, color = Color.Gray)
                    }
                }
                Divider()
            }
            
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                                .requestEmail()
                                .build()
                            val client = GoogleSignIn.getClient(context, gso)
                            // Sign out to force account picker
                            client.signOut().addOnCompleteListener {
                                signInLauncher.launch(client.signInIntent)
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add a cloud location", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

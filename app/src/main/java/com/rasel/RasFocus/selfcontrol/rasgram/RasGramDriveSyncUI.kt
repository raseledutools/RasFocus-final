package com.rasel.RasFocus.selfcontrol.rasgram

// ============================================================
// RasGramDriveSyncUI.kt
//
// UI components for:
//  1. Drive sync settings screen (multi-account, sync button)
//  2. File-from-folder attachment in chat (WhatsApp style)
// ============================================================

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// ============================================================
// DRIVE SYNC SETTINGS SCREEN  (placed inside RasGram Settings → Tab 4)
// ============================================================

@Composable
fun RasGramDriveSyncSettings(currentUser: User) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // State
    var accounts by remember { mutableStateOf(RasGramDriveAccountManager.getSyncAccounts(context)) }
    var defaultAccount by remember { mutableStateOf(RasGramDriveAccountManager.getDefaultSyncAccount(context)) }
    var isSyncing by remember { mutableStateOf(false) }
    var syncResult by remember { mutableStateOf<String?>(null) }
    val lastSyncTime = remember { RasGramDriveAccountManager.getLastSyncTime(context) }

    // Google Sign In launcher (for adding new Drive account)
    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            account?.email?.let { email ->
                RasGramDriveAccountManager.addSyncAccount(context, email)
                accounts = RasGramDriveAccountManager.getSyncAccounts(context)
                defaultAccount = RasGramDriveAccountManager.getDefaultSyncAccount(context)
                // Schedule daily sync
                RasGramDriveSyncScheduler.schedule(context)
                Toast.makeText(context, "✅ $email যোগ করা হয়েছে", Toast.LENGTH_SHORT).show()
            }
        } catch (e: ApiException) {
            Toast.makeText(context, "Sign-in ব্যর্থ: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Text(
            "Drive Sync",
            color = RasGramTheme.Green,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Text(
            "প্রতিদিন সব chat ও media তোমার Google Drive এ backup হবে। যেকোনো সময় Sync Now বাটনে চাপলে সাথে সাথে sync হবে।",
            color = RasGramTheme.TextMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Last sync time
        if (lastSyncTime > 0) {
            val fmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = RasGramTheme.DarkBackground
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, null, tint = RasGramTheme.Green, modifier = Modifier.size(18.dp))
                    Text(
                        "শেষ sync: ${fmt.format(Date(lastSyncTime))}",
                        color = RasGramTheme.TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Sync Now button
        Button(
            onClick = {
                if (!isSyncing && RasGramDriveAccountManager.isDriveAvailable(context)) {
                    isSyncing = true
                    syncResult = null
                    scope.launch {
                        val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            RasGramDriveSyncEngine.performFullSync(context)  // সব messages, no age filter
                        }
                        isSyncing = false
                        syncResult = if (result.success) {
                            if (result.syncedMessages > 0)
                                "✅ ${result.syncedMessages} messages ও ${result.syncedMediaFiles} media Drive এ sync হয়েছে (${result.durationMs / 1000}s)"
                            else "✅ সব আপ-টু-ডেট আছে"
                        } else {
                            "❌ ${result.errorMessage ?: "Sync ব্যর্থ হয়েছে"}"
                        }
                    }
                } else if (!RasGramDriveAccountManager.isDriveAvailable(context)) {
                    Toast.makeText(context, "আগে একটি Drive account যোগ করুন", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = !isSyncing && RasGramDriveAccountManager.isDriveAvailable(context),
            colors = ButtonDefaults.buttonColors(
                containerColor = RasGramTheme.Green,
                disabledContainerColor = RasGramTheme.Border
            )
        ) {
            if (isSyncing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text("Sync হচ্ছে...", color = Color.Black, fontWeight = FontWeight.Bold)
            } else {
                Icon(Icons.Default.Sync, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sync Now", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }

        // Sync result
        syncResult?.let { msg ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = if (msg.startsWith("✅")) RasGramTheme.Green.copy(alpha = 0.1f) else Color.Red.copy(alpha = 0.1f)
            ) {
                Text(
                    msg,
                    modifier = Modifier.padding(12.dp),
                    color = if (msg.startsWith("✅")) RasGramTheme.Green else Color.Red.copy(alpha = 0.8f),
                    fontSize = 13.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(color = RasGramTheme.Border, thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(4.dp))

        // Drive Accounts header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Drive Accounts",
                color = RasGramTheme.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Text(
                "${accounts.size} account",
                color = RasGramTheme.TextMuted,
                fontSize = 12.sp
            )
        }

        // Account list
        if (accounts.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = RasGramTheme.DarkPanel,
                border = BorderStroke(1.dp, RasGramTheme.Border)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.CloudOff, null, tint = RasGramTheme.TextMuted, modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("কোনো Drive account নেই", color = RasGramTheme.TextMuted, fontSize = 13.sp)
                    Text("নিচে + বাটন দিয়ে যোগ করুন", color = RasGramTheme.TextMuted, fontSize = 11.sp)
                }
            }
        } else {
            accounts.forEach { email ->
                val isDefault = email == defaultAccount
                val isRasFocusAccount = email == RasGramDriveAccountManager.getRasFocusAccount(context)?.email

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = if (isDefault) RasGramTheme.Green.copy(alpha = 0.08f) else RasGramTheme.DarkPanel,
                    border = BorderStroke(
                        1.dp,
                        if (isDefault) RasGramTheme.Green.copy(alpha = 0.5f) else RasGramTheme.Border
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Account icon
                        Box(
                            modifier = Modifier.size(38.dp).clip(CircleShape)
                                .background(if (isDefault) RasGramTheme.Green else RasGramTheme.DarkSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.CloudDone,
                                null,
                                tint = if (isDefault) Color.Black else RasGramTheme.TextMuted,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    email,
                                    color = RasGramTheme.TextPrimary,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, false)
                                )
                                if (isDefault) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = RasGramTheme.Green.copy(alpha = 0.2f)
                                    ) {
                                        Text(
                                            "Default",
                                            color = RasGramTheme.Green,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            if (isRasFocusAccount) {
                                Text(
                                    "RasFocus account",
                                    color = RasGramTheme.TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        // Actions
                        Row {
                            if (!isDefault) {
                                TextButton(onClick = {
                                    RasGramDriveAccountManager.setDefaultSyncAccount(context, email)
                                    defaultAccount = email
                                    Toast.makeText(context, "Default account পরিবর্তন হয়েছে", Toast.LENGTH_SHORT).show()
                                }) {
                                    Text("Set Default", color = RasGramTheme.Green, fontSize = 12.sp)
                                }
                            }
                            if (!isRasFocusAccount) {
                                IconButton(onClick = {
                                    RasGramDriveAccountManager.removeSyncAccount(context, email)
                                    accounts = RasGramDriveAccountManager.getSyncAccounts(context)
                                    defaultAccount = RasGramDriveAccountManager.getDefaultSyncAccount(context)
                                    Toast.makeText(context, "$email সরানো হয়েছে", Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(Icons.Default.Delete, null, tint = RasGramTheme.Red.copy(0.7f), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        }

        // Add account button
        OutlinedButton(
            onClick = {
                val gso = RasGramDriveAccountManager.driveSignInOptions()
                val client = com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(context, gso)
                // Sign out to show account picker
                client.signOut().addOnCompleteListener {
                    signInLauncher.launch(client.signInIntent)
                }
            },
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, RasGramTheme.Border),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = RasGramTheme.TextMuted)
        ) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("নতুন Drive Account যোগ করুন", fontSize = 13.sp)
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Info box
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = RasGramTheme.DarkBackground
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.Info, null, tint = RasGramTheme.Green.copy(0.7f), modifier = Modifier.size(16.dp).offset(y = 2.dp))
                Column {
                    Text("প্রতিদিন রাত ২টায় auto sync হয়", color = RasGramTheme.TextMuted, fontSize = 11.sp)
                    Text("Network চালু থাকলেই sync কাজ করবে", color = RasGramTheme.TextMuted, fontSize = 11.sp)
                    Text("Sync হলে RasFocus+ > RasGram folder এ save হয়", color = RasGramTheme.TextMuted, fontSize = 11.sp)
                }
            }
        }
    }
}

// ============================================================
// UPLOAD PROGRESS INDICATOR  (WhatsApp-style: name + progress)
// ============================================================

@Composable
fun FileUploadProgressIndicator(
    fileName: String,
    progress: Float,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 200),
        label = "upload_progress"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = RasGramTheme.DarkPanel.copy(alpha = 0.95f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.size(16.dp),
                        color = RasGramTheme.Green,
                        trackColor = RasGramTheme.Border,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (fileName.isNotEmpty()) "পাঠানো হচ্ছে: ${fileName.take(30)}${if (fileName.length > 30) "..." else ""}"
                        else "আপলোড হচ্ছে...",
                        color = RasGramTheme.TextSecondary,
                        fontSize = 12.sp
                    )
                }
                Text(
                    "${(animatedProgress * 100).toInt()}%",
                    color = RasGramTheme.Green,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = RasGramTheme.Green,
                trackColor = RasGramTheme.Border
            )
        }
    }
}

// ============================================================
// ENHANCED ATTACHMENT MENU  (includes "Files" from File Manager)
// ============================================================

@Composable
fun EnhancedAttachmentMenuSheet(
    onDismiss: () -> Unit,
    onImageVideo: () -> Unit,
    onDocument: () -> Unit,
    onAudio: () -> Unit,
    onFilesFromFolder: () -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            color = RasGramTheme.DarkPanel
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    "Share",
                    style = MaterialTheme.typography.titleMedium,
                    color = RasGramTheme.TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    AttachOption(Icons.Default.Image, "Photos & Videos", RasGramTheme.Orange, onImageVideo)
                    AttachOption(Icons.Default.InsertDriveFile, "Document", Color(0xFF6C63FF), onDocument)
                    AttachOption(Icons.Default.AudioFile, "Audio", Color(0xFF00BFA5), onAudio)
                    AttachOption(Icons.Default.Camera, "Camera", RasGramTheme.Green, onDismiss)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // "Files" option — opens file manager / file picker
                    AttachOption(Icons.Default.Folder, "Files", Color(0xFF1A73E8), onFilesFromFolder)
                    AttachOption(Icons.Default.LocationOn, "Location", RasGramTheme.Red, onDismiss)
                    AttachOption(Icons.Default.ContactPage, "Contact", Color(0xFF2196F3), onDismiss)
                    AttachOption(Icons.Default.Poll, "Poll", Color(0xFFFF9800), onDismiss)
                }
            }
        }
    }
}

// ============================================================
// SHARE FILE CONTACT PICKER DIALOG
// Shown when user taps "Send to RasGram" in FileManager.
// Lets user pick a contact, then opens the chat with file attached.
// ============================================================

@Composable
fun ShareFileContactPickerDialog(
    currentUser: User,
    fileUri: android.net.Uri,
    fileName: String,
    onContactSelected: (User) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { com.google.firebase.firestore.FirebaseFirestore.getInstance() }

    // Load contacts from Firestore (same as chat list)
    var contacts by remember { mutableStateOf<List<User>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var uploadingTo by remember { mutableStateOf<String?>(null) }
    var isUploading by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(currentUser.mobile) {
        try {
            db.collection("chat_users").addSnapshotListener { snap, _ ->
                snap?.documents?.mapNotNull { doc ->
                    doc.data?.let { d ->
                        val mobile = doc.id
                        if (mobile == currentUser.mobile) return@mapNotNull null
                        User(
                            uid = d["uid"] as? String ?: "",
                            name = d["name"] as? String ?: mobile,
                            mobile = mobile,
                            avatarUrl = d["avatarUrl"] as? String ?: ""
                        )
                    }
                }?.let { users ->
                    contacts = users.sortedBy { it.name }
                    isLoading = false
                }
            }
        } catch (e: Exception) {
            isLoading = false
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !isUploading
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            color = RasGramTheme.DarkPanel
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { if (!isUploading) onDismiss() }) {
                        Icon(Icons.Default.Close, null, tint = RasGramTheme.TextMuted)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Send to…",
                            color = RasGramTheme.TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        Text(
                            "📎 $fileName",
                            color = RasGramTheme.TextMuted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }

                HorizontalDivider(color = RasGramTheme.Border, thickness = 0.5.dp)

                // Upload progress
                if (isUploading) {
                    FileUploadProgressIndicator(
                        fileName = fileName,
                        progress = uploadProgress,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Contact list
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = RasGramTheme.Green, modifier = Modifier.size(36.dp))
                    }
                } else if (contacts.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.PersonOff, null, tint = RasGramTheme.TextMuted, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("কোনো contact নেই", color = RasGramTheme.TextMuted)
                        }
                    }
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
                        val contactList = contacts
                        contactList.forEach { contact ->
                            item(key = contact.mobile) {
                            val isSendingTo = uploadingTo == contact.mobile
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !isUploading) {
                                        if (!isUploading) {
                                            uploadingTo = contact.mobile
                                            isUploading = true
                                            scope.launch {
                                                try {
                                                    val (url, fn, ft) = uploadToCloudinary(context, fileUri) { prog ->
                                                        uploadProgress = prog
                                                    }
                                                    if (url != null) {
                                                        val chatId = generateChatId(currentUser.mobile, contact.mobile)
                                                        sendMessage(
                                                            db, chatId,
                                                            currentUser.mobile, currentUser.name,
                                                            contact.mobile, "", context,
                                                            url, fn ?: fileName, ft
                                                        )
                                                        Toast.makeText(context, "📎 ${contact.name} কে পাঠানো হয়েছে", Toast.LENGTH_SHORT).show()
                                                        onContactSelected(contact)
                                                    } else {
                                                        Toast.makeText(context, "আপলোড ব্যর্থ হয়েছে", Toast.LENGTH_SHORT).show()
                                                    }
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                                }
                                                isUploading = false
                                                uploadingTo = null
                                                uploadProgress = 0f
                                            }
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                UserAvatar(contact, size = 44.dp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(contact.name.ifEmpty { contact.mobile }, color = RasGramTheme.TextPrimary, fontWeight = FontWeight.Medium)
                                    Text(contact.mobile, color = RasGramTheme.TextMuted, fontSize = 12.sp)
                                }
                                if (isSendingTo && isUploading) {
                                    CircularProgressIndicator(
                                        progress = { uploadProgress },
                                        modifier = Modifier.size(22.dp),
                                        color = RasGramTheme.Green,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Default.Forward, null, tint = RasGramTheme.TextMuted.copy(0.4f), modifier = Modifier.size(18.dp))
                                }
                            }
                            HorizontalDivider(color = RasGramTheme.Border.copy(0.5f), thickness = 0.3.dp, modifier = Modifier.padding(start = 72.dp))
                            } // item
                        }
                    }
                }
            }
        }
    }
}

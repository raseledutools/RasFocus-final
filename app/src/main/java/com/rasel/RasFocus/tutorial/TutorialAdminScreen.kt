package com.rasel.RasFocus.tutorial

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudinary.android.MediaManager
import com.cloudinary.android.callback.ErrorInfo
import com.cloudinary.android.callback.UploadCallback
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

// ─────────────────────────────────────────────────────────────
// Colours
// ─────────────────────────────────────────────────────────────
private val AdmBg      = Color(0xFF0F1923)
private val AdmCard    = Color(0xFF1A2535)
private val AdmAccent  = Color(0xFF4A9EFF)
private val AdmGreen   = Color(0xFF34C98A)
private val AdmRed     = Color(0xFFFF6B6B)
private val AdmText    = Color(0xFFE8EDF2)
private val AdmSubtext = Color(0xFF8A9BB0)
private val AdmPurple  = Color(0xFF7B5CF5)

private enum class AdminTab { COURSES, USERS }

// ─────────────────────────────────────────────────────────────
// Admin Root
// ─────────────────────────────────────────────────────────────
@Composable
fun TutorialAdminScreen(onBack: () -> Unit) {
    var activeTab by remember { mutableStateOf(AdminTab.COURSES) }
    var selectedCourse by remember { mutableStateOf<TutorialCourse?>(null) }

    Column(Modifier.fillMaxSize().background(AdmBg)) {
        // Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xFF0D1F38), AdmBg)))
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = if (selectedCourse != null) ({ selectedCourse = null }) else onBack) {
                    Icon(Icons.Default.ArrowBack, null, tint = AdmText)
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (selectedCourse != null) selectedCourse!!.title else "Admin Panel",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AdmText,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text("Rajib Tutorial", fontSize = 12.sp, color = AdmSubtext)
                }
                Box(
                    modifier = Modifier
                        .background(AdmPurple.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("Admin", fontSize = 11.sp, color = AdmPurple, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (selectedCourse != null) {
            VideoManagementScreen(
                course = selectedCourse!!,
                onCourseUpdated = { selectedCourse = it }
            )
        } else {
            TabRow(
                selectedTabIndex = activeTab.ordinal,
                containerColor = AdmCard,
                contentColor = AdmAccent
            ) {
                Tab(
                    selected = activeTab == AdminTab.COURSES,
                    onClick = { activeTab = AdminTab.COURSES },
                    text = { Text("Courses", color = if (activeTab == AdminTab.COURSES) AdmAccent else AdmSubtext) },
                    icon = { Icon(Icons.Default.VideoLibrary, null, tint = if (activeTab == AdminTab.COURSES) AdmAccent else AdmSubtext) }
                )
                Tab(
                    selected = activeTab == AdminTab.USERS,
                    onClick = { activeTab = AdminTab.USERS },
                    text = { Text("Users", color = if (activeTab == AdminTab.USERS) AdmAccent else AdmSubtext) },
                    icon = { Icon(Icons.Default.People, null, tint = if (activeTab == AdminTab.USERS) AdmAccent else AdmSubtext) }
                )
            }
            when (activeTab) {
                AdminTab.COURSES -> CourseManagementScreen(onCourseClick = { selectedCourse = it })
                AdminTab.USERS   -> UserEnrollmentScreen()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Course Management
// ─────────────────────────────────────────────────────────────
@Composable
fun CourseManagementScreen(onCourseClick: (TutorialCourse) -> Unit) {
    val db = FirebaseFirestore.getInstance()
    val scope = rememberCoroutineScope()
    var courses by remember { mutableStateOf<List<TutorialCourse>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showAddDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<TutorialCourse?>(null) }
    var snack by remember { mutableStateOf("") }

    fun loadCourses() {
        scope.launch {
            loading = true
            try {
                val snap = db.collection("tutorial_courses").get().await()
                courses = snap.documents.map { doc ->
                    @Suppress("UNCHECKED_CAST")
                    val vRaw = doc.get("videos") as? List<Map<String, Any>> ?: emptyList()
                    TutorialCourse(
                        id = doc.id,
                        title = doc.getString("title") ?: "",
                        description = doc.getString("description") ?: "",
                        videos = vRaw.map { v ->
                            TutorialVideo(
                                id = v["id"] as? String ?: "",
                                title = v["title"] as? String ?: "",
                                cloudinaryUrl = v["cloudinaryUrl"] as? String ?: "",
                                durationMinutes = (v["durationMinutes"] as? Long)?.toInt() ?: 0
                            )
                        }
                    )
                }
            } catch (_: Exception) {}
            loading = false
        }
    }

    LaunchedEffect(Unit) { loadCourses() }

    Box(Modifier.fillMaxSize()) {
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AdmAccent)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { Text("${courses.size} courses", fontSize = 13.sp, color = AdmSubtext, modifier = Modifier.padding(bottom = 4.dp)) }
                items(courses, key = { it.id }) { course ->
                    AdminCourseCard(course, onEdit = { onCourseClick(course) }, onDelete = { deleteTarget = course })
                }
            }
        }

        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = AdmAccent, contentColor = Color.White
        ) { Icon(Icons.Default.Add, "Add Course") }

        if (snack.isNotEmpty()) {
            Snackbar(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp), containerColor = AdmCard) {
                Text(snack, color = AdmText)
            }
            LaunchedEffect(snack) { kotlinx.coroutines.delay(2000); snack = "" }
        }
    }

    if (showAddDialog) {
        AddCourseDialog(onDismiss = { showAddDialog = false }, onSave = { title, desc ->
            scope.launch {
                try {
                    val id = "course_${UUID.randomUUID().toString().take(8)}"
                    db.collection("tutorial_courses").document(id)
                        .set(mapOf("title" to title, "description" to desc, "videos" to emptyList<Any>())).await()
                    snack = "Course created!"
                    loadCourses()
                } catch (_: Exception) { snack = "Error" }
            }
            showAddDialog = false
        })
    }

    deleteTarget?.let { course ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = AdmCard,
            title = { Text("Delete Course?", color = AdmText) },
            text = { Text("\"${course.title}\" delete হবে।", color = AdmSubtext) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            db.collection("tutorial_courses").document(course.id).delete().await()
                            snack = "Deleted!"
                            loadCourses()
                        } catch (_: Exception) {}
                    }
                    deleteTarget = null
                }) { Text("Delete", color = AdmRed) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = AdmAccent) } }
        )
    }
}

@Composable
private fun AdminCourseCard(course: TutorialCourse, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = AdmCard)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp).background(AdmAccent.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.VideoLibrary, null, tint = AdmAccent, modifier = Modifier.size(26.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(course.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AdmText)
                if (course.description.isNotEmpty())
                    Text(course.description, fontSize = 12.sp, color = AdmSubtext, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text("${course.videos.size} videos", fontSize = 11.sp, color = AdmGreen)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit", tint = AdmAccent) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = AdmRed) }
        }
    }
}

@Composable
private fun AddCourseDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AdmCard,
        title = { Text("New Course", color = AdmText, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it },
                    label = { Text("Course Title", color = AdmSubtext) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                    colors = fieldColors(), singleLine = true)
                OutlinedTextField(value = desc, onValueChange = { desc = it },
                    label = { Text("Description (optional)", color = AdmSubtext) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                    colors = fieldColors(), maxLines = 3)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (title.isNotBlank()) onSave(title.trim(), desc.trim()) }, enabled = title.isNotBlank()) {
                Text("Create", color = AdmAccent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = AdmSubtext) } }
    )
}

// ─────────────────────────────────────────────────────────────
// Video Management — Cloudinary upload from phone
// ─────────────────────────────────────────────────────────────
@Composable
fun VideoManagementScreen(course: TutorialCourse, onCourseUpdated: (TutorialCourse) -> Unit) {
    val db = FirebaseFirestore.getInstance()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentVideos by remember { mutableStateOf(course.videos) }
    var deleteTarget by remember { mutableStateOf<TutorialVideo?>(null) }
    var snack by remember { mutableStateOf("") }

    // Upload state
    var uploading by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf(0) }
    var showTitleDialog by remember { mutableStateOf(false) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingTitle by remember { mutableStateOf("") }
    var pendingDuration by remember { mutableStateOf("") }

    // Video picker
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingUri = uri
            pendingTitle = ""
            pendingDuration = ""
            showTitleDialog = true
        }
    }

    fun saveVideos(updated: List<TutorialVideo>) {
        scope.launch {
            try {
                val maps = updated.map { v ->
                    mapOf("id" to v.id, "title" to v.title,
                          "cloudinaryUrl" to v.cloudinaryUrl,
                          "durationMinutes" to v.durationMinutes)
                }
                db.collection("tutorial_courses").document(course.id)
                    .update("videos", maps).await()
                currentVideos = updated
                onCourseUpdated(course.copy(videos = updated))
                snack = "Saved ✓"
            } catch (_: Exception) { snack = "Save failed" }
        }
    }

    fun uploadToCloudinary(uri: Uri, title: String, durationMin: Int) {
        uploading = true
        uploadProgress = 0
        val videoId = "vid_${UUID.randomUUID().toString().take(8)}"

        MediaManager.get().upload(uri)
            .option("resource_type", "video")
            .option("folder", "rajib_tutorial")
            .option("public_id", videoId)
            .callback(object : UploadCallback {
                override fun onStart(requestId: String) {}
                override fun onProgress(requestId: String, bytes: Long, totalBytes: Long) {
                    uploadProgress = ((bytes * 100) / totalBytes).toInt()
                }
                override fun onSuccess(requestId: String, resultData: Map<*, *>) {
                    val url = resultData["secure_url"] as? String ?: ""
                    val newVideo = TutorialVideo(
                        id = videoId, title = title,
                        cloudinaryUrl = url, durationMinutes = durationMin
                    )
                    saveVideos(currentVideos + newVideo)
                    uploading = false
                    snack = "Upload complete ✓"
                }
                override fun onError(requestId: String, error: ErrorInfo) {
                    uploading = false
                    snack = "Upload failed: ${error.description}"
                }
                override fun onReschedule(requestId: String, error: ErrorInfo) {
                    uploading = false
                }
            }).dispatch(context)
    }

    Box(Modifier.fillMaxSize()) {
        if (currentVideos.isEmpty() && !uploading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.VideoLibrary, null, tint = AdmSubtext, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No videos yet", color = AdmSubtext)
                    Spacer(Modifier.height(6.dp))
                    Text("+ বাটন চাপো video upload করতে", color = AdmSubtext.copy(alpha = 0.6f), fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 90.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Upload progress card
                if (uploading) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = AdmCard)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        progress = { uploadProgress / 100f },
                                        modifier = Modifier.size(32.dp),
                                        color = AdmAccent,
                                        strokeWidth = 3.dp
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text("Uploading to Cloudinary...", fontSize = 14.sp, color = AdmText, fontWeight = FontWeight.SemiBold)
                                        Text("$uploadProgress%", fontSize = 12.sp, color = AdmAccent)
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                LinearProgressIndicator(
                                    progress = { uploadProgress / 100f },
                                    modifier = Modifier.fillMaxWidth().height(6.dp),
                                    color = AdmAccent,
                                    trackColor = AdmSubtext.copy(alpha = 0.2f)
                                )
                            }
                        }
                    }
                }

                item { Text("${currentVideos.size} videos", fontSize = 13.sp, color = AdmSubtext, modifier = Modifier.padding(bottom = 4.dp)) }

                items(currentVideos.withIndex().toList(), key = { it.value.id }) { (index, video) ->
                    AdminVideoCard(index = index + 1, video = video, onDelete = { deleteTarget = video })
                }
            }
        }

        // FAB — pick video from phone
        FloatingActionButton(
            onClick = { if (!uploading) videoPicker.launch("video/*") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = if (uploading) AdmSubtext else AdmGreen,
            contentColor = Color.White
        ) {
            if (uploading) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.VideoCall, "Upload Video")
        }

        if (snack.isNotEmpty()) {
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp, start = 16.dp, end = 16.dp),
                containerColor = AdmCard
            ) { Text(snack, color = AdmText) }
            LaunchedEffect(snack) { kotlinx.coroutines.delay(2500); snack = "" }
        }
    }

    // Title + duration dialog (shows after picking video)
    if (showTitleDialog && pendingUri != null) {
        AlertDialog(
            onDismissRequest = { showTitleDialog = false; pendingUri = null },
            containerColor = AdmCard,
            title = { Text("Video Info", color = AdmText, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Video selected ✓", fontSize = 13.sp, color = AdmGreen)
                    OutlinedTextField(
                        value = pendingTitle, onValueChange = { pendingTitle = it },
                        label = { Text("Video Title", color = AdmSubtext) },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                        colors = fieldColors(), singleLine = true,
                        placeholder = { Text("e.g. Lesson 1 - Introduction", color = AdmSubtext.copy(0.4f), fontSize = 12.sp) }
                    )
                    OutlinedTextField(
                        value = pendingDuration, onValueChange = { pendingDuration = it.filter { c -> c.isDigit() } },
                        label = { Text("Duration (minutes, optional)", color = AdmSubtext) },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                        colors = fieldColors(), singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uri = pendingUri ?: return@TextButton
                        if (pendingTitle.isBlank()) return@TextButton
                        showTitleDialog = false
                        uploadToCloudinary(uri, pendingTitle.trim(), pendingDuration.toIntOrNull() ?: 0)
                        pendingUri = null
                    },
                    enabled = pendingTitle.isNotBlank()
                ) { Text("Upload", color = AdmGreen, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showTitleDialog = false; pendingUri = null }) { Text("Cancel", color = AdmSubtext) }
            }
        )
    }

    deleteTarget?.let { video ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = AdmCard,
            title = { Text("Delete Video?", color = AdmText) },
            text = { Text("\"${video.title}\" delete হবে।", color = AdmSubtext) },
            confirmButton = {
                TextButton(onClick = {
                    saveVideos(currentVideos.filter { it.id != video.id })
                    deleteTarget = null
                }) { Text("Delete", color = AdmRed) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = AdmAccent) } }
        )
    }
}

@Composable
private fun AdminVideoCard(index: Int, video: TutorialVideo, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AdmCard)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(36.dp).background(AdmGreen.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center) {
                Text("$index", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AdmGreen)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(video.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AdmText,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (video.cloudinaryUrl.length > 45) video.cloudinaryUrl.take(45) + "…" else video.cloudinaryUrl,
                    fontSize = 11.sp, color = AdmSubtext
                )
                if (video.durationMinutes > 0)
                    Text("${video.durationMinutes} min", fontSize = 11.sp, color = AdmAccent)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = AdmRed) }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// User Enrollment
// ─────────────────────────────────────────────────────────────
@Composable
fun UserEnrollmentScreen() {
    val db = FirebaseFirestore.getInstance()
    val scope = rememberCoroutineScope()
    var searchEmail by remember { mutableStateOf("") }
    var foundUid by remember { mutableStateOf("") }
    var foundEnrolled by remember { mutableStateOf<List<String>>(emptyList()) }
    var allCourses by remember { mutableStateOf<List<TutorialCourse>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var snack by remember { mutableStateOf("") }
    var searchError by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        try {
            val snap = db.collection("tutorial_courses").get().await()
            allCourses = snap.documents.map { TutorialCourse(id = it.id, title = it.getString("title") ?: it.id) }
        } catch (_: Exception) {}
    }

    fun searchUser() {
        if (searchEmail.isBlank()) return
        searching = true; searchError = ""; foundUid = ""; foundEnrolled = emptyList()
        scope.launch {
            try {
                val emailKey = searchEmail.trim().replace(".", "_")
                val emailDoc = db.collection("tutorial_user_emails").document(emailKey).get().await()
                if (!emailDoc.exists()) { searchError = "User not found. User must login once first."; searching = false; return@launch }
                val uid = emailDoc.getString("uid") ?: ""; if (uid.isEmpty()) { searchError = "UID not found."; searching = false; return@launch }
                val userDoc = db.collection("tutorial_users").document(uid).get().await()
                @Suppress("UNCHECKED_CAST")
                foundEnrolled = userDoc.get("enrolledCourses") as? List<String> ?: emptyList()
                foundUid = uid
            } catch (_: Exception) { searchError = "Search failed." }
            searching = false
        }
    }

    fun toggleCourse(courseId: String, give: Boolean) {
        if (foundUid.isEmpty()) return
        scope.launch {
            try {
                val ref = db.collection("tutorial_users").document(foundUid)
                if (give) { ref.update("enrolledCourses", FieldValue.arrayUnion(courseId)).await(); foundEnrolled = foundEnrolled + courseId; snack = "Access দেওয়া হয়েছে ✓" }
                else { ref.update("enrolledCourses", FieldValue.arrayRemove(courseId)).await(); foundEnrolled = foundEnrolled - courseId; snack = "Access সরানো হয়েছে" }
            } catch (_: Exception) { snack = "Update failed" }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("User খোঁজো", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AdmText)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = searchEmail, onValueChange = { searchEmail = it; searchError = "" },
                    label = { Text("Email", color = AdmSubtext) }, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp), colors = fieldColors(), singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = AdmAccent) })
                Button(onClick = ::searchUser, colors = ButtonDefaults.buttonColors(containerColor = AdmAccent),
                    shape = RoundedCornerShape(12.dp), enabled = !searching && searchEmail.isNotBlank()) {
                    if (searching) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Search", color = Color.White)
                }
            }
            if (searchError.isNotEmpty()) Text(searchError, color = AdmRed, fontSize = 13.sp)

            if (foundUid.isNotEmpty()) {
                HorizontalDivider(color = AdmSubtext.copy(alpha = 0.2f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(36.dp).background(AdmGreen.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Person, null, tint = AdmGreen, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(searchEmail.trim(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AdmText)
                        Text("${foundEnrolled.size} courses enrolled", fontSize = 12.sp, color = AdmSubtext)
                    }
                }
                Text("Course Access", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AdmText)
                allCourses.forEach { course ->
                    val enrolled = foundEnrolled.contains(course.id)
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = if (enrolled) AdmGreen.copy(alpha = 0.1f) else AdmCard)) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (enrolled) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                                tint = if (enrolled) AdmGreen else AdmSubtext, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(course.title, modifier = Modifier.weight(1f), fontSize = 14.sp, color = AdmText,
                                fontWeight = if (enrolled) FontWeight.SemiBold else FontWeight.Normal)
                            Switch(checked = enrolled, onCheckedChange = { toggleCourse(course.id, it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AdmGreen,
                                    uncheckedThumbColor = AdmSubtext, uncheckedTrackColor = AdmSubtext.copy(alpha = 0.3f)))
                        }
                    }
                }
            }
            Spacer(Modifier.height(80.dp))
        }

        if (snack.isNotEmpty()) {
            Snackbar(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp), containerColor = AdmCard) {
                Text(snack, color = AdmText)
            }
            LaunchedEffect(snack) { kotlinx.coroutines.delay(2000); snack = "" }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AdmAccent, unfocusedBorderColor = AdmSubtext.copy(alpha = 0.3f),
    focusedTextColor = AdmText, unfocusedTextColor = AdmText, cursorColor = AdmAccent
)

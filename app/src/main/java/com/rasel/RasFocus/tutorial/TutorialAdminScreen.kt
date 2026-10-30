package com.rasel.RasFocus.tutorial

// ============================================================
//  Admin Panel — Rajib Tutorial
//
//  Flow:
//    Admin Login (same Firebase Auth)
//      └── Courses Tab
//            └── Create Course (courseName, title, class, subject)
//            └── Open Course → Video list
//                  └── Upload video from phone → Cloudinary
//      └── Users Tab
//            └── Search by email → toggle course access
//
//  Firestore:
//    tutorial_courses/{id}/
//        courseName, title, className, subject, description
//        videos: [{id, title, cloudinaryUrl, durationMinutes}]
//    tutorial_users/{uid}/enrolledCourses: [courseId, ...]
//    tutorial_user_emails/{email_key}/uid
// ============================================================

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

// ── colours ──────────────────────────────────────────────────
private val AB  = Color(0xFF0F1923)   // bg
private val AC  = Color(0xFF1A2535)   // card
private val AA  = Color(0xFF4A9EFF)   // accent blue
private val AG  = Color(0xFF34C98A)   // green
private val AR  = Color(0xFFFF6B6B)   // red
private val AT  = Color(0xFFE8EDF2)   // text
private val AS  = Color(0xFF8A9BB0)   // subtext
private val AP  = Color(0xFF7B5CF5)   // purple

private enum class ATab { COURSES, USERS }

// ─────────────────────────────────────────────────────────────
// Root
// ─────────────────────────────────────────────────────────────
@Composable
fun TutorialAdminScreen(onBack: () -> Unit) {
    var tab by remember { mutableStateOf(ATab.COURSES) }
    var selectedCourse by remember { mutableStateOf<TutorialCourse?>(null) }

    Column(Modifier.fillMaxSize().background(AB)) {

        // Header
        Box(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xFF0D1F38), AB)))
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = if (selectedCourse != null) ({ selectedCourse = null }) else onBack) {
                    Icon(Icons.Default.ArrowBack, null, tint = AT)
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (selectedCourse != null) selectedCourse!!.title else "Admin Panel",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AT,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (selectedCourse != null) {
                        Text(
                            listOfNotNull(
                                selectedCourse!!.className.ifEmpty { null },
                                selectedCourse!!.subject.ifEmpty { null }
                            ).joinToString(" • ").ifEmpty { "Rajib Tutorial" },
                            fontSize = 12.sp, color = AS
                        )
                    } else {
                        Text("Rajib Tutorial", fontSize = 12.sp, color = AS)
                    }
                }
                Box(
                    Modifier.background(AP.copy(0.2f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) { Text("Admin", fontSize = 11.sp, color = AP, fontWeight = FontWeight.SemiBold) }
            }
        }

        if (selectedCourse != null) {
            VideoManagementScreen(
                course = selectedCourse!!,
                onCourseUpdated = { selectedCourse = it }
            )
        } else {
            TabRow(selectedTabIndex = tab.ordinal, containerColor = AC, contentColor = AA) {
                Tab(selected = tab == ATab.COURSES, onClick = { tab = ATab.COURSES },
                    text = { Text("Courses", color = if (tab == ATab.COURSES) AA else AS) },
                    icon = { Icon(Icons.Default.VideoLibrary, null, tint = if (tab == ATab.COURSES) AA else AS) })
                Tab(selected = tab == ATab.USERS, onClick = { tab = ATab.USERS },
                    text = { Text("Users", color = if (tab == ATab.USERS) AA else AS) },
                    icon = { Icon(Icons.Default.People, null, tint = if (tab == ATab.USERS) AA else AS) })
            }
            when (tab) {
                ATab.COURSES -> CourseManagementScreen(onCourseClick = { selectedCourse = it })
                ATab.USERS   -> UserEnrollmentScreen()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Courses Tab
// ─────────────────────────────────────────────────────────────
@Composable
fun CourseManagementScreen(onCourseClick: (TutorialCourse) -> Unit) {
    val db = FirebaseFirestore.getInstance()
    val scope = rememberCoroutineScope()
    var courses by remember { mutableStateOf<List<TutorialCourse>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showAdd by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<TutorialCourse?>(null) }
    var snack by remember { mutableStateOf("") }

    fun load() {
        scope.launch {
            loading = true
            try {
                val snap = db.collection("tutorial_courses").get().await()
                courses = snap.documents.map { doc ->
                    @Suppress("UNCHECKED_CAST")
                    val vr = doc.get("videos") as? List<Map<String, Any>> ?: emptyList()
                    TutorialCourse(
                        id          = doc.id,
                        courseName  = doc.getString("courseName") ?: "",
                        title       = doc.getString("title") ?: "",
                        className   = doc.getString("className") ?: "",
                        subject     = doc.getString("subject") ?: "",
                        description = doc.getString("description") ?: "",
                        videos      = vr.map { v ->
                            TutorialVideo(
                                id              = v["id"] as? String ?: "",
                                title           = v["title"] as? String ?: "",
                                cloudinaryUrl   = v["cloudinaryUrl"] as? String ?: "",
                                durationMinutes = (v["durationMinutes"] as? Long)?.toInt() ?: 0
                            )
                        }
                    )
                }
            } catch (_: Exception) {}
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    Box(Modifier.fillMaxSize()) {
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AA)
            }
        } else if (courses.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.VideoLibrary, null, tint = AS, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No courses yet", color = AS)
                    Text("+ বাটন চাপো course বানাতে", color = AS.copy(0.6f), fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { Text("${courses.size} courses", fontSize = 13.sp, color = AS, modifier = Modifier.padding(bottom = 4.dp)) }
                items(courses, key = { it.id }) { c ->
                    AdminCourseCard(c, onEdit = { onCourseClick(c) }, onDelete = { deleteTarget = c })
                }
            }
        }

        FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = AA, contentColor = Color.White
        ) { Icon(Icons.Default.Add, "Add") }

        if (snack.isNotEmpty()) {
            Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp), containerColor = AC) {
                Text(snack, color = AT)
            }
            LaunchedEffect(snack) { kotlinx.coroutines.delay(2200); snack = "" }
        }
    }

    if (showAdd) {
        AddCourseDialog(
            onDismiss = { showAdd = false },
            onSave = { cn, ti, cl, su, de ->
                scope.launch {
                    try {
                        val id = "course_${UUID.randomUUID().toString().take(8)}"
                        db.collection("tutorial_courses").document(id).set(
                            mapOf(
                                "courseName"  to cn,
                                "title"       to ti,
                                "className"   to cl,
                                "subject"     to su,
                                "description" to de,
                                "videos"      to emptyList<Any>()
                            )
                        ).await()
                        snack = "Course created ✓"
                        load()
                    } catch (_: Exception) { snack = "Error" }
                }
                showAdd = false
            }
        )
    }

    deleteTarget?.let { c ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = AC,
            title = { Text("Delete Course?", color = AT) },
            text = { Text("\"${c.title}\" permanently delete হবে।", color = AS) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        db.collection("tutorial_courses").document(c.id).delete().await()
                        snack = "Deleted"
                        load()
                    }
                    deleteTarget = null
                }) { Text("Delete", color = AR) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = AA) } }
        )
    }
}

@Composable
private fun AdminCourseCard(c: TutorialCourse, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = AC)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(50.dp).background(AA.copy(0.15f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.PlayCircle, null, tint = AA, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (c.courseName.isNotEmpty())
                    Text(c.courseName, fontSize = 11.sp, color = AP, fontWeight = FontWeight.Medium)
                Text(c.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AT,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(c.className.ifEmpty{null}, c.subject.ifEmpty{null}).joinToString(" • ")
                if (meta.isNotEmpty())
                    Text(meta, fontSize = 12.sp, color = AS)
                Spacer(Modifier.height(4.dp))
                Text("${c.videos.size} videos", fontSize = 11.sp, color = AG)
            }
            IconButton(onClick = onEdit)   { Icon(Icons.Default.Edit,   "Edit",   tint = AA) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = AR) }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Add Course Dialog — courseName, title, class, subject, desc
// ─────────────────────────────────────────────────────────────
@Composable
private fun AddCourseDialog(
    onDismiss: () -> Unit,
    onSave: (courseName: String, title: String, className: String, subject: String, description: String) -> Unit
) {
    var courseName by remember { mutableStateOf("") }
    var title      by remember { mutableStateOf("") }
    var className  by remember { mutableStateOf("") }
    var subject    by remember { mutableStateOf("") }
    var desc       by remember { mutableStateOf("") }

    // Class dropdown
    val classes = listOf("Class 1","Class 2","Class 3","Class 4","Class 5",
                         "Class 6","Class 7","Class 8","Class 9","Class 10",
                         "Class 11","Class 12","HSC","SSC","Other")
    var classExpanded by remember { mutableStateOf(false) }

    // Subject dropdown
    val subjects = listOf("Bangla","English","Math","Science","Physics","Chemistry",
                          "Biology","ICT","History","Geography","Economics","Other")
    var subjectExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AC,
        title = { Text("New Course", color = AT, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Course Name
                OutlinedTextField(
                    value = courseName, onValueChange = { courseName = it },
                    label = { Text("Course Name *", color = AS) },
                    placeholder = { Text("e.g. Rajib Math Course", color = AS.copy(0.4f), fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                    colors = fc(), singleLine = true
                )
                // Course Title
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("Course Title *", color = AS) },
                    placeholder = { Text("e.g. Math Master 2025", color = AS.copy(0.4f), fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                    colors = fc(), singleLine = true
                )

                // Class dropdown
                ExposedDropdownMenuBox(expanded = classExpanded, onExpandedChange = { classExpanded = it }) {
                    OutlinedTextField(
                        value = className, onValueChange = {},
                        readOnly = true,
                        label = { Text("Class", color = AS) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(classExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        shape = RoundedCornerShape(10.dp), colors = fc()
                    )
                    ExposedDropdownMenu(
                        expanded = classExpanded,
                        onDismissRequest = { classExpanded = false },
                        modifier = Modifier.background(AC)
                    ) {
                        classes.forEach { cl ->
                            DropdownMenuItem(
                                text = { Text(cl, color = AT) },
                                onClick = { className = cl; classExpanded = false }
                            )
                        }
                    }
                }

                // Subject dropdown
                ExposedDropdownMenuBox(expanded = subjectExpanded, onExpandedChange = { subjectExpanded = it }) {
                    OutlinedTextField(
                        value = subject, onValueChange = {},
                        readOnly = true,
                        label = { Text("Subject", color = AS) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(subjectExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        shape = RoundedCornerShape(10.dp), colors = fc()
                    )
                    ExposedDropdownMenu(
                        expanded = subjectExpanded,
                        onDismissRequest = { subjectExpanded = false },
                        modifier = Modifier.background(AC)
                    ) {
                        subjects.forEach { su ->
                            DropdownMenuItem(
                                text = { Text(su, color = AT) },
                                onClick = { subject = su; subjectExpanded = false }
                            )
                        }
                    }
                }

                // Description (optional)
                OutlinedTextField(
                    value = desc, onValueChange = { desc = it },
                    label = { Text("Description (optional)", color = AS) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                    colors = fc(), maxLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (courseName.isNotBlank() && title.isNotBlank())
                        onSave(courseName.trim(), title.trim(), className, subject, desc.trim())
                },
                enabled = courseName.isNotBlank() && title.isNotBlank()
            ) { Text("Create", color = AA, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = AS) } }
    )
}

// ─────────────────────────────────────────────────────────────
// Video Management — phone gallery → Cloudinary upload
// ─────────────────────────────────────────────────────────────
@Composable
fun VideoManagementScreen(course: TutorialCourse, onCourseUpdated: (TutorialCourse) -> Unit) {
    val db = FirebaseFirestore.getInstance()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var videos by remember { mutableStateOf(course.videos) }
    var deleteTarget by remember { mutableStateOf<TutorialVideo?>(null) }
    var snack by remember { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf(0) }
    var showTitleDialog by remember { mutableStateOf(false) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingTitle by remember { mutableStateOf("") }
    var pendingDuration by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { pendingUri = uri; pendingTitle = ""; pendingDuration = ""; showTitleDialog = true }
    }

    fun saveVideos(updated: List<TutorialVideo>) {
        scope.launch {
            try {
                db.collection("tutorial_courses").document(course.id)
                    .update("videos", updated.map { v ->
                        mapOf("id" to v.id, "title" to v.title,
                              "cloudinaryUrl" to v.cloudinaryUrl,
                              "durationMinutes" to v.durationMinutes)
                    }).await()
                videos = updated
                onCourseUpdated(course.copy(videos = updated))
                snack = "Saved ✓"
            } catch (_: Exception) { snack = "Save failed" }
        }
    }

    fun upload(uri: Uri, title: String, dur: Int) {
        uploading = true; uploadProgress = 0
        val vid = "vid_${UUID.randomUUID().toString().take(8)}"
        MediaManager.get().upload(uri)
            .option("resource_type", "video")
            .option("folder", "rajib_tutorial")
            .option("public_id", vid)
            .callback(object : UploadCallback {
                override fun onStart(requestId: String) {}
                override fun onProgress(requestId: String, bytes: Long, totalBytes: Long) {
                    uploadProgress = ((bytes * 100) / totalBytes).toInt()
                }
                override fun onSuccess(requestId: String, resultData: Map<*, *>) {
                    val url = resultData["secure_url"] as? String ?: ""
                    saveVideos(videos + TutorialVideo(id = vid, title = title, cloudinaryUrl = url, durationMinutes = dur))
                    uploading = false; snack = "Upload complete ✓"
                }
                override fun onError(requestId: String, error: ErrorInfo) {
                    uploading = false; snack = "Upload failed: ${error.description}"
                }
                override fun onReschedule(requestId: String, error: ErrorInfo) { uploading = false }
            }).dispatch(context)
    }

    Box(Modifier.fillMaxSize()) {
        if (videos.isEmpty() && !uploading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.VideoCall, null, tint = AS, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No videos yet", color = AS)
                    Text("+ বাটন চাপো phone থেকে video upload করতে", color = AS.copy(0.6f), fontSize = 12.sp)
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
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = AC)) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        progress = { uploadProgress / 100f },
                                        modifier = Modifier.size(36.dp), color = AA, strokeWidth = 3.dp
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Column {
                                        Text("Uploading to Cloudinary...", fontSize = 14.sp, color = AT, fontWeight = FontWeight.SemiBold)
                                        Text("$uploadProgress%", fontSize = 12.sp, color = AA)
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                LinearProgressIndicator(
                                    progress = { uploadProgress / 100f },
                                    modifier = Modifier.fillMaxWidth().height(6.dp),
                                    color = AA, trackColor = AS.copy(0.2f)
                                )
                            }
                        }
                    }
                }

                item { Text("${videos.size} videos", fontSize = 13.sp, color = AS, modifier = Modifier.padding(bottom = 4.dp)) }

                items(videos.withIndex().toList(), key = { it.value.id }) { (idx, v) ->
                    VideoCard(idx + 1, v, onDelete = { deleteTarget = v })
                }
            }
        }

        // FAB
        FloatingActionButton(
            onClick = { if (!uploading) picker.launch("video/*") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = if (uploading) AS else AG, contentColor = Color.White
        ) {
            if (uploading) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.VideoCall, "Upload")
        }

        if (snack.isNotEmpty()) {
            Snackbar(Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp, start = 16.dp, end = 16.dp),
                containerColor = AC) { Text(snack, color = AT) }
            LaunchedEffect(snack) { kotlinx.coroutines.delay(2500); snack = "" }
        }
    }

    // Title dialog after picking video
    if (showTitleDialog && pendingUri != null) {
        AlertDialog(
            onDismissRequest = { showTitleDialog = false; pendingUri = null },
            containerColor = AC,
            title = { Text("Video Info", color = AT, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = AG, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Video selected", fontSize = 13.sp, color = AG)
                    }
                    OutlinedTextField(
                        value = pendingTitle, onValueChange = { pendingTitle = it },
                        label = { Text("Video Title *", color = AS) },
                        placeholder = { Text("e.g. Lesson 1 - Introduction", color = AS.copy(0.4f), fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                        colors = fc(), singleLine = true
                    )
                    OutlinedTextField(
                        value = pendingDuration, onValueChange = { pendingDuration = it.filter { c -> c.isDigit() } },
                        label = { Text("Duration (minutes, optional)", color = AS) },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                        colors = fc(), singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uri = pendingUri ?: return@TextButton
                        if (pendingTitle.isBlank()) return@TextButton
                        showTitleDialog = false
                        upload(uri, pendingTitle.trim(), pendingDuration.toIntOrNull() ?: 0)
                        pendingUri = null
                    },
                    enabled = pendingTitle.isNotBlank()
                ) { Text("Upload", color = AG, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showTitleDialog = false; pendingUri = null }) { Text("Cancel", color = AS) }
            }
        )
    }

    deleteTarget?.let { v ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null }, containerColor = AC,
            title = { Text("Delete Video?", color = AT) },
            text = { Text("\"${v.title}\" delete হবে।", color = AS) },
            confirmButton = {
                TextButton(onClick = { saveVideos(videos.filter { it.id != v.id }); deleteTarget = null }) {
                    Text("Delete", color = AR)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = AA) } }
        )
    }
}

@Composable
private fun VideoCard(index: Int, v: TutorialVideo, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AC)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).background(AG.copy(0.15f), CircleShape), contentAlignment = Alignment.Center) {
                Text("$index", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AG)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(v.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AT,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(if (v.cloudinaryUrl.length > 45) v.cloudinaryUrl.take(45) + "…" else v.cloudinaryUrl,
                    fontSize = 11.sp, color = AS)
                if (v.durationMinutes > 0)
                    Text("${v.durationMinutes} min", fontSize = 11.sp, color = AA)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = AR) }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Users Tab
// ─────────────────────────────────────────────────────────────
@Composable
fun UserEnrollmentScreen() {
    val db = FirebaseFirestore.getInstance()
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var foundUid by remember { mutableStateOf("") }
    var enrolled by remember { mutableStateOf<List<String>>(emptyList()) }
    var allCourses by remember { mutableStateOf<List<TutorialCourse>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var snack by remember { mutableStateOf("") }
    var searchError by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        try {
            val snap = db.collection("tutorial_courses").get().await()
            allCourses = snap.documents.map { doc ->
                TutorialCourse(
                    id         = doc.id,
                    courseName = doc.getString("courseName") ?: "",
                    title      = doc.getString("title") ?: doc.id,
                    className  = doc.getString("className") ?: "",
                    subject    = doc.getString("subject") ?: ""
                )
            }
        } catch (_: Exception) {}
    }

    fun search() {
        if (email.isBlank()) return
        searching = true; searchError = ""; foundUid = ""; enrolled = emptyList()
        scope.launch {
            try {
                val key = email.trim().replace(".", "_")
                val doc = db.collection("tutorial_user_emails").document(key).get().await()
                if (!doc.exists()) { searchError = "User not found. User must login at least once."; searching = false; return@launch }
                val uid = doc.getString("uid") ?: ""; if (uid.isEmpty()) { searchError = "UID not found."; searching = false; return@launch }
                val ud = db.collection("tutorial_users").document(uid).get().await()
                @Suppress("UNCHECKED_CAST")
                enrolled = ud.get("enrolledCourses") as? List<String> ?: emptyList()
                foundUid = uid
            } catch (_: Exception) { searchError = "Search failed." }
            searching = false
        }
    }

    fun toggle(courseId: String, give: Boolean) {
        if (foundUid.isEmpty()) return
        scope.launch {
            try {
                val ref = db.collection("tutorial_users").document(foundUid)
                if (give) { ref.update("enrolledCourses", FieldValue.arrayUnion(courseId)).await(); enrolled = enrolled + courseId; snack = "Access দেওয়া হয়েছে ✓" }
                else       { ref.update("enrolledCourses", FieldValue.arrayRemove(courseId)).await(); enrolled = enrolled - courseId; snack = "Access সরানো হয়েছে" }
            } catch (_: Exception) { snack = "Update failed" }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("User খোঁজো", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AT)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = email, onValueChange = { email = it; searchError = "" },
                    label = { Text("Email", color = AS) }, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp), colors = fc(), singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = AA) }
                )
                Button(onClick = ::search,
                    colors = ButtonDefaults.buttonColors(containerColor = AA),
                    shape = RoundedCornerShape(12.dp), enabled = !searching && email.isNotBlank()) {
                    if (searching) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Search", color = Color.White)
                }
            }

            if (searchError.isNotEmpty()) Text(searchError, color = AR, fontSize = 13.sp)

            if (foundUid.isNotEmpty()) {
                HorizontalDivider(color = AS.copy(0.2f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).background(AG.copy(0.15f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Person, null, tint = AG, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(email.trim(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AT)
                        Text("${enrolled.size} courses enrolled", fontSize = 12.sp, color = AS)
                    }
                }

                Text("Course Access", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AT)

                allCourses.forEach { c ->
                    val isOn = enrolled.contains(c.id)
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = if (isOn) AG.copy(0.1f) else AC)) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                if (c.courseName.isNotEmpty())
                                    Text(c.courseName, fontSize = 11.sp, color = AP)
                                Text(c.title, fontSize = 14.sp, color = AT, fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Normal)
                                val meta = listOfNotNull(c.className.ifEmpty{null}, c.subject.ifEmpty{null}).joinToString(" • ")
                                if (meta.isNotEmpty()) Text(meta, fontSize = 11.sp, color = AS)
                            }
                            Switch(checked = isOn, onCheckedChange = { toggle(c.id, it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White,
                                    checkedTrackColor = AG, uncheckedThumbColor = AS, uncheckedTrackColor = AS.copy(0.3f)))
                        }
                    }
                }
            }
            Spacer(Modifier.height(80.dp))
        }

        if (snack.isNotEmpty()) {
            Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp), containerColor = AC) {
                Text(snack, color = AT)
            }
            LaunchedEffect(snack) { kotlinx.coroutines.delay(2000); snack = "" }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Field colours helper
// ─────────────────────────────────────────────────────────────
@Composable
private fun fc() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AA, unfocusedBorderColor = AS.copy(0.3f),
    focusedTextColor = AT, unfocusedTextColor = AT, cursorColor = AA
)

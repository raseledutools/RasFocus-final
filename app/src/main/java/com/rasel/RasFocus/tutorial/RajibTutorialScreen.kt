package com.rasel.RasFocus.tutorial

// ============================================================
//  Rajib Tutorial — Course & Video System
//
//  Screens:
//    RajibTutorialRoot        → entry point
//    CourseListScreen         → course list + 3-dot sidebar with Admin Login
//    VideoListScreen          → videos inside a course
//    VideoPlayerScreen        → ExoPlayer + FLAG_SECURE
//
//  Admin Access:
//    3-dot menu → Admin Login → password: 1111 → TutorialAdminScreen
//    (No Firebase auth required for admin access)
//
//  Firestore structure:
//    tutorial_courses/{courseId}/
//        title: String
//        description: String
//        videos: [ {id, title, cloudinaryUrl} ]
//    tutorial_users/{uid}/enrolledCourses: ["course_001", ...]
// ============================================================

import android.app.Activity
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// ─────────────────────────────────────────────────────────────
// Data models
// ─────────────────────────────────────────────────────────────

data class TutorialVideo(
    val id: String = "",
    val title: String = "",
    val cloudinaryUrl: String = "",
    val durationMinutes: Int = 0
)

data class TutorialCourse(
    val id: String = "",
    val courseName: String = "",
    val title: String = "",
    val className: String = "",
    val subject: String = "",
    val description: String = "",
    val thumbnailUrl: String = "",
    val videos: List<TutorialVideo> = emptyList()
)

// ─────────────────────────────────────────────────────────────
// Colours
// ─────────────────────────────────────────────────────────────

private val TutBg       = Color(0xFF0F1923)
private val TutCard     = Color(0xFF1A2535)
private val TutAccent   = Color(0xFF4A9EFF)
private val TutAccent2  = Color(0xFF7B5CF5)
private val TutText     = Color(0xFFE8EDF2)
private val TutSubtext  = Color(0xFF8A9BB0)

// Admin password — change here if needed
private const val ADMIN_PASSWORD = "1111"

// ─────────────────────────────────────────────────────────────
// Root — navigation controller
// ─────────────────────────────────────────────────────────────

@Composable
fun RajibTutorialRoot(onBack: () -> Unit) {
    var selectedCourse  by remember { mutableStateOf<TutorialCourse?>(null) }
    var selectedVideo   by remember { mutableStateOf<TutorialVideo?>(null) }
    var showAdminPanel  by remember { mutableStateOf(false) }

    when {
        showAdminPanel -> TutorialAdminScreen(onBack = { showAdminPanel = false })
        selectedVideo != null -> VideoPlayerScreen(
            video = selectedVideo!!,
            onBack = { selectedVideo = null }
        )
        selectedCourse != null -> VideoListScreen(
            course = selectedCourse!!,
            onVideoClick = { selectedVideo = it },
            onBack = { selectedCourse = null }
        )
        else -> CourseListScreen(
            onCourseClick = { selectedCourse = it },
            onAdminAccess = { showAdminPanel = true },
            onBack = onBack
        )
    }
}

// ─────────────────────────────────────────────────────────────
// Admin Login Dialog — password only, no Firebase
// ─────────────────────────────────────────────────────────────

@Composable
private fun AdminLoginDialog(
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    var password      by remember { mutableStateOf("") }
    var showPassword  by remember { mutableStateOf(false) }
    var errorMsg      by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TutCard,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(TutAccent2.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.AdminPanelSettings,
                        contentDescription = null,
                        tint = TutAccent2,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Admin Login", color = TutText, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("Admin panel এ প্রবেশ করুন", color = TutSubtext, fontSize = 11.sp)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; errorMsg = "" },
                    label = { Text("Password", color = TutSubtext) },
                    placeholder = { Text("Enter admin password", color = TutSubtext.copy(alpha = 0.4f), fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                                tint = TutSubtext
                            )
                        }
                    },
                    isError = errorMsg.isNotEmpty(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TutAccent2,
                        unfocusedBorderColor = TutSubtext.copy(alpha = 0.3f),
                        focusedTextColor = TutText,
                        unfocusedTextColor = TutText,
                        cursorColor = TutAccent2,
                        errorBorderColor = Color(0xFFFF6B6B),
                        errorTextColor = TutText
                    )
                )
                if (errorMsg.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = Color(0xFFFF6B6B),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(errorMsg, color = Color(0xFFFF6B6B), fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (password == ADMIN_PASSWORD) {
                        onSuccess()
                    } else {
                        errorMsg = "Incorrect password"
                        password = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = TutAccent2),
                shape = RoundedCornerShape(10.dp),
                enabled = password.isNotEmpty()
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Login", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TutSubtext)
            }
        }
    )
}

// ─────────────────────────────────────────────────────────────
// Course List Screen — with 3-dot sidebar for Admin Login
// ─────────────────────────────────────────────────────────────

@Composable
fun CourseListScreen(
    onCourseClick: (TutorialCourse) -> Unit,
    onAdminAccess: () -> Unit,
    onBack: () -> Unit
) {
    val db          = FirebaseFirestore.getInstance()
    val auth        = FirebaseAuth.getInstance()
    val currentUser = auth.currentUser
    val uid         = currentUser?.uid ?: ""
    val userEmail   = currentUser?.email ?: ""

    var courses         by remember { mutableStateOf<List<TutorialCourse>>(emptyList()) }
    var loading         by remember { mutableStateOf(true) }
    var showMenu        by remember { mutableStateOf(false) }   // 3-dot dropdown
    var showAdminLogin  by remember { mutableStateOf(false) }   // admin login dialog
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            val snap = db.collection("tutorial_courses").get().await()
            val loaded = mutableListOf<TutorialCourse>()

            // enrolled courses (if user logged in)
            var enrolledIds: List<String> = emptyList()
            if (uid.isNotEmpty()) {
                val userDoc = db.collection("tutorial_users").document(uid).get().await()
                @Suppress("UNCHECKED_CAST")
                enrolledIds = userDoc.get("enrolledCourses") as? List<String> ?: emptyList()
            }

            for (courseDoc in snap.documents) {
                if (!courseDoc.exists()) continue
                // Show all courses if no user, else show enrolled + public
                if (uid.isNotEmpty() && !enrolledIds.contains(courseDoc.id)) continue

                @Suppress("UNCHECKED_CAST")
                val videosRaw = courseDoc.get("videos") as? List<Map<String, Any>> ?: emptyList()
                val videos = videosRaw.map { v ->
                    TutorialVideo(
                        id = v["id"] as? String ?: "",
                        title = v["title"] as? String ?: "",
                        cloudinaryUrl = v["cloudinaryUrl"] as? String ?: "",
                        durationMinutes = (v["durationMinutes"] as? Long)?.toInt() ?: 0
                    )
                }
                loaded.add(
                    TutorialCourse(
                        id = courseDoc.id,
                        courseName = courseDoc.getString("courseName") ?: "",
                        title = courseDoc.getString("title") ?: "",
                        className = courseDoc.getString("className") ?: "",
                        subject = courseDoc.getString("subject") ?: "",
                        description = courseDoc.getString("description") ?: "",
                        videos = videos
                    )
                )
            }
            // If uid empty → show all courses (no enrollment filter)
            if (uid.isEmpty()) {
                val allLoaded = mutableListOf<TutorialCourse>()
                for (courseDoc in snap.documents) {
                    if (!courseDoc.exists()) continue
                    @Suppress("UNCHECKED_CAST")
                    val videosRaw = courseDoc.get("videos") as? List<Map<String, Any>> ?: emptyList()
                    val videos = videosRaw.map { v ->
                        TutorialVideo(
                            id = v["id"] as? String ?: "",
                            title = v["title"] as? String ?: "",
                            cloudinaryUrl = v["cloudinaryUrl"] as? String ?: "",
                            durationMinutes = (v["durationMinutes"] as? Long)?.toInt() ?: 0
                        )
                    }
                    allLoaded.add(
                        TutorialCourse(
                            id = courseDoc.id,
                            courseName = courseDoc.getString("courseName") ?: "",
                            title = courseDoc.getString("title") ?: "",
                            className = courseDoc.getString("className") ?: "",
                            subject = courseDoc.getString("subject") ?: "",
                            description = courseDoc.getString("description") ?: "",
                            videos = videos
                        )
                    )
                }
                courses = allLoaded
            } else {
                courses = loaded
            }
        } catch (_: Exception) {}
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TutBg)
    ) {
        // ── Header ───────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF0D1F38), TutBg))
                )
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = null, tint = TutText)
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Rajib Tutorial",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TutText
                    )
                    if (userEmail.isNotEmpty()) {
                        Text(
                            userEmail,
                            fontSize = 12.sp,
                            color = TutSubtext,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            "EEE Related Courses",
                            fontSize = 12.sp,
                            color = TutSubtext
                        )
                    }
                }

                // ── 3-dot Menu ───────────────────────────────
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Menu",
                            tint = TutText
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(TutCard)
                    ) {
                        // Admin Login option
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.AdminPanelSettings,
                                        contentDescription = null,
                                        tint = TutAccent2,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        "Admin Login",
                                        color = TutText,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            },
                            onClick = {
                                showMenu = false
                                showAdminLogin = true
                            }
                        )
                        HorizontalDivider(
                            color = TutSubtext.copy(alpha = 0.15f),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        // About option
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        tint = TutSubtext,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text("About", color = TutSubtext, fontSize = 14.sp)
                                }
                            },
                            onClick = { showMenu = false }
                        )
                    }
                }
            }
        }

        // ── Course list ───────────────────────────────────────
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TutAccent)
            }
        } else if (courses.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.VideoLibrary,
                        contentDescription = null,
                        tint = TutSubtext,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("No courses available", color = TutSubtext, fontSize = 16.sp)
                    Text(
                        "Contact admin for access",
                        color = TutSubtext.copy(alpha = 0.6f),
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        "Courses (${courses.size})",
                        fontSize = 15.sp,
                        color = TutSubtext,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                items(courses) { course ->
                    CourseCard(course = course, onClick = { onCourseClick(course) })
                }
            }
        }
    }

    // ── Admin Login Dialog ────────────────────────────────────
    if (showAdminLogin) {
        AdminLoginDialog(
            onDismiss = { showAdminLogin = false },
            onSuccess = {
                showAdminLogin = false
                onAdminAccess()
            }
        )
    }
}

@Composable
private fun CourseCard(course: TutorialCourse, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = TutCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(TutAccent.copy(alpha = 0.3f), TutAccent2.copy(alpha = 0.3f))
                        ),
                        RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PlayCircle,
                    contentDescription = null,
                    tint = TutAccent,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                if (course.courseName.isNotEmpty())
                    Text(
                        course.courseName,
                        fontSize = 13.sp,
                        color = TutAccent2,
                        fontWeight = FontWeight.Medium
                    )
                Text(
                    course.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TutText
                )
                if (course.className.isNotEmpty() || course.subject.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        listOfNotNull(
                            course.className.ifEmpty { null },
                            course.subject.ifEmpty { null }
                        ).joinToString(" • "),
                        fontSize = 12.sp,
                        color = TutSubtext
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text("${course.videos.size} videos", fontSize = 11.sp, color = TutAccent)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TutSubtext)
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Video List Screen
// ─────────────────────────────────────────────────────────────

@Composable
fun VideoListScreen(
    course: TutorialCourse,
    onVideoClick: (TutorialVideo) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TutBg)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xFF0D1F38), TutBg)))
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = null, tint = TutText)
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        course.title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TutText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("${course.videos.size} videos", fontSize = 12.sp, color = TutSubtext)
                }
            }
        }

        if (course.videos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No videos in this course yet", color = TutSubtext)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(course.videos.withIndex().toList()) { (index, video) ->
                    VideoListItem(
                        index = index + 1,
                        video = video,
                        onClick = { onVideoClick(video) }
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoListItem(index: Int, video: TutorialVideo, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = TutCard)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(TutAccent.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("$index", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TutAccent)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    video.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = TutText
                )
                if (video.durationMinutes > 0) {
                    Spacer(Modifier.height(3.dp))
                    Text("${video.durationMinutes} min", fontSize = 11.sp, color = TutSubtext)
                }
            }
            Icon(
                Icons.Default.PlayCircleFilled,
                contentDescription = null,
                tint = TutAccent,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Video Player Screen — FLAG_SECURE, no screenshot/record
// ─────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(video: TutorialVideo, onBack: () -> Unit) {
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    if (video.cloudinaryUrl.isBlank()) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.Start)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = null, tint = Color.White)
                }
                Spacer(Modifier.height(32.dp))
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = Color(0xFFFF6B6B),
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Video not available",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "URL is missing. Admin must re-upload.",
                    color = Color(0xFF8A9BB0),
                    fontSize = 13.sp
                )
            }
        }
        return
    }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(video.cloudinaryUrl)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = null, tint = Color.White)
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    video.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        AndroidView(
            factory = {
                PlayerView(it).apply {
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = 3000
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        )

        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp)
        ) {
            Text(
                video.title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            if (video.durationMinutes > 0) {
                Spacer(Modifier.height(6.dp))
                Text("${video.durationMinutes} min", fontSize = 13.sp, color = Color(0xFF8A9BB0))
            }
        }
    }
}

package com.rasel.RasFocus.tutorial

// ============================================================
//  Rajib Tutorial — Course & Video System
//
//  Screens:
//    RajibTutorialRoot        → entry point (checks login)
//    TutorialLoginScreen      → email/password login
//    CourseListScreen         → enrolled courses only
//    VideoListScreen          → videos inside a course
//    VideoPlayerScreen        → ExoPlayer + FLAG_SECURE
//
//  Firestore structure:
//    tutorial_users/{uid}/enrolledCourses: ["course_001", ...]
//    tutorial_courses/{courseId}/
//        title: String
//        description: String
//        videos: [ {id, title, cloudinaryUrl} ]
//
//  Admin: set tutorial_users/{uid}/role = "admin" in Firestore
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

// ─────────────────────────────────────────────────────────────
// Root — decides which screen to show
// ─────────────────────────────────────────────────────────────

@Composable
fun RajibTutorialRoot(onBack: () -> Unit) {
    // RasFocus এর existing login session use করি — আলাদা login নেই
    var selectedCourse by remember { mutableStateOf<TutorialCourse?>(null) }
    var selectedVideo by remember { mutableStateOf<TutorialVideo?>(null) }

    when {
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
            onBack = onBack
        )
    }
}

// ─────────────────────────────────────────────────────────────
// Course List Screen
// ─────────────────────────────────────────────────────────────

@Composable
fun CourseListScreen(
    onCourseClick: (TutorialCourse) -> Unit,
    onBack: () -> Unit
) {
    val db = FirebaseFirestore.getInstance()
    val auth = FirebaseAuth.getInstance()
    val uid = auth.currentUser?.uid ?: ""
    val userEmail = auth.currentUser?.email ?: ""
    val db2 = FirebaseFirestore.getInstance()
    // First visit এ uid-email mapping store করো
    LaunchedEffect(uid) {
        if (uid.isNotEmpty() && userEmail.isNotEmpty()) {
            val emailKey = userEmail.replace(".", "_")
            db2.collection("tutorial_user_emails").document(emailKey)
                .set(mapOf("uid" to uid, "email" to userEmail),
                    com.google.firebase.firestore.SetOptions.merge())
            db2.collection("tutorial_users").document(uid)
                .set(mapOf("email" to userEmail),
                    com.google.firebase.firestore.SetOptions.merge())
        }
    }

    var courses by remember { mutableStateOf<List<TutorialCourse>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var isAdmin by remember { mutableStateOf(false) }
    var showAdminPanel by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Load enrolled courses
    LaunchedEffect(uid) {
        if (uid.isEmpty()) return@LaunchedEffect
        try {
            // Check admin role
            val userDoc = db.collection("tutorial_users").document(uid).get().await()
            isAdmin = userDoc.getString("role") == "admin"

            // Get enrolled course IDs
            @Suppress("UNCHECKED_CAST")
            val enrolledIds = userDoc.get("enrolledCourses") as? List<String> ?: emptyList()

            if (enrolledIds.isEmpty()) {
                loading = false
                return@LaunchedEffect
            }

            // Fetch each course
            val loaded = mutableListOf<TutorialCourse>()
            for (courseId in enrolledIds) {
                val courseDoc = db.collection("tutorial_courses").document(courseId).get().await()
                if (courseDoc.exists()) {
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
            }
            courses = loaded
        } catch (_: Exception) {}
        loading = false
    }

    if (showAdminPanel) {
        TutorialAdminScreen(onBack = { showAdminPanel = false })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TutBg)
    ) {
        // Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF0D1F38), TutBg))
                )
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = null, tint = TutText)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Rajib Tutorial", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TutText)
                    Text(userEmail, fontSize = 12.sp, color = TutSubtext, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (isAdmin) {
                    // Admin Panel button
                    Box(
                        modifier = Modifier
                            .background(TutAccent2.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            .clickable { showAdminPanel = true }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Admin ⚙", fontSize = 11.sp, color = TutAccent2, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(8.dp))
                }

            }
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TutAccent)
            }
        } else if (courses.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = TutSubtext, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("No courses enrolled yet", color = TutSubtext, fontSize = 16.sp)
                    Text("Contact admin for access", color = TutSubtext.copy(alpha = 0.6f), fontSize = 13.sp)
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
                        "My Courses (${courses.size})",
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
                        Brush.linearGradient(listOf(TutAccent.copy(alpha = 0.3f), TutAccent2.copy(alpha = 0.3f))),
                        RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PlayCircle, contentDescription = null, tint = TutAccent, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                if (course.courseName.isNotEmpty())
                    Text(course.courseName, fontSize = 13.sp, color = TutAccent2, fontWeight = FontWeight.Medium)
                Text(course.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TutText)
                if (course.className.isNotEmpty() || course.subject.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        listOfNotNull(
                            course.className.ifEmpty { null },
                            course.subject.ifEmpty { null }
                        ).joinToString(" • "),
                        fontSize = 12.sp, color = TutSubtext
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
        // Header
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
                    Text(course.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TutText, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            // Index badge
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
                Text(video.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TutText)
                if (video.durationMinutes > 0) {
                    Spacer(Modifier.height(3.dp))
                    Text("${video.durationMinutes} min", fontSize = 11.sp, color = TutSubtext)
                }
            }
            Icon(Icons.Default.PlayCircleFilled, contentDescription = null, tint = TutAccent, modifier = Modifier.size(28.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Video Player Screen — FLAG_SECURE, no download
// ─────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(video: TutorialVideo, onBack: () -> Unit) {
    val context = LocalContext.current

    // Enable FLAG_SECURE — blocks screenshot + screen record
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
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
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Top bar
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

        // Video player — full width, 16:9
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    this.player = player
                    useController = true
                    // Hide download button — not available in PlayerView by default, but ensure no save option
                    controllerShowTimeoutMs = 3000
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        )

        // Video info below player
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(video.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            if (video.durationMinutes > 0) {
                Spacer(Modifier.height(6.dp))
                Text("${video.durationMinutes} min", fontSize = 13.sp, color = Color(0xFF8A9BB0))
            }
        }
    }
}

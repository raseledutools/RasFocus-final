package com.rasel.RasFocus.selfcontrol.rasgram

import android.Manifest
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import com.google.firebase.firestore.PersistentCacheSettings
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.media.MediaScannerConnection
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import android.provider.ContactsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.firestore.*
import com.google.firebase.firestore.ktx.snapshots
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okio.source  // FIX #4: correct import for extension function
import org.json.JSONObject
import org.webrtc.*
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// ==================== CONSTANTS ====================
const val CLOUDINARY_CLOUD_NAME = "de2w78yxh"
const val CLOUDINARY_UPLOAD_URL = "https://api.cloudinary.com/v1_1/de2w78yxh/auto/upload"
const val CLOUDINARY_UPLOAD_PRESET = "ml_default"
const val PREF_NAME = "rasgram_prefs"
const val PREF_MOBILE = "saved_mobile"
const val PREF_NAME_KEY = "saved_name"
const val PREF_UID = "saved_uid"
const val PREF_AVATAR = "saved_avatar"
const val PREF_CALL_DELIVERY = "call_delivery_method"
const val PREF_SA_JSON = "service_account_json"
const val PREF_LAN_MODE = "lan_mode_enabled"   // LAN/Local mode toggle
const val MAX_RETRY = 3
const val TYPING_DEBOUNCE_MS = 2500L
const val ONLINE_THRESHOLD_MS = 120_000L
const val MESSAGE_PAGE_SIZE = 30L

// ==================== DATA CLASSES ====================
data class User(
    val uid: String = "",
    val name: String = "",
    val mobile: String = "",
    val avatarUrl: String = "",
    val lastActive: Long = 0,
    val typingTo: String? = null,
    val statusVisible: Boolean = true,
    val about: String = "Hey there! I am using RasGram.",
    val fcmToken: String = "",
    val isBlocked: Boolean = false
)

data class Message(
    val id: String = "",
    val text: String = "",
    val senderMobile: String = "",
    val receiverMobile: String = "",
    val timestamp: Long = 0,
    val timeString: String = "",
    val fileUrl: String? = null,
    val fileName: String? = null,
    val fileType: String? = null,
    val fileSizeBytes: Long = 0,
    val thumbnailUrl: String? = null,
    val reaction: String? = null,
    val read: Boolean = false,
    val delivered: Boolean = false,
    val isCallLog: Boolean = false,
    val callStatus: String? = null,
    val callType: String? = null,
    val isPending: Boolean = false,
    val replyToId: String? = null,
    val replyToText: String? = null,
    val replyToSender: String? = null,
    val isDeleted: Boolean = false,
    val isForwarded: Boolean = false,
    val isStarred: Boolean = false,
    val duration: Int = 0,
    val waveform: List<Float> = emptyList()
)

data class Status(
    val id: String = "",
    val userMobile: String = "",
    val userName: String = "",
    val userAvatar: String = "",
    val mediaUrl: String = "",
    val mediaType: String = "image",
    val caption: String = "",
    val timestamp: Long = 0,
    val viewedBy: List<String> = emptyList(),
    val expiresAt: Long = 0
)

data class CallData(
    val id: String = "",
    val caller: String = "",
    val callee: String = "",
    val type: String = "audio",
    val status: String = "calling",
    val timestamp: Long = 0,
    val offer: Map<String, Any>? = null,
    val answer: Map<String, Any>? = null
)

data class Group(
    val id: String = "",
    val name: String = "",
    val avatarUrl: String = "",
    val description: String = "",
    val members: List<String> = emptyList(),
    val admins: List<String> = emptyList(),
    val createdBy: String = "",
    val createdAt: Long = 0
)

data class ChatPreview(
    val user: User? = null,
    val group: Group? = null,
    val lastMessage: Message? = null,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val isArchived: Boolean = false
)

// ==================== THEME ====================
object RasGramTheme {
    val DarkBackground = Color(0xFF0B141A)
    val DarkPanel = Color(0xFF1F2C34)
    val DarkSurface = Color(0xFF202C33)
    val Green = Color(0xFF00A884)
    val GreenDark = Color(0xFF008069)
    val GreenLight = Color(0xFF25D366)
    val TextPrimary = Color(0xFFE9EDEF)
    val TextSecondary = Color(0xFFAEBCC5)
    val TextMuted = Color(0xFF8696A0)
    val BubbleIn = Color(0xFF202C33)
    val BubbleOut = Color(0xFF005C4B)
    val Border = Color(0xFF2A3942)
    val BlueTick = Color(0xFF53BDEB)
    val Red = Color(0xFFEA0038)
    val Orange = Color(0xFFFF6B35)
    val Yellow = Color(0xFFFFD279)
    val LightBg = Color(0xFFEFEFEF)
    val InputBg = Color(0xFF2A3942)
    val DividerColor = Color(0xFF2C3E48)
    val OnlineGreen = Color(0xFF25D366)
    val CallGreen = Color(0xFF00BFA5)
    val CallRed = Color(0xFFF44336)
    val PinnedColor = Color(0xFF3B4A54)
    val Gradient1 = Color(0xFF00A884)
    val Gradient2 = Color(0xFF025144)
    val StarColor = Color(0xFFFFD700)
}

// ==================== COLORFUL AVATAR UTILITIES ====================

private val avatarPalette = listOf(
    Color(0xFF25D366), Color(0xFF128C7E), Color(0xFF00BCD4), Color(0xFF2196F3),
    Color(0xFF9C27B0), Color(0xFFE91E63), Color(0xFFFF5722), Color(0xFFFF9800),
    Color(0xFF4CAF50), Color(0xFF607D8B), Color(0xFF009688), Color(0xFF673AB7),
    Color(0xFF3F51B5), Color(0xFF795548), Color(0xFF00ACC1), Color(0xFFF44336)
)

private fun avatarColorFor(key: String): Color =
    avatarPalette[Math.abs(key.hashCode()) % avatarPalette.size]

private fun nameInitials(name: String, mobile: String): String {
    val trimmed = name.trim()
    if (trimmed.isNotEmpty()) {
        val parts = trimmed.split(" ").filter { it.isNotEmpty() }
        return if (parts.size >= 2) "${parts[0][0]}${parts[1][0]}" else "${parts[0][0]}"
    }
    return if (mobile.length >= 2) mobile.takeLast(2) else "?"
}

/** Local avatar — colored circle with initials; falls back to AsyncImage if avatarUrl present */
@Composable
fun UserAvatar(user: User, size: androidx.compose.ui.unit.Dp = 52.dp) {
    if (user.avatarUrl.isNotEmpty()) {
        AsyncImage(
            model = user.avatarUrl,
            contentDescription = "Avatar",
            modifier = Modifier.size(size).clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        val bg = remember(user.mobile) { avatarColorFor(user.mobile) }
        val initials = remember(user.name, user.mobile) { nameInitials(user.name, user.mobile).uppercase() }
        Box(
            modifier = Modifier.size(size).clip(CircleShape).background(bg),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initials,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.34f).sp
            )
        }
    }
}

// ==================== MAIN ACTIVITY ====================

// ==================== END TO END ENCRYPTION ====================
object AESCrypto {
    private const val SALT = "RasGram_E2EE_Secret_Salt_2026"

    private fun getKeyIv(chatId: String): Pair<SecretKeySpec, IvParameterSpec> {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest((chatId + SALT).toByteArray(Charsets.UTF_8))
        val key = ByteArray(16)
        val iv = ByteArray(16)
        System.arraycopy(hash, 0, key, 0, 16)
        System.arraycopy(hash, 16, iv, 0, 16)
        return Pair(SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    }

    fun encrypt(chatId: String, text: String): String {
        if (text.isEmpty()) return text
        return try {
            val (key, iv) = getKeyIv(chatId)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, key, iv)
            val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
            "E2EE:" + Base64.encodeToString(encrypted, Base64.DEFAULT)
        } catch (e: Exception) {
            text
        }
    }

    fun decrypt(chatId: String, encryptedText: String): String {
        if (encryptedText.isEmpty() || !encryptedText.startsWith("E2EE:")) return encryptedText
        return try {
            val actualEncrypted = encryptedText.replace("E2EE:", "").trim()
            val (key, iv) = getKeyIv(chatId)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            val decoded = Base64.decode(actualEncrypted, Base64.DEFAULT)
            String(cipher.doFinal(decoded), Charsets.UTF_8)
        } catch (e: Exception) {
            "🔓 (Decryption failed)"
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)

        val db = FirebaseFirestore.getInstance()
        // FIX #1: setSizeBytes doesn't exist on MemoryCacheSettings â€” removed it
        val settings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(
                PersistentCacheSettings.newBuilder().build()
            )
            .build()
        db.firestoreSettings = settings

        setContent { RasGramApp() }
    }
}

// ==================== ROOT APP ====================
@Composable
fun RasGramApp(
    incomingCallId: String? = null,
    incomingCallerMobile: String? = null,
    incomingCallerName: String? = null,
    incomingCallType: String? = null,
    openChatWithMobile: String? = null,
    sharedFileUri: android.net.Uri? = null,
    sharedFileName: String? = null
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) }

    val auth = remember { FirebaseAuth.getInstance() }

    // Read login state ONCE synchronously from SharedPreferences — no Compose state flip,
    // so OtpLoginScreen never renders (even for one frame) when the user is already logged in.
    // This is exactly how WhatsApp avoids a splash: it goes straight to the chat list.
    val savedMobile = prefs.getString(PREF_MOBILE, null)
    val savedUid = prefs.getString(PREF_UID, null)
    val initialUser: User? = if (savedMobile != null && savedUid != null) User(
        uid = savedUid,
        name = prefs.getString(PREF_NAME_KEY, "") ?: "",
        mobile = savedMobile,
        avatarUrl = prefs.getString(PREF_AVATAR, "") ?: ""
    ) else null

    var isLoggedIn by remember { mutableStateOf(initialUser != null) }
    var currentUser by remember { mutableStateOf(initialUser) }
    var isDarkMode by remember { mutableStateOf(true) }
    // Logged-in users: splash max 2s তারপর force-dismiss — Firebase slow হলেও আটকে থাকবে না।
    // Logged-out users: splash দেখাবে না — সরাসরি login screen।
    // Notification tap (openChatWithMobile != null): splash skip — সরাসরি chat।
    var showSplash by remember { mutableStateOf(initialUser != null && openChatWithMobile == null) }

    // Safety timeout: Firebase 1.5s এর মধ্যে না আসলে splash জোর করে সরাও
    LaunchedEffect(showSplash) {
        if (showSplash) {
            delay(1500L)
            showSplash = false
        }
    }

    // Already logged-in user: app open হলে presence service start করো
    // (login flow এর onLogin callback এ নতুন login cover হয়)
    LaunchedEffect(initialUser) {
        initialUser?.let { RasGramPresenceService.start(context, it.mobile) }
    }

    // ── LAN Mode auto-start: settings এ চালু থাকলে app open হলেই start ──────
    LaunchedEffect(initialUser) {
        val lanEnabled = prefs.getBoolean(PREF_LAN_MODE, false)
        if (lanEnabled && initialUser != null) {
            LanChatManager.getInstance(context).start(initialUser.mobile, initialUser.name)
            LanCallManager.getInstance(context).start()   // LAN audio/video call signal server
        }
    }

    MaterialTheme(colorScheme = if (isDarkMode) darkColorScheme(
        primary = RasGramTheme.Green,
        secondary = RasGramTheme.GreenDark,
        background = RasGramTheme.DarkBackground,
        surface = RasGramTheme.DarkPanel,
        onBackground = RasGramTheme.TextPrimary,
        onSurface = RasGramTheme.TextPrimary
    ) else lightColorScheme(
        primary = RasGramTheme.Green,
        secondary = RasGramTheme.GreenDark
    )) {
        // ── WhatsApp exact pattern ──────────────────────────────────────────────
        // Splash + MainScreen একসাথে Box এ রাখো।
        // MainScreen splash এর পেছনে compose হয়, Firebase load হতে থাকে।
        // Firebase snapshot আসলে onSplashDone() → splash fade out → chat list ready।
        // কোনো blank/empty screen দেখা যাবে না।
        // ────────────────────────────────────────────────────────────────────────
        Box(modifier = Modifier.fillMaxSize()) {
            if (!isLoggedIn || currentUser == null) {
                // Login screen — splash নেই
                OtpLoginScreen(
                    onLogin = { user ->
                        // Credential encrypted storage (normal path)
                        prefs.edit()
                            .putString(PREF_MOBILE, user.mobile)
                            .putString(PREF_NAME_KEY, user.name)
                            .putString(PREF_UID, user.uid)
                            .putString(PREF_AVATAR, user.avatarUrl)
                            .apply()
                        // Device encrypted storage mirror — Direct Boot path এর জন্য।
                        // Phone reboot এর পর lock screen unlock এর আগেও incoming call
                        // overlay service mobile number পড়তে পারবে।
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                            try {
                                context.createDeviceProtectedStorageContext()
                                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                                    .edit()
                                    .putString(PREF_MOBILE, user.mobile)
                                    .putString(PREF_NAME_KEY, user.name)
                                    .apply()
                            } catch (_: Exception) {}
                        }
                        currentUser = user
                        isLoggedIn = true
                        // Presence service start — data/WiFi চালু থাকলেই online দেখাবে
                        RasGramPresenceService.start(context, user.mobile)
                    }
                )
            } else {
                // MainScreen সবসময় compose হয় — splash এর পেছনেও
                // এতে Firebase load splash চলাকালীনই শুরু হয়
                MainScreen(
                    currentUser = currentUser!!,
                    isDarkMode = isDarkMode,
                    onToggleTheme = { isDarkMode = !isDarkMode },
                    onSplashDone = { showSplash = false },
                    onLogout = {
                        RasGramPresenceService.stop(context)
                        prefs.edit().clear().apply()
                        FirebaseAuth.getInstance().signOut()
                        isLoggedIn = false
                        currentUser = null
                    },
                    onUserUpdate = { updated ->
                        currentUser = updated
                        prefs.edit()
                            .putString(PREF_NAME_KEY, updated.name)
                            .putString(PREF_AVATAR, updated.avatarUrl)
                            .apply()
                    },
                    incomingCallId = incomingCallId,
                    incomingCallerMobile = incomingCallerMobile,
                    incomingCallerName = incomingCallerName,
                    incomingCallType = incomingCallType,
                    openChatWithMobile = openChatWithMobile,
                    sharedFileUri = sharedFileUri,
                    sharedFileName = sharedFileName
                )

                // Splash — MainScreen এর উপরে overlay হিসেবে
                // Firebase data আসলে fade out হয়, chat list ইতিমধ্যে ready
                AnimatedVisibility(
                    visible = showSplash,
                    enter = EnterTransition.None,
                    exit = fadeOut(animationSpec = tween(300))
                ) {
                    RasGramSplashScreen()
                }
            }
        }
    }
}

// ==================== SPLASH SCREEN ====================
@Composable
fun RasGramSplashScreen() {
    val infiniteTransition = rememberInfiniteTransition(label = "splash_pulse")

    // আইকনটা ধীরে breathe করবে
    val iconScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "icon_scale"
    )

    // নিচের tagline fade করবে
    val taglineAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "tagline_alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0B2318),
                        RasGramTheme.DarkBackground,
                        Color(0xFF071A14)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // পেছনে বড় glow
        Box(
            modifier = Modifier
                .size(260.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            RasGramTheme.Green.copy(alpha = 0.18f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo circle
            Box(
                modifier = Modifier
                    .scale(iconScale)
                    .size(110.dp)
                    .background(
                        brush = Brush.linearGradient(
                            listOf(RasGramTheme.Green, RasGramTheme.GreenDark)
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(52.dp)
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // RasGram নাম
            Row {
                Text(
                    "Ras",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 38.sp,
                    color = RasGramTheme.TextPrimary,
                    letterSpacing = 0.5.sp
                )
                Text(
                    "Gram",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 38.sp,
                    color = RasGramTheme.Green,
                    letterSpacing = 0.5.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                "Simple. Secure. Reliable.",
                color = RasGramTheme.Green.copy(alpha = taglineAlpha),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp
            )
        }

        // নিচে "from" badge
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = RasGramTheme.TextMuted,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    "End-to-end encrypted",
                    color = RasGramTheme.TextMuted,
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "from RasEduTools",
                color = RasGramTheme.TextMuted.copy(alpha = 0.5f),
                fontSize = 11.sp
            )
        }
    }
}

// ==================== OTP LOGIN SCREEN ====================
@Composable
fun OtpLoginScreen(onLogin: (User) -> Unit) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(0) } // 0=phone, 1=name, 2=otp
    var phoneNumber by remember { mutableStateOf("") }
    var countryCode by remember { mutableStateOf("+880") }
    var userName by remember { mutableStateOf("") }
    var otpCode by remember { mutableStateOf("") }
    var verificationId by remember { mutableStateOf("") }
    var resendToken by remember { mutableStateOf<PhoneAuthProvider.ForceResendingToken?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }
    var countdown by remember { mutableIntStateOf(60) }
    var canResend by remember { mutableStateOf(false) }

    LaunchedEffect(step) {
        if (step == 2) {
            countdown = 60
            canResend = false
            while (countdown > 0) {
                delay(1000)
                countdown--
            }
            canResend = true
        }
    }

    fun sendOtp(forceResend: Boolean = false) {
        val fullPhone = "$countryCode$phoneNumber"
        if (phoneNumber.length < 9) {
            errorMsg = "Enter a valid phone number"
            return
        }
        isLoading = true
        errorMsg = ""

        // BYPASS FIREBASE AUTH (For testing)
        scope.launch {
            try {
                val uid = "user_${fullPhone.replace("+", "").replace(" ", "")}"
                val mobile = fullPhone.replace("+", "").replace(" ", "")
                val docRef = db.collection("chat_users").document(mobile)
                val snap = docRef.get().await()
                // BUG FIX: একই নম্বর দিয়ে ভিন্ন নামে re-login block করা হচ্ছে।
                // নম্বর একবার register হলে সেই নম্বরের নাম/profile locked।
                // নতুন নাম শুধু Settings > Profile থেকে পরিবর্তন করা যাবে।
                if (!snap.exists()) {
                    docRef.set(hashMapOf(
                        "uid" to uid, "name" to userName, "mobile" to mobile,
                        "avatarUrl" to "", "lastActive" to System.currentTimeMillis(),
                        "typingTo" to null, "statusVisible" to true,
                        "about" to "Hey there! I am using RasGram."
                    )).await()
                } else {
                    // Existing user — name পরিবর্তন করা যাবে না login flow থেকে।
                    // শুধু lastActive এবং uid refresh করা হচ্ছে।
                    docRef.update("lastActive", System.currentTimeMillis(), "uid", uid).await()
                }
                // BUG FIX: snap ছিল login এর আগের state।
                // নতুন user হলে snap.getString("name") → null, তাই re-fetch করো।
                val freshSnap = docRef.get().await()
                val savedName = freshSnap.getString("name") ?: userName
                
                // Save FCM token after login
                try {
                    com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnSuccessListener { fcmToken ->
                        db.collection("chat_users").document(mobile).update("fcmToken", fcmToken)
                    }
                } catch (_: Exception) { }
                
                onLogin(User(uid = uid, name = savedName, mobile = mobile, avatarUrl = freshSnap.getString("avatarUrl") ?: ""))
            } catch (e: Exception) {
                errorMsg = "Login failed: ${e.message}"
                isLoading = false
            }
        }
    }

    fun verifyOtp() {
        if (otpCode.length != 6) {
            errorMsg = "Enter 6-digit OTP"
            return
        }
        isLoading = true
        errorMsg = ""
        val fullPhone = "$countryCode$phoneNumber"
        scope.launch {
            try {
                val credential = PhoneAuthProvider.getCredential(verificationId, otpCode)
                val result = auth.signInWithCredential(credential).await()
                val uid = result.user?.uid ?: throw Exception("No UID")
                val mobile = fullPhone.replace("+", "").replace(" ", "")
                val docRef = db.collection("chat_users").document(mobile)
                val snap = docRef.get().await()
                if (!snap.exists()) {
                    docRef.set(hashMapOf(
                        "uid" to uid, "name" to userName, "mobile" to mobile,
                        "avatarUrl" to "", "lastActive" to System.currentTimeMillis(),
                        "typingTo" to null, "statusVisible" to true,
                        "about" to "Hey there! I am using RasGram."
                    )).await()
                } else {
                    docRef.update("lastActive", System.currentTimeMillis(), "uid", uid)
                }
                val savedName = snap.getString("name") ?: userName
                // Save FCM token after OTP login
                try {
                    com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnSuccessListener { fcmToken ->
                        db.collection("chat_users").document(mobile).update("fcmToken", fcmToken)
                    }
                } catch (_: Exception) { }
                onLogin(User(uid = uid, name = savedName, mobile = mobile, avatarUrl = snap.getString("avatarUrl") ?: ""))
            } catch (e: Exception) {
                errorMsg = "Invalid OTP. Please try again."
                isLoading = false
            }
        }
    }

    // WhatsApp-style gradient background
    Box(modifier = Modifier.fillMaxSize()) {
        // Background gradient
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            RasGramTheme.GreenDark.copy(alpha = 0.3f),
                            RasGramTheme.DarkBackground
                        )
                    )
                )
        )
        
        // Decorative circles
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-100).dp)
                .size(300.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(RasGramTheme.Green.copy(alpha = 0.15f), Color.Transparent)
                    ),
                    shape = CircleShape
                )
        )
        
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = (-50).dp, y = (-100).dp)
                .size(200.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(RasGramTheme.GreenLight.copy(alpha = 0.1f), Color.Transparent)
                    ),
                    shape = CircleShape
                )
        )

        // Main content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(80.dp))

            // Logo + Header
            Surface(
                modifier = Modifier.size(100.dp),
                shape = CircleShape,
                color = RasGramTheme.Green,
                shadowElevation = 16.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "Logo",
                        tint = Color.White,
                        modifier = Modifier.size(56.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                "RasGram",
                style = MaterialTheme.typography.headlineLarge,
                color = RasGramTheme.TextPrimary,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 36.sp,
                letterSpacing = 0.5.sp
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                "Simple. Secure. Reliable.",
                style = MaterialTheme.typography.bodyMedium,
                color = RasGramTheme.Green,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                when (step) {
                    0 -> "Enter your phone number to continue"
                    1 -> "What should we call you?"
                    else -> "Enter the verification code"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = RasGramTheme.TextMuted,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Card with glassmorphism effect
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = RasGramTheme.DarkPanel.copy(alpha = 0.8f),
                shadowElevation = 12.dp,
                border = BorderStroke(1.dp, RasGramTheme.Border.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(28.dp)) {
                    AnimatedContent(targetState = step, label = "step") { currentStep ->
                        when (currentStep) {
                            0 -> PhoneInputStep(
                                phoneNumber = phoneNumber,
                                countryCode = countryCode,
                                onPhoneChange = { phoneNumber = it },
                                onCountryCodeChange = { countryCode = it },
                                isLoading = isLoading,
                                errorMsg = errorMsg,
                                onNext = {
                                    if (phoneNumber.isNotBlank()) step = 1 else errorMsg = "Enter phone number"
                                }
                            )
                            1 -> NameInputStep(
                                userName = userName,
                                onNameChange = { userName = it },
                                isLoading = isLoading,
                                errorMsg = errorMsg,
                                onNext = {
                                    if (userName.isNotBlank()) sendOtp() else errorMsg = "Enter your name"
                                },
                                onBack = { step = 0 }
                            )
                            2 -> OtpInputStep(
                                otpCode = otpCode,
                                phoneNumber = "$countryCode$phoneNumber",
                                onOtpChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) otpCode = it },
                                isLoading = isLoading,
                                errorMsg = errorMsg,
                                countdown = countdown,
                                canResend = canResend,
                                onVerify = { verifyOtp() },
                                onResend = { sendOtp(forceResend = true) },
                                onBack = { step = 1; otpCode = "" }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(36.dp))
            
            // Security badge
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = RasGramTheme.Green.copy(alpha = 0.1f),
                border = BorderStroke(1.dp, RasGramTheme.Green.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Lock,
                        null,
                        modifier = Modifier.size(16.dp),
                        tint = RasGramTheme.Green
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "End-to-end encrypted",
                        style = MaterialTheme.typography.bodySmall,
                        color = RasGramTheme.Green,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun PhoneInputStep(
    phoneNumber: String,
    countryCode: String,
    onPhoneChange: (String) -> Unit,
    onCountryCodeChange: (String) -> Unit,
    isLoading: Boolean,
    errorMsg: String,
    onNext: () -> Unit
) {
    val countryCodes = listOf("+880" to "🇧🇩 BD", "+1" to "🇺🇸 US", "+44" to "🇬🇧 UK", "+91" to "🇮🇳 IN", "+971" to "🇦🇪 AE", "+966" to "🇸🇦 SA")
    var showDropdown by remember { mutableStateOf(false) }
    var selectedCountry by remember { mutableStateOf(countryCodes[0]) }

    Column {
        Text("Phone Number", color = RasGramTheme.TextMuted, style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton(
                    onClick = { showDropdown = true },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = RasGramTheme.TextPrimary),
                    border = BorderStroke(1.dp, RasGramTheme.Border),
                    modifier = Modifier.height(56.dp)
                ) {
                    Text(selectedCountry.second.take(2), fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(selectedCountry.first, color = RasGramTheme.TextPrimary, fontSize = 14.sp)
                    Icon(Icons.Default.ArrowDropDown, null, tint = RasGramTheme.TextMuted)
                }
                DropdownMenu(
                    expanded = showDropdown,
                    onDismissRequest = { showDropdown = false },
                    modifier = Modifier.background(RasGramTheme.DarkPanel)
                ) {
                    countryCodes.forEach { (code, label) ->
                        DropdownMenuItem(
                            text = { Text("$label  $code", color = RasGramTheme.TextPrimary) },
                            onClick = {
                                selectedCountry = code to label
                                onCountryCodeChange(code)
                                showDropdown = false
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
                value = phoneNumber,
                onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 11) onPhoneChange(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Phone number", color = RasGramTheme.TextMuted) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { onNext() }),
                singleLine = true,
                colors = outlinedFieldColors(),
                shape = RoundedCornerShape(12.dp)
            )
        }

        if (errorMsg.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(errorMsg, color = RasGramTheme.Red, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RasGramTheme.Green),
            enabled = !isLoading && phoneNumber.isNotBlank()
        ) {
            if (isLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.Black)
            else Text("Continue", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

@Composable
fun NameInputStep(
    userName: String,
    onNameChange: (String) -> Unit,
    isLoading: Boolean,
    errorMsg: String,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    Column {
        Text("Your Name", color = RasGramTheme.TextMuted, style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = userName,
            onValueChange = { if (it.length <= 25) onNameChange(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Enter your name", color = RasGramTheme.TextMuted) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onNext() }),
            singleLine = true,
            colors = outlinedFieldColors(),
            shape = RoundedCornerShape(12.dp),
            trailingIcon = {
                Text("${userName.length}/25", color = RasGramTheme.TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 8.dp))
            }
        )
        if (errorMsg.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            // FIX: was RasGramTheme.bodySmall (invalid), now MaterialTheme.typography.bodySmall
            Text(errorMsg, color = RasGramTheme.Red, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, RasGramTheme.Border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = RasGramTheme.TextMuted)
            ) { Text("Back") }
            Button(
                onClick = onNext,
                modifier = Modifier.weight(2f).height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RasGramTheme.Green),
                enabled = !isLoading && userName.isNotBlank()
            ) {
                if (isLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.Black)
                else Text("Send OTP", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun OtpInputStep(
    otpCode: String,
    phoneNumber: String,
    onOtpChange: (String) -> Unit,
    isLoading: Boolean,
    errorMsg: String,
    countdown: Int,
    canResend: Boolean,
    onVerify: () -> Unit,
    onResend: () -> Unit,
    onBack: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "OTP sent to $phoneNumber",
            style = MaterialTheme.typography.bodySmall,
            color = RasGramTheme.TextMuted,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))

        // OTP boxes
        OtpBoxes(otpCode = otpCode, onOtpChange = onOtpChange)

        if (errorMsg.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(errorMsg, color = RasGramTheme.Red, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (canResend) {
            TextButton(onClick = onResend) {
                Text("Resend OTP", color = RasGramTheme.Green, fontWeight = FontWeight.Bold)
            }
        } else {
            Text("Resend in ${countdown}s", color = RasGramTheme.TextMuted, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, RasGramTheme.Border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = RasGramTheme.TextMuted)
            ) { Text("Back") }
            Button(
                onClick = onVerify,
                modifier = Modifier.weight(2f).height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RasGramTheme.Green),
                enabled = !isLoading && otpCode.length == 6
            ) {
                if (isLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.Black)
                else Text("Verify", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
fun OtpBoxes(otpCode: String, onOtpChange: (String) -> Unit) {
    var focusedIndex by remember { mutableIntStateOf(0) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        repeat(6) { index ->
            val char = otpCode.getOrNull(index)?.toString() ?: ""
            val isFocused = focusedIndex == index || (index == otpCode.length && index < 6)
            Surface(
                modifier = Modifier.size(46.dp),
                shape = RoundedCornerShape(10.dp),
                color = if (char.isNotEmpty()) RasGramTheme.Green.copy(alpha = 0.15f) else RasGramTheme.InputBg,
                border = BorderStroke(if (isFocused) 2.dp else 1.dp, if (isFocused) RasGramTheme.Green else RasGramTheme.Border)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = char,
                        color = RasGramTheme.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    // Hidden field for input
    OutlinedTextField(
        value = otpCode,
        onValueChange = onOtpChange,
        modifier = Modifier.size(1.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent
        )
    )
}

// ==================== MAIN SCREEN (TABS) ====================
@Composable
fun MainScreen(
    currentUser: User,
    isDarkMode: Boolean,
    onToggleTheme: () -> Unit,
    onLogout: () -> Unit,
    onUserUpdate: (User) -> Unit,
    onSplashDone: () -> Unit = {},
    incomingCallId: String? = null,
    incomingCallerMobile: String? = null,
    incomingCallerName: String? = null,
    incomingCallType: String? = null,
    openChatWithMobile: String? = null,
    sharedFileUri: android.net.Uri? = null,
    sharedFileName: String? = null
) {
    val context = LocalContext.current
    val db = remember { FirebaseFirestore.getInstance() }

    // Incoming call state — lock screen বা app-open দুটো path থেকেই আসতে পারে
    // Must be declared before any LaunchedEffect that references these variables
    var showIncomingCall by remember { mutableStateOf(incomingCallId != null) }
    // showCallUI must be declared before any LaunchedEffect that references it
    var showCallUI by remember { mutableStateOf(false) }
    // Guard: processed callId Set — Firestore snapshot reconnect এ same callId আবার ADDED আসলে ignore
    // FCM + Firestore দুটো path merge করলেও duplicate IncomingCallScreen দেখাবে না
    val processedCallIds = remember { mutableSetOf<String>().also { set ->
        if (incomingCallId != null) set.add(incomingCallId)
    }}
    // Overlay permission — ask once on first launch inside RasGram
    var showOverlayPermissionDialog by remember { mutableStateOf(false) }
    var activeIncomingCallId by remember { mutableStateOf(incomingCallId ?: "") }
    var activeIncomingCallerMobile by remember { mutableStateOf(incomingCallerMobile ?: "") }
    var activeIncomingCallerName by remember { mutableStateOf(incomingCallerName ?: "") }
    var activeIncomingCallType by remember { mutableStateOf(incomingCallType ?: "audio") }

    // Request permissions dynamically
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())

        // Overlay permission check — needed for WhatsApp-style call overlay
        if (!OverlayPermissionHelper.hasPermission(context)) {
            showOverlayPermissionDialog = true
        }

        // FIX: Android 14+ (API 34) — USE_FULL_SCREEN_INTENT permission।
        // এই permission ছাড়া lock screen এ full-screen call UI দেখা যায় না।
        // শুধু overlay permission grant করলেই হয় না — এটাও আলাদা করে grant করতে হয়।
        // canUseFullScreenIntent() false হলে Settings এ পাঠাও।
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            if (!nm.canUseFullScreenIntent()) {
                try {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        android.net.Uri.parse("package:${context.packageName}")
                    ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
        }

        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (token != null) {
                    db.collection("chat_users").document(currentUser.mobile).update("fcmToken", token)
                }
            }
        } catch (_: Exception) { }
    }

    // Listen for incoming calls when app is open — IncomingCallScreen দেখাও directly
    // Guard: same callId এর জন্য দুইবার trigger হলে ignore।
    // FCM + Firestore দুইটাই fire করলে duplicate overlay না আসে।
    LaunchedEffect(currentUser.mobile) {
        db.collection("calls")
            .whereEqualTo("callee", currentUser.mobile)
            .whereEqualTo("status", "calling")
            .addSnapshotListener { snapshot, _ ->
                snapshot?.documentChanges?.forEach { change ->
                    if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        val newCallId = change.document.id
                        // processedCallIds guard: Firestore network reconnect বা FCM+Firestore
                        // double-fire এ same callId দুইবার IncomingCallScreen দেখাবে না।
                        if (processedCallIds.contains(newCallId)) return@forEach
                        // CallingScreen চলছে মানে আগের call already accepted।
                        if (showCallUI) return@forEach
                        processedCallIds.add(newCallId)
                        val data = change.document.data
                        activeIncomingCallId       = newCallId
                        activeIncomingCallerMobile = data["caller"] as? String ?: ""
                        activeIncomingCallerName   = data["callerName"] as? String ?: ""
                        activeIncomingCallType     = data["type"] as? String ?: "audio"
                        showIncomingCall = true
                        // Overlay service চলছে থাকলে বন্ধ করো —
                        // in-app IncomingCallScreen নিজেই ring বাজাবে।
                        IncomingCallOverlayService.stop(context)
                    }
                }
            }
    }
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedContact by remember { mutableStateOf<User?>(null) }
    var selectedGroup by remember { mutableStateOf<Group?>(null) }

    // Notification tap → directly open that chat
    // openChatWithMobile = sender এর mobile number (FCM data এ ছিল)
    // Firestore থেকে User fetch করে selectedContact set করো
    LaunchedEffect(openChatWithMobile) {
        val mobile = openChatWithMobile ?: return@LaunchedEffect
        try {
            val snap = FirebaseFirestore.getInstance()
                .collection("chat_users")
                .document(mobile)
                .get()
                .await()
            if (snap.exists()) {
                val user = User(
                    uid       = snap.getString("uid")       ?: "",
                    name      = snap.getString("name")      ?: mobile,
                    mobile    = mobile,
                    avatarUrl = snap.getString("avatarUrl") ?: "",
                    about     = snap.getString("about")     ?: "",
                    fcmToken  = snap.getString("fcmToken")  ?: ""
                )
                selectedContact = user
                selectedGroup   = null
                selectedTab     = 0   // Chats tab এ যাও
            }
        } catch (_: Exception) { }
    }
    var isReceiverCall by remember { mutableStateOf(false) }
    var acceptedCallId by remember { mutableStateOf("") }
    var selectedStatusUser by remember { mutableStateOf<List<Status>?>(null) }
    var callType by remember { mutableStateOf("audio") }
    var callContact by remember { mutableStateOf<User?>(null) }
    var liveCurrentUser by remember { mutableStateOf(currentUser) }
    val isCompact = isCompactScreen()

    // ── LAN Call: incoming call observer ──────────────────────────────────────
    val lanCallManager = remember { LanCallManager.getInstance(context) }
    val incomingLanCall by lanCallManager.incomingCall.collectAsState()
    var showIncomingLanCall by remember { mutableStateOf(false) }
    var showLanCalleeUI by remember { mutableStateOf(false) }
    var activeLanIncomingCall by remember { mutableStateOf<LanCallManager.LanIncomingCall?>(null) }

    LaunchedEffect(incomingLanCall) {
        incomingLanCall?.let {
            activeLanIncomingCall = it
            showIncomingLanCall = true
        }
    }

    // ── Presence system ────────────────────────────────────────────────────────
    // Firebase RTDB onDisconnect: network drop হওয়ার সাথে সাথে offline mark হয়।
    // Firestore polling (30s delay) এর চেয়ে accurate — phone বন্ধ/network গেলেই offline।
    //
    // Flow:
    //   App খোলে → RTDB presence node: {online:true, lastActive:now}
    //             → Firestore: {lastActive:now} (contact list দেখানোর জন্য)
    //   App বন্ধ/network গেলে → RTDB onDisconnect fires:
    //             → RTDB presence node: {online:false, lastActive:now}
    //             → Firestore: {lastActive:serverTimestamp} (via RTDB listener)
    //
    // Contact list এর isOnline check: Firestore lastActive > now - ONLINE_THRESHOLD_MS
    // এটাই ঠিক থাকবে — RTDB disconnect হলে Firestore lastActive update হয়।
    LaunchedEffect(currentUser.mobile) {
        // ── Profile sync (unchanged) ──────────────────────────────────────────
        db.collection("chat_users").document(currentUser.mobile).addSnapshotListener { snap, _ ->
            snap?.data?.let { d ->
                liveCurrentUser = liveCurrentUser.copy(
                    name = d["name"] as? String ?: liveCurrentUser.name,
                    avatarUrl = d["avatarUrl"] as? String ?: liveCurrentUser.avatarUrl,
                    about = d["about"] as? String ?: liveCurrentUser.about
                )
                onUserUpdate(liveCurrentUser)
            }
        }

        // ── Presence: RasGramPresenceService handles RTDB online/offline ──────────
        // RasGramPresenceService (Foreground Service) এখন presence manage করে।
        // Service background এও চলে — data/WiFi চালু থাকলেই online থাকে।
        // এখানে আর inline RTDB code দরকার নেই — duplicate হবে।
    }

    val inChat = selectedContact != null || selectedGroup != null

    // ── Handle file shared from FileManager ─────────────────────────────────
    // When a file is shared via "Send to RasGram", show a contact picker sheet.
    // After contact is selected, open the chat with the file ready to upload.
    var pendingSharedUri by remember { mutableStateOf(sharedFileUri) }
    var pendingSharedName by remember { mutableStateOf(sharedFileName) }
    var showShareContactPicker by remember { mutableStateOf(sharedFileUri != null) }

    // Back button handling:
    // - Chat open → close chat, go back to chat list
    // - On non-home tab → go back to tab 0 (Chats)
    // - On home tab (Chats) with nothing open → let system handle (exit app)
    BackHandler(enabled = inChat || selectedTab != 0) {
        when {
            selectedContact != null -> selectedContact = null
            selectedGroup != null -> selectedGroup = null
            selectedTab != 0 -> selectedTab = 0
        }
    }

    // ── WhatsApp-style: chat list + chat window SAME Box, always composed ──
    // Tablet: side-by-side। Mobile: chat list সবসময় render, chat window
    // তার উপরে AnimatedVisibility দিয়ে slide করে আসে।
    // এতে chat list কখনো re-compose হয় না → কোনো lag নেই।
    if (!isCompact) {
        // ── Tablet layout: side-by-side ─────────────────────────────────────
        Scaffold(containerColor = RasGramTheme.DarkBackground) { padding ->
            Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                Column(modifier = Modifier.width(360.dp).fillMaxHeight()) {
                    TabLayout(selectedTab = selectedTab, onTabChange = { selectedTab = it })
                    SidebarContent(
                        tab = selectedTab,
                        currentUser = liveCurrentUser,
                        selectedContact = selectedContact,
                        onContactSelect = { selectedContact = it; selectedGroup = null },
                        onGroupSelect = { selectedGroup = it; selectedContact = null },
                        isDarkMode = isDarkMode,
                        onToggleTheme = onToggleTheme,
                        onLogout = onLogout,
                        onUserUpdate = onUserUpdate,
                        onStatusClick = { selectedStatusUser = it },
                        onSplashDone = onSplashDone
                    )
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    when {
                        selectedContact != null -> ChatArea(
                            currentUser = liveCurrentUser,
                            contact = selectedContact!!,
                            onBack = { selectedContact = null },
                            onCallClick = { type ->
                                callType = type
                                callContact = selectedContact
                                showCallUI = true
                            }
                        )
                        selectedGroup != null -> GroupChatArea(
                            currentUser = liveCurrentUser,
                            group = selectedGroup!!,
                            onBack = { selectedGroup = null }
                        )
                        else -> EmptyChatState()
                    }
                }
            }
        }
    } else {
        // ── Mobile layout: WhatsApp exact binding ────────────────────────────
        // Chat list + bottom nav সবসময় composed। Chat window তার উপরে
        // Box এ overlay — slide করে আসে, chat list কখনো unmount হয় না।
        Box(modifier = Modifier.fillMaxSize()) {

            // Layer 1: Chat list (সবসময় composed, কখনো destroy হয় না)
            Scaffold(
                containerColor = RasGramTheme.DarkBackground,
                bottomBar = {
                    BottomNavBar(
                        selectedTab = selectedTab,
                        onTabChange = { selectedTab = it }
                    )
                }
            ) { padding ->
                SidebarContent(
                    tab = selectedTab,
                    currentUser = liveCurrentUser,
                    selectedContact = selectedContact,
                    onContactSelect = { selectedContact = it; selectedGroup = null },
                    onGroupSelect = { selectedGroup = it; selectedContact = null },
                    isDarkMode = isDarkMode,
                    onToggleTheme = onToggleTheme,
                    onLogout = onLogout,
                    onUserUpdate = onUserUpdate,
                    onStatusClick = { selectedStatusUser = it },
                    onSplashDone = onSplashDone,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                )
            }

            // Layer 2: Chat window — slide from right, exactly like WhatsApp
            // AnimatedVisibility keeps ChatArea composed during exit animation
            // so back-swipe feels instant, no blank frame.
            AnimatedVisibility(
                visible = inChat,
                enter = slideInHorizontally(
                    initialOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
                ),
                exit = slideOutHorizontally(
                    targetOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
                )
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        selectedContact != null -> ChatArea(
                            currentUser = liveCurrentUser,
                            contact = selectedContact!!,
                            onBack = { selectedContact = null },
                            onCallClick = { type ->
                                callType = type
                                callContact = selectedContact
                                showCallUI = true
                            }
                        )
                        selectedGroup != null -> GroupChatArea(
                            currentUser = liveCurrentUser,
                            group = selectedGroup!!,
                            onBack = { selectedGroup = null }
                        )
                    }
                }
            }
        }
    }

    // Outgoing / accepted call overlay
    if (showCallUI && callContact != null) {
        CallingScreen(
            currentUser = liveCurrentUser,
            contact = callContact!!,
            callType = callType,
            onEndCall = {
                showCallUI = false
                isReceiverCall = false
                acceptedCallId = ""
            },
            isReceiver = isReceiverCall,
            existingCallId = acceptedCallId
        )
    }

    // Incoming call overlay — WhatsApp-style full screen accept/decline
    if (showIncomingCall && activeIncomingCallId.isNotEmpty()) {
        IncomingCallScreen(
            currentUser = liveCurrentUser,
            callerName = activeIncomingCallerName,
            callerMobile = activeIncomingCallerMobile,
            callType = activeIncomingCallType,
            callId = activeIncomingCallId,
            onAccept = {
                // Overlay service এখনো চলছে? বন্ধ করো — ring থামাও
                IncomingCallOverlayService.stop(context)
                // IncomingCallScreen সরাও, CallingScreen receiver mode এ চালু করো
                showIncomingCall = false
                callType = activeIncomingCallType
                callContact = User(
                    uid = "",
                    name = activeIncomingCallerName,
                    mobile = activeIncomingCallerMobile,
                    avatarUrl = ""
                )
                isReceiverCall = true
                acceptedCallId = activeIncomingCallId
                showCallUI = true
            },
            onDecline = {
                IncomingCallOverlayService.stop(context)
                showIncomingCall = false
                activeIncomingCallId = ""
            }
        )
    }

    // ── LAN Incoming Call overlay ─────────────────────────────────────────────
    // Internet ছাড়া — same WiFi/Hotspot এ কেউ call করলে এই screen দেখাবে
    if (showIncomingLanCall && activeLanIncomingCall != null) {
        IncomingLanCallScreen(
            call = activeLanIncomingCall!!,
            onAccept = {
                showIncomingLanCall = false
                showLanCalleeUI = true
            },
            onDecline = {
                showIncomingLanCall = false
                activeLanIncomingCall = null
            }
        )
    }

    // ── LAN Callee Calling screen (after accepting) ───────────────────────────
    if (showLanCalleeUI && activeLanIncomingCall != null) {
        val lanCall = activeLanIncomingCall!!
        CallingLanScreen(
            currentUser = liveCurrentUser,
            peerName = lanCall.callerName,
            peerMobile = lanCall.callerMobile,
            callType = lanCall.callType,
            call = lanCall,
            onEndCall = {
                showLanCalleeUI = false
                activeLanIncomingCall = null
            }
        )
    }

    // ── File Share Contact Picker ─────────────────────────────────────────────
    // When user taps "Send to RasGram" in FileManager, show a contact list
    // so user picks who to send to. Then open ChatArea with the file auto-queued.
    if (showShareContactPicker && pendingSharedUri != null) {
        ShareFileContactPickerDialog(
            currentUser = liveCurrentUser,
            fileUri = pendingSharedUri!!,
            fileName = pendingSharedName ?: "file",
            onContactSelected = { contact ->
                showShareContactPicker = false
                selectedContact = contact
                selectedGroup = null
                selectedTab = 0
                // The ChatArea will handle upload via its own pending URI state
            },
            onDismiss = {
                showShareContactPicker = false
                pendingSharedUri = null
                pendingSharedName = null
            }
        )
    }

    // Overlay permission dialog — shown once if SYSTEM_ALERT_WINDOW not granted
    if (showOverlayPermissionDialog) {
        OverlayPermissionDialog(
            onAllow = {
                showOverlayPermissionDialog = false
                OverlayPermissionHelper.openSettings(context)
            },
            onDismiss = { showOverlayPermissionDialog = false }
        )
    }

    // Status Viewer overlay
    if (selectedStatusUser != null && selectedStatusUser!!.isNotEmpty()) {
        StatusViewerScreen(
            currentUserMobile = liveCurrentUser.mobile,
            statuses = selectedStatusUser!!,
            initialIndex = 0,
            onClose = { selectedStatusUser = null }
        )
    }
}

@Composable
fun BottomNavBar(selectedTab: Int, onTabChange: (Int) -> Unit) {
    NavigationBar(containerColor = RasGramTheme.DarkPanel, tonalElevation = 0.dp) {
        val tabs = listOf(
            Triple(Icons.Default.Message, Icons.Default.Message, "Chats"),
            Triple(Icons.Default.RadioButtonChecked, Icons.Default.RadioButtonUnchecked, "Status"),
            Triple(Icons.Default.Call, Icons.Default.Call, "Calls"),
            Triple(Icons.Default.People, Icons.Default.People, "Groups")
        )
        tabs.forEachIndexed { i, (filledIcon, outlinedIcon, label) ->
            NavigationBarItem(
                icon = { Icon(if (selectedTab == i) filledIcon else outlinedIcon, label) },
                label = { Text(label, fontSize = 11.sp) },
                selected = selectedTab == i,
                onClick = { onTabChange(i) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = RasGramTheme.Green,
                    selectedTextColor = RasGramTheme.Green,
                    unselectedIconColor = RasGramTheme.TextMuted,
                    unselectedTextColor = RasGramTheme.TextMuted,
                    indicatorColor = RasGramTheme.Green.copy(alpha = 0.1f)
                )
            )
        }
    }
}

@Composable
fun TabLayout(selectedTab: Int, onTabChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(RasGramTheme.DarkPanel).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tabs = listOf("Chats", "Status", "Calls")
        tabs.forEachIndexed { i, label ->
            Column(
                modifier = Modifier.weight(1f).clickable { onTabChange(i) }.padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    label,
                    color = if (selectedTab == i) RasGramTheme.Green else RasGramTheme.TextMuted,
                    fontWeight = if (selectedTab == i) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 13.sp
                )
                if (selectedTab == i) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Box(modifier = Modifier.width(40.dp).height(2.dp).background(RasGramTheme.Green, RoundedCornerShape(1.dp)))
                }
            }
        }
    }
}

@Composable
fun SidebarContent(
    tab: Int,
    currentUser: User,
    selectedContact: User?,
    onContactSelect: (User) -> Unit,
    onGroupSelect: (Group) -> Unit,
    isDarkMode: Boolean,
    onToggleTheme: () -> Unit,
    onLogout: () -> Unit,
    onUserUpdate: (User) -> Unit,
    onStatusClick: (List<Status>) -> Unit,
    onSplashDone: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    when (tab) {
        0 -> ChatsTab(
            currentUser = currentUser,
            selectedContact = selectedContact,
            onContactSelect = onContactSelect,
            isDarkMode = isDarkMode,
            onToggleTheme = onToggleTheme,
            onLogout = onLogout,
            onUserUpdate = onUserUpdate,
            onSplashDone = onSplashDone,
            modifier = modifier
        )
        1 -> StatusTab(currentUser = currentUser, onStatusClick = onStatusClick, modifier = modifier)
        2 -> CallsTab(currentUser = currentUser, modifier = modifier)
        3 -> GroupsTab(currentUser = currentUser, onGroupSelect = onGroupSelect, modifier = modifier)
    }
}

// WhatsApp-style shimmer skeleton — chat list load হওয়ার আগে দেখায়
@Composable
private fun ChatSkeletonItem() {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "shimmerAlpha"
    )
    val shimmerColor = Color.White.copy(alpha = shimmerAlpha)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(52.dp).background(shimmerColor, CircleShape))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(0.45f).height(14.dp).background(shimmerColor, RoundedCornerShape(4.dp)))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth(0.7f).height(12.dp).background(shimmerColor, RoundedCornerShape(4.dp)))
        }
        Box(Modifier.width(36.dp).height(11.dp).background(shimmerColor, RoundedCornerShape(4.dp)))
    }
}

// WhatsApp-style message bubble skeleton — chat খোলার সাথে সাথে দেখায়, flicker নেই
@Composable
private fun MessageSkeletonItem(isMe: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "msgShimmer")
    val shimmerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.10f, targetValue = 0.28f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "msgShimmerAlpha"
    )
    val shimmerColor = Color.White.copy(alpha = shimmerAlpha)
    val width = if (isMe) 0.55f else 0.65f
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(width)
                .height(42.dp)
                .background(shimmerColor, RoundedCornerShape(12.dp))
        )
    }
}

// ==================== CHATS TAB ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsTab(
    currentUser: User,
    selectedContact: User?,
    onContactSelect: (User) -> Unit,
    isDarkMode: Boolean,
    onToggleTheme: () -> Unit,
    onLogout: () -> Unit,
    onUserUpdate: (User) -> Unit,
    onSplashDone: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val db = remember { FirebaseFirestore.getInstance() }
    var users by remember { mutableStateOf<List<User>>(emptyList()) }
    // Firebase এর প্রথম snapshot আসার আগে empty state দেখাবে না — WhatsApp এর মতো
    var usersLoaded by remember { mutableStateOf(false) }
    var latestMessages by remember { mutableStateOf<Map<String, Message>>(emptyMap()) }
    var unreadCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var searchQuery by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showNewGroup by remember { mutableStateOf(false) }
    var showAddContact by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── LAN discovered users (for badge in contact list) ──────────────────────
    val lanManager = remember { LanChatManager.getInstance(context) }
    val lanDiscoveredUsers by lanManager.discoveredUsers.collectAsState()

    // ── WhatsApp-style: Room DB থেকে cached chat previews instant load ─────────
    val rasGramRepo = remember { RasGramRepository.getInstance(context) }
    val cachedPreviews by rasGramRepo.chatPreviewDao
        .getChatPreviews()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // Room cache থেকে latestMessages ও unreadCounts pre-populate
    // এতে app open এ সাথে সাথে last message দেখা যায় — Firestore wait করতে হয় না
    LaunchedEffect(cachedPreviews) {
        val cachedLatest = mutableMapOf<String, Message>()
        val cachedUnread = mutableMapOf<String, Int>()
        cachedPreviews.forEach { preview ->
            if (preview.lastTimestamp > 0) {
                cachedLatest[preview.contactMobile] = Message(
                    id = "",
                    text = preview.lastMessageText,
                    senderMobile = preview.lastMessageSender,
                    timestamp = preview.lastTimestamp,
                    timeString = preview.lastTimeString,
                    fileType = preview.lastFileType,
                    isCallLog = preview.lastIsCallLog
                )
                cachedUnread[preview.contactMobile] = preview.unreadCount
            }
        }
        if (cachedLatest.isNotEmpty()) {
            latestMessages = cachedLatest
            unreadCounts = cachedUnread
            // Cache আছে মানে এখনই ready — blank screen নেই
            usersLoaded = true
            onSplashDone()
        }
    }

    // ─── Contact Sync (WhatsApp style) ───────────────────────────────────────
    var deviceContactNumbers by remember { mutableStateOf<Set<String>>(emptySet()) }
    // contactsLoaded = true once we've actually attempted to read the phonebook
    // (or confirmed permission is denied). Until then we skip the phonebook
    // filter so the chat list is visible immediately — exactly like WhatsApp.
    var contactsLoaded by remember { mutableStateOf(false) }
    var contactsPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED
        )
    }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        contactsPermissionGranted = granted
        if (granted) {
            deviceContactNumbers = getDeviceContactNumbers(context)
        }
        contactsLoaded = true // permission dialog dismissed — either way, stop blocking
    }

    // Permission check + load contacts on first open
    LaunchedEffect(contactsPermissionGranted) {
        if (contactsPermissionGranted) {
            deviceContactNumbers = getDeviceContactNumbers(context)
            contactsLoaded = true
        } else {
            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            // contactsLoaded stays false until the launcher callback fires
        }
    }
    // ─────────────────────────────────────────────────────────────────────────

    // Real-time users
    LaunchedEffect(Unit) {
        db.collection("chat_users").addSnapshotListener { snapshot, _ ->
            snapshot?.documents?.mapNotNull { doc ->
                doc.data?.let { d ->
                    User(
                        uid = d["uid"] as? String ?: "",
                        name = d["name"] as? String ?: "",
                        mobile = doc.id,
                        avatarUrl = d["avatarUrl"] as? String ?: "",
                        lastActive = d["lastActive"] as? Long ?: 0,
                        typingTo = d["typingTo"] as? String,
                        statusVisible = d["statusVisible"] as? Boolean ?: true,
                        about = d["about"] as? String ?: ""
                    )
                }
            }?.filter { it.mobile != currentUser.mobile }?.also { users = it }
            usersLoaded = true  // প্রথম snapshot এলেই, খালি হলেও — empty state দেখানো ঠিক আছে
            onSplashDone()     // splash লুকাও — data ready
        }
    }

    // ── RTDB presence: contact list এ real-time online status ─────────────────
    // সমস্যা: Firestore lastActive শুধু আপডেট হয় যখন contact এর app চালু থাকে।
    // Data বন্ধ হলে contact এর client side listener মরে যায় → Firestore stale থাকে
    // → contact list এ সবসময় "online" দেখায় (ONLINE_THRESHOLD_MS পার না হওয়া পর্যন্ত)।
    // Fix: RTDB presence node সরাসরি read করো — Firebase server onDisconnect handle করে,
    // data/WiFi বন্ধ হলেই server নিজে online:false লিখে দেয়। Real-time, accurate।
    var rtdbLastActive by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }

    LaunchedEffect(users) {
        if (users.isEmpty()) return@LaunchedEffect
        val rtdb = com.google.firebase.database.FirebaseDatabase.getInstance()
        users.forEach { user ->
            val presenceRef = rtdb.getReference("presence").child(user.mobile)
            presenceRef.addValueEventListener(object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    val isOnlineRtdb = snapshot.child("online").getValue(Boolean::class.java) ?: false
                    val lastActiveRtdb = snapshot.child("lastActive").getValue(Long::class.java) ?: 0L
                    val effectiveLastActive = when {
                        isOnlineRtdb -> System.currentTimeMillis()   // সত্যিই online → now
                        lastActiveRtdb > 0 -> lastActiveRtdb          // offline → disconnect timestamp
                        else -> return                                  // কোনো data নেই → skip
                    }
                    rtdbLastActive = rtdbLastActive + (user.mobile to effectiveLastActive)
                }
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
            })
        }
    }

    // RTDB data দিয়ে users এর lastActive override করো — Firestore এর চেয়ে accurate
    val usersWithLivePresence = users.map { user ->
        val rtdbTs = rtdbLastActive[user.mobile]
        if (rtdbTs != null) user.copy(lastActive = rtdbTs) else user
    }

    // ── Firestore latest message sync → Room + UI update ──────────────────────
    // এটা background sync — chat list এ last message দেখানোর জন্য
    // Room থেকে instant load হয়েছে already — এটা শুধু update করে
    LaunchedEffect(users) {
        users.forEach { user ->
            val chatId = generateChatId(currentUser.mobile, user.mobile)
            db.collection("pvt_msg_$chatId")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(1)
                .addSnapshotListener { snap, _ ->
                    snap?.documents?.firstOrNull()?.let { doc ->
                        doc.data?.let { d ->
                            val msg = Message(
                                id = doc.id,
                                text = AESCrypto.decrypt(chatId, d["text"] as? String ?: "") ?: "",
                                senderMobile = d["senderMobile"] as? String ?: "",
                                timestamp = d["timestamp"] as? Long ?: 0,
                                timeString = d["timeString"] as? String ?: "",
                                fileUrl = d["fileUrl"] as? String,
                                fileType = d["fileType"] as? String,
                                read = d["read"] as? Boolean ?: false,
                                isCallLog = d["isCallLog"] as? Boolean ?: false,
                                isDeleted = d["isDeleted"] as? Boolean ?: false
                            )
                            latestMessages = latestMessages + (user.mobile to msg)

                            // Room chat preview update করো (persistent cache)
                            scope.launch(Dispatchers.IO) {
                                val unread = (unreadCounts[user.mobile] ?: 0)
                                rasGramRepo.chatPreviewDao.upsertPreview(
                                    CachedChatPreview(
                                        contactMobile = user.mobile,
                                        contactName = user.name,
                                        contactAvatarUrl = user.avatarUrl,
                                        lastMessageText = msg.text,
                                        lastMessageSender = msg.senderMobile,
                                        lastTimestamp = msg.timestamp,
                                        lastTimeString = msg.timeString,
                                        lastFileType = msg.fileType,
                                        lastIsCallLog = msg.isCallLog,
                                        unreadCount = unread
                                    )
                                )
                            }
                        }
                    }

                    // Unread count — Firestore query
                    db.collection("pvt_msg_$chatId")
                        .whereEqualTo("senderMobile", user.mobile)
                        .whereEqualTo("read", false)
                        .get().addOnSuccessListener { qs ->
                            val count = qs.size()
                            unreadCounts = unreadCounts + (user.mobile to count)
                            // Room preview এ unread count আপডেট
                            scope.launch(Dispatchers.IO) {
                                rasGramRepo.chatPreviewDao.updateUnreadCount(user.mobile, count)
                            }
                        }
                }
        }
    }

    // WhatsApp style: শুধু phonebook contacts যারা RasGram-এ আছে।
    // contactsLoaded না হওয়া পর্যন্ত phonebook filter skip — সরাসরি chat list দেখাও।
    // ── WhatsApp exact: Room cache থেকে instant chat list ────────────────────
    // Firestore users না আসা পর্যন্ত cachedPreviews থেকে User বানাও।
    // Firestore এলে automatically replace হবে — কোনো flicker নেই।
    val displayUsers = if (users.isEmpty() && cachedPreviews.isNotEmpty()) {
        cachedPreviews
            .filter { it.contactMobile != currentUser.mobile }
            .map { preview ->
                User(mobile = preview.contactMobile, name = preview.contactName, avatarUrl = preview.contactAvatarUrl)
            }
    } else usersWithLivePresence  // RTDB presence দিয়ে lastActive override করা

    val filteredUsers = displayUsers.filter { user ->
        val isInPhonebook = when {
            !contactsLoaded -> true
            deviceContactNumbers.isEmpty() -> true
            else -> deviceContactNumbers.contains(user.mobile)
        }
        isInPhonebook && (
            user.name.contains(searchQuery, ignoreCase = true) ||
            user.mobile.contains(searchQuery)
        )
    }.sortedWith(compareByDescending<User> { latestMessages[it.mobile]?.timestamp ?: 0L })

    Column(modifier = modifier.fillMaxHeight().background(RasGramTheme.DarkBackground)) {
        // Header
        if (showSearch) {
            SearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onClose = { searchQuery = ""; showSearch = false }
            )
        } else {
            ChatsHeader(
                currentUser = currentUser,
                onSearchClick = { showSearch = true },
                onSettingsClick = { showSettings = true },
                onNewGroupClick = { showNewGroup = true },
                onAddContactClick = { showAddContact = true },
                onToggleTheme = onToggleTheme,
                onLogout = onLogout
            )
        }

        HorizontalDivider(color = RasGramTheme.DividerColor, thickness = 0.5.dp)

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // Shimmer skeleton: Room ও নেই, Firestore ও আসেনি → skeleton
            if (!usersLoaded && cachedPreviews.isEmpty()) {
                items(6) { ChatSkeletonItem() }
            } else if (usersLoaded && filteredUsers.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.PersonAddAlt, null, modifier = Modifier.size(64.dp), tint = RasGramTheme.TextMuted.copy(0.4f))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No contacts on RasGram", color = RasGramTheme.TextMuted, style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            if (deviceContactNumbers.isEmpty())
                                "Allow contacts permission to see your phonebook contacts who use RasGram."
                            else
                                "None of your phonebook contacts are on RasGram yet. Invite them!",
                            color = RasGramTheme.TextMuted,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            items(filteredUsers, key = { it.mobile }) { user ->
                ContactItem(
                    user = user,
                    latestMessage = latestMessages[user.mobile],
                    unreadCount = unreadCounts[user.mobile] ?: 0,
                    isSelected = selectedContact?.mobile == user.mobile,
                    currentUserMobile = currentUser.mobile,
                    isLanAvailable = lanDiscoveredUsers.any { it.mobile == user.mobile },
                    onClick = { onContactSelect(user) }
                )
                HorizontalDivider(color = RasGramTheme.DividerColor, thickness = 0.5.dp, modifier = Modifier.padding(start = 80.dp))
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            currentUser = currentUser,
            onDismiss = { showSettings = false },
            onSave = { updated ->
                showSettings = false
                onUserUpdate(updated)
            }
        )
    }
    if (showNewGroup) NewGroupDialog(onDismiss = { showNewGroup = false }, currentUser = currentUser)
}

@Composable
fun ChatsHeader(
    currentUser: User,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onNewGroupClick: () -> Unit,
    onAddContactClick: () -> Unit,
    onToggleTheme: () -> Unit,
    onLogout: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xFF0D1B22), Color(0xFF0B2027), Color(0xFF0D1B22))
                )
            )
            .padding(horizontal = 16.dp)
            .height(62.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text("Ras", style = MaterialTheme.typography.titleLarge,
                color = RasGramTheme.TextPrimary, fontWeight = FontWeight.ExtraBold)
            Text(
                "Gram",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                // Colorful gradient text effect via shimmer fallback
                color = RasGramTheme.GreenLight
            )
        }
        IconButton(onClick = onSearchClick) {
            Icon(Icons.Default.Search, null, tint = RasGramTheme.Green)
        }
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.MoreVert, null, tint = RasGramTheme.TextMuted)
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier.background(RasGramTheme.DarkPanel)
            ) {
                DropdownMenuItem(
                    text = { Text("New Group", color = RasGramTheme.TextPrimary) },
                    leadingIcon = { Icon(Icons.Default.People, null, tint = RasGramTheme.TextMuted) },
                    onClick = { onNewGroupClick(); showMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("Settings", color = RasGramTheme.TextPrimary) },
                    leadingIcon = { Icon(Icons.Default.Settings, null, tint = RasGramTheme.TextMuted) },
                    onClick = { onSettingsClick(); showMenu = false }
                )
                DropdownMenuItem(
                    text = { Text(if (true) "Light Mode" else "Dark Mode", color = RasGramTheme.TextPrimary) },
                    leadingIcon = { Icon(Icons.Default.WbSunny, null, tint = RasGramTheme.TextMuted) },
                    onClick = { onToggleTheme(); showMenu = false }
                )
                HorizontalDivider(color = RasGramTheme.Border)
                DropdownMenuItem(
                    text = { Text("Logout", color = RasGramTheme.Red) },
                    leadingIcon = { Icon(Icons.Default.ExitToApp, null, tint = RasGramTheme.Red) },
                    onClick = { onLogout(); showMenu = false }
                )
            }
        }
    }
}

@Composable
fun SearchBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(RasGramTheme.DarkPanel)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Default.ArrowBack, null, tint = RasGramTheme.Green)
        }
        Row(
            modifier = Modifier.weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(RasGramTheme.DarkBackground)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, null,
                tint = RasGramTheme.TextMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    color = RasGramTheme.TextPrimary, fontSize = 15.sp
                ),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text("Search...",
                            color = RasGramTheme.TextMuted, fontSize = 15.sp)
                        inner()
                    }
                },
                modifier = Modifier.weight(1f)
            )
            if (query.isNotEmpty()) {
                Icon(Icons.Default.Clear, null,
                    tint = RasGramTheme.TextMuted,
                    modifier = Modifier.size(16.dp).clickable { onQueryChange("") })
            }
        }
    }
}

// ==================== CONTACT ITEM ====================
@Composable
fun ContactItem(
    user: User,
    latestMessage: Message?,
    unreadCount: Int,
    isSelected: Boolean,
    currentUserMobile: String,
    isLanAvailable: Boolean = false,
    onClick: () -> Unit
) {
    val isOnline = user.lastActive > System.currentTimeMillis() - ONLINE_THRESHOLD_MS
    val isTyping = user.typingTo != null

    // Missed call detection — incoming call that wasn't answered
    val isMissedCall = latestMessage?.isCallLog == true &&
        latestMessage.callStatus == "missed" &&
        latestMessage.senderMobile != currentUserMobile

    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = when {
            isSelected -> RasGramTheme.DarkPanel
            else -> Color.Transparent
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar + online/LAN dot
            Box(modifier = Modifier.size(54.dp)) {
                UserAvatar(user = user, size = 54.dp)
                if (isLanAvailable) {
                    // LAN badge — cyan colour, slightly bigger than online dot
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .align(Alignment.BottomEnd)
                            .border(2.dp, RasGramTheme.DarkBackground, CircleShape)
                            .background(Color(0xFF00BCD4), CircleShape)
                    )
                } else if (isOnline) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .align(Alignment.BottomEnd)
                            .border(2.dp, RasGramTheme.DarkBackground, CircleShape)
                            .background(RasGramTheme.OnlineGreen, CircleShape)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        user.name.ifEmpty { user.mobile },
                        style = MaterialTheme.typography.bodyLarge,
                        color = RasGramTheme.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // LAN badge — internet ছাড়াই connect হওয়া বোঝাতে
                    if (isLanAvailable) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "📶",
                            fontSize = 12.sp
                        )
                    }
                    // Time — red for missed call, green for unread, muted otherwise
                    Text(
                        latestMessage?.timeString ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            isMissedCall -> RasGramTheme.Red
                            unreadCount > 0 -> RasGramTheme.Green
                            else -> RasGramTheme.TextMuted
                        },
                        fontSize = 11.sp
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    if (isTyping) {
                        Text("typing...", style = MaterialTheme.typography.bodySmall, color = RasGramTheme.Green, fontWeight = FontWeight.Medium)
                    } else {
                        // Tick icon for sent messages (not for missed calls)
                        if (!isMissedCall && latestMessage?.senderMobile == currentUserMobile && latestMessage != null) {
                            Icon(
                                imageVector = when {
                                    latestMessage.isPending -> Icons.Default.AccessTime
                                    latestMessage.read -> Icons.Default.DoneAll      // FIX: was Done (single tick)
                                    latestMessage.delivered -> Icons.Default.DoneAll  // FIX: was Done (single tick)
                                    else -> Icons.Default.Check
                                },
                                contentDescription = null,
                                modifier = Modifier.size(14.dp).padding(end = 2.dp),
                                tint = when {
                                    latestMessage.read -> RasGramTheme.BlueTick
                                    latestMessage.isPending -> RasGramTheme.TextMuted
                                    else -> RasGramTheme.TextMuted
                                }
                            )
                        }
                        // Missed call icon
                        if (isMissedCall) {
                            Icon(
                                Icons.Default.CallMissed,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp).padding(end = 3.dp),
                                tint = RasGramTheme.Red
                            )
                        }
                        val previewText = when {
                            isMissedCall -> "Missed ${if (latestMessage?.callType == "video") "video" else "voice"} call"
                            latestMessage?.isDeleted == true -> "🚫 This message was deleted"
                            latestMessage?.isCallLog == true -> "${if (latestMessage.callType == "video") "📹" else "📞"} ${latestMessage.text.ifEmpty { "Voice call" }}"
                            latestMessage?.text?.isNotEmpty() == true -> latestMessage.text
                            latestMessage != null -> getFileTypePreview(latestMessage)
                            else -> "Tap to start chatting"
                        }
                        Text(
                            previewText,
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                isMissedCall -> RasGramTheme.Red
                                unreadCount > 0 && latestMessage?.senderMobile != currentUserMobile -> RasGramTheme.TextPrimary
                                else -> RasGramTheme.TextMuted
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (unreadCount > 0 && latestMessage?.senderMobile != currentUserMobile) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(shape = CircleShape, color = if (isMissedCall) RasGramTheme.Red else RasGramTheme.Green) {
                                Text(
                                    if (unreadCount > 99) "99+" else unreadCount.toString(),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== CHAT AREA ====================
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatArea(
    currentUser: User,
    contact: User,
    onBack: () -> Unit,
    onCallClick: (String) -> Unit
) {
    val db = remember { FirebaseFirestore.getInstance() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isCompact = isCompactScreen()

    // ── WhatsApp-style: Room DB থেকে instant load, Firestore background sync ──
    val rasGramRepo = remember { RasGramRepository.getInstance(context) }

    // ── LAN Mode ─────────────────────────────────────────────────────────────
    val prefs = remember { context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) }
    val lanModeEnabled = remember { prefs.getBoolean(PREF_LAN_MODE, false) }
    val lanManager = remember { LanChatManager.getInstance(context) }
    val lanDiscoveredUsers by lanManager.discoveredUsers.collectAsState()
    // Contact টি LAN এ আছে কিনা — name match বা mobile match
    val lanPeer = remember(lanDiscoveredUsers, contact.mobile) {
        lanDiscoveredUsers.firstOrNull { it.mobile == contact.mobile }
    }
    val isLanAvailable = lanModeEnabled && lanPeer != null

    // ── LAN Call state ────────────────────────────────────────────────────────
    val lanCallManager = remember { LanCallManager.getInstance(context) }
    var showLanCallUI by remember { mutableStateOf(false) }
    var lanCallType by remember { mutableStateOf("audio") }

    // Effective call handler: LAN peer আছে → LAN call, নাহলে Firebase call
    val effectiveCallClick: (String) -> Unit = { type ->
        if (isLanAvailable && lanPeer != null) {
            lanCallType = type
            showLanCallUI = true
            scope.launch {
                lanCallManager.startCall(
                    peer = lanPeer,
                    myMobile = currentUser.mobile,
                    myName = currentUser.name,
                    callType = type
                )
            }
        } else {
            onCallClick(type)   // normal Firebase WebRTC call
        }
    }

    // LAN Call screen overlay
    if (showLanCallUI) {
        CallingLanScreen(
            currentUser = currentUser,
            peerName = contact.name,
            peerMobile = contact.mobile,
            callType = lanCallType,
            call = null,   // caller role
            onEndCall = { showLanCallUI = false }
        )
        return
    }

    var inputText by remember { mutableStateOf("") }
    var liveContact by remember { mutableStateOf(contact) }
    var isUploading by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableFloatStateOf(0f) }
    var uploadingFileName by remember { mutableStateOf("") }
    var replyToMessage by remember { mutableStateOf<Message?>(null) }
    var selectedMessages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current

    val chatId = remember(currentUser.mobile, contact.mobile) {
        generateChatId(currentUser.mobile, contact.mobile)
    }

    // ── STEP 1: Room DB থেকে instant local load (offline ও কাজ করে) ──────────
    // produceState: null = "Room এখনো emit করেনি" → skeleton দেখাও
    //               non-null = actual data (empty list হলেও) → messages দেখাও
    // এটাই flicker-free loading এর চাবিকাঠি — initialValue=emptyList() দিলে
    // blank → full jump হয়, কিন্তু null দিলে skeleton → full (কোনো flicker নেই)
    val cachedMessagesState by produceState<List<CachedMessage>?>(
        initialValue = null,
        key1 = chatId
    ) {
        rasGramRepo.messageDao.getMessages(chatId).collect { msgs ->
            value = msgs
        }
    }

    val messagesLoaded = cachedMessagesState != null

    // CachedMessage → Message conversion for UI
    val messages = remember(cachedMessagesState) {
        with(rasGramRepo) {
            cachedMessagesState?.map { it.toMessage() } ?: emptyList()
        }
    }

    // File launchers
    val imageVideoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val mimeType = context.contentResolver.getType(it) ?: "application/octet-stream"
            uploadingFileName = getFileName(context, it) ?: "media"
            isUploading = true
            scope.launch {
                try {
                    if (isLanAvailable && lanPeer != null) {
                        // ── LAN: Cloudinary নয়, সরাসরি TCP দিয়ে ────────────────
                        val tempFile = uriToTempFile(context, it, uploadingFileName)
                        if (tempFile != null) {
                            // FIX: onProgress দিয়ে LAN upload % UI তে দেখাও
                            lanManager.sendFile(lanPeer, tempFile, mimeType, chatId) { prog ->
                                kotlinx.coroutines.runBlocking {
                                    withContext(Dispatchers.Main) { uploadProgress = prog }
                                }
                            }
                        }
                    } else {
                        // FIX: WhatsApp style — attach করলেই সাথে সাথে pending bubble দেখাও।
                        // আগে: upload শেষ হওয়ার পরে sendMessage call হত → UI তে দেরিতে দেখাত।
                        // এখন: আগে pending message Room এ save করো (instant bubble),
                        //       তারপর background এ upload করো, upload শেষে Firestore update করো।
                        val pendingId = "pending_${System.currentTimeMillis()}_upload"
                        val attachedFileName = uploadingFileName

                        // Step 1: সাথে সাথে pending bubble দেখাও (placeholder URL দিয়ে)
                        sendMessage(
                            db, chatId, currentUser.mobile, currentUser.name, contact.mobile,
                            "", context,
                            fileUrl  = "uploading://$pendingId",   // placeholder
                            fileName = attachedFileName,
                            fileType = mimeType
                        )

                        // Step 2: background upload
                        val (url, uploadedFileName, fileType) = uploadToCloudinary(context, it) { prog ->
                            uploadProgress = prog
                        }

                        if (url != null) {
                            // Step 3: pending message কে real URL দিয়ে update করো
                            // Firestore এ pending doc খুঁজে update — Room এ isPending=false হবে Firestore listener এ
                            try {
                                val snap = db.collection("messages").document(chatId)
                                    .collection("msgs")
                                    .whereEqualTo("fileUrl", "uploading://$pendingId")
                                    .limit(1).get().await()
                                snap.documents.firstOrNull()?.reference?.update(
                                    "fileUrl", url,
                                    "fileName", uploadedFileName,
                                    "fileType", fileType,
                                    "isPending", false
                                )
                            } catch (_: Exception) {}
                        } else {
                            Toast.makeText(context, "আপলোড ব্যর্থ হয়েছে", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                isUploading = false
                uploadProgress = 0f
                uploadingFileName = ""
            }
        }
    }

    val docLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            uploadingFileName = getFileName(context, it) ?: "file"
            isUploading = true
            scope.launch {
                try {
                    val mimeType = context.contentResolver.getType(it) ?: "application/octet-stream"
                    if (isLanAvailable && lanPeer != null) {
                        val tempFile = uriToTempFile(context, it, uploadingFileName)
                        if (tempFile != null) {
                            lanManager.sendFile(lanPeer, tempFile, mimeType, chatId) { prog ->
                                kotlinx.coroutines.runBlocking {
                                    withContext(Dispatchers.Main) { uploadProgress = prog }
                                }
                            }
                        }
                    } else {
                        val pendingId = "pending_${System.currentTimeMillis()}_upload"
                        val attachedFileName = uploadingFileName
                        val mimeType2 = context.contentResolver.getType(it) ?: "application/octet-stream"

                        sendMessage(
                            db, chatId, currentUser.mobile, currentUser.name, contact.mobile,
                            "", context,
                            fileUrl  = "uploading://$pendingId",
                            fileName = attachedFileName,
                            fileType = mimeType2
                        )

                        val (url, uploadedFileName, fileType) = uploadToCloudinary(context, it) { prog ->
                            uploadProgress = prog
                        }

                        if (url != null) {
                            try {
                                val snap = db.collection("messages").document(chatId)
                                    .collection("msgs")
                                    .whereEqualTo("fileUrl", "uploading://$pendingId")
                                    .limit(1).get().await()
                                snap.documents.firstOrNull()?.reference?.update(
                                    "fileUrl", url,
                                    "fileName", uploadedFileName,
                                    "fileType", fileType,
                                    "isPending", false
                                )
                            } catch (_: Exception) {}
                        } else {
                            Toast.makeText(context, "আপলোড ব্যর্থ হয়েছে", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                isUploading = false
                uploadProgress = 0f
                uploadingFileName = ""
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms[Manifest.permission.RECORD_AUDIO] == true) {
            // Start recording
        }
    }

    // ── File-from-folder launcher (WhatsApp style — any file type) ────────────
    // Shows uploading filename + % progress in the input bar
    val anyFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val name = getFileName(context, it) ?: "file_${System.currentTimeMillis()}"
            uploadingFileName = name
            isUploading = true
            scope.launch {
                try {
                    val mimeType = context.contentResolver.getType(it) ?: "application/octet-stream"
                    if (isLanAvailable && lanPeer != null) {
                        val tempFile = uriToTempFile(context, it, name)
                        if (tempFile != null) {
                            lanManager.sendFile(lanPeer, tempFile, mimeType, chatId)
                            Toast.makeText(context, "📶 LAN দিয়ে পাঠানো হয়েছে", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val (url, fileName, fileType) = uploadToCloudinary(context, it) { prog -> uploadProgress = prog }
                        if (url != null) {
                            sendMessage(db, chatId, currentUser.mobile, currentUser.name, contact.mobile, "", context, url, fileName, fileType)
                            Toast.makeText(context, "📎 ফাইল পাঠানো হয়েছে", Toast.LENGTH_SHORT).show()
                        } else Toast.makeText(context, "আপলোড ব্যর্থ হয়েছে", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                isUploading = false
                uploadProgress = 0f
                uploadingFileName = ""
            }
        }
    }

    // ── Folder launcher — OpenDocumentTree দিয়ে পুরো folder pick করা ──────────
    // Sub-folder সহ recursive zip → Cloudinary upload → chat এ FOLDER bubble।
    // Receiver এর phone এ Downloads/<folderName>/ হিসেবে extract হয়।
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        treeUri?.let { uri ->
            scope.launch {
                try {
                    // Folder নাম বের করো
                    val docTree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                    val folderName = docTree?.name ?: "folder_${System.currentTimeMillis()}"
                    uploadingFileName = "📁 $folderName"
                    isUploading = true
                    uploadProgress = 0f

                    // Cache dir এ zip file তৈরি করো
                    val zipFile = java.io.File(context.cacheDir, "${folderName}_${System.currentTimeMillis()}.zip")

                    // Recursive zip — sub-folder সহ সব কিছু
                    val zipOk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            java.util.zip.ZipOutputStream(
                                java.io.BufferedOutputStream(java.io.FileOutputStream(zipFile))
                            ).use { zos ->
                                zipDocumentFile(context, docTree!!, folderName, zos)
                            }
                            true
                        } catch (e: Exception) {
                            android.util.Log.e("FolderZip", "zip error: ${e.message}", e)
                            false
                        }
                    }

                    if (!zipOk || !zipFile.exists() || zipFile.length() == 0L) {
                        Toast.makeText(context, "ফোল্ডার zip করা যায়নি", Toast.LENGTH_SHORT).show()
                        isUploading = false
                        uploadingFileName = ""
                        zipFile.delete()
                        return@launch
                    }

                    // Cloudinary তে upload
                    val (url, _, _) = uploadToCloudinary(context, zipFile.toUri()) { prog -> uploadProgress = prog }
                    zipFile.delete()  // temp zip মুছে দাও

                    if (url != null) {
                        // RASGRAM_FOLDER_PREFIX যোগ করো — receiver বুঝবে এটা folder
                        val markedName = "${RASGRAM_FOLDER_PREFIX}${folderName}.zip"
                        sendMessage(
                            db, chatId, currentUser.mobile, currentUser.name,
                            contact.mobile, "", context,
                            url, markedName, "application/zip"
                        )
                        Toast.makeText(context, "📁 ফোল্ডার পাঠানো হয়েছে", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "আপলোড ব্যর্থ হয়েছে", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                isUploading = false
                uploadProgress = 0f
                uploadingFileName = ""
            }
        }
    }

    // ── STEP 3: Firestore real-time sync → Room এ save → UI auto-update ───────
    // UI directly Firestore থেকে পড়ে না — Room Flow থেকে পড়ে (above)
    // এটা background sync — UI এর সাথে decouple
    // limit(50): শুধু সর্বশেষ ৫০টা Firestore থেকে live sync।
    // পুরানো messages Room এ আছে (archive হওয়ার আগ পর্যন্ত)।
    // এতে Firestore read cost ~95% কমে — ১০০০ message chat এ ও শুধু ৫০টা read।
    LaunchedEffect(chatId) {
        db.collection("pvt_msg_$chatId")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                snapshot?.let { qs ->
                    val newMessages = qs.documents.mapNotNull { doc ->
                        doc.data?.let { d ->
                            CachedMessage(
                                id = doc.id,
                                chatId = chatId,
                                text = AESCrypto.decrypt(chatId, d["text"] as? String ?: ""),
                                senderMobile = d["senderMobile"] as? String ?: "",
                                receiverMobile = d["receiverMobile"] as? String ?: "",
                                timestamp = d["timestamp"] as? Long ?: 0,
                                timeString = d["timeString"] as? String ?: "",
                                fileUrl = d["fileUrl"] as? String,
                                fileName = d["fileName"] as? String,
                                fileType = d["fileType"] as? String,
                                fileSizeBytes = d["fileSizeBytes"] as? Long ?: 0,
                                reaction = d["reaction"] as? String,
                                read = d["read"] as? Boolean ?: false,
                                delivered = d["delivered"] as? Boolean ?: false,
                                isCallLog = d["isCallLog"] as? Boolean ?: false,
                                callStatus = d["callStatus"] as? String,
                                callType = d["callType"] as? String,
                                isPending = doc.metadata.hasPendingWrites(),
                                replyToId = d["replyToId"] as? String,
                                replyToText = d["replyToText"]?.let { AESCrypto.decrypt(chatId, it as String) },
                                replyToSender = d["replyToSender"] as? String,
                                isDeleted = d["isDeleted"] as? Boolean ?: false,
                                isForwarded = d["isForwarded"] as? Boolean ?: false,
                                isStarred = d["isStarred"] as? Boolean ?: false,
                                duration = (d["duration"] as? Long)?.toInt() ?: 0
                            )
                        }
                    }

                    // Background এ Room এ save করো (IO thread এ)
                    scope.launch(Dispatchers.IO) {
                        rasGramRepo.messageDao.upsertMessages(newMessages)

                        // ── Auto-download: নতুন received messages এর image/audio locally save ──
                        // WhatsApp এর মতো — image ও audio automatically Rasgram folder এ নামে।
                        // Video ও document বড় হতে পারে — manual download এ রাখা হয়েছে।
                        newMessages.filter { msg ->
                            msg.senderMobile == contact.mobile &&  // শুধু received (আমার পাঠানো না)
                            !msg.fileUrl.isNullOrEmpty() &&
                            !msg.isDeleted &&
                            !msg.fileUrl!!.startsWith("local://") && // LAN file: already local, skip
                            (msg.fileType?.startsWith("image/") == true ||
                             msg.fileType?.startsWith("audio/") == true)
                        }.forEach { msg ->
                            val localFile = getRasgramCachedFile(context, msg.fileUrl!!, msg.fileName, msg.fileType)
                            if (!localFile.exists()) {
                                // Background এ quietly download — error হলে ignore (manual download আছে)
                                try {
                                    downloadToRasgramFolder(context, msg.fileUrl, msg.fileName, msg.fileType ?: "application/octet-stream")
                                } catch (_: Exception) {}
                            }
                        }

                        // Update chat preview (last message + unread)
                        val lastMsg = newMessages.maxByOrNull { it.timestamp }
                        if (lastMsg != null) {
                            val unread = newMessages.count {
                                it.senderMobile == contact.mobile && !it.read
                            }
                            rasGramRepo.chatPreviewDao.upsertPreview(
                                CachedChatPreview(
                                    contactMobile = contact.mobile,
                                    contactName = contact.name,
                                    contactAvatarUrl = contact.avatarUrl,
                                    lastMessageText = lastMsg.text,
                                    lastMessageSender = lastMsg.senderMobile,
                                    lastTimestamp = lastMsg.timestamp,
                                    lastTimeString = lastMsg.timeString,
                                    lastFileType = lastMsg.fileType,
                                    lastIsCallLog = lastMsg.isCallLog,
                                    unreadCount = unread
                                )
                            )
                        }
                    }



                    // FIX: mark delivered=true and read=true SEPARATELY.
                    // Before: both were set together when chat was opened → grey double tick never showed.
                    // Now: mark delivered=true for ALL unread messages (even if we haven't scrolled to them).
                    //       mark read=true only for messages we're marking as seen.
                    // The FCM service marks delivered=true when the notification arrives (separate path).
                    qs.documents.filter { doc ->
                        doc.getString("senderMobile") == contact.mobile && doc.getBoolean("read") == false
                    }.forEach { doc ->
                        // opened chat = message is both delivered and read
                        doc.reference.update("read", true, "delivered", true)
                    }
                    // Also mark delivered=true for any messages that arrived but were never marked delivered
                    // (e.g. FCM path failed). This closes the gap for grey double tick.
                    qs.documents.filter { doc ->
                        doc.getString("senderMobile") == contact.mobile
                            && doc.getBoolean("delivered") == false
                            && doc.getBoolean("read") == false
                    }.forEach { doc ->
                        doc.reference.update("delivered", true)
                    }

                    // Room এ ও read mark করো
                    scope.launch(Dispatchers.IO) {
                        rasGramRepo.messageDao.markAsRead(chatId, contact.mobile)
                    }
                }
            }
    }

    // Contact live status from Firestore (name, avatar, typing)
    LaunchedEffect(contact.mobile) {
        db.collection("chat_users").document(contact.mobile).addSnapshotListener { snap, _ ->
            snap?.data?.let { d ->
                liveContact = liveContact.copy(
                    name = d["name"] as? String ?: liveContact.name,
                    avatarUrl = d["avatarUrl"] as? String ?: liveContact.avatarUrl,
                    typingTo = d["typingTo"] as? String,
                    lastActive = d["lastActive"] as? Long ?: 0
                )
            }
        }
    }

    // FIX: Online/last-seen from Firebase RTDB presence — accurate even when contact's app is closed.
    // Problem before: Firestore lastActive was only updated when the contact's OWN app was running.
    // If contact closed the app, RTDB fired onDisconnect (server-side, always works), but Firestore
    // lastActive was never updated (client-side listener dead). So we saw stale "online" from Firestore.
    // Fix: read RTDB presence directly for the contact — this always reflects true online state.
    LaunchedEffect(contact.mobile) {
        try {
            val rtdb = com.google.firebase.database.FirebaseDatabase.getInstance()
            val presenceRef = rtdb.getReference("presence").child(contact.mobile)
            presenceRef.addValueEventListener(object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    val isOnlineRtdb = snapshot.child("online").getValue(Boolean::class.java) ?: false
                    val lastActiveRtdb = snapshot.child("lastActive").getValue(Long::class.java) ?: 0L
                    if (isOnlineRtdb) {
                        // Contact is online right now — bump lastActive to now so isOnline check passes
                        liveContact = liveContact.copy(lastActive = System.currentTimeMillis())
                    } else if (lastActiveRtdb > 0) {
                        // Contact went offline — use RTDB timestamp (accurate disconnect time)
                        liveContact = liveContact.copy(lastActive = lastActiveRtdb)
                    }
                }
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
            })
        } catch (_: Exception) {
            // RTDB unavailable — fall back to Firestore lastActive (may be slightly stale)
        }
    }

    // Auto scroll to bottom — WhatsApp exact behavior:
    // প্রথমবার load হলে instant jump (কোনো animation নেই, flicker নেই)
    // নতুন message আসলে smooth animate
    var prevMessagesSize by remember { mutableIntStateOf(0) }
    LaunchedEffect(messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (prevMessagesSize == 0) {
            // প্রথম load: instant, no animation — WhatsApp এর মতো সরাসরি নিচে
            listState.scrollToItem(messages.size - 1)
        } else if (messages.size > prevMessagesSize) {
            // নতুন message পাঠানো/আসা হলে smooth animate
            listState.animateScrollToItem(messages.size - 1)
        }
        prevMessagesSize = messages.size
    }

    // Recording timer
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingSeconds = 0
            while (isRecording) { delay(1000); recordingSeconds++ }
        }
    }

    var typingJob by remember { mutableStateOf<Job?>(null) }

    fun sendText() {
        val text = inputText.trim()
        if (text.isBlank()) return

        if (isLanAvailable && lanPeer != null) {
            // ── LAN Mode: 100% offline — TCP পাঠাও + Room এ sender copy save ──
            scope.launch {
                // 1. TCP দিয়ে peer এ পাঠাও
                lanManager.sendText(lanPeer, text, chatId)
                // 2. Sender নিজের copy Room এ save করো (Firebase নয়)
                val now       = System.currentTimeMillis()
                val timeStr   = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                                    .format(java.util.Date(now))
                val tempId    = "lan_out_${now}_${currentUser.mobile.takeLast(4)}"
                val rasRepo   = RasGramRepository.getInstance(context)
                rasRepo.messageDao.upsertMessage(
                    CachedMessage(
                        id             = tempId,
                        chatId         = chatId,
                        text           = text,
                        senderMobile   = currentUser.mobile,
                        receiverMobile = contact.mobile,
                        timestamp      = now,
                        timeString     = timeStr,
                        read           = true,
                        delivered      = true,
                        isPending      = false
                    )
                )
                // Chat preview update
                val existing = rasRepo.chatPreviewDao.getPreview(contact.mobile)
                rasRepo.chatPreviewDao.upsertPreview(
                    CachedChatPreview(
                        contactMobile     = contact.mobile,
                        contactName       = contact.name,
                        contactAvatarUrl  = contact.avatarUrl,
                        lastMessageText   = text,
                        lastMessageSender = currentUser.mobile,
                        lastTimestamp     = now,
                        lastTimeString    = timeStr,
                        unreadCount       = existing?.unreadCount ?: 0
                    )
                )
            }
        } else {
            // ── Normal Mode: Firebase Firestore ────────────────────────────────
            sendMessage(
                db, chatId, currentUser.mobile, currentUser.name, contact.mobile, text, context, null, null, null,
                replyToMessage?.id, replyToMessage?.text, replyToMessage?.senderMobile
            )
            typingJob?.cancel()
            scope.launch { db.collection("chat_users").document(currentUser.mobile).update("typingTo", null) }
        }
        inputText = ""
        replyToMessage = null
    }

    BackHandler(enabled = selectedMessages.isNotEmpty()) {
        selectedMessages = emptySet()
    }

    Column(modifier = Modifier.fillMaxSize().background(RasGramTheme.DarkBackground).statusBarsPadding().navigationBarsPadding().imePadding()) {
        // Header
        if (selectedMessages.isNotEmpty()) {
            SelectionHeader(
                count = selectedMessages.size,
                onClose = { selectedMessages = emptySet() },
                onDelete = {
                    scope.launch {
                        selectedMessages.forEach { id ->
                            db.collection("pvt_msg_$chatId").document(id).update("isDeleted", true, "text", "")
                        }
                        selectedMessages = emptySet()
                    }
                },
                onForward = { /* Forward logic */ },
                onStar = {
                    scope.launch {
                        selectedMessages.forEach { id ->
                            db.collection("pvt_msg_$chatId").document(id).update("isStarred", true)
                        }
                        selectedMessages = emptySet()
                    }
                },
                onCopy = {
                    val text = messages.filter { it.id in selectedMessages }.joinToString("\n") { it.text }
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("messages", text))
                    selectedMessages = emptySet()
                }
            )
        } else {
            ChatHeader(
                contact = liveContact,
                currentUserMobile = currentUser.mobile,
                isCompact = isCompact,
                onBack = onBack,
                onCallClick = effectiveCallClick,   // LAN peer → LanCallManager, else Firebase
                isLanActive = isLanAvailable,
                onClearChat = {
                    scope.launch {
                        db.collection("pvt_msg_$chatId").get().await().documents.forEach { it.reference.delete() }
                    }
                },
                onViewContact = { /* View contact */ }
            )
        }

        // Messages area
        Box(modifier = Modifier.weight(1f)) {
            // WhatsApp-style dark wallpaper with subtle icon pattern
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0B141A))
                    .drawBehind {
                        val patternColor = Color(0xFF1A2C35)
                        val iconSize = 28.dp.toPx()
                        val spacing = 52.dp.toPx()
                        var y = 0f
                        var row = 0
                        while (y < size.height + spacing) {
                            var x = if (row % 2 == 0) 0f else spacing / 2
                            while (x < size.width + spacing) {
                                drawCircle(
                                    color = patternColor,
                                    radius = iconSize / 2,
                                    center = androidx.compose.ui.geometry.Offset(x, y)
                                )
                                x += spacing
                            }
                            y += spacing * 0.86f
                            row++
                        }
                    }
            )

            // messagesLoaded = false হলে skeleton দেখাও (Room এখনো emit করেনি)
            // messagesLoaded = true হলে সরাসরি messages দেখাও (page + messages একসাথে)
            // এতে blank→full flicker সম্পূর্ণ দূর হয়
            if (!messagesLoaded) {
                // Skeleton: page আর messages একসাথে render হচ্ছে এমন দেখায়
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(8) { index ->
                        MessageSkeletonItem(isMe = index % 3 != 0)
                    }
                }
            } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                item {
                    EncryptionNotice()
                }

                var lastDateString = ""
                messages.forEach { message ->
                    val dateStr = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(Date(message.timestamp))
                    if (dateStr != lastDateString) {
                        lastDateString = dateStr
                        item(key = "date_$dateStr") {
                            DateDivider(dateStr)
                        }
                    }
                    item(key = message.id) {
                        MessageBubble(
                            message = message,
                            isMe = message.senderMobile == currentUser.mobile,
                            isSelected = message.id in selectedMessages,
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedMessages = selectedMessages + message.id
                            },
                            onClick = {
                                if (selectedMessages.isNotEmpty()) {
                                    selectedMessages = if (message.id in selectedMessages)
                                        selectedMessages - message.id else selectedMessages + message.id
                                }
                            },
                            onReact = { reaction ->
                                scope.launch {
                                    db.collection("pvt_msg_$chatId").document(message.id)
                                        .update("reaction", if (message.reaction == reaction) null else reaction)
                                }
                            },
                            onReply = { replyToMessage = message },
                            onDelete = {
                                scope.launch {
                                    // ── Telegram-style delete for everyone ────────────────────
                                    // ১. Firestore এ isDeleted=true, text="" (উভয় phone এ দেখা যাবে)
                                    db.collection("pvt_msg_$chatId").document(message.id)
                                        .update("isDeleted", true, "text", "", "fileUrl", null, "fileName", null)
                                    // ২. Room এ সাথে সাথে update (UI instant refresh)
                                    withContext(Dispatchers.IO) {
                                        rasGramRepo.messageDao.softDelete(message.id)
                                    }
                                    // ৩. Cloudinary থেকে media delete (background)
                                    if (!message.fileUrl.isNullOrEmpty()) {
                                        withContext(Dispatchers.IO) {
                                            try {
                                                val pubId = extractCloudinaryPublicId(message.fileUrl)
                                                if (pubId != null) {
                                                    val resType = cloudinaryResourceType(message.fileType)
                                                    deleteFromCloudinaryDirect(pubId, resType)
                                                }
                                            } catch (_: Exception) {}
                                        }
                                    }
                                }
                            },
                            onStar = {
                                scope.launch {
                                    db.collection("pvt_msg_$chatId").document(message.id)
                                        .update("isStarred", !message.isStarred)
                                }
                            },
                            onCopy = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("message", message.text))
                                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
            } // end else (messagesLoaded)


            // Scroll-to-bottom FAB - inside Box(weight(1f)) BoxScope, using wrapContentSize
            val showScrollFab by remember { derivedStateOf { listState.firstVisibleItemIndex < messages.size - 5 && messages.size > 10 } }
            if (showScrollFab) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 16.dp, end = 16.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    FloatingActionButton(
                        onClick = { scope.launch { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) } },
                        modifier = Modifier.size(40.dp),
                        containerColor = RasGramTheme.DarkPanel,
                        elevation = FloatingActionButtonDefaults.elevation(4.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, null, tint = RasGramTheme.TextMuted)
                    }
                }
            }
        }

        // Upload progress — WhatsApp style with filename + %
        if (isUploading) {
            FileUploadProgressIndicator(
                fileName = uploadingFileName,
                progress = uploadProgress
            )
        }

        // Reply preview
        replyToMessage?.let { reply ->
            ReplyPreview(
                message = reply,
                currentUserMobile = currentUser.mobile,
                onDismiss = { replyToMessage = null }
            )
        }

        // Input area
        ChatInputBar(
            inputText = inputText,
            onTextChange = { text ->
                inputText = text
                if (text.isNotEmpty()) {
                    typingJob?.cancel()
                    typingJob = scope.launch {
                        db.collection("chat_users").document(currentUser.mobile).update("typingTo", contact.mobile)
                        delay(TYPING_DEBOUNCE_MS)
                        db.collection("chat_users").document(currentUser.mobile).update("typingTo", null)
                    }
                }
            },
            onSend = { sendText() },
            onAttachClick = { showAttachMenu = true },
            isRecording = isRecording,
            recordingSeconds = recordingSeconds,
            onMicPress = {
                val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (!hasPerm) {
                    permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    return@ChatInputBar
                }
                if (!isRecording) {
                    try {
                        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
                        recordingFile = file
                        val recorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                            MediaRecorder(context)
                        else
                            @Suppress("DEPRECATION") MediaRecorder()
                        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        recorder.setOutputFile(file.absolutePath)
                        recorder.prepare()
                        recorder.start()
                        mediaRecorder = recorder
                        isRecording = true
                    } catch (e: Exception) {
                        Toast.makeText(context, "Recording error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onMicRelease = {
                if (isRecording) {
                    try {
                        mediaRecorder?.stop()
                        mediaRecorder?.release()
                        mediaRecorder = null
                        isRecording = false
                        val file = recordingFile ?: return@ChatInputBar
                        if (file.exists() && file.length() > 0) {
                            isUploading = true
                            scope.launch {
                                if (isLanAvailable && lanPeer != null) {
                                    // ── LAN: Cloudinary নয়, সরাসরি TCP দিয়ে ────────
                                    lanManager.sendVoice(lanPeer, file, recordingSeconds.toLong(), chatId)
                                    Toast.makeText(context, "📶 LAN দিয়ে voice পাঠানো হয়েছে", Toast.LENGTH_SHORT).show()
                                } else {
                                    val (url, fileName, _) = uploadToCloudinary(context, file.toUri()) { prog -> uploadProgress = prog }
                                    if (url != null) {
                                        sendMessage(db, chatId, currentUser.mobile, currentUser.name, contact.mobile, "", context, url, fileName ?: "voice.m4a", "audio/mp4", null, null, null, recordingSeconds)
                                    }
                                }
                                isUploading = false
                                uploadProgress = 0f
                                file.delete()
                            }
                        }
                    } catch (e: Exception) {
                        isRecording = false
                    }
                }
            },
            onMicCancel = {
                mediaRecorder?.release()
                mediaRecorder = null
                isRecording = false
                recordingFile?.delete()
            }
        )
    }

    // Attachment menu
    if (showAttachMenu) {
        EnhancedAttachmentMenuSheet(
            onDismiss = { showAttachMenu = false },
            onImageVideo = { imageVideoLauncher.launch(arrayOf("image/*", "video/*")); showAttachMenu = false },
            onDocument = { docLauncher.launch(arrayOf("*/*")); showAttachMenu = false },
            onAudio = { docLauncher.launch(arrayOf("audio/*")); showAttachMenu = false },
            onFilesFromFolder = {
                // OpenDocumentTree — পুরো folder pick করে, sub-folder সহ recursive zip হয়
                folderLauncher.launch(null)
                showAttachMenu = false
            }
        )
    }
}


@Composable
fun DateDivider(dateString: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = RasGramTheme.Border, thickness = 0.5.dp)
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF182229),
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Text(dateString, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = RasGramTheme.TextMuted, style = MaterialTheme.typography.labelSmall)
        }
        HorizontalDivider(modifier = Modifier.weight(1f), color = RasGramTheme.Border, thickness = 0.5.dp)
    }
}

@Composable
fun ChatHeader(
    contact: User,
    currentUserMobile: String,
    isCompact: Boolean,
    onBack: () -> Unit,
    onCallClick: (String) -> Unit,
    onClearChat: () -> Unit,
    onViewContact: () -> Unit,
    isLanActive: Boolean = false    // LAN mode: Firebase ছাড়াই connected
) {
    val isOnline = contact.lastActive > System.currentTimeMillis() - ONLINE_THRESHOLD_MS
    var showMenu by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxWidth(), color = RasGramTheme.DarkPanel, shadowElevation = 2.dp) {
        Column {
            // Single row: back + avatar/name + call icons + menu
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isCompact) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, null, tint = RasGramTheme.TextPrimary)
                    }
                }

                Row(
                    modifier = Modifier.weight(1f).clickable { onViewContact() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(40.dp)) {
                        UserAvatar(user = contact, size = 40.dp)
                        // LAN badge > online badge (priority)
                        if (isLanActive) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .align(Alignment.BottomEnd)
                                    .border(2.dp, RasGramTheme.DarkPanel, CircleShape)
                                    .background(Color(0xFF00BCD4), CircleShape),  // cyan = LAN
                                contentAlignment = Alignment.Center
                            ) {}
                        } else if (isOnline) {
                            Box(modifier = Modifier.size(10.dp).align(Alignment.BottomEnd).border(2.dp, RasGramTheme.DarkPanel, CircleShape).background(RasGramTheme.OnlineGreen, CircleShape))
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(contact.name, style = MaterialTheme.typography.bodyLarge, color = RasGramTheme.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (isLanActive) {
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    color = Color(0xFF00BCD4).copy(alpha = 0.18f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        "📶 LAN",
                                        color = Color(0xFF00BCD4),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            when {
                                isLanActive -> "LAN connected — internet ছাড়া চ্যাট হচ্ছে"
                                contact.typingTo == currentUserMobile -> "typing..."
                                isOnline -> "online"
                                else -> "last seen ${formatLastSeen(System.currentTimeMillis() - contact.lastActive)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isLanActive) Color(0xFF00BCD4) else if (contact.typingTo == currentUserMobile || isOnline) RasGramTheme.Green else RasGramTheme.TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }
                IconButton(onClick = { onCallClick("video") }) {
                    Icon(Icons.Default.Videocam, "Video Call", tint = RasGramTheme.TextPrimary, modifier = Modifier.size(24.dp))
                }
                // Voice call icon
                IconButton(onClick = { onCallClick("audio") }) {
                    Icon(Icons.Default.Call, "Voice Call", tint = RasGramTheme.TextPrimary, modifier = Modifier.size(22.dp))
                }

                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, null, tint = RasGramTheme.TextMuted)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(RasGramTheme.DarkPanel)
                    ) {
                        DropdownMenuItem(
                            text = { Text("View Contact", color = RasGramTheme.TextPrimary) },
                            leadingIcon = { Icon(Icons.Default.Person, null, tint = RasGramTheme.TextMuted) },
                            onClick = { onViewContact(); showMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Clear Chat", color = RasGramTheme.Red) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = RasGramTheme.Red) },
                            onClick = { onClearChat(); showMenu = false }
                        )
                    }
                }
            }

            HorizontalDivider(color = RasGramTheme.DividerColor, thickness = 0.5.dp)
        }
    }
}

@Composable
fun SelectionHeader(
    count: Int,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onForward: () -> Unit,
    onStar: () -> Unit,
    onCopy: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth(), color = RasGramTheme.DarkPanel) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(60.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, null, tint = RasGramTheme.TextPrimary)
            }
            Text("$count selected", style = MaterialTheme.typography.titleMedium, color = RasGramTheme.TextPrimary, modifier = Modifier.weight(1f))
            IconButton(onClick = onStar) { Icon(Icons.Default.Star, null, tint = RasGramTheme.Yellow) }
            IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, null, tint = RasGramTheme.TextMuted) }
            IconButton(onClick = onForward) { Icon(Icons.Default.Forward, null, tint = RasGramTheme.TextMuted) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, null, tint = RasGramTheme.Red) }
        }
    }
}

@Composable
fun ReplyPreview(message: Message, currentUserMobile: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = RasGramTheme.DarkPanel
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.width(3.dp).height(36.dp).background(RasGramTheme.Green, RoundedCornerShape(2.dp)))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (message.senderMobile == currentUserMobile) "You" else "Contact",
                    color = RasGramTheme.Green,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (message.isDeleted) "This message was deleted" else if (message.text.isNotEmpty()) message.text else getFileTypePreview(message),
                    color = RasGramTheme.TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Close, null, tint = RasGramTheme.TextMuted)
            }
        }
    }
}

@Composable
fun ChatInputBar(
    inputText: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttachClick: () -> Unit,
    isRecording: Boolean,
    recordingSeconds: Int,
    onMicPress: () -> Unit,
    onMicRelease: () -> Unit,
    onMicCancel: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth(), color = RasGramTheme.DarkBackground) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            if (isRecording) {
                // Recording UI
                Surface(
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(22.dp),
                    color = RasGramTheme.InputBg
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RecordingWaveAnimation()
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            formatTime(recordingSeconds),
                            color = RasGramTheme.Red,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("< Slide to cancel", color = RasGramTheme.TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                FloatingActionButton(
                    onClick = onMicRelease,
                    modifier = Modifier.size(44.dp),
                    containerColor = RasGramTheme.Green,
                    elevation = FloatingActionButtonDefaults.elevation(2.dp)
                ) {
                    Icon(Icons.Default.Send, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            } else {
                // Emoji + Attach
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(22.dp),
                    color = RasGramTheme.InputBg
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        // Emoji icon — ছোট করা
                        Box(
                            modifier = Modifier.size(44.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.EmojiEmotions, null,
                                tint = RasGramTheme.TextMuted,
                                modifier = Modifier.size(22.dp))
                        }
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = onTextChange,
                            // WhatsApp: single line by default, max 4 lines যাতে last message hide না হয়
                            modifier = Modifier.weight(1f).heightIn(min = 36.dp, max = 96.dp),
                            placeholder = { Text("Message", color = RasGramTheme.TextMuted, fontSize = 15.sp) },
                            maxLines = 4,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 15.sp,
                                color = RasGramTheme.TextPrimary
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedTextColor = RasGramTheme.TextPrimary,
                                unfocusedTextColor = RasGramTheme.TextPrimary,
                                cursorColor = RasGramTheme.Green,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { onSend() })
                        )
                        // Attach icon — full Box tappable (44dp hit area)
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clickable { onAttachClick() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.AttachFile, null,
                                tint = RasGramTheme.TextMuted,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                AnimatedContent(
                    targetState = inputText.isNotEmpty(),
                    transitionSpec = { (scaleIn() + fadeIn()).togetherWith(scaleOut() + fadeOut()) },
                    label = "SendMicButton"
                ) { hasText ->
                    FloatingActionButton(
                        onClick = if (hasText) onSend else onMicPress,
                        modifier = Modifier.size(44.dp),
                        containerColor = RasGramTheme.Green,
                        elevation = FloatingActionButtonDefaults.elevation(2.dp)
                    ) {
                        Icon(
                            if (hasText) Icons.Default.Send else Icons.Default.Mic,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RecordingWaveAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val bars = remember { (1..5).map { it } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(modifier = Modifier.size(8.dp).background(RasGramTheme.Red, CircleShape))
        Spacer(modifier = Modifier.width(4.dp))
        bars.forEachIndexed { i, _ ->
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(400, delayMillis = i * 80),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar$i"
            )
            Box(modifier = Modifier.width(3.dp).height((16 * scale).dp).background(RasGramTheme.Green, RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
fun AttachmentMenuSheet(
    onDismiss: () -> Unit,
    onImageVideo: () -> Unit,
    onDocument: () -> Unit,
    onAudio: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            color = RasGramTheme.DarkPanel
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Share", style = MaterialTheme.typography.titleMedium, color = RasGramTheme.TextPrimary, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    AttachOption(Icons.Default.Image, "Photos & Videos", RasGramTheme.Orange, onImageVideo)
                    AttachOption(Icons.Default.InsertDriveFile, "Document", Color(0xFF6C63FF), onDocument)
                    AttachOption(Icons.Default.AudioFile, "Audio", Color(0xFF00BFA5), onAudio)
                    AttachOption(Icons.Default.Camera, "Camera", RasGramTheme.Green, onDismiss)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    AttachOption(Icons.Default.LocationOn, "Location", RasGramTheme.Red, onDismiss)
                    AttachOption(Icons.Default.ContactPage, "Contact", Color(0xFF2196F3), onDismiss)
                    AttachOption(Icons.Default.Poll, "Poll", Color(0xFFFF9800), onDismiss)
                    AttachOption(Icons.Default.Gif, "GIF", Color(0xFF9C27B0), onDismiss)
                }
            }
        }
    }
}

// ── EnhancedAttachmentMenuSheet — Folder option সহ (LAN + Normal দুই mode এ কাজ করে) ──
@Composable
fun EnhancedAttachmentMenuSheet(
    onDismiss: () -> Unit,
    onImageVideo: () -> Unit,
    onDocument: () -> Unit,
    onAudio: () -> Unit,
    onFilesFromFolder: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
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
                // Row 1: Gallery, Document, Audio, Folder
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    AttachOption(Icons.Default.Image, "Photos & Videos", RasGramTheme.Orange, onImageVideo)
                    AttachOption(Icons.Default.InsertDriveFile, "Document", Color(0xFF6C63FF), onDocument)
                    AttachOption(Icons.Default.AudioFile, "Audio", Color(0xFF00BFA5), onAudio)
                    AttachOption(Icons.Default.Folder, "Folder", Color(0xFFFFB300), onFilesFromFolder)
                }
                Spacer(modifier = Modifier.height(8.dp))
                // Row 2: Camera, Location, Contact, GIF
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    AttachOption(Icons.Default.Camera, "Camera", RasGramTheme.Green, onDismiss)
                    AttachOption(Icons.Default.LocationOn, "Location", RasGramTheme.Red, onDismiss)
                    AttachOption(Icons.Default.ContactPage, "Contact", Color(0xFF2196F3), onDismiss)
                    AttachOption(Icons.Default.Gif, "GIF", Color(0xFF9C27B0), onDismiss)
                }
            }
        }
    }
}

@Composable
fun AttachOption(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(8.dp)
    ) {
        Surface(shape = CircleShape, color = color.copy(alpha = 0.15f), modifier = Modifier.size(56.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.padding(14.dp))
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(label, color = RasGramTheme.TextMuted, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}

// ==================== MESSAGE BUBBLE ====================
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    isMe: Boolean,
    isSelected: Boolean,
    senderName: String? = null,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onDelete: () -> Unit,
    onStar: () -> Unit,
    onCopy: () -> Unit
) {
    val context = LocalContext.current
    val bubbleColor = if (isMe) RasGramTheme.BubbleOut else RasGramTheme.BubbleIn
    val alignment = if (isMe) Alignment.End else Alignment.Start
    var showContextMenu by remember { mutableStateOf(false) }

    val selectionBg = if (isSelected) RasGramTheme.Green.copy(alpha = 0.15f) else Color.Transparent

    // ── Swipe-to-reply ───────────────────────────────────────────────────────
    var swipeOffset by remember { mutableFloatStateOf(0f) }
    val swipeThreshold = 90f
    val replyIconAlpha = (swipeOffset / swipeThreshold).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(selectionBg)
    ) {
        // Reply hint icon (revealed as you swipe)
        if (swipeOffset > 12f && !message.isCallLog) {
            Icon(
                imageVector = Icons.Default.Reply,
                contentDescription = "Reply",
                tint = RasGramTheme.Green.copy(alpha = replyIconAlpha),
                modifier = Modifier
                    .align(if (isMe) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 10.dp)
                    .size(26.dp)
            )
        }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(
                x = if (isMe) -swipeOffset.toInt() else swipeOffset.toInt(),
                y = 0
            ) }
            .pointerInput(message.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (swipeOffset >= swipeThreshold && !message.isCallLog) onReply()
                        swipeOffset = 0f
                    },
                    onDragCancel = { swipeOffset = 0f }
                ) { _, dragAmount ->
                    val delta = if (isMe) -dragAmount else dragAmount
                    if (delta > 0) swipeOffset = (swipeOffset + delta).coerceAtMost(swipeThreshold * 1.4f)
                    else swipeOffset = (swipeOffset + delta).coerceAtLeast(0f)
                }
            }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = alignment
    ) {
        if (message.isCallLog) {
            CallLogBubble(message = message)
            return@Column
        }

        if (message.isDeleted) {
            DeletedMessageBubble(isMe = isMe, timeString = message.timeString)
            return@Column
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (!isMe) { Spacer(modifier = Modifier.width(4.dp)) }

            Surface(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .combinedClickable(onClick = onClick, onLongClick = { showContextMenu = true; onLongClick() }),
                shape = RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomStart = if (isMe) 16.dp else 4.dp,
                    bottomEnd = if (isMe) 4.dp else 16.dp
                ),
                color = bubbleColor,
                shadowElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(0.dp)) {
                    // Sender Name (For Groups)
                    if (!isMe && senderName != null) {
                        Text(
                            senderName,
                            style = MaterialTheme.typography.labelMedium,
                            color = RasGramTheme.Yellow,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 0.dp)
                        )
                    }
                    
                    // Reply preview inside bubble
                    message.replyToId?.let {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 0.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Black.copy(alpha = 0.2f)
                        ) {
                            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.width(2.dp).height(28.dp).background(RasGramTheme.Green, RoundedCornerShape(1.dp)))
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(message.replyToSender ?: "Unknown", color = RasGramTheme.Green, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    Text(message.replyToText ?: "", color = RasGramTheme.TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }

                    // Forward badge
                    if (message.isForwarded) {
                        Row(modifier = Modifier.padding(start = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Forward, null, modifier = Modifier.size(12.dp), tint = RasGramTheme.TextMuted)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Forwarded", color = RasGramTheme.TextMuted, style = MaterialTheme.typography.labelSmall, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                        }
                    }

                    // File content
                    message.fileUrl?.let { url ->
                        when {
                            message.fileType?.startsWith("image/") == true -> ImageMessageContent(url = url, context = context)
                            message.fileType?.startsWith("video/") == true -> VideoMessageContent(url = url, fileName = message.fileName, fileType = message.fileType, context = context)
                            message.fileType?.startsWith("audio/") == true -> AudioMessageContent(url = url, fileName = message.fileName, duration = message.duration)
                            else -> DocumentMessageContent(url = url, fileName = message.fileName, fileType = message.fileType, fileSize = message.fileSizeBytes, context = context)
                        }
                    }

                    // Text
                    if (message.text.isNotEmpty()) {
                        Text(
                            message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = RasGramTheme.TextPrimary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = if (message.fileUrl != null) 4.dp else 8.dp)
                        )
                        // Link preview — URL থাকলে নিচে preview card
                        if (message.fileUrl == null) {
                            val urls = remember(message.text) { extractUrls(message.text) }
                            if (urls.isNotEmpty()) {
                                LinkPreviewCard(
                                    url      = urls.first(),
                                    context  = context,
                                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp)
                                )
                            }
                        }
                    }

                    // Time + ticks row
                    Row(
                        modifier = Modifier.align(Alignment.End).padding(end = 8.dp, bottom = 4.dp, top = if (message.text.isEmpty() && message.fileUrl != null) 4.dp else 0.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (message.isStarred) {
                            Icon(Icons.Default.Star, null, modifier = Modifier.size(10.dp), tint = RasGramTheme.StarColor)
                            Spacer(modifier = Modifier.width(2.dp))
                        }
                        Text(message.timeString, style = MaterialTheme.typography.labelSmall, color = RasGramTheme.TextMuted.copy(0.8f), fontSize = 10.sp)
                        if (isMe) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = when {
                                    message.isPending -> Icons.Default.Schedule
                                    message.read -> Icons.Default.DoneAll
                                    message.delivered -> Icons.Default.DoneAll
                                    else -> Icons.Default.Check
                                },
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = when {
                                    message.read -> RasGramTheme.BlueTick
                                    else -> RasGramTheme.TextMuted
                                }
                            )
                        }
                    }
                }
            }

            if (isMe) { Spacer(modifier = Modifier.width(4.dp)) }
        }

        // Reaction bubble
        message.reaction?.let { emoji ->
            Surface(
                modifier = Modifier.offset(y = (-6).dp).padding(horizontal = if (isMe) 8.dp else 8.dp),
                shape = RoundedCornerShape(12.dp),
                color = RasGramTheme.DarkPanel,
                border = BorderStroke(1.dp, RasGramTheme.Border)
            ) {
                Text(emoji, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 14.sp)
            }
        }
    }

    // Context menu
    if (showContextMenu) {
        MessageContextMenu(
            message = message,
            isMe = isMe,
            onDismiss = { showContextMenu = false },
            onReact = { emoji -> onReact(emoji); showContextMenu = false },
            onReply = { onReply(); showContextMenu = false },
            onDelete = { onDelete(); showContextMenu = false },
            onCopy = { onCopy(); showContextMenu = false },
            onStar = { onStar(); showContextMenu = false },
            onForward = { showContextMenu = false }
        )
    } // end Column (swipe content)
    } // end Box (swipe container)
}

@Composable
fun DeletedMessageBubble(isMe: Boolean, timeString: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isMe) RasGramTheme.BubbleOut.copy(alpha = 0.6f) else RasGramTheme.BubbleIn.copy(alpha = 0.6f)
        ) {
            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Block, null, modifier = Modifier.size(14.dp), tint = RasGramTheme.TextMuted)
                Spacer(modifier = Modifier.width(6.dp))
                Text("This message was deleted", style = MaterialTheme.typography.bodySmall, color = RasGramTheme.TextMuted, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                Spacer(modifier = Modifier.width(8.dp))
                Text(timeString, style = MaterialTheme.typography.labelSmall, color = RasGramTheme.TextMuted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun CallLogBubble(message: Message) {
    val isMe = message.callStatus != "missed"
    val isMissed = message.callStatus == "missed"
    val isVideo = message.callType == "video"
    val bubbleColor = if (isMe) RasGramTheme.BubbleOut else RasGramTheme.BubbleIn
    val iconTint = when {
        isMissed -> RasGramTheme.Red
        isMe -> RasGramTheme.Green
        else -> RasGramTheme.CallGreen
    }
    val statusText = when {
        isMissed -> "No answer"
        message.duration > 0 -> formatTime(message.duration)
        else -> message.text.ifEmpty { "Ended" }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (isMe) 18.dp else 6.dp,
                bottomEnd = if (isMe) 6.dp else 18.dp
            ),
            color = bubbleColor,
            shadowElevation = 2.dp,
            modifier = Modifier.widthIn(min = 180.dp, max = 260.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Compact call icon — small circle badge
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(iconTint.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            isVideo -> Icons.Default.Videocam
                            isMissed -> Icons.Default.PhoneMissed
                            isMe -> Icons.Default.CallMade
                            else -> Icons.Default.CallReceived
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = iconTint
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                // Call info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isVideo) "Video call" else "Voice call",
                        color = RasGramTheme.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Text(
                        statusText,
                        color = if (isMissed) RasGramTheme.Red else RasGramTheme.TextMuted,
                        fontSize = 11.sp
                    )
                }
                // Time + tick
                Column(horizontalAlignment = Alignment.End) {
                    Text(message.timeString, color = RasGramTheme.TextMuted, fontSize = 10.sp)
                    if (isMe) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Icon(
                            if (message.read) Icons.Default.DoneAll else Icons.Default.Check,
                            null,
                            modifier = Modifier.size(13.dp),
                            tint = if (message.read) RasGramTheme.BlueTick else RasGramTheme.TextMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ImageMessageContent(url: String, context: Context) {
    var showFullScreen by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // LAN mode: local:// URL → java.io.File → Coil কে File pass করো
    val imageModel: Any = remember(url) {
        if (url.startsWith("local://")) java.io.File(url.removePrefix("local://"))
        else url
    }

    AsyncImage(
        model = imageModel,
        contentDescription = "Image",
        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 220.dp)
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 4.dp))
            .clickable { showFullScreen = true },
        contentScale = ContentScale.Crop
    )
    if (showFullScreen) {
        Dialog(onDismissRequest = { showFullScreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black).clickable { showFullScreen = false }, contentAlignment = Alignment.Center) {
                AsyncImage(model = imageModel, contentDescription = null, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                IconButton(onClick = { showFullScreen = false }, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
                    Icon(Icons.Default.Close, null, tint = Color.White)
                }
                // LAN file: already local — no download needed
                if (!url.startsWith("local://")) {
                    IconButton(
                        onClick = {
                            scope.launch {
                                isSaving = true
                                val saved = downloadToRasgramFolder(context, url, null, "image/jpeg")
                                isSaving = false
                                if (saved != null) {
                                    Toast.makeText(context, "Rasgram ফোল্ডারে সেভ হয়েছে", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "সেভ করা যায়নি", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                    ) {
                        if (isSaving) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                        else Icon(Icons.Default.Download, null, tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun VideoMessageContent(url: String, fileName: String?, fileType: String?, context: Context) {
    val scope = rememberCoroutineScope()
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }

    // Check if already downloaded in Rasgram folder
    val localFile = remember(url) { getRasgramCachedFile(context, url, fileName, fileType ?: "video/mp4") }
    var isLocal by remember(localFile) { mutableStateOf(localFile?.exists() == true) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 4.dp)),
        color = Color.Black.copy(0.6f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isDownloading) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.size(52.dp),
                        color = RasGramTheme.Green,
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("${(downloadProgress * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            } else if (isLocal) {
                // Already downloaded — tap to play
                Icon(
                    Icons.Default.PlayCircleFilled,
                    null,
                    tint = RasGramTheme.Green,
                    modifier = Modifier.size(64.dp).clickable {
                        openLocalFileWithProvider(context, localFile!!, fileType ?: "video/mp4")
                    }
                )
            } else {
                // Not downloaded — Telegram-style download button
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(0.55f),
                    modifier = Modifier.size(64.dp).clickable {
                        scope.launch {
                            isDownloading = true
                            val saved = downloadToRasgramFolder(context, url, fileName ?: "video_${System.currentTimeMillis()}.mp4", fileType ?: "video/mp4") { prog ->
                                downloadProgress = prog
                            }
                            isDownloading = false
                            if (saved != null) {
                                isLocal = true
                                Toast.makeText(context, "Rasgram ফোল্ডারে ডাউনলোড হয়েছে", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "ডাউনলোড ব্যর্থ হয়েছে", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Download, null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                }
            }

            // File name badge (bottom left)
            Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(0.6f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Icon(Icons.Default.Videocam, null, tint = Color.White, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        fileName ?: "Video",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Downloaded badge (top right)
            if (isLocal) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = RasGramTheme.Green.copy(0.85f)
                ) {
                    Text("✓", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                }
            }
        }
    }
}

@Composable
fun AudioMessageContent(url: String, fileName: String?, duration: Int) {
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val mediaPlayer = remember { MediaPlayer() }
    var durationMs by remember { mutableIntStateOf(if (duration > 0) duration * 1000 else 0) }
    val scope = rememberCoroutineScope()

    DisposableEffect(url) {
        onDispose {
            try { if (mediaPlayer.isPlaying) mediaPlayer.stop(); mediaPlayer.release() } catch (_: Exception) {}
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FloatingActionButton(
            onClick = {
                if (!isPlaying) {
                    try {
                        mediaPlayer.reset()
                        // LAN mode: local:// → File path দিয়ে setDataSource
                        if (url.startsWith("local://")) {
                            mediaPlayer.setDataSource(url.removePrefix("local://"))
                        } else {
                            mediaPlayer.setDataSource(url)
                        }
                        mediaPlayer.prepareAsync()
                        mediaPlayer.setOnPreparedListener { mp ->
                            mp.start()
                            durationMs = mp.duration
                            isPlaying = true
                            scope.launch {
                                while (mp.isPlaying) {
                                    progress = mp.currentPosition.toFloat() / mp.duration.toFloat()
                                    delay(100)
                                }
                                progress = 0f
                                isPlaying = false
                            }
                        }
                        mediaPlayer.setOnCompletionListener { isPlaying = false; progress = 0f }
                    } catch (_: Exception) {}
                } else {
                    mediaPlayer.pause(); isPlaying = false
                }
            },
            modifier = Modifier.size(40.dp),
            containerColor = RasGramTheme.Green,
            elevation = FloatingActionButtonDefaults.elevation(0.dp)
        ) {
            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }

        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = RasGramTheme.Green,
                trackColor = RasGramTheme.TextMuted.copy(0.3f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fileName ?: "Voice message", color = RasGramTheme.TextMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (durationMs > 0) Text(formatTime(durationMs / 1000), color = RasGramTheme.TextMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ── Folder message marker (FileManager থেকে পাঠানো folder) ────────────────────
private const val RASGRAM_FOLDER_PREFIX = "__RASGRAM_FOLDER__"

@Composable
fun DocumentMessageContent(url: String, fileName: String?, fileType: String?, fileSize: Long, context: Context) {
    val scope = rememberCoroutineScope()

    // ── Folder detection: FileManager zip করে এই prefix দিয়ে পাঠায় ────────
    val isRasgramFolder = fileName?.startsWith(RASGRAM_FOLDER_PREFIX) == true
    // Display name: prefix ও .zip extension বাদ দিয়ে আসল folder নাম
    val displayName: String = if (isRasgramFolder) {
        fileName!!
            .removePrefix(RASGRAM_FOLDER_PREFIX)
            .removeSuffix(".zip")
    } else {
        fileName ?: "Document"
    }

    val isPdf   = !isRasgramFolder && fileType?.contains("pdf") == true
    val isWord  = !isRasgramFolder && (fileType?.contains("word") == true || fileType?.contains("document") == true)
    val isExcel = !isRasgramFolder && (fileType?.contains("sheet") == true || fileType?.contains("excel") == true)
    val isPpt   = !isRasgramFolder && (fileType?.contains("presentation") == true || fileType?.contains("powerpoint") == true)
    val isZip   = !isRasgramFolder && (fileType?.contains("zip") == true || fileType?.contains("archive") == true)

    val iconBgColor = when {
        isRasgramFolder -> Color(0xFFEF8C00)   // amber-orange — folder রঙ
        isPdf   -> Color(0xFFE53935)
        isWord  -> Color(0xFF1565C0)
        isExcel -> Color(0xFF2E7D32)
        isPpt   -> Color(0xFFE65100)
        isZip   -> Color(0xFF6A1B9A)
        else    -> Color(0xFF37474F)
    }
    val iconLabel = when {
        isRasgramFolder -> "FOLDER"
        isPdf   -> "PDF"
        isWord  -> "DOC"
        isExcel -> "XLS"
        isPpt   -> "PPT"
        isZip   -> "ZIP"
        else    -> fileName?.substringAfterLast(".")?.uppercase()?.take(3) ?: "FILE"
    }
    val icon = when {
        isRasgramFolder -> Icons.Default.Folder
        isPdf   -> Icons.Default.PictureAsPdf
        isWord  -> Icons.Default.Description
        isExcel -> Icons.Default.TableChart
        isPpt   -> Icons.Default.Slideshow
        isZip   -> Icons.Default.FolderZip
        else    -> Icons.Default.InsertDriveFile
    }

    // Check if already downloaded
    val localFile = remember(url) { getRasgramCachedFile(context, url, fileName, fileType ?: "application/octet-stream") }
    // Folder: extracted folder path = Downloads/<folderName>
    val extractedFolder = remember(isRasgramFolder, displayName) {
        if (isRasgramFolder) {
            java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ),
                displayName
            )
        } else null
    }
    var isLocal by remember(localFile, extractedFolder) {
        mutableStateOf(
            if (isRasgramFolder) extractedFolder?.exists() == true
            else localFile?.exists() == true
        )
    }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (isRasgramFolder) {
                    if (isLocal && extractedFolder != null) {
                        // Folder already extracted — open it in file manager
                        try {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(
                                    androidx.core.content.FileProvider.getUriForFile(
                                        context, "${context.packageName}.fileprovider", extractedFolder
                                    ),
                                    "resource/folder"
                                )
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            if (intent.resolveActivity(context.packageManager) != null) {
                                context.startActivity(intent)
                            } else {
                                Toast.makeText(context, "📁 '$displayName' ফোল্ডার Downloads এ আছে", Toast.LENGTH_LONG).show()
                            }
                        } catch (_: Exception) {
                            Toast.makeText(context, "📁 '$displayName' ফোল্ডার Downloads এ আছে", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        // Download zip → unzip → delete zip (user জানে না zip হচ্ছে)
                        scope.launch {
                            isDownloading = true
                            val saved = downloadToRasgramFolder(
                                context, url, fileName ?: "folder.zip",
                                fileType ?: "application/zip"
                            ) { prog -> downloadProgress = prog }
                            if (saved != null) {
                                // Background এ unzip করো
                                val destDir = java.io.File(
                                    android.os.Environment.getExternalStoragePublicDirectory(
                                        android.os.Environment.DIRECTORY_DOWNLOADS
                                    ),
                                    displayName
                                )
                                val unzipOk = try {
                                    withContext(Dispatchers.IO) {
                                        destDir.mkdirs()
                                        var ok = true
                                        java.util.zip.ZipInputStream(java.io.BufferedInputStream(java.io.FileInputStream(saved))).use { zis ->
                                            var entry = zis.nextEntry
                                            while (entry != null) {
                                                // Zip Slip protection
                                                val outFile = java.io.File(destDir, entry.name)
                                                if (!outFile.canonicalPath.startsWith(destDir.canonicalPath + java.io.File.separator) &&
                                                    outFile.canonicalPath != destDir.canonicalPath) {
                                                    ok = false; break
                                                }
                                                if (entry.isDirectory) {
                                                    outFile.mkdirs()
                                                } else {
                                                    outFile.parentFile?.mkdirs()
                                                    java.io.FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                                                }
                                                entry = zis.nextEntry
                                            }
                                        }
                                        // Zip file মুছে দাও — user দেখবে না
                                        saved.delete()
                                        ok
                                    }
                                } catch (_: Exception) { false }

                                isDownloading = false
                                if (unzipOk) {
                                    isLocal = true
                                    Toast.makeText(context, "📁 '$displayName' ফোল্ডার Downloads এ সেভ হয়েছে", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "ফোল্ডার সেভ করা যায়নি", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                isDownloading = false
                                Toast.makeText(context, "ডাউনলোড ব্যর্থ হয়েছে", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    // Normal file (non-folder)
                    if (isLocal && localFile != null) {
                        openLocalFileWithProvider(context, localFile, fileType ?: "application/octet-stream")
                    } else {
                        scope.launch {
                            isDownloading = true
                            val saved = downloadToRasgramFolder(context, url, fileName ?: "document_${System.currentTimeMillis()}", fileType ?: "application/octet-stream") { prog ->
                                downloadProgress = prog
                            }
                            isDownloading = false
                            if (saved != null) {
                                isLocal = true
                                Toast.makeText(context, "Rasgram ফোল্ডারে ডাউনলোড হয়েছে", Toast.LENGTH_SHORT).show()
                                openLocalFileWithProvider(context, saved, fileType ?: "application/octet-stream")
                            } else {
                                Toast.makeText(context, "ডাউনলোড ব্যর্থ হয়েছে", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
    ) {
        // Colored header block
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .background(iconBgColor.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(iconLabel, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, letterSpacing = 1.sp)
            }

            // Download progress overlay
            if (isDownloading) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.size(36.dp),
                            color = Color.White,
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (isRasgramFolder) "ডাউনলোড হচ্ছে…" else "${(downloadProgress * 100).toInt()}%",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        // File / folder info row
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    displayName,   // folder নাম দেখায় — zip prefix/ext ছাড়া
                    style = MaterialTheme.typography.bodyMedium,
                    color = RasGramTheme.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    buildString {
                        if (fileSize > 0) append("${formatFileSize(fileSize)} • ")
                        append(if (isRasgramFolder) "Folder" else iconLabel)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = RasGramTheme.TextMuted
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (isLocal) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Saved", tint = RasGramTheme.Green, modifier = Modifier.size(22.dp))
            } else {
                Icon(Icons.Default.Download, contentDescription = "Download", tint = RasGramTheme.TextMuted, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
fun MessageContextMenu(
    message: Message,
    isMe: Boolean,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onStar: () -> Unit,
    onForward: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Emoji reactions
            Surface(shape = RoundedCornerShape(24.dp), color = RasGramTheme.DarkPanel, modifier = Modifier.padding(bottom = 8.dp)) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("👍", "❤️", "😂", "😮", "😢", "🙏").forEach { emoji ->
                        Text(
                            emoji,
                            modifier = Modifier.clickable { onReact(emoji) }.padding(4.dp),
                            fontSize = 26.sp
                        )
                    }
                }
            }

            // Action menu
            Surface(shape = RoundedCornerShape(16.dp), color = RasGramTheme.DarkPanel, modifier = Modifier.fillMaxWidth()) {
                Column {
                    val actions = buildList {
                        add(Triple(Icons.Default.Reply, "Reply", onReply))
                        if (message.text.isNotEmpty()) add(Triple(Icons.Default.ContentCopy, "Copy Text", onCopy))
                        add(Triple(Icons.Default.Forward, "Forward", onForward))
                        add(Triple(Icons.Default.Star, if (message.isStarred) "Unstar" else "Star", onStar))
                        if (isMe) add(Triple(Icons.Default.Delete, "Delete", onDelete))
                    }
                    actions.forEachIndexed { i, (icon, label, action) ->
                        val isLast = i == actions.size - 1
                        ListItem(
                            headlineContent = { Text(label, color = if (label == "Delete") RasGramTheme.Red else RasGramTheme.TextPrimary) },
                            leadingContent = { Icon(icon, null, tint = if (label == "Delete") RasGramTheme.Red else RasGramTheme.TextMuted) },
                            modifier = Modifier.clickable { action() },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        if (!isLast) HorizontalDivider(color = RasGramTheme.DividerColor, thickness = 0.5.dp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Surface(shape = RoundedCornerShape(16.dp), color = RasGramTheme.DarkPanel, modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("Cancel", color = RasGramTheme.TextPrimary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                    modifier = Modifier.clickable { onDismiss() },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
    }
}

// ==================== STATUS TAB ====================
@Composable
fun StatusTab(currentUser: User, onStatusClick: (List<Status>) -> Unit, modifier: Modifier = Modifier) {
    val db = remember { FirebaseFirestore.getInstance() }
    var statuses by remember { mutableStateOf<List<Status>>(emptyList()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isUploading by remember { mutableStateOf(false) }

    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            isUploading = true
            scope.launch {
                val (url, _, fileType) = uploadToCloudinary(context, it) { }
                if (url != null) {
                    val status = hashMapOf(
                        "userMobile" to currentUser.mobile,
                        "userName" to currentUser.name,
                        "userAvatar" to currentUser.avatarUrl,
                        "mediaUrl" to url,
                        "mediaType" to if (fileType?.startsWith("video") == true) "video" else "image",
                        "caption" to "",
                        "timestamp" to System.currentTimeMillis(),
                        "viewedBy" to listOf<String>(),
                        "expiresAt" to (System.currentTimeMillis() + 86400_000)
                    )
                    db.collection("statuses").add(status).await()
                    Toast.makeText(context, "Status posted!", Toast.LENGTH_SHORT).show()
                }
                isUploading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        db.collection("statuses")
            .whereGreaterThan("expiresAt", System.currentTimeMillis())
            .orderBy("expiresAt", Query.Direction.DESCENDING)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, _ ->
                snap?.documents?.mapNotNull { doc ->
                    doc.data?.let { d ->
                        Status(
                            id = doc.id,
                            userMobile = d["userMobile"] as? String ?: "",
                            userName = d["userName"] as? String ?: "",
                            userAvatar = d["userAvatar"] as? String ?: "",
                            mediaUrl = d["mediaUrl"] as? String ?: "",
                            mediaType = d["mediaType"] as? String ?: "image",
                            caption = d["caption"] as? String ?: "",
                            timestamp = d["timestamp"] as? Long ?: 0,
                            viewedBy = (d["viewedBy"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                            expiresAt = d["expiresAt"] as? Long ?: 0
                        )
                    }
                }?.also { statuses = it }
            }
    }

    val myStatuses = statuses.filter { it.userMobile == currentUser.mobile }
    // অন্যদের status — mobile অনুযায়ী group করা
    val othersStatuses = statuses
        .filter { it.userMobile != currentUser.mobile }
        .groupBy { it.userMobile }
        .values.toList()

    Column(modifier = modifier.fillMaxSize().background(RasGramTheme.DarkBackground)) {

        // ── Header ──────────────────────────────────────────────────────────
        Surface(modifier = Modifier.fillMaxWidth(), color = RasGramTheme.DarkPanel) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(56.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Status",
                    style = MaterialTheme.typography.titleLarge,
                    color = RasGramTheme.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { imageLauncher.launch(arrayOf("image/*", "video/*")) }) {
                    Icon(Icons.Default.Edit, null, tint = RasGramTheme.TextMuted)
                }
            }
        }

        // ── Story cards — horizontal LazyRow ────────────────────────────────
        val allStoryGroups: List<Pair<Boolean, List<Status>>> = buildList {
            // নিজের story সবার আগে
            add(Pair(true, myStatuses))
            // বাকিরা
            othersStatuses.forEach { add(Pair(false, it)) }
        }

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(allStoryGroups) { (isMe, group) ->
                val first = group.firstOrNull()
                val viewed = !isMe && group.all { currentUser.mobile in it.viewedBy }
                val name = if (isMe) currentUser.name else (first?.userName ?: "")
                val avatarUrl = if (isMe) currentUser.avatarUrl else (first?.userAvatar ?: "")
                val thumbnailUrl = first?.mediaUrl ?: ""

                StatusStoryCard(
                    name = name,
                    avatarUrl = avatarUrl,
                    thumbnailUrl = thumbnailUrl,
                    isMe = isMe,
                    viewed = viewed,
                    isUploading = isMe && isUploading,
                    hasStatus = group.isNotEmpty(),
                    onClick = {
                        if (isMe) {
                            if (myStatuses.isNotEmpty()) onStatusClick(myStatuses)
                            else imageLauncher.launch(arrayOf("image/*", "video/*"))
                        } else {
                            onStatusClick(group)
                        }
                    }
                )
            }
        }

        HorizontalDivider(color = RasGramTheme.DividerColor, thickness = 0.5.dp)

        // ── Recent Updates list (নিচে text list) ────────────────────────────
        if (othersStatuses.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.RadioButtonChecked,
                    null,
                    modifier = Modifier.size(72.dp),
                    tint = RasGramTheme.TextMuted.copy(0.2f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("No recent updates", color = RasGramTheme.TextMuted, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Status updates from your contacts will appear here.",
                    color = RasGramTheme.TextMuted.copy(0.6f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        } else {
            Text(
                "Recent updates",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                color = RasGramTheme.TextMuted,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(othersStatuses) { userStatuses ->
                    val first = userStatuses.first()
                    val viewed = userStatuses.all { currentUser.mobile in it.viewedBy }
                    StatusListItem(
                        avatarUrl = first.userAvatar,
                        name = first.userName,
                        time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(first.timestamp)),
                        viewed = viewed,
                        onClick = { onStatusClick(userStatuses) }
                    )
                    HorizontalDivider(
                        color = RasGramTheme.DividerColor,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(start = 76.dp)
                    )
                }
            }
        }
    }
}

// ── Story card (WhatsApp style thumbnail card) ───────────────────────────────
@Composable
fun StatusStoryCard(
    name: String,
    avatarUrl: String,
    thumbnailUrl: String,
    isMe: Boolean,
    viewed: Boolean,
    isUploading: Boolean,
    hasStatus: Boolean,
    onClick: () -> Unit
) {
    val cardWidth = 100.dp
    val cardHeight = 156.dp
    val ringColor = when {
        isMe -> RasGramTheme.Green
        !viewed && hasStatus -> RasGramTheme.Green
        else -> RasGramTheme.TextMuted.copy(0.35f)
    }

    Box(
        modifier = Modifier
            .width(cardWidth)
            .height(cardHeight)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        // Thumbnail background
        if (thumbnailUrl.isNotEmpty()) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(RasGramTheme.DarkPanel)
            )
        }

        // gradient overlay নিচে
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.72f)
                        )
                    )
                )
        )

        // Avatar — উপরে বাঁয়ে ring সহ
        Box(
            modifier = Modifier
                .padding(8.dp)
                .align(Alignment.TopStart)
        ) {
            // ring
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .border(2.5.dp, ringColor, CircleShape)
                    .padding(3.dp)
            ) {
                AsyncImage(
                    model = avatarUrl.ifEmpty {
                        "https://ui-avatars.com/api/?name=${name.replace(" ", "+")}&background=008069&color=fff&size=80"
                    },
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            }
            // নিজেরটায় + বাটন
            if (isMe) {
                Surface(
                    modifier = Modifier
                        .size(16.dp)
                        .align(Alignment.BottomEnd),
                    shape = CircleShape,
                    color = RasGramTheme.Green,
                    border = BorderStroke(1.5.dp, RasGramTheme.DarkBackground)
                ) {
                    Icon(
                        Icons.Default.Add,
                        null,
                        tint = Color.White,
                        modifier = Modifier.padding(2.dp)
                    )
                }
            }
        }

        // uploading indicator
        if (isUploading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp).align(Alignment.Center),
                color = RasGramTheme.Green,
                strokeWidth = 2.dp
            )
        }

        // নাম — নিচে
        Text(
            text = if (isMe) "My Status" else name.split(" ").first(),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
        )
    }
}

// ── Recent updates list item (নিচের সাধারণ list) ────────────────────────────
@Composable
fun StatusListItem(
    avatarUrl: String,
    name: String,
    time: String,
    viewed: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .border(
                    2.dp,
                    if (viewed) RasGramTheme.TextMuted.copy(0.3f) else RasGramTheme.Green,
                    CircleShape
                )
                .padding(3.dp)
        ) {
            AsyncImage(
                model = avatarUrl.ifEmpty {
                    "https://ui-avatars.com/api/?name=${name.replace(" ", "+")}&background=008069&color=fff"
                },
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                color = RasGramTheme.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                time,
                style = MaterialTheme.typography.bodySmall,
                color = RasGramTheme.TextMuted
            )
        }
    }
}

// ==================== CALLS TAB ====================
@Composable
fun CallsTab(currentUser: User, modifier: Modifier = Modifier) {
    val db = remember { FirebaseFirestore.getInstance() }
    var callLogs by remember { mutableStateOf<List<Message>>(emptyList()) }

    LaunchedEffect(Unit) {
        // Get all contacts to find chat collections with call logs
        db.collection("chat_users").get().addOnSuccessListener { snap ->
            val chatIds = snap.documents
                .map { it.id }
                .filter { it != currentUser.mobile }
                .map { mobile ->
                    if (currentUser.mobile < mobile) "${currentUser.mobile}_${mobile}"
                    else "${mobile}_${currentUser.mobile}"
                }.toSet()

            val allLogs = mutableListOf<Message>()
            chatIds.forEach { chatId ->
                db.collection("pvt_msg_$chatId")
                    .whereEqualTo("isCallLog", true)
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .limit(20)
                    .addSnapshotListener { snap2, _ ->
                        val logs = snap2?.documents?.mapNotNull { d ->
                            d.data?.let { data ->
                                Message(
                                    id = d.id,
                                    text = data["text"] as? String ?: "",
                                    senderMobile = data["senderMobile"] as? String ?: "",
                                    receiverMobile = data["receiverMobile"] as? String ?: "",
                                    timestamp = data["timestamp"] as? Long ?: 0L,
                                    timeString = data["timeString"] as? String ?: "",
                                    isCallLog = true,
                                    callStatus = data["callStatus"] as? String ?: "ended",
                                    callType = data["callType"] as? String ?: "audio",
                                    duration = (data["duration"] as? Long)?.toInt() ?: 0
                                )
                            }
                        } ?: emptyList()
                        allLogs.removeAll { m ->
                            logs.any { it.senderMobile == m.senderMobile && it.receiverMobile == m.receiverMobile }
                        }
                        allLogs.addAll(logs)
                        callLogs = allLogs.sortedByDescending { it.timestamp }
                    }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().background(RasGramTheme.DarkBackground)) {
        // Header
        Surface(modifier = Modifier.fillMaxWidth(), color = RasGramTheme.DarkPanel, shadowElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(60.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Calls",
                    style = MaterialTheme.typography.titleLarge,
                    color = RasGramTheme.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { }) {
                    Icon(Icons.Default.PhoneForwarded, null, tint = RasGramTheme.Green)
                }
            }
        }

        if (callLogs.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier.size(90.dp).clip(CircleShape)
                        .background(RasGramTheme.Green.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Call, null,
                        modifier = Modifier.size(44.dp),
                        tint = RasGramTheme.Green.copy(0.5f))
                }
                Spacer(Modifier.height(20.dp))
                Text("No Recent Calls",
                    style = MaterialTheme.typography.titleMedium,
                    color = RasGramTheme.TextPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("Call history will appear here",
                    color = RasGramTheme.TextMuted,
                    style = MaterialTheme.typography.bodySmall)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(callLogs, key = { it.id }) { log ->
                    CallLogItem(log = log, currentUserMobile = currentUser.mobile)
                    HorizontalDivider(
                        color = RasGramTheme.DividerColor,
                        modifier = Modifier.padding(start = 80.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun CallLogItem(log: Message, currentUserMobile: String) {
    val isOutgoing = log.senderMobile == currentUserMobile
    val isMissed   = log.callStatus == "missed" || log.callStatus == "declined"
    val isVideo    = log.callType == "video"
    val otherMobile = if (isOutgoing) log.receiverMobile else log.senderMobile

    val arrowIcon = when {
        isMissed && !isOutgoing -> Icons.Default.CallMissed
        isOutgoing              -> Icons.Default.CallMade
        else                    -> Icons.Default.CallReceived
    }
    val arrowTint = if (isMissed && !isOutgoing) RasGramTheme.Red else RasGramTheme.Green

    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar circle with colorful initials
        val avatarBg = remember(otherMobile) { avatarColorFor(otherMobile) }
        Box(
            modifier = Modifier.size(52.dp).clip(CircleShape).background(avatarBg),
            contentAlignment = Alignment.Center
        ) {
            Text(
                nameInitials("", otherMobile).uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                otherMobile,
                color = if (isMissed && !isOutgoing) RasGramTheme.Red else RasGramTheme.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(arrowIcon, null, tint = arrowTint, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    buildString {
                        append(if (isVideo) "Video" else "Voice")
                        when {
                            log.duration > 0   -> append(" · ${formatTime(log.duration)}")
                            isMissed           -> append(" · Missed")
                        }
                    },
                    color = RasGramTheme.TextMuted,
                    fontSize = 12.sp
                )
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(log.timeString, color = RasGramTheme.TextMuted, fontSize = 11.sp)
            Spacer(Modifier.height(5.dp))
            Icon(
                if (isVideo) Icons.Default.Videocam else Icons.Default.Call,
                null,
                tint = RasGramTheme.Green.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}


// ==================== GROUPS TAB ====================
@Composable
fun GroupsTab(currentUser: User, onGroupSelect: (Group) -> Unit, modifier: Modifier = Modifier) {
    val db = remember { FirebaseFirestore.getInstance() }
    var groups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var showCreateGroup by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        db.collection("groups")
            .whereArrayContains("members", currentUser.mobile)
            .addSnapshotListener { snap, _ ->
                snap?.documents?.mapNotNull { doc ->
                    doc.data?.let { d ->
                        Group(
                            id = doc.id,
                            name = d["name"] as? String ?: "",
                            avatarUrl = d["avatarUrl"] as? String ?: "",
                            description = d["description"] as? String ?: "",
                            members = (d["members"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                            admins = (d["admins"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                            createdBy = d["createdBy"] as? String ?: "",
                            createdAt = d["createdAt"] as? Long ?: 0
                        )
                    }
                }?.also { groups = it }
            }
    }

    Column(modifier = modifier.fillMaxSize().background(RasGramTheme.DarkBackground)) {
        Surface(modifier = Modifier.fillMaxWidth(), color = RasGramTheme.DarkPanel) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Groups", style = MaterialTheme.typography.titleLarge, color = RasGramTheme.TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { showCreateGroup = true }) {
                    Icon(Icons.Default.GroupAdd, null, tint = RasGramTheme.TextMuted)
                }
            }
        }

        if (groups.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.Groups, null, modifier = Modifier.size(80.dp), tint = RasGramTheme.TextMuted.copy(0.3f))
                Spacer(modifier = Modifier.height(16.dp))
                Text("No Groups Yet", style = MaterialTheme.typography.titleMedium, color = RasGramTheme.TextMuted)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { showCreateGroup = true }, colors = ButtonDefaults.buttonColors(containerColor = RasGramTheme.Green)) {
                    Icon(Icons.Default.GroupAdd, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Create Group", color = Color.Black)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(groups, key = { it.id }) { group ->
                    GroupItem(group = group, onClick = { onGroupSelect(group) })
                    HorizontalDivider(color = RasGramTheme.DividerColor, modifier = Modifier.padding(start = 80.dp))
                }
            }
        }
    }

    if (showCreateGroup) {
        NewGroupDialog(onDismiss = { showCreateGroup = false }, currentUser = currentUser)
    }
}

@Composable
fun GroupItem(group: Group, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Group avatar with group icon overlay
        Box(modifier = Modifier.size(54.dp)) {
            AsyncImage(
                model = group.avatarUrl.ifEmpty {
                    "https://ui-avatars.com/api/?name=${group.name.replace(" ", "+")}&background=005C4B&color=fff&bold=true&size=128"
                },
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = ContentScale.Crop
            )
            // Members count badge
            if (group.members.size > 1) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd),
                    shape = CircleShape,
                    color = RasGramTheme.DarkPanel
                ) {
                    Text(
                        "${group.members.size}",
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        color = RasGramTheme.TextMuted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                group.name,
                style = MaterialTheme.typography.bodyLarge,
                color = RasGramTheme.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.People, null,
                    tint = RasGramTheme.TextMuted,
                    modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(3.dp))
                Text(
                    "${group.members.size} members",
                    style = MaterialTheme.typography.bodySmall,
                    color = RasGramTheme.TextMuted
                )
            }
        }

        // Created time
        val timeAgo = remember(group.createdAt) {
            val diff = System.currentTimeMillis() - group.createdAt
            when {
                diff < 3_600_000 -> "${diff / 60000}m"
                diff < 86_400_000 -> "${diff / 3_600_000}h"
                else -> "${diff / 86_400_000}d"
            }
        }
        Text(timeAgo, color = RasGramTheme.TextMuted, fontSize = 11.sp)
    }
}

// ==================== WEBRTC CALLING SCREEN ====================
@Composable
fun CallingScreen(
    currentUser: User,
    contact: User,
    callType: String,
    onEndCall: () -> Unit,
    isReceiver: Boolean = false,
    existingCallId: String = ""
) {
    val context = LocalContext.current
    val db = remember { FirebaseFirestore.getInstance() }
    val scope = rememberCoroutineScope()

    var callStatus by remember { mutableStateOf("Calling...") }
    var isMuted by remember { mutableStateOf(false) }
    var isCameraOff by remember { mutableStateOf(false) }
    var isSpeakerOn by remember { mutableStateOf(callType == "video") }
    var isConnected by remember { mutableStateOf(false) }
    var callSeconds by remember { mutableIntStateOf(0) }
    var callId by remember { mutableStateOf(existingCallId) }
    // Call ended duration summary
    var showEndedSummary by remember { mutableStateOf(false) }
    var finalCallSeconds by remember { mutableIntStateOf(0) }
    // ICE DISCONNECTED grace period job — cancel if CONNECTED comes back within 8s
    var iceDisconnectJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // ── Screen Share state ────────────────────────────────────────────────────
    val isSharingScreen   by ScreenShareManager.isSharingScreen.collectAsState()
    val isRemoteSharing   by ScreenShareManager.isRemoteSharing.collectAsState()
    val remoteInputGranted by ScreenShareManager.remoteInputGranted.collectAsState()
    val incomingInputRequest by ScreenShareManager.incomingInputRequest.collectAsState()
    var showInputRequestDialog by remember { mutableStateOf(false) }

    // MediaProjection permission launcher
    val mediaProjectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
    }
    val screenShareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            ScreenShareManager.startScreenShare(context, result.data!!)
        }
    }

    // Incoming input request from peer
    LaunchedEffect(incomingInputRequest) {
        if (incomingInputRequest) showInputRequestDialog = true
    }

    // FIX: EglBase.create() and PeerConnectionFactory.initialize() are heavy
    // native calls. Running them inside remember {} executes on the Compose
    // main thread → blocks the UI → ANR / "app keeps stopping" on cold start
    // (app was closed). Moved to a LaunchedEffect on Dispatchers.IO so the
    // main thread is never blocked.
    val eglBase = remember { mutableStateOf<EglBase?>(null) }
    val peerConnectionFactory = remember { mutableStateOf<PeerConnectionFactory?>(null) }

    // WebRTC session state — kept outside LaunchedEffect so DisposableEffect and UI can access them
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    }
    var peerConnection by remember { mutableStateOf<PeerConnection?>(null) }
    var localStream by remember { mutableStateOf<MediaStream?>(null) }
    var localVideoTrack by remember { mutableStateOf<VideoTrack?>(null) }
    var remoteVideoTrack by remember { mutableStateOf<VideoTrack?>(null) }
    var remoteSurfaceView by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var localSurfaceView by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    // Store capturer so we can stop/flip camera properly
    var videoCapturer by remember { mutableStateOf<VideoCapturer?>(null) }
    val iceServers = remember {
        // ── ICE Server Strategy ─────────────────────────────────────────────────
        // Mobile data (GP/Robi/Banglalink) সবই Carrier-Grade NAT (CGNAT) ব্যবহার করে।
        // CGNAT এ STUN কাজ করে না — direct peer connection সম্ভব না।
        // TURN relay সার্ভার দরকার যা দুটো peer এর মাঝে traffic route করে।
        //
        // FIX (আগে): openrelay.metered.ca credentials "openrelayproject/openrelayproject"
        //   সবাই use করে → server প্রায়ই overloaded বা blocked → Android 15 এ TURN fail।
        // Fix: Multiple free public TURN servers দেওয়া হয়েছে।
        //   WebRTC নিজেই সবগুলো try করে — যেটা কাজ করে সেটা দিয়ে connect হয়।
        //
        // Tier 1: STUN — same-network/same-carrier এ যথেষ্ট (no relay)
        // Tier 2: TURN — different network/CGNAT এ relay দরকার
        listOf(
            // ── STUN servers ──────────────────────────────────────────────────
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),

            // ── TURN #1: Metered.ca free tier (most reliable free TURN) ──────
            // https://www.metered.ca/tools/openrelay/ — free, ~500MB/month
            // UDP 80 — carrier এ সবচেয়ে কম block হয়
            PeerConnection.IceServer.builder("turn:a.relay.metered.ca:80")
                .setUsername("e85e4a7c6c26f4adcdd6a191")
                .setPassword("qJRdVXUgjmVFkRuV")
                .createIceServer(),
            // UDP 443 — UDP 80 block হলে
            PeerConnection.IceServer.builder("turn:a.relay.metered.ca:443")
                .setUsername("e85e4a7c6c26f4adcdd6a191")
                .setPassword("qJRdVXUgjmVFkRuV")
                .createIceServer(),
            // TCP 443 — UDP সম্পূর্ণ blocked হলে (strict carrier firewall)
            PeerConnection.IceServer.builder("turn:a.relay.metered.ca:443?transport=tcp")
                .setUsername("e85e4a7c6c26f4adcdd6a191")
                .setPassword("qJRdVXUgjmVFkRuV")
                .createIceServer(),
            // TLS 443 — সবচেয়ে strict firewall ও পার করে (HTTPS এর মতো encrypted)
            PeerConnection.IceServer.builder("turns:a.relay.metered.ca:443?transport=tcp")
                .setUsername("e85e4a7c6c26f4adcdd6a191")
                .setPassword("qJRdVXUgjmVFkRuV")
                .createIceServer(),

            // ── TURN #2: Xirsys free tier fallback ───────────────────────────
            // Different infrastructure — metered fail করলে এটা try হবে
            PeerConnection.IceServer.builder("turn:global.xirsys.net:80?transport=udp")
                .setUsername("rasfocus").setPassword("rasfocus-turn-2025")
                .createIceServer(),

            // ── TURN #3: OpenRelay fallback (last resort) ─────────────────────
            // Overloaded হতে পারে কিন্তু last fallback হিসেবে রাখা হলো
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
                .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer()
        )
    }

    // ── Step 1: IO thread — init WebRTC native libs ──────────────────────────
    // Split into a separate LaunchedEffect to avoid JVM bytecode method size limit.
    // When done, peerConnectionFactory.value becomes non-null, triggering Step 2+3.
    LaunchedEffect(Unit) {
        try {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val b = EglBase.create()
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions
                        .builder(context)
                        .createInitializationOptions()
                )
                val f = PeerConnectionFactory.builder()
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(b.eglBaseContext))
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(b.eglBaseContext, true, true))
                    .createPeerConnectionFactory()
                Pair(b, f)
            }
            eglBase.value             = result.first
            peerConnectionFactory.value = result.second
        } catch (e: Exception) {
            android.util.Log.e("RasGram", "WebRTC init failed: ${e.message}", e)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                Toast.makeText(context, "Call setup failed. Please restart the app.", Toast.LENGTH_LONG).show()
            }
            onEndCall()
        }
    }

    // ── Step 2+3: Permission check + WebRTC session ───────────────────────────
    // Runs only after Step 1 completes (peerConnectionFactory.value becomes non-null).
    LaunchedEffect(peerConnectionFactory.value) {
        val factory = peerConnectionFactory.value ?: return@LaunchedEffect

        // ── Step 2: Permission check ─────────────────────────────────────────
        val hasMicPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasMicPerm) { Toast.makeText(context, "Microphone permission needed", Toast.LENGTH_SHORT).show(); onEndCall(); return@LaunchedEffect }

        // ── Step 3: WebRTC session ───────────────────────────────────────────

        val egl = eglBase.value!!

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = isSpeakerOn

            // Receiver হলে existingCallId ব্যবহার করো; caller হলে নতুন callId তৈরি করো
            if (!isReceiver) {
                val chatHash = generateChatId(currentUser.mobile, contact.mobile)
                callId = "call_${chatHash}_${System.currentTimeMillis()}"
            }
            // isReceiver=true হলে callId ইতিমধ্যে existingCallId দিয়ে initialized

            val stream = factory.createLocalMediaStream("localStream")
            val audioSource = factory.createAudioSource(MediaConstraints())
            stream.addTrack(factory.createAudioTrack("audioTrack", audioSource))

            if (callType == "video") {
                val hasCamPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                if (hasCamPerm) {
                    getVideoCapturer(context)?.let { capturer ->
                        val helper = SurfaceTextureHelper.create("CaptureThread", egl.eglBaseContext)
                        val videoSource = factory.createVideoSource(capturer.isScreencast)
                        capturer.initialize(helper, context, videoSource.capturerObserver)
                        capturer.startCapture(1280, 720, 30)
                        val vTrack = factory.createVideoTrack("videoTrack", videoSource)
                        stream.addTrack(vTrack)
                        localVideoTrack = vTrack
                        videoCapturer = capturer // store for cleanup/flip
                    }
                }
            }
            localStream = stream

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                // Different network fix:
                // TCP candidate enable করো — UDP blocked হলে TCP TURN দিয়ে relay হবে।
                // Mobile data ↔ WiFi এ UDP প্রায়ই NAT/firewall এ block হয়।
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
                // ICE_TRANSPORT_POLICY_ALL: STUN + TURN দুটোই try করো।
                // RELAY_ONLY দিলে direct connection বাদ যায়, ALL দিলে best path বেছে নেয়।
                iceTransportsType = PeerConnection.IceTransportsType.ALL
            }

            // FIX: observer object extracted to buildPeerConnectionObserver() top-level function.
            // Kotlin 2.1.x IR backend NullPointerException at instruction #346
            // (FixStackAnalyzer / coroutine state machine bytecode overflow) —
            // the anonymous object declaration inside this LaunchedEffect was pushing
            // the suspend lambda past the JVM 64KB method bytecode limit.
            // Moving it to a named top-level function allocates its bytecode in a
            // separate class file, cutting this lambda's size below the threshold.
            val observer = buildPeerConnectionObserver(
                isReceiver           = isReceiver,
                scope                = scope,
                db                   = db,
                getCallId            = { callId },
                setCallStatus        = { callStatus = it },
                setIsConnected       = { isConnected = it },
                getIceDisconnectJob  = { iceDisconnectJob },
                setIceDisconnectJob  = { iceDisconnectJob = it },
                onEndCall            = onEndCall,
                getRemoteVideoTrack  = { remoteVideoTrack },
                setRemoteVideoTrack  = { remoteVideoTrack = it },
                getRemoteSurfaceView = { remoteSurfaceView }
            )

            val pc = factory.createPeerConnection(rtcConfig, observer)
            stream.audioTracks.forEach { pc?.addTrack(it, listOf("localStream")) }
            if (callType == "video") stream.videoTracks.forEach { pc?.addTrack(it, listOf("localStream")) }
            peerConnection = pc
            // FIX: receiver/caller signaling paths moved to LaunchedEffect(peerConnection) below.
            // This splits the single 300+ line LaunchedEffect into two smaller ones,
            // reducing per-method bytecode size below the threshold that triggers
            // the Kotlin 2.1.x FixStackAnalyzer NullPointerException at instruction #346.
        } catch (e: Exception) {
            Toast.makeText(context, "Call error: ${e.message}", Toast.LENGTH_SHORT).show()
            onEndCall()
        }
    }

    // ── ScreenShareManager attach — separate LaunchedEffect to avoid IR bytecode overflow ──
    // Keeping this out of LaunchedEffect(peerConnection) below prevents the Kotlin 2.1.x
    // FixStackAnalyzer NullPointerException at instruction #346 (same fix pattern as before).
    LaunchedEffect(peerConnection, callId) {
        val pc = peerConnection ?: return@LaunchedEffect
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val isLan = prefs.getBoolean(PREF_LAN_MODE, false)
        val stream = localStream ?: return@LaunchedEffect
        val factory = peerConnectionFactory.value ?: return@LaunchedEffect
        val egl = eglBase.value ?: return@LaunchedEffect
        ScreenShareManager.attachCall(
            peerConnection = pc,
            factory        = factory,
            eglBase        = egl,
            localStream    = stream,
            callDocId      = callId,
            lanMode        = isLan,
            lanManager     = if (isLan) LanCallManager.getInstance(context) else null
        )
    }

    // FIX: Receiver/caller signaling paths extracted to top-level suspend functions
    // handleReceiverSignaling() and handleCallerSignaling() below.
    // Each anonymous SdpObserver + nested coroutine block was pushing this
    // LaunchedEffect lambda past the JVM 64KB method bytecode limit, triggering
    // Kotlin 2.1.x FixStackAnalyzer NullPointerException at instruction #346.
    // Moving them to named top-level functions allocates their bytecode in
    // separate class files, cutting this lambda's bytecode size below the threshold.
    LaunchedEffect(peerConnection, isReceiver) {
        val pc = peerConnection ?: return@LaunchedEffect
        try {
            if (isReceiver) {
                handleReceiverSignaling(
                    pc = pc,
                    db = db,
                    callId = callId,
                    callType = callType,
                    scope = scope,
                    setCallStatus = { callStatus = it },
                    onEndCall = onEndCall
                )
            } else {
                handleCallerSignaling(
                    pc = pc,
                    db = db,
                    callId = callId,
                    callType = callType,
                    currentUser = currentUser,
                    contact = contact,
                    context = context,
                    scope = scope,
                    setCallStatus = { callStatus = it },
                    onEndCall = onEndCall
                )
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Call error: ${e.message}", Toast.LENGTH_SHORT).show()
            onEndCall()
        }
    }

    LaunchedEffect(isConnected) {
        // isConnected=true হলেই কাউন্ট শুরু।
        // delay শেষে increment → connected হওয়ার ১ সেকেন্ড পর "0:01" দেখায়।
        if (isConnected) while (true) { delay(1000L); callSeconds++ }
    }

    // Call চলাকালীন screen যেন না নেভে (WhatsApp এর মতো)
    val currentView = LocalView.current
    DisposableEffect(Unit) {
        currentView.keepScreenOn = true
        onDispose {
            currentView.keepScreenOn = false
            iceDisconnectJob?.cancel()
            peerConnection?.close()
            // Stop camera capturer before disposing stream
            try { videoCapturer?.stopCapture(); videoCapturer?.dispose() } catch (_: Exception) {}
            // Remove sinks before releasing
            try {
                localVideoTrack?.removeSink(localSurfaceView)
                remoteVideoTrack?.removeSink(remoteSurfaceView)
            } catch (_: Exception) {}
            try { localSurfaceView?.release() } catch (_: Exception) {}
            try { remoteSurfaceView?.release() } catch (_: Exception) {}
            localStream?.dispose()
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
            try { eglBase.value?.release() } catch (_: Exception) {}
            // Screen share cleanup
            ScreenShareManager.reset()
        }
    }

    // ── Screen share: Firestore signal listener (normal mode) ─────────────────
    // LAN mode: signals come via LanCallManager TCP socket (already handled in processSignalMessage)
    LaunchedEffect(callId) {
        if (callId.isEmpty()) return@LaunchedEffect
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_LAN_MODE, false)) return@LaunchedEffect   // LAN: TCP handles it
        db.collection("calls").document(callId).collection("screenShare")
            .addSnapshotListener { snap, _ ->
                snap?.documentChanges?.forEach { change ->
                    if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        try {
                            val payload = change.document.getString("payload") ?: return@forEach
                            ScreenShareManager.handleSignal(context, org.json.JSONObject(payload))
                        } catch (_: Exception) {}
                    }
                }
            }
    }

    // Pulsing animation for outgoing/calling state
    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseScale"
    )

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B141A)) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (callType == "video") {
                // ── VIDEO RENDERING — Black Screen Fix ───────────────────────────────
                //
                // পুরনো সমস্যা (কেন black screen আসত):
                //   1. `eglBase.value` null থাকতেই AndroidView factory চলত।
                //      `return@also` দিয়ে বেরিয়ে যেত → remoteSurfaceView কখনো set হত না।
                //   2. LaunchedEffect(remoteVideoTrack) শুধু track change এ চলত।
                //      যদি track আগে আসে, renderer null → sink attach হত না।
                //      যদি renderer আগে আসে, track null → sink attach হত না।
                //   3. WiFi ↔ mobile data switch এ eglBase নতুন করে তৈরি হত না
                //      কিন্তু renderer পুরানো context দিয়ে initialized থাকত।
                //
                // নতুন fix:
                //   - `eglBase.value` null হলে AndroidView render করবে না (key দিয়ে guard)।
                //   - `update` callback দিয়ে renderer কে eglBase ready হলেই init করো।
                //   - LaunchedEffect(remoteVideoTrack, remoteSurfaceView) —
                //     দুটোর যেকোনোটা পরিবর্তন হলে cross-attach চেষ্টা করো।
                //     এতে race condition সম্পূর্ণ দূর হয়।

                val eglCtx = eglBase.value?.eglBaseContext

                if (eglCtx != null) {
                    // Remote video — full screen background
                    AndroidView(
                        factory = { ctx ->
                            SurfaceViewRenderer(ctx).apply {
                                try {
                                    init(eglCtx, null)
                                    setMirror(false)
                                    setEnableHardwareScaler(true)
                                } catch (_: Exception) {}
                                remoteSurfaceView = this
                                remoteVideoTrack?.addSink(this)
                            }
                        },
                        update = { renderer ->
                            remoteVideoTrack?.let { track ->
                                try { track.addSink(renderer) } catch (_: Exception) {}
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // ── Remote touch forwarding overlay (normal mode) ─────────
                    if (remoteInputGranted) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            ScreenShareManager.sendTouchEvent(context,
                                                offset.x / size.width.toFloat(),
                                                offset.y / size.height.toFloat(),
                                                android.view.MotionEvent.ACTION_DOWN)
                                        },
                                        onDrag = { change, _ ->
                                            ScreenShareManager.sendTouchEvent(context,
                                                change.position.x / size.width.toFloat(),
                                                change.position.y / size.height.toFloat(),
                                                android.view.MotionEvent.ACTION_MOVE)
                                        },
                                        onDragEnd = {
                                            ScreenShareManager.sendTouchEvent(context, 0f, 0f, android.view.MotionEvent.ACTION_UP)
                                        }
                                    )
                                }
                        ) {
                            Surface(
                                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                                color = Color(0xFFFF9800).copy(0.9f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("✋ Touch Mode", color = Color.White, fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                        }
                    }

                    // Local video — PiP (Picture-in-Picture), top-right corner
                    AndroidView(
                        factory = { ctx ->
                            SurfaceViewRenderer(ctx).apply {
                                try {
                                    init(eglCtx, null)
                                    setMirror(true)
                                    setEnableHardwareScaler(true)
                                    setZOrderMediaOverlay(true) // PiP কে remote এর উপরে রাখো
                                } catch (_: Exception) {}
                                localSurfaceView = this
                                localVideoTrack?.addSink(this)
                            }
                        },
                        update = { renderer ->
                            localVideoTrack?.let { track ->
                                try { track.addSink(renderer) } catch (_: Exception) {}
                            }
                        },
                        modifier = Modifier
                            .size(120.dp, 160.dp)
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                }

                // ── Cross-attach: track বা renderer যে পরেই আসুক, attach হবে ──
                // race condition সম্পূর্ণ দূর করতে দুটো key একসাথে watch করো।
                LaunchedEffect(remoteVideoTrack, remoteSurfaceView) {
                    val track = remoteVideoTrack ?: return@LaunchedEffect
                    val renderer = remoteSurfaceView ?: return@LaunchedEffect
                    try { track.addSink(renderer) } catch (_: Exception) {}
                }
                LaunchedEffect(localVideoTrack, localSurfaceView) {
                    val track = localVideoTrack ?: return@LaunchedEffect
                    val renderer = localSurfaceView ?: return@LaunchedEffect
                    try { track.addSink(renderer) } catch (_: Exception) {}
                }

                // ── eglBase ready হলে renderer নেই? তাহলে recompose trigger করো ──
                // eglBase IO thread এ async তৈরি হয়। প্রথম recompose এ null,
                // পরে non-null হয় — কিন্তু AndroidView তখন আর factory চালায় না।
                // key(eglBase.value) দিয়ে eglBase পরিবর্তনে AndroidView পুনরায় তৈরি করো।
                LaunchedEffect(eglBase.value) {
                    val ctx = eglBase.value?.eglBaseContext ?: return@LaunchedEffect
                    // renderer ইতিমধ্যে initialized? তাহলে কিছু করার নেই।
                    // না হলে Compose পরের recompose এ নতুন AndroidView তৈরি করবে।
                    // (key(eglBase.value) এটা handle করে — explicit reattach লাগবে না)
                }
            }

            Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(80.dp))
                if (callType != "video" || !isConnected) {
                    // Pulsing rings when not yet connected
                    Box(contentAlignment = Alignment.Center) {
                        if (!isConnected) {
                            Box(
                                modifier = Modifier
                                    .size((120 * pulseScale).dp)
                                    .clip(CircleShape)
                                    .background(RasGramTheme.Green.copy(alpha = 0.12f))
                            )
                            Box(
                                modifier = Modifier
                                    .size((100 * pulseScale).dp)
                                    .clip(CircleShape)
                                    .background(RasGramTheme.Green.copy(alpha = 0.18f))
                            )
                        }
                        AsyncImage(
                            model = contact.avatarUrl.ifEmpty { "https://ui-avatars.com/api/?name=${contact.name.replace(" ", "+")}&size=200&background=008069&color=fff&bold=true" },
                            contentDescription = null,
                            modifier = Modifier.size(100.dp).clip(CircleShape).border(3.dp, RasGramTheme.Green, CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(contact.name, style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))
                    // Connected: green dot + timer | Calling: animated status
                    if (isConnected) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(RasGramTheme.Green))
                            Spacer(Modifier.width(6.dp))
                            Text(formatTime(callSeconds), color = RasGramTheme.Green, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        }
                    } else {
                        Text(callStatus, color = RasGramTheme.TextMuted, style = MaterialTheme.typography.titleMedium)
                        Text(contact.mobile, color = RasGramTheme.TextMuted.copy(alpha = 0.5f), fontSize = 13.sp)
                    }
                }
                Spacer(modifier = Modifier.weight(1f))

                Surface(shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), color = Color(0xFF182229).copy(0.95f), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            CallControlButton(icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic, label = if (isMuted) "Unmute" else "Mute", isActive = isMuted, activeColor = RasGramTheme.Red) {
                                isMuted = !isMuted
                                localStream?.audioTracks?.firstOrNull()?.setEnabled(!isMuted)
                            }
                            FloatingActionButton(onClick = {
                                scope.launch {
                                    val durationSecs = callSeconds
                                    if (callId.isNotEmpty()) {
                                        // Not connected = caller cancelled before pickup → "missed"
                                        // Connected = normal end → "ended"
                                        val finalStatus = if (isConnected) "ended" else "missed"
                                        db.collection("calls").document(callId).update(
                                            "status", finalStatus,
                                            "duration", durationSecs
                                        )
                                        // Chat এ call log bubble
                                        val chatId = generateChatId(currentUser.mobile, contact.mobile)
                                        val logText = if (callType == "video")
                                            "📹 Video call · ${formatTime(durationSecs)}"
                                        else
                                            "📞 Voice call · ${formatTime(durationSecs)}"
                                        db.collection("pvt_msg_$chatId").add(hashMapOf(
                                            "text"           to logText,
                                            "senderMobile"   to currentUser.mobile,
                                            "receiverMobile" to contact.mobile,
                                            "timestamp"      to System.currentTimeMillis(),
                                            "timeString"     to java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date()),
                                            "isCallLog"      to true,
                                            "callStatus"     to "ended",
                                            "callType"       to callType,
                                            "duration"       to durationSecs,
                                            "read"           to false,
                                            "delivered"      to false
                                        ))
                                    }
                                    if (isConnected && durationSecs > 0) {
                                        finalCallSeconds = durationSecs
                                        showEndedSummary = true
                                    } else {
                                        onEndCall()
                                    }
                                }
                            }, containerColor = RasGramTheme.Red, modifier = Modifier.size(72.dp)) {
                                Icon(Icons.Default.CallEnd, null, tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                            CallControlButton(icon = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff, label = "Speaker", isActive = isSpeakerOn, activeColor = RasGramTheme.Green) {
                                isSpeakerOn = !isSpeakerOn
                                // MODE_IN_COMMUNICATION must stay active during toggle
                                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                                audioManager.isSpeakerphoneOn = isSpeakerOn
                            }
                        }
                        if (callType == "video") {
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                CallControlButton(icon = if (isCameraOff) Icons.Default.VideocamOff else Icons.Default.Videocam, label = "Camera", isActive = isCameraOff, activeColor = RasGramTheme.Red) {
                                    isCameraOff = !isCameraOff
                                    localStream?.videoTracks?.firstOrNull()?.setEnabled(!isCameraOff)
                                }
                                CallControlButton(icon = Icons.Default.Cameraswitch, label = "Flip", isActive = false, activeColor = RasGramTheme.Green) {
                                    try {
                                        (videoCapturer as? org.webrtc.Camera2Capturer)?.switchCamera(null)
                                        ?: (videoCapturer as? org.webrtc.Camera1Capturer)?.switchCamera(null)
                                    } catch (_: Exception) {}
                                }
                                // ── Screen Share button ───────────────────────
                                CallControlButton(
                                    icon = if (isSharingScreen) Icons.Default.StopScreenShare else Icons.Default.ScreenShare,
                                    label = if (isSharingScreen) "Stop Share" else "Share Screen",
                                    isActive = isSharingScreen,
                                    activeColor = Color(0xFF00BCD4)
                                ) {
                                    if (isSharingScreen) {
                                        ScreenShareManager.stopScreenShare(context)
                                    } else {
                                        screenShareLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                                    }
                                }
                                // ── Remote Input button (viewer side) ────────
                                // দেখায় যখন peer screen share করছে
                                if (isRemoteSharing) {
                                    CallControlButton(
                                        icon = if (remoteInputGranted) Icons.Default.TouchApp else Icons.Default.PanTool,
                                        label = if (remoteInputGranted) "Touch On" else "Request Touch",
                                        isActive = remoteInputGranted,
                                        activeColor = Color(0xFFFF9800)
                                    ) {
                                        if (!remoteInputGranted) {
                                            // Check Accessibility first
                                            if (RemoteInputAccessibilityService.isServiceEnabled(context)) {
                                                ScreenShareManager.requestRemoteInput(context)
                                            } else {
                                                RemoteInputAccessibilityService.openAccessibilitySettings(context)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // ── Remote input permission dialog (sharer side) ──────
                        if (showInputRequestDialog) {
                            AlertDialog(
                                onDismissRequest = { showInputRequestDialog = false },
                                containerColor = RasGramTheme.DarkPanel,
                                icon = { Icon(Icons.Default.TouchApp, null, tint = Color(0xFFFF9800)) },
                                title = { Text("Remote Touch Request", color = RasGramTheme.TextPrimary, fontWeight = FontWeight.Bold) },
                                text = {
                                    Text(
                                        "${contact.name} আপনার স্ক্রিনে ট্যাচ করার অনুমতি চাইছে।\nতারা screen share দেখার সাথে সাথে আপনার ডিভাইস নিয়ন্ত্রণ করতে পারবে।",
                                        color = RasGramTheme.TextMuted
                                    )
                                },
                                confirmButton = {
                                    Button(
                                        onClick = { showInputRequestDialog = false; ScreenShareManager.grantRemoteInput(context) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                                    ) { Text("অনুমতি দিন") }
                                },
                                dismissButton = {
                                    OutlinedButton(onClick = { showInputRequestDialog = false; ScreenShareManager.denyRemoteInput(context) }) {
                                        Text("না", color = RasGramTheme.TextMuted)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Call ended duration summary overlay ──────────────────────────────────
    if (showEndedSummary) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xCC000000)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(Color(0xFF1E2B23), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.CallEnd,
                        contentDescription = null,
                        tint = Color(0xFFFF4444),
                        modifier = Modifier.size(36.dp)
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    contact.name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "কল শেষ",
                    color = RasGramTheme.TextMuted,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(16.dp))
                // Duration pill
                Box(
                    modifier = Modifier
                        .background(Color(0xFF1E3A2B), RoundedCornerShape(50.dp))
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.AccessTime,
                            contentDescription = null,
                            tint = RasGramTheme.Green,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        val mins = finalCallSeconds / 60
                        val secs = finalCallSeconds % 60
                        val durationText = when {
                            mins == 0 -> "$secs সেকেন্ড"
                            secs == 0 -> "$mins মিনিট"
                            else -> "$mins মিনিট $secs সেকেন্ড"
                        }
                        Text(
                            "কথা হয়েছে $durationText",
                            color = RasGramTheme.Green,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                    }
                }
                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = { showEndedSummary = false; onEndCall() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                    shape = RoundedCornerShape(50.dp),
                    contentPadding = PaddingValues(horizontal = 36.dp, vertical = 14.dp)
                ) {
                    Text("বন্ধ করুন", color = Color.White, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// ==================== INCOMING CALL SCREEN ====================
// WhatsApp-style full-screen incoming call UI
@Composable
fun IncomingCallScreen(
    currentUser: User,
    callerName: String,
    callerMobile: String,
    callType: String,
    callId: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { FirebaseFirestore.getInstance() }
    val scope = rememberCoroutineScope()

    // ── Ring management ──────────────────────────────────────────────────────
    // দুটো path:
    //   A) App BACKGROUND: IncomingCallOverlayService ring করে।
    //      Activity/screen খুললে service stop → onDestroy → ring বন্ধ।
    //      এই path এ IncomingCallScreen নিজে ring করলে double ring হবে।
    //      তাই: IncomingCallOverlayService.isRunning check করে ring এড়াব।
    //
    //   B) App FOREGROUND (Firestore listener trigger):
    //      IncomingCallOverlayService চলছে না।
    //      IncomingCallScreen কে নিজেই ring বাজাতে হবে।
    //
    // উপসংহার: isRunning=false হলেই ring বাজাব।

    val ringtoneRef = remember { mutableStateOf<android.media.Ringtone?>(null) }

    // Ring start + cleanup
    // FIX: isRunning check সরানো হয়েছে।
    // আগে: service চলছে মানে ring বাজাতাম না → service stop হলে gap তৈরি হত।
    // এখন: IncomingCallScreen সবসময় নিজেই ring বাজায়।
    //   - Service path: service ring + screen ring একসাথে চলে (overlap) → gap নেই
    //   - Service stop হলে: service এর ring বন্ধ, screen এর ring চলতে থাকে
    //   - Foreground path: service নেই, screen একাই ring করে (আগের মতোই)
    DisposableEffect(callId) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.mode = android.media.AudioManager.MODE_RINGTONE

            // ── Force vibrate সবসময় — ringer mode যাই হোক ──────────────────
            // Silent/vibrate/normal সব mode এ call vibrate হবে।
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
            // Call pattern: 800ms on, 600ms off, loop — strong & noticeable
            val callPattern = longArrayOf(0, 800, 600)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createWaveform(callPattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(callPattern, 0)
            }

            // ── Ring: Normal mode এ ring ও বাজাও (vibrate এর সাথে) ──────────
            if (am.ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL) {
                val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
                val rt = android.media.RingtoneManager.getRingtone(context, uri)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) rt?.isLooping = true
                rt?.play()
                ringtoneRef.value = rt
            }
            // Silent/vibrate mode এ ring নেই — শুধু vibrate (উপরে already started)
        } catch (_: Exception) {}
        onDispose {
            try {
                ringtoneRef.value?.stop()
                ringtoneRef.value = null
                val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                }
                vibrator.cancel()
                // NOTE: AudioManager.MODE_NORMAL এ reset করা হচ্ছে না।
                // কারণ: accept করলে সাথে সাথে CallingScreen compose হয় এবং
                // audioManager.mode = MODE_IN_COMMUNICATION সেট করে।
                // এখানে MODE_NORMAL সেট করলে CallingScreen এর audio বন্ধ হয়ে যায়
                // (race condition: onDispose কখনো CallingScreen init এর পরে চলে)।
                // CallingScreen এর নিজের DisposableEffect.onDispose MODE_NORMAL সেট করবে।
            } catch (_: Exception) {}
        }
    }

    fun stopRingAndCall(action: () -> Unit) {
        try { ringtoneRef.value?.stop(); ringtoneRef.value = null } catch (_: Exception) {}
        action()
    }

    // Ringing animation
    val infiniteTransition = rememberInfiniteTransition(label = "ring")
    val ringScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ), label = "ringScale"
    )
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f, targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000), repeatMode = RepeatMode.Restart
        ), label = "ringAlpha"
    )

    // Auto-dismiss যদি caller cancel করে
    LaunchedEffect(callId) {
        db.collection("calls").document(callId).addSnapshotListener { snap, _ ->
            val status = snap?.getString("status") ?: return@addSnapshotListener
            if (status == "ended" || status == "rejected" ||
                    status == "missed" || status == "cancelled" || status == "declined") {
                onDecline()
            }
        }
    }

    // 45s ring timeout — app foreground path এ caller cancel না করলেও auto-dismiss।
    // Overlay service এ 60s আছে, কিন্তু foreground IncomingCallScreen এর নিজের timeout নেই।
    // 45s পর status="missed" লিখে dismiss — WhatsApp behavior।
    LaunchedEffect(callId) {
        kotlinx.coroutines.delay(45_000L)
        try {
            db.collection("calls").document(callId).update("status", "missed")
        } catch (_: Exception) {}
        onDecline()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0B3D2E), Color(0xFF071A14))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(60.dp))

            // Call type label
            Text(
                if (callType == "video") "📹  ভিডিও কল আসছে..." else "📞  অডিও কল আসছে...",
                color = RasGramTheme.Green.copy(alpha = 0.85f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(Modifier.height(32.dp))

            // Avatar with ripple ring
            Box(contentAlignment = Alignment.Center) {
                // Outer ripple
                Box(
                    modifier = Modifier
                        .size((120 * ringScale).dp)
                        .background(RasGramTheme.Green.copy(alpha = ringAlpha * 0.3f), CircleShape)
                )
                // Inner ring
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .background(RasGramTheme.Green.copy(alpha = 0.15f), CircleShape)
                )
                // Avatar
                AsyncImage(
                    model = "https://ui-avatars.com/api/?name=${callerName.replace(" ", "+")}&size=200&background=128C7E&color=fff",
                    contentDescription = null,
                    modifier = Modifier
                        .size(110.dp)
                        .clip(CircleShape)
                        .border(3.dp, RasGramTheme.Green, CircleShape),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                callerName,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 28.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                callerMobile,
                color = RasGramTheme.TextMuted,
                fontSize = 15.sp
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "RasGram",
                color = RasGramTheme.Green.copy(alpha = 0.7f),
                fontSize = 13.sp
            )

            Spacer(Modifier.weight(1f))

            // Accept / Decline buttons — WhatsApp layout
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 60.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Decline
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingActionButton(
                        onClick = {
                            stopRingAndCall {
                                scope.launch {
                                    db.collection("calls").document(callId).update("status", "rejected")
                                }
                                onDecline()
                            }
                        },
                        containerColor = Color(0xFFE53935),
                        modifier = Modifier.size(72.dp)
                    ) {
                        Icon(
                            Icons.Default.CallEnd,
                            contentDescription = "Decline",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("প্রত্যাখ্যান", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                }

                // Accept
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingActionButton(
                        onClick = {
                            stopRingAndCall {
                                // NOTE: status="answered" এখানে লেখা হচ্ছে না।
                                // CallingScreen receiver path এ setLocalDescription.onSetSuccess এ
                                // status + answer SDP একসাথে atomically লেখা হয়।
                                // এখানে আগে status="answered" লিখলে:
                                //   1) Caller SDP ছাড়াই "answered" দেখে → setRemoteDescription fail
                                //   2) এই Firestore write RasGramApp snapshot listener re-trigger করে
                                //      → double incoming call UI দেখায়
                                onAccept()
                            }
                        },
                        containerColor = RasGramTheme.Green,
                        modifier = Modifier.size(72.dp)
                    ) {
                        Icon(
                            if (callType == "video") Icons.Default.Videocam else Icons.Default.Call,
                            contentDescription = "Accept",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("গ্রহণ", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun CallControlButton(icon: ImageVector, label: String, isActive: Boolean, activeColor: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FloatingActionButton(
            onClick = onClick,
            containerColor = if (isActive) activeColor else Color.White.copy(0.15f),
            modifier = Modifier.size(56.dp)
        ) {
            Icon(icon, label, tint = Color.White)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(label, color = RasGramTheme.TextMuted, style = MaterialTheme.typography.labelSmall)
    }
}

// ==================== SETTINGS DIALOG ====================
@Composable
fun SettingsDialog(
    currentUser: User,
    onDismiss: () -> Unit,
    onSave: (User) -> Unit
) {
    val db = remember { FirebaseFirestore.getInstance() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var name by remember { mutableStateOf(currentUser.name) }
    var about by remember { mutableStateOf(currentUser.about) }
    var isUploading by remember { mutableStateOf(false) }
    var avatarUrl by remember { mutableStateOf(currentUser.avatarUrl) }
    var selectedTab by remember { mutableIntStateOf(0) }

    val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    var callDeliveryMethod by remember { mutableStateOf(prefs.getString(PREF_CALL_DELIVERY, "fcm") ?: "fcm") }
    var serviceAccountJson by remember { mutableStateOf(prefs.getString(PREF_SA_JSON, "") ?: "") }
    var lanModeEnabled by remember { mutableStateOf(prefs.getBoolean(PREF_LAN_MODE, false)) }
    val lanManager = remember { LanChatManager.getInstance(context) }
    val lanUsers by lanManager.discoveredUsers.collectAsState()

    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            isUploading = true
            scope.launch {
                val (url, _, _) = uploadToCloudinary(context, it) { }
                if (url != null) {
                    avatarUrl = url
                    db.collection("chat_users").document(currentUser.mobile).update("avatarUrl", url)
                    Toast.makeText(context, "Profile photo updated!", Toast.LENGTH_SHORT).show()
                }
                isUploading = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()), shape = RoundedCornerShape(20.dp), color = RasGramTheme.DarkPanel) {
            Column {
                // Header with gradient
                Box(
                    modifier = Modifier.fillMaxWidth().height(160.dp).background(
                        Brush.verticalGradient(listOf(RasGramTheme.GreenDark, RasGramTheme.DarkPanel))
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        AsyncImage(
                            model = avatarUrl.ifEmpty { "https://ui-avatars.com/api/?name=${currentUser.name.replace(" ", "+")}&background=008069&color=fff&size=200" },
                            contentDescription = null,
                            modifier = Modifier.size(96.dp).clip(CircleShape).border(3.dp, Color.White, CircleShape),
                            contentScale = ContentScale.Crop
                        )
                        FloatingActionButton(onClick = { imageLauncher.launch(arrayOf("image/*")) }, modifier = Modifier.size(34.dp), containerColor = RasGramTheme.Green) {
                            Icon(Icons.Default.CameraAlt, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                Column(modifier = Modifier.padding(20.dp)) {
                    // Tabs
                    Row(modifier = Modifier.fillMaxWidth().background(RasGramTheme.DarkBackground, RoundedCornerShape(10.dp)).padding(4.dp)) {
                        listOf("Profile", "Privacy", "Notifs", "Calls", "Storage", "LAN").forEachIndexed { i, label ->
                            Surface(
                                modifier = Modifier.weight(1f).clickable { selectedTab = i },
                                shape = RoundedCornerShape(8.dp),
                                color = if (selectedTab == i) RasGramTheme.Green else Color.Transparent
                            ) {
                                Text(label, modifier = Modifier.padding(vertical = 8.dp), textAlign = TextAlign.Center, color = if (selectedTab == i) Color.Black else RasGramTheme.TextMuted, style = MaterialTheme.typography.labelMedium, fontWeight = if (selectedTab == i) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    when (selectedTab) {
                        0 -> {
                            // Profile tab
                            OutlinedTextField(value = name, onValueChange = { if (it.length <= 25) name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), colors = outlinedFieldColors(), shape = RoundedCornerShape(12.dp), trailingIcon = { Text("${name.length}/25", color = RasGramTheme.TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 8.dp)) })
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(value = about, onValueChange = { if (it.length <= 139) about = it }, label = { Text("About") }, modifier = Modifier.fillMaxWidth(), colors = outlinedFieldColors(), shape = RoundedCornerShape(12.dp), maxLines = 2, trailingIcon = { Text("${about.length}/139", color = RasGramTheme.TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 8.dp)) })
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Phone, null, tint = RasGramTheme.Green, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("+${currentUser.mobile}", color = RasGramTheme.TextMuted, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        1 -> {
                            // FIX #2: Icons.Default.ProfileBadge doesn't exist â€” replaced with AccountCircle
                            SettingsToggleRow(Icons.Default.Visibility, "Show Last Seen", true) { }
                            SettingsToggleRow(Icons.Default.DoneAll, "Show Read Receipts", true) { }
                            SettingsToggleRow(Icons.Default.AccountCircle, "Show Profile Photo", true) { }
                            SettingsToggleRow(Icons.Default.Circle, "Show Status", true) { }
                        }
                        2 -> {
                            SettingsToggleRow(Icons.Default.Notifications, "Message Notifications", true) { }
                            SettingsToggleRow(Icons.Default.VolumeUp, "Notification Sound", true) { }
                            SettingsToggleRow(Icons.Default.Vibration, "Vibration", true) { }
                            SettingsToggleRow(Icons.Default.Groups, "Group Notifications", true) { }
                        }
                        3 -> {
                            Text("Background Call Method", color = RasGramTheme.Green, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Select how RasGram should receive calls when app is closed.", color = RasGramTheme.TextMuted, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(16.dp))

                            // FCM Radio
                            Row(modifier = Modifier.fillMaxWidth().clickable { callDeliveryMethod = "fcm" }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = callDeliveryMethod == "fcm", onClick = { callDeliveryMethod = "fcm" }, colors = RadioButtonDefaults.colors(selectedColor = RasGramTheme.Green))
                                Column {
                                    Text("FCM Push (Recommended)", color = RasGramTheme.TextPrimary)
                                    Text("Service account embedded in app. Works even if phone is locked.", color = RasGramTheme.TextMuted, fontSize = 11.sp, lineHeight = 14.sp)
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // Foreground Service Radio
                            Row(modifier = Modifier.fillMaxWidth().clickable { callDeliveryMethod = "foreground" }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = callDeliveryMethod == "foreground", onClick = { callDeliveryMethod = "foreground" }, colors = RadioButtonDefaults.colors(selectedColor = RasGramTheme.Green))
                                Column {
                                    Text("Foreground Service", color = RasGramTheme.TextPrimary)
                                    Text("Shows a persistent notification to keep app listener alive.", color = RasGramTheme.TextMuted, fontSize = 11.sp)
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))

                            // WorkManager Radio
                            Row(modifier = Modifier.fillMaxWidth().clickable { callDeliveryMethod = "workmanager" }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = callDeliveryMethod == "workmanager", onClick = { callDeliveryMethod = "workmanager" }, colors = RadioButtonDefaults.colors(selectedColor = RasGramTheme.Green))
                                Column {
                                    Text("Background Polling", color = RasGramTheme.TextPrimary)
                                    Text("Checks every 15 mins. Battery intensive and delayed.", color = RasGramTheme.TextMuted, fontSize = 11.sp)
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))

                            // Disabled Radio
                            Row(modifier = Modifier.fillMaxWidth().clickable { callDeliveryMethod = "disabled" }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = callDeliveryMethod == "disabled", onClick = { callDeliveryMethod = "disabled" }, colors = RadioButtonDefaults.colors(selectedColor = RasGramTheme.Green))
                                Column {
                                    Text("In-App Only (Current)", color = RasGramTheme.TextPrimary)
                                    Text("App must be open on screen to receive calls.", color = RasGramTheme.TextMuted, fontSize = 11.sp)
                                }
                            }
                        }
                        4 -> {
                            // ── Drive Sync Tab — multi-account + Sync Now button ──
                            RasGramDriveSyncSettings(currentUser = currentUser)
                        }
                        5 -> {
                            // ── LAN / Local Mode Tab ──────────────────────────────
                            LanModeSettingsTab(
                                lanModeEnabled = lanModeEnabled,
                                lanUsers = lanUsers,
                                localIp = LanChatManager.getLocalIp(context),
                                onToggle = { enabled ->
                                    lanModeEnabled = enabled
                                    prefs.edit().putBoolean(PREF_LAN_MODE, enabled).apply()
                                    if (enabled) {
                                        lanManager.start(currentUser.mobile, currentUser.name)
                                        LanCallManager.getInstance(context).start()
                                    } else {
                                        lanManager.stop()
                                        LanCallManager.getInstance(context).stop()
                                    }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, RasGramTheme.Border), colors = ButtonDefaults.outlinedButtonColors(contentColor = RasGramTheme.TextMuted)) { Text("Cancel") }
                        Button(onClick = {
                            prefs.edit()
                                .putString(PREF_CALL_DELIVERY, callDeliveryMethod)
                                .putString(PREF_SA_JSON, serviceAccountJson)
                                .putBoolean(PREF_LAN_MODE, lanModeEnabled)
                                .apply()
                            scope.launch {
                                db.collection("chat_users").document(currentUser.mobile).update("name", name, "about", about)
                            }
                            onSave(currentUser.copy(name = name, about = about, avatarUrl = avatarUrl))
                        }, modifier = Modifier.weight(2f).height(48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = RasGramTheme.Green)) {
                            Text("Save Changes", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsToggleRow(icon: ImageVector, label: String, initialValue: Boolean, onChange: (Boolean) -> Unit) {
    var checked by remember { mutableStateOf(initialValue) }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = RasGramTheme.Green, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Text(label, color = RasGramTheme.TextPrimary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { checked = it; onChange(it) }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = RasGramTheme.Green, uncheckedTrackColor = RasGramTheme.TextMuted.copy(0.3f)))
    }
}

// ==================== LAN MODE SETTINGS TAB ====================

@Composable
fun LanModeSettingsTab(
    lanModeEnabled: Boolean,
    lanUsers: List<LanDiscoveredUser>,
    localIp: String,
    onToggle: (Boolean) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {

        // ── Main Toggle ────────────────────────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = if (lanModeEnabled) RasGramTheme.Green.copy(alpha = 0.15f) else RasGramTheme.InputBg,
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Wifi,
                            null,
                            tint = if (lanModeEnabled) RasGramTheme.Green else RasGramTheme.TextMuted,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "LAN / Local Mode",
                            color = RasGramTheme.TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (lanModeEnabled)
                            "✅ চালু — ১০০% অফলাইন। চ্যাট, ফাইল, কল সব শুধু WiFi/Hotspot এ। Firebase/ইন্টারনেট কিছুই লাগবে না।"
                        else
                            "বন্ধ — স্বাভাবিক মোড (Firebase + ইন্টারনেট ব্যবহার হচ্ছে)",
                        color = RasGramTheme.TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(start = 32.dp)
                    )
                }
                Switch(
                    checked = lanModeEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = RasGramTheme.Green,
                        uncheckedTrackColor = RasGramTheme.TextMuted.copy(0.3f)
                    )
                )
            }
        }

        if (lanModeEnabled) {
            Spacer(Modifier.height(16.dp))

            // ── My IP ────────────────────────────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = RasGramTheme.DarkBackground,
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Router, null, tint = RasGramTheme.Green, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("আমার IP Address", color = RasGramTheme.TextMuted, fontSize = 11.sp)
                        Text(localIp, color = RasGramTheme.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── Discovered Users ─────────────────────────────────────────
            Text(
                "একই WiFi তে পাওয়া Users (${lanUsers.size})",
                color = RasGramTheme.Green,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(8.dp))

            if (lanUsers.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = RasGramTheme.DarkBackground,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.SearchOff, null, tint = RasGramTheme.TextMuted, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "এখনো কেউ পাওয়া যায়নি",
                            color = RasGramTheme.TextMuted,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            "অন্য device এ RasGram খুলে LAN Mode চালু করুন",
                            color = RasGramTheme.TextMuted.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                lanUsers.forEach { user ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        color = RasGramTheme.DarkBackground,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(RasGramTheme.Green.copy(alpha = 0.2f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    user.name.take(1).uppercase(),
                                    color = RasGramTheme.Green,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(user.name, color = RasGramTheme.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(user.ip, color = RasGramTheme.TextMuted, fontSize = 11.sp)
                            }
                            Icon(Icons.Default.Wifi, null, tint = RasGramTheme.Green, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── How it works note ─────────────────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = RasGramTheme.GreenDark.copy(alpha = 0.2f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("⚡ কীভাবে কাজ করে?", color = RasGramTheme.Green, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    listOf(
                        "একই WiFi/Hotspot এ দুজন RasGram user",
                        "দুজনেই LAN Mode ON করুন",
                        "Chat List এ LAN user icon দেখাবে 📶",
                        "Text, File, Voice — সব LAN দিয়ে যাবে",
                        "Firebase ও Cloudinary ব্যবহার হবে না"
                    ).forEach { line ->
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text("• ", color = RasGramTheme.Green, fontSize = 12.sp)
                            Text(line, color = RasGramTheme.TextMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// ==================== NEW GROUP DIALOG ====================
@Composable
fun NewGroupDialog(onDismiss: () -> Unit, currentUser: User) {
    val db = remember { FirebaseFirestore.getInstance() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var groupName by remember { mutableStateOf("") }
    var groupDesc by remember { mutableStateOf("") }
    var allUsers by remember { mutableStateOf<List<User>>(emptyList()) }
    var selectedMembers by remember { mutableStateOf<Set<String>>(emptySet()) }
    var step by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        db.collection("chat_users").get().await().documents.mapNotNull { doc ->
            doc.data?.let { d ->
                User(name = d["name"] as? String ?: "", mobile = doc.id, avatarUrl = d["avatarUrl"] as? String ?: "")
            }
        }.filter { it.mobile != currentUser.mobile }.also { allUsers = it }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(20.dp), color = RasGramTheme.DarkPanel) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (step > 0) step-- else onDismiss() }) {
                        Icon(Icons.Default.ArrowBack, null, tint = RasGramTheme.TextMuted)
                    }
                    Text(if (step == 0) "Add Participants" else "New Group", style = MaterialTheme.typography.titleLarge, color = RasGramTheme.TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (step == 0 && selectedMembers.isNotEmpty()) {
                        FloatingActionButton(onClick = { step = 1 }, modifier = Modifier.size(44.dp), containerColor = RasGramTheme.Green) {
                            Icon(Icons.Default.ArrowForward, null, tint = Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (step == 0) {
                    // Select members
                    if (selectedMembers.isNotEmpty()) {
                        Text("${selectedMembers.size} selected", color = RasGramTheme.Green, style = MaterialTheme.typography.labelSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp)) {
                        items(allUsers, key = { it.mobile }) { user ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selectedMembers = if (user.mobile in selectedMembers) selectedMembers - user.mobile else selectedMembers + user.mobile
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = user.mobile in selectedMembers,
                                    onCheckedChange = { checked ->
                                        selectedMembers = if (checked) selectedMembers + user.mobile else selectedMembers - user.mobile
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = RasGramTheme.Green)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                UserAvatar(user = user, size = 40.dp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(user.name, color = RasGramTheme.TextPrimary, fontWeight = FontWeight.Medium)
                                    Text("+${user.mobile}", color = RasGramTheme.TextMuted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                } else {
                    // Group details
                    OutlinedTextField(value = groupName, onValueChange = { if (it.length <= 30) groupName = it }, label = { Text("Group Name") }, modifier = Modifier.fillMaxWidth(), colors = outlinedFieldColors(), shape = RoundedCornerShape(12.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(value = groupDesc, onValueChange = { groupDesc = it }, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth(), colors = outlinedFieldColors(), shape = RoundedCornerShape(12.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("${selectedMembers.size} participants", color = RasGramTheme.TextMuted, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            if (groupName.isNotBlank()) {
                                isLoading = true
                                scope.launch {
                                    try {
                                        val members = selectedMembers.toMutableList().also { it.add(currentUser.mobile) }
                                        db.collection("groups").add(hashMapOf(
                                            "name" to groupName, "description" to groupDesc,
                                            "members" to members, "admins" to listOf(currentUser.mobile),
                                            "createdBy" to currentUser.mobile, "createdAt" to System.currentTimeMillis(),
                                            "avatarUrl" to ""
                                        )).await()
                                        Toast.makeText(context, "Group created!", Toast.LENGTH_SHORT).show()
                                        onDismiss()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                        isLoading = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RasGramTheme.Green),
                        enabled = groupName.isNotBlank() && !isLoading
                    ) {
                        if (isLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.Black)
                        else Text("Create Group", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ==================== EMPTY STATE ====================
@Composable
fun EmptyChatState() {
    Column(
        modifier = Modifier.fillMaxSize().background(RasGramTheme.DarkBackground),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(modifier = Modifier.size(140.dp), shape = CircleShape, color = RasGramTheme.DarkPanel) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Send, null, tint = RasGramTheme.Green, modifier = Modifier.size(64.dp))
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text("RasGram", style = MaterialTheme.typography.headlineLarge, color = RasGramTheme.TextPrimary, fontWeight = FontWeight.Light, letterSpacing = 2.sp)
        Spacer(modifier = Modifier.height(12.dp))
        Text("Click on a contact to start chatting", style = MaterialTheme.typography.bodyMedium, color = RasGramTheme.TextMuted, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(32.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Lock, null, modifier = Modifier.size(14.dp), tint = RasGramTheme.Green)
            Text("Your personal messages are end-to-end encrypted", style = MaterialTheme.typography.bodySmall, color = RasGramTheme.TextMuted)
        }
    }
}

// ==================== RASGRAM FILE HELPERS ====================

/**
 * Rasgram folder এর path বের করে দেয়।
 * Android Q+ (API 29+): Environment.getExternalStoragePublicDirectory(DIRECTORY_DOCUMENTS)/Rasgram
 * Older: External storage /Rasgram
 */
fun getRasgramFolder(context: Context): java.io.File {
    val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
    val dir = java.io.File(base, "Rasgram")
    if (!dir.exists()) dir.mkdirs()
    return dir
}

/**
 * URL থেকে একটি consistent local file নাম তৈরি করে।
 * URL hash + original file name যুক্ত করে দেয়।
 */
fun rasgramLocalFileName(url: String, fileName: String?, fileType: String?): String {
    val urlHash = url.hashCode().let { if (it < 0) "n${-it}" else "p$it" }
    val ext = when {
        fileName != null && fileName.contains('.') -> ".${fileName.substringAfterLast('.')}"
        fileType != null -> ".${MimeTypeMap.getSingleton().getExtensionFromMimeType(fileType) ?: "bin"}"
        else -> ".bin"
    }
    val baseName = fileName?.substringBeforeLast('.')?.take(32) ?: "rasgram"
    return "${baseName}_${urlHash}${ext}"
}

/**
 * Rasgram folder এ ওই URL-এর জন্য cached file আছে কিনা দেখে।
 * থাকলে File object ফেরত দেয়, না থাকলে null।
 */
fun getRasgramCachedFile(context: Context, url: String, fileName: String?, fileType: String?): java.io.File {
    // LAN mode: local:// URL মানে file already local disk এ আছে
    if (url.startsWith("local://")) {
        return java.io.File(url.removePrefix("local://"))
    }
    val folder = getRasgramFolder(context)
    val name = rasgramLocalFileName(url, fileName, fileType)
    return java.io.File(folder, name)
}

/**
 * Telegram/WhatsApp style download:
 * URL থেকে file download করে Rasgram folder এ রাখে।
 * Progress callback (0.0 - 1.0) দিয়ে progress update করে।
 * সফল হলে saved File, ব্যর্থ হলে null।
 */
suspend fun downloadToRasgramFolder(
    context: Context,
    url: String,
    fileName: String?,
    fileType: String,
    onProgress: (Float) -> Unit = {}
): java.io.File? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        val folder = getRasgramFolder(context)
        val name = rasgramLocalFileName(url, fileName, fileType)
        val destFile = java.io.File(folder, name)

        // Already exists? Return immediately
        if (destFile.exists() && destFile.length() > 0) {
            return@withContext destFile
        }

        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val request = okhttp3.Request.Builder().url(url).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) return@withContext null

        val body = response.body ?: return@withContext null
        val totalBytes = body.contentLength()
        var downloadedBytes = 0L

        val tmpFile = java.io.File(folder, "${name}.tmp")
        body.byteStream().use { input ->
            tmpFile.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloadedBytes += read
                    if (totalBytes > 0) {
                        val prog = downloadedBytes.toFloat() / totalBytes.toFloat()
                        kotlinx.coroutines.runBlocking {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onProgress(prog) }
                        }
                    }
                }
            }
        }

        tmpFile.renameTo(destFile)

        // Scan so it appears in gallery/file managers
        MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), arrayOf(fileType), null)

        destFile
    } catch (e: Exception) {
        null
    }
}

/**
 * Local file কে FileProvider দিয়ে open করে।
 * Browser/external URL এ যায় না — সরাসরি device-এর viewer খুলে।
 */
fun openLocalFileWithProvider(context: Context, file: java.io.File, mimeType: String) {
    try {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Check if any app can handle this
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            // Fallback: try without specific mime type
            val fallback = Intent(Intent.ACTION_VIEW).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(fallback, "Open with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    } catch (e: Exception) {
        Toast.makeText(context, "ফাইল খোলা যায়নি", Toast.LENGTH_SHORT).show()
    }
}

// ==================== CLOUDINARY UPLOAD ====================
suspend fun uploadToCloudinary(
    context: Context,
    uri: Uri,
    onProgress: (Float) -> Unit = {}
): Triple<String?, String?, String?> = withContext(Dispatchers.IO) {
    try {
        val contentResolver = context.contentResolver
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val fileName = getFileName(context, uri) ?: "file_${System.currentTimeMillis()}"

        val inputStream: InputStream = contentResolver.openInputStream(uri)
            ?: return@withContext Triple(null, null, null)
        val tempFile = File(context.cacheDir, fileName)
        tempFile.outputStream().use { out -> inputStream.copyTo(out) }

        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        val fileBody = tempFile.asRequestBody(mimeType.toMediaTypeOrNull())

        val progressBody = object : RequestBody() {
            override fun contentType() = mimeType.toMediaTypeOrNull()
            override fun contentLength() = tempFile.length()
            override fun writeTo(sink: okio.BufferedSink) {
                val buf = okio.Buffer()
                // FIX #4: was okio.Okio.source(tempFile) â€” now extension function tempFile.source()
                val src = tempFile.source()
                val total = tempFile.length()
                var uploaded = 0L
                val segmentSize = 2048L
                var read: Long
                while (src.read(buf, segmentSize).also { read = it } != -1L) {
                    sink.write(buf, read)
                    uploaded += read
                    val prog = uploaded.toFloat() / total.toFloat()
                    kotlinx.coroutines.runBlocking { withContext(Dispatchers.Main) { onProgress(prog) } }
                }
            }
        }

        // FIX: fileBody এর বদলে progressBody use করো — আগে progressBody বানানো হত
        // কিন্তু request এ fileBody পাঠানো হত, তাই progress কখনো update হত না।
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, progressBody)
            .addFormDataPart("upload_preset", CLOUDINARY_UPLOAD_PRESET)
            .build()

        val request = Request.Builder().url(CLOUDINARY_UPLOAD_URL).post(requestBody).build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: return@withContext Triple(null, null, null)

        if (!response.isSuccessful) return@withContext Triple(null, null, null)

        val json = JSONObject(responseBody)
        val url = json.optString("secure_url", null)
        tempFile.delete()
        Triple(url, fileName, mimeType)
    } catch (e: Exception) {
        Triple(null, null, null)
    }
}

fun getFileName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }
} catch (_: Exception) { null }

// ==================== FCM CALL NOTIFICATION ====================
/**
 * Caller → callee-কে FCM data message পাঠায়
 * App বন্ধ থাকলেও কাজ করে (high priority data message)
 */
suspend fun sendFcmCallNotification(
    calleeMobile: String,
    callerMobile: String,
    callerName: String,
    callType: String,
    callId: String,
    db: FirebaseFirestore,
    context: Context
) = withContext(Dispatchers.IO) {
    val TAG = "RasGram_FCM"
    try {
        // ── Step 1: callee FCM token ──────────────────────────────────────────
        android.util.Log.d(TAG, "sendFcmCall → callee=$calleeMobile callId=$callId")
        val calleeDoc = db.collection("chat_users").document(calleeMobile).get().await()
        val fcmToken = calleeDoc.getString("fcmToken")
        if (fcmToken.isNullOrEmpty()) {
            android.util.Log.w(TAG, "fcmToken missing for $calleeMobile — call not sent")
            return@withContext
        }

        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val deliveryMethod = prefs.getString(PREF_CALL_DELIVERY, "fcm") ?: "fcm"
        if (deliveryMethod != "fcm") return@withContext

        // ── Step 2: Service Account JSON ─────────────────────────────────────
        val saJsonStr = prefs.getString(PREF_SA_JSON, "")
        val saJson = if (saJsonStr.isNullOrEmpty()) {
            val resId = context.resources.getIdentifier("service_account", "raw", context.packageName)
            if (resId == 0) {
                android.util.Log.e(TAG, "service_account.json raw resource not found")
                return@withContext
            }
            org.json.JSONObject(context.resources.openRawResource(resId).bufferedReader().readText())
        } else {
            org.json.JSONObject(saJsonStr)
        }

        // ── Step 3: JWT + OAuth2 access token ────────────────────────────────
        val privateKeyPem = saJson.getString("private_key")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\n", "")
            .replace("\n", "")
            .trim()
        val keyBytes = android.util.Base64.decode(privateKeyPem, android.util.Base64.DEFAULT)
        val privateKey = java.security.KeyFactory.getInstance("RSA")
            .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(keyBytes))

        val projectId   = saJson.getString("project_id")
        val clientEmail = saJson.getString("client_email")
        val now         = System.currentTimeMillis() / 1000
        val scope       = "https://www.googleapis.com/auth/firebase.messaging"

        val header = android.util.Base64.encodeToString(
            """{"alg":"RS256","typ":"JWT"}""".toByteArray(),
            android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
        )
        val claims = android.util.Base64.encodeToString(
            """{"iss":"$clientEmail","scope":"$scope","aud":"https://oauth2.googleapis.com/token","iat":$now,"exp":${now + 3600}}""".toByteArray(),
            android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
        )
        val toSign = "$header.$claims"
        val signer = java.security.Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(toSign.toByteArray())
        }
        val jwt = "$toSign.${android.util.Base64.encodeToString(signer.sign(), android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)}"

        // FIX: explicit timeouts — Android 15 এ default OkHttpClient এ
        // connectTimeout = 10s কিন্তু readTimeout = 10s যা cellular এ miss করে।
        // 20s/20s দিলে slow network এ reliable।
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val tokenResp = client.newCall(
            okhttp3.Request.Builder()
                .url("https://oauth2.googleapis.com/token")
                .post(okhttp3.FormBody.Builder()
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                    .add("assertion", jwt).build())
                .build()
        ).execute()

        val tokenBody = tokenResp.body?.string() ?: ""
        val accessToken = org.json.JSONObject(tokenBody).optString("access_token", "")
        if (accessToken.isEmpty()) {
            android.util.Log.e(TAG, "OAuth2 token exchange failed. HTTP ${tokenResp.code} body=$tokenBody")
            return@withContext
        }
        android.util.Log.d(TAG, "OAuth2 token OK, sending FCM…")

        // ── Step 4: FCM v1 data + notification message ───────────────────────
        // notification block: app closed/killed থাকলেও system নিজেই notification দেখায়।
        // data block: app foreground/background থাকলে onMessageReceived() এ যায়।
        // HIGH priority + ttl 60s: Doze wakeup করে, 1-minute expiry (stale ring নেই)।
        val callTitle = if (callType == "video") "📹 Incoming Video Call" else "📞 Incoming Voice Call"
        val fcmPayload = org.json.JSONObject().apply {
            put("message", org.json.JSONObject().apply {
                put("token", fcmToken)
                put("data", org.json.JSONObject().apply {
                    put("type", "incoming_call")
                    put("callerName", callerName)
                    put("callerMobile", callerMobile)
                    put("calleeMobile", calleeMobile)
                    put("callType", callType)
                    put("callId", callId)
                    put("direct_boot_ok", "true")
                })
                // notification block: app killed হলেও system tray এ দেখায়
                put("notification", org.json.JSONObject().apply {
                    put("title", callTitle)
                    put("body", "$callerName · $callerMobile")
                })
                put("android", org.json.JSONObject().apply {
                    put("priority", "HIGH")
                    put("ttl", "60s")
                    put("direct_boot_ok", true)
                    // notification channel: IMPORTANCE_MAX, CATEGORY_CALL
                    put("notification", org.json.JSONObject().apply {
                        put("channel_id", "CALL_CHANNEL")
                        put("sound", "default")
                        put("default_vibrate_timings", true)
                        put("notification_priority", "PRIORITY_MAX")
                        put("visibility", "PUBLIC")
                    })
                })
            })
        }.toString()

        val fcmResp = client.newCall(
            okhttp3.Request.Builder()
                .url("https://fcm.googleapis.com/v1/projects/$projectId/messages:send")
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), fcmPayload))
                .build()
        ).execute()

        val fcmRespBody = fcmResp.body?.string() ?: ""
        if (fcmResp.isSuccessful) {
            android.util.Log.i(TAG, "FCM call sent OK → $calleeMobile")
        } else {
            // FIX: এটাই Android 15 এর সমস্যার root cause বের করবে।
            // আগে catch এ চুপ হয়ে যেত, এখন logcat এ দেখা যাবে।
            android.util.Log.e(TAG, "FCM send failed HTTP ${fcmResp.code}: $fcmRespBody")
        }
    } catch (e: Exception) {
        // আগে: catch (_: Exception) { } — সম্পূর্ণ silent, debug অসম্ভব।
        // এখন: logcat এ দেখা যাবে — Android 15 এ কী ভাঙছে জানা যাবে।
        android.util.Log.e(TAG, "sendFcmCallNotification exception: ${e.javaClass.simpleName}: ${e.message}", e)
    }
}
// ==================== SEND MESSAGE ====================
fun sendMessage(
    db: FirebaseFirestore,
    chatId: String,
    senderMobile: String,
    senderName: String,
    receiverMobile: String,
    text: String,
    context: Context,
    fileUrl: String? = null,
    fileName: String? = null,
    fileType: String? = null,
    replyToId: String? = null,
    replyToText: String? = null,
    replyToSender: String? = null,
    duration: Int = 0
) {
    val encryptedText = AESCrypto.encrypt(chatId, text)
    val encryptedReply = replyToText?.let { AESCrypto.encrypt(chatId, it) }

    val now = System.currentTimeMillis()
    // WhatsApp-style time format (e.g., "10:30 AM", "Yesterday", "12/07/25")
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = now
    val timeString = when {
        // Today: Show time like "10:30 AM"
        isToday(now) -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(now))
        // Yesterday: Show "Yesterday"
        isYesterday(now) -> "Yesterday"
        // This week: Show day name like "Monday"
        isThisWeek(now) -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(now))
        // This year: Show date like "12/07/25"
        isThisYear(now) -> SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(Date(now))
        // Older: Show full date
        else -> SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(now))
    }

    // ── WhatsApp Optimistic Send: Room এ আগে save, তারপর Firestore ──────────
    // এতে message send করার সাথে সাথে UI তে দেখা যায় — network wait নেই
    val tempId = "pending_${now}_${senderMobile.takeLast(4)}"

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
        // Room এ pending message save করো (instant UI update)
        val repo = RasGramRepository.getInstance(context)
        repo.messageDao.upsertMessage(
            CachedMessage(
                id = tempId,
                chatId = chatId,
                text = text,                    // plain text (already decrypted form)
                senderMobile = senderMobile,
                receiverMobile = receiverMobile,
                timestamp = now,
                timeString = timeString,
                fileUrl = fileUrl,
                fileName = fileName,
                fileType = fileType,
                read = false,
                delivered = false,
                isPending = true,               // ✓ = sending... indicator
                replyToId = replyToId,
                replyToText = replyToText,
                replyToSender = replyToSender,
                duration = duration
            )
        )

        // Chat preview update (last message)
        repo.chatPreviewDao.upsertPreview(
            CachedChatPreview(
                contactMobile = receiverMobile,
                contactName = "",               // contact name Firestore থেকে already cached
                lastMessageText = text,
                lastMessageSender = senderMobile,
                lastTimestamp = now,
                lastTimeString = timeString,
                lastFileType = fileType
            )
        )
    }

    // Firestore এ পাঠাও (background)
    val message = hashMapOf(
        "text" to encryptedText,
        "senderMobile" to senderMobile,
        "receiverMobile" to receiverMobile,
        "timestamp" to now,
        "timeString" to timeString,
        "fileUrl" to fileUrl,
        "fileName" to fileName,
        "fileType" to fileType,
        "reaction" to null,
        "read" to false,
        "delivered" to false,
        "isCallLog" to false,
        "isDeleted" to false,
        "isForwarded" to false,
        "isStarred" to false,
        "replyToId" to replyToId,
        "replyToText" to encryptedReply,
        "replyToSender" to replyToSender,
        "duration" to duration
    )
    db.collection("pvt_msg_$chatId").add(message)
        .addOnSuccessListener { docRef ->
            // Firestore confirm → pending temp message replace করো real ID দিয়ে
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                val repo = RasGramRepository.getInstance(context)
                // FIX: deleteMessage() physically removes the temp row.
                // softDelete() was setting isDeleted=true which kept the row visible
                // as a "🚫 This message was deleted" ghost bubble. Since the real message
                // arrives via Firestore snapshot listener with a real ID, we just need
                // the temp row gone completely — no ghost, no double bubble.
                repo.messageDao.deleteMessage(tempId)
                // Firestore snapshot listener will upsert the real message with its Firestore ID.
            }
        }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    kotlinx.coroutines.GlobalScope.launch {
        sendFcmMessageNotification(
            receiverMobile = receiverMobile,
            senderMobile = senderMobile,
            senderName = senderName,
            messageText = if (text.isNotBlank()) text else if (fileType?.startsWith("image") == true) "📷 Image" else if (fileType?.startsWith("video") == true) "📹 Video" else if (fileType?.startsWith("audio") == true) "🎵 Voice Message" else "📎 File",
            db = db,
            context = context
        )
    }
}

// Helper functions for WhatsApp-style date formatting
fun isToday(timestamp: Long): Boolean {
    val today = Calendar.getInstance()
    today.set(Calendar.HOUR_OF_DAY, 0)
    today.set(Calendar.MINUTE, 0)
    today.set(Calendar.SECOND, 0)
    today.set(Calendar.MILLISECOND, 0)
    return timestamp >= today.timeInMillis
}

fun isYesterday(timestamp: Long): Boolean {
    val yesterday = Calendar.getInstance()
    yesterday.add(Calendar.DAY_OF_YEAR, -1)
    yesterday.set(Calendar.HOUR_OF_DAY, 0)
    yesterday.set(Calendar.MINUTE, 0)
    yesterday.set(Calendar.SECOND, 0)
    yesterday.set(Calendar.MILLISECOND, 0)
    
    val today = Calendar.getInstance()
    today.set(Calendar.HOUR_OF_DAY, 0)
    today.set(Calendar.MINUTE, 0)
    today.set(Calendar.SECOND, 0)
    today.set(Calendar.MILLISECOND, 0)
    
    return timestamp >= yesterday.timeInMillis && timestamp < today.timeInMillis
}

fun isThisWeek(timestamp: Long): Boolean {
    val calendar = Calendar.getInstance()
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    
    val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
    val daysSinceSunday = dayOfWeek - 1
    calendar.add(Calendar.DAY_OF_YEAR, -daysSinceSunday)
    
    return timestamp >= calendar.timeInMillis && !isToday(timestamp) && !isYesterday(timestamp)
}

fun isThisYear(timestamp: Long): Boolean {
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    val messageCalendar = Calendar.getInstance()
    messageCalendar.timeInMillis = timestamp
    return messageCalendar.get(Calendar.YEAR) == currentYear
}

// ==================== HELPER FUNCTIONS ====================
fun generateChatId(mobile1: String, mobile2: String): String =
    if (mobile1 < mobile2) "${mobile1}_${mobile2}" else "${mobile2}_${mobile1}"

// ── LAN helper: Uri → temp File (LAN file send এর জন্য) ─────────────────────
// Cloudinary path এর মতোই — Uri → cache তে temp file → LAN এ send করো
fun uriToTempFile(context: Context, uri: Uri, fileName: String): File? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val tempFile = File(context.cacheDir, "lan_send_${System.currentTimeMillis()}_$fileName")
        tempFile.outputStream().use { output ->
            inputStream.use { input -> input.copyTo(output) }
        }
        if (tempFile.exists() && tempFile.length() > 0) tempFile else null
    } catch (e: Exception) {
        android.util.Log.e("LanSend", "uriToTempFile: ${e.message}")
        null
    }
}

fun getFileTypePreview(message: Message): String = when {
    message.isDeleted -> "ðŸš« This message was deleted"
    message.isCallLog -> "${if (message.callType == "video") "ðŸ“¹" else "ðŸ“ž"} ${message.text}"
    message.fileType?.startsWith("image/") == true -> "ðŸ“· Photo"
    message.fileType?.startsWith("video/") == true -> "ðŸŽ¥ Video"
    message.fileType?.startsWith("audio/") == true -> "ðŸŽ¤ Voice message"
    message.fileType?.contains("pdf") == true -> "ðŸ“„ ${message.fileName ?: "PDF"}"
    message.fileUrl != null -> "ðŸ“Ž ${message.fileName ?: "Document"}"
    else -> message.text
}

fun formatTime(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(mins, secs)
}

fun formatLastSeen(diffMs: Long): String {
    val mins = diffMs / 60_000
    val hours = mins / 60
    val days = hours / 24
    return when {
        mins < 2 -> "just now"
        mins < 60 -> "${mins}m ago"
        hours < 24 -> "${hours}h ago"
        days == 1L -> "yesterday"
        else -> "${days}d ago"
    }
}

fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}

@Composable
fun outlinedFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = RasGramTheme.Green,
    unfocusedBorderColor = RasGramTheme.Border,
    focusedTextColor = RasGramTheme.TextPrimary,
    unfocusedTextColor = RasGramTheme.TextPrimary,
    cursorColor = RasGramTheme.Green,
    focusedLabelColor = RasGramTheme.Green,
    unfocusedLabelColor = RasGramTheme.TextMuted,
    focusedContainerColor = RasGramTheme.InputBg,
    unfocusedContainerColor = RasGramTheme.InputBg
)

fun Modifier.rightBorder(width: Dp, color: Color): Modifier = this.then(
    Modifier.drawBehind {
        drawLine(color = color, start = Offset(size.width, 0f), end = Offset(size.width, size.height), strokeWidth = width.toPx())
    }
)

@Composable
fun isCompactScreen(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.screenWidthDp < 600
}


// ==================== CONTACT SYNC HELPERS ====================

/**
 * Device এর phonebook থেকে সব phone number পড়ে নেয়
 */
fun getDeviceContactNumbers(context: Context): Set<String> {
    val numbers = mutableSetOf<String>()
    try {
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )
        cursor?.use {
            val col = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val raw = it.getString(col) ?: continue
                val normalized = normalizeNumber(raw)
                if (normalized.isNotEmpty()) numbers.add(normalized)
            }
        }
    } catch (_: Exception) { }
    return numbers
}

/**
 * Phone number normalize করে Firebase format এ আনে
 * Bangladesh: 01XXXXXXXXX → 8801XXXXXXXXX
 */
fun normalizeNumber(raw: String): String {
    val digits = raw.replace(Regex("[^0-9]"), "")
    return when {
        digits.startsWith("880") && digits.length >= 12 -> digits
        digits.startsWith("0") && digits.length == 11 -> "880${digits.substring(1)}"
        digits.length == 10 && digits.startsWith("1") -> "880$digits"
        else -> digits.takeLast(11).let { tail ->
            if (tail.startsWith("1") && tail.length == 11) "880${tail.substring(1)}"
            else digits
        }
    }
}
// ── IR bytecode overflow fix ─────────────────────────────────────────────────
// Extracted from LaunchedEffect(peerConnectionFactory.value) in CallingScreen.
// Kotlin 2.1.x FixStackAnalyzer crashes when a single suspend lambda exceeds
// the JVM 64KB bytecode limit (instruction #346 NullPointerException).
// ── Signaling helpers — extracted from CallingScreen LaunchedEffect(peerConnection, isReceiver) ──
// Each SdpObserver anonymous object + nested coroutine block was pushing that single
// suspend lambda past the JVM 64KB method bytecode limit, causing Kotlin 2.1.x IR crash
// (FixStackAnalyzer NullPointerException at instruction #346).
// Extracting them to named top-level functions puts their bytecode in separate class files.

suspend fun handleReceiverSignaling(
    pc: PeerConnection,
    db: FirebaseFirestore,
    callId: String,
    callType: String,
    scope: kotlinx.coroutines.CoroutineScope,
    setCallStatus: (String) -> Unit,
    onEndCall: () -> Unit
) {
    setCallStatus("Connecting...")
    val callDoc = db.collection("calls").document(callId).get().await()
    val offerMap = callDoc.data?.get("offer") as? Map<*, *>
        ?: run { onEndCall(); return }
    val offerSdp = offerMap["sdp"] as? String
        ?: run { onEndCall(); return }

    // ── FIX: caller_ice listener আগে attach করো — BEFORE setRemoteDescription ──
    // Bug (আগে): listener শুধু setLocalDescription.onSetSuccess callback এর ভেতরে attach হত।
    // Race condition: caller ICE candidates Firestore-এ আসে offer post করার সাথে সাথেই।
    // Receiver এর setRemoteDescription + createAnswer + setLocalDescription async chain
    // শেষ হওয়ার আগেই caller এর candidates চলে আসত — সব miss হত।
    // Fix: listener আগে attach করো। Remote description set হওয়ার আগে আসা candidates
    // WebRTC নিজেই queue করে রাখে এবং remote set হলে apply করে।
    var remoteDescriptionSet = false
    val pendingCallerCandidates = mutableListOf<IceCandidate>()

    db.collection("calls").document(callId)
        .collection("caller_ice")
        .addSnapshotListener { snap, _ ->
            snap?.documentChanges?.forEach { change ->
                if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                    val d = change.document.data
                    val candidate = IceCandidate(
                        d["sdpMid"] as? String ?: "",
                        (d["sdpMLineIndex"] as? Long)?.toInt() ?: 0,
                        d["candidate"] as? String ?: ""
                    )
                    if (remoteDescriptionSet) {
                        // Remote description ready — সরাসরি add করো
                        pc.addIceCandidate(candidate)
                    } else {
                        // Remote description এখনো set হয়নি — queue করে রাখো
                        synchronized(pendingCallerCandidates) {
                            pendingCallerCandidates.add(candidate)
                        }
                    }
                }
            }
        }

    pc.setRemoteDescription(object : SdpObserver {
        override fun onCreateSuccess(s: SessionDescription?) {}
        override fun onSetSuccess() {
            // Remote description set হয়েছে — pending candidates apply করো
            remoteDescriptionSet = true
            synchronized(pendingCallerCandidates) {
                pendingCallerCandidates.forEach { pc.addIceCandidate(it) }
                pendingCallerCandidates.clear()
            }

            val answerConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                if (callType == "video") mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            }
            pc.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let { s ->
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(s2: SessionDescription?) {}
                            override fun onSetSuccess() {
                                scope.launch {
                                    db.collection("calls").document(callId).update(
                                        "status", "answered",
                                        "answer", mapOf("type" to s.type.canonicalForm(), "sdp" to s.description)
                                    )
                                    setCallStatus("Connecting...")
                                }
                            }
                            override fun onCreateFailure(e: String?) {}
                            override fun onSetFailure(e: String?) {}
                        }, s)
                    }
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(e: String?) {}
                override fun onSetFailure(e: String?) {}
            }, answerConstraints)
        }
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }, SessionDescription(SessionDescription.Type.OFFER, offerSdp))

    db.collection("calls").document(callId).addSnapshotListener { snapshot, _ ->
        val status = snapshot?.data?.get("status") as? String ?: return@addSnapshotListener
        if (status == "ended" || status == "rejected") scope.launch { onEndCall() }
    }
}

suspend fun handleCallerSignaling(
    pc: PeerConnection,
    db: FirebaseFirestore,
    callId: String,
    callType: String,
    currentUser: User,
    contact: User,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    setCallStatus: (String) -> Unit,
    onEndCall: () -> Unit
) {
    val offerConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        if (callType == "video") mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
    }

    pc.createOffer(object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {
            sdp?.let { s ->
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(s2: SessionDescription?) {}
                    override fun onSetSuccess() {
                        // FIX: Android 15 deadlock — এই callback WebRTC internal
                        // thread এ আসে। scope.launch করলে Compose Main dispatcher
                        // এ যায়, কিন্তু sendFcmCallNotification এর ভেতরে
                        // withContext(Dispatchers.IO) করার সময় Firestore internal
                        // executor কে block করে → .get().await() কখনো return করে না।
                        //
                        // Fix: Dispatchers.IO explicitly দিয়ে launch করো।
                        // Firestore এবং OkHttp দুটোই IO dispatcher এ নিরাপদ।
                        // callId capture করা হলো — SdpObserver callback এ
                        // outer scope এর mutable var access unsafe।
                        val capturedCallId = callId
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                db.collection("calls").document(capturedCallId).set(hashMapOf(
                                    "caller" to currentUser.mobile,
                                    "callerName" to currentUser.name,
                                    "callee" to contact.mobile,
                                    "type" to callType, "status" to "calling",
                                    "timestamp" to System.currentTimeMillis(),
                                    "offer" to mapOf("type" to s.type.canonicalForm(), "sdp" to s.description)
                                )).await()
                            } catch (e: Exception) {
                                android.util.Log.e("RasGram_Call", "Firestore call doc set failed: ${e.message}")
                                return@launch
                            }
                            sendFcmCallNotification(
                                calleeMobile = contact.mobile,
                                callerMobile = currentUser.mobile,
                                callerName = currentUser.name,
                                callType = callType,
                                callId = capturedCallId,
                                db = db,
                                context = context
                            )
                        }
                    }
                    override fun onCreateFailure(e: String?) {}
                    override fun onSetFailure(e: String?) {}
                }, s)
            }
        }
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }, offerConstraints)

    db.collection("calls").document(callId).addSnapshotListener { snapshot, _ ->
        val data = snapshot?.data ?: return@addSnapshotListener
        when (data["status"] as? String) {
            "answered" -> {
                (data["answer"] as? Map<*, *>)?.let { ans ->
                    val sdpStr = ans["sdp"] as? String ?: return@addSnapshotListener

                    // ── FIX: callee_ice listener আগে attach করো — BEFORE setRemoteDescription ──
                    // Bug (আগে): listener শুধু setRemoteDescription.onSetSuccess এর ভেতরে attach হত।
                    // Race: receiver answer post করার সাথে সাথে callee ICE candidates ও Firestore-এ আসে।
                    // Caller এর setRemoteDescription async callback শেষ হওয়ার আগেই miss হত।
                    // Fix: listener আগে attach করো, remote set হলে pending candidates flush করো।
                    var remoteAnswerSet = false
                    val pendingCalleeCandidates = mutableListOf<IceCandidate>()

                    db.collection("calls").document(callId)
                        .collection("callee_ice")
                        .addSnapshotListener { snap, _ ->
                            snap?.documentChanges?.forEach { change ->
                                if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                                    val d = change.document.data
                                    val candidate = IceCandidate(
                                        d["sdpMid"] as? String ?: "",
                                        (d["sdpMLineIndex"] as? Long)?.toInt() ?: 0,
                                        d["candidate"] as? String ?: ""
                                    )
                                    if (remoteAnswerSet) {
                                        pc.addIceCandidate(candidate)
                                    } else {
                                        synchronized(pendingCalleeCandidates) {
                                            pendingCalleeCandidates.add(candidate)
                                        }
                                    }
                                }
                            }
                        }

                    pc.setRemoteDescription(object : SdpObserver {
                        override fun onCreateSuccess(s: SessionDescription?) {}
                        override fun onSetSuccess() {
                            // Remote answer set হয়েছে — pending candidates flush করো
                            remoteAnswerSet = true
                            synchronized(pendingCalleeCandidates) {
                                pendingCalleeCandidates.forEach { pc.addIceCandidate(it) }
                                pendingCalleeCandidates.clear()
                            }
                            scope.launch { setCallStatus("Connecting...") }
                        }
                        override fun onCreateFailure(e: String?) {}
                        override fun onSetFailure(e: String?) {}
                    }, SessionDescription(SessionDescription.Type.ANSWER, sdpStr))
                }
            }
            "ended", "rejected" -> scope.launch { onEndCall() }
        }
    }
}

// Putting the PeerConnection.Observer in a separate top-level function causes
// the compiler to emit its bytecode in a distinct class file, keeping the
// calling LaunchedEffect lambda small enough to compile cleanly.
fun buildPeerConnectionObserver(
    isReceiver:           Boolean,
    scope:                kotlinx.coroutines.CoroutineScope,
    db:                   FirebaseFirestore,
    getCallId:            () -> String,
    setCallStatus:        (String) -> Unit,
    setIsConnected:       (Boolean) -> Unit,
    getIceDisconnectJob:  () -> kotlinx.coroutines.Job?,
    setIceDisconnectJob:  (kotlinx.coroutines.Job?) -> Unit,
    onEndCall:            () -> Unit,
    getRemoteVideoTrack:  () -> VideoTrack?,
    setRemoteVideoTrack:  (VideoTrack) -> Unit,
    getRemoteSurfaceView: () -> SurfaceViewRenderer?
): PeerConnection.Observer = object : PeerConnection.Observer {
    override fun onIceCandidate(candidate: IceCandidate?) {
        candidate?.let { c ->
            scope.launch {
                val iceCollection = if (isReceiver) "callee_ice" else "caller_ice"
                db.collection("calls").document(getCallId()).collection(iceCollection).add(
                    mapOf("sdpMid" to c.sdpMid, "sdpMLineIndex" to c.sdpMLineIndex, "candidate" to c.sdp)
                )
            }
        }
    }
    override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
    override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED -> scope.launch {
                getIceDisconnectJob()?.cancel()
                setIceDisconnectJob(null)
                setCallStatus("Connected")
                setIsConnected(true)
            }
            PeerConnection.IceConnectionState.DISCONNECTED -> scope.launch {
                if (getIceDisconnectJob()?.isActive != true) {
                    setCallStatus("Reconnecting...")
                    // FIX: 8s → 15s — mobile data network switch (WiFi ↔ data) এ
                    // ICE restart নিতে 10-12s পর্যন্ত সময় লাগতে পারে।
                    // 8s এ prematurely end হয়ে যাচ্ছিল।
                    setIceDisconnectJob(scope.launch {
                        kotlinx.coroutines.delay(15_000L)
                        onEndCall()
                    })
                }
            }
            PeerConnection.IceConnectionState.FAILED -> scope.launch {
                getIceDisconnectJob()?.cancel()
                onEndCall()
            }
            else -> {}
        }
    }
    override fun onIceConnectionReceivingChange(b: Boolean) {}
    override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
    override fun onAddStream(s: MediaStream?) {
        s?.videoTracks?.firstOrNull()?.let { track ->
            setRemoteVideoTrack(track)
            getRemoteSurfaceView()?.let { track.addSink(it) }
        }
    }
    override fun onRemoveStream(s: MediaStream?) {}
    override fun onDataChannel(d: DataChannel?) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(r: RtpReceiver?, streams: Array<out MediaStream>?) {
        r?.track()?.let { track ->
            if (track is VideoTrack) {
                setRemoteVideoTrack(track)
                getRemoteSurfaceView()?.let { track.addSink(it) }
            }
        }
    }
}

fun getVideoCapturer(context: Context): VideoCapturer? = try {
    val e2 = Camera2Enumerator(context)
    e2.deviceNames.firstOrNull { e2.isFrontFacing(it) }?.let { e2.createCapturer(it, null) }
} catch (_: Exception) {
    try {
        val e1 = Camera1Enumerator(false)
        e1.deviceNames.firstOrNull { e1.isFrontFacing(it) }?.let { e1.createCapturer(it, null) }
    } catch (_: Exception) { null }
}


// ==================== STATUS VIEWER SCREEN ====================
@Composable
fun StatusViewerScreen(
    currentUserMobile: String,
    statuses: List<Status>,
    initialIndex: Int,
    onClose: () -> Unit
) {
    val db = remember { FirebaseFirestore.getInstance() }
    var currentIndex by remember { mutableIntStateOf(initialIndex) }
    if (currentIndex >= statuses.size || currentIndex < 0) {
        onClose()
        return
    }
    
    val currentStatus = statuses[currentIndex]
    var progress by remember { mutableFloatStateOf(0f) }
    
    LaunchedEffect(currentIndex) {
        // Mark as viewed
        if (currentUserMobile !in currentStatus.viewedBy) {
            db.collection("statuses").document(currentStatus.id).update(
                "viewedBy", com.google.firebase.firestore.FieldValue.arrayUnion(currentUserMobile)
            )
        }
        
        progress = 0f
        val duration = 5000L // 5 seconds per image status
        val interval = 50L
        while (progress < 1f) {
            delay(interval)
            progress += interval.toFloat() / duration
        }
        currentIndex++
    }
    
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AsyncImage(
            model = currentStatus.mediaUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        
        // Progress bars
        Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 8.dp, end = 8.dp).statusBarsPadding()) {
            statuses.forEachIndexed { index, status ->
                val p = when {
                    index < currentIndex -> 1f
                    index == currentIndex -> progress
                    else -> 0f
                }
                LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier.weight(1f).padding(horizontal = 2.dp).height(3.dp).clip(RoundedCornerShape(1.dp)),
                    color = Color.White,
                    trackColor = Color.Gray.copy(alpha = 0.5f)
                )
            }
        }
        
        // Header
        Row(modifier = Modifier.fillMaxWidth().padding(top = 40.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
            AsyncImage(
                model = currentStatus.userAvatar.ifEmpty { "https://ui-avatars.com/api/?name=${currentStatus.userName.replace(" ", "+")}&background=008069&color=fff" },
                contentDescription = null,
                modifier = Modifier.size(40.dp).clip(CircleShape),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(currentStatus.userName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(formatLastSeen(System.currentTimeMillis() - currentStatus.timestamp), color = Color.LightGray, fontSize = 13.sp)
            }
        }
        
        // Tap areas for navigation
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {
                if (currentIndex > 0) currentIndex-- else progress = 0f
            })
            Box(modifier = Modifier.weight(1f).fillMaxHeight().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {
                currentIndex++
            })
        }
    }
}
@Composable
fun EncryptionNotice() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            color = Color(0xFF1E2B30),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Messages and calls are end-to-end encrypted.", color = Color(0xFFFFD54F), fontSize = 11.sp, textAlign = TextAlign.Center)
            }
        }
    }
}
// ==================== GROUP CHAT AREA ====================
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupChatArea(
    currentUser: User,
    group: Group,
    onBack: () -> Unit
) {
    val db = remember { FirebaseFirestore.getInstance() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isCompact = isCompactScreen()
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var groupMessagesLoaded by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    
    var isUploading by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableFloatStateOf(0f) }
    var replyToMessage by remember { mutableStateOf<Message?>(null) }
    var selectedMessages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }

    // Group members cache for name mapping
    var membersMap by remember { mutableStateOf<Map<String, User>>(emptyMap()) }

    // File launchers
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    var uploadingFileName by remember { mutableStateOf("") }

    // Image/Video launcher
    val groupImageVideoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val name = getFileName(context, it) ?: "media_${System.currentTimeMillis()}"
            uploadingFileName = name
            isUploading = true
            scope.launch {
                try {
                    val (url, fileName, fileType) = uploadToCloudinary(context, it) { prog -> uploadProgress = prog }
                    if (url != null) {
                        val now = System.currentTimeMillis()
                        val msgMap = hashMapOf("text" to "", "senderMobile" to currentUser.mobile,
                            "fileUrl" to url, "fileName" to (fileName ?: name), "fileType" to (fileType ?: "image/*"),
                            "timestamp" to now, "timeString" to java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(now)))
                        db.collection("groups").document(group.id).collection("messages").add(msgMap)
                        db.collection("groups").document(group.id).update("lastMessageTime", now)
                    } else Toast.makeText(context, "আপলোড ব্যর্থ", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
                isUploading = false; uploadProgress = 0f; uploadingFileName = ""
            }
        }
    }

    // Document / Audio / Any-file launcher — folder option ও এটাই ব্যবহার করে
    val groupDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val name = getFileName(context, it) ?: "file_${System.currentTimeMillis()}"
            uploadingFileName = name
            isUploading = true
            scope.launch {
                try {
                    val mimeType = context.contentResolver.getType(it) ?: "application/octet-stream"
                    val (url, fileName, fileType) = uploadToCloudinary(context, it) { prog -> uploadProgress = prog }
                    if (url != null) {
                        val now = System.currentTimeMillis()
                        val msgMap = hashMapOf("text" to "", "senderMobile" to currentUser.mobile,
                            "fileUrl" to url, "fileName" to (fileName ?: name), "fileType" to (fileType ?: mimeType),
                            "timestamp" to now, "timeString" to java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(now)))
                        db.collection("groups").document(group.id).collection("messages").add(msgMap)
                        db.collection("groups").document(group.id).update("lastMessageTime", now)
                    } else Toast.makeText(context, "আপলোড ব্যর্থ", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
                isUploading = false; uploadProgress = 0f; uploadingFileName = ""
            }
        }
    }

    LaunchedEffect(group.id) {
        if (group.members.isNotEmpty()) {
            db.collection("chat_users").whereIn("mobile", group.members.take(10)).get().addOnSuccessListener { snap ->
                val map = mutableMapOf<String, User>()
                snap.documents.forEach { doc ->
                    doc.data?.let { d ->
                        val u = User(
                            uid = doc.id, name = d["name"] as? String ?: "",
                            mobile = d["mobile"] as? String ?: "", avatarUrl = d["avatarUrl"] as? String ?: ""
                        )
                        map[u.mobile] = u
                    }
                }
                membersMap = map
            }
        }

        db.collection("groups").document(group.id).collection("messages")
            .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.ASCENDING)
            .addSnapshotListener { snap, _ ->
                snap?.documents?.mapNotNull { doc ->
                    doc.data?.let { d ->
                        Message(
                            id = doc.id,
                            text = AESCrypto.decrypt(group.id, d["text"] as? String ?: ""),
                            senderMobile = d["senderMobile"] as? String ?: "",
                            receiverMobile = group.id,
                            timestamp = d["timestamp"] as? Long ?: 0,
                            timeString = d["timeString"] as? String ?: "",
                            fileUrl = d["fileUrl"] as? String,
                            fileName = d["fileName"] as? String,
                            fileType = d["fileType"] as? String,
                            fileSizeBytes = d["fileSizeBytes"] as? Long ?: 0,
                            reaction = d["reaction"] as? String,
                            isDeleted = d["isDeleted"] as? Boolean ?: false,
                            replyToId = d["replyToId"] as? String,
                            replyToText = d["replyToText"]?.let { AESCrypto.decrypt(group.id, it as String) },
                            replyToSender = d["replyToSender"] as? String,
                            duration = (d["duration"] as? Long)?.toInt() ?: 0
                        )
                    }
                }?.also { msgs ->
                    messages = msgs
                    groupMessagesLoaded = true
                }
            }
    }

    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
    LaunchedEffect(isRecording) {
        if (isRecording) { recordingSeconds = 0; while (isRecording) { delay(1000); recordingSeconds++ } }
    }

    BackHandler(enabled = selectedMessages.isNotEmpty()) { selectedMessages = emptySet() }

    Column(modifier = Modifier.fillMaxSize().background(RasGramTheme.DarkBackground).statusBarsPadding().navigationBarsPadding().imePadding()) {
        if (selectedMessages.isNotEmpty()) {
            SelectionHeader(
                count = selectedMessages.size,
                onClose = { selectedMessages = emptySet() },
                onDelete = {
                    scope.launch {
                        selectedMessages.forEach { id ->
                            db.collection("groups").document(group.id).collection("messages").document(id).update("isDeleted", true, "text", "")
                        }
                        selectedMessages = emptySet()
                    }
                },
                onForward = { }, onStar = { },
                onCopy = {
                    val text = messages.filter { it.id in selectedMessages }.joinToString("\n") { it.text }
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("messages", text))
                    selectedMessages = emptySet()
                }
            )
        } else {
            Surface(modifier = Modifier.fillMaxWidth(), color = RasGramTheme.DarkPanel, shadowElevation = 4.dp) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isCompact) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = RasGramTheme.TextPrimary) } }
                    AsyncImage(
                        model = group.avatarUrl.ifEmpty { "https://ui-avatars.com/api/?name=${group.name.replace(" ", "+")}&background=005C4B&color=fff&bold=true" },
                        contentDescription = null, modifier = Modifier.size(40.dp).clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(group.name, style = MaterialTheme.typography.titleMedium, color = RasGramTheme.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${group.members.size} members", style = MaterialTheme.typography.bodySmall, color = RasGramTheme.TextMuted)
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (!groupMessagesLoaded) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(8) { index -> MessageSkeletonItem(isMe = index % 3 != 0) }
                }
            } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item { EncryptionNotice() }
                messages.forEach { msg ->
                    item(key = msg.id) {
                        val isMe = msg.senderMobile == currentUser.mobile
                        val senderName = if (isMe) "You" else membersMap[msg.senderMobile]?.name ?: msg.senderMobile
                        MessageBubble(
                            message = msg, isMe = isMe, isSelected = msg.id in selectedMessages, senderName = senderName,
                            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); selectedMessages = selectedMessages + msg.id },
                            onClick = { if (selectedMessages.isNotEmpty()) selectedMessages = if (msg.id in selectedMessages) selectedMessages - msg.id else selectedMessages + msg.id },
                            onReact = { rx -> scope.launch { db.collection("groups").document(group.id).collection("messages").document(msg.id).update("reaction", if (msg.reaction == rx) null else rx) } },
                            onReply = { replyToMessage = msg },
                            onDelete = { scope.launch {
                                db.collection("groups").document(group.id).collection("messages").document(msg.id)
                                    .update("isDeleted", true, "text", "", "fileUrl", null, "fileName", null)
                                withContext(Dispatchers.IO) { RasGramRepository.getInstance(context).messageDao.softDelete(msg.id) }
                                if (!msg.fileUrl.isNullOrEmpty()) {
                                    withContext(Dispatchers.IO) {
                                        try {
                                            val pubId = extractCloudinaryPublicId(msg.fileUrl)
                                            if (pubId != null) deleteFromCloudinaryDirect(pubId, cloudinaryResourceType(msg.fileType))
                                        } catch (_: Exception) {}
                                    }
                                }
                            }},
                            onStar = { }, onCopy = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("message", msg.text))
                                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
            } // end else (groupMessagesLoaded)
        }

        if (isUploading) {
            LinearProgressIndicator(progress = { uploadProgress }, modifier = Modifier.fillMaxWidth(), color = RasGramTheme.Green, trackColor = RasGramTheme.DarkPanel)
        }

        replyToMessage?.let { reply ->
            ReplyPreview(message = reply, currentUserMobile = currentUser.mobile, onDismiss = { replyToMessage = null })
        }

        ChatInputBar(
            inputText = inputText,
            onTextChange = { inputText = it },
            onSend = {
                val text = inputText.trim()
                if (text.isNotBlank()) {
                    val encryptedText = AESCrypto.encrypt(group.id, text)
                    val encryptedReply = replyToMessage?.text?.let { AESCrypto.encrypt(group.id, it) }
                    val now = System.currentTimeMillis()
                    val msgMap = hashMapOf(
                        "text" to encryptedText, "senderMobile" to currentUser.mobile,
                        "timestamp" to now, "timeString" to java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(now)),
                        "replyToId" to replyToMessage?.id, "replyToText" to encryptedReply, "replyToSender" to replyToMessage?.senderMobile
                    )
                    db.collection("groups").document(group.id).collection("messages").add(msgMap)
                    db.collection("groups").document(group.id).update("lastMessageTime", now)
                    inputText = ""
                    replyToMessage = null
                }
            },
            onAttachClick = { showAttachMenu = true },
            isRecording = isRecording, recordingSeconds = recordingSeconds,
            onMicPress = {
                val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (!hasPerm) { permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)); return@ChatInputBar }
                if (!isRecording) {
                    try {
                        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
                        recordingFile = file
                        val recorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                            MediaRecorder(context)
                        else
                            @Suppress("DEPRECATION") MediaRecorder()
                        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        recorder.setOutputFile(file.absolutePath)
                        recorder.prepare(); recorder.start()
                        mediaRecorder = recorder
                        isRecording = true
                    } catch (e: Exception) {}
                }
            },
            onMicRelease = {
                if (isRecording) {
                    try {
                        mediaRecorder?.stop(); mediaRecorder?.release(); mediaRecorder = null; isRecording = false
                        val file = recordingFile ?: return@ChatInputBar
                        if (file.exists() && file.length() > 0) {
                            isUploading = true
                            scope.launch {
                                val (url, fileName, _) = uploadToCloudinary(context, file.toUri()) { prog -> uploadProgress = prog }
                                if (url != null) {
                                    val now = System.currentTimeMillis()
                                    val msgMap = hashMapOf(
                                        "text" to "", "senderMobile" to currentUser.mobile, "fileUrl" to url, "fileName" to (fileName ?: "voice.m4a"),
                                        "fileType" to "audio/mp4", "duration" to recordingSeconds, "timestamp" to now,
                                        "timeString" to java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(now))
                                    )
                                    db.collection("groups").document(group.id).collection("messages").add(msgMap)
                                    db.collection("groups").document(group.id).update("lastMessageTime", now)
                                }
                                isUploading = false; uploadProgress = 0f; file.delete()
                            }
                        }
                    } catch (e: Exception) { isRecording = false }
                }
            },
            onMicCancel = { mediaRecorder?.release(); mediaRecorder = null; isRecording = false; recordingFile?.delete() }
        )

        // Group attach menu — same options as ChatArea
        if (showAttachMenu) {
            EnhancedAttachmentMenuSheet(
                onDismiss = { showAttachMenu = false },
                onImageVideo = { groupImageVideoLauncher.launch(arrayOf("image/*", "video/*")); showAttachMenu = false },
                onDocument = { groupDocLauncher.launch(arrayOf("application/*", "text/*")); showAttachMenu = false },
                onAudio = { groupDocLauncher.launch(arrayOf("audio/*")); showAttachMenu = false },
                onFilesFromFolder = { groupDocLauncher.launch(arrayOf("*/*")); showAttachMenu = false }
            )
        }
    }
}

// ==================== SEND FCM MESSAGE ====================
suspend fun sendFcmMessageNotification(
    receiverMobile: String,
    senderMobile: String,
    senderName: String,
    messageText: String,
    db: FirebaseFirestore,
    context: Context
) = withContext(Dispatchers.IO) {
    try {
        val receiverDoc = db.collection("chat_users").document(receiverMobile).get().await()
        val fcmToken = receiverDoc.getString("fcmToken") ?: return@withContext

        // Message notification সবসময় যাবে — call delivery setting শুধু call এর জন্য
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val saJsonStr = prefs.getString(PREF_SA_JSON, "")
        val saJson = if (saJsonStr.isNullOrEmpty()) {
            val saStream = context.resources.openRawResource(
                context.resources.getIdentifier("service_account", "raw", context.packageName)
            )
            org.json.JSONObject(saStream.bufferedReader().readText())
        } else {
            org.json.JSONObject(saJsonStr)
        }

        val privateKeyPem = saJson.getString("private_key")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\n", "")
            .replace("\n", "")
            .trim()
        val keyBytes = android.util.Base64.decode(privateKeyPem, android.util.Base64.DEFAULT)
        val keySpec = java.security.spec.PKCS8EncodedKeySpec(keyBytes)
        val privateKey = java.security.KeyFactory.getInstance("RSA").generatePrivate(keySpec)

        val projectId = saJson.getString("project_id")
        val clientEmail = saJson.getString("client_email")
        val now = System.currentTimeMillis() / 1000
        val scope = "https://www.googleapis.com/auth/firebase.messaging"

        val header = android.util.Base64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".toByteArray(), android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
        val claim = android.util.Base64.encodeToString(
            "{\"iss\":\"$clientEmail\",\"scope\":\"$scope\",\"aud\":\"https://oauth2.googleapis.com/token\",\"exp\":${now + 3600},\"iat\":$now}".toByteArray(),
            android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
        )
        val signatureBytes = java.security.Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update("$header.$claim".toByteArray())
            sign()
        }
        val signature = android.util.Base64.encodeToString(signatureBytes, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
        val jwt = "$header.$claim.$signature"

        val tokenRequest = okhttp3.Request.Builder()
            .url("https://oauth2.googleapis.com/token")
            .post(okhttp3.FormBody.Builder().add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer").add("assertion", jwt).build())
            .build()

        val tokenResponse = okhttp3.OkHttpClient().newCall(tokenRequest).execute()
        if (!tokenResponse.isSuccessful) return@withContext
        val accessToken = org.json.JSONObject(tokenResponse.body?.string() ?: "").getString("access_token")

        val payload = org.json.JSONObject().apply {
            put("message", org.json.JSONObject().apply {
                put("token", fcmToken)
                put("data", org.json.JSONObject().apply {
                    put("type", "message")
                    put("senderMobile", senderMobile)
                    put("senderName", senderName)
                    put("message", messageText)
                })
                put("android", org.json.JSONObject().apply {
                    put("priority", "high")
                })
            })
        }

        val pushRequest = okhttp3.Request.Builder()
            .url("https://fcm.googleapis.com/v1/projects/$projectId/messages:send")
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Content-Type", "application/json")
            .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), payload.toString()))
            .build()
        okhttp3.OkHttpClient().newCall(pushRequest).execute()
    } catch (_: Exception) { }
}


// ─────────────────────────────────────────────────────────────────────────────
// CLOUDINARY HELPERS — delete for everyone এর জন্য
// ─────────────────────────────────────────────────────────────────────────────

private const val CLD_CLOUD_MOD   = "de2w78yxh"
private const val CLD_API_KEY_MOD = "292749814534824"
private const val CLD_SECRET_MOD  = "EEYmph3nZLR8Modypt0J7eH--58"

fun extractCloudinaryPublicId(url: String?): String? {
    if (url.isNullOrEmpty()) return null
    return try {
        val parts = url.split("/upload/")
        if (parts.size < 2) return null
        val after = parts[1]
        val noVer = if (after.startsWith("v") && after.contains("/")) after.substringAfter("/") else after
        noVer.substringBeforeLast(".")
    } catch (_: Exception) { null }
}

fun cloudinaryResourceType(mimeType: String?): String = when {
    mimeType == null               -> "raw"
    mimeType.startsWith("image/") -> "image"
    mimeType.startsWith("video/") -> "video"
    mimeType.startsWith("audio/") -> "video"
    else                          -> "raw"
}

fun deleteFromCloudinaryDirect(publicId: String, resourceType: String): Boolean = try {
    val ts  = (System.currentTimeMillis() / 1000).toString()
    val sigStr = "public_id=${publicId}&timestamp=${ts}${CLD_SECRET_MOD}"
    val md  = java.security.MessageDigest.getInstance("SHA-1")
    val sig = md.digest(sigStr.toByteArray()).joinToString("") { "%02x".format(it) }
    val body = okhttp3.FormBody.Builder()
        .add("public_id", publicId).add("timestamp", ts)
        .add("api_key", CLD_API_KEY_MOD).add("signature", sig).build()
    val resp = okhttp3.OkHttpClient().newCall(
        okhttp3.Request.Builder()
            .url("https://api.cloudinary.com/v1_1/$CLD_CLOUD_MOD/$resourceType/destroy")
            .post(body).build()
    ).execute()
    val result = org.json.JSONObject(resp.body?.string() ?: "{}").optString("result")
    result == "ok" || result == "not found"
} catch (_: Exception) { false }

// ─────────────────────────────────────────────────────────────────────────────
// LINK DETECTION
// ─────────────────────────────────────────────────────────────────────────────

private val URL_REGEX = Regex("""https?://[^\s<>"']+""")

fun extractUrls(text: String): List<String> = URL_REGEX.findAll(text).map { it.value }.toList()

fun isVideoUrl(url: String): Boolean {
    val lower = url.lowercase()
    return lower.contains("youtu.be") || lower.contains("youtube.com/watch") ||
           lower.contains("youtube.com/shorts") ||
           lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mkv") ||
           (lower.contains("cloudinary.com") && lower.contains("/video/"))
}

data class LinkMeta(
    val url: String,
    val title: String = "",
    val description: String = "",
    val imageUrl: String = "",
    val isVideo: Boolean = false
)

suspend fun fetchLinkMeta(url: String): LinkMeta = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 5000; conn.readTimeout = 5000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (conn.responseCode != 200) return@withContext LinkMeta(url, isVideo = isVideoUrl(url))
        val buf = StringBuilder(); val arr = CharArray(1024); var total = 0
        conn.inputStream.bufferedReader().use { br ->
            while (total < 8000) {
                val r = br.read(arr, 0, minOf(1024, 8000 - total)); if (r == -1) break
                buf.append(arr, 0, r); total += r
            }
        }
        val html = buf.toString()
        val title = Regex("<meta[^>]*property=[\"']og:title[\"'][^>]*content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?: Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1) ?: ""
        val img = Regex("<meta[^>]*property=[\"']og:image[\"'][^>]*content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1) ?: ""
        LinkMeta(url, title.trim(), "", img.trim(), isVideoUrl(url))
    } catch (_: Exception) { LinkMeta(url, isVideo = isVideoUrl(url)) }
}

// ─────────────────────────────────────────────────────────────────────────────
// LINK PREVIEW CARD
// ─────────────────────────────────────────────────────────────────────────────

@androidx.compose.runtime.Composable
fun LinkPreviewCard(url: String, context: android.content.Context, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    var meta    by androidx.compose.runtime.remember(url) { androidx.compose.runtime.mutableStateOf<LinkMeta?>(null) }
    var loading by androidx.compose.runtime.remember(url) { androidx.compose.runtime.mutableStateOf(true) }
    val scope   = androidx.compose.runtime.rememberCoroutineScope()

    androidx.compose.runtime.LaunchedEffect(url) { meta = fetchLinkMeta(url); loading = false }

    if (loading) {
        androidx.compose.foundation.layout.Box(
            modifier.fillMaxWidth().height(44.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .background(androidx.compose.ui.graphics.Color(0xFF1A2C35))
        ); return
    }
    val m = meta ?: return

    androidx.compose.material3.Surface(
        modifier  = modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .clickable { openLinkSmart(context, url) },
        color     = androidx.compose.ui.graphics.Color(0xFF1A2C35),
        shape     = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    ) {
        Column {
            if (m.imageUrl.isNotEmpty()) {
                coil.compose.AsyncImage(
                    model = m.imageUrl, contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (m.isVideo) Icon(Icons.Default.PlayCircleFilled, null, tint = RasGramTheme.Green, modifier = Modifier.size(20.dp))
                else           Icon(Icons.Default.Link, null, tint = RasGramTheme.TextMuted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    if (m.title.isNotEmpty()) Text(m.title, color = RasGramTheme.TextPrimary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(url.take(42) + if (url.length > 42) "…" else "", color = RasGramTheme.Green, fontSize = 11.sp, maxLines = 1)
                }
                // Family Browser open button
                IconButton(onClick = { openInFamilyBrowser(context, url) }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.OpenInBrowser, null, tint = RasGramTheme.TextMuted, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}

fun openLinkSmart(context: android.content.Context, url: String) {
    if (isVideoUrl(url)) {
        try {
            val i = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.parse(url), "video/*")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(i)
        } catch (_: Exception) { openInFamilyBrowser(context, url) }
    } else openInFamilyBrowser(context, url)
}

fun openInFamilyBrowser(context: android.content.Context, url: String) {
    try {
        context.startActivity(android.content.Intent().apply {
            setClassName(context.packageName, "com.rasel.RasFocus.familybrowser.FamilyBrowserActivity")
            putExtra("url", url)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (_: Exception) {
        try {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url)).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (_: Exception) {}
    }
}

// ── Recursive DocumentFile zip helper ────────────────────────────────────────
// OpenDocumentTree URI থেকে DocumentFile tree নিয়ে সব sub-folder সহ zip করে।
// entryPath = zip এর ভেতরে file এর path (যেমন "MyFolder/Documents/file.pdf")
// Hidden files (.xxx) skip করা হয়।
fun zipDocumentFile(
    context: android.content.Context,
    doc: androidx.documentfile.provider.DocumentFile,
    entryPath: String,
    zos: java.util.zip.ZipOutputStream
) {
    if (doc.name?.startsWith(".") == true) return  // hidden skip

    if (doc.isDirectory) {
        // Directory entry — zip এ folder marker হিসেবে যোগ করো
        val dirEntry = java.util.zip.ZipEntry("$entryPath/")
        try { zos.putNextEntry(dirEntry); zos.closeEntry() } catch (_: Exception) {}

        // Sub-file ও sub-folder সব recursive process করো
        doc.listFiles().forEach { child ->
            val childPath = "$entryPath/${child.name ?: "unnamed"}"
            zipDocumentFile(context, child, childPath, zos)
        }
    } else {
        // File entry — ContentResolver দিয়ে পড়ো, zip এ লেখো
        try {
            val entry = java.util.zip.ZipEntry(entryPath)
            zos.putNextEntry(entry)
            context.contentResolver.openInputStream(doc.uri)?.use { input ->
                input.copyTo(zos)
            }
            zos.closeEntry()
        } catch (e: Exception) {
            android.util.Log.w("FolderZip", "skip file $entryPath: ${e.message}")
            // একটা file fail করলে বাকিগুলো চলতে থাকে
        }
    }
}


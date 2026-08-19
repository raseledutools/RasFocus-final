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
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
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
    incomingCallType: String? = null
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) }

    val auth = remember { FirebaseAuth.getInstance() }
    var isLoggedIn by remember {
        mutableStateOf(
            prefs.getString(PREF_MOBILE, null) != null &&
            prefs.getString(PREF_UID, null) != null
        )
    }
    var currentUser by remember {
        mutableStateOf(
            if (prefs.getString(PREF_MOBILE, null) != null) User(
                uid = prefs.getString(PREF_UID, "") ?: "",
                name = prefs.getString(PREF_NAME_KEY, "") ?: "",
                mobile = prefs.getString(PREF_MOBILE, "") ?: "",
                avatarUrl = prefs.getString(PREF_AVATAR, "") ?: ""
            ) else null
        )
    }
    var isDarkMode by remember { mutableStateOf(true) }

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
        if (!isLoggedIn || currentUser == null) {
            OtpLoginScreen(
                onLogin = { user ->
                    prefs.edit()
                        .putString(PREF_MOBILE, user.mobile)
                        .putString(PREF_NAME_KEY, user.name)
                        .putString(PREF_UID, user.uid)
                        .putString(PREF_AVATAR, user.avatarUrl)
                        .apply()
                    currentUser = user
                    isLoggedIn = true
                }
            )
        } else {
            MainScreen(
                currentUser = currentUser!!,
                isDarkMode = isDarkMode,
                onToggleTheme = { isDarkMode = !isDarkMode },
                onLogout = {
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
                incomingCallType = incomingCallType
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
                if (!snap.exists()) {
                    docRef.set(hashMapOf(
                        "uid" to uid, "name" to userName, "mobile" to mobile,
                        "avatarUrl" to "", "lastActive" to System.currentTimeMillis(),
                        "typingTo" to null, "statusVisible" to true,
                        "about" to "Hey there! I am using RasGram."
                    )).await()
                } else {
                    docRef.update("lastActive", System.currentTimeMillis(), "uid", uid).await()
                }
                val savedName = snap.getString("name") ?: userName
                
                // Save FCM token after login
                try {
                    com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnSuccessListener { fcmToken ->
                        db.collection("chat_users").document(mobile).update("fcmToken", fcmToken)
                    }
                } catch (_: Exception) { }
                
                onLogin(User(uid = uid, name = savedName, mobile = mobile, avatarUrl = snap.getString("avatarUrl") ?: ""))
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
    incomingCallId: String? = null,
    incomingCallerMobile: String? = null,
    incomingCallerName: String? = null,
    incomingCallType: String? = null
) {
    val context = LocalContext.current
    val db = remember { FirebaseFirestore.getInstance() }

    // Incoming call state — lock screen বা app-open দুটো path থেকেই আসতে পারে
    // Must be declared before any LaunchedEffect that references these variables
    var showIncomingCall by remember { mutableStateOf(incomingCallId != null) }
    var activeIncomingCallId by remember { mutableStateOf(incomingCallId ?: "") }
    var activeIncomingCallerMobile by remember { mutableStateOf(incomingCallerMobile ?: "") }
    var activeIncomingCallerName by remember { mutableStateOf(incomingCallerName ?: "") }
    var activeIncomingCallType by remember { mutableStateOf(incomingCallType ?: "audio") }

    // Request permissions dynamically
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    // Listen for incoming calls when app is open — IncomingCallScreen দেখাও directly
    LaunchedEffect(currentUser.mobile) {
        db.collection("calls")
            .whereEqualTo("callee", currentUser.mobile)
            .whereEqualTo("status", "calling")
            .addSnapshotListener { snapshot, _ ->
                snapshot?.documentChanges?.forEach { change ->
                    if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        val data = change.document.data
                        activeIncomingCallId = change.document.id
                        activeIncomingCallerMobile = data["caller"] as? String ?: ""
                        activeIncomingCallerName = data["callerName"] as? String ?: ""
                        activeIncomingCallType = data["type"] as? String ?: "audio"
                        showIncomingCall = true
                    }
                }
            }
    }
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedContact by remember { mutableStateOf<User?>(null) }
    var selectedGroup by remember { mutableStateOf<Group?>(null) }
    var showCallUI by remember { mutableStateOf(false) }
    var selectedStatusUser by remember { mutableStateOf<List<Status>?>(null) }
    var callType by remember { mutableStateOf("audio") }
    var callContact by remember { mutableStateOf<User?>(null) }
    var liveCurrentUser by remember { mutableStateOf(currentUser) }
    val isCompact = isCompactScreen()

    // Keep user online + sync profile
    LaunchedEffect(currentUser.mobile) {
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
        while (true) {
            db.collection("chat_users").document(currentUser.mobile).update("lastActive", System.currentTimeMillis())
            delay(30_000)
        }
    }

    val inChat = selectedContact != null || selectedGroup != null

    if (isCompact && inChat) {
        // Full screen chat on mobile
        if (selectedContact != null) {
            ChatArea(
                currentUser = liveCurrentUser,
                contact = selectedContact!!,
                onBack = { selectedContact = null },
                onCallClick = { type ->
                    callType = type
                    callContact = selectedContact
                    showCallUI = true
                }
            )
        } else if (selectedGroup != null) {
            GroupChatArea(
                currentUser = liveCurrentUser,
                group = selectedGroup!!,
                onBack = { selectedGroup = null }
            )
        }
    } else {
        Scaffold(
            containerColor = RasGramTheme.DarkBackground,
            bottomBar = {
                if (isCompact) {
                    BottomNavBar(
                        selectedTab = selectedTab,
                        onTabChange = { selectedTab = it }
                    )
                }
            }
        ) { padding ->
            Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                // Left sidebar on tablet
                if (!isCompact) {
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
                            onStatusClick = { selectedStatusUser = it }
                        )
                    }
                } else {
                    SidebarContent(
                        tab = selectedTab,
                        currentUser = liveCurrentUser,
                        selectedContact = selectedContact,
                        onContactSelect = { selectedContact = it },
                        onGroupSelect = { selectedGroup = it },
                        isDarkMode = isDarkMode,
                        onToggleTheme = onToggleTheme,
                        onLogout = onLogout,
                        onUserUpdate = onUserUpdate,
                        onStatusClick = { selectedStatusUser = it },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Chat pane on tablet
                if (!isCompact) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        if (selectedContact != null) {
                            ChatArea(
                                currentUser = liveCurrentUser,
                                contact = selectedContact!!,
                                onBack = { selectedContact = null },
                                onCallClick = { type ->
                                    callType = type
                                    callContact = selectedContact
                                    showCallUI = true
                                }
                            )
                        } else if (selectedGroup != null) {
                            GroupChatArea(
                                currentUser = liveCurrentUser,
                                group = selectedGroup!!,
                                onBack = { selectedGroup = null }
                            )
                        } else {
                            EmptyChatState()
                        }
                    }
                }
            }
        }
    }

    // Outgoing call overlay
    if (showCallUI && callContact != null) {
        CallingScreen(
            currentUser = liveCurrentUser,
            contact = callContact!!,
            callType = callType,
            onEndCall = { showCallUI = false }
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
                // Accept হলে — CallingScreen এর receiver mode তে যাও
                showIncomingCall = false
                // callContact খোঁজো Firebase থেকে; না পেলে placeholder দিয়ে চালাও
                callType = activeIncomingCallType
                callContact = User(
                    uid = "",
                    name = activeIncomingCallerName,
                    mobile = activeIncomingCallerMobile,
                    avatarUrl = ""
                )
                showCallUI = true
            },
            onDecline = {
                showIncomingCall = false
                activeIncomingCallId = ""
            }
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
            modifier = modifier
        )
        1 -> StatusTab(currentUser = currentUser, onStatusClick = onStatusClick, modifier = modifier)
        2 -> CallsTab(currentUser = currentUser, modifier = modifier)
        3 -> GroupsTab(currentUser = currentUser, onGroupSelect = onGroupSelect, modifier = modifier)
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
    modifier: Modifier = Modifier
) {
    val db = remember { FirebaseFirestore.getInstance() }
    var users by remember { mutableStateOf<List<User>>(emptyList()) }
    var latestMessages by remember { mutableStateOf<Map<String, Message>>(emptyMap()) }
    var unreadCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var searchQuery by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showNewGroup by remember { mutableStateOf(false) }
    var showAddContact by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
        }
    }

    // Fast batch latest messages
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
                        }
                    }

                    // Unread count
                    db.collection("pvt_msg_$chatId")
                        .whereEqualTo("senderMobile", user.mobile)
                        .whereEqualTo("read", false)
                        .get().addOnSuccessListener { qs ->
                            unreadCounts = unreadCounts + (user.mobile to qs.size())
                        }
                }
        }
    }

    // WhatsApp style: শুধু phonebook contacts যারা RasGram-এ আছে।
    // contactsLoaded না হওয়া পর্যন্ত phonebook filter skip — সরাসরি chat list দেখাও।
    val filteredUsers = users.filter { user ->
        val isInPhonebook = when {
            !contactsLoaded -> true                              // এখনও load হয়নি → সব দেখাও
            deviceContactNumbers.isEmpty() -> true               // permission deny → fallback সব দেখাও
            else -> deviceContactNumbers.contains(user.mobile)   // normal phonebook filter
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
            if (filteredUsers.isEmpty()) {
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
        modifier = Modifier.fillMaxWidth().background(RasGramTheme.DarkPanel).padding(horizontal = 16.dp).height(60.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text("Ras", style = MaterialTheme.typography.titleLarge, color = RasGramTheme.TextPrimary, fontWeight = FontWeight.ExtraBold)
            Text("Gram", style = MaterialTheme.typography.titleLarge, color = RasGramTheme.Green, fontWeight = FontWeight.ExtraBold)
        }
        IconButton(onClick = onSearchClick) {
            Icon(Icons.Default.Search, null, tint = RasGramTheme.TextMuted)
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
        modifier = Modifier.fillMaxWidth().background(RasGramTheme.DarkPanel).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Default.ArrowBack, null, tint = RasGramTheme.TextMuted)
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Search...", color = RasGramTheme.TextMuted) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = RasGramTheme.TextPrimary,
                unfocusedTextColor = RasGramTheme.TextPrimary,
                cursorColor = RasGramTheme.Green
            )
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Default.Clear, null, tint = RasGramTheme.TextMuted)
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
    onClick: () -> Unit
) {
    val isOnline = user.lastActive > System.currentTimeMillis() - ONLINE_THRESHOLD_MS
    val isTyping = user.typingTo != null

    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = when {
            isSelected -> RasGramTheme.DarkPanel
            else -> Color.Transparent
        }
    ) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            // Avatar + online dot
            Box(modifier = Modifier.size(52.dp)) {
                AsyncImage(
                    model = user.avatarUrl.ifEmpty { "https://ui-avatars.com/api/?name=${user.name.replace(" ", "+")}&background=008069&color=fff&bold=true&size=128" },
                    contentDescription = "Avatar",
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                if (isOnline) {
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
                        user.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = RasGramTheme.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        latestMessage?.timeString ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (unreadCount > 0) RasGramTheme.Green else RasGramTheme.TextMuted,
                        fontSize = 11.sp
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    if (isTyping) {
                        Text("typing...", style = MaterialTheme.typography.bodySmall, color = RasGramTheme.Green, fontWeight = FontWeight.Medium)
                    } else {
                        // Tick icon for sent messages
                        if (latestMessage?.senderMobile == currentUserMobile && latestMessage != null) {
                            Icon(
                                imageVector = when {
                                    latestMessage.isPending -> Icons.Default.AccessTime
                                    latestMessage.read -> Icons.Default.Done
                                    latestMessage.delivered -> Icons.Default.Done
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
                        val previewText = when {
                            latestMessage?.isDeleted == true -> "🚫 This message was deleted"
                            latestMessage?.text?.isNotEmpty() == true -> latestMessage.text
                            latestMessage != null -> getFileTypePreview(latestMessage)
                            else -> "Tap to start chatting"
                        }
                        Text(
                            previewText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (unreadCount > 0 && latestMessage?.senderMobile != currentUserMobile) RasGramTheme.TextPrimary else RasGramTheme.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (unreadCount > 0 && latestMessage?.senderMobile != currentUserMobile) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(shape = CircleShape, color = RasGramTheme.Green) {
                                Text(
                                    if (unreadCount > 99) "99+" else unreadCount.toString(),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Black,
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

    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var inputText by remember { mutableStateOf("") }
    var liveContact by remember { mutableStateOf(contact) }
    var isUploading by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableFloatStateOf(0f) }
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

    // File launchers
    val imageVideoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            isUploading = true
            scope.launch {
                try {
                    val (url, fileName, fileType) = uploadToCloudinary(context, it) { prog -> uploadProgress = prog }
                    if (url != null) {
                        sendMessage(db, chatId, currentUser.mobile, contact.mobile, "", url, fileName, fileType)
                    } else Toast.makeText(context, "Upload failed", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                isUploading = false
                uploadProgress = 0f
            }
        }
    }

    val docLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            isUploading = true
            scope.launch {
                try {
                    val (url, fileName, fileType) = uploadToCloudinary(context, it) { prog -> uploadProgress = prog }
                    if (url != null) {
                        sendMessage(db, chatId, currentUser.mobile, contact.mobile, "", url, fileName, fileType)
                    } else Toast.makeText(context, "Upload failed", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                isUploading = false
                uploadProgress = 0f
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms[Manifest.permission.RECORD_AUDIO] == true) {
            // Start recording
        }
    }

    // Load messages with real-time listener
    LaunchedEffect(chatId) {
        db.collection("pvt_msg_$chatId")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                snapshot?.let { qs ->
                    messages = qs.documents.mapNotNull { doc ->
                        doc.data?.let { d ->
                            Message(
                                id = doc.id,
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
                    // Mark as read
                    qs.documents.filter { doc ->
                        doc.getString("senderMobile") == contact.mobile && doc.getBoolean("read") == false
                    }.forEach { doc ->
                        doc.reference.update("read", true, "delivered", true)
                    }
                }
            }
    }

    // Contact live status
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

    // Auto scroll to bottom
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
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
        sendMessage(
            db, chatId, currentUser.mobile, contact.mobile, text, null, null, null,
            replyToMessage?.id, replyToMessage?.text, replyToMessage?.senderMobile
        )
        inputText = ""
        replyToMessage = null
        typingJob?.cancel()
        scope.launch { db.collection("chat_users").document(currentUser.mobile).update("typingTo", null) }
    }

    BackHandler(enabled = selectedMessages.isNotEmpty()) {
        selectedMessages = emptySet()
    }

    Column(modifier = Modifier.fillMaxSize().background(RasGramTheme.DarkBackground)) {
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
                onCallClick = onCallClick,
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
            // Chat wallpaper pattern (subtle)
            Canvas(modifier = Modifier.fillMaxSize()) {
                // subtle background pattern
            }

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
                                    db.collection("pvt_msg_$chatId").document(message.id)
                                        .update("isDeleted", true, "text", "")
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

        // Upload progress
        if (isUploading) {
            LinearProgressIndicator(
                progress = { uploadProgress },
                modifier = Modifier.fillMaxWidth(),
                color = RasGramTheme.Green,
                trackColor = RasGramTheme.DarkPanel
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
                        val recorder = MediaRecorder()
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
                                val (url, fileName, _) = uploadToCloudinary(context, file.toUri()) { prog -> uploadProgress = prog }
                                if (url != null) {
                                    sendMessage(db, chatId, currentUser.mobile, contact.mobile, "", url, fileName ?: "voice.m4a", "audio/mp4", null, null, null, recordingSeconds)
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
        AttachmentMenuSheet(
            onDismiss = { showAttachMenu = false },
            onImageVideo = { imageVideoLauncher.launch(arrayOf("image/*", "video/*")); showAttachMenu = false },
            onDocument = { docLauncher.launch(arrayOf("*/*")); showAttachMenu = false },
            onAudio = { docLauncher.launch(arrayOf("audio/*")); showAttachMenu = false }
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
    onViewContact: () -> Unit
) {
    val isOnline = contact.lastActive > System.currentTimeMillis() - ONLINE_THRESHOLD_MS
    var showMenu by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxWidth(), color = RasGramTheme.DarkPanel) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(60.dp),
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
                    AsyncImage(
                        model = contact.avatarUrl.ifEmpty { "https://ui-avatars.com/api/?name=${contact.name.replace(" ", "+")}&background=008069&color=fff&bold=true&size=80" },
                        contentDescription = "Avatar",
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                    if (isOnline) {
                        Box(modifier = Modifier.size(10.dp).align(Alignment.BottomEnd).border(2.dp, RasGramTheme.DarkPanel, CircleShape).background(RasGramTheme.OnlineGreen, CircleShape))
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(contact.name, style = MaterialTheme.typography.bodyLarge, color = RasGramTheme.TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            contact.typingTo == currentUserMobile -> "typing..."
                            isOnline -> "online"
                            else -> "last seen ${formatLastSeen(System.currentTimeMillis() - contact.lastActive)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (contact.typingTo == currentUserMobile || isOnline) RasGramTheme.Green else RasGramTheme.TextMuted,
                        fontSize = 11.sp
                    )
                }
            }

            IconButton(onClick = { onCallClick("video") }) {
                Icon(Icons.Default.VideoCall, null, tint = RasGramTheme.TextMuted)
            }
            IconButton(onClick = { onCallClick("audio") }) {
                Icon(Icons.Default.Call, null, tint = RasGramTheme.TextMuted)
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            if (isRecording) {
                // Recording UI
                Surface(
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(24.dp),
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
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("< Slide to cancel", color = RasGramTheme.TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                FloatingActionButton(
                    onClick = onMicRelease,
                    modifier = Modifier.size(48.dp),
                    containerColor = RasGramTheme.Green,
                    elevation = FloatingActionButtonDefaults.elevation(2.dp)
                ) {
                    Icon(Icons.Default.Send, null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
            } else {
                // Emoji + Attach
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    color = RasGramTheme.InputBg
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        IconButton(onClick = { /* emoji picker */ }) {
                            Icon(Icons.Default.EmojiEmotions, null, tint = RasGramTheme.TextMuted)
                        }
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = onTextChange,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 140.dp),
                            placeholder = { Text("Message", color = RasGramTheme.TextMuted) },
                            maxLines = 6,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedTextColor = RasGramTheme.TextPrimary,
                                unfocusedTextColor = RasGramTheme.TextPrimary,
                                cursorColor = RasGramTheme.Green
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { onSend() })
                        )
                        IconButton(onClick = onAttachClick) {
                            Icon(Icons.Default.AttachFile, null, tint = RasGramTheme.TextMuted)
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                AnimatedContent(
                    targetState = inputText.isNotEmpty(),
                    transitionSpec = { (scaleIn() + fadeIn()).togetherWith(scaleOut() + fadeOut()) },
                    label = "SendMicButton"
                ) { hasText ->
                    FloatingActionButton(
                        onClick = if (hasText) onSend else onMicPress,
                        modifier = Modifier.size(48.dp),
                        containerColor = RasGramTheme.Green,
                        elevation = FloatingActionButtonDefaults.elevation(2.dp)
                    ) {
                        Icon(
                            if (hasText) Icons.Default.Send else Icons.Default.Mic,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
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

    Column(
        modifier = Modifier.fillMaxWidth().background(selectionBg).padding(horizontal = 4.dp, vertical = 2.dp),
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
    }
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
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF182229),
            border = BorderStroke(0.5.dp, RasGramTheme.Border)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (message.callType == "video") Icons.Default.Videocam else Icons.Default.Call,
                    null, modifier = Modifier.size(14.dp),
                    tint = if (message.callStatus == "missed") RasGramTheme.Red else RasGramTheme.Green
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(message.text, style = MaterialTheme.typography.bodySmall, color = RasGramTheme.TextPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(message.timeString, style = MaterialTheme.typography.labelSmall, color = RasGramTheme.TextMuted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun ImageMessageContent(url: String, context: Context) {
    var showFullScreen by remember { mutableStateOf(false) }
    AsyncImage(
        model = url,
        contentDescription = "Image",
        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 220.dp).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 4.dp)).clickable { showFullScreen = true },
        contentScale = ContentScale.Crop
    )
    if (showFullScreen) {
        Dialog(onDismissRequest = { showFullScreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black).clickable { showFullScreen = false }, contentAlignment = Alignment.Center) {
                AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                IconButton(onClick = { showFullScreen = false }, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
                    Icon(Icons.Default.Close, null, tint = Color.White)
                }
                IconButton(
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                ) {
                    Icon(Icons.Default.Download, null, tint = Color.White)
                }
            }
        }
    }
}

@Composable
fun VideoMessageContent(url: String, fileName: String?, fileType: String?, context: Context) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 4.dp)).clickable {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            intent.setDataAndType(url.toUri(), fileType)
            context.startActivity(intent)
        },
        color = Color.Black.copy(0.6f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.size(60.dp))
            Surface(modifier = Modifier.align(Alignment.BottomStart).padding(8.dp), shape = RoundedCornerShape(4.dp), color = Color.Black.copy(0.6f)) {
                Text(fileName ?: "Video", modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
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
                        mediaPlayer.setDataSource(url)
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

@Composable
fun DocumentMessageContent(url: String, fileName: String?, fileType: String?, fileSize: Long, context: Context) {
    val icon = when {
        fileType?.contains("pdf") == true -> Icons.Default.PictureAsPdf
        fileType?.contains("word") == true || fileType?.contains("document") == true -> Icons.Default.Description
        fileType?.contains("sheet") == true || fileType?.contains("excel") == true -> Icons.Default.TableChart
        fileType?.contains("presentation") == true || fileType?.contains("powerpoint") == true -> Icons.Default.Slideshow
        fileType?.contains("zip") == true || fileType?.contains("archive") == true -> Icons.Default.FolderZip
        else -> Icons.Default.InsertDriveFile
    }
    val iconColor = when {
        fileType?.contains("pdf") == true -> RasGramTheme.Red
        fileType?.contains("word") == true -> Color(0xFF2196F3)
        fileType?.contains("sheet") == true || fileType?.contains("excel") == true -> RasGramTheme.Green
        else -> RasGramTheme.TextMuted
    }

    Row(
        modifier = Modifier.fillMaxWidth().clickable {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            context.startActivity(intent)
        }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = RoundedCornerShape(10.dp), color = iconColor.copy(0.1f), modifier = Modifier.size(46.dp)) {
            Icon(icon, null, tint = iconColor, modifier = Modifier.padding(10.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(fileName ?: "Document", style = MaterialTheme.typography.bodyMedium, color = RasGramTheme.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (fileSize > 0) {
                Text(formatFileSize(fileSize), style = MaterialTheme.typography.labelSmall, color = RasGramTheme.TextMuted)
            }
        }
        Icon(Icons.Default.Download, null, tint = RasGramTheme.TextMuted, modifier = Modifier.size(20.dp))
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

    Column(modifier = modifier.fillMaxSize().background(RasGramTheme.DarkBackground)) {
        Surface(modifier = Modifier.fillMaxWidth(), color = RasGramTheme.DarkPanel) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Status", style = MaterialTheme.typography.titleLarge, color = RasGramTheme.TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { imageLauncher.launch(arrayOf("image/*", "video/*")) }) {
                    Icon(Icons.Default.Edit, null, tint = RasGramTheme.TextMuted)
                }
            }
        }

        val myStatuses = statuses.filter { it.userMobile == currentUser.mobile }
        val othersStatuses = statuses.filter { it.userMobile != currentUser.mobile }
            .groupBy { it.userMobile }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // My status
            item {
                StatusItem(
                    avatarUrl = currentUser.avatarUrl,
                    name = "My Status",
                    subtitle = if (isUploading) "Uploading..." else "Tap to add status update",
                    hasNewStatus = false,
                    onClick = { 
                        if (myStatuses.isNotEmpty()) onStatusClick(myStatuses) 
                        else imageLauncher.launch(arrayOf("image/*", "video/*"))
                    },
                    isMyStatus = true
                )
                HorizontalDivider(color = RasGramTheme.DividerColor, modifier = Modifier.padding(start = 80.dp))
            }

            if (othersStatuses.isNotEmpty()) {
                item {
                    Text("Recent Updates", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = RasGramTheme.TextMuted, style = MaterialTheme.typography.labelSmall)
                }
                items(othersStatuses.entries.toList()) { (mobile, userStatuses) ->
                    val first = userStatuses.first()
                    val viewed = userStatuses.all { currentUser.mobile in it.viewedBy }
                    StatusItem(
                        avatarUrl = first.userAvatar,
                        name = first.userName,
                        subtitle = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(first.timestamp)),
                        hasNewStatus = !viewed,
                        onClick = { onStatusClick(userStatuses) },
                        isMyStatus = false
                    )
                    HorizontalDivider(color = RasGramTheme.DividerColor, modifier = Modifier.padding(start = 80.dp))
                }
            } else {
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Circle, null, modifier = Modifier.size(64.dp), tint = RasGramTheme.TextMuted.copy(0.3f))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No recent updates", color = RasGramTheme.TextMuted)
                        Text("Status updates from your contacts will appear here.", color = RasGramTheme.TextMuted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
fun StatusItem(avatarUrl: String, name: String, subtitle: String, hasNewStatus: Boolean, onClick: () -> Unit, isMyStatus: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(54.dp)) {
            Box(
                modifier = Modifier.fillMaxSize().border(
                    width = 2.5.dp,
                    color = when {
                        isMyStatus -> RasGramTheme.Green
                        hasNewStatus -> RasGramTheme.Green
                        else -> RasGramTheme.TextMuted.copy(0.3f)
                    },
                    shape = CircleShape
                ).padding(3.dp)
            ) {
                AsyncImage(
                    model = avatarUrl.ifEmpty { "https://ui-avatars.com/api/?name=${name.replace(" ", "+")}&background=008069&color=fff" },
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            }
            if (isMyStatus) {
                Surface(
                    modifier = Modifier.size(20.dp).align(Alignment.BottomEnd),
                    shape = CircleShape,
                    color = RasGramTheme.Green,
                    border = BorderStroke(2.dp, RasGramTheme.DarkBackground)
                ) {
                    Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.padding(4.dp))
                }
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = RasGramTheme.TextPrimary, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = RasGramTheme.TextMuted)
        }
    }
}

// ==================== CALLS TAB ====================
@Composable
fun CallsTab(currentUser: User, modifier: Modifier = Modifier) {
    val db = remember { FirebaseFirestore.getInstance() }
    var callLogs by remember { mutableStateOf<List<Message>>(emptyList()) }

    LaunchedEffect(Unit) {
        db.collectionGroup("pvt_msg_${currentUser.mobile}")
            .whereEqualTo("isCallLog", true)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { _, _ -> }
    }

    Column(modifier = modifier.fillMaxSize().background(RasGramTheme.DarkBackground)) {
        Surface(modifier = Modifier.fillMaxWidth(), color = RasGramTheme.DarkPanel) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Calls", style = MaterialTheme.typography.titleLarge, color = RasGramTheme.TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                // FIX #3: Icons.Default.AddCall doesn't exist â€” replaced with PhoneForwarded
                IconButton(onClick = { }) {
                    Icon(Icons.Default.PhoneForwarded, null, tint = RasGramTheme.TextMuted)
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Call, null, modifier = Modifier.size(80.dp), tint = RasGramTheme.TextMuted.copy(0.3f))
            Spacer(modifier = Modifier.height(16.dp))
            Text("No Recent Calls", style = MaterialTheme.typography.titleMedium, color = RasGramTheme.TextMuted)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Your call history will appear here.", color = RasGramTheme.TextMuted, style = MaterialTheme.typography.bodySmall)
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
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = group.avatarUrl.ifEmpty { "https://ui-avatars.com/api/?name=${group.name.replace(" ", "+")}&background=005C4B&color=fff&bold=true" },
            contentDescription = null,
            modifier = Modifier.size(52.dp).clip(CircleShape),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(group.name, style = MaterialTheme.typography.bodyLarge, color = RasGramTheme.TextPrimary, fontWeight = FontWeight.SemiBold)
            Text("${group.members.size} members", style = MaterialTheme.typography.bodySmall, color = RasGramTheme.TextMuted)
        }
    }
}

// ==================== WEBRTC CALLING SCREEN ====================
@Composable
fun CallingScreen(
    currentUser: User,
    contact: User,
    callType: String,
    onEndCall: () -> Unit
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
    var callId by remember { mutableStateOf("") }
    // Call ended duration summary
    var showEndedSummary by remember { mutableStateOf(false) }
    var finalCallSeconds by remember { mutableIntStateOf(0) }

    val eglBase = remember { EglBase.create() }
    val peerConnectionFactory = remember {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()
    }
    val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80").setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443").setUsername("openrelayproject").setPassword("openrelayproject").createIceServer()
    )

    var peerConnection by remember { mutableStateOf<PeerConnection?>(null) }
    var localStream by remember { mutableStateOf<MediaStream?>(null) }
    var localVideoTrack by remember { mutableStateOf<VideoTrack?>(null) }
    var remoteVideoTrack by remember { mutableStateOf<VideoTrack?>(null) }
    var localSurfaceView by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var remoteSurfaceView by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    LaunchedEffect(Unit) {
        val hasMicPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasMicPerm) { Toast.makeText(context, "Microphone permission needed", Toast.LENGTH_SHORT).show(); onEndCall(); return@LaunchedEffect }

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = isSpeakerOn
            val chatHash = generateChatId(currentUser.mobile, contact.mobile)
            callId = "call_${chatHash}_${System.currentTimeMillis()}"

            val stream = peerConnectionFactory.createLocalMediaStream("localStream")
            val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
            stream.addTrack(peerConnectionFactory.createAudioTrack("audioTrack", audioSource))

            if (callType == "video") {
                val hasCamPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                if (hasCamPerm) {
                    getVideoCapturer(context)?.let { capturer ->
                        val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
                        val videoSource = peerConnectionFactory.createVideoSource(capturer.isScreencast)
                        capturer.initialize(helper, context, videoSource.capturerObserver)
                        capturer.startCapture(1280, 720, 30)
                        val vTrack = peerConnectionFactory.createVideoTrack("videoTrack", videoSource)
                        stream.addTrack(vTrack)
                        localVideoTrack = vTrack
                    }
                }
            }
            localStream = stream

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }

            val observer = object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let { c ->
                        scope.launch {
                            db.collection("calls").document(callId).collection("caller_ice").add(
                                mapOf("sdpMid" to c.sdpMid, "sdpMLineIndex" to c.sdpMLineIndex, "candidate" to c.sdp)
                            )
                        }
                    }
                }
                override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
                override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> { callStatus = "Connected"; isConnected = true }
                        PeerConnection.IceConnectionState.DISCONNECTED, PeerConnection.IceConnectionState.FAILED -> scope.launch { onEndCall() }
                        else -> {}
                    }
                }
                override fun onIceConnectionReceivingChange(b: Boolean) {}
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
                override fun onAddStream(s: MediaStream?) { s?.videoTracks?.firstOrNull()?.let { remoteVideoTrack = it } }
                override fun onRemoveStream(s: MediaStream?) {}
                override fun onDataChannel(d: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(r: RtpReceiver?, streams: Array<out MediaStream>?) {
                    streams?.firstOrNull()?.videoTracks?.firstOrNull()?.let { remoteVideoTrack = it }
                }
            }

            val pc = peerConnectionFactory.createPeerConnection(rtcConfig, observer)
            stream.audioTracks.forEach { pc?.addTrack(it, listOf("localStream")) }
            if (callType == "video") stream.videoTracks.forEach { pc?.addTrack(it, listOf("localStream")) }
            peerConnection = pc

            val offerConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                if (callType == "video") mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            }

            pc?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let { s ->
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(s2: SessionDescription?) {}
                            override fun onSetSuccess() {
                                scope.launch {
                                    db.collection("calls").document(callId).set(hashMapOf(
                                        "caller" to currentUser.mobile,
                                        "callerName" to currentUser.name,
                                        "callee" to contact.mobile,
                                        "type" to callType, "status" to "calling",
                                        "timestamp" to System.currentTimeMillis(),
                                        "offer" to mapOf("type" to s.type.canonicalForm(), "sdp" to s.description)
                                    ))
                                    // Send FCM push to callee so call arrives even if app is closed
                                    sendFcmCallNotification(
                                        calleeMobile = contact.mobile,
                                        callerMobile = currentUser.mobile,
                                        callerName = currentUser.name,
                                        callType = callType,
                                        callId = callId,
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
                            pc?.setRemoteDescription(object : SdpObserver {
                                override fun onCreateSuccess(s: SessionDescription?) {}
                                override fun onSetSuccess() {
                                    callStatus = "Connected"; isConnected = true
                                    scope.launch {
                                        db.collection("calls").document(callId).collection("callee_ice").get().await()
                                            .documents.forEach { doc ->
                                                doc.data?.let { d ->
                                                    pc?.addIceCandidate(IceCandidate(d["sdpMid"] as? String ?: "", (d["sdpMLineIndex"] as? Long)?.toInt() ?: 0, d["candidate"] as? String ?: ""))
                                                }
                                            }
                                    }
                                }
                                override fun onCreateFailure(e: String?) {}
                                override fun onSetFailure(e: String?) {}
                            }, SessionDescription(SessionDescription.Type.ANSWER, sdpStr))
                        }
                    }
                    "ended", "rejected" -> scope.launch { onEndCall() }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Call error: ${e.message}", Toast.LENGTH_SHORT).show()
            onEndCall()
        }
    }

    LaunchedEffect(isConnected) {
        if (isConnected) while (true) { delay(1000); callSeconds++ }
    }

    DisposableEffect(Unit) {
        onDispose {
            peerConnection?.close()
            localStream?.dispose()
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
            try { eglBase.release() } catch (_: Exception) {}
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B141A)) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (callType == "video") {
                AndroidView(factory = { ctx ->
                    SurfaceViewRenderer(ctx).also { it.init(eglBase.eglBaseContext, null); it.setMirror(false); remoteSurfaceView = it; remoteVideoTrack?.addSink(it) }
                }, modifier = Modifier.fillMaxSize())
                AndroidView(factory = { ctx ->
                    SurfaceViewRenderer(ctx).also { it.init(eglBase.eglBaseContext, null); it.setMirror(true); localSurfaceView = it; localVideoTrack?.addSink(it) }
                }, modifier = Modifier.size(120.dp, 160.dp).align(Alignment.TopEnd).padding(16.dp).clip(RoundedCornerShape(12.dp)))
            }

            Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(80.dp))
                if (callType != "video" || !isConnected) {
                    AsyncImage(
                        model = contact.avatarUrl.ifEmpty { "https://ui-avatars.com/api/?name=${contact.name.replace(" ", "+")}&size=200&background=008069&color=fff" },
                        contentDescription = null,
                        modifier = Modifier.size(120.dp).clip(CircleShape).border(3.dp, RasGramTheme.Green, CircleShape),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(contact.name, style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(if (isConnected) formatTime(callSeconds) else callStatus, color = RasGramTheme.Green, style = MaterialTheme.typography.titleMedium)
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
                                    if (callId.isNotEmpty()) db.collection("calls").document(callId).update("status", "ended")
                                    if (isConnected && callSeconds > 0) {
                                        finalCallSeconds = callSeconds
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
                                CallControlButton(icon = Icons.Default.Cameraswitch, label = "Flip", isActive = false, activeColor = RasGramTheme.Green) { }
                            }
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

    // ── ফোনের default call ringtone বাজানো ──────────────────────────────────
    val ringtone: Ringtone? = remember {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            RingtoneManager.getRingtone(context, uri)
        } catch (e: Exception) { null }
    }
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    // Screen compose হলে ring শুরু, dispose হলে বন্ধ
    DisposableEffect(Unit) {
        try {
            // সাময়িকভাবে volume max করো (user এর ring mode মেনে চলে)
            ringtone?.play()
        } catch (_: Exception) {}
        onDispose {
            try { ringtone?.stop() } catch (_: Exception) {}
        }
    }

    // helper — ringtone বন্ধ করে callback চালাও
    fun stopAndCall(action: () -> Unit) {
        try { ringtone?.stop() } catch (_: Exception) {}
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
            if (status == "ended" || status == "rejected" || status == "missed") {
                try { ringtone?.stop() } catch (_: Exception) {}
                onDecline()
            }
        }
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
                            stopAndCall {
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
                            stopAndCall {
                                scope.launch {
                                    db.collection("calls").document(callId).update("status", "answered")
                                }
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
                        listOf("Profile", "Privacy", "Notifications").forEachIndexed { i, label ->
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
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, RasGramTheme.Border), colors = ButtonDefaults.outlinedButtonColors(contentColor = RasGramTheme.TextMuted)) { Text("Cancel") }
                        Button(onClick = {
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
                                AsyncImage(
                                    model = user.avatarUrl.ifEmpty { "https://ui-avatars.com/api/?name=${user.name.replace(" ", "+")}&background=008069&color=fff" },
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp).clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
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

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, fileBody)
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
    try {
        // Get callee FCM token from Firestore
        val calleeDoc = db.collection("chat_users").document(calleeMobile).get().await()
        val fcmToken = calleeDoc.getString("fcmToken") ?: return@withContext

        // Read service account from assets
        val saStream = context.resources.openRawResource(
            context.resources.getIdentifier("service_account", "raw", context.packageName)
        )
        val saJson = org.json.JSONObject(saStream.bufferedReader().readText())

        // Get OAuth2 access token for FCM v1 API
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

        // Build JWT
        val header = android.util.Base64.encodeToString(
            """{"alg":"RS256","typ":"JWT"}""".toByteArray(), android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
        )
        val claims = android.util.Base64.encodeToString(
            """{"iss":"$clientEmail","scope":"$scope","aud":"https://oauth2.googleapis.com/token","iat":$now,"exp":${now + 3600}}""".toByteArray(),
            android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
        )
        val toSign = "$header.$claims"
        val signer = java.security.Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(toSign.toByteArray())
        val sig = android.util.Base64.encodeToString(signer.sign(), android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
        val jwt = "$toSign.$sig"

        // Exchange JWT for access token
        val client = okhttp3.OkHttpClient()
        val tokenReq = okhttp3.Request.Builder()
            .url("https://oauth2.googleapis.com/token")
            .post(okhttp3.FormBody.Builder()
                .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                .add("assertion", jwt).build())
            .build()
        val tokenResp = client.newCall(tokenReq).execute()
        val accessToken = org.json.JSONObject(tokenResp.body?.string() ?: "").optString("access_token")
        if (accessToken.isEmpty()) return@withContext

        // Send FCM data message (high priority - works even when app is killed)
        val fcmPayload = org.json.JSONObject().apply {
            put("message", org.json.JSONObject().apply {
                put("token", fcmToken)
                put("data", org.json.JSONObject().apply {
                    put("type", "incoming_call")
                    put("callerName", callerName)
                    put("callerMobile", callerMobile)
                    put("callType", callType)
                    put("callId", callId)
                })
                put("android", org.json.JSONObject().apply {
                    put("priority", "HIGH")
                })
            })
        }.toString()

        val fcmReq = okhttp3.Request.Builder()
            .url("https://fcm.googleapis.com/v1/projects/$projectId/messages:send")
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Content-Type", "application/json")
            .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), fcmPayload))
            .build()
        client.newCall(fcmReq).execute()
    } catch (_: Exception) { }
}
// ==================== SEND MESSAGE ====================
fun sendMessage(
    db: FirebaseFirestore,
    chatId: String,
    senderMobile: String,
    receiverMobile: String,
    text: String,
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
                }?.also { msgs -> messages = msgs }
            }
    }

    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
    LaunchedEffect(isRecording) {
        if (isRecording) { recordingSeconds = 0; while (isRecording) { delay(1000); recordingSeconds++ } }
    }

    BackHandler(enabled = selectedMessages.isNotEmpty()) { selectedMessages = emptySet() }

    Column(modifier = Modifier.fillMaxSize().background(RasGramTheme.DarkBackground)) {
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
                            onDelete = { scope.launch { db.collection("groups").document(group.id).collection("messages").document(msg.id).update("isDeleted", true, "text", "") } },
                            onStar = { }, onCopy = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("message", msg.text))
                                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
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
                        val recorder = MediaRecorder()
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
    }
}


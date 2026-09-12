package com.rasel.RasFocus.selfcontrol.rasgram

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.rasel.RasFocus.ui.theme.RasFocusAppTheme
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat.getMainExecutor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// ══════════════════════════════════════════════════════════════════════
// RasGramWebLoginActivity
//
// ব্যবহার:  Settings → Linked Devices → Link a Device
// Flow:
//   1. Phone camera দিয়ে PC-র QR scan করে
//   2. QR-এর token পড়ে Firestore-এ  qr_sessions/{token}  doc update করে:
//        { status: "confirmed", uid, mobile, name, idToken }
//   3. PC সেটা poll করে detect করে → auto-login হয়
// ══════════════════════════════════════════════════════════════════════

class RasGramWebLoginActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        setContent {
            RasFocusAppTheme {
                QrScanScreen(
                    onDone = { finish() }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

// ══════════════════════════════════════════════════════════════════════
// UI
// ══════════════════════════════════════════════════════════════════════

enum class ScanState { Scanning, Confirming, Done, Error }

@Composable
fun QrScanScreen(onDone: () -> Unit) {
    val context  = LocalContext.current
    val auth     = FirebaseAuth.getInstance()
    val db       = FirebaseFirestore.getInstance()

    var state    by remember { mutableStateOf(ScanState.Scanning) }
    var errorMsg by remember { mutableStateOf("") }
    var scanned  by remember { mutableStateOf(false) }

    // Camera permission
    var hasCam by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCam = granted }

    LaunchedEffect(Unit) {
        if (!hasCam) permLauncher.launch(Manifest.permission.CAMERA)
    }

    // Background: dark navy (same as PC UI)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1418)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                "RasGram PC লগইন",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE9EDEF)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "PC-তে দেখানো QR code scan করুন",
                fontSize = 13.sp,
                color = Color(0xFF00A884),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))

            when (state) {
                ScanState.Scanning -> {
                    if (hasCam) {
                        CameraPreviewWithQR(
                            modifier = Modifier
                                .size(260.dp)
                                .background(Color.Black, RoundedCornerShape(12.dp)),
                            onTokenFound = { token ->
                                if (!scanned) {
                                    scanned = true
                                    state   = ScanState.Confirming
                                    confirmLogin(auth, db, token, context,
                                        onSuccess = { state = ScanState.Done },
                                        onError   = { msg ->
                                            errorMsg = msg
                                            state    = ScanState.Error
                                            scanned  = false
                                        }
                                    )
                                }
                            }
                        )
                    } else {
                        Text("Camera permission প্রয়োজন", color = Color.White)
                    }
                }

                ScanState.Confirming -> {
                    CircularProgressIndicator(color = Color(0xFF00A884))
                    Spacer(Modifier.height(12.dp))
                    Text("যাচাই করা হচ্ছে...", color = Color(0xFF8696A0))
                }

                ScanState.Done -> {
                    Text("✓ লগইন সফল!", fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, color = Color(0xFF00A884))
                    Spacer(Modifier.height(8.dp))
                    Text("PC-তে RasGram খুলে যাবে", color = Color(0xFF8696A0))
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = onDone,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00A884)
                        )
                    ) {
                        Text("সম্পন্ন")
                    }
                }

                ScanState.Error -> {
                    Text("⚠ ত্রুটি", fontSize = 16.sp, color = Color(0xFFEA4335))
                    Spacer(Modifier.height(6.dp))
                    Text(errorMsg, color = Color(0xFF8696A0), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { state = ScanState.Scanning; scanned = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A884))
                    ) {
                        Text("আবার চেষ্টা করুন")
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// Camera preview with ML Kit QR scanner
// ══════════════════════════════════════════════════════════════════════

@Composable
fun CameraPreviewWithQR(
    modifier: Modifier = Modifier,
    onTokenFound: (String) -> Unit
) {
    val context  = LocalContext.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner  = remember { BarcodeScanning.getClient() }
    var alreadyFound by remember { mutableStateOf(false) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val camProviderFuture = ProcessCameraProvider.getInstance(ctx)

            camProviderFuture.addListener({
                val camProvider = camProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(executor) { imageProxy ->
                    if (alreadyFound) { imageProxy.close(); return@setAnalyzer }

                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val image = InputImage.fromMediaImage(
                            mediaImage, imageProxy.imageInfo.rotationDegrees
                        )
                        scanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                for (bc in barcodes) {
                                    if (bc.format == Barcode.FORMAT_QR_CODE) {
                                        val raw = bc.rawValue ?: continue
                                        // Token is a 32-char hex string
                                        if (raw.length == 32 && raw.all { it.isLetterOrDigit() }) {
                                            alreadyFound = true
                                            onTokenFound(raw)
                                            break
                                        }
                                    }
                                }
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    } else {
                        imageProxy.close()
                    }
                }

                try {
                    camProvider.unbindAll()
                    camProvider.bindToLifecycle(
                        ctx as androidx.lifecycle.LifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, getMainExecutor(ctx))

            previewView
        },
        modifier = modifier
    )
}

// ══════════════════════════════════════════════════════════════════════
// Firestore confirmation — phone লিখবে, PC পড়বে
// ══════════════════════════════════════════════════════════════════════

private fun confirmLogin(
    auth     : FirebaseAuth,
    db       : FirebaseFirestore,
    token    : String,
    context  : android.content.Context,
    onSuccess: () -> Unit,
    onError  : (String) -> Unit
) {
    val user = auth.currentUser
    if (user == null) {
        onError("আপনি RasGram-এ লগইন করেননি")
        return
    }

    user.getIdToken(true)
        .addOnSuccessListener { result ->
            val idToken = result.token ?: ""

            val prefs  = context.getSharedPreferences("rasgram_prefs", android.content.Context.MODE_PRIVATE)
            val mobile = prefs.getString("saved_mobile", null) ?: user.phoneNumber ?: ""
            val name   = prefs.getString("saved_name",   null) ?: user.displayName ?: ""
            val uid    = prefs.getString("saved_uid",    null) ?: user.uid

            val data = hashMapOf(
                "status"  to "confirmed",
                "uid"     to uid,
                "mobile"  to mobile,
                "name"    to name,
                "email"   to (user.email ?: ""),
                "idToken" to idToken
            )

            db.collection("qr_sessions")
                .document(token)
                .set(data)
                .addOnSuccessListener { onSuccess() }
                .addOnFailureListener { e ->
                    onError("Firestore error: ${e.message}")
                }
        }
        .addOnFailureListener { e ->
            onError("Token error: ${e.message}")
        }
}

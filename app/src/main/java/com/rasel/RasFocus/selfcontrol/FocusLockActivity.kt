package com.rasel.RasFocus.selfcontrol

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rasel.RasFocus.DataManager
import kotlinx.coroutines.delay

// ═════════════════════════════════════════════════════════════════════
// FOCUS SESSION STATE
//
// Deep Study (Focus) UI এখানে session চালু/বন্ধ জানায়।
// UnifiedBlockerService প্রতিটা accessibility event এ এটা পড়ে —
// তাই Chrome এর notun tab বা অন্য non-allowed app এ গেলে সাথে সাথে
// (poll এর অপেক্ষা ছাড়াই) FocusLockActivity সামনে চলে আসে।
//
// Safety valve: state এ end-time থাকে। app crash / force-stop হলেও
// end-time পার হলে enforcement নিজে থেকেই শেষ — চিরস্থায়ী lockout হবে না।
// ═════════════════════════════════════════════════════════════════════
object FocusSessionState {
    private const val PREFS = "focus_lock_state"
    private const val KEY_END = "end_millis"

    fun start(context: Context, endMillis: Long) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_END, endMillis).apply()
    }

    fun stop(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_END, 0L).apply()
    }

    fun endMillis(context: Context): Long =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_END, 0L)

    fun remainingMillis(context: Context): Long =
        maxOf(0L, endMillis(context) - System.currentTimeMillis())

    fun isActive(context: Context): Boolean = remainingMillis(context) > 0L
}

// ═════════════════════════════════════════════════════════════════════
// FOCUS LOCK ACTIVITY (native)
//
// Focus চলাকালীন non-allowed app / Chrome notun tab খুললে এটা সাথে সাথে
// সামনে আসে। শুধু allowed app গুলো এখান থেকে খোলা যায় — exe এর মতো।
// Session শেষ হলে নিজে থেকেই বন্ধ হয়ে যায়।
// ═════════════════════════════════════════════════════════════════════
class FocusLockActivity : ComponentActivity() {

    companion object {
        fun launch(context: Context) {
            val intent = Intent(context, FocusLockActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Back চাপলে Home এ যাবে (আগের non-allowed app এ ফিরবে না)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goHome()
            }
        })

        setContent {
            FocusLockScreen(
                onOpenApp = { pkg -> openAllowedApp(pkg) },
                onGoHome = { goHome() },
                onSessionEnded = { finish() }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (!FocusSessionState.isActive(this)) finish()
    }

    private fun goHome() {
        try {
            startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
        }
    }

    private fun openAllowedApp(pkg: String) {
        try {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            }
        } catch (_: Exception) {
        }
    }
}

private val focusQuotes = listOf(
    "এখন মনোযোগের সময় — বাকি সব পরে।",
    "আজকের কষ্টই আগামীর সাফল্য।",
    "নিজের লক্ষ্যে অটল থাকো।",
    "একটু ধৈর্য ধরো, সেশন প্রায় শেষ।"
)

@Composable
private fun FocusLockScreen(
    onOpenApp: (String) -> Unit,
    onGoHome: () -> Unit,
    onSessionEnded: () -> Unit
) {
    val context = LocalContext.current
    var remaining by remember { mutableStateOf(FocusSessionState.remainingMillis(context)) }
    val quote = remember { focusQuotes.random() }
    val allowedPkgs = remember {
        DataManager.dsAllowAppList.filter { it.isNotBlank() && it != context.packageName }
    }

    LaunchedEffect(Unit) {
        while (true) {
            remaining = FocusSessionState.remainingMillis(context)
            if (remaining <= 0L) {
                onSessionEnded()
                break
            }
            delay(500)
        }
    }

    val totalSecs = remaining / 1000
    val hours = totalSecs / 3600
    val mins = (totalSecs % 3600) / 60
    val secs = totalSecs % 60
    val timeText = if (hours > 0) "%02d:%02d:%02d".format(hours, mins, secs)
    else "%02d:%02d".format(mins, secs)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF060B14))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🔒", fontSize = 56.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            "Focus চলছে",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            timeText,
            color = Color(0xFF2DD4AA),
            fontSize = 48.sp,
            fontWeight = FontWeight.Black
        )
        Spacer(Modifier.height(16.dp))
        Text(
            quote,
            color = Color(0xFF94A3B8),
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))

        if (allowedPkgs.isNotEmpty()) {
            Text(
                "অনুমোদিত অ্যাপ",
                color = Color(0xFF94A3B8),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))
            allowedPkgs.forEach { pkg ->
                val label = remember(pkg) {
                    try {
                        val pm = context.packageManager
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    } catch (_: Exception) {
                        pkg
                    }
                }
                Button(
                    onClick = { onOpenApp(pkg) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C6BFF))
                ) {
                    Text(label, color = Color.White, fontSize = 15.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Button(
            onClick = onGoHome,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
        ) {
            Text("Home এ যাও", color = Color.White, fontSize = 15.sp)
        }
    }
}

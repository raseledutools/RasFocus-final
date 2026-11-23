package com.rasel.RasFocus.selfcontrol.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import com.rasel.RasFocus.ui.theme.RasFocusAppTheme

/**
 * LauncherActivity — Home screen launcher entry point.
 *
 * MainActivity handles the full RasFocus app (splash → login → dashboard).
 * This separate activity is registered with HOME intent-filter so Android
 * opens the minimalist launcher UI when the user presses the home button,
 * without routing through the main app navigation stack.
 */
class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pure black status + navigation bars (light/white icons), no contrast scrim
        enableEdgeToEdge(
            statusBarStyle     = SystemBarStyle.dark(Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(Color.BLACK)
        )
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            RasFocusAppTheme {
                MinimalistLauncherScreen(navController = null)
            }
        }
    }

    // নতুন shortcut pin হলে UI আবার লোড করো (সাধারণ home press এ নয়)
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val isReload = intent.getBooleanExtra("reload_shortcuts", false)
        if (isReload) {
            intent.removeExtra("reload_shortcuts")
            recreate()
        }
    }
}

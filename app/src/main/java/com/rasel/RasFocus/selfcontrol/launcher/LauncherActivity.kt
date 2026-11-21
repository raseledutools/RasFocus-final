package com.rasel.RasFocus.selfcontrol.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        enableEdgeToEdge()
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
        if (intent.getBooleanExtra("reload_shortcuts", false)) {
            intent.removeExtra("reload_shortcuts")
            recreate()
        }
    }
}

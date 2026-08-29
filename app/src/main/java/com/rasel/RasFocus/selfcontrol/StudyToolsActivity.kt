package com.rasel.RasFocus.selfcontrol

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rasel.RasFocus.selfcontrol.study_tools.ProfessionalDiaryScreen
import com.rasel.RasFocus.selfcontrol.study_tools.StudyToolsScreen

/**
 * StudyToolsActivity — SelfControlModule এর StudyToolsCard এই Activity launch করে।
 * StudyToolsScreen composable (study_tools.kt) কে host করে।
 *
 * StudyToolsScreen এর ভেতরের "Personal Diary" কার্ডে ট্যাপ করলে onOpenDiary()
 * ট্রিগার হয়। সেই অনুযায়ী এই Activity নিজের মধ্যেই StudyToolsScreen আর
 * ProfessionalDiaryScreen (diary.kt) এর মধ্যে টগল করে — আলাদা কোনো Activity
 * লাগে না।
 *
 * FIX: Home screen shortcut (ACTION_OPEN_DIARY) এখন সরাসরি diary open করে,
 * পুরো app launch করে না।
 */
class StudyToolsActivity : ComponentActivity() {

    // Compose state — onNewIntent থেকেও update করা যাবে
    private val _showDiary = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── shortcut বা intent থেকে diary সরাসরি open ─────────────────────
        _showDiary.value = shouldOpenDiary(intent)

        setContent {
            MaterialTheme {
                var showDiary by remember { _showDiary }

                if (showDiary) {
                    ProfessionalDiaryScreen(
                        onNavigateBack = { showDiary = false }
                    )
                } else {
                    val openTab = intent.getStringExtra("open_tab")
                    StudyToolsScreen(
                        initialTab = openTab,
                        onBack = { finish() },
                        onOpenDiary = { showDiary = true }
                    )
                }
            }
        }
    }

    /**
     * FIX: singleTop launchMode থাকায় shortcut দ্বিতীয়বার ট্যাপ করলে
     * onNewIntent() আসে। এখানেও diary খুলি।
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (shouldOpenDiary(intent)) {
            _showDiary.value = true
        }
    }

    /** intent টা diary-open shortcut কিনা চেক করে */
    private fun shouldOpenDiary(intent: Intent?): Boolean {
        if (intent == null) return false
        return intent.action == "com.rasel.RasFocus.ACTION_OPEN_DIARY" ||
               intent.getStringExtra("open_tab") == "diary"
    }
}
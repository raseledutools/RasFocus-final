import re
import os

files_to_patch = [
    "app/src/main/java/com/rasel/RasFocus/selfcontrol/ButtonPhone.kt",
    "app/src/main/java/com/rasel/RasFocus/selfcontrol/deep_study.kt",
    "app/src/main/java/com/rasel/RasFocus/selfcontrol/BlockingPlan.kt",
    "app/src/main/java/com/rasel/RasFocus/combo/selfcontrol/ButtonPhone.kt",
    "app/src/main/java/com/rasel/RasFocus/combo/selfcontrol/deep_study.kt",
    "app/src/main/java/com/rasel/RasFocus/combo/selfcontrol/BlockingPlan.kt",
    "app/src/main/java/com/rasel/RasFocus/combo/selfcontrol/FamilyBrowser.kt",
    "app/src/main/java/com/rasel/RasFocus/selfcontrol/FamilyBrowser.kt"
]

# Patterns for each card to replace the PremiumCard layout with MinimalistLauncherRow
patterns = {
    # FocusLauncherCard
    r'fun FocusLauncherCard.*?var showSetup.*?\n.*?com\.rasel\.RasFocus\.ui\.theme\.PremiumCard[\s\S]*?Icon\(Icons\.Default\.ChevronRight.*?\n\s*\}\n\s*\}': 
    r'''fun FocusLauncherCard(onSessionStart: () -> Unit) {
    var showSetup by remember { mutableStateOf(false) }
    com.rasel.RasFocus.selfcontrol.MinimalistLauncherRow(
        icon = androidx.compose.material.icons.Icons.Default.PhoneLocked,
        title = "Start Focus Session",
        subtitle = "Lock yourself to minimal apps only",
        onClick = { showSetup = true },
        accentColor = androidx.compose.ui.graphics.Color(0xFF0EA5E9)
    )''',
    
    # TakeABreakCard
    r'com\.rasel\.RasFocus\.ui\.theme\.PremiumCard\(Modifier\.fillMaxWidth\(\)\.padding\(horizontal = 20\.dp\), onClick = \{ showDialog = true \}[\s\S]*?Icon\(if \(isActive\).*?\n\s*\}\n\s*\}':
    r'''com.rasel.RasFocus.selfcontrol.MinimalistLauncherRow(
        icon = androidx.compose.material.icons.Icons.Default.Timer,
        title = if (isActive) "Session Active" else "Take a Break",
        subtitle = if (isActive) "Tap to stop active session" else "Custom break / Pomodoro timer",
        onClick = { showDialog = true },
        accentColor = if (isActive) androidx.compose.ui.graphics.Color(0xFF10B981) else androidx.compose.ui.graphics.Color(0xFF8B5CF6)
    )''',
    
    # TakeRestCard
    r'com\.rasel\.RasFocus\.ui\.theme\.PremiumCard\(Modifier\.fillMaxWidth\(\)\.padding\(horizontal = 20\.dp\), onClick = \{ showDialog = true \}[\s\S]*?Icon\(Icons\.Default\.ChevronRight.*?\n\s*\}\n\s*\}':
    r'''com.rasel.RasFocus.selfcontrol.MinimalistLauncherRow(
        icon = androidx.compose.material.icons.Icons.Default.Bedtime,
        title = "Take Rest",
        subtitle = "Block all apps for 1-4 hours",
        onClick = { showDialog = true },
        accentColor = androidx.compose.ui.graphics.Color(0xFFF59E0B)
    )''',
    
    # NormalModeCard
    r'fun NormalModeCard.*?\n.*?var showDialog.*?\n.*?com\.rasel\.RasFocus\.ui\.theme\.PremiumCard[\s\S]*?Icon\(Icons\.Default\.ChevronRight.*?\n\s*\}\n\s*\}':
    r'''fun NormalModeCard() {
    var showDialog by remember { mutableStateOf(false) }
    com.rasel.RasFocus.selfcontrol.MinimalistLauncherRow(
        icon = androidx.compose.material.icons.Icons.Default.LockOpen,
        title = "Normal Mode",
        subtitle = "Resume regular usage with monitoring",
        onClick = { showDialog = true },
        accentColor = androidx.compose.ui.graphics.Color(0xFF64748B)
    )''',
    
    # ExtremBlockCard
    r'fun ExtremBlockCard\(onClick: \(\) -> Unit\) \{\n.*?com\.rasel\.RasFocus\.ui\.theme\.PremiumCard[\s\S]*?Icon\(Icons\.Default\.ChevronRight.*?\n\s*\}\n\s*\}':
    r'''fun ExtremBlockCard(onClick: () -> Unit) {
    com.rasel.RasFocus.selfcontrol.MinimalistLauncherRow(
        icon = androidx.compose.material.icons.Icons.Default.Warning,
        title = "Extreme Block",
        subtitle = "Zero access mode for severe distraction",
        onClick = onClick,
        accentColor = androidx.compose.ui.graphics.Color(0xFFEF4444)
    )''',
    
    # BlockingPlanCard
    r'fun BlockingPlanCard\(navController: androidx\.navigation\.NavController\) \{\n.*?com\.rasel\.RasFocus\.ui\.theme\.PremiumCard[\s\S]*?Icon\(Icons\.Default\.ChevronRight.*?\n\s*\}\n\s*\}':
    r'''fun BlockingPlanCard(navController: androidx.navigation.NavController) {
    com.rasel.RasFocus.selfcontrol.MinimalistLauncherRow(
        icon = androidx.compose.material.icons.Icons.Default.CalendarMonth,
        title = "Blocking Plans",
        subtitle = "Schedule automated focus times",
        onClick = { navController.navigate("blocking_plan") },
        accentColor = androidx.compose.ui.graphics.Color(0xFFEC4899)
    )''',
    
    # FamilyBrowserCard
    r'fun FamilyBrowserCard\(context: android\.content\.Context\) \{\n.*?com\.rasel\.RasFocus\.ui\.theme\.PremiumCard[\s\S]*?Icon\(Icons\.Default\.ChevronRight.*?\n\s*\}\n\s*\}':
    r'''fun FamilyBrowserCard(context: android.content.Context) {
    com.rasel.RasFocus.selfcontrol.MinimalistLauncherRow(
        icon = androidx.compose.material.icons.Icons.Default.Public,
        title = "Safe Browser",
        subtitle = "Distraction-free browsing",
        onClick = { context.startActivity(android.content.Intent(context, com.rasel.RasFocus.selfcontrol.familybrowser.MainActivity::class.java)) },
        accentColor = androidx.compose.ui.graphics.Color(0xFF14B8A6)
    )'''
}

for filepath in files_to_patch:
    if os.path.exists(filepath):
        with open(filepath, 'r', encoding='utf-8') as f:
            content = f.read()
        
        original_content = content
        for pattern, replacement in patterns.items():
            content = re.sub(pattern, replacement, content)
            
        if content != original_content:
            with open(filepath, 'w', encoding='utf-8') as f:
                f.write(content)
            print(f"Patched {filepath}")

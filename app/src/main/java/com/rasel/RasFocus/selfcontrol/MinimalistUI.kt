package com.rasel.RasFocus.selfcontrol

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

val MinimalistBg = Color(0xFF0B101E)
val MinimalistSurface = Color(0xFF161E31)
val MinimalistTextPrimary = Color(0xFFF1F5F9)
val MinimalistTextSecondary = Color(0xFF94A3B8)
val MinimalistAccent = Color(0xFF0EA5E9)

@Composable
fun MinimalistLauncherRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    accentColor: Color = MinimalistAccent
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(MinimalistSurface, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = MinimalistTextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = MinimalistTextSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
fun StudyLauncherHeader() {
    var timeString by remember { mutableStateOf("") }
    var dateString by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            val date = Date()
            timeString = SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
            dateString = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(date)
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp, bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = timeString,
            color = MinimalistTextPrimary,
            fontSize = 72.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = (-2).sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = dateString.uppercase(),
            color = MinimalistTextSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.height(24.dp))
        
        Text(
            text = "\"Focus on being productive instead of busy.\"",
            color = MinimalistAccent.copy(alpha = 0.8f),
            fontSize = 13.sp,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
        )
    }
}

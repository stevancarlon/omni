package com.buddy.assistant.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    val bgGradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF0A0A1A), Color(0xFF0D0D2B), Color(0xFF0A0A1A))
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgGradient)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Setup",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 4.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                "Complete these steps to activate Buddy",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
            )

            Spacer(modifier = Modifier.height(32.dp))

            SetupStep(
                number = 1,
                title = "Enable Accessibility Service",
                description = "Buddy needs accessibility access to read your screen and perform actions on your behalf.",
                buttonText = "Open Accessibility Settings",
                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            SetupStep(
                number = 2,
                title = "Set Buddy as Default Assistant",
                description = "Replace Google Assistant with Buddy so you can activate it with the home button or voice.",
                buttonText = "Open Assistant Settings",
                onClick = { context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            SetupStep(
                number = 3,
                title = "Configure API Key",
                description = "Add your Claude or OpenAI API key in Settings to power Buddy's intelligence.",
                buttonText = "Go to Settings",
                onClick = onBack
            )

            Spacer(modifier = Modifier.height(16.dp))

            SetupStep(
                number = 4,
                title = "Grant Microphone Permission",
                description = "Buddy needs microphone access to hear your voice commands.",
                buttonText = "Already granted if you see this",
                onClick = {}
            )

            Spacer(modifier = Modifier.height(16.dp))

            SetupStep(
                number = 5,
                title = "Allow Overlay",
                description = "Buddy shows a floating status overlay while it's working, so you can see what it's doing on top of other apps.",
                buttonText = "Open Overlay Settings",
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            )

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun SetupStep(
    number: Int,
    title: String,
    description: String,
    buttonText: String,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.05f)
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color(0xFF6C63FF),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "$number",
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = Color.White.copy(alpha = 0.6f)
                )
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF6C63FF).copy(alpha = 0.8f)
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(buttonText, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

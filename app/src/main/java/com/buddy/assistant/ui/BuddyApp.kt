package com.buddy.assistant.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.buddy.assistant.agent.AgentController
import com.buddy.assistant.service.BuddyAccessibilityService
import com.buddy.assistant.ui.screens.HomeScreen
import com.buddy.assistant.ui.screens.SettingsScreen
import com.buddy.assistant.ui.screens.SetupScreen

@Composable
fun BuddyApp(agentController: AgentController) {
    val context = LocalContext.current
    var showAccessibilityDialog by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                showAccessibilityDialog = BuddyAccessibilityService.instance == null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showAccessibilityDialog) {
        AccessibilitySetupDialog(
            onOpenSettings = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            onDismiss = { showAccessibilityDialog = false }
        )
    }

    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                agentController = agentController,
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToSetup = { navController.navigate("setup") }
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("setup") {
            SetupScreen(onBack = { navController.popBackStack() })
        }
    }
}

@Composable
private fun AccessibilitySetupDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A2E),
        titleContentColor = Color.White,
        textContentColor = Color.White.copy(alpha = 0.8f),
        title = {
            Text("Enable Accessibility Service", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Buddy needs the Accessibility Service to read your screen and perform actions on your behalf.",
                    lineHeight = 20.sp
                )
                Text("To enable it:", fontWeight = FontWeight.SemiBold)
                Text(
                    "1. Tap \"Open Settings\" below\n" +
                    "2. Tap \"Installed apps\"\n" +
                    "3. Find \"Buddy\" in the list\n" +
                    "4. Toggle it on and confirm",
                    lineHeight = 22.sp
                )
                Text(
                    "Buddy only reads the screen when you give it a command. No data is collected in the background.",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C63FF)),
                shape = RoundedCornerShape(50)
            ) {
                Text("Open Settings")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Later", color = Color.White.copy(alpha = 0.5f))
            }
        }
    )
}

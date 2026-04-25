package com.omni.assistant.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.assistant.OmniApplication
import androidx.compose.ui.text.TextStyle
import com.omni.assistant.ui.theme.OmniColors
import com.omni.assistant.ui.theme.OmniGradients
import com.omni.assistant.ui.theme.OmniShapes
import com.omni.assistant.ui.theme.OmniButton
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.omni.assistant.agent.AgentController
import com.omni.assistant.service.OmniAccessibilityService
import com.omni.assistant.ui.screens.CreditsScreen
import com.omni.assistant.ui.screens.HomeScreen
import com.omni.assistant.ui.screens.SettingsScreen
import com.omni.assistant.ui.screens.SetupScreen
import com.omni.assistant.ui.screens.WelcomeScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun OmniApp(agentController: AgentController) {
    val context = LocalContext.current
    val app = context.applicationContext as OmniApplication
    val scope = rememberCoroutineScope()
    var showAccessibilityDialog by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Determine the starting route based on whether the user has seen the welcome.
    // Null while we're still loading — render nothing to avoid flashing the wrong screen.
    var startDestination by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val seen = app.settingsRepository.welcomeSeen.first()
        startDestination = if (seen) "home" else "welcome"
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Only nag about accessibility once the user has moved past welcome.
                scope.launch {
                    val seen = app.settingsRepository.welcomeSeen.first()
                    showAccessibilityDialog =
                        seen && OmniAccessibilityService.instance == null
                }
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
    val start = startDestination ?: return

    NavHost(navController = navController, startDestination = start) {
        composable("welcome") {
            WelcomeScreen(
                onGetStarted = {
                    scope.launch {
                        app.settingsRepository.setWelcomeSeen(true)
                        navController.navigate("setup") {
                            popUpTo("welcome") { inclusive = true }
                        }
                    }
                },
            )
        }
        composable("home") {
            HomeScreen(
                agentController = agentController,
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToSetup = { navController.navigate("setup") },
                onNavigateToCredits = { navController.navigate("credits") },
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("setup") {
            SetupScreen(onBack = {
                // After setup, land on home rather than popping back to welcome.
                navController.navigate("home") {
                    popUpTo("setup") { inclusive = true }
                }
            })
        }
        composable("credits") {
            CreditsScreen(onBack = { navController.popBackStack() })
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
        containerColor = OmniColors.Surface,
        titleContentColor = OmniColors.Ink,
        textContentColor = OmniColors.InkDim,
        shape = OmniShapes.CardLg,
        title = {
            Text("Enable Accessibility Service", fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Omni needs the Accessibility Service to read your screen and perform actions on your behalf.",
                    lineHeight = 20.sp
                )
                Text("To enable it:", fontWeight = FontWeight.SemiBold, color = OmniColors.Ink)
                Text(
                    "1. Tap \"Open Settings\" below\n" +
                    "2. Tap \"Installed apps\"\n" +
                    "3. Find \"Omni\" in the list\n" +
                    "4. Toggle it on and confirm",
                    lineHeight = 22.sp
                )
                Text(
                    "Omni only reads the screen when you give it a command. No data is collected in the background.",
                    color = OmniColors.InkMute,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        },
        confirmButton = {
            OmniButton(
                onClick = onOpenSettings,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(
                    "Open Settings",
                    style = TextStyle(
                        brush = OmniGradients.SilverText,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Later", color = OmniColors.InkMute)
            }
        }
    )
}

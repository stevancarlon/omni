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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.assistant.OmniApplication
import com.omni.assistant.BuildConfig
import androidx.compose.ui.text.TextStyle
import com.omni.assistant.auth.GoogleSignInClient
import com.omni.assistant.auth.UnauthorizedException
import com.omni.assistant.ui.theme.OmniColors
import com.omni.assistant.ui.theme.OmniGradients
import com.omni.assistant.ui.theme.OmniShapes
import com.omni.assistant.ui.theme.OmniButton
import com.omni.assistant.ui.theme.dockPillStyle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.window.Dialog
import com.omni.assistant.agent.AgentController
import com.omni.assistant.service.OmniAccessibilityService
import com.omni.assistant.ui.screens.HomeScreen
import com.omni.assistant.ui.screens.PaywallScreen
import com.omni.assistant.ui.screens.SettingsScreen
import com.omni.assistant.ui.screens.SetupScreen
import com.omni.assistant.ui.screens.WelcomeScreen
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun OmniApp(agentController: AgentController) {
    val context = LocalContext.current
    val app = context.applicationContext as OmniApplication
    val scope = rememberCoroutineScope()
    var showAccessibilityDialog by remember { mutableStateOf(false) }
    var signingIn by remember { mutableStateOf(false) }
    var signInError by remember { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Determine the starting route based on account and onboarding state.
    // Null while we're still loading — render nothing to avoid flashing the wrong screen.
    var startDestination by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val authToken = app.settingsRepository.authToken.first()
        val onboardingComplete = app.settingsRepository.onboardingComplete.first()
        startDestination = when {
            authToken.isBlank() -> "welcome"
            !onboardingComplete -> "setup"
            else -> "home"
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Only nag about accessibility once the user has moved past welcome.
                scope.launch {
                    val onboardingComplete = app.settingsRepository.onboardingComplete.first()
                    showAccessibilityDialog =
                        onboardingComplete && OmniAccessibilityService.instance == null
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        val activity = context as? MainActivity ?: return@LaunchedEffect
        combine(
            app.settingsRepository.authToken,
            app.settingsRepository.onboardingComplete,
            app.settingsRepository.subscriptionStatus,
            app.settingsRepository.subscriptionPlan,
            app.settingsRepository.wakeWordEnabled,
        ) { authToken, onboardingComplete, subscriptionStatus, subscriptionPlan, wakeWordEnabled ->
            WakeServiceState(
                signedIn = authToken.isNotBlank(),
                onboardingComplete = onboardingComplete,
                subscriptionActive = subscriptionStatus == "active" && subscriptionPlan != "free",
                wakeWordEnabled = wakeWordEnabled,
            )
        }
            .distinctUntilChanged()
            .collect { state ->
                if (state.canSyncService) activity.startWakeWordIfReady()
            }
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

    LaunchedEffect(start) {
        if (start != "welcome") {
            runCatching { app.authRepository.refreshSession() }
                .onSuccess { session ->
                    app.settingsRepository.setAccountSession(
                        authToken = session.authToken,
                        email = session.email,
                        name = session.name,
                        subscriptionStatus = session.subscriptionStatus,
                        subscriptionPlan = session.subscriptionPlan,
                    )
                }
                .onFailure { error ->
                    if (error is UnauthorizedException) {
                        app.settingsRepository.clearAccountSession()
                        navController.navigate("welcome") {
                            popUpTo(0)
                            launchSingleTop = true
                        }
                    }
                }
        }
    }

    NavHost(navController = navController, startDestination = start) {
        composable("welcome") {
            WelcomeScreen(
                communityBuild = BuildConfig.COMMUNITY_BUILD,
                signingIn = signingIn,
                errorMessage = signInError,
                onContinueWithGoogle = {
                    if (!signingIn) scope.launch {
                        signingIn = true
                        signInError = null
                        runCatching {
                            val googleAccount = GoogleSignInClient(context).signIn()
                            val session = app.authRepository.signInWithGoogle(
                                idToken = googleAccount.idToken,
                                emailHint = googleAccount.email,
                                nameHint = googleAccount.displayName,
                            )
                            app.settingsRepository.setAccountSession(
                                authToken = session.authToken,
                                email = session.email,
                                name = session.name,
                                subscriptionStatus = session.subscriptionStatus,
                                subscriptionPlan = session.subscriptionPlan,
                            )
                        }.onSuccess {
                            navController.navigate("setup") {
                                popUpTo("welcome") { inclusive = true }
                            }
                        }.onFailure { error ->
                            signInError = error.message ?: "Google sign-in failed. Please try again."
                        }
                        signingIn = false
                    }
                },
                onContinueWithCommunity = {
                    if (!signingIn) scope.launch {
                        signingIn = true
                        signInError = null
                        runCatching { app.authRepository.signInForCommunity() }
                            .onSuccess { session ->
                                app.settingsRepository.setAccountSession(
                                    authToken = session.authToken,
                                    email = session.email,
                                    name = session.name,
                                    subscriptionStatus = session.subscriptionStatus,
                                    subscriptionPlan = session.subscriptionPlan,
                                )
                                navController.navigate("setup") {
                                    popUpTo("welcome") { inclusive = true }
                                }
                            }
                            .onFailure { error ->
                                signInError = error.message ?: "Community sign-in failed."
                            }
                        signingIn = false
                    }
                },
            )
        }
        composable("setup") {
            SetupScreen(onFinish = {
                scope.launch {
                    app.settingsRepository.setOnboardingComplete(true)
                    (context as? MainActivity)?.startWakeWordIfReady()
                    navController.navigate("home") {
                        popUpTo("setup") { inclusive = true }
                        launchSingleTop = true
                    }
                        }
            })
        }
        composable("home") {
            HomeScreen(
                agentController = agentController,
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToPaywall = { navController.navigate("paywall") },
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToPaywall = { navController.navigate("paywall") },
                onSignedOut = {
                    navController.navigate("welcome") {
                        popUpTo(0)
                        launchSingleTop = true
                    }
                },
            )
        }
        composable("paywall") {
            PaywallScreen(onBack = { navController.popBackStack() })
        }
    }
}

private data class WakeServiceState(
    val signedIn: Boolean,
    val onboardingComplete: Boolean,
    val subscriptionActive: Boolean,
    val wakeWordEnabled: Boolean,
) {
    val canSyncService: Boolean
        get() = signedIn && onboardingComplete && subscriptionActive
}

@Composable
private fun AccessibilitySetupDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 336.dp)
                .shadow(
                    elevation = 24.dp,
                    shape = OmniShapes.Card,
                    ambientColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.75f),
                    spotColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.75f),
                )
                .dockPillStyle(OmniShapes.Card)
                .padding(20.dp),
        ) {
            Text(
                "Enable Accessibility Service",
                style = TextStyle(
                    brush = OmniGradients.SilverText,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    lineHeight = 26.sp,
                ),
            )
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Omni needs the Accessibility Service to read your screen and perform actions on your behalf.",
                    color = OmniColors.InkDim,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Text(
                    "To enable it:",
                    fontWeight = FontWeight.SemiBold,
                    color = OmniColors.Ink,
                    fontSize = 13.sp,
                )
                Text(
                    "1. Tap \"Open Settings\" below\n" +
                    "2. Tap \"Installed apps\"\n" +
                    "3. Find \"Omni\" in the list\n" +
                    "4. Toggle it on and confirm",
                    color = OmniColors.InkDim,
                    fontSize = 13.sp,
                    lineHeight = 21.sp,
                )
                Text(
                    "Omni only reads the screen when you give it a command. No data is collected in the background.",
                    color = OmniColors.InkMute,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Later",
                    color = OmniColors.InkMute,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(OmniShapes.Pill)
                        .clickable { onDismiss() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
                Spacer(Modifier.width(6.dp))
                OmniButton(
                    onClick = onOpenSettings,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 11.dp),
                ) {
                    Text(
                        "Open Settings",
                        style = TextStyle(
                            brush = OmniGradients.SilverText,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                        ),
                    )
                }
            }
        }
    }
}

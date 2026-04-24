package com.buddy.assistant.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.buddy.assistant.service.BuddyAccessibilityService
import androidx.compose.ui.text.TextStyle
import com.buddy.assistant.ui.theme.BuddyColors
import com.buddy.assistant.ui.theme.BuddyGradients
import com.buddy.assistant.ui.theme.BuddyShapes
import com.buddy.assistant.ui.theme.BuddyTextMetrics
import com.buddy.assistant.ui.theme.OmniButton

/**
 * SetupScreen — mirrors Figma `2 · Permissions` (node 15:20).
 *
 * Layout:
 *   "Quick setup" (display) + "Three permissions for full power" (sub)
 *   Three permission cards — 332×96 each, 36dp circle badge (✓ if granted, number otherwise).
 *   Continue CTA at bottom.
 */
@Composable
fun SetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Re-evaluate on each resume so the badges flip to ✓ after the user
    // comes back from the system settings screen.
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val accessibilityGranted = remember(tick) { BuddyAccessibilityService.instance != null }
    val micGranted = remember(tick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    val overlayGranted = remember(tick) { Settings.canDrawOverlays(context) }

    val allGranted = accessibilityGranted && micGranted && overlayGranted

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BuddyGradients.Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = BuddyColors.Ink)
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "Quick setup",
                style = MaterialTheme.typography.displaySmall.copy(
                    brush = BuddyGradients.SilverText,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 0.5.sp,
                    fontSize = 40.sp,
                ),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Three permissions for full power",
                color = BuddyColors.InkMute,
                fontSize = 14.sp,
                fontWeight = FontWeight.Light,
            )

            Spacer(Modifier.height(28.dp))

            PermissionCard(
                index = 1,
                granted = accessibilityGranted,
                title = "Accessibility",
                description = "Read the screen, tap buttons.",
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
            )
            Spacer(Modifier.height(16.dp))
            PermissionCard(
                index = 2,
                granted = micGranted,
                title = "Microphone",
                description = "Hear \u201CHey Omni\u201D anytime.",
                onClick = {
                    // Microphone permission is requested at first use; nudging to app settings
                    // is the next best thing if the user denied it.
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
            )
            Spacer(Modifier.height(16.dp))
            PermissionCard(
                index = 3,
                granted = overlayGranted,
                title = "Overlay",
                description = "Show status over other apps.",
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
            )

            Spacer(Modifier.weight(1f))

            ContinueButton(enabled = allGranted, onClick = onBack)
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PermissionCard(
    index: Int,
    granted: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BuddyShapes.Card)
            .background(BuddyColors.Surface.copy(alpha = 0.55f))
            .border(1.dp, BuddyColors.InkGhost, BuddyShapes.Card)
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PermissionBadge(granted = granted, index = index)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = BuddyColors.Ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                description,
                color = BuddyColors.InkDim,
                fontSize = 13.sp,
                fontWeight = FontWeight.Light,
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = BuddyColors.InkMute,
        )
    }
}

@Composable
private fun PermissionBadge(granted: Boolean, index: Int) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .then(
                if (granted)
                    Modifier.background(BuddyColors.Success)
                else
                    Modifier.background(BuddyGradients.iris())
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (granted) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Granted",
                tint = BuddyColors.Ink,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Text(
                "$index",
                color = BuddyColors.Bg,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ContinueButton(enabled: Boolean, onClick: () -> Unit) {
    OmniButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        fillMaxWidth = true,
        contentPadding = PaddingValues(vertical = 18.dp),
    ) {
        if (enabled) {
            Text(
                text = "Continue",
                style = TextStyle(
                    brush = BuddyGradients.SilverText,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    letterSpacing = 0.4.sp,
                ),
            )
        } else {
            Text(
                text = "Grant permissions to continue",
                color = BuddyColors.InkMute,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.4.sp,
            )
        }
    }
}

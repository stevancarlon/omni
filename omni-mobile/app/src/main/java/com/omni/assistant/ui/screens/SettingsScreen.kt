package com.omni.assistant.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.omni.assistant.OmniApplication
import com.omni.assistant.service.OmniAccessibilityService
import com.omni.assistant.ui.theme.OmniColors
import com.omni.assistant.ui.theme.OmniGradients
import com.omni.assistant.ui.theme.OmniShapes
import com.omni.assistant.ui.theme.OmniTextMetrics
import com.omni.assistant.ui.theme.OmniToggle
import com.omni.assistant.ui.theme.dockPillStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * SettingsScreen — Buddy / Omni Settings (Figma node 243:3).
 *
 * Dark background, silver-gradient title, blue section labels, dark-metal
 * settings cards, and compact blue/orange permission status controls.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToPaywall: () -> Unit,
    onSignedOut: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OmniApplication
    val repo = app.settingsRepository
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Re-check permission state each time the user comes back from the
    // system settings screens so the status indicators update.
    var permTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val accessibilityGranted = remember(permTick) { OmniAccessibilityService.instance != null }
    val micGranted = remember(permTick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    val overlayGranted = remember(permTick) { Settings.canDrawOverlays(context) }

    var wakeWordEnabled by remember { mutableStateOf(true) }
    var wakeWord by remember { mutableStateOf("Hey Omni") }
    var ttsEnabled by remember { mutableStateOf(true) }
    var maxSteps by remember { mutableIntStateOf(30) }
    var speechLanguage by remember { mutableStateOf("") }
    var languageSheetOpen by remember { mutableStateOf(false) }
    var maxStepsSheetOpen by remember { mutableStateOf(false) }
    val accountEmail by repo.accountEmail.collectAsState(initial = "")
    val accountName by repo.accountName.collectAsState(initial = "")
    val subscriptionStatus by repo.subscriptionStatus.collectAsState(initial = "inactive")
    val subscriptionPlan by repo.subscriptionPlan.collectAsState(initial = "free")
    var privacyDialogOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        wakeWordEnabled = repo.wakeWordEnabled.first()
        wakeWord = repo.wakeWord.first()
        ttsEnabled = repo.ttsEnabled.first()
        maxSteps = repo.maxSteps.first()
        speechLanguage = repo.speechLanguage.first()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OmniGradients.Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = SettingsInk,
                    modifier = Modifier.size(22.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Settings",
                style = MaterialTheme.typography.displaySmall.copy(
                    brush = OmniGradients.SilverText,
                    fontWeight = FontWeight.Light,
                    fontSize = 40.sp,
                    letterSpacing = 0.5.sp,
                ),
                modifier = Modifier.padding(start = 13.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Tune Omni to fit how you work.",
                color = SettingsMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(start = 13.dp),
            )

            val languages = languageOptions()
            val selectedLanguageLabel = languages.firstOrNull { it.first == speechLanguage }?.second
                ?: "System default"

            // ─── Account ─────────────────────────────────────────────────────
            SectionLabel("ACCOUNT")
            SettingsCard {
                ProductRow(
                    title = accountName.ifBlank { "Google account" },
                    subtitle = accountEmail.ifBlank { "Signed in with Google" },
                    onClick = {},
                )
                Divider()
                ProductRow(
                    title = "Plan",
                    subtitle = if (subscriptionStatus == "active") "${subscriptionPlan.uppercase()} · active" else "Subscription inactive",
                    trailing = if (subscriptionStatus == "active") "Manage" else "Upgrade",
                    onClick = onNavigateToPaywall,
                )
                Divider()
                ProductRow(
                    title = "Privacy & data usage",
                    subtitle = "Screen context, voice, and retention",
                    onClick = { privacyDialogOpen = true },
                )
            }
            if (privacyDialogOpen) {
                PrivacyDialog(onDismiss = { privacyDialogOpen = false })
            }

            // ─── Wake Word ───────────────────────────────────────────────────
            SectionLabel("WAKE WORD")
            SettingsCard {
                ToggleRow(
                    title = "Enable wake word",
                    subtitle = "Listen for \"$wakeWord\"",
                    checked = wakeWordEnabled,
                    onChange = {
                        wakeWordEnabled = it
                        scope.launch { repo.setWakeWordEnabled(it) }
                    },
                )
                Divider()
                TextInputRow(
                    label = "Phrase",
                    value = wakeWord,
                    onChange = {
                        wakeWord = it
                        scope.launch { repo.setWakeWord(it) }
                    },
                )
                Divider()
                SelectorRow(
                    label = "Language",
                    value = selectedLanguageLabel,
                    onClick = { languageSheetOpen = true },
                )
            }
            if (languageSheetOpen) {
                OptionPickerSheet(
                    title = "Recognition Language",
                    options = languages,
                    current = speechLanguage,
                    onSelect = {
                        speechLanguage = it
                        scope.launch { repo.setSpeechLanguage(it) }
                        languageSheetOpen = false
                    },
                    onDismiss = { languageSheetOpen = false },
                )
            }

            // ─── Behavior ────────────────────────────────────────────────────
            SectionLabel("BEHAVIOR")
            SettingsCard {
                ToggleRow(
                    title = "Voice responses",
                    subtitle = "Omni speaks results out loud",
                    checked = ttsEnabled,
                    onChange = {
                        ttsEnabled = it
                        scope.launch { repo.setTtsEnabled(it) }
                    },
                )
                Divider()
                ValueRow(
                    title = "Max agent steps",
                    value = maxSteps,
                    onClick = { maxStepsSheetOpen = true },
                )
            }
            if (maxStepsSheetOpen) {
                OptionPickerSheet(
                    title = "Max Agent Steps",
                    options = (5..80 step 5).map { it.toString() to it.toString() },
                    current = maxSteps.toString(),
                    onSelect = {
                        maxSteps = it.toInt()
                        scope.launch { repo.setMaxSteps(maxSteps) }
                        maxStepsSheetOpen = false
                    },
                    onDismiss = { maxStepsSheetOpen = false },
                )
            }

            // ─── Permissions ─────────────────────────────────────────────────
            SectionLabel("PERMISSIONS")
            SettingsCard {
                PermissionRow(
                    title = "Accessibility",
                    subtitle = "Read the screen, tap buttons",
                    granted = accessibilityGranted,
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                )
                Divider()
                PermissionRow(
                    title = "Microphone",
                    subtitle = "Hear \u201CHey Omni\u201D anytime",
                    granted = micGranted,
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                )
                Divider()
                PermissionRow(
                    title = "Overlay",
                    subtitle = "Show status over other apps",
                    granted = overlayGranted,
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                )
            }

            Spacer(Modifier.height(24.dp))
            SignOutFooter(
                onClick = {
                    scope.launch {
                        runCatching { app.authRepository.logout() }
                        repo.clearAccountSession()
                        onSignedOut()
                    }
                },
            )
            Spacer(Modifier.height(28.dp))
            Text(
                "Omni v0.1",
                color = SettingsMuted,
                fontSize = 11.sp,
                letterSpacing = OmniTextMetrics.CapsTightSp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun PrivacyDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101114),
        title = {
            Text("Privacy & data", color = SettingsInk, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Text(
                "Omni sends screen context and your command to the Omni backend only when you ask it to act. Provider API keys stay on the backend. Voice sessions use short-lived backend-issued speech tokens.",
                color = SettingsMuted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        },
        confirmButton = {
            Text(
                "Done",
                color = OmniColors.BrandBlueGlow,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { onDismiss() }
                    .padding(12.dp),
            )
        },
    )
}

// ─── Section label ──────────────────────────────────────────────────────────

private val SettingsInk = Color(0xFFE9ECF2)
private val SettingsMuted = Color(0xFFA7ADBC)
private val SettingsDivider = Color(0xFF282A35)
private val SettingsChevron = Color(0xFF6B6E76)
private val SettingsOrange = Color(0xFFFF8957)
private val SettingsBlueStatus = Brush.horizontalGradient(
    colors = listOf(Color(0xFF1E3B6E), OmniColors.BrandBlueGlow),
)
private val SettingsOrangeStatus = Brush.horizontalGradient(
    colors = listOf(Color(0xFF85462C), SettingsOrange),
)

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(28.dp))
    Text(
        text,
        color = OmniColors.BrandBlue,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 1.dp),
    )
    Spacer(Modifier.height(12.dp))
}

// ─── Card ───────────────────────────────────────────────────────────────────

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 12.dp,
                shape = OmniShapes.Card,
                ambientColor = Color.Black.copy(alpha = 0.70f),
                spotColor = Color.Black.copy(alpha = 0.70f),
            )
            .dockPillStyle(OmniShapes.Card),
        content = content,
    )
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(1.dp)
            .background(SettingsDivider),
    )
}

// ─── Rows ───────────────────────────────────────────────────────────────────

@Composable
private fun ProductRow(
    title: String,
    subtitle: String,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = SettingsInk, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, color = SettingsMuted, fontSize = 12.sp, fontWeight = FontWeight.Light)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                trailing,
                color = SettingsMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Light,
            )
        }
        Spacer(Modifier.width(10.dp))
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = SettingsChevron,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SignOutFooter(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 12.dp,
                shape = OmniShapes.Card,
                ambientColor = Color.Black.copy(alpha = 0.70f),
                spotColor = Color.Black.copy(alpha = 0.70f),
            )
            .dockPillStyle(OmniShapes.Card)
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 13.dp),
    ) {
        Text("Sign out", color = SettingsOrange, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(3.dp))
        Text(
            "Remove this account from Omni",
            color = SettingsMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Light,
        )
    }
}

@Composable
private fun TextInputRow(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(label, color = SettingsMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(
                color = SettingsInk,
                fontSize = 15.sp,
                fontWeight = FontWeight.Light,
            ),
            cursorBrush = SolidColor(OmniColors.BrandBlue),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = SettingsInk, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = SettingsMuted, fontSize = 12.sp, fontWeight = FontWeight.Light)
        }
        Spacer(Modifier.width(12.dp))
        PermissionStatus(granted = granted)
        Spacer(Modifier.width(10.dp))
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = SettingsChevron,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun PermissionStatus(granted: Boolean) {
    val glow = if (granted) OmniColors.BrandBlueGlow else SettingsOrange
    Box(
        modifier = Modifier
            .size(21.dp)
            .shadow(
                elevation = 10.dp,
                shape = CircleShape,
                ambientColor = glow.copy(alpha = 0.45f),
                spotColor = glow.copy(alpha = 0.45f),
            )
            .clip(CircleShape)
            .background(if (granted) SettingsBlueStatus else SettingsOrangeStatus),
        contentAlignment = Alignment.Center,
    ) {
        if (granted) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Granted",
                tint = SettingsInk,
                modifier = Modifier.size(13.dp),
            )
        } else {
            Text(
                "!",
                style = TextStyle(
                    brush = OmniGradients.SilverText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = SettingsInk, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = SettingsMuted, fontSize = 12.sp, fontWeight = FontWeight.Light)
            }
        }
        OmniToggle(checked = checked, onChange = onChange)
    }
}

@Composable
private fun SelectorRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = SettingsMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(value, color = SettingsInk, fontSize = 15.sp, fontWeight = FontWeight.Light)
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = SettingsChevron,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ValueRow(
    title: String,
    value: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = SettingsInk,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Text("$value", color = SettingsInk, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StepperRow(
    title: String,
    subtitle: String?,
    value: Int,
    onChange: (Int) -> Unit,
    min: Int,
    max: Int,
    step: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = OmniColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = OmniColors.InkMute, fontSize = 12.sp, fontWeight = FontWeight.Light)
            }
        }
        StepperButton("\u2212", enabled = value > min) {
            onChange((value - step).coerceAtLeast(min))
        }
        Box(
            modifier = Modifier
                .padding(horizontal = 10.dp)
                .widthIn(min = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("$value", color = OmniColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        StepperButton("+", enabled = value < max) {
            onChange((value + step).coerceAtMost(max))
        }
    }
}

@Composable
private fun StepperButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(if (enabled) OmniColors.SurfaceHi else OmniColors.Surface.copy(alpha = 0.4f))
            .border(1.dp, OmniColors.InkGhost, CircleShape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            color = if (enabled) OmniColors.Ink else OmniColors.InkMute,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SelectorCard(
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OmniShapes.CardLg)
            .background(OmniColors.Surface.copy(alpha = 0.55f))
            .border(1.dp, OmniColors.InkGhost, OmniShapes.CardLg)
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = OmniColors.InkMute, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(value, color = OmniColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = OmniColors.InkMute)
    }
}

// ─── Picker sheet (simple modal via Dialog-like overlay) ────────────────────

@Composable
private fun OptionPickerSheet(
    title: String,
    options: List<Pair<String, String>>,
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(OmniShapes.CardLg)
                .background(OmniColors.Surface)
                .border(1.dp, OmniColors.InkGhost, OmniShapes.CardLg)
                .padding(20.dp),
        ) {
            Text(title, color = OmniColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(16.dp))
            options.forEach { (id, label) ->
                val selected = id == current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(OmniShapes.Field)
                        .background(
                            if (selected) OmniColors.SurfaceHi else Color.Transparent
                        )
                        .clickable { onSelect(id) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .border(
                                1.5.dp,
                                if (selected) OmniColors.Accent else OmniColors.InkMute,
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(OmniColors.Accent),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(label, color = OmniColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.Light)
                }
            }
        }
    }
}

// ─── Data helpers ───────────────────────────────────────────────────────────

private fun languageOptions(): List<Pair<String, String>> = listOf(
    "" to "System Default",
    "en-US" to "English (US)",
    "en-GB" to "English (UK)",
    "pt-BR" to "Portuguese (Brazil)",
    "pt-PT" to "Portuguese (Portugal)",
    "es-ES" to "Spanish (Spain)",
    "es-MX" to "Spanish (Mexico)",
    "fr-FR" to "French",
    "de-DE" to "German",
    "it-IT" to "Italian",
    "ja-JP" to "Japanese",
    "ko-KR" to "Korean",
    "zh-CN" to "Chinese (Simplified)",
    "hi-IN" to "Hindi",
)

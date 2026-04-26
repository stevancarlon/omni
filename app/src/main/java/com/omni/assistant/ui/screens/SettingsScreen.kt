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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * SettingsScreen — Figma `7 · Settings` (node 47:2).
 *
 * Dark gradient background, display-small "Settings" title, accent-colored
 * section labels, pill-shaped provider chips with iris gradient when selected,
 * card-styled input rows, and custom iris-gradient toggles.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
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
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = OmniColors.Ink)
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
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Tune Omni to fit how you work.",
                color = OmniColors.InkMute,
                fontSize = 13.sp,
                fontWeight = FontWeight.Light,
            )

            // ─── Permissions ─────────────────────────────────────────────────
            SectionLabel("PERMISSIONS")
            SettingsCard {
                PermissionRow(
                    title = "Accessibility",
                    subtitle = "Read the screen, tap buttons.",
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
                    subtitle = "Hear \u201CHey Omni\u201D anytime.",
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
                    subtitle = "Show status over other apps.",
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

            // ─── Wake Word ───────────────────────────────────────────────────
            SectionLabel("WAKE WORD")
            SettingsCard {
                ToggleRow(
                    title = "Enable wake word",
                    subtitle = "Listen continuously for \"$wakeWord\"",
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
            }

            // ─── Speech ──────────────────────────────────────────────────────
            SectionLabel("SPEECH")
            val languages = languageOptions()
            val selectedLanguageLabel = languages.firstOrNull { it.first == speechLanguage }?.second
                ?: "System Default"
            var languageSheetOpen by remember { mutableStateOf(false) }
            SelectorCard(
                title = "Language",
                value = selectedLanguageLabel,
                onClick = { languageSheetOpen = true },
            )
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
                StepperRow(
                    title = "Max agent steps",
                    subtitle = "Cap the number of actions per task",
                    value = maxSteps,
                    onChange = {
                        maxSteps = it
                        scope.launch { repo.setMaxSteps(it) }
                    },
                    min = 5,
                    max = 80,
                    step = 5,
                )
            }

            Spacer(Modifier.height(36.dp))
            Text(
                "Omni v0.1",
                color = OmniColors.InkMute,
                fontSize = 11.sp,
                letterSpacing = OmniTextMetrics.CapsTightSp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(48.dp))
        }
    }
}

// ─── Section label ──────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(28.dp))
    Text(
        text,
        color = OmniColors.Accent,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = OmniTextMetrics.CapsTightSp,
    )
    Spacer(Modifier.height(12.dp))
}

// ─── Card ───────────────────────────────────────────────────────────────────

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OmniShapes.CardLg)
            .background(OmniColors.Surface.copy(alpha = 0.55f))
            .border(1.dp, OmniColors.InkGhost, OmniShapes.CardLg),
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
            .background(OmniColors.InkGhost),
    )
}

// ─── Rows ───────────────────────────────────────────────────────────────────

@Composable
private fun TextInputRow(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(label, color = OmniColors.InkMute, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(
                color = OmniColors.Ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.Light,
            ),
            cursorBrush = SolidColor(OmniColors.Accent),
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
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = OmniColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = OmniColors.InkMute, fontSize = 12.sp, fontWeight = FontWeight.Light)
        }
        Spacer(Modifier.width(12.dp))
        PermissionStatus(granted = granted)
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = OmniColors.InkMute,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun PermissionStatus(granted: Boolean) {
    if (granted) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(OmniColors.Success),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Granted",
                tint = OmniColors.Ink,
                modifier = Modifier.size(14.dp),
            )
        }
    } else {
        Text(
            "Grant",
            color = OmniColors.Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.sp,
            modifier = Modifier
                .clip(OmniShapes.Pill)
                .border(1.dp, OmniColors.Accent.copy(alpha = 0.5f), OmniShapes.Pill)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
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
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = OmniColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = OmniColors.InkMute, fontSize = 12.sp, fontWeight = FontWeight.Light)
            }
        }
        OmniToggle(checked = checked, onChange = onChange)
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

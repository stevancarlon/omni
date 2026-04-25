package com.buddy.assistant.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.buddy.assistant.R
import com.buddy.assistant.data.SettingsRepository
import com.buddy.assistant.service.BuddyAccessibilityService
import com.buddy.assistant.ui.theme.BuddyColors
import com.buddy.assistant.ui.theme.BuddyGradients
import com.buddy.assistant.ui.theme.BuddyShapes
import com.buddy.assistant.ui.theme.OmniButton
import com.buddy.assistant.ui.theme.ctaPillStyle
import com.buddy.assistant.ui.theme.dockPillStyle
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * SetupScreen — DS 2.0 three-step onboarding wizard. Faithful to Figma
 * `Setup · Microphone permission` / `Setup · Accessibility service` /
 * `Setup · Provider selection` (1/2/3 of 3).
 *
 * Visual signatures driving the layout:
 *  - Pure `#0A0A0C` background
 *  - Plain white bold title (~32sp), no silver gradient
 *  - Cards (hero, capability rows, provider rows) use the dock-pill gradient
 *    (`#26272C → #1C1D22 → #101114`) with hairline `#2A2B32`
 *  - Selected provider swaps the gray hairline for the blue CTA hairline
 *    `rgba(108,184,255,0.55)`
 *  - CTA uses `OmniButton` — same dark-metal body + blue hairline + 24dp blue
 *    outer glow
 *  - All icons (mic, eye, bolt, chat, check) come from drawable XML lifted
 *    verbatim from the Figma source — no Material Icons in this screen
 */
@Composable
fun SetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val repo = remember { SettingsRepository(context.applicationContext) }

    var tick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val micGranted = remember(tick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    val accessibilityGranted = remember(tick) { BuddyAccessibilityService.instance != null }

    var step by remember { mutableIntStateOf(0) }
    var selectedProvider by remember { mutableStateOf("claude") }

    LaunchedEffect(Unit) {
        val saved = repo.llmProvider.firstOrNull()
        if (saved != null && saved in setOf("claude", "openrouter", "groq")) {
            selectedProvider = saved
        }
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) step = 1
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BuddyColors.Bg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            Box(modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = { if (step == 0) onBack() else step -= 1 },
                    modifier = Modifier
                        .size(40.dp)
                        .align(Alignment.CenterStart),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = BuddyColors.Ink,
                    )
                }
                Text(
                    "${step + 1} of 3",
                    color = BuddyColors.InkMute,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 0.6.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            Spacer(Modifier.height(24.dp))

            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = targetState > initialState
                    val dir = if (forward) 1 else -1
                    (slideInHorizontally(tween(280)) { it * dir / 4 } + fadeIn(tween(280))) togetherWith
                        (slideOutHorizontally(tween(220)) { -it * dir / 4 } + fadeOut(tween(180)))
                },
                label = "setup-step",
                modifier = Modifier.weight(1f),
            ) { current ->
                when (current) {
                    0 -> MicrophoneStep(
                        granted = micGranted,
                        onAllow = {
                            if (micGranted) step = 1
                            else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onSkip = { step = 1 },
                    )
                    1 -> AccessibilityStep(
                        granted = accessibilityGranted,
                        onOpen = {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                        onSkip = { step = 2 },
                        onContinue = { step = 2 },
                    )
                    else -> ProviderStep(
                        selected = selectedProvider,
                        onSelect = { selectedProvider = it },
                        onFinish = {
                            scope.launch { repo.setLlmProvider(selectedProvider) }
                            if (!Settings.canDrawOverlays(context)) {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                            onBack()
                        },
                    )
                }
            }
        }
    }
}

// ─── Step 1: Microphone ────────────────────────────────────────────────────

@Composable
private fun MicrophoneStep(
    granted: Boolean,
    onAllow: () -> Unit,
    onSkip: () -> Unit,
) {
    StepScaffold(
        title = "Microphone",
        description = "Omni listens for your wake word. We never send raw audio off the device without your command.",
        hero = {
            HeroCard {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_mic_silver),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(60.dp))
                    Text(
                        if (granted) "Microphone permission granted"
                        else "Needed for wake word detection",
                        color = BuddyColors.InkMute,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 0.2.sp,
                    )
                }
            }
        },
        ctaLabel = "Allow microphone",
        onCta = onAllow,
        skipLabel = if (granted) null else "Skip for now",
        onSkip = onSkip,
    )
}

// ─── Step 2: Accessibility ─────────────────────────────────────────────────

@Composable
private fun AccessibilityStep(
    granted: Boolean,
    onOpen: () -> Unit,
    onSkip: () -> Unit,
    onContinue: () -> Unit,
) {
    StepScaffold(
        title = "Accessibility",
        description = "Lets Omni read the screen and tap buttons for you. This is how the agent actually does things.",
        hero = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                CapabilityPill(painterResource(R.drawable.ic_eye), "Read content on screen")
                CapabilityPill(painterResource(R.drawable.ic_bolt), "Perform taps & scrolls")
                CapabilityPill(painterResource(R.drawable.ic_chat), "Type into input fields")
            }
        },
        ctaLabel = "Open accessibility settings",
        onCta = if (granted) onContinue else onOpen,
        skipLabel = if (granted) null else "Skip for now",
        onSkip = onSkip,
    )
}

@Composable
private fun CapabilityPill(icon: Painter, label: String) {
    // DS 2.0 row card — gradient body + bevel + gray hairline (`dockPillStyle`).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .dockPillStyle(BuddyShapes.Pill)
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painter = icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            color = BuddyColors.Ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.2.sp,
        )
    }
}

// ─── Step 3: Provider ──────────────────────────────────────────────────────

private data class ProviderOption(val key: String, val initial: String, val name: String, val subtitle: String)

private val providerOptions = listOf(
    ProviderOption("claude", "C", "Claude", "Anthropic"),
    ProviderOption("openrouter", "O", "OpenRouter", "Mix of models"),
    ProviderOption("groq", "G", "Groq", "Fast inference"),
)

@Composable
private fun ProviderStep(
    selected: String,
    onSelect: (String) -> Unit,
    onFinish: () -> Unit,
) {
    StepScaffold(
        title = "Pick a brain",
        description = "Omni needs an LLM to think. Paste an API key or pick your provider.",
        hero = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                providerOptions.forEach { opt ->
                    ProviderPill(
                        option = opt,
                        selected = opt.key == selected,
                        onClick = { onSelect(opt.key) },
                    )
                }
            }
        },
        ctaLabel = "Finish setup",
        onCta = onFinish,
        skipLabel = null,
        onSkip = {},
    )
}

@Composable
private fun ProviderPill(option: ProviderOption, selected: Boolean, onClick: () -> Unit) {
    // Same dark-metal gradient body for every row. When selected, swap the
    // gray hairline for the blue CTA hairline (`ctaPillStyle`) — the row
    // doesn't get the outer glow (that's reserved for the actual CTA).
    val style: Modifier = if (selected) {
        Modifier.ctaPillStyle(BuddyShapes.Pill)
    } else {
        Modifier.dockPillStyle(BuddyShapes.Pill)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(style)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .dockPillStyle(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                option.initial,
                color = BuddyColors.Ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                option.name,
                color = BuddyColors.Ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.2.sp,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                option.subtitle,
                color = BuddyColors.InkMute,
                fontSize = 12.sp,
                fontWeight = FontWeight.Light,
            )
        }
        if (selected) {
            Image(
                painter = painterResource(R.drawable.ic_check_blue),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ─── Shared scaffolding ────────────────────────────────────────────────────

@Composable
private fun StepScaffold(
    title: String,
    description: String,
    hero: @Composable () -> Unit,
    ctaLabel: String,
    onCta: () -> Unit,
    skipLabel: String?,
    onSkip: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            title,
            color = BuddyColors.Ink,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.2).sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            description,
            color = BuddyColors.InkMute,
            fontSize = 13.sp,
            fontWeight = FontWeight.Light,
            lineHeight = 19.sp,
        )

        Spacer(Modifier.height(28.dp))

        Box(modifier = Modifier.fillMaxWidth()) {
            hero()
        }

        Spacer(Modifier.weight(1f))

        // Primary CTA — `OmniButton` carries the DS 2.0 blue accent: blue
        // hairline (0.55α) + 24dp blue outer glow on the elevation shadow.
        OmniButton(
            onClick = onCta,
            fillMaxWidth = true,
            shape = BuddyShapes.Pill,
            contentPadding = PaddingValues(vertical = 18.dp),
        ) {
            Text(
                ctaLabel,
                style = TextStyle(
                    brush = BuddyGradients.SilverText,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    letterSpacing = 0.4.sp,
                ),
            )
        }

        Spacer(Modifier.height(12.dp))

        if (skipLabel != null) {
            Text(
                skipLabel,
                color = BuddyColors.InkMute,
                fontSize = 13.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 0.2.sp,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSkip() }
                    .padding(8.dp),
            )
        } else {
            Spacer(Modifier.height(33.dp))
        }

        Spacer(Modifier.height(20.dp))
    }
}

// Hero card — DS 2.0 dark-metal gradient + bevel + gray hairline. Same body
// language as every other card on the screen, just taller.
@Composable
private fun HeroCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .dockPillStyle(RoundedCornerShape(24.dp))
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

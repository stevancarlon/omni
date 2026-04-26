package com.omni.assistant.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.omni.assistant.R
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.assistant.OmniApplication
import com.omni.assistant.agent.AgentController
import com.omni.assistant.data.AgentStatus
import com.omni.assistant.ui.MainActivity
import com.omni.assistant.ui.components.OmniOrb
import com.omni.assistant.ui.components.OmniOrbPerformance
import com.omni.assistant.ui.theme.OmniColors
import com.omni.assistant.ui.theme.OmniGradients
import com.omni.assistant.ui.theme.OmniShapes
import com.omni.assistant.ui.theme.OmniTextMetrics
import com.omni.assistant.ui.theme.GradientIcon
import com.omni.assistant.ui.theme.OmniIconButton
import com.omni.assistant.ui.theme.dockPillStyle

/**
 * HomeScreen — reproduces Figma `06 · Screens` frames 15:43 (Home Idle),
 * 15:65 (Listening), 15:82 (Thinking). Layout is state-driven: the top
 * chrome (omni title + credit pill) stays, the middle morphs based on
 * [AgentStatus], and the bottom switches between the type-command bar
 * (idle) and contextual actions (cancel / stop).
 */
@Composable
fun HomeScreen(
    agentController: AgentController,
    onNavigateToSettings: () -> Unit,
    onNavigateToSetup: () -> Unit,
    onNavigateToCredits: () -> Unit = {},
) {
    val status by agentController.status.collectAsState()
    val log by agentController.log.collectAsState()
    val think by agentController.currentThink.collectAsState()
    val context = LocalContext.current
    val activity = context as? MainActivity
    val app = context.applicationContext as OmniApplication
    val credits by app.settingsRepository.creditsBalance.collectAsState(initial = 0)

    // Debug: long-press the "omni" wordmark to cycle through orb states
    // so we can visually QA each one without a live LLM run.
    val debugCycle = remember {
        listOf<AgentStatus>(
            AgentStatus.Idle,
            AgentStatus.WakeWordListening,
            AgentStatus.VoiceListening,
            AgentStatus.Processing(goal = "Plan the next move"),
            AgentStatus.Executing(step = 3, maxSteps = 10, goal = "Open the calendar", lastAction = "Tap Calendar"),
            AgentStatus.Speaking(text = "All set."),
            AgentStatus.Done(success = true, reason = "Task completed."),
            AgentStatus.Error(message = "Network unavailable."),
        )
    }
    var debugIdx by remember { mutableIntStateOf(0) }

    Box(modifier = Modifier.fillMaxSize().background(OmniGradients.Background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .imePadding()
        ) {
            Spacer(Modifier.height(16.dp))
            HomeTopBar(
                credits = credits,
                onSetup = onNavigateToSetup,
                onSettings = onNavigateToSettings,
                onCredits = onNavigateToCredits,
                onDebugCycle = {
                    debugIdx = (debugIdx + 1) % debugCycle.size
                    agentController.debugSetStatus(debugCycle[debugIdx])
                },
                onDebugOverlay = { activity?.debugToggleOverlay() },
            )

            AnimatedContent(
                targetState = stateBucket(status),
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                label = "home-state",
                modifier = Modifier.weight(1f)
            ) { bucket ->
                when (bucket) {
                    Bucket.Idle -> IdleLayout(status)
                    Bucket.Listening -> ListeningLayout(status, think)
                    Bucket.Thinking -> ThinkingLayout(status, log)
                    Bucket.Speaking -> SpeakingLayout(status)
                    Bucket.Done -> DoneLayout(status)
                    Bucket.Error -> ErrorLayout(status, activity)
                }
            }

            BottomArea(status, activity, agentController)
        }
    }
}

private enum class Bucket { Idle, Listening, Thinking, Speaking, Done, Error }

private fun stateBucket(status: AgentStatus): Bucket = when (status) {
    is AgentStatus.Idle -> Bucket.Idle
    is AgentStatus.WakeWordListening,
    is AgentStatus.VoiceListening -> Bucket.Listening
    is AgentStatus.Processing,
    is AgentStatus.Executing -> Bucket.Thinking
    is AgentStatus.Speaking -> Bucket.Speaking
    is AgentStatus.Done -> Bucket.Done
    is AgentStatus.Error -> Bucket.Error
}

// ─── Top bar ────────────────────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HomeTopBar(
    credits: Int,
    onSetup: () -> Unit,
    onSettings: () -> Unit,
    onCredits: () -> Unit,
    onDebugCycle: () -> Unit = {},
    onDebugOverlay: () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "omni",
            style = TextStyle(
                brush = OmniGradients.SilverText,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                letterSpacing = 3.sp,
            ),
            modifier = Modifier.combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = onDebugCycle,
            ),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            CreditPill(credits = credits, onClick = onCredits, onLongClick = onDebugOverlay)
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onSettings, modifier = Modifier.size(32.dp)) {
                // DS 2.0 settings gear — Figma node 319:2, two-stop silver gradient.
                Image(
                    painter = painterResource(R.drawable.ic_settings),
                    contentDescription = "Settings",
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CreditPill(credits: Int, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .clip(OmniShapes.Pill)
            .background(OmniColors.Surface.copy(alpha = 0.55f))
            .border(1.dp, OmniColors.InkGhost, OmniShapes.Pill)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(OmniColors.Success)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "$credits cr",
            color = OmniColors.InkDim,
            fontSize = 11.sp,
            letterSpacing = 0.5.sp,
        )
    }
}

// ─── Idle ───────────────────────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun IdleLayout(status: AgentStatus) {
    // DS 2.0 idle: orb centered with two-line prompt below. `compact` is a
    // single boolean driven by IME visibility (target, not the per-frame
    // animated inset) so we don't re-measure the shader orb on every keyboard
    // animation frame — that was causing jank when the keyboard slides up.
    val compact = WindowInsets.isImeVisible
    val orbSize = if (compact) 140.dp else 200.dp
    val gap = if (compact) 20.dp else 40.dp
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OmniOrb(
            status = status,
            modifier = Modifier.size(orbSize),
            performance = if (compact) OmniOrbPerformance.Static else OmniOrbPerformance.Efficient,
        )
        Spacer(Modifier.height(gap))
        Text(
            text = if (compact) "Ready when you are." else "Say \u201CHey Omni\u201D",
            style = TextStyle(
                brush = OmniGradients.SilverText,
                fontSize = 22.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 0.4.sp,
            ),
        )
        if (!compact) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "or tap the orb to speak",
                color = OmniColors.InkMute,
                fontSize = 13.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 0.2.sp,
            )
        }
    }
}

// ─── Listening ──────────────────────────────────────────────────────────────

@Composable
private fun ListeningLayout(status: AgentStatus, think: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))
        Text(
            "Listening\u2026",
            style = MaterialTheme.typography.titleLarge.copy(
                color = OmniColors.Ink,
                fontWeight = FontWeight.Light,
                letterSpacing = 1.sp,
            ),
        )
        Spacer(Modifier.weight(1f))
        OmniOrb(status = status, modifier = Modifier.size(260.dp))
        Spacer(Modifier.weight(1f))
        TranscriptCard(think)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TranscriptCard(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OmniShapes.Card)
            .background(OmniColors.Surface.copy(alpha = 0.5f))
            .border(1.dp, OmniColors.InkGhost, OmniShapes.Card)
            .padding(20.dp),
    ) {
        Text(
            "YOU SAID",
            color = OmniColors.Accent,
            fontSize = 10.sp,
            letterSpacing = OmniTextMetrics.CapsTightSp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (text.isBlank()) "\u2026" else "\u201C$text\u201D",
            color = OmniColors.Ink,
            fontSize = 16.sp,
            fontWeight = FontWeight.Light,
            lineHeight = 22.sp,
        )
    }
}

// ─── Thinking / Executing ───────────────────────────────────────────────────

@Composable
private fun ThinkingLayout(status: AgentStatus, log: List<String>) {
    val (stepNumber, maxSteps) = when (status) {
        is AgentStatus.Executing -> status.step to status.maxSteps
        else -> 1 to 1
    }
    val headline = when (status) {
        is AgentStatus.Executing -> status.lastAction.ifBlank { "Working\u2026" }
        is AgentStatus.Processing -> "Thinking\u2026"
        else -> "Working\u2026"
    }
    val subline = when (status) {
        is AgentStatus.Processing -> status.goal
        is AgentStatus.Executing -> "Towards: ${status.goal}"
        else -> ""
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            "RUNNING TASK",
            color = OmniColors.Accent,
            fontSize = 10.sp,
            letterSpacing = OmniTextMetrics.CapsTightSp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Step $stepNumber of $maxSteps",
            color = OmniColors.InkDim,
            fontSize = 14.sp,
            fontWeight = FontWeight.Light,
        )

        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            OmniOrb(status = status, modifier = Modifier.size(160.dp))
        }

        Text(
            headline,
            color = OmniColors.Ink,
            fontSize = 20.sp,
            fontWeight = FontWeight.Light,
            lineHeight = 26.sp,
        )
        if (subline.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                subline,
                color = OmniColors.InkMute,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }

        Spacer(Modifier.height(16.dp))
        ProgressBar(progress = stepNumber.toFloat() / maxSteps.coerceAtLeast(1))

        Spacer(Modifier.height(20.dp))
        StepList(log.takeLast(5))
    }
}

@Composable
private fun ProgressBar(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(OmniColors.Surface.copy(alpha = 0.6f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(OmniGradients.iris()),
        )
    }
}

@Composable
private fun StepList(entries: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        entries.forEach { entry ->
            val text = entry.substringAfter("] ")
            val color = when {
                text.startsWith("Result:") && text.contains("success") -> OmniColors.Success
                text.startsWith("Result:") && text.contains("failed") -> OmniColors.Error
                text.startsWith("Action:") -> OmniColors.AuroraPink
                text.startsWith("Think:") -> OmniColors.AuroraLavender
                else -> OmniColors.InkMute
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 2.dp),
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.85f)),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text,
                    color = OmniColors.InkDim,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Light,
                    maxLines = 1,
                )
            }
        }
    }
}

// ─── Speaking / Done / Error ────────────────────────────────────────────────

@Composable
private fun SpeakingLayout(status: AgentStatus) {
    val text = (status as? AgentStatus.Speaking)?.text ?: ""
    CenteredCaption(status = status, caption = "\u201C$text\u201D", tone = OmniColors.AuroraPeach)
}

@Composable
private fun DoneLayout(status: AgentStatus) {
    val done = status as? AgentStatus.Done
    val success = done?.success == true
    val tone = if (success) OmniColors.Success else OmniColors.Error
    CenteredCaption(
        status = status,
        caption = if (success) "Done! ${done?.reason.orEmpty()}" else "Couldn\u2019t complete: ${done?.reason.orEmpty()}",
        tone = tone,
    )
}

@Composable
private fun ErrorLayout(status: AgentStatus, activity: MainActivity?) {
    CenteredCaption(
        status = status,
        caption = (status as? AgentStatus.Error)?.message ?: "Something went wrong.",
        tone = OmniColors.Error,
    )
}

@Composable
private fun CenteredCaption(status: AgentStatus, caption: String, tone: Color) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        OmniOrb(
            status = status,
            modifier = Modifier.size(220.dp),
            performance = OmniOrbPerformance.Static,
        )
        Spacer(Modifier.height(32.dp))
        Text(
            caption,
            color = OmniColors.Ink,
            fontSize = 18.sp,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .width(48.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(tone.copy(alpha = 0.7f))
        )
        Spacer(Modifier.weight(1f))
    }
}

// ─── Bottom area ────────────────────────────────────────────────────────────

@Composable
private fun BottomArea(
    status: AgentStatus,
    activity: MainActivity?,
    agentController: AgentController,
) {
    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        when (status) {
            is AgentStatus.Idle, is AgentStatus.Done, is AgentStatus.Error -> {
                TypeCommandBar(
                    onMic = { activity?.startListenerService() },
                    onSubmit = { text ->
                        if (text.isNotBlank()) agentController.onCommandReceived(text)
                    },
                )
                Spacer(Modifier.height(24.dp))
            }
            is AgentStatus.WakeWordListening, is AgentStatus.VoiceListening -> {
                CancelButton("Cancel") { activity?.stopCommandListening() }
                Spacer(Modifier.height(24.dp))
            }
            is AgentStatus.Processing, is AgentStatus.Executing -> {
                CancelButton("Stop task") { agentController.reset() }
                Spacer(Modifier.height(24.dp))
            }
            is AgentStatus.Speaking -> Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TypeCommandBar(onMic: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var isFocused by remember { mutableStateOf(false) }
    val inputShape = OmniShapes.Pill
    val submitCommand = {
        val command = text.trim()
        if (command.isNotBlank()) {
            onSubmit(command)
            text = ""
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .shadow(
                    elevation = if (isFocused) 14.dp else 0.dp,
                    shape = inputShape,
                    ambientColor = OmniColors.BrandBlueGlow.copy(alpha = 0.35f),
                    spotColor = OmniColors.BrandBlueGlow.copy(alpha = 0.35f),
                )
                .clip(inputShape)
                .background(OmniGradients.DockPill)
                .background(OmniGradients.DockInnerShadow)
                .border(
                    width = if (isFocused) 1.5.dp else 1.dp,
                    color = if (isFocused) OmniColors.BrandBlueGlow else OmniColors.Hairline,
                    shape = inputShape,
                )
                .padding(horizontal = 22.dp, vertical = 16.dp),
            contentAlignment = Alignment.TopStart,
        ) {
            if (text.isEmpty()) {
                Text(
                    "Type a command...",
                    color = Color(0xFFA3A6AE),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = false,
                cursorBrush = SolidColor(OmniColors.BrandBlueGlow),
                textStyle = TextStyle(
                    color = OmniColors.Ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                keyboardActions = KeyboardActions.Default,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { isFocused = it.isFocused },
            )
        }
        Spacer(Modifier.width(12.dp))
        MicFab(
            icon = if (text.isBlank()) Icons.Default.Mic else Icons.AutoMirrored.Filled.ArrowForward,
            onClick = {
                if (text.isBlank()) onMic() else submitCommand()
            },
        )
    }
}

@Composable
private fun MicFab(icon: ImageVector, onClick: () -> Unit) {
    // DS 2.0: circular dock-pill FAB. The mic state uses the Figma vector
    // drawable (silver gradient lifted from the source); the send state keeps
    // the Material arrow tinted via the same brush for visual consistency.
    OmniIconButton(onClick = onClick, size = 56.dp) {
        if (icon == Icons.Default.Mic) {
            Image(
                painter = painterResource(R.drawable.ic_mic_small),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
        } else {
            GradientIcon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun CancelButton(label: String, onClick: () -> Unit) {
    // Figma spec for secondary CTA on Listening / Thinking screens:
    //   fill white α0.06, border white α0.16, ink text, pill corner.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OmniShapes.Pill)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), OmniShapes.Pill)
            .clickable { onClick() }
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = OmniColors.Ink,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
        )
    }
}

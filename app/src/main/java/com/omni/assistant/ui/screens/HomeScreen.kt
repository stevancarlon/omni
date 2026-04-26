package com.omni.assistant.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.ui.text.style.TextOverflow
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
import kotlin.math.PI
import kotlin.math.sin

/**
 * HomeScreen — reproduces Figma `06 · Screens` frames 15:43 (Home Idle),
 * 15:65 (Listening), 15:82 (Thinking). Layout is state-driven: the top
 * chrome stays, the middle morphs based on
 * [AgentStatus], and the bottom switches between the type-command bar
 * (idle) and contextual actions (cancel / stop).
 */
@Composable
fun HomeScreen(
    agentController: AgentController,
    onNavigateToSettings: () -> Unit,
    onNavigateToPaywall: () -> Unit = {},
) {
    val status by agentController.status.collectAsState()
    val log by agentController.log.collectAsState()
    val think by agentController.currentThink.collectAsState()
    val speechLevel by agentController.speechLevel.collectAsState()
    val context = LocalContext.current
    val activity = context as? MainActivity
    val app = context.applicationContext as OmniApplication
    val subscriptionStatus by app.settingsRepository.subscriptionStatus.collectAsState(initial = "inactive")

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
            AnimatedContent(
                targetState = stateBucket(status),
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                label = "home-state",
                modifier = Modifier.weight(1f)
            ) { bucket ->
                when (bucket) {
                    Bucket.Idle -> IdleLayout(
                        status,
                        subscriptionActive = subscriptionStatus == "active",
                        onUpgrade = onNavigateToPaywall,
                        onSettings = onNavigateToSettings,
                        onMic = { activity?.startListenerService() },
                        onSubmit = { text ->
                            if (text.isNotBlank()) agentController.onCommandReceived(text)
                        },
                        onDebugCycle = {
                            debugIdx = (debugIdx + 1) % debugCycle.size
                            agentController.debugSetStatus(debugCycle[debugIdx])
                        },
                    )
                    Bucket.Listening -> ListeningLayout(
                        status = status,
                        transcript = think,
                        speechLevel = speechLevel,
                        onSettings = onNavigateToSettings,
                        onMic = { activity?.stopCommandListening() },
                    )
                    Bucket.Thinking -> TaskLayout(
                        status = status,
                        log = log,
                        onSettings = onNavigateToSettings,
                        onStop = { agentController.reset() },
                    )
                    Bucket.Speaking -> SpeakingLayout(status, onNavigateToSettings)
                    Bucket.Done -> DoneLayout(
                        status = status,
                        onSettings = onNavigateToSettings,
                        onMic = { activity?.startListenerService() },
                        onSubmit = { text ->
                            if (text.isNotBlank()) agentController.onCommandReceived(text)
                        },
                    )
                    Bucket.Error -> ErrorLayout(
                        status = status,
                        onSettings = onNavigateToSettings,
                        onMic = { activity?.startListenerService() },
                        onSubmit = { text ->
                            if (text.isNotBlank()) agentController.onCommandReceived(text)
                        },
                    )
                }
            }
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

// ─── Header / shared state chrome ────────────────────────────────────────────

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun StateHeader(
    title: String,
    chip: String,
    chipColor: Color,
    onSettings: () -> Unit,
    onDebugCycle: () -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.align(Alignment.TopStart)) {
            Text(
                text = title,
                color = Color(0xFFE8EAEE),
                fontWeight = FontWeight.SemiBold,
                fontSize = 32.sp,
                lineHeight = 38.sp,
                modifier = Modifier.combinedClickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClick = onDebugCycle,
                ),
            )
            Spacer(Modifier.height(12.dp))
            StateChip(label = chip, color = chipColor)
        }
        IconButton(
            onClick = onSettings,
            modifier = Modifier.align(Alignment.TopEnd).size(32.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = "Settings",
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun HomeTopBar(onSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
        )
        IconButton(onClick = onSettings, modifier = Modifier.size(32.dp)) {
            Image(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = "Settings",
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun StateChip(label: String, color: Color) {
    Row(
        modifier = Modifier
            .shadow(
                elevation = 10.dp,
                shape = OmniShapes.Pill,
                ambientColor = color.copy(alpha = 0.28f),
                spotColor = color.copy(alpha = 0.28f),
            )
            .clip(OmniShapes.Pill)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.5f), OmniShapes.Pill)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

// ─── Idle ───────────────────────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun IdleLayout(
    status: AgentStatus,
    subscriptionActive: Boolean,
    onUpgrade: () -> Unit,
    onSettings: () -> Unit,
    onMic: () -> Unit,
    onSubmit: (String) -> Unit,
    onDebugCycle: () -> Unit,
) {
    // DS 2.0 idle: orb centered with two-line prompt below. `compact` is a
    // single boolean driven by IME visibility (target, not the per-frame
    // animated inset) so we don't re-measure the shader orb on every keyboard
    // animation frame — that was causing jank when the keyboard slides up.
    val compact = WindowInsets.isImeVisible
    val orbSize = if (compact) 140.dp else 200.dp
    val gap = if (compact) 20.dp else 40.dp
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        StateHeader(
            title = "Ready",
            chip = "Idle",
            chipColor = Color(0xFFA3A6AE),
            onSettings = onSettings,
            onDebugCycle = onDebugCycle,
        )
        if (!compact) {
            Spacer(Modifier.height(18.dp))
            SubscriptionCard(active = subscriptionActive, onClick = onUpgrade)
        }
        Spacer(Modifier.weight(1f))
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
        Spacer(Modifier.weight(1f))
        TypeCommandBar(
            onMic = onMic,
            onSubmit = onSubmit,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SubscriptionCard(active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(18.dp),
                ambientColor = Color.Black.copy(alpha = 0.55f),
                spotColor = Color.Black.copy(alpha = 0.55f),
            )
            .dockPillStyle(RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(start = 18.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (active) "PLUS" else "PRO",
                color = OmniColors.BrandBlueGlow,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.4.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (active) "Subscription active" else "Upgrade for more agent runs",
                color = OmniColors.Ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        Row(
            modifier = Modifier
                .shadow(
                    elevation = 12.dp,
                    shape = OmniShapes.Pill,
                    ambientColor = OmniColors.BrandBlueGlow.copy(alpha = 0.35f),
                    spotColor = OmniColors.BrandBlueGlow.copy(alpha = 0.35f),
                )
                .clip(OmniShapes.Pill)
                .background(OmniGradients.PrimaryBlue)
                .border(1.dp, OmniColors.BrandBlueGlow.copy(alpha = 0.55f), OmniShapes.Pill)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (active) "Manage" else "Upgrade",
                color = OmniColors.Ink,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "›",
                color = OmniColors.Ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.Light,
            )
        }
    }
}

// ─── Listening ──────────────────────────────────────────────────────────────

@Composable
private fun ListeningLayout(
    status: AgentStatus,
    transcript: String,
    speechLevel: Float,
    onSettings: () -> Unit,
    onMic: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        StateHeader(
            title = "Listening",
            chip = "Listening",
            chipColor = Color(0xFF4FD39A),
            onSettings = onSettings,
        )
        Spacer(Modifier.height(30.dp))
        OmniOrb(
            status = AgentStatus.VoiceListening,
            modifier = Modifier.size(300.dp),
            performance = OmniOrbPerformance.Full,
        )
        Spacer(Modifier.height(0.dp))
        TranscriptCard(
            text = transcript,
            speechLevel = speechLevel,
            waveformActive = status is AgentStatus.VoiceListening,
        )
        Spacer(Modifier.weight(1f))
        GlowingMicButton(onClick = onMic, enabled = true)
        Spacer(Modifier.height(36.dp))
    }
}

@Composable
private fun TranscriptCard(
    text: String,
    label: String = "You said",
    speechLevel: Float = 0f,
    waveformActive: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(174.dp)
            .shadow(
                elevation = 28.dp,
                shape = OmniShapes.Card,
                ambientColor = Color.Black.copy(alpha = 0.65f),
                spotColor = Color.Black.copy(alpha = 0.65f),
            )
            .clip(OmniShapes.Card)
            .background(OmniGradients.DockPill)
            .background(OmniGradients.DockInnerShadow)
            .border(1.dp, OmniColors.Hairline, OmniShapes.Card)
            .padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                label,
                color = Color(0xFF6B6E76),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = text.ifBlank { "..." },
                color = Color(0xFFE8EAEE),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 20.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        WaveformBars(
            level = speechLevel,
            active = waveformActive,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun WaveformBars(
    level: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "speech-waveform")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 560, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "speech-waveform-phase",
    )
    val gatedLevel = if (active) ((level - 0.12f) / 0.88f).coerceIn(0f, 1f) else 0f
    val animatedLevel by animateFloatAsState(
        targetValue = gatedLevel,
        animationSpec = tween(durationMillis = 70),
        label = "speech-waveform-level",
    )
    val baseHeights = listOf(18, 24, 14, 30, 18, 10, 12, 20, 28, 29, 28)
    Row(
        modifier = modifier.height(34.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        baseHeights.forEachIndexed { index, baseHeight ->
            val wave = ((sin((phase * 2f * PI + index * 0.78f)).toFloat() + 1f) / 2f)
            val isSpeaking = animatedLevel > 0.01f
            val speechMotion = if (isSpeaking) animatedLevel * (0.55f + wave * 0.65f) else 0f
            val height = if (isSpeaking) {
                (baseHeight * (0.35f + speechMotion)).coerceIn(7f, 32f)
            } else {
                4f
            }
            Box(
                Modifier
                    .width(3.dp)
                    .height(height.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF6EE7C1).copy(alpha = 0.72f))
            )
        }
    }
}

@Composable
private fun GlowingMicButton(onClick: () -> Unit, enabled: Boolean) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .shadow(
                elevation = if (enabled) 26.dp else 0.dp,
                shape = CircleShape,
                ambientColor = OmniColors.BrandBlueGlow.copy(alpha = if (enabled) 0.65f else 0f),
                spotColor = OmniColors.BrandBlueGlow.copy(alpha = if (enabled) 0.65f else 0f),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Color(0xFF14161E).copy(alpha = if (enabled) 0.72f else 0.34f))
                .border(
                    1.dp,
                    if (enabled) OmniColors.BrandBlueGlow else Color.White.copy(alpha = 0.16f),
                    CircleShape,
                )
                .clickable(enabled = enabled) { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_mic_small),
                contentDescription = if (enabled) "Listen" else null,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

// ─── Thinking / Executing ───────────────────────────────────────────────────

@Composable
private fun TaskLayout(
    status: AgentStatus,
    log: List<String>,
    onSettings: () -> Unit,
    onStop: () -> Unit,
) {
    val executing = status as? AgentStatus.Executing
    val processing = status as? AgentStatus.Processing
    val (stepNumber, maxSteps) = when (status) {
        is AgentStatus.Executing -> status.step to status.maxSteps
        else -> 1 to 1
    }
    val title = if (executing != null) "Executing" else "Thinking"
    val chip = if (executing != null) "Acting on screen" else "Thinking"
    val chipColor = if (executing != null) Color(0xFFFFD166) else OmniColors.BrandBlueGlow
    val currentAction = executing?.lastAction?.ifBlank { "Working..." } ?: "Planning next step"
    val goal = executing?.goal ?: processing?.goal.orEmpty()

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        StateHeader(
            title = title,
            chip = chip,
            chipColor = chipColor,
            onSettings = onSettings,
        )
        Spacer(Modifier.height(if (executing != null) 18.dp else 30.dp))
        if (executing != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(R.drawable.omni_orb_executing),
                    contentDescription = null,
                    modifier = Modifier.size(120.dp),
                )
            }
            Spacer(Modifier.height(22.dp))
            ActionCard(
                action = currentAction,
                goal = goal,
                step = stepNumber,
                maxSteps = maxSteps,
            )
            Spacer(Modifier.height(18.dp))
            ProgressBar(progress = stepNumber.toFloat() / maxSteps.coerceAtLeast(1))
            Spacer(Modifier.height(22.dp))
            StepList(log.takeLast(5))
            Spacer(Modifier.weight(1f))
            StopButton(onClick = onStop)
            Spacer(Modifier.height(34.dp))
        } else {
            OmniOrb(status = status, modifier = Modifier.size(190.dp), performance = OmniOrbPerformance.Full)
            Spacer(Modifier.height(46.dp))
            ReasoningCard(log.takeLast(3), goal)
            Spacer(Modifier.weight(1f))
            GlowingMicButton(onClick = {}, enabled = false)
            Spacer(Modifier.height(36.dp))
        }
    }
}

@Composable
private fun ReasoningCard(entries: List<String>, goal: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(174.dp)
            .shadow(28.dp, OmniShapes.Card, ambientColor = Color.Black.copy(alpha = 0.65f), spotColor = Color.Black.copy(alpha = 0.65f))
            .dockPillStyle(OmniShapes.Card)
            .padding(20.dp),
    ) {
        Text(
            "REASONING",
            color = Color(0xFF6B6E76),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(12.dp))
        val lines = entries.ifEmpty { listOf("Starting task: $goal") }
        lines.forEach { entry ->
            Text(
                "• ${entry.substringAfter("] ")}",
                color = Color(0xFFC9CDD6),
                fontSize = 13.sp,
                lineHeight = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.weight(1f))
        Text("•••", color = OmniColors.BrandBlueGlow, fontSize = 18.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun ActionCard(action: String, goal: String, step: Int, maxSteps: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(28.dp, OmniShapes.Card, ambientColor = Color.Black.copy(alpha = 0.65f), spotColor = Color.Black.copy(alpha = 0.65f))
            .dockPillStyle(OmniShapes.Card)
            .padding(20.dp),
    ) {
        Text("TAP", color = Color(0xFFFFD166), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            action,
            color = Color(0xFFE8EAEE),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (goal.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(goal, color = Color(0xFFA3A6AE), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Text("Step $step of $maxSteps", color = Color(0xFF6B6E76), fontSize = 11.sp)
    }
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 18.dp,
                shape = OmniShapes.Pill,
                ambientColor = OmniColors.Error.copy(alpha = 0.28f),
                spotColor = OmniColors.Error.copy(alpha = 0.28f),
            )
            .clip(OmniShapes.Pill)
            .background(OmniColors.Error.copy(alpha = 0.10f))
            .border(1.dp, OmniColors.Error.copy(alpha = 0.45f), OmniShapes.Pill)
            .clickable { onClick() }
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Stop", color = OmniColors.Error, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ProgressBar(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0xFF1A1B20)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(2.dp),
                    ambientColor = OmniColors.BrandBlueGlow.copy(alpha = 0.5f),
                    spotColor = OmniColors.BrandBlueGlow.copy(alpha = 0.5f),
                )
                .clip(RoundedCornerShape(2.dp))
                .background(OmniGradients.PrimaryBlue),
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
private fun SpeakingLayout(status: AgentStatus, onSettings: () -> Unit) {
    val text = (status as? AgentStatus.Speaking)?.text ?: ""
    CenteredCaption(status = status, title = "Speaking", caption = "\u201C$text\u201D", tone = OmniColors.AuroraPeach, onSettings = onSettings)
}

@Composable
private fun DoneLayout(
    status: AgentStatus,
    onSettings: () -> Unit,
    onMic: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val done = status as? AgentStatus.Done
    val success = done?.success == true
    val tone = if (success) OmniColors.Success else OmniColors.Error
    ResultLayout(
        status = status,
        caption = if (success) "Done! ${done?.reason.orEmpty()}" else "Couldn\u2019t complete: ${done?.reason.orEmpty()}",
        tone = tone,
        onSettings = onSettings,
        onMic = onMic,
        onSubmit = onSubmit,
    )
}

@Composable
private fun ErrorLayout(
    status: AgentStatus,
    onSettings: () -> Unit,
    onMic: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    ResultLayout(
        status = status,
        caption = (status as? AgentStatus.Error)?.message ?: "Something went wrong.",
        tone = OmniColors.Error,
        onSettings = onSettings,
        onMic = onMic,
        onSubmit = onSubmit,
    )
}

@Composable
private fun CenteredCaption(
    status: AgentStatus,
    title: String,
    caption: String,
    tone: Color,
    onSettings: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        StateHeader(title = title, chip = title, chipColor = tone, onSettings = onSettings)
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

@Composable
private fun ResultLayout(
    status: AgentStatus,
    caption: String,
    tone: Color,
    onSettings: () -> Unit,
    onMic: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(16.dp))
        HomeTopBar(onSettings = onSettings)
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
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
        TypeCommandBar(onMic = onMic, onSubmit = onSubmit)
        Spacer(Modifier.height(24.dp))
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

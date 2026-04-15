package com.buddy.assistant.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.buddy.assistant.agent.AgentController
import com.buddy.assistant.data.AgentStatus
import com.buddy.assistant.ui.MainActivity
import com.buddy.assistant.ui.components.BuddyOrb

private val Purple = Color(0xFF6C63FF)
private val Red = Color(0xFFFF6B6B)

private val BgGradient = Brush.verticalGradient(
    colors = listOf(Color(0xFF0A0A1A), Color(0xFF0D0D2B), Color(0xFF0A0A1A))
)

@Composable
fun HomeScreen(
    agentController: AgentController,
    onNavigateToSettings: () -> Unit,
    onNavigateToSetup: () -> Unit
) {
    val status by agentController.status.collectAsState()
    val log by agentController.log.collectAsState()
    val activity = LocalContext.current as? MainActivity
    val listState = rememberLazyListState()

    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
    }

    Box(modifier = Modifier.fillMaxSize().background(BgGradient)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(56.dp))
            TopBar(onNavigateToSetup, onNavigateToSettings)
            Spacer(modifier = Modifier.height(32.dp))
            BuddyOrb(status = status, modifier = Modifier.size(200.dp))
            Spacer(modifier = Modifier.height(32.dp))
            StatusText(status)
            Spacer(modifier = Modifier.height(24.dp))
            ActionButtons(status, activity, agentController)
            Spacer(modifier = Modifier.height(24.dp))
            LogPanel(log, listState, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun TopBar(onSetup: () -> Unit, onSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "buddy",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Light,
                letterSpacing = 8.sp,
                color = Color.White
            )
        )
        Row {
            IconButton(onClick = onSetup) {
                Icon(Icons.Default.CheckCircle, "Setup", tint = Color.White.copy(alpha = 0.7f))
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, "Settings", tint = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun ActionButtons(
    status: AgentStatus,
    activity: MainActivity?,
    agentController: AgentController
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (status) {
            is AgentStatus.Idle, is AgentStatus.Done ->
                PrimaryButton("Start Listening", Icons.Default.Mic) {
                    activity?.startListenerService()
                }
            is AgentStatus.Error ->
                ErrorActionButton(status, activity)
            is AgentStatus.Processing, is AgentStatus.Executing ->
                StopButton("Stop") { agentController.reset() }
            is AgentStatus.WakeWordListening, is AgentStatus.VoiceListening ->
                StopButton("Stop Listening", Icons.Default.MicOff) {
                    activity?.stopCommandListening()
                }
            is AgentStatus.Speaking -> {}
        }
    }
}

@Composable
private fun ErrorActionButton(status: AgentStatus.Error, activity: MainActivity?) {
    val isAccessibilityError = status.message.contains("Accessibility", ignoreCase = true)
    if (isAccessibilityError) {
        PrimaryButton("Open Accessibility Settings", Icons.Default.Settings) {
            activity?.openAccessibilitySettings()
        }
    } else {
        PrimaryButton("Start Listening", Icons.Default.Mic) {
            activity?.startListenerService()
        }
    }
}

@Composable
private fun PrimaryButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Purple),
        shape = RoundedCornerShape(50)
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun StopButton(
    label: String,
    icon: ImageVector = Icons.Default.Stop,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Red),
        border = BorderStroke(1.dp, Red),
        shape = RoundedCornerShape(50)
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun LogPanel(
    log: List<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier
) {
    if (log.isEmpty()) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(log) { entry ->
                Text(
                    text = entry.substringAfter("] "),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = logEntryColor(entry),
                        fontSize = 11.sp
                    )
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
}

private fun logEntryColor(entry: String): Color = when {
    entry.contains("Error") || entry.contains("failed") -> Color(0xFFFF6B6B)
    entry.contains("Done") || entry.contains("success") -> Color(0xFF6BCB77)
    entry.contains("Think") -> Color(0xFF6C63FF).copy(alpha = 0.9f)
    entry.contains("User:") -> Color(0xFFFFD93D)
    else -> Color.White.copy(alpha = 0.7f)
}

@Composable
private fun StatusText(status: AgentStatus) {
    val text = when (status) {
        is AgentStatus.Idle -> "Tap \"Start Listening\" or say \"Hey Buddy\""
        is AgentStatus.WakeWordListening -> "Listening for wake word..."
        is AgentStatus.VoiceListening -> "Listening... speak your command"
        is AgentStatus.Processing -> "Processing: \"${status.goal}\""
        is AgentStatus.Executing -> "Step ${status.step}/${status.maxSteps} — ${status.lastAction}"
        is AgentStatus.Speaking -> "\"${status.text}\""
        is AgentStatus.Done -> if (status.success) "Done! ${status.reason}" else "Couldn't complete: ${status.reason}"
        is AgentStatus.Error -> "Error: ${status.message}"
    }

    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center
        ),
        modifier = Modifier.padding(horizontal = 32.dp)
    )
}

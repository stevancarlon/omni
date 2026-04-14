package com.buddy.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.buddy.assistant.BuddyApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BuddyApplication
    val repo = app.settingsRepository
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("claude") }
    var model by remember { mutableStateOf("claude-opus-4-6") }
    var wakeWordEnabled by remember { mutableStateOf(true) }
    var wakeWord by remember { mutableStateOf("Hey Buddy") }
    var ttsEnabled by remember { mutableStateOf(true) }
    var maxSteps by remember { mutableStateOf("30") }
    var showApiKey by remember { mutableStateOf(false) }

    // Load saved values
    LaunchedEffect(Unit) {
        apiKey = repo.apiKey.first()
        provider = repo.llmProvider.first()
        model = repo.llmModel.first()
        wakeWordEnabled = repo.wakeWordEnabled.first()
        wakeWord = repo.wakeWord.first()
        ttsEnabled = repo.ttsEnabled.first()
        maxSteps = repo.maxSteps.first().toString()
    }

    val bgGradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF0A0A1A), Color(0xFF0D0D2B), Color(0xFF0A0A1A))
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgGradient)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Settings",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 4.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            SettingsSectionTitle("LLM Provider")

            // Provider selection
            val providers = listOf("claude", "openai", "openrouter")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                providers.forEach { p ->
                    FilterChip(
                        selected = provider == p,
                        onClick = {
                            provider = p
                            scope.launch { repo.setLlmProvider(p) }
                            model = when (p) {
                                "claude" -> "claude-opus-4-6"
                                "openai" -> "gpt-4o"
                                else -> "anthropic/claude-opus-4-6"
                            }
                            scope.launch { repo.setLlmModel(model) }
                        },
                        label = { Text(p, color = Color.White) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF6C63FF),
                            containerColor = Color.White.copy(alpha = 0.1f)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // API Key
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; scope.launch { repo.setApiKey(it) } },
                label = { Text("API Key", color = Color.White.copy(alpha = 0.7f)) },
                placeholder = { Text("sk-ant-...", color = Color.White.copy(alpha = 0.3f)) },
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.7f)
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF6C63FF),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                ),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Model
            OutlinedTextField(
                value = model,
                onValueChange = { model = it; scope.launch { repo.setLlmModel(it) } },
                label = { Text("Model", color = Color.White.copy(alpha = 0.7f)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF6C63FF),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(32.dp))
            SettingsSectionTitle("Wake Word")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Enable Wake Word", color = Color.White)
                Switch(
                    checked = wakeWordEnabled,
                    onCheckedChange = {
                        wakeWordEnabled = it
                        scope.launch { repo.setWakeWordEnabled(it) }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF6C63FF))
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = wakeWord,
                onValueChange = { wakeWord = it; scope.launch { repo.setWakeWord(it) } },
                label = { Text("Wake Word Phrase", color = Color.White.copy(alpha = 0.7f)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF6C63FF),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(32.dp))
            SettingsSectionTitle("Behavior")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Voice Responses (TTS)", color = Color.White)
                Switch(
                    checked = ttsEnabled,
                    onCheckedChange = {
                        ttsEnabled = it
                        scope.launch { repo.setTtsEnabled(it) }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF6C63FF))
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = maxSteps,
                onValueChange = {
                    maxSteps = it
                    it.toIntOrNull()?.let { v -> scope.launch { repo.setMaxSteps(v) } }
                },
                label = { Text("Max Steps", color = Color.White.copy(alpha = 0.7f)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF6C63FF),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            color = Color(0xFF6C63FF),
            letterSpacing = 3.sp,
            fontWeight = FontWeight.SemiBold
        )
    )
    Spacer(modifier = Modifier.height(12.dp))
}

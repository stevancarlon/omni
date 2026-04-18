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

    var claudeKey by remember { mutableStateOf("") }
    var openaiKey by remember { mutableStateOf("") }
    var openrouterKey by remember { mutableStateOf("") }
    var groqKey by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("claude") }
    var model by remember { mutableStateOf("claude-opus-4-6") }
    var wakeWordEnabled by remember { mutableStateOf(true) }
    var wakeWord by remember { mutableStateOf("Hey Buddy") }
    var ttsEnabled by remember { mutableStateOf(true) }
    var maxSteps by remember { mutableStateOf("30") }
    var showApiKey by remember { mutableStateOf(false) }
    var speechProvider by remember { mutableStateOf("builtin") }
    var deepgramApiKey by remember { mutableStateOf("") }
    var showDeepgramKey by remember { mutableStateOf(false) }
    var speechLanguage by remember { mutableStateOf("") }

    // Load saved values
    LaunchedEffect(Unit) {
        claudeKey = repo.apiKeyClaude.first().ifBlank { repo.apiKey.first() }
        openaiKey = repo.apiKeyOpenai.first()
        openrouterKey = repo.apiKeyOpenrouter.first()
        groqKey = repo.apiKeyGroq.first()
        provider = repo.llmProvider.first()
        model = repo.llmModel.first()
        wakeWordEnabled = repo.wakeWordEnabled.first()
        wakeWord = repo.wakeWord.first()
        ttsEnabled = repo.ttsEnabled.first()
        maxSteps = repo.maxSteps.first().toString()
        speechProvider = repo.speechProvider.first()
        deepgramApiKey = repo.deepgramApiKey.first()
        speechLanguage = repo.speechLanguage.first()
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
            val providers = listOf("claude", "openai", "groq", "openrouter")
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
                                "groq" -> "llama-3.3-70b-versatile"
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

            ApiKeyField("Claude API Key", "sk-ant-...", claudeKey, showApiKey) {
                claudeKey = it; scope.launch { repo.setApiKeyClaude(it) }
            }
            Spacer(modifier = Modifier.height(8.dp))
            ApiKeyField("OpenAI API Key", "sk-...", openaiKey, showApiKey) {
                openaiKey = it; scope.launch { repo.setApiKeyOpenai(it) }
            }
            Spacer(modifier = Modifier.height(8.dp))
            ApiKeyField("OpenRouter API Key", "sk-or-...", openrouterKey, showApiKey) {
                openrouterKey = it; scope.launch { repo.setApiKeyOpenrouter(it) }
            }
            Spacer(modifier = Modifier.height(8.dp))
            ApiKeyField("Groq API Key", "gsk_...", groqKey, showApiKey) {
                groqKey = it; scope.launch { repo.setApiKeyGroq(it) }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { showApiKey = !showApiKey }) {
                    Text(
                        if (showApiKey) "Hide keys" else "Show keys",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Model selection
            val modelOptions = when (provider) {
                "claude" -> listOf(
                    "claude-sonnet-4-6" to "Claude Sonnet 4.6 (fast)",
                    "claude-opus-4-6" to "Claude Opus 4.6 (smart)",
                    "claude-haiku-4-5-20251001" to "Claude Haiku 4.5 (fastest)",
                )
                "openai" -> listOf(
                    "gpt-4o" to "GPT-4o",
                    "gpt-4o-mini" to "GPT-4o Mini (fast)",
                    "gpt-4.1" to "GPT-4.1",
                    "gpt-4.1-mini" to "GPT-4.1 Mini (fast)",
                    "gpt-4.1-nano" to "GPT-4.1 Nano (fastest)",
                )
                "groq" -> listOf(
                    "llama-3.3-70b-versatile" to "Llama 3.3 70B (best)",
                    "llama-3.1-8b-instant" to "Llama 3.1 8B (fastest)",
                    "llama-4-scout-17b-16e-instruct" to "Llama 4 Scout",
                    "mixtral-8x7b-32768" to "Mixtral 8x7B",
                )
                "openrouter" -> listOf(
                    "anthropic/claude-sonnet-4-6" to "Claude Sonnet 4.6",
                    "anthropic/claude-haiku-4-5-20251001" to "Claude Haiku 4.5",
                    "openai/gpt-4o-mini" to "GPT-4o Mini",
                    "meta-llama/llama-3.3-70b-instruct" to "Llama 3.3 70B",
                    "google/gemini-2.5-flash" to "Gemini 2.5 Flash",
                )
                else -> emptyList()
            }

            var modelMenuExpanded by remember { mutableStateOf(false) }
            val selectedLabel = modelOptions.firstOrNull { it.first == model }?.second ?: model

            ExposedDropdownMenuBox(
                expanded = modelMenuExpanded,
                onExpandedChange = { modelMenuExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Model", color = Color.White.copy(alpha = 0.7f)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF6C63FF),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = modelMenuExpanded,
                    onDismissRequest = { modelMenuExpanded = false }
                ) {
                    modelOptions.forEach { (id, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                model = id
                                scope.launch { repo.setLlmModel(id) }
                                modelMenuExpanded = false
                            }
                        )
                    }
                }
            }

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
            SettingsSectionTitle("Speech Recognition")

            // Provider selection
            val speechProviders = listOf("builtin" to "Built-in (Android)", "deepgram" to "Deepgram (Streaming)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                speechProviders.forEach { (id, label) ->
                    FilterChip(
                        selected = speechProvider == id,
                        onClick = {
                            speechProvider = id
                            scope.launch { repo.setSpeechProvider(id) }
                        },
                        label = { Text(label, color = Color.White, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF6C63FF),
                            containerColor = Color.White.copy(alpha = 0.1f)
                        )
                    )
                }
            }

            if (speechProvider == "deepgram") {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = deepgramApiKey,
                    onValueChange = { deepgramApiKey = it; scope.launch { repo.setDeepgramApiKey(it) } },
                    label = { Text("Deepgram API Key", color = Color.White.copy(alpha = 0.7f)) },
                    placeholder = { Text("dg-...", color = Color.White.copy(alpha = 0.3f)) },
                    visualTransformation = if (showDeepgramKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showDeepgramKey = !showDeepgramKey }) {
                            Icon(
                                if (showDeepgramKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
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
            }

            Spacer(modifier = Modifier.height(12.dp))

            val languages = listOf(
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
                "hi-IN" to "Hindi"
            )

            var languageExpanded by remember { mutableStateOf(false) }
            val selectedLanguageLabel = languages.firstOrNull { it.first == speechLanguage }?.second ?: "System Default"

            ExposedDropdownMenuBox(
                expanded = languageExpanded,
                onExpandedChange = { languageExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedLanguageLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Language", color = Color.White.copy(alpha = 0.7f)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF6C63FF),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = languageExpanded,
                    onDismissRequest = { languageExpanded = false }
                ) {
                    languages.forEach { (code, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                speechLanguage = code
                                scope.launch { repo.setSpeechLanguage(code) }
                                languageExpanded = false
                            }
                        )
                    }
                }
            }

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
private fun ApiKeyField(
    label: String,
    placeholder: String,
    value: String,
    visible: Boolean,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = Color.White.copy(alpha = 0.7f)) },
        placeholder = { Text(placeholder, color = Color.White.copy(alpha = 0.3f)) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = Color(0xFF6C63FF),
            unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
        ),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
    )
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

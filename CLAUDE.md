# CLAUDE.md — Omni Android

## Project Overview

Omni is a voice-controlled Android assistant that uses LLM-driven agents with accessibility services to perceive and interact with the screen. Users activate it with a wake word, speak a command, and the agent loop reads the screen, calls an LLM, and executes UI actions until the goal is complete.

## Build & Run

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Run lint checks
./gradlew lint
```

- Gradle KTS with version catalog at `gradle/libs.versions.toml`
- AGP 8.7.3, Kotlin 2.0.0, JVM target 11
- Single `app` module, package: `com.omni.assistant`
- minSdk 29, targetSdk 35, compileSdk 35
- No test suite currently exists

## Tech Stack

- **UI:** Jetpack Compose + Material 3 (dark theme), Navigation Compose
- **Networking:** OkHttp3 + Gson (direct API calls to Claude, OpenAI, OpenRouter)
- **Storage:** DataStore Preferences
- **Async:** Kotlinx Coroutines, StateFlow
- **Permissions:** Accompanist Permissions

## Architecture

Singleton-based components with StateFlow-driven reactive state:

- **AgentController** (`agent/`) — core agent loop: get screen → call LLM → execute action → repeat
- **OmniAccessibilityService** (`service/`) — reads accessibility node tree, executes taps/swipes/typing via gesture API
- **OmniListenerService** (`service/`) — foreground service for wake word detection + speech recognition
- **LLMClient** (`llm/`) — multi-provider LLM client (Claude, OpenAI, OpenRouter), manages conversation history (last 10 turns)
- **SettingsRepository** (`data/`) — DataStore-backed settings with Flow-based API

State models in `data/AgentState.kt`: `AgentStatus` (sealed class for Idle/Processing/Executing/etc.) and `AgentAction` (sealed class for Tap/TypeText/Swipe/etc.).

## Package Layout

```
com.omni.assistant/
├── agent/          # Agent loop and decision logic
├── data/           # State models, settings repository
├── llm/            # LLM API client
├── receiver/       # Boot receiver
├── service/        # Accessibility + listener services
└── ui/
    ├── theme/      # Material 3 dark color scheme
    ├── screens/    # Home, Settings, Setup screens
    └── components/ # OmniOrb animated indicator
```

## Key Conventions

- Pure Kotlin, no Java — official Kotlin code style
- Pure Compose UI, no XML layouts
- Sealed classes for state and action types
- Suspend functions for async work, coroutine scopes with SupervisorJob
- Screen element descriptions capped at 4000 chars for LLM context
- Accessibility node tree traversal limited to depth 10
- Activity log keeps last 100 entries

# Buddy

A voice-controlled AI assistant for Android that uses LLM-driven agents to interact with your phone on your behalf.

Say **"Hey Buddy"** or tap **Start Listening**, speak a command, and Buddy will read your screen, decide what to do, and execute actions — tapping buttons, typing text, opening apps, scrolling, and more.

## How it works

1. You give Buddy a goal (e.g. "open YouTube Music and play my liked songs")
2. Buddy reads the screen using Android's Accessibility Service
3. An LLM (Claude, OpenAI, or OpenRouter) decides what action to take
4. Buddy executes the action (tap, type, swipe, etc.)
5. Steps 2-4 repeat until the goal is complete

## Setup

1. Install the app on your Android device (minSdk 29 / Android 10+)
2. Enable Buddy in **Settings → Accessibility → Installed apps**
3. Add your LLM API key in the app's **Settings** screen
4. Grant microphone permission when prompted

## Build

```bash
./gradlew assembleDebug
./gradlew installDebug
```

## Tech stack

- **Kotlin** + **Jetpack Compose** (Material 3)
- **Accessibility Service** for screen reading and UI automation
- **SpeechRecognizer** for voice input (on-device when available)
- **OkHttp** + **Gson** for LLM API calls
- **DataStore** for settings persistence
- **Coroutines** + **StateFlow** for reactive state

## Supported LLM providers

- **Claude** (Anthropic)
- **OpenAI**
- **OpenRouter**

## Permissions

- `RECORD_AUDIO` — voice commands
- `ACCESSIBILITY_SERVICE` — screen reading and action execution
- `INTERNET` — LLM API calls
- `FOREGROUND_SERVICE` — background wake word listening
- `QUERY_ALL_PACKAGES` — listing and launching installed apps

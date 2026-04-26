# Omni

A voice-controlled AI assistant for Android that uses LLM-driven agents to interact with your phone on your behalf.

Say **"Hey Omni"** or tap **Start Listening**, speak a command, and Omni will read your screen, decide what to do, and execute actions — tapping buttons, typing text, opening apps, scrolling, and more.

## How it works

1. You give Omni a goal (e.g. "open YouTube Music and play my liked songs")
2. Omni reads the screen using Android's Accessibility Service
3. The Omni backend calls the LLM provider and decides what action to take
4. Omni executes the action (tap, type, swipe, etc.)
5. Steps 2-4 repeat until the goal is complete

## Setup

1. Install the app on your Android device (minSdk 29 / Android 10+)
2. Continue with Google
3. Grant microphone, accessibility, and always-on-top permissions
4. Subscribe through Google Play to enable agent runs and wake word

## Build

```bash
./gradlew assembleDebug
./gradlew installDebug
```

## Tech stack

- **Kotlin** + **Jetpack Compose** (Material 3)
- **Accessibility Service** for screen reading and UI automation
- **Credential Manager** for Google sign-in
- **Google Play Billing** for subscriptions
- **SpeechRecognizer** fallback and backend-issued Deepgram sessions for voice input
- **OkHttp** + **Gson** for Omni backend API calls
- **DataStore** for settings persistence
- **Coroutines** + **StateFlow** for reactive state

## API keys

No provider API keys are stored in the Android app. LLM, speech, and billing secrets are configured on the Omni backend.

## Permissions

- `RECORD_AUDIO` — voice commands
- `ACCESSIBILITY_SERVICE` — screen reading and action execution
- `INTERNET` — Omni backend API calls
- `FOREGROUND_SERVICE` — background wake word listening
- `QUERY_ALL_PACKAGES` — listing and launching installed apps

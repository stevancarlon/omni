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

## Google sign-in configuration

The Play Store package name is `com.omni.orb`.

Google OAuth must know every signing certificate used to install the app. The Web client ID stays the same, but Google Cloud needs one Android OAuth client per package/signature pair.

For internal testing builds distributed by Google Play, configure an Android OAuth client with the Play app signing certificate:

1. In Play Console, open **Test and release > Setup > App signing**.
2. Copy the SHA-1 fingerprint from **App signing key certificate**.
3. In Google Cloud Console, create or update an **Android** OAuth client for package `com.omni.orb` and that SHA-1 fingerprint.
4. Keep the Android app's `google_web_client_id` set to the **Web** OAuth client ID, and set the backend `GOOGLE_WEB_CLIENT_ID` to the same value.

For locally installed debug/release APKs, also create an Android OAuth client for package `com.omni.orb` with this repository's upload key SHA-1:

```text
42:12:51:B9:E5:66:E0:96:53:5B:5D:9F:0F:B3:A1:3D:B3:C9:17:E6
```

## Build

```bash
./gradlew assembleDebug
./gradlew installDebug
```

## Backend endpoint

Release builds always use:

```text
https://omni-backend-bq8e.onrender.com
```

Do not use `OMNI_BACKEND_URL` for release builds. Debug builds can point at a tunnel or local backend with:

```properties
OMNI_DEBUG_BACKEND_URL=https://your-debug-endpoint.example
```

Only use `OMNI_RELEASE_BACKEND_URL` for an intentional production backend migration.

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

# Omni

Omni is an experimental open-source Android agent that can see the current
screen and operate the phone through Android's Accessibility API. Give it a
goal and it can tap, type, swipe, navigate, open apps, and adapt as the screen
changes.

## Demo

https://github.com/user-attachments/assets/a493cc3c-ee82-4cf8-93a8-307a6a66d0bf

## Repository

- [`omni-mobile/`](omni-mobile/) — native Kotlin Android application
- [`omni_backend/`](omni_backend/) — self-hostable Elixir/Phoenix API

The community build uses your own backend and API keys. It does not require
Google sign-in, Google Play, Aptoide, Stripe, or a store subscription.

## What you need

- An Android 10+ phone or emulator
- USB debugging enabled on the phone
- Android Studio with Android SDK 35 and JDK 17
- Elixir 1.19 with Erlang/OTP 28
- Docker with Docker Compose, used for PostgreSQL
- One supported LLM API key

Linux and macOS commands are shown below. On Windows, use WSL or the equivalent
PowerShell commands.

## 1. Clone the project

```bash
git clone https://github.com/stevancarlon/omni.git
cd omni
```

## 2. Choose API keys

You need **one** LLM provider. Put its key, provider name, and model in
`omni_backend/.env`.

| Provider | Environment variable | Provider value | Example model | Notes |
| --- | --- | --- | --- | --- |
| [Gemini](https://aistudio.google.com/app/apikey) | `GEMINI_API_KEY` | `gemini` | `gemini-2.5-pro` | Supports annotated screenshots |
| [Groq](https://console.groq.com/keys) | `GROQ_API_KEY` | `groq` | `llama-3.3-70b-versatile` | Fast, but this adapter uses screen text rather than images |
| [OpenAI](https://platform.openai.com/api-keys) | `OPENAI_API_KEY` | `openai` | `gpt-5.5` | Also enables Whisper and app-inventory enhancement |
| [Anthropic](https://console.anthropic.com/settings/keys) | `ANTHROPIC_API_KEY` | `claude` | `claude-opus-4-6` | Supports annotated screenshots |

Optional keys:

- `DEEPGRAM_API_KEY` enables wake-word listening and streaming speech
  recognition. The key must be able to create temporary auth grants.
- `OPENAI_API_KEY` additionally enables Whisper transcription refinement and a
  generated installed-app inventory when OpenAI is not the main LLM provider.

Store and hosted-service credentials such as Google OAuth, Google Play,
Aptoide, Stripe, and Android signing keys are **not needed** for the community
build.

API providers charge according to their own pricing. Set spending limits before
testing autonomous tasks.

## 3. Start the backend

Create your local environment file:

```bash
cd omni_backend
cp .env.example .env
```

Open `.env` and replace the example key. A minimal Gemini configuration is:

```dotenv
COMMUNITY_MODE=true
DEFAULT_LLM_PROVIDER=gemini
DEFAULT_LLM_MODEL=gemini-2.5-pro
GEMINI_API_KEY=replace-with-your-real-key
```

Leave the PostgreSQL values from `.env.example` unchanged for the Docker setup.
Then start PostgreSQL, install dependencies, create the database, and run the
API:

```bash
docker compose up -d postgres
mix setup
mix phx.server
```

Keep this terminal running. Confirm the API is available on port `4000`:

```bash
curl http://localhost:4000/api/health
```

If `mix` is unavailable, install the Elixir and Erlang versions listed above or
use a version manager such as `mise` or `asdf`.

## 4. Build and install Android

Connect the phone over USB, approve its debugging prompt, and verify it appears:

```bash
adb devices
```

Forward the phone's port `4000` to the backend on your computer:

```bash
adb reverse tcp:4000 tcp:4000
```

In a second terminal:

```bash
cd omni/omni-mobile
cp local.properties.example local.properties
./gradlew installCommunityDebug
```

If you are already at the repository root, use `cd omni-mobile` instead. The
community app uses package ID `com.omni.orb.community` and connects to
`http://127.0.0.1:4000` by default.

To use a remote backend instead of USB forwarding, edit
`omni-mobile/local.properties` before building:

```properties
OMNI_COMMUNITY_BACKEND_URL=https://your-backend.example
```

Use HTTPS for any backend exposed beyond your own machine.

## 5. Enable Android permissions

1. Open **Omni Community** and choose **Connect to community backend**.
2. Allow microphone and notification permissions when requested.
3. On Android 13+, open **Settings → Apps → Omni Community**, open the overflow
   menu, and choose **Allow restricted settings**.
4. Return to Omni and enable its Accessibility service during setup.
5. Press the listening button and try a low-risk command such as “open
   Settings.”

Menu names differ between manufacturers. If the Accessibility toggle is greyed
out, the restricted-settings step has not been completed.

## Troubleshooting

- **Community sign-in returns 404:** verify `COMMUNITY_MODE=true` in
  `omni_backend/.env`, then restart Phoenix.
- **The app cannot reach the backend:** confirm the health check works, rerun
  `adb reverse tcp:4000 tcp:4000`, and keep the USB connection active.
- **LLM request fails:** make sure `DEFAULT_LLM_PROVIDER` matches the configured
  key and that the model is available to your provider account.
- **Wake word does not work:** configure `DEEPGRAM_API_KEY`; without Deepgram,
  use the app's manual listening flow.
- **Gradle cannot find Android SDK:** open `omni-mobile` once in Android Studio or
  set `sdk.dir` in `omni-mobile/local.properties`.
- **Database connection fails:** check `docker compose ps` inside
  `omni_backend` and confirm port `55432` is free.

## Development checks

```bash
cd omni-mobile
./gradlew lintCommunityDebug testCommunityDebugUnitTest assembleCommunityDebug

cd ../omni_backend
mix precommit
```

## Safety

> [!WARNING]
> Omni can read screen content and interact with other apps. Only install builds
> you trust. Do not use it to automate passwords, banking, payments,
> authentication challenges, or other sensitive flows. The sensitive-app guard
> reduces risk but is not a security boundary.

This is alpha software and is not currently distributed through Google Play.
Read the [privacy model](omni-mobile/PRIVACY.md) before using it with real data.

## Contributing

Start with [CONTRIBUTING.md](CONTRIBUTING.md), the
[roadmap](omni-mobile/ROADMAP.md), and issues labeled
[`good first issue`](https://github.com/stevancarlon/omni/labels/good%20first%20issue).
Report vulnerabilities privately according to [SECURITY.md](SECURITY.md).

## License

Apache License 2.0. The Omni name, wordmark, and logo may not be used to imply
that an unofficial build or deployment is operated or endorsed by the project.

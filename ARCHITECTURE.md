# Architecture

## Agent loop

```text
voice/debug command
        |
        v
AgentController
        |
        +--> OmniAccessibilityService --> node tree + screenshot
        |
        +--> LLMClient -------------> Omni backend -------------> model provider
        |                                  |
        |                                  +--> agent memories
        |
        +<-- structured AgentAction <------+
        |
        +--> validate --> tap/type/swipe/global action --> repeat
```

`AgentController` owns task state, limits steps, detects repeated actions, and decides when to stop. `OmniAccessibilityService` is the only component that reads cross-app UI nodes or injects UI interactions. `LLMClient` sends bounded screen descriptions and optional JPEG screenshots to the configured backend.

## Android source sets

- `main`: shared application, agent, Accessibility, voice, and UI code.
- `community`: no store SDK; connects to a self-hosted backend.
- `googlePlay`: Google Play Billing integration.
- `aptoide`: Aptoide Billing integration.
- `debug`: cleartext localhost access and the ADB test-command receiver.

Community builds use `com.omni.orb.community` so they can coexist with an official build.

## Trust boundaries

1. Android grants the Accessibility service access to visible UI state and interaction.
2. The app sends selected screen state to the configured backend over HTTP(S).
3. The backend sends prompts and, when present, screenshots to the selected model provider.
4. The model's response is untrusted input and is parsed into a limited `AgentAction` schema.
5. Local validation, sensitive-app suspension, duplicate detection, and step limits reduce risk but cannot make arbitrary UI automation safe.

See [PRIVACY.md](PRIVACY.md) and [SECURITY.md](SECURITY.md) for operational requirements.

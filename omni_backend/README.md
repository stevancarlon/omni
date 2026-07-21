# Omni Backend

The Omni backend is the self-hostable Phoenix API used by the [Omni Android computer-use agent](https://github.com/stevancarlon/omni-mobile). It handles authentication, model-provider calls, short-lived speech credentials, agent memories, and optional hosted-service billing.

> [!WARNING]
> Agent requests can contain screenshots, visible text, app identities, and user goals. Treat logs, databases, provider requests, and backups as sensitive. Read [SECURITY.md](SECURITY.md) before exposing the service publicly.

## Community quick start

Prerequisites:

- Elixir 1.15+ and Erlang/OTP
- PostgreSQL 16, locally or through Docker
- At least one supported LLM provider key

```bash
cp .env.example .env
```

Set `COMMUNITY_MODE=true`, choose a provider, and add its key. Community mode creates anonymous local sessions and bypasses paid entitlements so contributors can run the complete stack. **Never enable it on the official hosted service or an untrusted public server.**

Start PostgreSQL and the application:

```bash
docker compose up -d postgres
mix setup
mix phx.server
```

Health check:

```bash
curl http://localhost:4000/api/health
```

Connect the Android community build over USB:

```bash
adb reverse tcp:4000 tcp:4000
```

The community app then reaches `http://127.0.0.1:4000` from the device.

## Supported providers

Configure one of `anthropic`, `openai`, `openrouter`, `groq`, or `gemini` using `DEFAULT_LLM_PROVIDER`, `DEFAULT_LLM_MODEL`, and the matching API key in `.env`.

Deepgram is optional. Android can fall back to platform speech recognition when speech-provider configuration is unavailable.

## Hosted-service configuration

Google sign-in, Google Play, Aptoide, Stripe, and Deepgram variables in `.env.example` are optional for community development. They are used by official hosted distributions and must remain server-side.

Production additionally requires:

```text
DATABASE_URL
SECRET_KEY_BASE
PHX_HOST
PHX_SERVER=true
```

Do not run production with `COMMUNITY_MODE=true`.

See [DEPLOYMENT.md](DEPLOYMENT.md) before exposing an instance publicly.

## Verify changes

```bash
mix precommit
```

This compiles with warnings as errors, checks dependency locks and formatting, and runs the test suite. Tests require the configured PostgreSQL server.

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request. Report vulnerabilities privately according to [SECURITY.md](SECURITY.md).

## License

Source code is available under the [Apache License 2.0](LICENSE). The Omni name and logo may not be used to imply that an unofficial deployment is operated or endorsed by the project.

# Omni Backend

The self-hostable Phoenix API for the [Omni Android agent](../omni-mobile). It
handles community authentication, LLM-provider calls, speech credentials, agent
memories, and optional hosted-service billing.

Follow the complete [installation guide](../README.md) for prerequisites, API
keys, database setup, Android connection, and troubleshooting.

## Local development

```bash
cp .env.example .env
# Add one LLM provider key to .env.
docker compose up -d postgres
mix setup
mix phx.server
```

The local API runs at `http://localhost:4000`. Check it with:

```bash
curl http://localhost:4000/api/health
```

`COMMUNITY_MODE=true` creates anonymous local sessions and bypasses paid
entitlements. Never enable it on an untrusted public or production server.

Run the backend checks with:

```bash
mix precommit
```

See [DEPLOYMENT.md](DEPLOYMENT.md), the root
[contribution guide](../CONTRIBUTING.md), and [SECURITY.md](../SECURITY.md). The
project is licensed under the root [Apache License 2.0](../LICENSE).

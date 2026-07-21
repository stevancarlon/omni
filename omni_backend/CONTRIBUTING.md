# Contributing

Thank you for contributing to the Omni backend.

## Setup

Follow [README.md](README.md), keep `COMMUNITY_MODE=true` local, and use placeholder or test provider credentials. Never attach real prompts, screenshots, tokens, database dumps, `.env` files, service-account JSON, or webhook secrets to issues.

## Workflow

1. Search existing issues and pull requests.
2. Discuss large API, schema, billing, or provider changes in an issue first.
3. Keep changes focused and add tests for behavior changes.
4. Use `Req` for HTTP clients.
5. Run:

```bash
mix precommit
```

## Pull requests

Describe the problem, approach, API or migration impact, privacy impact, and verification performed. By contributing, you agree that your work is licensed under Apache-2.0.

Report vulnerabilities privately according to [SECURITY.md](SECURITY.md).

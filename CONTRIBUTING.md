# Contributing to Omni

Thanks for helping make Omni safer, easier to self-host, and more reliable
across Android devices.

## Start here

1. Follow the [installation guide](README.md) and confirm the community app can
   reach your local backend.
2. Search existing issues and pull requests.
3. Choose an issue labeled `good first issue` or `help wanted`, or open an issue
   before making a large architectural change.
4. Create a focused branch from `main`.

Never include real screenshots, prompts, contacts, credentials, tokens,
database dumps, signing files, or private app data in commits or issues. Use
synthetic fixtures and redact logs.

## Project layout

- `omni-mobile/` contains the Kotlin Android client.
- `omni_backend/` contains the Elixir/Phoenix API.
- `.github/workflows/ci.yml` defines the required monorepo checks.

Store integrations belong in their Android flavor-specific source sets. The
`community` flavor must remain buildable without Google Play, Aptoide, signing
credentials, or hosted-service secrets.

## Run checks

For Android changes:

```bash
cd omni-mobile
./gradlew lintCommunityDebug testCommunityDebugUnitTest assembleCommunityDebug
```

For backend changes:

```bash
cd omni_backend
mix precommit
```

Accessibility behavior crosses app and system boundaries, so automation is not
enough. For behavioral changes, also test on a physical device and include the
device model, Android version, target app/version, sanitized reproduction steps,
expected result, and actual result in the pull request.

## Pull requests

- Explain the user problem and why the approach is safe.
- Keep the change focused and avoid unrelated formatting.
- Add or update tests when behavior changes.
- Update setup, privacy, or architecture documentation when relevant.
- Complete the pull-request safety and privacy section.
- Confirm that no private data or secrets are present.

By contributing, you agree that your contribution is licensed under
Apache-2.0. Security vulnerabilities must follow [SECURITY.md](SECURITY.md), not
the public issue tracker.

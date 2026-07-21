# Contributing

Thank you for helping improve Omni. Contributions should make the agent safer, easier to self-host, more accessible, or more reliable across Android devices.

## Before coding

1. Search existing issues and pull requests.
2. Open an issue before large architectural changes.
3. Use a focused branch and avoid unrelated formatting changes.
4. Never include real screenshots, credentials, tokens, private app data, or signing material in an issue or fixture.

## Development

Follow the community quick start in [README.md](README.md). Verify Android changes with:

```bash
./gradlew lintCommunityDebug
./gradlew testCommunityDebugUnitTest
./gradlew assembleCommunityDebug
```

For UI automation changes, include the device model, Android version, target app/version, sanitized reproduction steps, expected result, and actual result. Use synthetic data in screenshots.

## Pull requests

- Explain the user problem and the chosen approach.
- Keep store-specific code in the appropriate source set.
- Add or update tests when behavior changes.
- Update documentation for configuration or privacy changes.
- Confirm that you have not committed secrets or private data.
- By contributing, you agree that your work is licensed under Apache-2.0.

Security vulnerabilities must follow [SECURITY.md](SECURITY.md), not the public issue tracker.

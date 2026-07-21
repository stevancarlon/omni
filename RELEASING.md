# Releasing Omni for Android

This checklist is for project maintainers. Contributors can build `communityDebug`
without any signing credentials.

## Before the first public release

- Change the password on the existing private keystore: an old placeholder
  password was previously present in source history. Keep the same signing key if
  users already have an official build installed.
- Back up the keystore and its passwords in a private password manager. Never add
  them to this repository, an issue, a CI log, or a release artifact.
- Review the accessibility disclosure, privacy policy, and distribution-channel
  requirements for the exact build being shipped.

## Build

Set the four signing values described in the README, then run:

```bash
./gradlew clean lintGooglePlayRelease testGooglePlayReleaseUnitTest assembleGooglePlayRelease
```

For a community APK, use `assembleCommunityRelease`. With no private signing
variables it intentionally produces an unsigned release artifact.

## Verify and publish

- Install the exact artifact on a clean device and complete onboarding.
- Exercise voice input, accessibility actions, login, billing or community mode,
  logout, and the sensitive-app guard.
- Inspect the certificate with `apksigner verify --print-certs <apk>`.
- Publish a SHA-256 checksum and concise release notes.
- Tag the source commit used for the binary and attach only deliberate artifacts.

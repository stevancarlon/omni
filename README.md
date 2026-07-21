# Omni

Omni is an experimental open-source Android agent that can see the screen and
operate the phone through Android's Accessibility API. Give it a goal and it can
tap, type, swipe, navigate, open apps, and adapt as the screen changes.

## Demo

<!-- Add the demo video here. GitHub supports an uploaded video URL on its own line. -->

_Demo video coming soon._

## Run it

You need Android 10+, Android Studio, and a running
[Omni backend](https://github.com/stevancarlon/omni-backend) configured with
`COMMUNITY_MODE=true`.

```bash
adb reverse tcp:4000 tcp:4000
cp local.properties.example local.properties
./gradlew assembleCommunityDebug
adb install -r app/build/outputs/apk/community/debug/app-community-debug.apk
```

Open Omni and choose **Connect to community backend**. On Android 13+, you must
also open Omni's App Info screen and select **Allow restricted settings** before
enabling its Accessibility service.

The community flavor is self-hosted, contains no store billing integration, and
does not require Google sign-in.

> [!WARNING]
> Omni can read screen content and interact with other apps. Only install builds
> you trust. Do not use it to automate passwords, banking, payments, or
> authentication challenges. This project is alpha software and is not currently
> distributed through Google Play.

## Project docs

- [Architecture](ARCHITECTURE.md)
- [Privacy model](PRIVACY.md)
- [Roadmap](ROADMAP.md)
- [Contributing](CONTRIBUTING.md)
- [Security](SECURITY.md)
- [Releasing](RELEASING.md)

## License

Apache License 2.0. The Omni name, wordmark, and logo may not be used to imply
that an unofficial build is operated or endorsed by the project.

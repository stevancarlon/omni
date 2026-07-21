# Omni

Omni is an experimental open-source Android agent that can see the screen and
operate a phone through Android's Accessibility API. Give it a goal and it can
tap, type, swipe, navigate, open apps, and adapt as the screen changes.

## Demo

<!-- Replace the line below with your uploaded demo video URL. -->

_Demo video coming soon._

## Repository

- [`omni-mobile/`](omni-mobile/) — native Android application
- [`omni_backend/`](omni_backend/) — self-hostable Phoenix API

Start with the [Android setup guide](omni-mobile/README.md) and run the backend
with `COMMUNITY_MODE=true`. The community build requires neither Google sign-in
nor store billing.

> [!WARNING]
> Omni can read screen content and interact with other apps. Only install builds
> you trust. Do not use it to automate passwords, banking, payments, or
> authentication challenges. This is alpha software and is not currently
> distributed through Google Play.

## Contributing and license

Read the [contribution guide](omni-mobile/CONTRIBUTING.md) and
[security policy](omni-mobile/SECURITY.md) before opening an issue or pull
request. The project is available under the
[Apache License 2.0](omni-mobile/LICENSE).

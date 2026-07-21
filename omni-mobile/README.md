# Omni Android

The native Android client for the [Omni monorepo](..). It observes Android UI
through an Accessibility service, sends bounded screen context to the
self-hosted backend, validates the returned action, and executes it.

Follow the complete [installation guide](../README.md) to configure API keys,
start the backend, install the community build, and enable Android permissions.

## Community build

```bash
cp local.properties.example local.properties
./gradlew installCommunityDebug
```

With `adb reverse tcp:4000 tcp:4000`, the default backend URL is
`http://127.0.0.1:4000`. For a remote server, set
`OMNI_COMMUNITY_BACKEND_URL=https://your-backend.example` in
`local.properties`.

The community flavor has package ID `com.omni.orb.community`, no store billing,
and no Google sign-in requirement.

## Checks

```bash
./gradlew lintCommunityDebug
./gradlew testCommunityDebugUnitTest
./gradlew assembleCommunityDebug
```

See [ARCHITECTURE.md](ARCHITECTURE.md), [PRIVACY.md](PRIVACY.md), and the root
[contribution guide](../CONTRIBUTING.md). The project is licensed under the root
[Apache License 2.0](../LICENSE).

# Heeler for Android

Native Kotlin / Jetpack Compose companion for herdr, targeting Android 17
(API 37) with a minimum of Android 13 (API 33). Design rationale is in
[ADR 0017](../docs/adr/0017-android-kotlin-compose-port.md); herdr vocabulary
and the load-bearing protocol facts are in the repository root `CONTEXT.md`
and `AGENTS.md`.

## Layout

| Module   | Kind      | Owns                                                                                     |
| -------- | --------- | ---------------------------------------------------------------------------------------- |
| `:herdr` | pure JVM  | Generated wire types, NDJSON framing, event kinds, `Transport` interface, `PairingCode`  |
| `:ssh`   | pure JVM  | `JschTransport` (direct-streamlocal RPC, events, PTY attach), Ed25519 identity, TOFU     |
| `:app`   | Android   | Compose UI, Host store, Keystore-wrapped Device Key, known hosts, `HeelerApplication`    |

The UI depends on `dev.bybee.heeler.herdr.Transport` only; JSch types never
reach `:app` code outside `AppContainer`.

`herdr/src/main/kotlin/dev/bybee/heeler/herdr/generated/HerdrAPITypes.kt` is
generated. Regenerate it together with the Swift types:

```sh
python3 scripts/generate-wire-types.py --schema scripts/herdr-schema.json   # from the repo root
```

## Prerequisites

- JDK 17
- Android SDK with `platforms;android-37`, `build-tools;37.0.0`, `platform-tools`
  (`ANDROID_HOME` set or `local.properties` with `sdk.dir=`)
- For the transport E2E script: `sshd`, `ssh-keygen`, `python3` (no root needed)

Versions of AGP, Kotlin, Compose, JSch and BouncyCastle are pinned exactly in
`gradle/libs.versions.toml`; bump deliberately.

## Commands

Run from `android/`.

| Task                                          | Command                                                   |
| --------------------------------------------- | --------------------------------------------------------- |
| Wire, events, domain unit tests               | `./gradlew :herdr:test`                                   |
| Transport unit tests (E2E cases skip)         | `./gradlew :ssh:test`                                     |
| Transport E2E against real sshd + fake herdr  | `./scripts/run-transport-e2e.sh`                          |
| Debug APK                                     | `./gradlew :app:assembleDebug`                            |
| Lint                                          | `./gradlew :app:lintDebug`                                |
| Verify rendered screens against references    | `./gradlew :app:validateDebugScreenshotTest`              |
| Regenerate screenshot references (after a UI change; inspect the PNGs) | `./gradlew :app:updateDebugScreenshotTest` |

`run-transport-e2e.sh` provisions two unprivileged sshd instances on loopback
(one allowing and one denying stream-local forwarding), starts the shared
`scripts/fixtures/fake-herdr-streamlocal.py`, runs `:ssh:test` with the
`heeler.e2e.*` system properties set, and fails if the E2E cases were skipped
instead of executed.

Screenshot references live under `app/src/screenshotTestDebug/reference/` and
are the only rendered-UI check that runs without an emulator (orbs and Linux CI
have no KVM). They cover layout, not interaction: run the debug APK on a device
or emulator for interactive checks.

## Status

First slice: Hosts, Device Key display, Agent list with live status, Agent
output (`agent.read` with `pane.read` fallback), prompting. Interactive
terminal Attach is exposed by the transport but has no UI yet — see ADR 0017
for the engine options.

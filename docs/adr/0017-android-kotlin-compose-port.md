---
status: accepted
---

# Android port as a native Kotlin/Compose app in `android/`

Heeler ships an Android companion built natively in Kotlin with Jetpack Compose,
living in `android/` of this repository, targeting Android 17 (API 37) with a
minimum of Android 13 (API 33). It reuses herdr's transport design unchanged:
the JSON API over OpenSSH `direct-streamlocal` channels onto the herdr socket
(ADR 0011), the same protocol floor (17) and generated protocol (22) as the iOS
app, and the same wire types, generated from the committed schema snapshot.

## Considered Options

- **Flutter (dartssh2 + xterm.dart), the "Plan B" of ADR 0001** — rejected.
  Adopting it now would mean either rewriting the iOS app or maintaining two
  UI stacks anyway, and xterm.dart is still the weak board. The Android port
  does not change the iOS stack, so the Plan B rationale (one codebase for two
  platforms) does not apply.
- **Kotlin Multiplatform sharing code with the Swift app** — rejected. The iOS
  app is Swift end to end and the Swift `Transport` layer is small; the parts
  worth sharing (wire types) are already shared through codegen from
  `scripts/herdr-schema.json`.
- **Native Kotlin + Compose** — chosen. First-party toolchain for Android 17,
  and the parts of Heeler that are policy rather than pixels (request queue,
  event sessions, TOFU host keys, attach command construction) translate
  one-to-one into pure-JVM Kotlin that is unit-tested without an emulator.

## Structure

Three Gradle modules mirror the iOS layering so the UI never sees SSH types:

- `:herdr` (pure JVM) — generated wire types, `HerdrWire` framing,
  `HerdrEvents`, the `Transport` interface and domain types, `PairingCode`.
- `:ssh` (pure JVM) — `JschTransport` implementing `Transport` over the mwiede
  JSch fork, Ed25519 Device Key identity via BouncyCastle, `TofuHostKeyVerifier`,
  and byte-for-byte parity with the iOS attach command strings.
- `:app` (Android) — Compose UI, Preferences DataStore for Hosts, Android
  Keystore wrapping of the Device Key seed, known-hosts store.

## Trade-offs accepted

- **JSch (mwiede fork) instead of libssh2.** JSch is the only maintained JVM
  SSH client with `direct-streamlocal@openssh.com`, PTY exec with window
  change, and a pluggable `Identity`. Ed25519 host-key verification and Device
  Key signing go through BouncyCastle because Android's JCA has no Ed25519
  provider on every supported release. Both are pinned exactly in
  `android/gradle/libs.versions.toml`.
- **No embedded terminal in the first slice.** The Android Console lists
  Hosts and Agents, reads Agent output (`agent.read`, falling back to
  `pane.read` on `agent_not_idle`), prompts, and follows live status through
  `pane.agent_status_changed`. The interactive Attach terminal is deferred:
  the mature Android terminal engine (Termux's) is GPLv3, incompatible with
  this repo's Apache-2.0 license. Candidates for a later ADR are jackpal's
  `EmulatorView`, xterm.js in a WebView, or libghostty through the NDK. The
  transport already exposes `attach` with PTY and resize so the UI can adopt
  an engine without a transport change.
- **Device Key storage.** Android Keystore cannot sign Ed25519 (only EC P-256
  and RSA), and OpenSSH Ed25519 is what Heeler pairs with. The Ed25519 seed is
  therefore generated on device and stored wrapped by a Keystore AES-GCM key
  (`device_key.v1`), excluded from backup and device transfer. This is weaker
  than the iOS Keychain-resident key: the seed exists in app memory while
  signing. Switching to an ECDSA key signed inside the Keystore would restore
  hardware isolation at the cost of a second key type in the pairing flow.
- **Rendered-UI verification without an emulator.** Orbs and Linux CI have no
  KVM, so the only rendered check is Compose Preview Screenshot Testing
  (`:app:validateDebugScreenshotTest`) against committed references. It is a
  layout regression test, not an interaction test; device or emulator runs
  remain a local, manual step.

## Consequences

- `android/**` changes run `.github/workflows/ci-android.yml`; they never start
  the macOS simulator jobs.
- `scripts/generate-wire-types.py` now emits both Swift and Kotlin from one
  schema snapshot; `--check` fails on drift in either.
- The E2E script `android/scripts/run-transport-e2e.sh` reuses
  `scripts/fixtures/fake-herdr-streamlocal.py`, so a fixture change exercises
  both apps' transports.

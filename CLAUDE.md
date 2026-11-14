# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

DioxamineVR is a fork of [Dioxamine](https://github.com/rhythmcache/Dioxamine), an Android app (Kotlin + Jetpack Compose) that acts as an ADB / Scrcpy / Fastboot client running phone-to-phone over USB OTG or Wireless ADB, with an HTML/JS plugin engine. The fork's recent work targets TV / VR / D-pad / remote-control use (side rail navigation, focus rings, remote-friendly scrcpy overlay). Application id and Kotlin package remain `io.github.rhythmcache.dioxamine`.

## Build and test

Requires JDK 17+, Android SDK (compileSdk 37), and the Android NDK (`ANDROID_NDK_HOME`, CI uses 29.0.14206865). Clone with `--recurse-submodules`; the `scrcpy/` directory is a git submodule (currently empty in a plain checkout).

```bash
./gradlew assembleDebug            # debug APK -> app/build/outputs/apk/debug
./gradlew testDebugUnitTest        # all JVM unit tests (what CI runs)
./gradlew :app:testDebugUnitTest --tests "io.github.rhythmcache.dioxamine.plugin.PluginManifestTest"   # single test class
./gradlew ktlintCheck              # lint (org.jlleitschuh ktlint plugin on :app)
./gradlew ktlintFormat
```

Release builds (`assembleRelease`) are minified and read signing config from env vars `KEYSTORE_PATH`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. Unit tests use real `org.json` on the test classpath because Android's stub throws "not mocked".

### Build-time asset generation (`app/build.gradle.kts`)

`preBuild` depends on three tasks that generate files into `app/src/main/assets/`; they are not checked in, so a build needs the SDK build-tools (d8), NDK, and (optionally) the scrcpy submodule:

- `buildScrcpyServer` → `scrcpy-server.jar`, built from the `scrcpy` submodule (`onlyIf` the submodule has `server/`; silently skipped otherwise).
- `buildDioxAgentJar` → `diox-agent.jar`: `src_ext/DioxAgent.java` compiled with javac against `android.jar` plus hand-written hidden-API stubs in `src_ext/stub/`, then dexed with d8. This runs on the *target* device (package dumping, etc.).
- `buildDxlsNative` → `assets/dxls/dxls-<abi>`: `src_ext/dxls.c` (fast native directory lister served over an abstract unix socket, used by the file manager) cross-compiled for arm64-v8a, armeabi-v7a, x86, x86_64 using the NDK clang. Skipped if no NDK is found under the SDK.

## Module layout

Gradle modules (see `settings.gradle.kts`): `:app`, `:app-logger` (the `AppLogger` object), `:adb-kt` (project dir is `adb-kt/lib`), `:fastboot-kt` (project dir is `fastboot-kt/lib`), `:terminal-emulator` and `:terminal-view` (vendored Termux terminal). `adb-kt` and `fastboot-kt` are vendored copies of standalone libraries (each has its own Gradle wrapper, README, CI); changes there are library changes, so keep them API-compatible with the app.

`app/src/main/kotlin/io/github/rhythmcache/dioxamine/` is organised by feature:

- `adb/` — `AdbViewModel` is the central state holder: a map of `DeviceConnection`s (USB and TCP), an "active device" selection, key management (`AdbKeyManager`), pairing, and flashing state. `adb/builtin/*` holds the built-in tools (file manager, package manager, process manager, logcat, misc, reboot, screencap, touchpad/UHID, remote control), each typically a `*Screen` + `*Tile` pair surfaced in `BuiltInActionsTab`. `adb/discovery/` covers NSD discovery, QR pairing, and local ADB detection; `adb/shell/` is the interactive terminal on top of `terminal-view`.
- `scrcpy/` — `ScrcpySession` pushes/starts the scrcpy server over an `AdbClient`, opens video/audio/control sockets via `AdbEndpoint`, and feeds `ScrcpyVideoDecoder` / `ScrcpyAudioDecoder` (MediaCodec) to a Surface. `ScrcpyControl` encodes control messages (touch, keys, UHID, clipboard); `ScrcpyConfig`/`ScrcpyConfigStore` hold settings; overlays (`ScrcpyTouchpadOverlay`, `ScrcpyTvOverlay`) sit on top of the player.
- `fastboot/` — `FastbootViewModel` plus screens; talks to `fastboot-kt` via `core/UsbFastbootTransport` (USB) or TCP. Commands are serialized with a mutex; `FastbootCommandParser` parses the shell input.
- `core/` — shared infrastructure: USB transports (`UsbPacketTransport` for ADB, `UsbFastbootTransport`), `UsbHelper`, `DioxForegroundService`, theme, `UpdateChecker`, constants.
- `plugin/` — the plugin engine. Plugins are `.zip` bundles with a `plugin.json` manifest (`PluginManifest`) run in a sandboxed WebView (`PluginRunnerScreen`). `DioxaminePluginBridge` (large; the native side of the JS API) is paired with the JS shim `app/src/main/assets/plugin_runtime/dioxamine-bridge.js`. Access is gated per-permission (`PluginPermissionGate`, `PluginPermissionDialog`); plugin install, online registry, and update checking live alongside. When adding bridge APIs, update the JS shim, the permission gate, and `docs/src/plugins/api-reference.md`.
- `ui/tv/TvComponents.kt` — TV/D-pad drop-in replacements for Material 3 components (buttons, chips, switches, text fields, menu items) with focus borders and press-to-edit text fields. Screens opt in by importing the component by name from `io.github.rhythmcache.dioxamine.ui.tv` (explicit imports win over `androidx.compose.material3.*`), so call sites stay unchanged. New screens should import from there to stay D-pad friendly.
- `MainActivity.kt` wires the tabs (a left side rail, designed for D-pad navigation), USB attach handling, and the view models.

## Docs, i18n, release

- User/plugin docs are an mdBook under `docs/src` (`docs/book.toml`); `docs/book/` is generated output committed by the docs workflow on `main`, so don't edit it by hand.
- Translations live in `app/src/main/res/values-<locale>/strings.xml`; new languages must also be registered in `settings/SupportedLanguages.kt` and have a `settings_language_*` string marked `translatable="false"`. `scripts/translation_coverage.py` generates `badges/coverage.svg` (CI only, on `main`).
- Version is set in `app/build.gradle.kts` (`versionCode` / `versionName`); each release has a matching `assets/changelogs/<versionCode>.txt` and a `CHANGELOG.md` entry.
- CI (`.github/workflows/build_debug.yml`) runs unit tests then builds debug and release APKs on every push.

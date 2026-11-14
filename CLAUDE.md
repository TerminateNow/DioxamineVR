# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Dioxamine is an Android app (package `io.github.rhythmcache.dioxamine`, Kotlin + Jetpack Compose) that runs ADB, Scrcpy and Fastboot *from* a phone/tablet against another device over USB OTG or Wireless ADB. No root or companion app is needed on the target device. The Gradle project name is `Dioxamine`; the checkout directory here is `DioxamineVR`.

## Build & test

Requirements: JDK 17+, Android SDK (compileSdk 37; CI also installs platform-tools, build-tools 34.0.0), and an Android NDK (`ANDROID_NDK_HOME`, CI pins `29.0.14206865`). Clone with `--recurse-submodules`: the `scrcpy` submodule (Genymobile/scrcpy) is required.

```bash
./gradlew assembleDebug            # debug APK (release: assembleRelease)
./gradlew testDebugUnitTest        # all JVM unit tests (what CI runs)
./gradlew :app:testDebugUnitTest --tests "io.github.rhythmcache.dioxamine.scrcpy.ScrcpyConfigTest"   # single test class
./gradlew ktlintCheck              # lint (ktlint Gradle plugin is applied in :app)
```

- The repo has no `.editorconfig`, so ktlint runs with defaults, and some existing files probably don't pass it. Check before assuming a failure is yours.
- Unit tests live under `app/src/test` (plugin, scrcpy, touchpad/HID, fastboot parser, update checker) and in the library modules (`adb-kt/lib/src/test`, `fastboot-kt/lib/src/test`). There are no instrumented tests.
- Release signing is read from env vars (`KEYSTORE_PATH`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`); unset for debug builds.
- `docs/` is an mdBook (`cd docs && mdbook build`). The `docs.yml` workflow rebuilds `docs/book` on pushes to `main` that touch `docs/**`, so don't hand-edit `docs/book`.

## Module layout

`settings.gradle.kts` wires these modules. Two of them are checked in with their own Gradle wrapper/CI but are consumed here as source modules:

- `:app` is the application.
- `:adb-kt` (dir `adb-kt/lib`) is a pure-Kotlin ADB client library: protocol, auth, pairing (TLS), sync, shell, forward/reverse, sideload, rescue, install. It is transport-agnostic via `PacketTransport`.
- `:fastboot-kt` (dir `fastboot-kt/lib`) is a Kotlin Fastboot client with sparse-image planning/splitting, transport-agnostic via `FastbootTransport`.
- `:terminal-emulator` and `:terminal-view` are a vendored Termux terminal (Java, `com.termux.*`).
- `:app-logger` provides `AppLogger`, used app-wide.

Note the path quirk: the Gradle project `:adb-kt` lives in `adb-kt/lib`, and `adb-kt/` itself is a standalone Gradle build.

## Architecture

**Single-activity, tab-based UI.** `MainActivity.kt` holds a `Tab` enum (ADB, SCRCPY, FASTBOOT, PLUGINS, SETTINGS) and swaps top-level screens in a `when`; there is no Navigation component. Feature areas are packages under `app/src/main/kotlin/io/github/rhythmcache/dioxamine/`:

- `adb/` holds the connection and shell UI. `AdbViewModel` owns the `AdbClient` and the connected-device state that other tabs share. `adb/discovery/` handles NSD/mDNS discovery and QR pairing, and `adb/builtin/*` holds the built-in tools (file manager, logcat, package/process manager, reboot, screencap, touchpad/UHID, remote control, misc). `adb/shell/` is the Termux-backed terminal.
- `scrcpy/` is the mirroring client. It speaks the scrcpy protocol directly (no scrcpy binary on the host). `ScrcpySession` pushes the bundled `scrcpy-server.jar` asset to the device's tmp dir, launches it with `app_process`, and opens separate video/audio/control `AdbStream`s. These feed `ScrcpyVideoDecoder`/`ScrcpyAudioDecoder` (MediaCodec), `ScrcpyControl`, and `ScrcpyRecorder`. `ScrcpyConfig.toServerArgs()` builds the server CLI args, and `Constants.SCRCPY_SERVER_VERSION` must match the bundled server build.
- `fastboot/` is the Fastboot UI and `FastbootViewModel`. `core/UsbFastbootTransport` and `core/UsbPacketTransport` adapt Android USB host APIs to the library transport interfaces. Fastboot also works over TCP.
- `plugin/` is the HTML/JS plugin engine. Plugins are zip packages with a `plugin.json` manifest (`PluginManifest`), run in a sandboxed WebView, and talk to the app through `DioxaminePluginBridge`, a `@JavascriptInterface` exposing ADB shell, fastboot, file push/pull, forwarding, HTTP, theming, locale and SAF. Every capability is gated by user-approved permissions (`PluginPermissionGate`/`PluginPermissionDialog`, e.g. `adb.shell`, `fastboot`). The JS side of the bridge is `app/src/main/assets/plugin_runtime/dioxamine-bridge.js`. Changing the bridge API means editing both the Kotlin and JS sides, plus `docs/src/plugins/`. `PluginOnlineRepository` and `PluginUpdateChecker` handle the community plugin registry.
- `core/` holds shared pieces: `DeviceConnection`, `Constants`, `Theme`, `UpdateChecker`, the foreground service and USB helpers.

**Native/helper assets are built during Gradle `preBuild`** (see the "Native / external asset generation" section of `app/build.gradle.kts`). These are generated into `app/src/main/assets` and are not checked in:
- `scrcpy-server.jar` is built by running `server:assembleRelease` in the `scrcpy` submodule (a nested `GradleBuild`).
- `src_ext/DioxAgent.java` is compiled with `javac` against the compileSdk `android.jar` plus hidden-API stubs in `src_ext/stub/`, then dexed with `d8` into `diox-agent.jar`. It runs on the target device and does things plain shell can't (e.g. package info and icons).
- `src_ext/dxls.c` (+ `dxsock.h`) is cross-compiled with the NDK for four ABIs. It is a fast directory-lister daemon pushed to the target device that serves NDJSON over the abstract socket `localabstract:dxls`, and the file manager talks to it. It self-unlinks on start and exits after an idle timeout.

So the app is a host-side client plus small device-side helpers pushed over ADB at runtime. Behavior spans the Kotlin client, these helper sources, and the `Constants` that tie them together.

## Conventions

- **Localization:** all user-facing strings go in `app/src/main/res/values/strings.xml`; translations live in `values-<locale>/` (currently de, fa, hi, ru, zh-rCN). New languages must also be registered in `settings/SupportedLanguages.kt`, with a `translatable="false"` endonym string. The README documents the full steps. `scripts/translation_coverage.py` (run by the `translation-coverage` workflow) generates `badges/coverage.svg`.
- **Versioning:** `versionCode`/`versionName` are set in `app/build.gradle.kts`, and user-visible changes are recorded in `CHANGELOG.md` per release.
- CI (`build_debug.yml`) runs the unit tests and builds both debug and release on every push.

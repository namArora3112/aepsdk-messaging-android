# Experiment 1: build native Messaging source with AGP 9

## Purpose

Reproduce the native SDK producer's build-tooling incompatibility without
permanently upgrading the producer. This is not an AAR-consumer test and
does not establish that a published Messaging AAR is incompatible.

## Setup

- Normal producer: `code/`, Gradle 8.2, original Commons
  `com.github.adobe:aepsdk-commons:gp-3.4.1`, providing AGP 8.2.0.
- Explicit experiment: Gradle 9.1.0 and AGP 9.0.1, JDK 17, Android SDK.
- The original source build still includes all its modules.
- `source-build.init.gradle` temporarily selects AGP 9.0.1 on buildscript
  classpaths and prints the effective module plugin/Kotlin state.
- No Commons replacement, Kotlin fix, `android.builtInKotlin=false`, or
  `android.newDsl=false` is applied.

The dedicated wrapper lives here so the normal producer wrapper remains
unchanged. An Android SDK path must be configured with `ANDROID_HOME` or the
usual local SDK configuration.

## Run

From the repository root:

```bash
./experiments/agp9/run-producer.sh
```

The script invokes the experimental wrapper against `code/` and requests
`:messaging:bundlePhoneReleaseAar`. It is expected to exit nonzero.

## Observed result

Verified on 2026-11-11: the command failed before building an AAR.

```text
Gradle=9.1.0
Commons=com.github.adobe:aepsdk-commons:gp-3.4.1
:messaging: AGP=9.0.1, plugin=com.android.library
:messaging: kotlinExtension=true, externalKotlinPlugin=false
:messaging: android.builtInKotlin=<unset>
:messaging: android.newDsl=<unset>
```

Applying `aep-library` at `code/messaging/build.gradle.kts:14` fails:

```text
Failed to apply plugin 'kotlin-android'.
Cannot add extension with name 'kotlin',
as there is an extension already registered with that name.
```

The stack points to the original Commons plugin's
`AEPLibraryPlugin.apply(AEPLibraryPlugin.kt:60)`. The Android plugin has already
provided Kotlin support; Commons then tries to add external Kotlin to the
same source module.

## Limits and normal build

This reproduces the first producer configuration blocker. Later DSL,
publishing, documentation, Compose, screenshot, compilation, and runtime
compatibility is not verified by this failed run.

There is no configuration to revert. To use the normal producer again,
omit the experimental runner/init script:

```bash
cd code
./gradlew :messaging:bundlePhoneReleaseAar --console=plain --no-daemon
```

The normal wrapper still selects Gradle 8.2 and Commons still supplies
AGP 8.2.0. The experiment does not commit build outputs or machine-local logs.

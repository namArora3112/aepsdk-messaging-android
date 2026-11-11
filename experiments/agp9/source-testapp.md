# Experiment 3: AGP 9 testapp builds Messaging from source

## Purpose

Compare the original testapp's `implementation(project(":messaging"))` with
the standalone consumer's Messaging Maven AAR dependency. Keep the existing
UI and source unchanged; vary the build boundary, not Messaging runtime code.

The original `code/settings.gradle.kts` includes `:testapp`, `:messaging`,
and `:messagingtestutils`. The source-integrated testapp and SDK modules
belong to the same Gradle build. The AGP 9 experiment therefore affects SDK
source modules as well as the app; it is not an app-only AGP upgrade.

## Run

Use JDK 17 and a configured Android SDK. From the repository root:

```bash
./experiments/agp9/run-source-testapp.sh
```

The runner reuses the independent Gradle 9.1.0 wrapper and command-scoped
AGP 9.0.1 override from experiment 1. It selects the original `code/` build
and requests `:testapp:assembleDebug`. The original Commons dependency
remains `gp-3.4.1`; AGP Kotlin/DSL defaults are not disabled.

## Observed result

Verified on 2026-11-11: failed with the expected Kotlin-extension collision
while configuring `:messaging`, before testapp compilation or a new APK.

```text
Gradle=9.1.0
Commons=com.github.adobe:aepsdk-commons:gp-3.4.1
:messaging: AGP=9.0.1
:messaging: kotlinExtension=true, externalKotlinPlugin=false
:messaging: android.builtInKotlin=<unset>
:messaging: android.newDsl=<unset>
```

The error enters through `code/messaging/build.gradle.kts:14`, applying
`aep-library`. Commons' `AEPLibraryPlugin.kt:60` applies `kotlin-android`,
which attempts to create another Kotlin extension after AGP 9 has created it.
The testapp has its own external Kotlin application, but this run stops at
the SDK module first; it does not independently verify later app failures.

## Why the AAR consumer passed

```text
Source dependency:
AGP 9 shared build
  -> execute SDK module build script / Commons
  -> external Kotlin collision

AAR dependency:
AGP 8.2 producer builds Messaging separately
  -> published compiled AAR
  -> AGP 9 standalone app consumes it without executing Commons
```

React Native autolinking similarly includes the RN wrapper as a source
module, while the wrapper still consumes the underlying native SDK AAR.
The RN wrapper's Kotlin collision and the Commons producer collision have
different owners; fixing one does not require executing or fixing the other
when the native SDK remains a prebuilt dependency.

## Limits and configuration preservation

No Kotlin/Commons/DSL fixes, skipped modules, lint suppressions, source
changes, or persistent producer toolchain upgrade are part of this runner.
The expected failure establishes a configuration blocker, not SDK runtime
incompatibility. Further app/Compose and producer tooling migration remains
unverified.

To return to the normal shared source build, do not invoke this runner:

```bash
cd code
./gradlew :testapp:assembleDebug --console=plain --no-daemon
```

That selects the original Gradle 8.2 / AGP 8.2.0 configuration. For the
working AGP 9 AAR consumer, open `code/testapp/` independently and follow
[experiment 2](aar-consumer.md).

The consolidated [RCA](../../docs/runbook/agp9-messaging-rca.md) records the
build pipelines, verified outcomes, historical migration attempts, and
remaining limitations. Historical Commons patches are not published as part
of these three native experiments.

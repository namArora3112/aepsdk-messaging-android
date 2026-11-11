# Experiment 2: AGP 9 app consumes a prebuilt Messaging AAR

## Purpose and build boundary

Keep the SDK producer on its original AGP 8.2.0 / Gradle 8.2 toolchain.
Upgrade only the consuming application build to AGP 9.0.1 / Gradle 9.1.0.
This tests published-library consumption rather than SDK producer migration.

The same existing testapp source, UI, application ID, SDK levels, Firebase
configuration, and sample dependency versions are retained.

| Entry point | Dependency | Toolchain |
|---|---|---|
| `code/` | Original testapp builds `project(":messaging")` | Original producer AGP 8.2.0 / Gradle 8.2 |
| `code/testapp/` as an independent root | Messaging Maven AAR | Consumer AGP 9.0.1 / Gradle 9.1.0 |

The new `code/testapp/settings.gradle.kts` selects `build-agp9.gradle.kts`.
It does not include the SDK modules or a composite producer build.
The original `code/testapp/build.gradle.kts` is unchanged.

The standalone app uses AGP built-in Kotlin and Compose compiler plugin
2.2.10, not external Kotlin Android or the old Compose compiler-extension
setting. Google Services is 4.4.4. No AGP compatibility opt-outs are added.
SDK version properties are read from `code/gradle.properties`; producer
build flags are not imported.

## Prerequisites

Use JDK 17 and a configured Android SDK. Local sample/Firebase configuration
is still required for the corresponding runtime flows. The experiment does
not publish credentials, local SDK paths, or the AAR binary to Git.

## Publish Messaging with the original producer

From the repository root:

```bash
cd code
./gradlew :messaging:bundlePhoneReleaseAar \
  :messaging:publishReleasePublicationToMavenLocal \
  -PmoduleVersion=3.13.0-agp9-consumer-local-20261110 \
  --console=plain --no-daemon
```

Result on 2026-11-11: passed. The publication automatically appends
`-SNAPSHOT`, producing:

```text
com.adobe.marketing.mobile:messaging:3.13.0-agp9-consumer-local-20261110-SNAPSHOT
```

That full version is the standalone app's `messagingLocalVersion`.
Existing duplicate-path warnings from the SDK sources archive did not
prevent publication.

## Build and resolve the standalone consumer

From the repository root:

```bash
cd code/testapp
./gradlew assembleDebug dependencyInsight \
  --configuration debugRuntimeClasspath \
  --dependency com.adobe.marketing.mobile:messaging \
  --console=plain --no-daemon
```

Result on 2026-11-11: passed. The dependency report resolves the exact local
Messaging snapshot and requests `AgpVersionAttr=9.0.1`. The generated
`build/outputs/apk/debug/messaging-testapp-agp9-consumer-debug.apk` contains:

```properties
appMetadataVersion=1.1
androidGradlePluginVersion=9.0.1
```

Open `code/testapp/` as its own Android Studio project to run this consumer.
Opening `code/` instead selects the original shared source build.

Earlier, the user ran the standalone app; read-only inspection of its
installed APK confirmed AGP 9.0.1 and a running process. The fresh command
above builds without installing or replacing that running app.

## Lint result and limits

From `code/testapp/`:

```bash
./gradlew lintDebug --console=plain --no-daemon
```

Result on 2026-11-11: failed with 2 errors and 85 warnings. Both errors are
`MissingConstraints` in unchanged sample layouts:

- `src/main/res/layout/activity_second_level.xml:22`
- `src/main/res/layout/activity_third_level.xml:22`

The sample source files were not changed. No lint baseline, suppression, or
unrelated layout fix was added to make this experiment appear green.

This establishes the tested debug build, dependency consumption, and earlier
basic launch. It does not certify all AGP 9.x versions, release/R8 behavior,
all devices, push delivery, or every Messaging API.

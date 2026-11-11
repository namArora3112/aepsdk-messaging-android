# Messaging Android: AGP 9 consumer test and producer investigation

## Published native experiments

These are three separate experimental commits, not a production AGP 9 SDK
migration. The normal producer remains on AGP 8.2.0 / Gradle 8.2. Use JDK 17
and a configured Android SDK for the commands below.

| Experiment | Repository-root entry point | Verified outcome on 2026-11-11 |
|---|---|---|
| [1. Native SDK producer](../../experiments/agp9/producer.md) | `./experiments/agp9/run-producer.sh` | Expected failure: Commons duplicate Kotlin extension |
| [2. Standalone AAR consumer](../../experiments/agp9/aar-consumer.md) | Publish from `code/`, then build independently from `code/testapp/` | Publication/build/dependency resolution passed; fresh APK embeds AGP 9.0.1 |
| [3. Source-integrated testapp](../../experiments/agp9/source-testapp.md) | `./experiments/agp9/run-source-testapp.sh` | Expected failure: SDK Commons configuration before testapp compilation |

The consumer's `lintDebug` failed with 2 `MissingConstraints` errors in
unchanged `activity_second_level.xml:22` and `activity_third_level.xml:22`,
plus 85 warnings. No baseline or unrelated UI fix was added. The previously
installed consumer's AGP 9.0.1 metadata and running process were also verified;
the fresh publication checks did not reinstall it.

The historical Commons changes described later are context only: they remain
outside this publication and are not used by these reproduction entry points.
The separate RN hybrid experiment is documented for contrast, but its app
implementation is not part of these native commits.

## Current scope: an AGP 9 app consuming the SDK

The goal was corrected during the investigation: test an app consuming Messaging
under AGP 9, not migrate the Messaging SDK producer build to AGP 9.

The existing testapp sources and screens are retained. The testapp folder now
has an independent Gradle entry point; no new application source tree was
created.

| Build | Open this folder | Toolchain | Messaging dependency |
|---|---|---|---|
| SDK producer | `code/` | Gradle 8.2, original AGP 8.2.0 through Commons `gp-3.4.1` | Builds the existing SDK source |
| Existing testapp as consumer | `code/testapp/` | Gradle 9.1.0, AGP 9.0.1, JDK 17 | Locally published Messaging AAR |

### Open the correct project in Android Studio

Open this directory as its own project:

```text
aepsdk-messaging-android/code/testapp
```

Opening `code/` instead loads the original producer build, not the AGP 9
consumer. A single shared Gradle build is not used to mix producer and consumer
AGP versions.

The standalone `settings.gradle.kts` selects `build-agp9.gradle.kts` and does not
include `:messaging`, `:messagingtestutils`, or a composite SDK source build.
The existing `testapp/build.gradle.kts` remains unchanged for use from the
original producer build.

The app's Google Services plugin is `4.4.4`. It uses AGP 9 built-in Kotlin and
Compose compiler plugin `2.2.10`, without applying `org.jetbrains.kotlin.android`
or using the legacy Compose compiler-extension option. App ID, SDK levels,
existing UI source, Firebase configuration, and sample SDK dependency versions
are retained. SDK dependency versions are read from `code/gradle.properties`;
producer build flags are not imported.

### Local SDK artifact

The producer published:

```text
com.adobe.marketing.mobile:messaging:3.13.0-agp9-consumer-local-20261110-SNAPSHOT
```

The consumer reads this version from `code/testapp/gradle.properties` and
resolves it through Maven local. The SDK publication process automatically adds
`-SNAPSHOT` for this non-release publication; the app must use the full version.

The SDK producer's original wrapper and published Commons dependency were
restored. The experimental local Commons versions below remain available but
are not used by either current entry point. Screenshot dependency upgrades
made solely for the producer AGP 9 experiment were also reverted; user changes
were preserved.

To republish SDK source using its original toolchain:

```bash
cd code
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :messaging:bundlePhoneReleaseAar \
  :messaging:publishReleasePublicationToMavenLocal \
  -PmoduleVersion=3.13.0-agp9-consumer-local-20261110 \
  --console=plain --no-daemon
```

The SDK bundle and local publication passed with Gradle 8.2. Both wrapper
versions were checked using `--version`, which does not configure the app.

### Consumer verification result

After the user built and ran the standalone consumer, read-only verification
confirmed:

| Check | Result |
|---|---|
| Installed APK metadata on `emulator-5580` | `androidGradlePluginVersion=9.0.1` |
| Consumer dependency-report toolchain | Gradle 9.1.0 |
| `debugRuntimeClasspath` Messaging dependency | `com.adobe.marketing.mobile:messaging:3.13.0-agp9-consumer-local-20261110-SNAPSHOT` |
| Requested dependency AGP attribute | `9.0.1` |
| App process | Running when checked, PID `29951` |

The dependency report was obtained without rebuilding or reinstalling:

```bash
cd code/testapp
./gradlew dependencyInsight \
  --configuration debugRuntimeClasspath \
  --dependency com.adobe.marketing.mobile:messaging \
  --console=plain --no-daemon
```

The command passed. The installed APK was read through ADB and its embedded
`META-INF/com/android/build/gradle/app-metadata.properties` was inspected.
The temporary APK copy was removed afterward.

An older `build/outputs/apk/debug/testapp-debug.apk` still reported AGP 8.2.0.
Its SHA-256 differed from the installed APK, so it was not evidence of the
toolchain used for the running AGP 9 app. The installed APK's SHA-256 was:

```text
5f00b3218f5b24e1deefc850225d65d5d594dffbd6f5c378efa76cccd05318b1
```

That stale-output observation predates the fresh publication build, which
produced `messaging-testapp-agp9-consumer-debug.apk` with AGP 9.0.1 metadata.

This supports compatibility for the tested AGP 9.0.1 debug consumer build and
basic launch, without changing Messaging SDK source or upgrading its producer
toolchain. It is not blanket certification for all AGP 9.x versions, release/R8
builds, every SDK API, or all runtime messaging flows.

### Interpreting further results

Use the standalone consumer to investigate actual AAR consumption: dependency
metadata, manifest/resource merging, Kotlin/Compose compatibility, consumer
rules, SDK API calls, and runtime behavior. An app-plugin failure must still be
distinguished from an SDK artifact failure.

The Commons, Roborazzi, publishing, and Dokka failures recorded below arose from
moving the producer build to AGP 9. They do not prove that the published SDK
AAR is incompatible with an AGP 9 app.

## Temporary AGP 9 experiment: original source-integrated testapp

The user approved a temporary experiment using the original testapp's
`implementation(project(":messaging"))` dependency, rather than the standalone
AAR consumer. This test was run on 2026-11-11.

The original `code/settings.gradle.kts` includes `:testapp`, `:messaging`, and
`:messagingtestutils`. Moving this shared source build to AGP 9 affects SDK
modules as well as the app; it is not an isolated app-only upgrade.

No repository build scripts or wrapper versions were changed for this run.
The existing standalone wrapper supplied Gradle 9.1.0, but `-p .` selected the
original `code/` build. A session-only init script temporarily added/forced
AGP 9.0.1 on buildscript classpaths and printed diagnostic state.
It did not replace Commons, skip SDK modules, or disable AGP defaults.

The original run used a session-only init script. The published equivalent,
with JDK 17 and the Android SDK configured, is invoked from the repository root:

```bash
./experiments/agp9/run-source-testapp.sh
```

Result: **failed during Messaging SDK configuration, before testapp
compilation or execution**. Diagnostics confirmed:

```text
Gradle=9.1.0
Commons=com.github.adobe:aepsdk-commons:gp-3.4.1
:messaging: AGP=9.0.1, plugin=com.android.library
:messaging: kotlinExtension=true, externalKotlinPlugin=false
:messaging: android.builtInKotlin=<unset>
:messaging: android.newDsl=<unset>
```

The failure was at `code/messaging/build.gradle.kts:14`, applying
`aep-library`:

```text
Failed to apply plugin 'kotlin-android'.
Cannot add extension with name 'kotlin',
as there is an extension already registered with that name.
```

The stack identifies
`com.adobe.marketing.mobile.gradle.AEPLibraryPlugin.apply(AEPLibraryPlugin.kt:60)`
inside the original published Commons plugin. AGP 9 creates Kotlin support
first; Commons then tries to apply the external Kotlin plugin to that same
Messaging source module.

This confirms the distinction experimentally:

| AGP 9.0.1 consumer setup | Observed result | External Kotlin application causing failure |
|---|---|---|
| Standalone native testapp consumes prebuilt Messaging AAR | Installed app and dependency resolution verified; basic launch passed | SDK Commons is not executed by this consumer |
| Original native testapp builds Messaging source | Build fails configuring `:messaging` | SDK Commons `aep-library` plugin |
| Hybrid RN app builds published Messaging wrapper source | Build fails configuring RN Messaging wrapper | Wrapper `android/build.gradle:22` |

The source-integrated test is blocked before a new APK is produced. Further
app/Compose, SDK DSL, and tooling compatibility is therefore unverified.
No Kotlin/Commons fixes were attempted during this experiment.

Git checks after the run confirmed the original root, settings, and testapp
build scripts still match their prior state. The producer wrapper still
selects Gradle 8.2; its pre-existing missing-final-newline difference was
preserved. The working standalone AAR-consumer configuration was not changed.

The original init script and complete log remain in the session artifact directory as
`native-messaging-agp9-source-experiment.gradle` and
`native-messaging-agp9-source-experiment.log`. Gradle source observations are
UNINDEXED because those files were excluded from Scout.

## Why native consumption passes but React Native consumption fails

### Explanation for a React Native engineer

An npm package with native functionality is more than JavaScript. For
`@adobe/react-native-aepmessaging`, the integration has three layers:

```text
Your JS code imports Messaging
    -> React Native wrapper: JS bridge + Android wrapper source/build script
    -> Native Adobe Messaging SDK AAR
```

React Native autolinking adds the wrapper's Android folder from `node_modules`
as a Gradle subproject. Building your app executes that folder's
`android/build.gradle` and compiles its native wrapper code with your app's
toolchain. Successfully installing or importing the npm package does not
guarantee that this Android build step will succeed.

The native-only test bypasses this wrapper:

```text
AGP 9.0.1 native app
    -> Prebuilt Messaging AAR, published separately using AGP 8.2.0
```

An AAR is an Android library archive containing compiled classes and resources.
The consuming app still processes those classes, resources, manifests, and
dependencies, but does not execute the SDK producer's Gradle build script.
This is the difference between consuming a built library and compiling an
additional source project inside your app build.

### Android build tools: the terms are different

| Term | Responsibility in this investigation |
|---|---|
| Gradle | Runs build scripts, resolves dependencies, and executes tasks; version 9.1.0 in both AGP 9 consumers |
| Android Gradle Plugin (AGP) | Adds Android configuration/tasks to Gradle, including compiling, merging resources/manifests, and producing APKs; version 9.0.1 in the consumers |
| Kotlin Android support | Configures Kotlin compilation for Android modules; external plugin with the older producer setup, built-in support with AGP 9 defaults |
| React Native Gradle plugin | Adds RN integration such as autolinking, codegen, and JS/Hermes build tasks on top of the Android build |
| Metro | Transforms/bundles JavaScript; it does not apply the wrapper's Android Gradle plugins |
| AAR | Prebuilt Android library archive consumed by an app; it is not a separate AGP installation |

AGP and Gradle version numbers are independent. Upgrading the Gradle wrapper
alone does not select AGP 9. In the hybrid app, the wrapper selects Gradle 9.1.0
and `android/build.gradle` selects
`com.android.tools.build:gradle:9.0.1`.

### Example 1: native Android app consuming a prebuilt library

The current native test uses two independent Gradle builds:

```text
Build A: SDK producer, code/
  Messaging source
    + aep-library / SDK Commons
    + external Kotlin plugin
    -- AGP 8.2.0 / Gradle 8.2 -->
  Messaging AAR + Maven metadata, published to Maven local

Build B: consumer app, code/testapp/
  App Kotlin/Java source
    + Messaging AAR resolved from Maven local
    -- AGP 9.0.1 / Gradle 9.1.0 -->
  App APK
```

Illustrative consumer dependency using the actual tested coordinate:

```kotlin
dependencies {
    implementation(
        "com.adobe.marketing.mobile:messaging:" +
            "3.13.0-agp9-consumer-local-20261110-SNAPSHOT"
    )
}
```

The actual standalone consumer reads the version from its Gradle properties.
It does not include the Messaging SDK source project.

The consuming build resolves the library and its dependencies, compiles the
app, merges library/app resources and manifests, and processes compiled
classes into Android DEX code. A minified release build can additionally
shrink/optimize library classes. None of these steps executes the SDK
producer's `aep-library` plugin or rebuilds its Kotlin source.

That is why SDK Commons can apply external Kotlin successfully under AGP 8.2,
while the consuming app independently uses AGP 9 built-in Kotlin without
creating two Kotlin extensions in one module.

### Example 2: React Native app consuming the Messaging npm package

Installing the npm package places JavaScript and Android wrapper source in
`node_modules`. It does not precompile that wrapper for the consuming app.
The hybrid app's settings run:

```groovy
extensions.configure(com.facebook.react.ReactSettingsExtension) {
    ex -> ex.autolinkLibrariesFromCommand()
}
```

Its app's `react` block then uses:

```groovy
autolinkLibrariesWithApp()
```

Together, these discover the Android wrapper projects and wire their
dependencies into the Android app. The relevant build shape is:

```text
One consumer Gradle build, apps/AGP9HybridApp/android/
  :app
    -> :adobe_react-native-aepmessaging
         -> published native Messaging AAR

  :app uses com.android.application
  RN wrapper uses com.android.library
  Both execute with the resolved Android plugin in this build: AGP 9.0.1
```

The wrapper is a Gradle source project, not the native Messaging SDK producer.
Its build script executes during configuration. If configuration succeeds,
its Java/Kotlin source is compiled for the app and its classes are packaged
alongside the underlying native SDK classes.

The wrapper's own `buildscript` declares AGP 8.1.1, but that declaration does
not create an isolated AGP 8 build inside the AGP 9 app. In this reproduction,
the effective Android plugin is AGP 9.0.1. Dependency declarations in a
library build script are not a guarantee of independently selected plugin
versions when it is included as a subproject.

### Where React Native's JavaScript fits

Once the relevant Android configuration/build steps can proceed, RN also has
JavaScript and native integration work: Metro prepares JS, the RN Gradle
plugin coordinates bundle/Hermes tasks for bundled variants, and codegen
tasks can run for packages that declare codegen configuration. Ordinary
debug builds can instead load JavaScript from Metro at runtime.

Conceptually, a Messaging API call follows:

```text
JS: Messaging.extensionVersion()
  -> RN native-module adapter
  -> compiled native Messaging SDK
  -> result returned to JS
```

The actual failure happens earlier:

```text
Gradle configures the RN wrapper
  -> AGP 9 Android plugin creates Kotlin support
  -> wrapper applies external kotlin-android
  -> duplicate "kotlin" extension
  -> no successful wrapper compilation or Messaging runtime call
```

Changing the JS import, rebuilding the native AAR, or changing SDK runtime
configuration cannot correct this specific wrapper-plugin collision.

### The distinction is binary versus source, not native versus React Native

A native Android app that includes a library using `project(":messaging")`
and evaluates its producer build script under AGP 9 can encounter the same
Commons/external-Kotlin conflict. React Native does not uniquely cause
source modules to share an Android build toolchain; autolinking is simply how
this RN wrapper source module enters the app's build.

| Integration | Executes the relevant library build script in the consumer build? | Implication |
|---|---|---|
| Native app consumes Messaging Maven AAR | No SDK producer script | Producer AGP need not match consumer AGP |
| Native app builds Messaging source with AGP 9 | Yes, including its Commons plugin | Producer build tooling must support AGP 9 |
| RN app autolinks Messaging wrapper source | Yes, wrapper script; no SDK producer script for its Maven AAR | Wrapper build tooling must support the app's effective AGP |

Different producer/consumer AGP versions are possible, not automatically
safe for every library. Manifest/resource compatibility, bytecode/Kotlin
metadata, dependencies, consumer rules, and runtime behavior still require
appropriate verification. This investigation confirms the tested native
debug consumption/basic launch and the RN wrapper configuration failure.

### Confirmed React Native hybrid failure

The separate RN repository's hybrid app is `apps/AGP9HybridApp`, using
React Native 0.85.0, AGP 9.0.1, Gradle 9.1.0, and published
`@adobe/react-native-aepmessaging@7.4.0`. The app declares Messaging at
`apps/AGP9HybridApp/package.json:22` and imports it at
`apps/AGP9HybridApp/App.tsx:10`. Android autolinking includes the native wrapper.

The installed npm package's `android/build.gradle:21-22` applies:

```groovy
apply plugin: "com.android.library"
apply plugin: "kotlin-android"
```

AGP 9's Android plugin already provides built-in Kotlin. The wrapper then
applies the separate Kotlin Android plugin, which tries to create another
extension named `kotlin`. Configuration fails at line 22:

```text
Failed to apply plugin 'kotlin-android'.
Cannot add extension with name 'kotlin',
as there is an extension already registered with that name.
```

A read-only lifecycle diagnostic confirmed the state immediately after
`com.android.library`, before the wrapper applies `kotlin-android`:

```text
kotlinExtension=true
externalKotlinPlugin=false
builtInKotlinProperty=<unset>
```

This is a build-tooling conflict, not a JavaScript import error, a
TurboModule/runtime failure, or a failure inside the native Messaging API.
The wrapper's Android configuration fails before its compilation and before
the app can run Messaging.

The same build also reports a legacy `LibraryExtension` lookup from RN's
Gradle plugin at `AgpConfiguratorUtils.kt:121`. The interrupted wrapper script
has not configured its namespace; RN's namespace fallback tries the old
`com.android.build.gradle.LibraryExtension` type, which does not match AGP 9's
new DSL extension. This secondary error does not prove that fixing the first
error is sufficient for a complete working build.

### Reproduction and conclusion

From the RN repository:

```bash
cd apps/AGP9HybridApp/android
./gradlew :app:assembleDebug --stacktrace --console=plain --no-daemon
```

Result: failed during configuration of
`:adobe_react-native-aepmessaging`, at the published wrapper's
`android/build.gradle:22`. No package fix or AGP compatibility opt-out was
applied. Gradle source files were excluded from Scout and read directly;
those file-level observations are UNINDEXED.

The fresh build and lifecycle logs are saved in the investigation session as
`hybrid-messaging-agp9-recheck.log` and
`hybrid-messaging-agp9-lifecycle-recheck.log`.

The native-only AAR test and RN wrapper test therefore have different results:
the tested native AAR can be consumed by an AGP 9.0.1 app, while the published
RN wrapper's build script blocks this hybrid setup. Compatibility of the
underlying native SDK does not automatically establish compatibility of its
React Native wrapper. Removing or appropriately gating the redundant external
Kotlin plugin is the first correction to investigate, not a verified complete
fix.

## Historical producer investigation: document intent

- Audience: SDK engineers investigating Android build-tool compatibility.
- Purpose: reproduce the observed failures, explain their causes, and track the
  local test fixes without presenting an incomplete migration as supported.
- Investigation date: 2026-11-10.
- Scope: the native Messaging producer build and its shared Commons Gradle plugin.
  Published Messaging AAR consumption is a separate compatibility question.

## Historical producer environment

| Component | Configuration |
|---|---|
| Messaging branch | `repro/agp9-kotlin-conflict` |
| Commons branch | `repro/agp9-local-commons` |
| Commons starting commit | `7c965d41a0606227b79d498933fc4ba2a1f0c6af` |
| Messaging Gradle wrapper | `9.1.0` |
| Messaging AGP override | `9.0.1` |
| JDK used to build the local plugin | 17 |
| Latest local Commons version | `3.4.3-agp9-local-20261110-dsl1` |
| Roborazzi plugin and screenshot libraries | `1.56.0` |
| Robolectric | `4.14.1` |
| AGP compatibility opt-outs | None added |
| Later reported blockers | Publishing sources task and Dokka source-set failures; no successful AGP 9 Messaging producer build established |

`origin/main` was fetched for both repositories. The Commons test branch was
created from its fetched `origin/main`; Messaging's reproduction branch was not
merged or rebased. Existing user changes were preserved.

Changing the Gradle wrapper does not change AGP. The historical Messaging
experiment selected AGP 9 through an explicit buildscript dependency in
`code/build.gradle.kts`; that change was subsequently reverted. Commons' own
plugin build still uses its existing Gradle 8.2 toolchain and AGP 8.2 dependency;
publishing that plugin successfully is not proof of consumer-side AGP 9 support.

## Observed producer errors and root causes

### 1. Duplicate Kotlin extension

Android Studio sync with published Commons `gp-3.4.1` reported:

```text
Failed to apply plugin 'kotlin-android'.
Cannot add extension with name 'kotlin',
as there is an extension already registered with that name.
```

The supplied stack trace identified `AEPLibraryPlugin.kt:60` in that published
version. The chain was:

```text
messaging/build.gradle.kts applies aep-library
  -> Commons applies com.android.library
  -> AGP 9 enables built-in Kotlin
  -> Commons applies kotlin-android
  -> the external plugin attempts to register kotlin again
```

This is a plugin-configuration failure, not a Messaging API or BOM failure.
Upgrading the Kotlin version alone does not remove the duplicate application.

### 2. Legacy LibraryExtension lookup

After switching to the first local Commons artifact, sync/clean reported:

```text
Failed to apply plugin 'aep-library'.
Extension of type 'LibraryExtension' does not exist.
```

Registered extensions included `LibraryExtensionImpl`,
`LibraryAndroidComponentsExtension`, and `KotlinAndroidProjectExtension`.

The first local patch removed Commons' external Kotlin plugin application.
The next failure exposed a separate issue: Commons requested the old class:

```kotlin
import com.android.build.gradle.LibraryExtension
```

AGP 9's new DSL implementation does not implement that legacy class. Its
presence under the name `android` does not make an old-type `getByType` lookup
valid. The corresponding public interface is:

```kotlin
import com.android.build.api.dsl.LibraryExtension
```

The short error does not identify the exact failing helper. Source inspection
found legacy lookups in library configuration, namespace/Compose configuration,
documentation, and coverage helpers, plus a `BaseExtension` SDK-path lookup.

### 3. Roborazzi TestedExtension lookup

The next user-provided Android Studio stack trace reports:

```text
Failed to apply plugin 'io.github.takahirom.roborazzi'.
Extension of type 'TestedExtension' does not exist.
```

Its cause points to `RoborazziPlugin.kt:496`, through the plugin callback at
line 491. The registered extensions include the new `LibraryExtensionImpl` and
`LibraryAndroidComponentsExtension`.

This is a different failing plugin, not a repeat of Commons'
`LibraryExtension` lookup. The build progressed to Roborazzi plugin application,
where another legacy Android extension lookup failed. At that point Messaging's
buildscript declared `roborazzi-gradle-plugin:1.31.0`, while the screenshot test
libraries were on `1.32.2`.

The user selected upgrading rather than disabling screenshot tooling.
Roborazzi's official `1.56.0` release explicitly adds AGP 9 compatibility and
identifies `TestedExtension` and `Variant.unitTest` among the deprecated APIs
replaced. The upgrade below was temporarily applied; the next user stack
trace exposed publishing/Dokka failures. No successful complete producer build
or screenshot-test result was established, and the upgrade was later reverted.

### 4. Publishing task and Dokka source-set assumptions

The following user-provided producer stack trace reported two failures:

```text
Task with name 'phoneReleaseSourcesJar' not found in project ':messaging'.
Could not create task ':messaging:dokkaJavadoc'.
GradleDokkaSourceSetBuilder with name 'main' not found.
```

The publishing failure goes through `ProjectExtensions.kt:73` and
`PublishPlugin.kt:51`: the publication eagerly retrieves a sources task that
was not registered in that producer configuration.

The Dokka failure goes through `AEPLibraryPlugin.kt:230`: configuration calls
`dokkaSourceSets.named("main")`, assuming a source set exists when it does not.
The configuration also assumes a `phone` Dokka source set.

These are producer publishing/documentation assumptions. Their complete AGP 9
migration was not pursued after the consumer-only goal was clarified. No fix
for these two AGP 9 producer failures is claimed. The restored original
toolchain subsequently built and published the SDK successfully.

## Historical producer fixes and local Commons override

These are local investigation changes, not a remote release or a completed
production migration.

| Local version | Changes | Evidence |
|---|---|---|
| `3.4.3-agp9-local-20261110` | Removed Commons' `kotlin-android` application | Plugin published locally; user then observed the legacy DSL error |
| `3.4.3-agp9-local-20261110-dsl1` | Public DSL and variant API changes described below | Plugin compilation/check and local publication passed; the user's next sync failed in Roborazzi |

The first patch deliberately removes the external Kotlin application for an
AGP 9-only test. It is not a backwards-compatible AGP 8 release. A production
plugin supporting both toolchains needs version/feature-aware Kotlin setup.

The DSL patch:

1. Uses public `LibraryExtension` for Android library DSL configuration.
2. Uses `flavorDimensions` instead of the legacy `flavorDimensionList`.
3. Registers Javadoc variant callbacks early with
   `LibraryAndroidComponentsExtension.onVariants`, not `libraryVariants`.
   Javadoc still depends on the corresponding Java compilation task and keeps
   the existing task names and archive wiring.
4. Supplies coverage source directories through the `phoneDebug` variant's
   public Java source provider. This includes that variant's Java sources,
   rather than separately querying legacy main/phone source-directory getters.
5. Obtains the SDK directory through `androidComponents.sdkComponents` instead
   of `BaseExtension`.
6. Uses `proguard-android-optimize.txt`; AGP 9 rejects the old
   `proguard-android.txt` default file. The plugin's existing
   `isMinifyEnabled = false` setting is unchanged.

Changed Commons sources, relative to `android/aepsdk-gradle-plugin/`:

- `aepsdk-gradle-plugin/build.gradle.kts`: unique publication version.
- `aepsdk-gradle-plugin/src/main/kotlin/com/adobe/marketing/mobile/gradle/AEPLibraryPlugin.kt`.
- `aepsdk-gradle-plugin/src/main/kotlin/com/adobe/marketing/mobile/gradle/JacocoPlugin.kt`.
- `aepsdk-gradle-plugin/src/main/kotlin/com/adobe/marketing/mobile/gradle/ProjectExtensions.kt`.

During that historical experiment, Messaging's `code/build.gradle.kts` selected:

```kotlin
classpath("com.github.adobe.aepsdk-commons:aepsdk-gradle-plugin:3.4.3-agp9-local-20261110-dsl1")
classpath("com.android.tools.build:gradle:9.0.1")
```

A prioritized `mavenLocal` repository was filtered to the Commons plugin module.
The existing repositories remained available. These module publication coordinates
differ from JitPack's old aggregate coordinates
`com.github.adobe:aepsdk-commons:gp-3.4.1`.

### Roborazzi upgrade

| Dependency | Previous declaration | New declaration |
|---|---|---|
| `roborazzi-gradle-plugin` | `1.31.0` | `1.56.0` |
| `roborazzi` | `1.32.2` | `1.56.0` |
| `roborazzi-compose` | `1.32.2` | `1.56.0` |
| `robolectric` | `4.14` | `4.14.1` |

`1.56.0` is the release explicitly documenting the required AGP 9 fix; a later
release was not chosen just because it is newer. All three Roborazzi artifacts
were confirmed published on Maven Central. Robolectric's patch update matches
the upstream `1.56.0` test baseline.

During the upgrade, the `io.github.takahirom.roborazzi` plugin remained applied,
and no screenshot tests or baselines were removed. The previous plugin/library version mismatch
was not the cause of the legacy extension lookup, but the upgrade aligns those
versions instead of preserving the mismatch.

Upstream also documents a Compose Preview `LaunchedEffect` behavior change in
this release that can affect preview screenshots. Review any resulting image
differences; do not automatically regenerate baselines to conceal regressions.

## Historical producer reproduction (not the current consumer setup)

Open the native Messaging repository's `code/` directory in Android Studio.
Use Gradle JDK 17. Leave AGP 9 defaults enabled: do not set
`android.builtInKotlin=false` or `android.newDsl=false`.

To reproduce each phase, change only the Commons classpath dependency while
retaining AGP 9.0.1 and Gradle 9.1.0:

- Published `com.github.adobe:aepsdk-commons:gp-3.4.1`: observed Kotlin collision.
- Local version without `-dsl1`: observed legacy `LibraryExtension` failure.
- Latest `-dsl1` version: the user subsequently observed the Roborazzi
  `TestedExtension` failure with the old Roborazzi plugin. The temporary
  `1.56.0` upgrade was followed by publishing/Dokka failures. These changes
  were reverted in Messaging; a successful AGP 9 producer build was not
  established. Recreating later historical phases requires the separate
  local Commons patches/artifacts, not just this branch.

Do not remove `id("aep-library")` from Messaging as a workaround; it supplies
required build configuration. The decompiled IDE stub is not editable plugin
implementation source.

To rebuild and republish the latest local artifact, run from the Commons repo:

```bash
cd android/aepsdk-gradle-plugin
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :aepsdk-gradle-plugin:check \
  :aepsdk-gradle-plugin:publishPluginJitpackPublicationToMavenLocal \
  --console=plain --no-daemon
```

Use a new local version for subsequent patches and update Messaging's dependency
to match, rather than relying on cached artifacts under an unchanged version.

## Historical producer validation evidence and limits

The command above passed for both local versions. For the latest version:

- `compileKotlin`, `validatePlugins`, and `check` passed.
- `publishPluginJitpackPublicationToMavenLocal` passed.
- Published bytecode for the three migrated classes was checked: the legacy
  `LibraryExtension`/`BaseExtension` lookups, `libraryVariants` access, and
  Commons' `kotlin-android` application are absent. Both AEP plugin descriptors
  are present in the published JAR.
- The plugin project has no test sources; Gradle reported test tasks as
  `NO-SOURCE`. This is not an integration-test result.
- Messaging sync, clean/build, Javadoc generation, and coverage execution were
  not run by the assistant after the DSL patch.
- At that stage, the user's subsequent stack trace identified Roborazzi plugin
  application as the blocker; it did not prove that later Commons callbacks,
  documentation tasks, or coverage tasks are fully AGP 9-compatible.
- The Roborazzi upgrade was based on official release notes and published Maven
  POMs. A subsequent user stack trace exposed publishing/Dokka failures.
  No successful complete AGP 9 build or screenshot tests were verified.

Producer compilation against AGP 8.2 public APIs is useful validation, but cannot
establish that every API invocation or third-party plugin works at runtime with
AGP 9.

## Remaining producer migration checks (outside the current consumer test)

These are pending migration checks, not all reproduced failures:

- Verify that a future Roborazzi `1.56.0` upgrade resolves the observed
  `TestedExtension` failure; then run the screenshot tests.
- `code/testapp/build.gradle.kts:16` and
  `code/messagingtestutils/build.gradle.kts:16` still explicitly apply
  `org.jetbrains.kotlin.android`. Those applications can cause their own
  built-in Kotlin conflicts after library configuration progresses.
- Commons and the test app still use legacy Compose compiler configuration.
  Align Compose setup with the Kotlin version selected under AGP 9.
- Review Commons' Kotlin language/API settings against the selected compiler.
- Validate Dokka, Spotless, publishing, Javadoc, and coverage behavior
  under the target toolchain.
- Resolve any further public-API differences using the first new stack trace;
  do not disable AGP 9 defaults to conceal migration failures.

The published Messaging AAR does not execute this producer repository's Gradle
scripts in consumer applications. These failures do not establish that the AAR
itself is incompatible with AGP 9.

## References

- [AGP 9 release notes and compatibility](https://developer.android.com/build/releases/agp-9-0-0-release-notes).
- [Migrate to built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin).
- [Roborazzi 1.56.0 release notes: AGP 9 compatibility](https://github.com/takahirom/roborazzi/releases/tag/1.56.0).
- [Upstream AGP 9 compatibility change](https://github.com/takahirom/roborazzi/pull/782).
- [Commons source at the fetched main commit](https://github.com/adobe/aepsdk-commons/tree/7c965d41a0606227b79d498933fc4ba2a1f0c6af/android/aepsdk-gradle-plugin).

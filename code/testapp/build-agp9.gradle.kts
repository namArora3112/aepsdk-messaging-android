import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application") version "9.0.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
    id("com.google.gms.google-services") version "4.4.4"
}

val sdkProperties = Properties().apply {
    file("../gradle.properties").inputStream().use { load(it) }
}
fun sdkVersion(name: String): String =
    requireNotNull(sdkProperties.getProperty(name)) { "Missing SDK version property: $name" }

val messagingLocalVersion: String by project

android {
    namespace = "com.adobe.marketing.mobile.messagingsample"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.adobe.marketing.mobile.messagingsample"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

dependencies {
    implementation("com.adobe.marketing.mobile:messaging:$messagingLocalVersion")
    implementation("com.adobe.marketing.mobile:core:${sdkVersion("mavenCoreVersion")}")
    implementation("com.adobe.marketing.mobile:lifecycle:${sdkVersion("mavenLifecycleVersion")}")
    implementation("com.adobe.marketing.mobile:assurance:${sdkVersion("mavenAssuranceVersion")}")
    implementation("com.adobe.marketing.mobile:edge:${sdkVersion("mavenEdgeVersion")}")
    implementation("com.adobe.marketing.mobile:edgeidentity:${sdkVersion("mavenEdgeIdentityVersion")}")
    implementation("com.adobe.marketing.mobile:edgeconsent:${sdkVersion("mavenEdgeConsentVersion")}")
    implementation("com.adobe.marketing.mobile:notificationbuilder:${sdkVersion("mavenNotificationBuilderVersion")}")

    implementation("com.google.firebase:firebase-messaging:23.4.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.11.0")

    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.activity:activity-compose")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

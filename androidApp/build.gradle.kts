/**
 * Build configuration for the `androidApp` module.
 *
 * This is a pure Android **application** module (AGP 9.0). It contains only the
 * Android entry point (`MainActivity`) and app-level resources (launcher icons,
 * manifest). All shared frontend logic lives in the `:app` KMP library module,
 * which this module depends on.
 *
 * AGP 9.0 bundles Kotlin support, so the `org.jetbrains.kotlin.android` plugin
 * must NOT be applied here.
 */
repositories {
    mavenCentral()
    google()
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
// Capture the central version at the project scope (the `android {}` block changes
// the receiver, so `extensions` there would not resolve project extra properties).
val centralVersion = extensions.extraProperties["centralVersion"]?.toString() ?: "unspecified"
android {
    namespace = "eu.torvian.chatbot"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "eu.torvian.chatbot"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = centralVersion
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        val javaAndroidVal = libs.versions.javaAndroid.get()
        sourceCompatibility = JavaVersion.toVersion(javaAndroidVal)
        targetCompatibility = JavaVersion.toVersion(javaAndroidVal)
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
}
dependencies {
    // Shared frontend logic (KMP library module).
    implementation(project(":app"))

    // AndroidX Activity for Compose Multiplatform.
    implementation(libs.androidx.activity.compose)

    // Koin Android integration (provides androidContext() and Koin Android application support).
    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    // Compose UI tooling (debug only).
    debugImplementation(libs.compose.uiTooling)
}

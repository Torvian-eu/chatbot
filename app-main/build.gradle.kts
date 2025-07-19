import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

//import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
//import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

/**
 * Build configuration for the `app-main` module.
 *
 * Implements platform-specific entry points for the client application.
 * - `main` function for JVM (Compose for Desktop)
 */

description = "Main application module for the chatbot client application"

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}

repositories {
    mavenCentral()
    google()
}

kotlin {
    jvm("desktop"){
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
        }
    }

//    @OptIn(ExperimentalWasmDsl::class)
//    wasmJs {
//        moduleName = "composeApp"
//        browser {
//            val rootDirPath = project.rootDir.path
//            val projectDirPath = project.projectDir.path
//            commonWebpackConfig {
//                outputFileName = "composeApp.js"
//                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
//                    static = (static ?: mutableListOf()).apply {
//                        // Serve sources to debug inside browser
//                        add(rootDirPath)
//                        add(projectDirPath)
//                    }
//                }
//            }
//        }
//        binaries.executable()
//    }

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation(project(":app-shared"))

            // Compose dependencies
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)

            // AndroidX Lifecycle
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)

            // Ktor Client
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.resources)

            // Arrow dependencies for Either
            implementation(libs.arrow.core)
            implementation(libs.arrow.fx.coroutines)

            // KotlinX dependencies
            implementation(libs.coroutines.core)
            implementation(libs.kotlinx.datetime)

            // Koin dependency injection
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
        }

        desktopMain.dependencies {
            implementation(project(":server"))
            // Compose for Desktop
            implementation(compose.desktop.currentOs)
            // KotlinX Coroutines Swing for JVM Main Dispatcher
            implementation(libs.kotlinx.coroutines.swing)
            // Logging (JVM-specific)
            implementation(libs.log4j.api)
            runtimeOnly(libs.log4j.core)
            runtimeOnly(libs.log4j.slf4j2)
        }
    }
}

// Task to create native installers (E7.S1)
compose.desktop {
    application {
        mainClass = "eu.torvian.chatbot.app.mainAppMainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Chatbot"
            packageVersion = "1.0.0"

            // Add launcher configuration if needed
            // linux { ... }
            // windows { ... }
            // macos { ... }
        }
    }
}

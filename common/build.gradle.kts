import org.jetbrains.kotlin.gradle.dsl.JvmTarget

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

/**
 * Build configuration for the `common` module.
 *
 * - Contains shared logic and data classes.
 */

description = "Shared logic and data classes for the chatbot application"

plugins {
//    id("common-module-convention")  // Apply custom convention plugin
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

repositories {
    mavenCentral()
    google()
}

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
        }

        testRuns["test"].executionTask.configure {
            // Use JUnit 5 Platform for testing
            useJUnitPlatform()

            // Enable dynamic agent loading for MockK and disable class data sharing (JVM args)
            jvmArgs("-XX:+EnableDynamicAgentLoading", "-Xshare:off")

            // Enable parallel test execution (JVM system properties)
            systemProperty("junit.jupiter.execution.parallel.enabled", "true")
            // Run tests in parallel within a class
            systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
            // Run tests from the same class in the same thread to avoid concurrency issues
            systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "same_thread")
            // Dynamic parallelism strategy
            systemProperty("junit.jupiter.execution.parallel.config.strategy", "dynamic")
            // Dynamic parallelism factor (50% of available processors)
            systemProperty("junit.jupiter.execution.parallel.config.dynamic.factor", "0.5")
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName = "composeCommon"
        browser {
            val rootDirPath = project.rootDir.path
            val projectDirPath = project.projectDir.path
            commonWebpackConfig {
                outputFileName = "composeCommon.js"
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    static = (static ?: mutableListOf()).apply {
                        // Serve sources to debug inside browser
                        add(rootDirPath)
                        add(projectDirPath)
                    }
                }
            }
        }
        binaries.executable()
    }

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    // Apply the default hierarchy template again. Needed for custom source sets to work correctly.
    applyDefaultHierarchyTemplate()

    sourceSets {
        // Create a new source set for shared Android/Desktop code
        val desktopAndroidMain by creating {
            dependsOn(commonMain.get())
        }
        val desktopMain by getting {
            dependsOn(desktopAndroidMain)
        }
        val desktopTest by getting

        androidMain {
            dependsOn(desktopAndroidMain)
        }

        commonMain.dependencies {
            implementation(libs.koin.core)           // Koin dependency injection
            implementation(libs.ktor.resources)      // Ktor resources for type-safe routing
            implementation(libs.serialization.json)  // KotlinX Serialization JSON
            implementation(libs.kotlinx.datetime)    // KotlinX DateTime
            implementation(libs.arrow.core)           // Arrow Core for functional programming
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        desktopMain.dependencies {
            // Logging (JVM-specific)
            implementation(libs.log4j.api)
            runtimeOnly(libs.log4j.core)
            runtimeOnly(libs.log4j.slf4j2)
        }

        desktopTest.dependencies {
            // Mocking library (JVM-specific)
            implementation(libs.mockk)
        }
    }

    android {
        namespace = "eu.torvian.chatbot"
        compileSdk = libs.versions.android.compileSdk.get().toInt()

        defaultConfig {
            minSdk = libs.versions.android.minSdk.get().toInt()
        }
        packaging {
            resources {
                excludes += "/META-INF/{AL2.0,LGPL2.1}"
            }
        }
        buildTypes {
            getByName("release") {
                isMinifyEnabled = false
            }
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
    }
}

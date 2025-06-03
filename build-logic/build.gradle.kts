description = "Build logic for the project"

plugins {
    `kotlin-dsl`  // Enables writing Gradle plugins with Kotlin DSL
    alias(libs.plugins.kotlin.jvm.buildlogic)
}

repositories {
    mavenCentral()
}

// Define the common module convention plugin, shared by all modules
gradlePlugin {
    plugins {
        create("commonModuleConvention") {
            id = "common-module-convention"
            implementationClass = "CommonModuleConventionPlugin"
        }
    }
}

dependencies {
    implementation(libs.kotlin.gradle.plugin.buildlogic)
}
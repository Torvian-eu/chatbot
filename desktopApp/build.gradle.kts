import org.jetbrains.compose.desktop.application.dsl.TargetFormat

repositories {
    mavenCentral()
    google()
}

/**
 * Build configuration for the `desktopApp` module.
 *
 * This is a pure JVM (Desktop) application module that contains only the desktop
 * entry point (`AppMain`). All shared frontend logic lives in the `:app` KMP
 * library module, which this module depends on. The Compose Desktop application
 * configuration (native distributions, JVM args) lives here.
 */

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}

// Get the central version from settings.gradle.kts
val centralVersion = extensions.extraProperties["centralVersion"]?.toString() ?: "unspecified"

dependencies {
    // Shared frontend logic (KMP library module).
    implementation(project(":app"))
    // Compose for Desktop runtime for the current OS.
    implementation(compose.desktop.currentOs)
    // KotlinX IO for kotlinx.io.files.Path used by the desktop entry point.
    implementation(libs.kotlinx.io.core)
    // Koin Compose integration (provides modules() and Koin application support).
    implementation(libs.koin.compose)
}

// Task to create distributable to a custom path
tasks.register<Sync>("createDistributableTo") {
    group = "compose desktop"
    description = "Copies the desktop distributable to a custom path using -PinstallPath=<path>."

    dependsOn("createDistributable")
    from(layout.buildDirectory.dir("compose/binaries/main/app/Chatbot"))
    into(provider {
        val installPath = findProperty("installPath")?.toString()?.trim()
        if (installPath.isNullOrEmpty()) {
            throw GradleException(
                "Missing required property 'installPath'. Usage: ./gradlew desktopApp:createDistributableTo -PinstallPath=/your/target/path"
            )
        }
        file(installPath)
    })
}

compose.desktop {
    application {
        mainClass = "eu.torvian.chatbot.app.main.AppMainKt"

        nativeDistributions {
            // Strip -SNAPSHOT suffix for native distribution package version
            val cleanPackageVersion = centralVersion.removeSuffix("-SNAPSHOT")

            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Exe)
            packageName = "Chatbot"
            packageVersion = cleanPackageVersion

            modules("java.sql") // Prevents exception "NoClassDefFoundError: java/sql/DriverManager"

            // Add launcher configuration if needed
            // linux { ... }
            // windows { ... }
            // macos { ... }
        }

        jvmArgs += listOf(
            "--enable-native-access=ALL-UNNAMED", // Required for Skiko native access to work properly
            "-Duser.language=en",
            "-Duser.country=US"
        )
        // Uncomment to test with Spanish locale
//        jvmArgs += "-Duser.language=es"
//        jvmArgs += "-Duser.country=ES" // Optional, for regional variants like es-ES
    }
}

// Configure the createDistributable task to also copy config files and launch scripts
afterEvaluate {
    tasks.named("createDistributable") {
        val windowsBatchFile = layout.projectDirectory.file("../app/dist-resources/Chatbot-with-logs.bat")
        val unixShellScript = layout.projectDirectory.file("../app/dist-resources/chatbot-with-logs.sh")
        val configDir = layout.projectDirectory.dir("../app/src/commonMain/composeResources/files/config")
        val distDirProvider = layout.buildDirectory.dir("compose/binaries/main/app/Chatbot")

        doLast {
            val distDir = distDirProvider.get().asFile

            // Copy default config files into the distributable so createDistributable output is complete.
            val targetConfigDir = File(distDir, "config")
            targetConfigDir.mkdirs()

            val defaultConfig = configDir.file("default_config.json").asFile
            val defaultSetup = configDir.file("default_setup.json").asFile

            if (defaultConfig.exists()) {
                defaultConfig.copyTo(File(targetConfigDir, "config.json"), overwrite = true)
                println("Copied config.json to distribution: ${distDir.absolutePath}")
            } else {
                println("WARNING: default_config.json not found at ${defaultConfig.absolutePath}")
            }

            if (defaultSetup.exists()) {
                defaultSetup.copyTo(File(targetConfigDir, "setup.json"), overwrite = true)
                println("Copied setup.json to distribution: ${distDir.absolutePath}")
            } else {
                println("WARNING: default_setup.json not found at ${defaultSetup.absolutePath}")
            }

            // Copy platform-specific launch scripts
            // This adds convenience scripts that start the application with a console/terminal window visible,
            // allowing users to see log output.
            val os = System.getProperty("os.name").lowercase()

            when {
                os.contains("win") -> {
                    // Windows: copy batch file
                    val batchFile = windowsBatchFile.asFile
                    if (batchFile.exists()) {
                        batchFile.copyTo(File(distDir, batchFile.name), overwrite = true)
                        println("Copied Windows batch file to distribution: ${distDir.absolutePath}")
                    } else {
                        println("WARNING: Windows batch file not found at ${batchFile.absolutePath}")
                    }
                }

                os.contains("mac") || os.contains("darwin") -> {
                    // macOS: copy shell script
                    val shellScript = unixShellScript.asFile
                    if (shellScript.exists()) {
                        val targetFile = File(distDir, shellScript.name)
                        shellScript.copyTo(targetFile, overwrite = true)
                        // Make executable on Unix-like systems
                        targetFile.setExecutable(true, false)
                        println("Copied macOS shell script to distribution: ${distDir.absolutePath}")
                    } else {
                        println("WARNING: Shell script not found at ${shellScript.absolutePath}")
                    }
                }

                os.contains("nux") || os.contains("nix") -> {
                    // Linux: copy shell script
                    val shellScript = unixShellScript.asFile
                    if (shellScript.exists()) {
                        val targetFile = File(distDir, shellScript.name)
                        shellScript.copyTo(targetFile, overwrite = true)
                        // Make executable on Unix-like systems
                        targetFile.setExecutable(true, false)
                        println("Copied Linux shell script to distribution: ${distDir.absolutePath}")
                    } else {
                        println("WARNING: Shell script not found at ${shellScript.absolutePath}")
                    }
                }

                else -> {
                    println("WARNING: Unknown OS '${os}', no launcher script copied")
                }
            }
        }
    }
}

tasks.withType<Test> {
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow" // Allow unsafe memory access for testing purposes (needed for MockK)
    )
}

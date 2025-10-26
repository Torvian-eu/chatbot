package eu.torvian.chatbot.app.main

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import eu.torvian.chatbot.app.compose.AppShell
import eu.torvian.chatbot.app.koin.appModule
import eu.torvian.chatbot.app.koin.wasmJsModule
import eu.torvian.chatbot.app.utils.misc.createKmpLogger
import eu.torvian.chatbot.common.security.EncryptionConfig
import kotlinx.browser.document
import org.koin.compose.KoinApplication

private val logger = createKmpLogger("WasmJsAppMain")
private const val APPLICATION_NAME = "eu.torvian.chatbot"

/**
 * Main entry point for the WASM/JS application.
 *
 * TODO: Read config objects from configuration file (config.json)
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    logger.info("WASM/JS application starting...")

    val appConfig = AppConfig(
        serverUrl = "https://localhost:8443",
        baseUserDataStoragePath = APPLICATION_NAME,
        tokenStorageDir = "tokens",
        certificateStorageDir = "certs" // Not used in WASM, but needed for Koin setup
    )

    val encryptionConfig = EncryptionConfig(
        // TODO: **IMPORTANT:** Change this key in production!
        masterKeys = mapOf(1 to "G2CgJOQQtIC+yfz+LLoDp/osBLUVzW9JE9BrQA0dQFo="),
        keyVersion = 1
    )

    ComposeViewport(document.body!!) {
        KoinApplication(application = {
            modules(
                wasmJsModule(appConfig, encryptionConfig),
                appModule(appConfig.serverUrl)
            )
        }) {
            AppShell()
        }
    }
}
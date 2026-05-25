package eu.torvian.chatbot.app.main

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import eu.torvian.chatbot.app.compose.startup.CommonAppLifecycleManager
import eu.torvian.chatbot.app.config.WebStorageClientConfigLoader
import eu.torvian.chatbot.app.koin.appModule
import eu.torvian.chatbot.app.koin.wasmJsModule
import eu.torvian.chatbot.app.utils.misc.createKmpLogger
import kotlinx.browser.window

private val logger = createKmpLogger("WasmJsAppMain")

/**
 * The localStorage namespace used as the config directory for the WasmJS platform.
 * Config entries are stored as keys prefixed with this value, e.g.
 * `"eu.torvian.chatbot/config.json"`.
 */
private const val CONFIG_DIR = "eu.torvian.chatbot"

/**
 * Main entry point for the WasmJS application.
 *
 * Delegates all startup logic to [CommonAppLifecycleManager], which drives the same
 * Loading → NeedsSetup/Ready/Error state machine used on the desktop target.
 * Configuration is loaded from and saved to the browser's localStorage via
 * [WebStorageClientConfigLoader].
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    logger.info("WasmJS application starting...")
    ComposeViewport {
        CommonAppLifecycleManager(
            configDir = CONFIG_DIR,
            configLoader = WebStorageClientConfigLoader(),
            onExit = {
                logger.info("User requested page reload from error screen.")
                window.location.reload()
            },
            koinApp = { config ->
                modules(
                    wasmJsModule(config),
                    appModule(config)
                )
            }
        )
    }
}
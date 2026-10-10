package eu.torvian.chatbot.app.koin

import eu.torvian.chatbot.app.config.AppConfiguration
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module for providing dependencies related to the application's frontend.
 *
 * The definitions are grouped into four feature modules and included in registration order:
 * application-wide infrastructure, the backend API clients and repositories, the chat runtime, and
 * the ViewModels behind the screens.
 *
 * @param config The application configuration containing server URL and other settings.
 * @return A Koin module with frontend dependencies
 */
fun appModule(config: AppConfiguration): Module = module {
    includes(
        appCoreModule(config),
        appApiModule(config),
        appChatModule(),
        appSettingsModule()
    )
}

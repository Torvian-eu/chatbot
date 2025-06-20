package eu.torvian.chatbot.server.main

import eu.torvian.chatbot.server.ktor.routes.ApiRoutesKtor
import io.ktor.server.application.Application
import org.koin.dsl.module

/**
 * Defines the Koin module for components specific to the main application setup.
 * @param application The Ktor Application instance to provide.
 */
fun mainModule(application: Application) = module {
    single { application }
    single<DataManager> { ExposedDataManager(get()) } // Assuming ExposedDataManager is a dependency
    single { ApiRoutesKtor( get(), get(), get(), get(), get(), get()) }
}

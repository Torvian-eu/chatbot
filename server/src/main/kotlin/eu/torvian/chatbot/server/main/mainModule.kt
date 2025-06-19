package eu.torvian.chatbot.server.main

import io.ktor.server.application.Application
import org.koin.dsl.module

fun mainModule(application: Application) = module {
    single { application }
    single<DataManager> { ExposedDataManager(get()) }
    single<ApiRoutes> { ApiRoutesKtor(get(), get(), get(), get(), get(), get(), get()) }
}
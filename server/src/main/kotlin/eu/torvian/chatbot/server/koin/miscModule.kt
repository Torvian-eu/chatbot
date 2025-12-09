package eu.torvian.chatbot.server.koin

import eu.torvian.chatbot.server.ktor.routes.ApiRoutesKtor
import eu.torvian.chatbot.server.utils.transactions.ExposedTransactionScope
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import org.koin.dsl.module

/**
 * Koin module for providing miscellaneous dependencies.
 *
 * This module includes:
 * - An instance of [TransactionScope] using [ExposedTransactionScope].
 * - An instance of [ApiRoutesKtor] for configuring Ktor routes.
 *
 * @return A Koin module with miscellaneous dependencies.
 */
fun miscModule() = module {
    single<TransactionScope> {
        ExposedTransactionScope(get())
    }
    single { ApiRoutesKtor(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}
package eu.torvian.chatbot.server.koin

import eu.torvian.chatbot.server.utils.transactions.ExposedTransactionScope
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import org.koin.dsl.module

/**
 * Koin module for providing miscellaneous dependencies.
 *
 * This module includes:
 * - An instance of [TransactionScope] using [ExposedTransactionScope].
 *
 * @return A Koin module with miscellaneous dependencies.
 */
fun miscModule() = module {
    single<TransactionScope> {
        ExposedTransactionScope(get())
    }
}
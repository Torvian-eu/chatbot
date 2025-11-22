package eu.torvian.chatbot.app.koin

import eu.torvian.chatbot.app.database.LocalDatabaseProvider
import eu.torvian.chatbot.app.database.dao.EncryptedSecretLocalDao
import eu.torvian.chatbot.app.database.dao.EncryptedSecretLocalDaoImpl
import eu.torvian.chatbot.app.database.dao.LocalMCPServerLocalDao
import eu.torvian.chatbot.app.database.dao.LocalMCPServerLocalDaoImpl
import eu.torvian.chatbot.app.service.misc.EncryptedSecretService
import eu.torvian.chatbot.app.service.misc.EncryptedSecretServiceImpl
import eu.torvian.chatbot.app.utils.transaction.SqlDelightTransactionScope
import eu.torvian.chatbot.app.utils.transaction.databaseDispatcher
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.security.EncryptionService
import org.koin.dsl.module

/**
 * Koin module for database and local storage dependencies.
 *
 * This module provides:
 * - LocalDatabaseProvider singleton
 * - DAO implementations for database access
 * - Service layer for encrypted secrets
 *
 * Note: Platform-specific DriverFactory must be provided by platform modules.
 */
val databaseModule = module {
    // Database provider (singleton)
    single { LocalDatabaseProvider(get()) }

    // Transaction scope
    single<TransactionScope> {
        SqlDelightTransactionScope(
            transacter = get<LocalDatabaseProvider>().database,
            coroutineContext = databaseDispatcher
        )
    }

    // DAOs
    single<EncryptedSecretLocalDao> {
        EncryptedSecretLocalDaoImpl(
            queries = get<LocalDatabaseProvider>().database.encryptedSecretTableQueries,
            transactionScope = get()
        )
    }

    single<LocalMCPServerLocalDao> {
        LocalMCPServerLocalDaoImpl(
            queries = get<LocalDatabaseProvider>().database.localMCPServerLocalTableQueries,
            encryptedSecretService = get(),
            transactionScope = get()
        )
    }

    // Services
    single<EncryptedSecretService> {
        EncryptedSecretServiceImpl(
            encryptionService = get<EncryptionService>(),
            dao = get(),
            clock = get()
        )
    }
}

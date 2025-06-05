package eu.torvian.chatbot.server.koin

import eu.torvian.chatbot.server.data.dao.*
import eu.torvian.chatbot.server.data.exposed.*
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import org.koin.dsl.module

/**
 * Dependency injection module for configuring the application's data access objects (DAOs).
 *
 * This module provides:
 * - Implementations of DAO interfaces using Exposed ORM.
 * - Dependencies required by the DAO implementations, such as [TransactionScope].
 */
fun daoModule() = module {
    single<ApiSecretDao> { ApiSecretDaoExposed(get()) }
}
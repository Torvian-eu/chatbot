package eu.torvian.chatbot.server.testutils.koin

import eu.torvian.chatbot.server.testutils.data.ExposedTestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import org.koin.dsl.module

/**
 * Koin module for setting up the database helper for chatbot tests.
 *
 * This module provides a singleton instance of the [TestDataManager] interface,
 * which is implemented by [ExposedTestDataManager].
 */
fun testSetupModule() = module {
    single<TestDataManager> { ExposedTestDataManager(get()) } // Requires TransactionScope from database module
}
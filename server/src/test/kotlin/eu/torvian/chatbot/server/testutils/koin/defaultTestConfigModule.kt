package eu.torvian.chatbot.server.testutils.koin

import eu.torvian.chatbot.server.koin.configModule
import eu.torvian.chatbot.server.testutils.data.TestDefaults

/**
 * Provides a default test configuration for the chatbot application.
 *
 * This module configures the database to use an in-memory SQLite database
 * and provides default encryption settings for testing purposes.
 */
fun defaultTestConfigModule() = configModule(
    databaseConfig = TestDefaults.getDefaultDatabaseConfig(),
    encryptionConfig = TestDefaults.DEFAULT_ENCRYPTION_CONFIG
)

package eu.torvian.chatbot.server.testutils.data

import eu.torvian.chatbot.server.data.models.ApiSecretEntity

/**
 * A declarative container for domain objects to be inserted into the chatbot test database.
 *
 * This class is used to specify which test data should be made available in the database
 * before running a test. All objects are inserted within a single transaction to improve
 * performance and maintain referential integrity.
 *
 * You can build a `TestDataSet` from predefined values (see [TestDefaults]) or construct
 * it ad hoc in each test.
 *
 * ```kotlin
 * Example:
 * 
 * val dataSet = TestDataSet(
 *     apiSecrets = listOf(TestDefaults.apiSecret1, TestDefaults.apiSecret2)
 * )
 * ```
 *
 * @property apiSecrets List of API secret entries to insert into the `api_secrets` table.
 */
data class TestDataSet(
    val apiSecrets: List<ApiSecretEntity> = emptyList(),
    // Add other lists for different domain objects/tables as your chatbot project grows
)
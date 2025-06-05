package eu.torvian.chatbot.server.testutils.data

import eu.torvian.chatbot.server.data.models.ApiSecretEntity

/**
 * Manager interface for inserting and cleaning up test data in the chatbot database.
 *
 * A `TestDataManager` implementation is responsible for:
 * - Creating the necessary database tables for the provided domain objects.
 * - Inserting all objects from a [TestDataSet] in a valid order (respecting dependencies).
 * - Wrapping all inserts in a single transaction for performance and consistency.
 * - Optionally cleaning up (dropping) the created tables after the test.
 *
 * Example usage in a test:
 *
 * ```kotlin
 * @BeforeEach
 * fun setUp() {
 *     val dataSet = TestDataSet(
 *         apiSecretEntries = listOf(TestDefaults.apiSecretEntry1)
 *     )
 *     testDataManager.setup(dataSet)
 * }
 *
 * @AfterEach
 * fun tearDown() {
 *     testDataManager.cleanup()
 * }
 * ```
 * 
 */
interface TestDataManager {

    /**
     * Prepares the database with the given set of domain objects.
     *
     * - Ensures the correct tables exist (and are created if needed).
     * - Inserts all data from the given [TestDataSet].
     * - Uses a single transaction to ensure atomic setup.
     *
     * @param dataSet The test data to insert into the database.
     * @return The same data, but with updated values (e.g. generated IDs - though not applicable here).
     */
    suspend fun setup(dataSet: TestDataSet): TestDataSet

    /**
     * Cleans up all tables that were created as part of [setup].
     *
     * Typically called after a test completes to restore a clean state.
     */
    suspend fun cleanup()


    // --- Table management ---

    /**
     * Creates the specified tables in the database.
     *
     * This function automatically creates the tables in the correct order, so that
     * foreign key constraints are not violated.
     *
     * @param tables The set of tables to create.
     */
    suspend fun createTables(tables: Set<Table>)

    /**
     * Drops the specified tables from the database.
     *
     * This function automatically drops the tables in the correct order, so that
     * foreign key constraints are not violated.
     *
     * @param tables The set of tables to drop.
     */
    suspend fun dropTables(tables: Set<Table>)

    /**
     * Creates all tables in the database known to the manager.
     */
    suspend fun createAllTables()

    /**
     * Drops all tables in the database known to the manager.
     */
    suspend fun dropAllTables()

    // --- Individual insert operations for flexible test control ---

    /**
     * Inserts an API secret into the database. Creates the table if it does not exist.
     *
     * @param secret The API secret to insert.
     * @return The inserted secret.
     */
    suspend fun insertApiSecret(secret: ApiSecretEntity): ApiSecretEntity

    // Add other individual insert functions here as your chatbot project grows
}
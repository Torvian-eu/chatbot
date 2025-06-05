package eu.torvian.chatbot.server.testutils.data

import eu.torvian.chatbot.server.data.models.*
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert

/**
 * Implementation of [TestDataManager] for Exposed ORM.
 *
 * This implementation is specific to the chatbot's database schema and uses the Exposed library
 * for database operations. It is responsible for creating and populating the necessary tables
 * with test data, and for cleaning up after the tests.
 *
 * @param transactionScope The transaction scope to use for database operations.
 */
class ExposedTestDataManager(private val transactionScope: TransactionScope) : TestDataManager {
    /**
     * Tracks the database tables that have been created so far during this manager's lifecycle.
     * Used for both avoiding duplicate creation and for cleanup.
     */
    private val createdTables = mutableSetOf<Table>()

    companion object {
        /**
         * List of table mappings for the chatbot database.
         *
         * Each entry in the list is a pair of a Table enum value and its corresponding Exposed Table object.
         * The order of the list defines the order in which tables are created. They are dropped in reverse order.
         * This is important for foreign key constraints.
         */
        private val tableMappings = listOf(
            Table.CHAT_SESSIONS to ChatSessions,
            Table.CHAT_MESSAGES to ChatMessages,
            Table.CHAT_GROUPS to ChatGroups,
            Table.LLM_MODELS to LLMModels,
            Table.MODEL_SETTINGS to ModelSettings,
            Table.API_SECRETS to ApiSecretsTable,
        )

        /**
         * List of all tables known to this manager.
         */
        private val allTables = tableMappings.map { it.second }
    }

    override suspend fun setup(dataSet: TestDataSet): TestDataSet = transactionScope.transaction {
        val requiredTables = inferTablesFromDataSet(dataSet)
        createTables(requiredTables)

        val insertedApiSecrets = dataSet.apiSecrets.map { insertApiSecret(it) }
        // Add insertion loops for other data types here as needed

        // Return the dataset with potentially updated objects (though TestApiSecret has no DB-generated ID currently)
        TestDataSet(
            apiSecrets = insertedApiSecrets
            // Populate other lists with inserted data
        )
    }

    override suspend fun cleanup() {
        transactionScope.transaction {
            if (createdTables.isEmpty()) {
                // No tables were created by this manager instance, nothing to drop
                return@transaction
            }
            // Drop tables in reverse creation order
            val tablesToDrop = tableMappings
                .filter { it.first in createdTables }
                .map { it.second }
                .reversed()

            if (tablesToDrop.isNotEmpty()) {
                SchemaUtils.drop(*tablesToDrop.toTypedArray(), inBatch = true)
                createdTables.clear() // Clear the set after dropping
            }
        }
    }

    override suspend fun createTables(tables: Set<Table>) {
        transactionScope.transaction {
            if (tables.isEmpty()) return@transaction
            val tablesToCreate = tableMappings
                .filter { it.first in tables && it.first !in createdTables } // Only create requested tables not already created
                .map { it.second }

            if (tablesToCreate.isNotEmpty()) {
                SchemaUtils.create(*tablesToCreate.toTypedArray(), inBatch = true)
                createdTables.addAll(tables.filter { it in tableMappings.map { mapping -> mapping.first } }) // Add only the tables we actually created
            }
        }
    }

    override suspend fun dropTables(tables: Set<Table>) {
        transactionScope.transaction {
            if (tables.isEmpty()) return@transaction
            val tablesToDrop = tableMappings
                .filter { it.first in tables && it.first in createdTables } // Only drop tables we created and that were requested
                .map { it.second }
                .reversed() // Drop in reverse order

            if (tablesToDrop.isNotEmpty()) {
                SchemaUtils.drop(*tablesToDrop.toTypedArray(), inBatch = true)
                createdTables.removeAll(tables) // Remove dropped tables from tracking
            }
        }
    }

    override suspend fun createAllTables() {
        transactionScope.transaction {
            SchemaUtils.create(*allTables.toTypedArray(), inBatch = true)
            createdTables.addAll(tableMappings.map { it.first }) // Assume all tables are now created
        }
    }

    override suspend fun dropAllTables() {
        transactionScope.transaction {
            SchemaUtils.drop(*allTables.reversed().toTypedArray(), inBatch = true)
            createdTables.clear() // Assume all tables are now dropped
        }
    }

    override suspend fun insertApiSecret(secret: ApiSecretEntity): ApiSecretEntity =
        transactionScope.transaction {
            ensureTableCreated(Table.API_SECRETS)
            ApiSecretsTable.insert {
                it[alias] = secret.alias
                it[encrypted_credential] = secret.encryptedCredential
                it[wrapped_dek] = secret.wrappedDek
                it[key_version] = secret.keyVersion
                it[created_at] = secret.createdAt
                it[updated_at] = secret.updatedAt
            }
            secret
        }

    /**
     * Ensures the specified table has been created by this manager instance. If it hasn't been created yet,
     * it is created immediately and marked as created. This is useful for individual insert operations.
     */
    private fun ensureTableCreated(table: Table) {
        if (table !in createdTables) {
            tableMappings.find { it.first == table }?.second?.let {
                SchemaUtils.create(it)
                createdTables += table
            } ?: error("Table mapping not found for enum: $table") // Should not happen if tableMappings is correct
        }
    }

    /**
     * Infers which tables are required based on the data included in a [TestDataSet].
     */
    private fun inferTablesFromDataSet(data: TestDataSet): Set<Table> {
        val required = mutableSetOf<Table>()
        if (data.apiSecrets.isNotEmpty()) required += Table.API_SECRETS
        // Add checks for other data lists here
        return required
    }
}

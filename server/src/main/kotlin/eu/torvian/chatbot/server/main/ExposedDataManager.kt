package eu.torvian.chatbot.server.main

import eu.torvian.chatbot.server.data.tables.*
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.exists

/**
 * Implementation of [DataManager] for Exposed ORM.
 *
 * This implementation is specific to the chatbot's database schema and uses the Exposed library
 * for database operations. It is responsible for creating and dropping the necessary tables
 * for the chatbot's data model.
 *
 * @param transactionScope The transaction scope to use for database operations.
 */
class ExposedDataManager(
    private val transactionScope: TransactionScope
) : DataManager {

    companion object {
        /**
         * List of all tables in the chatbot database.
         *
         * The order of the list defines the order in which tables are created. They are dropped in reverse order.
         * This is important for foreign key constraints.
         */
        private val tables = listOf(
            // Core user management tables (must come first)
            UsersTable,
            RolesTable,
            PermissionsTable,
            RolePermissionsTable,
            UserRoleAssignmentsTable,
            UserSessionsTable,
            UserGroupsTable,
            UserGroupMembershipsTable,

            // Existing core tables
            ApiSecretTable,
            LLMProviderTable,
            LLMModelTable,
            ModelSettingsTable,
            ChatGroupTable,
            ChatSessionTable,
            ChatMessageTable,
            AssistantMessageTable,
            SessionCurrentLeafTable,

            // Ownership tables (must come after the resources they reference)
            ChatSessionOwnersTable,
            ChatGroupOwnersTable,
            LLMProviderOwnersTable,
            LLMModelOwnersTable,
            ModelSettingsOwnersTable,
            ApiSecretOwnersTable,

            // Access tables (must come after both resources and user groups)
            LLMProviderAccessTable,
            LLMModelAccessTable,
            ModelSettingsAccessTable
        )
    }

    override suspend fun createTables() {
        transactionScope.transaction {
            SchemaUtils.create(*tables.toTypedArray(), inBatch = true)
        }
    }

    override suspend fun dropTables() {
        transactionScope.transaction {
            SchemaUtils.drop(*tables.reversed().toTypedArray(), inBatch = true)
        }
    }

    override suspend fun isDatabaseEmpty(): Boolean {
        // Check if any tables exist
        return transactionScope.transaction {
            !tables.any { it.exists() }
        }
    }
}

package eu.torvian.chatbot.server.testutils.data

import eu.torvian.chatbot.common.models.*
import eu.torvian.chatbot.server.data.entities.ApiSecretEntity
import eu.torvian.chatbot.server.data.entities.ChatSessionEntity
import eu.torvian.chatbot.server.data.entities.SessionCurrentLeafEntity
import eu.torvian.chatbot.server.data.tables.*
import eu.torvian.chatbot.server.data.tables.mappers.*
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.leftJoin
import org.jetbrains.exposed.sql.selectAll

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
            Table.API_SECRETS to ApiSecretTable,
            Table.LLM_PROVIDERS to LLMProviderTable,
            Table.LLM_MODELS to LLMModelTable,
            Table.MODEL_SETTINGS to ModelSettingsTable,
            Table.CHAT_GROUPS to ChatGroupTable,
            Table.CHAT_SESSIONS to ChatSessionTable,
            Table.CHAT_MESSAGES to ChatMessageTable,
            Table.ASSISTANT_MESSAGES to AssistantMessageTable,
            Table.SESSION_CURRENT_LEAF to SessionCurrentLeafTable
        )

        /**
         * List of all tables known to this manager.
         */
        private val allTables = tableMappings.map { it.second }
    }

    override suspend fun setup(dataSet: TestDataSet) = transactionScope.transaction {
        val requiredTables = inferTablesFromDataSet(dataSet)
        createTables(requiredTables)

        dataSet.apiSecrets.forEach { insertApiSecret(it) }
        dataSet.llmProviders.forEach { insertLLMProvider(it) }
        dataSet.llmModels.forEach { insertLLMModel(it) }
        dataSet.modelSettings.forEach { insertModelSettings(it) }
        dataSet.chatGroups.forEach { insertChatGroup(it) }
        dataSet.chatSessions.forEach { insertChatSession(it) }
        dataSet.chatMessages.forEach { insertChatMessage(it) }
        dataSet.sessionCurrentLeaves.forEach { insertSessionCurrentLeaf(it) }
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

    override suspend fun insertApiSecret(secret: ApiSecretEntity) =
        transactionScope.transaction {
            ensureTableCreated(Table.API_SECRETS)
            ApiSecretTable.insert {
                it[alias] = secret.alias
                it[encrypted_credential] = secret.encryptedCredential
                it[wrapped_dek] = secret.wrappedDek
                it[key_version] = secret.keyVersion
                it[created_at] = secret.createdAt
                it[updated_at] = secret.updatedAt
            }
            return@transaction
        }

    override suspend fun getApiSecret(alias: String): ApiSecretEntity? =
        transactionScope.transaction {
            ensureTableCreated(Table.API_SECRETS)
            ApiSecretTable.selectAll().where { ApiSecretTable.alias eq alias }
                .map { it.toApiSecretEntity() }
                .singleOrNull()
        }

    override suspend fun insertLLMProvider(provider: LLMProvider) =
        transactionScope.transaction {
            ensureTableCreated(Table.LLM_PROVIDERS)
            LLMProviderTable.insert {
                it[id] = provider.id
                it[apiKeyId] = provider.apiKeyId
                it[name] = provider.name
                it[description] = provider.description
                it[baseUrl] = provider.baseUrl
                it[type] = provider.type
            }
            return@transaction
        }

    override suspend fun getLLMProvider(id: Long): LLMProvider? =
        transactionScope.transaction {
            ensureTableCreated(Table.LLM_PROVIDERS)
            LLMProviderTable.selectAll().where { LLMProviderTable.id eq id }
                .map { it.toLLMProvider() }
                .singleOrNull()
        }

    override suspend fun insertChatGroup(chatGroup: ChatGroup) =
        transactionScope.transaction {
            ensureTableCreated(Table.CHAT_GROUPS)
            ChatGroupTable.insert {
                it[id] = chatGroup.id
                it[name] = chatGroup.name
                it[createdAt] = chatGroup.createdAt.toEpochMilliseconds()
            }
            return@transaction
        }

    override suspend fun getChatGroup(id: Long): ChatGroup? =
        transactionScope.transaction {
            ensureTableCreated(Table.CHAT_GROUPS)
            ChatGroupTable.selectAll().where { ChatGroupTable.id eq id }
                .map { it.toChatGroup() }
                .singleOrNull()
        }

    override suspend fun insertChatSession(chatSession: ChatSessionEntity) =
        transactionScope.transaction {
            ensureTableCreated(Table.CHAT_SESSIONS)
            ChatSessionTable.insert {
                it[id] = chatSession.id
                it[name] = chatSession.name
                it[createdAt] = chatSession.createdAt.toEpochMilliseconds()
                it[updatedAt] = chatSession.updatedAt.toEpochMilliseconds()
                it[groupId] = chatSession.groupId
                it[currentModelId] = chatSession.currentModelId
                it[currentSettingsId] = chatSession.currentSettingsId
            }
            return@transaction
        }

    override suspend fun getChatSession(id: Long): ChatSessionEntity? =
        transactionScope.transaction {
            ensureTableCreated(Table.CHAT_SESSIONS)
            ChatSessionTable.selectAll().where { ChatSessionTable.id eq id }
                .map { it.toChatSessionEntity() }
                .singleOrNull()
        }

    override suspend fun insertSessionCurrentLeaf(sessionCurrentLeaf: SessionCurrentLeafEntity) =
        transactionScope.transaction {
            ensureTableCreated(Table.SESSION_CURRENT_LEAF)
            SessionCurrentLeafTable.insert {
                it[sessionId] = sessionCurrentLeaf.sessionId
                it[messageId] = sessionCurrentLeaf.messageId
            }
            return@transaction
        }

    override suspend fun getSessionCurrentLeaf(sessionId: Long): SessionCurrentLeafEntity? =
        transactionScope.transaction {
            ensureTableCreated(Table.SESSION_CURRENT_LEAF)
            SessionCurrentLeafTable.selectAll()
                .where { SessionCurrentLeafTable.sessionId eq sessionId }
                .map { it.toSessionCurrentLeafEntity() }
                .singleOrNull()
        }

    override suspend fun insertChatMessage(chatMessage: ChatMessage) =
        transactionScope.transaction {
            ensureTableCreated(Table.CHAT_MESSAGES)
            ensureTableCreated(Table.ASSISTANT_MESSAGES)
            ChatMessageTable.insert {
                it[id] = chatMessage.id
                it[sessionId] = chatMessage.sessionId
                it[role] = chatMessage.role
                it[content] = chatMessage.content
                it[createdAt] = chatMessage.createdAt.toEpochMilliseconds()
                it[updatedAt] = chatMessage.updatedAt.toEpochMilliseconds()
                it[parentMessageId] = chatMessage.parentMessageId
                it[childrenMessageIds] = Json.encodeToString(chatMessage.childrenMessageIds)
            }
            if (chatMessage is ChatMessage.AssistantMessage) {
                AssistantMessageTable.insert {
                    it[messageId] = chatMessage.id
                    it[modelId] = chatMessage.modelId
                    it[settingsId] = chatMessage.settingsId
                }
            }
            return@transaction
        }

    override suspend fun getChatMessage(id: Long): ChatMessage? =
        transactionScope.transaction {
            ensureTableCreated(Table.CHAT_MESSAGES)
            ensureTableCreated(Table.ASSISTANT_MESSAGES)

            // Perform a single query with left join to get all needed data
            val query = ChatMessageTable
                .leftJoin(AssistantMessageTable, { ChatMessageTable.id }, { AssistantMessageTable.messageId })
                .selectAll()
                .where { ChatMessageTable.id eq id }
                .singleOrNull() ?: return@transaction null

            // Convert to appropriate message type based on role
            when (query[ChatMessageTable.role]) {
                ChatMessage.Role.USER -> query.toUserMessage()
                ChatMessage.Role.ASSISTANT -> query.toAssistantMessage()
            }
        }

    override suspend fun getChatMessagesForSession(sessionId: Long): List<ChatMessage> {
        return transactionScope.transaction {
            ensureTableCreated(Table.CHAT_MESSAGES)
            ensureTableCreated(Table.ASSISTANT_MESSAGES)

            // Perform a single query with left join to get all messages with their assistant data if any
            val results = ChatMessageTable
                .leftJoin(AssistantMessageTable, { ChatMessageTable.id }, { AssistantMessageTable.messageId })
                .selectAll()
                .where { ChatMessageTable.sessionId eq sessionId }

            // Transform to appropriate message types based on role
            results.map { row ->
                when (row[ChatMessageTable.role]) {
                    ChatMessage.Role.USER -> row.toUserMessage()
                    ChatMessage.Role.ASSISTANT -> row.toAssistantMessage()
                }
            }
        }
    }

    override suspend fun insertLLMModel(llmModel: LLMModel) =
        transactionScope.transaction {
            ensureTableCreated(Table.LLM_MODELS)
            LLMModelTable.insert {
                it[id] = llmModel.id
                it[name] = llmModel.name
                it[providerId] = llmModel.providerId
                it[active] = llmModel.active
                it[displayName] = llmModel.displayName
                it[type] = llmModel.type
                it[capabilities] = llmModel.capabilities?.let { cap ->
                    Json.encodeToString(cap)
                }
            }
            return@transaction
        }

    override suspend fun getLLMModel(id: Long): LLMModel? =
        transactionScope.transaction {
            ensureTableCreated(Table.LLM_MODELS)
            LLMModelTable.selectAll().where { LLMModelTable.id eq id }
                .map { it.toLLMModel() }
                .singleOrNull()
        }

    override suspend fun insertModelSettings(modelSettings: ModelSettings) =
        transactionScope.transaction {
            ensureTableCreated(Table.MODEL_SETTINGS)
            ModelSettingsTable.insert {
                it[id] = modelSettings.id
                it[modelId] = modelSettings.modelId
                it[name] = modelSettings.name
                it[systemMessage] = modelSettings.systemMessage
                it[temperature] = modelSettings.temperature
                it[maxTokens] = modelSettings.maxTokens
                it[customParamsJson] = modelSettings.customParamsJson
            }
            return@transaction
        }

    override suspend fun getModelSettings(id: Long): ModelSettings? =
        transactionScope.transaction {
            ensureTableCreated(Table.MODEL_SETTINGS)
            ModelSettingsTable.selectAll().where { ModelSettingsTable.id eq id }
                .map { it.toModelSettings() }
                .singleOrNull()
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
        if (data.llmProviders.isNotEmpty()) required += Table.LLM_PROVIDERS
        if (data.chatGroups.isNotEmpty()) required += Table.CHAT_GROUPS
        if (data.chatSessions.isNotEmpty()) required += Table.CHAT_SESSIONS
        if (data.chatMessages.isNotEmpty()) required += Table.CHAT_MESSAGES
        if (data.llmModels.isNotEmpty()) required += Table.LLM_MODELS
        if (data.modelSettings.isNotEmpty()) required += Table.MODEL_SETTINGS
        if (data.sessionCurrentLeaves.isNotEmpty()) required += Table.SESSION_CURRENT_LEAF
        return required
    }
}

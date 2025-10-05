package eu.torvian.chatbot.server.testutils.data

import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.core.ChatGroup
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.server.data.entities.ApiSecretEntity
import eu.torvian.chatbot.server.data.entities.ChatSessionEntity
import eu.torvian.chatbot.server.data.entities.SessionCurrentLeafEntity
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.data.entities.UserSessionEntity
import eu.torvian.chatbot.server.data.entities.RoleEntity
import eu.torvian.chatbot.server.data.entities.PermissionEntity
import eu.torvian.chatbot.server.data.entities.RolePermissionEntity
import eu.torvian.chatbot.server.data.entities.UserRoleAssignmentEntity

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
     */
    suspend fun setup(dataSet: TestDataSet)

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
     */
    suspend fun insertApiSecret(secret: ApiSecretEntity)

    /**
     * Retrieves an API secret from the database.
     *
     * @param alias The alias of the secret to retrieve.
     * @return The secret if found, null otherwise.
     */
    suspend fun getApiSecret(alias: String): ApiSecretEntity?

    /**
     * Inserts a chat group into the database. Creates the table if it does not exist.
     *
     * @param chatGroup The chat group to insert (including its ID).
     */
    suspend fun insertChatGroup(chatGroup: ChatGroup)

    /**
     * Retrieves a chat group from the database.
     *
     * @param id The ID of the chat group to retrieve.
     * @return The chat group if found, null otherwise.
     */
    suspend fun getChatGroup(id: Long): ChatGroup?

    /**
     * Inserts a chat session into the database. Creates the table if it does not exist.
     *
     * @param chatSession The chat session to insert (including its ID).
     * @return The inserted chat session.
     */
    suspend fun insertChatSession(chatSession: ChatSessionEntity)

    /**
     * Retrieves a chat session from the database.
     *
     * @param id The ID of the chat session to retrieve.
     * @return The chat session if found, null otherwise.
     */
    suspend fun getChatSession(id: Long): ChatSessionEntity?

    /**
     * Inserts a chat message into the database. Creates the table if it does not exist.
     *
     * @param chatMessage The chat message to insert (including its ID).
     */
    suspend fun insertChatMessage(chatMessage: ChatMessage)

    /**
     * Retrieves a chat message from the database.
     *
     * @param id The ID of the chat message to retrieve.
     * @return The chat message if found, null otherwise.
     */
    suspend fun getChatMessage(id: Long): ChatMessage?

    /**
     * Retrieves all chat messages from the database for a specific session.
     *
     * @param sessionId The ID of the session to get the messages for.
     * @return The list of chat messages for the session.
     */
    suspend fun getChatMessagesForSession(sessionId: Long): List<ChatMessage>

    /**
     * Inserts a session current leaf record into the database. Creates the table if it does not exist.
     * This record links a session with its current leaf message.
     *
     * @param sessionCurrentLeaf The session current leaf entity to insert.
     */
    suspend fun insertSessionCurrentLeaf(sessionCurrentLeaf: SessionCurrentLeafEntity)

    /**
     * Retrieves a session current leaf record from the database for a specific session.
     *
     * @param sessionId The ID of the session to get the current leaf for.
     * @return The session current leaf entity if found, null otherwise.
     */
    suspend fun getSessionCurrentLeaf(sessionId: Long): SessionCurrentLeafEntity?

    /**
     * Inserts an LLM model into the database. Creates the table if it does not exist.
     *
     * @param llmModel The LLM model to insert (including its ID).
     */
    suspend fun insertLLMModel(llmModel: LLMModel)

    /**
     * Retrieves an LLM model from the database.
     *
     * @param id The ID of the LLM model to retrieve.
     * @return The LLM model if found, null otherwise.
     */
    suspend fun getLLMModel(id: Long): LLMModel?

    /**
     * Inserts model settings into the database. Creates the table if it does not exist.
     *
     * @param modelSettings The model settings to insert (including its ID).
     */
    suspend fun insertModelSettings(modelSettings: ModelSettings)

    /**
     * Retrieves model settings from the database.
     *
     * @param id The ID of the model settings to retrieve.
     * @return The model settings if found, null otherwise.
     */
    suspend fun getModelSettings(id: Long): ModelSettings?

    /**
     * Inserts an LLM provider into the database. Creates the table if it does not exist.
     *
     * @param provider The LLM provider to insert.
     */
    suspend fun insertLLMProvider(provider: LLMProvider)

    /**
     * Retrieves an LLM provider from the database.
     *
     * @param id The ID of the LLM provider to retrieve.
     * @return The LLM provider if found, null otherwise.
     */
    suspend fun getLLMProvider(id: Long): LLMProvider?

    /**
     * Inserts a user into the database. Creates the table if it does not exist.
     *
     * @param user The user entity to insert.
     */
    suspend fun insertUser(user: UserEntity)

    /**
     * Retrieves a user from the database.
     *
     * @param id The ID of the user to retrieve.
     * @return The user entity if found, null otherwise.
     */
    suspend fun getUser(id: Long): UserEntity?

    /**
     * Inserts a user session into the database. Creates the table if it does not exist.
     *
     * @param userSession The user session entity to insert.
     */
    suspend fun insertUserSession(userSession: UserSessionEntity)

    /**
     * Retrieves a user session from the database.
     *
     * @param id The ID of the user session to retrieve.
     * @return The user session entity if found, null otherwise.
     */
    suspend fun getUserSession(id: Long): UserSessionEntity?

    /**
     * Inserts a group ownership record into the database. Creates the table if it does not exist.
     *
     * @param groupId The ID of the group.
     * @param userId The ID of the user who owns the group.
     */
    suspend fun insertGroupOwnership(groupId: Long, userId: Long)

    /**
     * Inserts a session ownership record into the database. Creates the table if it does not exist.
     *
     * @param sessionId The ID of the session.
     * @param userId The ID of the user who owns the session.
     */
    suspend fun insertSessionOwnership(sessionId: Long, userId: Long)

    /**
     * Inserts a role into the database. Creates the table if it does not exist.
     *
     * @param role The role entity to insert.
     */
    suspend fun insertRole(role: RoleEntity)

    /**
     * Retrieves a role from the database.
     *
     * @param id The ID of the role to retrieve.
     * @return The role entity if found, null otherwise.
     */
    suspend fun getRole(id: Long): RoleEntity?

    /**
     * Inserts a permission into the database. Creates the table if it does not exist.
     *
     * @param permission The permission entity to insert.
     */
    suspend fun insertPermission(permission: PermissionEntity)

    /**
     * Retrieves a permission from the database.
     *
     * @param id The ID of the permission to retrieve.
     * @return The permission entity if found, null otherwise.
     */
    suspend fun getPermission(id: Long): PermissionEntity?

    /**
     * Inserts a role permission mapping into the database. Creates the table if it does not exist.
     *
     * @param rolePermission The role permission entity to insert.
     */
    suspend fun insertRolePermission(rolePermission: RolePermissionEntity)

    /**
     * Retrieves a role permission mapping from the database.
     *
     * @param roleId The ID of the role.
     * @param permissionId The ID of the permission.
     * @return The role permission entity if found, null otherwise.
     */
    suspend fun getRolePermission(roleId: Long, permissionId: Long): RolePermissionEntity?

    /**
     * Inserts a user role assignment into the database. Creates the table if it does not exist.
     *
     * @param userRoleAssignment The user role assignment entity to insert.
     */
    suspend fun insertUserRoleAssignment(userRoleAssignment: UserRoleAssignmentEntity)

    /**
     * Retrieves a user role assignment from the database.
     *
     * @param userId The ID of the user.
     * @param roleId The ID of the role.
     * @return The user role assignment entity if found, null otherwise.
     */
    suspend fun getUserRoleAssignment(userId: Long, roleId: Long): UserRoleAssignmentEntity?

    // Add other individual insert functions here as your chatbot project grows
}
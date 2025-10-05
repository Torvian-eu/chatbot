package eu.torvian.chatbot.server.testutils.data

import eu.torvian.chatbot.common.models.ChatGroup
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.server.data.entities.ApiSecretEntity
import eu.torvian.chatbot.server.data.entities.ChatSessionEntity
import eu.torvian.chatbot.server.data.entities.SessionCurrentLeafEntity
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.data.entities.RoleEntity
import eu.torvian.chatbot.server.data.entities.PermissionEntity
import eu.torvian.chatbot.server.data.entities.RolePermissionEntity
import eu.torvian.chatbot.server.data.entities.UserRoleAssignmentEntity

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
 *     apiSecrets = listOf(TestDefaults.apiSecret1, TestDefaults.apiSecret2),
 *     users = listOf(TestDefaults.adminUser, TestDefaults.standardUser),
 *     roles = listOf(TestDefaults.adminRole, TestDefaults.userRole)
 * )
 * ```
 *
 * @property apiSecrets List of API secret entries to insert into the `api_secrets` table.
 * @property llmProviders List of LLM provider entries to insert into the `llm_providers` table.
 * @property chatGroups List of chat group entries to insert into the `chat_groups` table.
 * @property chatSessions List of chat session entries to insert into the `chat_sessions` table.
 * @property chatMessages List of chat message entries to insert into the `chat_messages` table.
 * @property llmModels List of LLM model entries to insert into the `llm_models` table.
 * @property modelSettings List of model settings entries to insert into the `model_settings` table.
 * @property sessionCurrentLeaves List of session current leaf entries to insert into the `session_current_leaf` table.
 * @property users List of user entries to insert into the `users` table.
 * @property roles List of role entries to insert into the `roles` table.
 * @property permissions List of permission entries to insert into the `permissions` table.
 * @property rolePermissions List of role permission mappings to insert into the `role_permissions` table.
 * @property userRoleAssignments List of user role assignments to insert into the `user_role_assignments` table.
 */
data class TestDataSet(
    val apiSecrets: List<ApiSecretEntity> = emptyList(),
    val llmProviders: List<LLMProvider> = emptyList(),
    val chatGroups: List<ChatGroup> = emptyList(),
    val chatSessions: List<ChatSessionEntity> = emptyList(),
    val chatMessages: List<ChatMessage> = emptyList(),
    val llmModels: List<LLMModel> = emptyList(),
    val modelSettings: List<ModelSettings> = emptyList(),
    val sessionCurrentLeaves: List<SessionCurrentLeafEntity> = emptyList(),
    val users: List<UserEntity> = emptyList(),
    val roles: List<RoleEntity> = emptyList(),
    val permissions: List<PermissionEntity> = emptyList(),
    val rolePermissions: List<RolePermissionEntity> = emptyList(),
    val userRoleAssignments: List<UserRoleAssignmentEntity> = emptyList()
)
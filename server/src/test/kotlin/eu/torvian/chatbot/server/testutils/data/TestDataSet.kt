package eu.torvian.chatbot.server.testutils.data

import eu.torvian.chatbot.common.models.ChatGroup
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.common.models.ModelSettings
import eu.torvian.chatbot.server.data.entities.ApiSecretEntity
import eu.torvian.chatbot.server.data.entities.ChatSessionEntity
import eu.torvian.chatbot.server.data.entities.SessionCurrentLeafEntity

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
 * @property chatGroups List of chat group entries to insert into the `chat_groups` table.
 * @property chatSessions List of chat session entries to insert into the `chat_sessions` table.
 * @property chatMessages List of chat message entries to insert into the `chat_messages` table.
 * @property llmModels List of LLM model entries to insert into the `llm_models` table.
 * @property modelSettings List of model settings entries to insert into the `model_settings` table.
 * @property sessionCurrentLeaves List of session current leaf entries to insert into the `session_current_leaf` table.
 */
data class TestDataSet(
    val apiSecrets: List<ApiSecretEntity> = emptyList(),
    val chatGroups: List<ChatGroup> = emptyList(),
    val chatSessions: List<ChatSessionEntity> = emptyList(),
    val chatMessages: List<ChatMessage> = emptyList(),
    val llmModels: List<LLMModel> = emptyList(),
    val modelSettings: List<ModelSettings> = emptyList(),
    val sessionCurrentLeaves: List<SessionCurrentLeafEntity> = emptyList()
)
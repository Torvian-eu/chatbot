package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.tables.AssistantMessageTable
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Verifies how a content update treats the stored reasoning items of an assistant message.
 *
 * The two writers disagree on purpose: the public edit path replaces the answer the reasoning was produced for and
 * therefore clears it, while turn finalization writes content moments after it wrote the reasoning of the same step
 * and must keep it.
 */
class MessageDaoExposedReasoningClearTest {
    private lateinit var container: DIContainer
    private lateinit var messageDao: MessageDao
    private lateinit var testDataManager: TestDataManager
    private lateinit var transactionScope: TransactionScope

    private val testSession = TestDefaults.chatSession1
    private val testUserMessage = TestDefaults.chatMessage1
    private val testModel = TestDefaults.llmModel1
    private val testProvider = TestDefaults.llmProvider1
    private val testSettings = TestDefaults.modelSettings1
    private val testGroup = TestDefaults.chatGroup1

    /** Reasoning items as the persistence layer stores them: already sanitized to the Responses `input` shape. */
    private val reasoningItems: List<JsonObject> = listOf(
        buildJsonObject {
            put("type", "reasoning")
            put("id", "rs_persisted")
        },
        buildJsonObject {
            put("type", "reasoning")
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "reasoning_text")
                            put("text", "streamed thought")
                        }
                    )
                }
            )
        }
    )

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()

        messageDao = container.get()
        testDataManager = container.get()
        transactionScope = container.get()

        testDataManager.createTables(
            setOf(
                Table.CHAT_GROUPS,
                Table.LLM_MODELS,
                Table.LLM_PROVIDERS,
                Table.MODEL_SETTINGS,
                Table.CHAT_SESSIONS,
                Table.CHAT_MESSAGES,
                Table.ASSISTANT_MESSAGES
            )
        )
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup),
                llmModels = listOf(testModel),
                llmProviders = listOf(testProvider),
                modelSettings = listOf(testSettings),
                chatSessions = listOf(testSession),
                chatMessages = listOf(testUserMessage)
            )
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `updateMessageContent with clearReasoning nulls the stored reasoning`() = runTest {
        val inserted = insertAssistantMessageWithReasoning()

        val updated = assertIs<ChatMessage.AssistantMessage>(
            assertNotNull(
                messageDao.updateMessageContent(
                    id = inserted.id,
                    content = "edited by the user",
                    clearReasoning = true
                ).getOrNull()
            )
        )

        assertEquals("edited by the user", updated.content)
        assertNull(updated.reasoningItems, "The returned message must reflect the cleared column")
        assertNull(assistantRowReasoningJson(inserted.id), "The stored reasoning must be gone")
        assertNull(readBack(inserted.id).reasoningItems)
    }

    @Test
    fun `updateMessageContent without clearReasoning keeps the stored reasoning`() = runTest {
        val inserted = insertAssistantMessageWithReasoning()

        val updated = assertIs<ChatMessage.AssistantMessage>(
            assertNotNull(
                messageDao.updateMessageContent(
                    id = inserted.id,
                    content = "final content of the same step"
                ).getOrNull()
            )
        )

        // Turn finalization must not destroy the reasoning it wrote for the very same step.
        assertEquals(reasoningItems, updated.reasoningItems)
        assertEquals(reasoningItems, readBack(inserted.id).reasoningItems)
    }

    @Test
    fun `updateMessageContent with clearReasoning on a user message keeps the message update working`() = runTest {
        // Only assistant rows carry the reasoning column, so this must neither fail nor create assistant data.
        val updated = assertIs<ChatMessage.UserMessage>(
            assertNotNull(
                messageDao.updateMessageContent(
                    id = testUserMessage.id,
                    content = "user edited text",
                    clearReasoning = true
                ).getOrNull()
            )
        )

        assertEquals("user edited text", updated.content)
        assertEquals(0L, assistantRowCount(updated.id), "Updating a user message must not create an assistant row")
    }

    /**
     * Inserts a root assistant message carrying [reasoningItems] into the prepared session.
     *
     * @return The inserted assistant message as returned by the DAO.
     */
    private suspend fun insertAssistantMessageWithReasoning(): ChatMessage.AssistantMessage {
        val result = messageDao.insertMessage(
            sessionId = testSession.id,
            targetMessageId = null,
            position = MessageInsertPosition.APPEND,
            role = ChatMessage.Role.ASSISTANT,
            content = "answer",
            modelId = testModel.id,
            settingsId = testSettings.id,
            reasoningItems = reasoningItems
        )

        return assertIs<ChatMessage.AssistantMessage>(
            assertNotNull(result.getOrNull(), "Inserting an assistant message must succeed: ${result.leftOrNull()}")
        )
    }

    /**
     * Reads an assistant message back through the regular session-read path.
     *
     * @param messageId Id of the message to read.
     * @return The persisted assistant message.
     */
    private suspend fun readBack(messageId: Long): ChatMessage.AssistantMessage =
        assertIs<ChatMessage.AssistantMessage>(assertNotNull(messageDao.getMessageById(messageId).getOrNull()))

    /**
     * Reads the raw reasoning column of an assistant row.
     *
     * @param messageId Id of the chat message whose assistant row is looked up.
     * @return The stored JSON text, or `null` when nothing is stored.
     */
    private suspend fun assistantRowReasoningJson(messageId: Long): String? =
        transactionScope.transaction {
            AssistantMessageTable
                .selectAll()
                .where { AssistantMessageTable.messageId eq messageId }
                .singleOrNull()
                ?.get(AssistantMessageTable.reasoningItemsJson)
        }

    /**
     * Counts the `assistant_messages` rows that exist for the given message id.
     *
     * @param messageId Id of the chat message whose assistant metadata row is looked up.
     * @return `1` when an assistant row exists, `0` otherwise.
     */
    private suspend fun assistantRowCount(messageId: Long): Long =
        transactionScope.transaction {
            AssistantMessageTable
                .selectAll()
                .where { AssistantMessageTable.messageId eq messageId }
                .count()
        }
}

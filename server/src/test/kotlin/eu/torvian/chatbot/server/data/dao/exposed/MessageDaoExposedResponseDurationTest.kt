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
 * Verifies how the `response_duration_ms` column of an assistant message is written, read back and cleared.
 *
 * The column is a nullable duration in milliseconds: an absent value means "nothing was measured" (never a fake
 * zero), the insert stores it with the row it belongs to, and the content update always writes the value it was
 * given so an edit can clear it.
 */
class MessageDaoExposedResponseDurationTest {
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
    fun `insertMessage stores the duration and mirrors it into the returned message`() = runTest {
        val inserted = insertAssistantMessageWithDuration(responseDurationMs = 4200L)

        assertEquals(4200L, inserted.responseDurationMs)
        assertEquals(4200L, storedResponseDurationMs(inserted.id), "The duration must be stored on the assistant row")
        assertEquals(4200L, readBack(inserted.id).responseDurationMs)
    }

    @Test
    fun `insertMessage without a duration stores no value`() = runTest {
        val inserted = insertAssistantMessageWithDuration(responseDurationMs = null)

        assertNull(inserted.responseDurationMs)
        assertNull(storedResponseDurationMs(inserted.id))
        assertNull(readBack(inserted.id).responseDurationMs)
    }

    @Test
    fun `updateMessageContent writes the duration it was given`() = runTest {
        val inserted = insertAssistantMessageWithDuration(responseDurationMs = null)

        val updated = assertIs<ChatMessage.AssistantMessage>(
            assertNotNull(
                messageDao.updateMessageContent(
                    id = inserted.id,
                    content = "final content",
                    responseDurationMs = 1500L
                ).getOrNull()
            )
        )

        assertEquals(1500L, updated.responseDurationMs)
        assertEquals(1500L, storedResponseDurationMs(inserted.id))
        assertEquals(1500L, readBack(inserted.id).responseDurationMs)
    }

    @Test
    fun `updateMessageContent clears the stored duration when given none`() = runTest {
        val inserted = insertAssistantMessageWithDuration(responseDurationMs = 4200L)

        val updated = assertIs<ChatMessage.AssistantMessage>(
            assertNotNull(
                messageDao.updateMessageContent(
                    id = inserted.id,
                    content = "edited by the user",
                    clearReasoning = true,
                    responseDurationMs = null
                ).getOrNull()
            )
        )

        assertNull(updated.responseDurationMs, "The returned message must reflect the cleared column")
        assertNull(storedResponseDurationMs(inserted.id), "The stored duration must be gone")
        assertNull(readBack(inserted.id).responseDurationMs)
    }

    @Test
    fun `a measured duration reaches the session read path`() = runTest {
        val inserted = insertAssistantMessageWithDuration(responseDurationMs = 900L)

        val readThroughSession = messageDao.getMessagesBySessionId(testSession.id)
            .filterIsInstance<ChatMessage.AssistantMessage>()
            .single { it.id == inserted.id }

        assertEquals(900L, readThroughSession.responseDurationMs)
    }

    @Test
    fun `an insert without a duration reads back as no duration through the session read path`() = runTest {
        val inserted = insertAssistantMessageWithDuration(responseDurationMs = null)

        val readThroughSession = messageDao.getMessagesBySessionId(testSession.id)
            .filterIsInstance<ChatMessage.AssistantMessage>()
            .single { it.id == inserted.id }

        assertNull(readThroughSession.responseDurationMs)
    }

    /**
     * Inserts a root assistant message with the given measured duration into the prepared session.
     *
     * @param responseDurationMs Duration to store, or `null` when nothing was measured.
     * @return The inserted assistant message as returned by the DAO.
     */
    private suspend fun insertAssistantMessageWithDuration(
        responseDurationMs: Long?
    ): ChatMessage.AssistantMessage {
        val result = messageDao.insertMessage(
            sessionId = testSession.id,
            targetMessageId = null,
            position = MessageInsertPosition.APPEND,
            role = ChatMessage.Role.ASSISTANT,
            content = "answer",
            modelId = testModel.id,
            settingsId = testSettings.id,
            responseDurationMs = responseDurationMs
        )

        return assertIs<ChatMessage.AssistantMessage>(
            assertNotNull(result.getOrNull(), "Inserting an assistant message must succeed: ${result.leftOrNull()}")
        )
    }

    /**
     * Reads an assistant message back through the single-message read path.
     *
     * @param messageId Id of the message to read.
     * @return The persisted assistant message.
     */
    private suspend fun readBack(messageId: Long): ChatMessage.AssistantMessage =
        assertIs<ChatMessage.AssistantMessage>(assertNotNull(messageDao.getMessageById(messageId).getOrNull()))

    /**
     * Reads the raw duration column of an assistant row.
     *
     * @param messageId Id of the chat message whose assistant row is looked up.
     * @return The stored duration in milliseconds, or `null` when nothing is stored.
     */
    private suspend fun storedResponseDurationMs(messageId: Long): Long? =
        transactionScope.transaction {
            AssistantMessageTable
                .selectAll()
                .where { AssistantMessageTable.messageId eq messageId }
                .singleOrNull()
                ?.get(AssistantMessageTable.responseDurationMs)
        }
}

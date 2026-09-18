package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.core.AssistantMessageErrorCode
import eu.torvian.chatbot.common.models.core.AssistantMessageIncompleteCause
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import eu.torvian.chatbot.server.data.dao.AssistantMessageCompletionState
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
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the persistence round-trip of the assistant message completion state through [MessageDaoExposed]:
 * the four columns are written on insert and on content update, read back by the mapper, the public content
 * update clears a previous incompletion state (D10), and an unknown stored enum name degrades to "no cause"
 * instead of breaking a session read.
 */
class MessageDaoExposedAssistantCompletionTest {
    private lateinit var container: DIContainer
    private lateinit var messageDao: MessageDao
    private lateinit var testDataManager: TestDataManager
    private lateinit var transactionScope: TransactionScope

    private val testSession = TestDefaults.chatSession1
    private val testUserMessage = TestDefaults.chatMessage1
    private val testAssistantMessage = TestDefaults.chatMessage2
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
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `insertMessage persists a failure state that reads back unchanged`() = runTest {
        prepareSession()

        val completion = AssistantMessageCompletionState.failed(
            code = AssistantMessageErrorCode.OUTPUT_LIMIT_EXCEEDED,
            message = "The response was stopped because it exceeded the 64,000-character limit."
        )

        val inserted = insertAssistantMessage(content = "truncated text", completion = completion)

        // The returned DTO mirrors the persisted state, so callers can emit it without re-reading the row.
        assertFalse(inserted.isComplete)
        assertTrue(inserted.showsIncompleteNotice)
        assertEquals(AssistantMessageIncompleteCause.FAILED, inserted.incompleteCause)
        assertEquals(AssistantMessageErrorCode.OUTPUT_LIMIT_EXCEEDED, inserted.errorCode)
        assertEquals(completion.errorMessage, inserted.errorMessage)

        val readBack = assertIs<ChatMessage.AssistantMessage>(
            assertNotNull(messageDao.getMessageById(inserted.id).getOrNull())
        )
        assertEquals(inserted, readBack)
        assertEquals("truncated text", readBack.content)
        assertEquals(completion, readBack.toCompletionState())
    }

    @Test
    fun `insertMessage defaults to a completed message state`() = runTest {
        prepareSession()

        val inserted = insertAssistantMessage(content = "complete answer")

        assertTrue(inserted.isComplete, "A default insert must be completed (manual insert path)")
        assertNull(inserted.incompleteCause)
        assertNull(inserted.errorCode)
        assertNull(inserted.errorMessage)
        assertFalse(inserted.showsIncompleteNotice)

        val readBack = assertIs<ChatMessage.AssistantMessage>(
            assertNotNull(messageDao.getMessageById(inserted.id).getOrNull())
        )
        assertEquals(AssistantMessageCompletionState.Completed, readBack.toCompletionState())
    }

    @Test
    fun `insertMessage with an in-flight placeholder is read back as not completed without a cause`() = runTest {
        prepareSession()

        val inserted = insertAssistantMessage(
            content = "",
            completion = AssistantMessageCompletionState.InFlight
        )

        val readBack = assertIs<ChatMessage.AssistantMessage>(
            assertNotNull(messageDao.getMessageById(inserted.id).getOrNull())
        )
        assertFalse(readBack.isComplete, "A streaming placeholder is born not completed")
        assertNull(readBack.incompleteCause, "An in-flight message has no terminal cause yet")
        assertFalse(readBack.showsIncompleteNotice, "An in-flight message must render no notice")
    }

    @Test
    fun `updateMessageContent without a state clears a previous failure`() = runTest {
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup),
                llmModels = listOf(testModel),
                llmProviders = listOf(testProvider),
                modelSettings = listOf(testSettings),
                chatSessions = listOf(testSession),
                chatMessages = listOf(testUserMessage, testAssistantMessage)
            )
        )
        val failedState = AssistantMessageCompletionState.failed(
            code = AssistantMessageErrorCode.PROVIDER_UNAVAILABLE,
            message = "The provider is currently unavailable (HTTP 503)."
        )
        assertNotNull(
            messageDao.updateMessageContent(testAssistantMessage.id, "partial", completion = failedState).getOrNull(),
            "Marking the message as failed must succeed"
        )

        // The public edit path (user rewrites the message) relies on the default, completed state.
        val updated = assertIs<ChatMessage.AssistantMessage>(
            assertNotNull(messageDao.updateMessageContent(testAssistantMessage.id, "edited by the user").getOrNull())
        )

        assertEquals("edited by the user", updated.content)
        assertEquals(AssistantMessageCompletionState.Completed, updated.toCompletionState())
        assertFalse(updated.showsIncompleteNotice, "Editing a message clears its notice")

        val readBack = assertIs<ChatMessage.AssistantMessage>(
            assertNotNull(messageDao.getMessageById(testAssistantMessage.id).getOrNull())
        )
        assertEquals(AssistantMessageCompletionState.Completed, readBack.toCompletionState())
    }

    @Test
    fun `updateMessageContent persists an explicit interrupted state with the partial content`() = runTest {
        prepareSession()
        val placeholder = insertAssistantMessage(
            content = "",
            completion = AssistantMessageCompletionState.InFlight
        )

        val updated = assertIs<ChatMessage.AssistantMessage>(
            assertNotNull(
                messageDao.updateMessageContent(
                    id = placeholder.id,
                    content = "partial answer",
                    completion = AssistantMessageCompletionState.InterruptedByUser
                ).getOrNull()
            )
        )

        assertEquals("partial answer", updated.content)
        assertFalse(updated.isComplete)
        assertEquals(AssistantMessageIncompleteCause.INTERRUPTED_BY_USER, updated.incompleteCause)
        assertNull(updated.errorCode, "A user interruption records no error code (D2)")
        assertNull(updated.errorMessage, "A user interruption records no reason (D2)")
        assertTrue(updated.showsIncompleteNotice)
    }

    @Test
    fun `updateMessageContent on a user message is a no-op for the assistant state`() = runTest {
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

        // Only assistant rows carry completion columns, so this must neither fail nor create assistant data.
        val updated = assertIs<ChatMessage.UserMessage>(
            assertNotNull(
                messageDao.updateMessageContent(
                    id = testUserMessage.id,
                    content = "user edited text",
                    completion = AssistantMessageCompletionState.InterruptedByUser
                ).getOrNull()
            )
        )

        assertEquals("user edited text", updated.content)
        assertTrue(
            assistantRowCount(updated.id) == 0L,
            "Updating a user message must not create an assistant_messages row"
        )
    }

    @Test
    fun `unknown stored enum names degrade to no cause instead of failing the session read`() = runTest {
        prepareSession()
        val inserted = insertAssistantMessage(content = "answer")

        // Simulate a foreign/corrupt write: the two enum columns hold values this build does not know.
        transactionScope.transaction {
            AssistantMessageTable.update({ AssistantMessageTable.messageId eq inserted.id }) {
                it[AssistantMessageTable.isComplete] = false
                it[AssistantMessageTable.incompleteCause] = "NOT_A_CAUSE"
                it[AssistantMessageTable.errorCode] = "NOT_A_CODE"
                it[AssistantMessageTable.errorMessage] = "leftover text"
            }
        }

        val readBack = assertIs<ChatMessage.AssistantMessage>(
            assertNotNull(messageDao.getMessageById(inserted.id).getOrNull())
        )
        assertFalse(readBack.isComplete)
        assertNull(readBack.incompleteCause, "An unknown cause name must degrade to null")
        assertNull(readBack.errorCode, "An unknown error code name must degrade to null")
        assertFalse(readBack.showsIncompleteNotice)
        assertEquals("leftover text", readBack.errorMessage, "A readable reason text is preserved")

        // The session-wide read must not throw either.
        val sessionMessages = messageDao.getMessagesBySessionId(testSession.id)
            .filterIsInstance<ChatMessage.AssistantMessage>()
        assertEquals(1, sessionMessages.size)
        assertNull(sessionMessages.single().incompleteCause)
    }

    /**
     * Inserts the test group/model/provider/settings and an empty session so assistant messages can be created.
     */
    private suspend fun prepareSession() {
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup),
                llmModels = listOf(testModel),
                llmProviders = listOf(testProvider),
                modelSettings = listOf(testSettings),
                chatSessions = listOf(testSession)
            )
        )
    }

    /**
     * Inserts a root assistant message into the prepared session.
     *
     * @param content Content of the message, empty for a placeholder.
     * @param completion Completion state to persist with the message; completed by default.
     * @return The inserted assistant message as returned by the DAO.
     */
    private suspend fun insertAssistantMessage(
        content: String = "answer",
        completion: AssistantMessageCompletionState = AssistantMessageCompletionState.Completed
    ): ChatMessage.AssistantMessage {
        val result = messageDao.insertMessage(
            sessionId = testSession.id,
            targetMessageId = null,
            position = MessageInsertPosition.APPEND,
            role = ChatMessage.Role.ASSISTANT,
            content = content,
            modelId = testModel.id,
            settingsId = testSettings.id,
            completion = completion
        )

        return assertIs<ChatMessage.AssistantMessage>(
            assertNotNull(result.getOrNull(), "Inserting an assistant message must succeed: ${result.leftOrNull()}")
        )
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

    /**
     * Projects the four completion fields of an assistant message into the write-side state carrier, so tests
     * can compare a persisted message against the expected state in one assertion.
     *
     * @receiver The assistant message read back from the database.
     * @return The completion state represented by that message.
     */
    private fun ChatMessage.AssistantMessage.toCompletionState(): AssistantMessageCompletionState =
        AssistantMessageCompletionState(
            isComplete = isComplete,
            incompleteCause = incompleteCause,
            errorCode = errorCode,
            errorMessage = errorMessage
        )
}

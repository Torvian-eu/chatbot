package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import eu.torvian.chatbot.common.models.core.UsageStats
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Verifies how the `usage_stats` column of an assistant message is written, read back and recovered from.
 *
 * The column is nullable JSON: an absent value means "no usage reported", a value the reader cannot decode also
 * reads as no usage instead of failing the session, and the content update always writes the value it was given.
 */
class MessageDaoExposedUsageStatsTest {
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

    /** Usage with every counter reported, so the optional fields are covered by the round trip. */
    private val fullUsage = UsageStats(
        inputTokens = 120,
        outputTokens = 30,
        totalTokens = 150,
        reasoningTokens = 12,
        cachedTokens = 8,
        cacheWriteTokens = 4
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
    fun `insertMessage stores the usage and mirrors it into the returned message`() = runTest {
        val inserted = insertAssistantMessageWithUsage(fullUsage)

        assertEquals(fullUsage, inserted.usageStats)
        assertEquals(fullUsage, readBack(inserted.id).usageStats)
        assertNotNull(storedUsageJson(inserted.id), "The usage must be stored on the assistant row")
    }

    @Test
    fun `insertMessage without usage stores no value`() = runTest {
        val inserted = insertAssistantMessageWithUsage(usageStats = null)

        assertNull(inserted.usageStats)
        assertNull(storedUsageJson(inserted.id))
        assertNull(readBack(inserted.id).usageStats)
    }

    @Test
    fun `updateMessageContent writes the usage it was given`() = runTest {
        val inserted = insertAssistantMessageWithUsage(usageStats = null)

        val updated = assertIs<ChatMessage.AssistantMessage>(
            assertNotNull(
                messageDao.updateMessageContent(
                    id = inserted.id,
                    content = "final content",
                    usageStats = fullUsage
                ).getOrNull()
            )
        )

        assertEquals(fullUsage, updated.usageStats)
        assertEquals(fullUsage, readBack(inserted.id).usageStats)
    }

    @Test
    fun `updateMessageContent clears the stored usage when given none`() = runTest {
        val inserted = insertAssistantMessageWithUsage(fullUsage)

        val updated = assertIs<ChatMessage.AssistantMessage>(
            assertNotNull(
                messageDao.updateMessageContent(
                    id = inserted.id,
                    content = "edited by the user",
                    clearReasoning = true,
                    usageStats = null
                ).getOrNull()
            )
        )

        assertNull(updated.usageStats, "The returned message must reflect the cleared column")
        assertNull(storedUsageJson(inserted.id), "The stored usage must be gone")
        assertNull(readBack(inserted.id).usageStats)
    }

    @Test
    fun `a value this version cannot decode reads as no usage`() = runTest {
        val inserted = insertAssistantMessageWithUsage(fullUsage)
        storeRawUsageJson(inserted.id, "{\"inputTokens\":\"not-a-number\"}")

        assertNull(readBack(inserted.id).usageStats, "A corrupt value must degrade to \"no usage\"")
    }

    @Test
    fun `a blank stored value reads as no usage`() = runTest {
        val inserted = insertAssistantMessageWithUsage(fullUsage)
        storeRawUsageJson(inserted.id, "   ")

        assertNull(readBack(inserted.id).usageStats)
    }

    /**
     * Inserts a root assistant message with the given usage into the prepared session.
     *
     * @param usageStats Usage to store, or `null` when the message has none.
     * @return The inserted assistant message as returned by the DAO.
     */
    private suspend fun insertAssistantMessageWithUsage(
        usageStats: UsageStats?
    ): ChatMessage.AssistantMessage {
        val result = messageDao.insertMessage(
            sessionId = testSession.id,
            targetMessageId = null,
            position = MessageInsertPosition.APPEND,
            role = ChatMessage.Role.ASSISTANT,
            content = "answer",
            modelId = testModel.id,
            settingsId = testSettings.id,
            usageStats = usageStats
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
     * Reads the raw usage column of an assistant row.
     *
     * @param messageId Id of the chat message whose assistant row is looked up.
     * @return The stored JSON text, or `null` when nothing is stored.
     */
    private suspend fun storedUsageJson(messageId: Long): String? =
        transactionScope.transaction {
            AssistantMessageTable
                .selectAll()
                .where { AssistantMessageTable.messageId eq messageId }
                .singleOrNull()
                ?.get(AssistantMessageTable.usageStatsJson)
        }

    /**
     * Overwrites the raw usage column of an assistant row, simulating a foreign or corrupted value.
     *
     * @param messageId Id of the chat message whose assistant row is written.
     * @param rawJson Text to store in the usage column.
     */
    private suspend fun storeRawUsageJson(messageId: Long, rawJson: String) {
        transactionScope.transaction {
            AssistantMessageTable.update({ AssistantMessageTable.messageId eq messageId }) {
                it[AssistantMessageTable.usageStatsJson] = rawJson
            }
        }
    }
}

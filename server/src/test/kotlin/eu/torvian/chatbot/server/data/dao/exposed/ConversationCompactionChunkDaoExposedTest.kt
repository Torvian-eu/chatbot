package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.server.data.dao.ConversationCompactionChunkDao
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.error.ConversationCompactionChunkDaoError
import eu.torvian.chatbot.server.data.tables.ChatSessionTable
import eu.torvian.chatbot.server.data.tables.LLMProviderTable
import eu.torvian.chatbot.server.service.core.chat.compaction.CompactedMessageCoverage
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionChunkCandidate
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Verifies atomic verified chunk persistence and immutable coverage behavior.
 *
 * The DAO transaction re-verifies the source chain (session membership, exact timestamps, ordered
 * parent links, final leaf) before inserting the chunk and its coverage rows; any mismatch rolls back
 * the whole write. Rows are retained even when superseded, message deletion leaves the immutable
 * coverage snapshot, and session deletion cascades both tables.
 */
class ConversationCompactionChunkDaoExposedTest {

    private lateinit var container: DIContainer
    private lateinit var testDataManager: TestDataManager
    private lateinit var dao: ConversationCompactionChunkDao
    private lateinit var messageDao: MessageDao
    private lateinit var transactionScope: TransactionScope

    private val session = TestDefaults.chatSession1.copy(agentRoleId = null, groupId = null)
    private val t2 = TestDefaults.DEFAULT_INSTANT + 2.milliseconds

    private val m1 =
        TestDefaults.chatMessage1.copy(sessionId = session.id, parentMessageId = null, childrenMessageIds = listOf(2L))
    private val m2 =
        TestDefaults.chatMessage2.copy(sessionId = session.id, parentMessageId = 1L, childrenMessageIds = listOf(3L))
    private val m3 = ChatMessage.UserMessage(
        id = 3L,
        sessionId = session.id,
        content = "Third message",
        createdAt = t2,
        updatedAt = t2,
        parentMessageId = 2L,
        childrenMessageIds = emptyList()
    )

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        testDataManager = container.get()
        dao = container.get()
        messageDao = container.get()
        transactionScope = container.get()

        testDataManager.createTables(
            setOf(
                Table.CHAT_GROUPS,
                Table.LLM_PROVIDERS,
                Table.LLM_MODELS,
                Table.MODEL_SETTINGS,
                Table.CHAT_SESSIONS,
                Table.CHAT_MESSAGES,
                Table.ASSISTANT_MESSAGES,
                Table.CONVERSATION_COMPACTION_CHUNKS,
                Table.CONVERSATION_COMPACTION_CHUNK_MESSAGES
            )
        )
        testDataManager.insertLLMProvider(TestDefaults.llmProvider1)
        testDataManager.insertLLMModel(TestDefaults.llmModel1)
        testDataManager.insertModelSettings(TestDefaults.modelSettings1)
        testDataManager.insertChatSession(session)
        testDataManager.insertChatMessage(m1)
        testDataManager.insertChatMessage(m2)
        testDataManager.insertChatMessage(m3)
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    /**
     * Builds a candidate covering the full [m1, m2, m3] chain with the given createdAt.
     */
    private fun candidateOf(createdAt: Long, sessionId: Long = session.id): ConversationCompactionChunkCandidate =
        ConversationCompactionChunkCandidate(
            sessionId = sessionId,
            summary = "summary-$createdAt",
            modelId = TestDefaults.llmModel1.id,
            settingsId = TestDefaults.modelSettings1.id,
            providerId = TestDefaults.llmProvider1.id,
            modelName = TestDefaults.llmModel1.name,
            settingsName = TestDefaults.modelSettings1.name,
            providerName = TestDefaults.llmProvider1.name,
            instruction = "Summarize faithfully",
            thresholdTokens = 100_000L,
            sourceTokenCount = 9_000L,
            resultTokenCount = 90L,
            tokenCounterVersion = "approx_utf16_json_v1",
            createdAt = createdAt,
            coverage = listOf(
                CompactedMessageCoverage(0, m1.id, m1.updatedAt),
                CompactedMessageCoverage(1, m2.id, m2.updatedAt),
                CompactedMessageCoverage(2, m3.id, m3.updatedAt)
            )
        )

    @Test
    fun `verified insert persists chunk with ordered coverage`() = runTest {
        val result = dao.insertVerifiedChunk(candidateOf(createdAt = 5_000L), expectedLeafMessageId = m3.id)
        val chunk = result.getOrNull()
        assertNotNull(chunk, "Expected successful verified insert")
        assertEquals(session.id, chunk.sessionId)
        assertEquals(listOf(1L, 2L, 3L), chunk.coverage.map { it.messageId })
        assertEquals(listOf(0, 1, 2), chunk.coverage.map { it.ordinal })

        val loaded = dao.getChunksBySessionId(session.id)
        assertEquals(1, loaded.size)
        assertEquals(chunk.id, loaded.single().id)
        assertEquals(listOf(1L, 2L, 3L), loaded.single().coverage.map { it.messageId })
    }

    @Test
    fun `superseding chunks are retained and both are readable`() = runTest {
        dao.insertVerifiedChunk(candidateOf(createdAt = 5_000L), expectedLeafMessageId = m3.id)
        dao.insertVerifiedChunk(candidateOf(createdAt = 6_000L), expectedLeafMessageId = m3.id)

        val loaded = dao.getChunksBySessionId(session.id)
        assertEquals(2, loaded.size)
        assertEquals(listOf(6_000L, 5_000L), loaded.map { it.createdAt })
    }

    @Test
    fun `changed timestamp is rejected and rolls back the whole insert`() = runTest {
        val stale = candidateOf(createdAt = 5_000L).let {
            it.copy(coverage = it.coverage.map { covered ->
                if (covered.messageId == m2.id) covered.copy(observedUpdatedAt = covered.observedUpdatedAt + 99.milliseconds) else covered
            })
        }
        val result = dao.insertVerifiedChunk(stale, expectedLeafMessageId = m3.id)
        assertIs<ConversationCompactionChunkDaoError.SourceVerificationFailed>(result.leftOrNull())
        assertTrue(
            dao.getChunksBySessionId(session.id).isEmpty(),
            "No chunk may be persisted after a stale-source rejection"
        )
    }

    @Test
    fun `cross-session coverage is rejected`() = runTest {
        val otherSession = TestDefaults.chatSession2
        val result = dao.insertVerifiedChunk(
            candidateOf(createdAt = 5_000L, sessionId = otherSession.id),
            expectedLeafMessageId = m3.id
        )
        assertIs<ConversationCompactionChunkDaoError.SourceVerificationFailed>(result.leftOrNull())
        assertTrue(dao.getChunksBySessionId(session.id).isEmpty())
    }

    @Test
    fun `leaf mismatch is rejected`() = runTest {
        val result = dao.insertVerifiedChunk(candidateOf(createdAt = 5_000L), expectedLeafMessageId = 999L)
        assertIs<ConversationCompactionChunkDaoError.SourceVerificationFailed>(result.leftOrNull())
        assertTrue(dao.getChunksBySessionId(session.id).isEmpty())
    }

    @Test
    fun `broken parent chain is rejected`() = runTest {
        val broken = candidateOf(createdAt = 5_000L).let {
            it.copy(coverage = it.coverage.reversed())
        }
        val result = dao.insertVerifiedChunk(broken, expectedLeafMessageId = m1.id)
        assertIs<ConversationCompactionChunkDaoError.SourceVerificationFailed>(result.leftOrNull())
        assertTrue(dao.getChunksBySessionId(session.id).isEmpty())
    }

    @Test
    fun `empty coverage is rejected defensively`() = runTest {
        val empty = candidateOf(createdAt = 5_000L).copy(coverage = emptyList())
        val result = dao.insertVerifiedChunk(empty, expectedLeafMessageId = m3.id)
        assertIs<ConversationCompactionChunkDaoError.SourceVerificationFailed>(result.leftOrNull())
        assertTrue(dao.getChunksBySessionId(session.id).isEmpty())
    }

    @Test
    fun `message deletion leaves the immutable coverage snapshot intact`() = runTest {
        dao.insertVerifiedChunk(candidateOf(createdAt = 5_000L), expectedLeafMessageId = m3.id)

        // Deleting a covered message must not cascade into the coverage table (no FK on message_id)
        // and must not be blocked by it either.
        messageDao.deleteMessageRecursively(m3.id)

        val loaded = dao.getChunksBySessionId(session.id)
        assertEquals(1, loaded.size)
        assertEquals(listOf(1L, 2L, 3L), loaded.single().coverage.map { it.messageId })
        // The coverage snapshot survives even though the message row is gone (eligibility rejects it).
        assertEquals(3, loaded.single().coverageCount)
    }

    @Test
    fun `session deletion cascades chunks and coverage`() = runTest {
        dao.insertVerifiedChunk(candidateOf(createdAt = 5_000L), expectedLeafMessageId = m3.id)

        // Deleting the session cascades chat_messages and conversation_compaction_chunks (and their
        // coverage rows) through the FK chain.
        transactionScope.transaction {
            ChatSessionTable.deleteWhere { ChatSessionTable.id eq session.id }
        }

        assertTrue(dao.getChunksBySessionId(session.id).isEmpty(), "Chunks must cascade with the session")
    }

    @Test
    fun `nullable provenance ids survive referenced row deletion while snapshots remain`() = runTest {
        val chunk = dao.insertVerifiedChunk(candidateOf(createdAt = 5_000L), expectedLeafMessageId = m3.id).getOrNull()
        assertNotNull(chunk)

        // Deleting the provider cascades its model and that model's settings; the chunk FK columns
        // become null (ON DELETE SET NULL) while the immutable *_name snapshots survive.
        transactionScope.transaction {
            LLMProviderTable.deleteWhere { LLMProviderTable.id eq TestDefaults.llmProvider1.id }
        }

        val loaded = dao.getChunksBySessionId(session.id).single()
        assertEquals(null, loaded.modelId)
        assertEquals(null, loaded.settingsId)
        assertEquals(null, loaded.providerId)
        assertEquals(TestDefaults.llmModel1.name, loaded.modelName)
        assertEquals(TestDefaults.modelSettings1.name, loaded.settingsName)
        assertEquals(TestDefaults.llmProvider1.name, loaded.providerName)
    }
}

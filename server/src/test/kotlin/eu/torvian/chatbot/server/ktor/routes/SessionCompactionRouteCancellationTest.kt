package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.resources.SessionResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.api.core.CompactionEvent
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import eu.torvian.chatbot.common.models.api.me.PreferenceKeys
import eu.torvian.chatbot.server.data.dao.ConversationCompactionChunkDao
import eu.torvian.chatbot.server.data.dao.UserPreferenceDao
import eu.torvian.chatbot.server.data.entities.SessionCurrentLeafEntity
import eu.torvian.chatbot.server.testutils.auth.TestAuthHelper
import eu.torvian.chatbot.server.testutils.auth.authenticate
import eu.torvian.chatbot.server.testutils.auth.offerWebSocketAuthSubprotocolMarker
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import eu.torvian.chatbot.server.testutils.ktor.KtorTestApp
import eu.torvian.chatbot.server.testutils.ktor.myTestApplication
import eu.torvian.chatbot.server.testutils.llm.BlockingLLMApiClient
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies the abort path of the compaction socket over a real socket and a real route.
 *
 * The application under test is the production session routing with a controllable LLM client: the
 * auxiliary compaction call suspends until it is cancelled, so closing the client socket mid-compaction
 * is a deterministic event rather than a race against an instant stub. The assertions go through the
 * wire (no outcome frame) and through the database (no chunk and no coverage row).
 */
class SessionCompactionRouteCancellationTest {

    private lateinit var container: DIContainer
    private lateinit var sessionTestApplication: KtorTestApp
    private lateinit var testDataManager: TestDataManager
    private lateinit var authHelper: TestAuthHelper
    private lateinit var userPreferenceDao: UserPreferenceDao
    private lateinit var chunkDao: ConversationCompactionChunkDao
    private lateinit var authToken: String

    private val json = Json

    /** Auxiliary client that never returns until cancelled; the fixture for the abort assertions. */
    private val blockingClient = BlockingLLMApiClient()

    private val session = TestDefaults.chatSession1.copy(id = 5L, groupId = null, agentRoleId = 1L)
    private val role = TestDefaults.agentRole1.copy(id = 1L, modelPresetId = TestDefaults.modelPreset1.id)
    private val preset = TestDefaults.modelPreset1

    /** First message of the compactable thread, deliberately long enough to be reducible. */
    private val firstMessage = TestDefaults.chatMessage1.copy(
        id = 31L,
        sessionId = session.id,
        content = "First long message. ".repeat(60),
        childrenMessageIds = listOf(32L)
    )

    /** Leaf message of the compactable thread. */
    private val leafMessage = TestDefaults.chatMessage2.copy(
        id = 32L,
        sessionId = session.id,
        parentMessageId = 31L,
        content = "Second long message. ".repeat(60),
        childrenMessageIds = emptyList()
    )

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer(llmApiClient = blockingClient)
        val apiRoutesKtor: ApiRoutesKtor = container.get()
        sessionTestApplication = myTestApplication(
            container = container,
            routing = { apiRoutesKtor.configureSessionRoutes(this) }
        )
        testDataManager = container.get()
        userPreferenceDao = container.get()
        chunkDao = container.get()

        testDataManager.setup(
            dataSet = TestDataSet(
                apiSecrets = listOf(TestDefaults.apiSecret1),
                llmProviders = listOf(TestDefaults.llmProvider1),
                llmModels = listOf(TestDefaults.llmModel1),
                modelSettings = listOf(TestDefaults.modelSettings1),
                agentRoles = listOf(role),
                modelPresets = listOf(preset)
            )
        )
        testDataManager.createTables(
            setOf(
                Table.USERS,
                Table.USER_DEVICES,
                Table.USER_PREFERENCES,
                Table.CHAT_SESSIONS,
                Table.CHAT_MESSAGES,
                Table.ASSISTANT_MESSAGES,
                Table.SESSION_CURRENT_LEAF,
                Table.TOOL_CALLS,
                Table.INSTRUCTIONS,
                Table.INSTRUCTION_OWNERS,
                Table.AGENT_ROLE_INSTRUCTIONS,
                Table.USER_SESSIONS,
                Table.CHAT_SESSION_OWNERS,
                Table.LLM_MODEL_OWNERS,
                Table.MODEL_SETTINGS_OWNERS,
                Table.AGENT_ROLES,
                Table.AGENT_ROLE_OWNERS,
                Table.ROLES,
                Table.USER_ROLE_ASSIGNMENTS,
                Table.MODEL_PRESETS,
                Table.MODEL_PRESET_OWNERS,
                Table.CONVERSATION_COMPACTION_CHUNKS,
                Table.CONVERSATION_COMPACTION_CHUNK_MESSAGES
            )
        )

        authHelper = TestAuthHelper(container)
        authToken = authHelper.createUserAndGetToken()

        testDataManager.insertModelPresetOwnership(preset.id, authHelper.defaultTestUser.id)
        seedCompactableSession()
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `closing the compaction socket mid-compaction aborts the call and persists no chunk`() =
        sessionTestApplication {
            // Act
            val receivedEvents = mutableListOf<CompactionEvent>()
            client.webSocket(
                urlString = href(
                    SessionResource.ById.Compaction(parent = SessionResource.ById(sessionId = session.id))
                ),
                request = {
                    authenticate(authToken)
                    offerWebSocketAuthSubprotocolMarker()
                }
            ) {
                val collector = launch {
                    for (frame in incoming) {
                        val textFrame = frame as? Frame.Text ?: continue
                        receivedEvents.add(json.decodeFromString(CompactionEvent.serializer(), textFrame.readText()))
                    }
                }

                // The auxiliary call is in flight: the compaction is suspended and nothing is persisted.
                withTimeout(10.seconds) { blockingClient.started.await() }
                assertTrue(chunkDao.getChunksBySessionId(session.id).isEmpty())

                // The user stops the compaction: the client closes the socket, which is the whole request.
                close(CloseReason(CloseReason.Codes.NORMAL, "Compaction cancelled by the user"))

                // The server must observe the close and abort the auxiliary call.
                withTimeout(10.seconds) { blockingClient.cancelled.await() }
                withTimeout(10.seconds) { collector.join() }
            }

            // Assert: the aborted operation is invisible — no outcome frame and no persisted chunk.
            assertTrue(
                receivedEvents.isEmpty(),
                "A cancelled compaction must not report an outcome, received: $receivedEvents"
            )
            assertTrue(
                chunkDao.getChunksBySessionId(session.id).isEmpty(),
                "A cancelled compaction must not persist a chunk"
            )
            assertEquals(
                listOf(firstMessage.id, leafMessage.id),
                testDataManager.getChatMessagesForSession(session.id).map { it.id },
                "The transcript is untouched by a cancelled compaction"
            )
        }

    /**
     * Seeds a compaction-ready session: ownership, the role's preset ownership, the long thread and the
     * global compaction preference the forced path resolves.
     */
    private suspend fun seedCompactableSession() {
        testDataManager.insertChatSession(session)
        testDataManager.insertSessionOwnership(session.id, authHelper.defaultTestUser.id)
        testDataManager.insertAgentRoleOwnership(role.id, authHelper.defaultTestUser.id)
        testDataManager.insertChatMessage(firstMessage)
        testDataManager.insertChatMessage(leafMessage)
        testDataManager.insertSessionCurrentLeaf(
            SessionCurrentLeafEntity(sessionId = session.id, messageId = leafMessage.id)
        )
        userPreferenceDao.upsertPreference(
            userId = authHelper.defaultTestUser.id,
            internalDeviceId = null,
            clientDeviceId = null,
            key = PreferenceKeys.CONVERSATION_COMPACTION,
            value = json.encodeToString(
                ConversationCompactionPreference.serializer(),
                ConversationCompactionPreference(
                    modelId = TestDefaults.llmModel1.id,
                    settingsId = TestDefaults.modelSettings1.id,
                    instruction = "Summarize faithfully",
                    thresholdTokens = 50_000L
                )
            )
        )
    }
}

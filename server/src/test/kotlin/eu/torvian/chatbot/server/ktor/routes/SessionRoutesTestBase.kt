package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.server.data.dao.UserPreferenceDao
import eu.torvian.chatbot.server.testutils.auth.TestAuthHelper
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import eu.torvian.chatbot.server.testutils.ktor.KtorTestApp
import eu.torvian.chatbot.server.testutils.ktor.myTestApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * Shared harness for the session route integration tests.
 *
 * Each subclass inherits the same test container, session route application, seeded fixtures and
 * authenticated user so the per-subject test classes only differ in the endpoints they exercise.
 */
abstract class SessionRoutesTestBase {
    protected lateinit var container: DIContainer
    protected lateinit var sessionTestApplication: KtorTestApp
    protected lateinit var testDataManager: TestDataManager
    protected lateinit var authHelper: TestAuthHelper
    protected lateinit var userPreferenceDao: UserPreferenceDao
    protected lateinit var authToken: String
    protected val json = Json

    // Test data
    protected val testGroup = TestDefaults.chatGroup1.copy(id = 1L)
    protected val testGroup2 = TestDefaults.chatGroup2.copy(id = 2L)
    protected val testModel = TestDefaults.llmModel1.copy(id = 1L)
    protected val testModel2 = TestDefaults.llmModel2.copy(id = 2L)
    protected val testSettings = TestDefaults.modelSettings1.copy(id = 1L)
    protected val testSettings2 = TestDefaults.modelSettings2.copy(id = 2L)

    // Create additional settings that belong to the same model as testSession for testing
    protected val testSettings3 = TestDefaults.modelSettings1.copy(
        id = 3L,
        modelId = testModel.id, // Same model as testSession
        name = "Alternative Settings for Model 1"
    )

    // Create non-streaming settings for testing non-streaming message processing
    protected val testNonStreamingSettings = TestDefaults.modelSettings1.copy(
        id = 4L,
        modelId = testModel.id,
        name = "Non-Streaming Settings for Model 1",
        stream = false
    )
    // Model presets: the sole source of truth for a role's model/settings configuration, so every
    // role fixture below references one instead of carrying the two ids directly.
    protected val testPreset = TestDefaults.modelPreset1
    protected val testPreset2 = TestDefaults.modelPreset2
    protected val testNonStreamingPreset = TestDefaults.modelPreset1.copy(
        id = 3L,
        name = "Non-Streaming Preset",
        modelSettingsId = testNonStreamingSettings.id
    )
    protected val testAgentRole = TestDefaults.agentRole1.copy(
        id = 1L,
        name = "Test Agent Role",
        modelPresetId = testPreset.id
    )
    protected val testAgentRole2 = TestDefaults.agentRole2.copy(
        id = 2L,
        name = "Test Agent Role 2",
        modelPresetId = testPreset2.id
    )
    protected val testNonStreamingAgentRole = TestDefaults.agentRole1.copy(
        id = 3L,
        name = "Non-Streaming Agent Role",
        modelPresetId = testNonStreamingPreset.id
    )
    protected val testSession = TestDefaults.chatSession1.copy(
        id = 1L,
        name = "Test Session",
        groupId = testGroup.id,
        agentRoleId = testAgentRole.id
    )
    protected val testSession2 = TestDefaults.chatSession2.copy(
        id = 2L,
        name = "Test Session 2",
        groupId = testGroup.id,
        agentRoleId = testAgentRole.id
    )

    // Create a test session configured for non-streaming
    protected val testNonStreamingSession = TestDefaults.chatSession1.copy(
        id = 3L,
        name = "Non-Streaming Test Session",
        groupId = testGroup.id,
        agentRoleId = testNonStreamingAgentRole.id
    )
    protected val testUserMessage = TestDefaults.chatMessage1.copy(
        id = 1L,
        sessionId = testSession.id
    )
    protected val testAssistantMessage = TestDefaults.chatMessage2.copy(
        id = 2L,
        sessionId = testSession.id,
        parentMessageId = testUserMessage.id
    )

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        val apiRoutesKtor: ApiRoutesKtor = container.get()

        sessionTestApplication = myTestApplication(
            container = container,
            routing = {
                apiRoutesKtor.configureSessionRoutes(this)
            }
        )

        testDataManager = container.get()
        userPreferenceDao = container.get()
        // Setup required tables and test data
        testDataManager.setup(
            dataSet = TestDataSet(
                apiSecrets = listOf(TestDefaults.apiSecret1),
                chatGroups = listOf(testGroup, testGroup2),
                llmProviders = listOf(TestDefaults.llmProvider1, TestDefaults.llmProvider2),
                llmModels = listOf(testModel, testModel2),
                modelSettings = listOf(testSettings, testSettings2, testSettings3, testNonStreamingSettings),
                agentRoles = listOf(testAgentRole, testAgentRole2, testNonStreamingAgentRole),
                modelPresets = listOf(testPreset, testPreset2, testNonStreamingPreset)
            )
        )
        testDataManager.createTables(
            setOf(
                Table.CHAT_SESSIONS,
                Table.CHAT_MESSAGES,
                Table.ASSISTANT_MESSAGES,
                Table.SESSION_CURRENT_LEAF,
                Table.TOOL_CALLS,
                // Role reads resolve the role's shareable instructions through these.
                Table.INSTRUCTIONS,
                Table.INSTRUCTION_OWNERS,
                Table.AGENT_ROLE_INSTRUCTIONS,
                Table.USERS,
                Table.USER_DEVICES,
                Table.USER_PREFERENCES,
                Table.ROLES, Table.USER_ROLE_ASSIGNMENTS,
                Table.USER_SESSIONS,
                Table.CHAT_SESSION_OWNERS,
                Table.CHAT_GROUP_OWNERS,
                Table.LLM_MODEL_OWNERS,
                Table.MODEL_SETTINGS_OWNERS,
                Table.AGENT_ROLES,
                Table.AGENT_ROLE_OWNERS,
                // The compaction socket persists verified chunks and their coverage rows.
                Table.CONVERSATION_COMPACTION_CHUNKS,
                Table.CONVERSATION_COMPACTION_CHUNK_MESSAGES
            )
        )

        // Set up authentication
        authHelper = TestAuthHelper(container)
        authToken = authHelper.createUserAndGetToken()

        // Turn preparation resolves the role's model preset through its ownership link (presets are
        // per-owner resources), so the seeded presets must be owned by the authenticated user.
        testDataManager.insertModelPresetOwnership(testPreset.id, authHelper.defaultTestUser.id)
        testDataManager.insertModelPresetOwnership(testPreset2.id, authHelper.defaultTestUser.id)
        testDataManager.insertModelPresetOwnership(testNonStreamingPreset.id, authHelper.defaultTestUser.id)
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }
}

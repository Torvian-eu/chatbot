package eu.torvian.chatbot.server.service.core.chat.preparation

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMModelCapabilities
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.error.MessageError
import eu.torvian.chatbot.server.data.dao.error.SessionError
import eu.torvian.chatbot.server.service.core.AgentRoleService
import eu.torvian.chatbot.server.service.core.LLMModelService
import eu.torvian.chatbot.server.service.core.LLMProviderService
import eu.torvian.chatbot.server.service.core.ModelSettingsService
import eu.torvian.chatbot.server.service.core.ToolService
import eu.torvian.chatbot.server.service.core.agent.AgentRole
import eu.torvian.chatbot.server.service.core.agent.CustomInstruction
import eu.torvian.chatbot.server.service.core.agent.SystemPromptComposer
import eu.torvian.chatbot.server.service.core.error.agent.AgentRoleError
import eu.torvian.chatbot.server.service.core.error.message.ValidateNewMessageError
import eu.torvian.chatbot.server.service.security.CredentialManager
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Verifies that [DefaultConversationTurnPreparationService] resolves model, settings, tools and the
 * composed system prompt from the session's selected agent role.
 */
class DefaultConversationTurnPreparationServiceTest {
    private lateinit var messageDao: MessageDao
    private lateinit var sessionDao: SessionDao
    private lateinit var llmModelService: LLMModelService
    private lateinit var modelSettingsService: ModelSettingsService
    private lateinit var llmProviderService: LLMProviderService
    private lateinit var credentialManager: CredentialManager
    private lateinit var toolService: ToolService
    private lateinit var agentRoleService: AgentRoleService
    private lateinit var systemPromptComposer: SystemPromptComposer
    private lateinit var transactionScope: TransactionScope
    private lateinit var preparationService: DefaultConversationTurnPreparationService

    private val testSession = ChatSession(
        id = 1L,
        name = "Test Session",
        createdAt = Instant.fromEpochMilliseconds(1234567890000L),
        updatedAt = Instant.fromEpochMilliseconds(1234567890000L),
        groupId = null,
        agentRoleId = 1L,
        currentLeafMessageId = null,
        messages = emptyList()
    )

    private val testModel = LLMModel(
        id = 1L,
        name = "gpt-3.5-turbo",
        providerId = 1L,
        active = true,
        displayName = "GPT-3.5 Turbo"
    )

    private val toolCallingModel = testModel.copy(
        capabilities = buildJsonObject {
            put(LLMModelCapabilities.TOOL_CALLING, JsonPrimitive(true))
        }
    )

    private val testProvider = LLMProvider(
        id = 1L,
        apiKeyId = "test-key-id",
        name = "OpenAI",
        description = "OpenAI Provider",
        baseUrl = "https://api.openai.com/v1",
        type = LLMProviderType.OPENAI
    )

    private val testSettings = ChatModelSettings(
        id = 1L,
        name = "Default",
        modelId = 1L,
        temperature = 0.7f,
        maxTokens = 1000,
        customParams = null,
        stream = false
    )

    private val testRole = AgentRole(
        id = 1L,
        name = "Test Role",
        modelId = testModel.id,
        modelSettingsId = testSettings.id,
        tools = emptySet(),
        instructions = emptyList()
    )

    /**
     * Recreates the collaborator with fresh mocks for each test.
     */
    @BeforeEach
    fun setUp() {
        messageDao = mockk()
        sessionDao = mockk()
        llmModelService = mockk()
        modelSettingsService = mockk()
        llmProviderService = mockk()
        credentialManager = mockk()
        toolService = mockk()
        agentRoleService = mockk()
        systemPromptComposer = mockk()
        transactionScope = mockk()
        preparationService = DefaultConversationTurnPreparationService(
            messageDao = messageDao,
            sessionDao = sessionDao,
            toolService = toolService,
            llmModelService = llmModelService,
            modelSettingsService = modelSettingsService,
            llmProviderService = llmProviderService,
            credentialManager = credentialManager,
            agentRoleService = agentRoleService,
            systemPromptComposer = systemPromptComposer,
            transactionScope = transactionScope
        )

        coEvery { transactionScope.transaction(any<suspend () -> Any?>()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = invocation.args[0] as suspend () -> Any?
            block()
        }
    }

    /**
     * Clears mocks after each test run.
     */
    @AfterEach
    fun tearDown() {
        clearMocks(
            messageDao, sessionDao, llmModelService, modelSettingsService,
            llmProviderService, credentialManager, toolService, agentRoleService,
            systemPromptComposer, transactionScope
        )
    }

    /**
     * Verifies branch-and-continue requests still reject a missing parent anchor.
     */
    @Test
    fun `prepareNewMessageTurn should return ModelConfigurationError when content is null and parentMessageId is null`() =
        runTest {
            val result = preparationService.prepareNewMessageTurn(1L, null, null, false)

            assertTrue(result.isLeft())
            val error = result.leftOrNull()
            assertNotNull(error)
            assertIs<ValidateNewMessageError.ModelConfigurationError>(error)
            assertTrue(error.message.contains("Branch & Continue"))
            coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        }

    /**
     * Verifies missing sessions keep the existing error mapping.
     */
    @Test
    fun `prepareNewMessageTurn should return SessionNotFound when session does not exist`() = runTest {
        val sessionId = 999L
        coEvery { sessionDao.getSessionById(sessionId) } returns SessionError.SessionNotFound(sessionId).left()

        val result = preparationService.prepareNewMessageTurn(sessionId, "test content", null, false)

        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertNotNull(error)
        assertIs<ValidateNewMessageError.SessionNotFound>(error)
        assertEquals(sessionId, error.sessionId)
        coVerify(exactly = 1) { sessionDao.getSessionById(sessionId) }
    }

    /**
     * Verifies a session without a selected agent role cannot be prepared.
     */
    @Test
    fun `prepareNewMessageTurn should return ModelConfigurationError when no agent role is selected`() = runTest {
        val sessionId = 1L
        coEvery { sessionDao.getSessionById(sessionId) } returns testSession.copy(agentRoleId = null).right()

        val result = preparationService.prepareNewMessageTurn(sessionId, "test content", null, false)

        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertNotNull(error)
        assertIs<ValidateNewMessageError.ModelConfigurationError>(error)
        assertEquals("No agent role selected for session $sessionId", error.message)
    }

    /**
     * Verifies a role whose referenced model was deleted (model_id null) cannot be prepared.
     */
    @Test
    fun `prepareNewMessageTurn should return ModelConfigurationError when role references a deleted model`() = runTest {
        val sessionId = 1L
        coEvery { sessionDao.getSessionById(sessionId) } returns testSession.right()
        coEvery { agentRoleService.getAgentRoleById(testSession.agentRoleId!!) } returns testRole.copy(modelId = null).right()

        val result = preparationService.prepareNewMessageTurn(sessionId, "test content", null, false)

        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertNotNull(error)
        assertIs<ValidateNewMessageError.ModelConfigurationError>(error)
        assertTrue(error.message.contains("deleted model"))
    }

    /**
     * Verifies a role whose referenced settings were deleted (model_settings_id null) cannot be prepared.
     */
    @Test
    fun `prepareNewMessageTurn should return ModelConfigurationError when role references deleted settings`() = runTest {
        val sessionId = 1L
        coEvery { sessionDao.getSessionById(sessionId) } returns testSession.right()
        coEvery {
            agentRoleService.getAgentRoleById(testSession.agentRoleId!!)
        } returns testRole.copy(modelSettingsId = null).right()

        val result = preparationService.prepareNewMessageTurn(sessionId, "test content", null, false)

        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertNotNull(error)
        assertIs<ValidateNewMessageError.ModelConfigurationError>(error)
        assertTrue(error.message.contains("deleted settings"))
    }

    /**
     * Verifies parent lookup failures keep mapping to the existing session-scoped parent error.
     */
    @Test
    fun `prepareNewMessageTurn should return ParentNotInSession when parent message does not exist`() = runTest {
        val sessionId = 1L
        val parentMessageId = 999L
        coEvery { sessionDao.getSessionById(sessionId) } returns testSession.right()
        coEvery { messageDao.getMessageById(parentMessageId) } returns MessageError.MessageNotFound(parentMessageId).left()

        val result = preparationService.prepareNewMessageTurn(sessionId, "test content", parentMessageId, false)

        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertNotNull(error)
        assertIs<ValidateNewMessageError.ParentNotInSession>(error)
        assertEquals(sessionId, error.sessionId)
        assertEquals(parentMessageId, error.parentId)
        coVerify(exactly = 1) { messageDao.getMessageById(parentMessageId) }
    }

    /**
     * Verifies a role whose referenced role no longer exists cannot be prepared.
     */
    @Test
    fun `prepareNewMessageTurn should return ModelConfigurationError when role does not exist`() = runTest {
        val sessionId = 1L
        coEvery { sessionDao.getSessionById(sessionId) } returns testSession.right()
        coEvery {
            agentRoleService.getAgentRoleById(testSession.agentRoleId!!)
        } returns AgentRoleError.NotFound(testSession.agentRoleId!!).left()

        val result = preparationService.prepareNewMessageTurn(sessionId, "test content", null, false)

        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertNotNull(error)
        assertIs<ValidateNewMessageError.ModelConfigurationError>(error)
        assertTrue(error.message.contains("no longer exists"))
    }

    /**
     * Verifies successful preparation assembles the session, LLM runtime configuration and the composed
     * system message from the role.
     */
    @Test
    fun `prepareNewMessageTurn should return prepared turn when validation succeeds`() = runTest {
        val sessionId = 1L
        val streamingSettings = testSettings.copy(stream = true)
        val roleWithInstructions = testRole.copy(
            modelSettingsId = streamingSettings.id,
            instructions = listOf(CustomInstruction("Role", "You are a senior architect."))
        )
        coEvery { sessionDao.getSessionById(sessionId) } returns testSession.right()
        coEvery { agentRoleService.getAgentRoleById(testSession.agentRoleId!!) } returns roleWithInstructions.right()
        coEvery { systemPromptComposer.compose(roleWithInstructions) } returns "composed system"
        coEvery { llmModelService.getModelById(testModel.id) } returns testModel.right()
        coEvery { modelSettingsService.getSettingsById(streamingSettings.id) } returns streamingSettings.right()
        coEvery { llmProviderService.getProviderById(testModel.providerId) } returns testProvider.right()
        coEvery { credentialManager.getCredential(testProvider.apiKeyId!!) } returns "test-api-key".right()

        val result = preparationService.prepareNewMessageTurn(sessionId, "test content", null, true)

        assertTrue(result.isRight())
        val preparedTurn = result.getOrNull()
        assertNotNull(preparedTurn)
        assertEquals(testSession, preparedTurn.session)
        assertEquals(testProvider, preparedTurn.llmConfig.provider)
        assertEquals(testModel, preparedTurn.llmConfig.model)
        assertEquals(streamingSettings, preparedTurn.llmConfig.settings)
        assertEquals("test-api-key", preparedTurn.llmConfig.apiKey)
        assertEquals("composed system", preparedTurn.llmConfig.systemMessage)
        assertEquals(null, preparedTurn.llmConfig.tools)
        coVerify(exactly = 1) { systemPromptComposer.compose(roleWithInstructions) }
        coVerify(exactly = 1) { llmProviderService.getProviderById(testModel.providerId) }
        coVerify(exactly = 1) { credentialManager.getCredential(testProvider.apiKeyId!!) }
        coVerify(exactly = 0) { toolService.getToolsByIds(any()) }
    }

    /**
     * Verifies tool-capable models resolve the role's tool definitions via a single batch lookup.
     */
    @Test
    fun `prepareNewMessageTurn should resolve role tools when model supports tool calling`() = runTest {
        val sessionId = 1L
        val toolId = 7L
        val tool = eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition(
            id = toolId,
            name = "search",
            description = "Searches docs",
            config = buildJsonObject { },
            inputSchema = buildJsonObject { },
            outputSchema = null,
            isEnabled = true,
            createdAt = Instant.fromEpochMilliseconds(0L),
            updatedAt = Instant.fromEpochMilliseconds(0L),
            serverId = 1L,
            mcpToolName = "search"
        )
        val roleWithTools = testRole.copy(
            modelId = toolCallingModel.id,
            modelSettingsId = testSettings.id,
            tools = setOf(toolId)
        )
        coEvery { sessionDao.getSessionById(sessionId) } returns testSession.right()
        coEvery { agentRoleService.getAgentRoleById(testSession.agentRoleId!!) } returns roleWithTools.right()
        coEvery { systemPromptComposer.compose(roleWithTools) } returns ""
        coEvery { llmModelService.getModelById(toolCallingModel.id) } returns toolCallingModel.right()
        coEvery { modelSettingsService.getSettingsById(testSettings.id) } returns testSettings.right()
        coEvery { llmProviderService.getProviderById(toolCallingModel.providerId) } returns testProvider.right()
        coEvery { credentialManager.getCredential(testProvider.apiKeyId!!) } returns "test-api-key".right()
        coEvery { toolService.getToolsByIds(setOf(toolId)) } returns mapOf(toolId to tool)

        val result = preparationService.prepareNewMessageTurn(sessionId, "test content", null, false)

        assertEquals(listOf(tool), result.getOrNull()?.llmConfig?.tools)
        coVerify(exactly = 1) { toolService.getToolsByIds(setOf(toolId)) }
    }
}

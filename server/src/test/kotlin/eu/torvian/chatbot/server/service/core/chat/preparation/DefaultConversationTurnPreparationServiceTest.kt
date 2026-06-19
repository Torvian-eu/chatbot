package eu.torvian.chatbot.server.service.core.chat.preparation

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMModelCapabilities
import eu.torvian.chatbot.common.models.llm.LLMModelType
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.error.MessageError
import eu.torvian.chatbot.server.data.dao.error.SessionError
import eu.torvian.chatbot.server.service.core.LLMModelService
import eu.torvian.chatbot.server.service.core.LLMProviderService
import eu.torvian.chatbot.server.service.core.ModelSettingsService
import eu.torvian.chatbot.server.service.core.ToolService
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
 * Verifies that [DefaultConversationTurnPreparationService] preserves the extracted validation and
 * runtime-preparation behavior.
 */
class DefaultConversationTurnPreparationServiceTest {
    private lateinit var messageDao: MessageDao
    private lateinit var sessionDao: SessionDao
    private lateinit var llmModelService: LLMModelService
    private lateinit var modelSettingsService: ModelSettingsService
    private lateinit var llmProviderService: LLMProviderService
    private lateinit var credentialManager: CredentialManager
    private lateinit var toolService: ToolService
    private lateinit var transactionScope: TransactionScope
    private lateinit var preparationService: DefaultConversationTurnPreparationService

    private val testSession = ChatSession(
        id = 1L,
        name = "Test Session",
        createdAt = Instant.fromEpochMilliseconds(1234567890000L),
        updatedAt = Instant.fromEpochMilliseconds(1234567890000L),
        groupId = null,
        currentModelId = 1L,
        currentSettingsId = 1L,
        currentLeafMessageId = null,
        messages = emptyList()
    )

    private val testModel = LLMModel(
        id = 1L,
        name = "gpt-3.5-turbo",
        providerId = 1L,
        active = true,
        displayName = "GPT-3.5 Turbo",
        type = LLMModelType.CHAT
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
        systemMessage = "You are a helpful assistant.",
        temperature = 0.7f,
        maxTokens = 1000,
        customParams = null,
        stream = false
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
        transactionScope = mockk()
        preparationService = DefaultConversationTurnPreparationService(
            messageDao = messageDao,
            sessionDao = sessionDao,
            toolService = toolService,
            llmModelService = llmModelService,
            modelSettingsService = modelSettingsService,
            llmProviderService = llmProviderService,
            credentialManager = credentialManager,
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
            llmProviderService, credentialManager, toolService, transactionScope
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
     * Verifies model selection is still required before runtime inputs are assembled.
     */
    @Test
    fun `prepareNewMessageTurn should return ModelConfigurationError when no model is selected`() = runTest {
        val sessionId = 1L
        coEvery { sessionDao.getSessionById(sessionId) } returns testSession.copy(currentModelId = null).right()

        val result = preparationService.prepareNewMessageTurn(sessionId, "test content", null, false)

        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertNotNull(error)
        assertIs<ValidateNewMessageError.ModelConfigurationError>(error)
        assertEquals("No model selected for session $sessionId", error.message)
    }

    /**
     * Verifies settings selection is still required before runtime inputs are assembled.
     */
    @Test
    fun `prepareNewMessageTurn should return ModelConfigurationError when no settings are selected`() = runTest {
        val sessionId = 1L
        val sessionWithoutSettings = testSession.copy(currentSettingsId = null)
        coEvery { sessionDao.getSessionById(sessionId) } returns sessionWithoutSettings.right()
        coEvery { llmModelService.getModelById(sessionWithoutSettings.currentModelId!!) } returns testModel.right()

        val result = preparationService.prepareNewMessageTurn(sessionId, "test content", null, false)

        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertNotNull(error)
        assertIs<ValidateNewMessageError.ModelConfigurationError>(error)
        assertEquals("No settings selected for session $sessionId", error.message)
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
     * Verifies successful preparation still assembles the same session and LLM runtime configuration.
     */
    @Test
    fun `prepareNewMessageTurn should return prepared turn when validation succeeds`() = runTest {
        val sessionId = 1L
        val streamingSettings = testSettings.copy(stream = true)
        coEvery { sessionDao.getSessionById(sessionId) } returns testSession.right()
        coEvery { llmModelService.getModelById(testSession.currentModelId!!) } returns testModel.right()
        coEvery { modelSettingsService.getSettingsById(testSession.currentSettingsId!!) } returns streamingSettings.right()
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
        assertEquals(null, preparedTurn.llmConfig.tools)
        coVerify(exactly = 1) { llmProviderService.getProviderById(testModel.providerId) }
        coVerify(exactly = 1) { credentialManager.getCredential(testProvider.apiKeyId!!) }
        coVerify(exactly = 0) { toolService.getEnabledToolsForSession(any()) }
    }

    /**
     * Verifies tool-capable models still resolve the enabled-tool list instead of returning null.
     */
    @Test
    fun `prepareNewMessageTurn should resolve enabled tools when model supports tool calling`() = runTest {
        val sessionId = 1L
        val enabledTools = emptyList<eu.torvian.chatbot.common.models.tool.ToolDefinition>()
        coEvery { sessionDao.getSessionById(sessionId) } returns testSession.right()
        coEvery { llmModelService.getModelById(testSession.currentModelId!!) } returns toolCallingModel.right()
        coEvery { modelSettingsService.getSettingsById(testSession.currentSettingsId!!) } returns testSettings.right()
        coEvery { llmProviderService.getProviderById(toolCallingModel.providerId) } returns testProvider.right()
        coEvery { credentialManager.getCredential(testProvider.apiKeyId!!) } returns "test-api-key".right()
        coEvery { toolService.getEnabledToolsForSession(sessionId) } returns enabledTools

        val result = preparationService.prepareNewMessageTurn(sessionId, "test content", null, false)

        assertEquals(enabledTools, result.getOrNull()?.llmConfig?.tools)
        coVerify(exactly = 1) { toolService.getEnabledToolsForSession(sessionId) }
    }
}
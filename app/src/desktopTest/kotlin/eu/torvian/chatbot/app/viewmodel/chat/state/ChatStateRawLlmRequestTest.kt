package eu.torvian.chatbot.app.viewmodel.chat.state

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.*
import eu.torvian.chatbot.app.viewmodel.chat.util.DefaultThreadBuilder
import eu.torvian.chatbot.common.models.api.core.RawLlmRequestPayload
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Verifies that the captured provider request lives in one in-memory slot of [ChatStateImpl], is replaced
 * by every later capture, and is discarded by a reset.
 */
class ChatStateRawLlmRequestTest {

    private val model = LLMModel(id = 1L, name = "gpt-4", providerId = 10L, active = true)
    private val settings: ModelSettings = ChatModelSettings(id = 2L, modelId = 1L, name = "Chat profile")

    /** Verifies a fresh state exposes no capture. */
    @Test
    fun `a fresh state exposes no capture`() = runTest {
        val state = createState(this)

        assertNull(state.rawLlmRequest.value)
    }

    /** Verifies a later capture replaces the previous one instead of accumulating. */
    @Test
    fun `a later capture overwrites the previous one`() = runTest {
        val state = createState(this)
        state.setRawLlmRequest(payload("first"))

        state.setRawLlmRequest(payload("second"))

        assertEquals("second", state.rawLlmRequest.value?.body)
    }

    /** Verifies a capture can be forgotten explicitly. */
    @Test
    fun `an explicit null clears the capture`() = runTest {
        val state = createState(this)
        state.setRawLlmRequest(payload("first"))

        state.setRawLlmRequest(null)

        assertNull(state.rawLlmRequest.value)
    }

    /** Verifies a reset discards the capture together with the rest of the session state. */
    @Test
    fun `reset clears the capture`() = runTest {
        val state = createState(this)
        state.setRawLlmRequest(payload("first"))

        state.resetState()

        assertNull(state.rawLlmRequest.value)
    }

    /** Builds a captured-request payload carrying the supplied marker in its body. */
    private fun payload(marker: String) = RawLlmRequestPayload(
        method = "POST",
        url = "https://api.example.com/v1/chat/completions",
        headers = emptyMap(),
        body = marker
    )

    /**
     * Builds a state with placeholder repositories; the captured request needs no repository data.
     *
     * @param scope Test scope supplying the long-lived background scope of the state.
     * @return A state whose repositories carry empty successful data.
     */
    private fun createState(scope: TestScope): ChatStateImpl {
        val session = ChatSession(
            id = 100L,
            name = "Session",
            createdAt = Instant.fromEpochSeconds(0),
            updatedAt = Instant.fromEpochSeconds(0),
            groupId = null,
            agentRoleId = null,
            currentLeafMessageId = null,
            messages = emptyList(),
            projectId = null
        )

        val conversationRepository = mockk<ConversationRepository>()
        coEvery { conversationRepository.getSessionDetailsFlow(any()) } returns
                MutableStateFlow(DataState.Success(session))

        val settingsRepository = mockk<ModelSettingsRepository>()
        every { settingsRepository.allSettings } returns MutableStateFlow(DataState.Success(listOf(settings)))

        val modelRepository = mockk<ModelRepository>()
        every { modelRepository.models } returns MutableStateFlow(DataState.Success(listOf(model)))

        val toolRepository = mockk<ToolRepository>()
        every { toolRepository.tools } returns MutableStateFlow(DataState.Success(emptyList()))

        val mcpServerRepository = mockk<LocalMCPServerRepository>()
        every { mcpServerRepository.servers } returns MutableStateFlow(DataState.Success(emptyList()))

        val agentRoleRepository = mockk<AgentRoleRepository>()
        every { agentRoleRepository.roles } returns MutableStateFlow(DataState.Success(emptyList()))

        val projectRepository = mockk<ProjectRepository>()
        every { projectRepository.projects } returns MutableStateFlow(DataState.Success(emptyList()))

        return ChatStateImpl(
            conversationRepository = conversationRepository,
            modelSettingsRepository = settingsRepository,
            modelRepository = modelRepository,
            toolRepository = toolRepository,
            mcpServerRepository = mcpServerRepository,
            agentRoleRepository = agentRoleRepository,
            projectRepository = projectRepository,
            threadBuilder = DefaultThreadBuilder(),
            backgroundScope = scope.backgroundScope
        )
    }
}

package eu.torvian.chatbot.server.service.core.chat.turn

import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.data.dao.AssistantMessageCompletionState
import eu.torvian.chatbot.server.runtime.TurnControlSignal
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedAssistantMessage
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedUserMessage
import io.mockk.coEvery
import kotlinx.coroutines.flow.emptyFlow

/**
 * Adds the streaming turn fixtures shared by the streaming scenario suites on top of
 * [DefaultConversationTurnOrchestratorTestBase].
 *
 * Holds the request/user-message/placeholder builders and the turn-start stubbing so each scenario class
 * declares only the behavior it verifies; every mock and stub default still lives in the inherited
 * `setUp`/`tearDown` of the common base.
 */
abstract class DefaultConversationTurnOrchestratorStreamingTestBase : DefaultConversationTurnOrchestratorTestBase() {

    /**
     * Builds a streaming turn request on the shared session/model/provider fixtures.
     *
     * @param content User content of the turn.
     * @param turnControlSignal Cooperative control signal observed by the turn.
     * @param tools Enabled tools for the turn, or `null` when tool calling is unavailable.
     * @return Turn request wired to the shared test fixtures.
     */
    protected fun streamingTurnRequest(
        content: String,
        turnControlSignal: TurnControlSignal = TurnControlSignal(),
        tools: List<ToolDefinition>? = null
    ): ConversationTurnRequest = ConversationTurnRequest(
        userId = 1L,
        session = testSession,
        llmConfig = LLMConfig(testProvider, testModel, testSettings.copy(stream = true), "api-key", tools),
        content = content,
        parentMessageId = null,
        fileReferences = emptyList(),
        toolApprovalFlow = emptyFlow(),
        operatorToolResultFlow = emptyFlow(),
        turnControlSignal = turnControlSignal
    )

    /**
     * Builds a user message fixture for a streaming turn.
     *
     * @param id Message identifier.
     * @param content Message content.
     * @return User message belonging to the shared [testSession].
     */
    protected fun streamingUserMessage(id: Long, content: String): ChatMessage.UserMessage = ChatMessage.UserMessage(
        id = id,
        sessionId = testSession.id,
        content = content,
        createdAt = baseInstant,
        updatedAt = baseInstant,
        parentMessageId = null,
        childrenMessageIds = emptyList()
    )

    /**
     * Builds the not-completed placeholder of a streaming assistant step that is still generating.
     *
     * @param id Message identifier of the placeholder.
     * @param parentMessageId Parent the placeholder replies to.
     * @return Assistant message with empty content and no terminal cause.
     */
    protected fun streamingPlaceholder(id: Long, parentMessageId: Long): ChatMessage.AssistantMessage =
        ChatMessage.AssistantMessage(
            id = id,
            sessionId = testSession.id,
            content = "",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = parentMessageId,
            childrenMessageIds = emptyList(),
            modelId = testModel.id,
            settingsId = testSettings.id,
            isComplete = false
        )

    /**
     * Stubs the deterministic start of a streaming turn: user persistence, tool-call loading and the
     * not-completed placeholder insert.
     *
     * @param userMessage User message persisted at turn start.
     * @param placeholder Placeholder returned by the assistant insert.
     */
    protected fun stubStreamingTurnStart(
        userMessage: ChatMessage.UserMessage,
        placeholder: ChatMessage.AssistantMessage
    ) {
        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, userMessage.content, null, any())
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                "",
                userMessage.id,
                testModel,
                testSettings.copy(stream = true),
                agentRoleId = testRoleId,
                reasoningItems = null,
                completion = AssistantMessageCompletionState.InFlight
            )
        } returns PersistedAssistantMessage(placeholder, userMessage)
    }
}

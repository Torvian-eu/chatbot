package eu.torvian.chatbot.app.service.agent

import eu.torvian.chatbot.app.repository.AuthRepository
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.ChatViewModel
import eu.torvian.chatbot.app.viewmodel.chat.SpawnedChatViewModelResolver
import eu.torvian.chatbot.common.models.agent.OperatorToolMode
import eu.torvian.chatbot.common.models.agent.SendMessageRequest
import eu.torvian.chatbot.common.models.api.core.ChatClientEvent
import kotlinx.coroutines.CancellationException

/**
 * [OperatorTool] implementation for the `send_message` operator tool, registered in the central
 * router ([DefaultOperatorToolExecutor]) under the catalog name `send_message`.
 *
 * `send_message` injects a user [SendMessageRequest.message] into an existing chat session
 * identified by [SendMessageRequest.chatSessionId] and — in wait mode — returns that session's last
 * assistant message. The target session already exists and its ownership was validated server-side
 * before the relay, so there is no session creation here; sendability (a resolvable
 * role/model/settings profile) is the app-runtime concern handled by the shared turn driver
 * ([runTurnThroughViewModel]).
 *
 * ## Mode semantics
 *
 * - [OperatorToolMode.WAIT_FOR_RESPONSE] joins the send job and returns the target session's last
 *   **new** assistant message labeled with the target chat session id and `**Response:**` (the id
 *   tag keeps concurrent multi-session sends distinguishable; a blank response renders as
 *   `No response received.`); a missing/stale response is an error.
 * - [OperatorToolMode.FIRE_AND_FORGET] starts the target turn and returns a success notification
 *   tagged with the target chat session id (`**Target chat session id:** <id>` +
 *   "Message sent successfully; the target conversation continues in the background.") **without**
 *   joining the send job and **without** force-cancelling the background turn (the mode-aware
 *   cleanup only force-cancels for wait mode).
 *
 * The id tag makes the tool output self-describing when the caller dispatches several messages to
 * different sessions concurrently: each result identifies the session it belongs to. Errors still
 * omit the id (the caller supplied it — it sent the message there), so only successes are tagged.
 *
 * @property authRepository Source of the authenticated user id required by [ChatViewModel.loadSession]
 *            when loading the target session.
 * @property spawnedViewModelResolver Resolves the target session's [ChatViewModel], reusing the same
 *            instance the UI resolves for that session.
 */
class SendMessageTool(
    private val authRepository: AuthRepository,
    private val spawnedViewModelResolver: SpawnedChatViewModelResolver
) : OperatorTool {

    /** Logger used for `send_message` tool diagnostics. */
    private val logger = kmpLogger<SendMessageTool>()

    override suspend fun execute(
        toolCallId: Long,
        payload: String,
        clientEvents: suspend (ChatClientEvent.ToolExecutionResult) -> Unit
    ) {
        val request = runCatching { operatorToolJson.decodeFromString<SendMessageRequest>(payload) }
            .getOrElse { error ->
                logger.error("Failed to decode SendMessageRequest for tool call $toolCallId", error)
                clientEvents(toolError(toolCallId, "Failed to decode send message request: ${error.message}"))
                return
            }

        // loadSession needs the authenticated user id to fetch user-scoped MCP servers.
        val userId = (authRepository.authState.value as? AuthState.Authenticated)?.userId
        if (userId == null) {
            clientEvents(toolError(toolCallId, "Send message failed: user is not authenticated"))
            return
        }

        val targetChatViewModel = try {
            spawnedViewModelResolver.forSession(request.chatSessionId)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            logger.error("Failed to resolve ChatViewModel for target session ${request.chatSessionId}", error)
            clientEvents(toolError(toolCallId, "Send message failed: could not resolve the target session"))
            return
        }

        // Same mode-aware cleanup rule as spawn: wait modes force-cancel the in-flight target turn
        // when this coroutine ends (including cancellation); fire-and-forget never cancels the
        // background turn it just started.
        var forceCancelOnExit = false
        try {
            forceCancelOnExit = request.mode == OperatorToolMode.WAIT_FOR_RESPONSE
            val outcome = runTurnThroughViewModel(
                viewModel = targetChatViewModel,
                sessionId = request.chatSessionId,
                userId = userId,
                message = request.message,
                mode = request.mode
            )
            clientEvents(formatSendMessageOutcome(toolCallId, request.chatSessionId, request.mode, outcome))
        } finally {
            // As for spawn, wait mode force-cancels the in-flight target turn when this executor
            // coroutine ends (owning socket close / calling-turn stop), while fire-and-forget
            // deliberately orphans the background turn to its own session lifecycle.
            if (forceCancelOnExit) {
                targetChatViewModel.forceCancelSend()
            }
        }
    }

    /**
     * Formats the outcome of a driven `send_message` turn into the tool result for the calling LLM.
     *
     * Output shapes:
     *  - wait success → `"**Target chat session id:** <id>\n\n**Response:**\n\n<content>"` (the
     *    target's last **new** assistant message; the id tag keeps concurrent multi-session sends
     *    distinguishable); a blank response renders as `"No response received."`;
     *  - fire-and-forget success → `"**Target chat session id:** <id>\n\nMessage sent
     *    successfully; the target conversation continues in the background."`;
     *  - errors → readable error result without an id (the caller supplied it).
     *
     * @param toolCallId Correlation key of the originating tool call.
     * @param chatSessionId The target session's identifier (tagged so concurrent multi-session
     *            sends stay distinguishable).
     * @param mode The shared operator-tool execution mode.
     * @param outcome The turn outcome produced by [runTurnThroughViewModel].
     * @return The [ChatClientEvent.ToolExecutionResult] to emit on the primary socket.
     */
    private fun formatSendMessageOutcome(
        toolCallId: Long,
        chatSessionId: Long,
        mode: OperatorToolMode,
        outcome: TurnOutcome
    ): ChatClientEvent.ToolExecutionResult = when (outcome) {
        is TurnOutcome.Succeeded ->
            if (mode == OperatorToolMode.FIRE_AND_FORGET) {
                ChatClientEvent.ToolExecutionResult(
                    toolCallId = toolCallId,
                    output = """
                        **Target chat session id:** $chatSessionId

                        Message sent successfully; the target conversation continues in the background.
                    """.trimIndent()
                )
            } else {
                // The id tag keeps the result self-describing when the caller sends messages to
                // several sessions concurrently; the body is the assistant response itself, labeled
                // so the calling model knows what the section contains. A blank response is
                // normally rejected earlier by the wait-mode newness guard; this fallback keeps the
                // output readable if a blank response ever reaches the formatter.
                val response = outcome.content?.takeIf { it.isNotBlank() } ?: "No response received."
                ChatClientEvent.ToolExecutionResult(
                    toolCallId = toolCallId,
                    output = """
                        **Target chat session id:** $chatSessionId

                        **Response:**

                        $response
                    """.trimIndent()
                )
            }

        TurnOutcome.Refused ->
            toolError(toolCallId, "Send message was refused.")

        TurnOutcome.NoAssistantMessage ->
            toolError(toolCallId, "Target conversation ended without an assistant message.")

        TurnOutcome.UnresolvedRole ->
            toolError(toolCallId, "Target session could not resolve its role, model or settings.")
    }
}
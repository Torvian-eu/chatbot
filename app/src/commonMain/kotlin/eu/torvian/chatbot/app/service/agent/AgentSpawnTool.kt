package eu.torvian.chatbot.app.service.agent

import eu.torvian.chatbot.app.repository.AuthRepository
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.ChatViewModel
import eu.torvian.chatbot.app.viewmodel.chat.SpawnedChatViewModelResolver
import eu.torvian.chatbot.common.models.agent.AgentSpawnMessage
import eu.torvian.chatbot.common.models.agent.AgentSpawnRequest
import eu.torvian.chatbot.common.models.agent.OperatorToolMode
import eu.torvian.chatbot.common.models.api.core.ChatClientEvent
import kotlinx.coroutines.CancellationException

/**
 * [OperatorTool] implementation for the `spawn_agent` operator tool, registered in the central
 * router ([DefaultOperatorToolExecutor]) under the catalog name `spawn_agent`.
 *
 * This tool is a thin **coordinator**: it does not run the spawned conversation itself. Instead it
 * creates the spawned session through [SessionRepository], resolves that session's own
 * [ChatViewModel] via [SpawnedChatViewModelResolver], and drives it with the exact public methods a
 * user would use ([ChatViewModel.loadSession], [ChatViewModel.updateInput],
 * [ChatViewModel.sendMessage] — see the shared turn driver [runTurnThroughViewModel]). Driving the
 * spawned turn through its own ViewModel makes the conversation behave like a user-created,
 * user-driven session:
 *
 * - tool approvals flow through the existing pipeline (stored preferences auto-approve/auto-deny; a
 *   no-preference call stays pending and the user can switch to the spawned session and approve);
 * - pause/stop works from the spawned session's own UI because the turn is driven by that session's
 *   [ChatViewModel] with a live event collector;
 * - session state (messages, tool calls, turn state) is populated through ChatState;
 * - nested operator-tool calls inside the spawned conversation run through the spawned session's own
 *   pipeline.
 *
 * ## Mode semantics
 *
 * - [OperatorToolMode.WAIT_FOR_RESPONSE] joins the send job and reports the spawned session's last
 *   assistant message in markdown (`**Spawned chat session id:** <id>` and a `**Response:**` label
 *   with the summary; a blank response renders as `No response received.`);
 *   wait-mode failures **after** the session was created carry
 *   `output = "**Spawned chat session id:** <id>"` so the caller can still reach the spawned session
 *   (the top-level id-on-failure rule).
 * - [OperatorToolMode.FIRE_AND_FORGET] starts the spawned first turn and returns a markdown result
 *   with the spawned session id and a status line (`**Spawned chat session id:** <id>` +
 *   "The spawned conversation started; its first turn continues in the background.") **without**
 *   joining the send job and **without** force-cancelling the background turn — including when
 *   this executor coroutine is cancelled (the mode-aware cleanup only force-cancels for wait
 *   mode). Immediate mode reports the id on success only.
 *
 * Pre-session failures (decode, missing prompt, create failure) are reported without any id.
 *
 * @property sessionRepository Repository used to create the spawned session and attach the role.
 * @property authRepository Source of the authenticated user id required by [ChatViewModel.loadSession]
 *            when loading the spawned session.
 * @property spawnedViewModelResolver Resolves the spawned session's [ChatViewModel], reusing the same
 *            instance the UI resolves for that session.
 */
class AgentSpawnTool(
    private val sessionRepository: SessionRepository,
    private val authRepository: AuthRepository,
    private val spawnedViewModelResolver: SpawnedChatViewModelResolver
) : OperatorTool {

    companion object {
        /**
         * Prefix that distinguishes agent-created sessions from sessions created directly by a user.
         */
        private const val SPAWNED_SESSION_NAME_PREFIX = "Spawned: "
    }

    /** Logger used for `spawn_agent` tool diagnostics. */
    private val logger = kmpLogger<AgentSpawnTool>()

    override suspend fun execute(
        toolCallId: Long,
        payload: String,
        clientEvents: suspend (ChatClientEvent.ToolExecutionResult) -> Unit
    ) {
        val request = runCatching { operatorToolJson.decodeFromString<AgentSpawnRequest>(payload) }
            .getOrElse { error ->
                logger.error("Failed to decode AgentSpawnRequest for tool call $toolCallId", error)
                clientEvents(toolError(toolCallId, "Failed to decode spawn request: ${error.message}"))
                return
            }

        val role = request.agentRoleToSpawn

        // The spawned conversation needs a first user message; in practice the request carries a
        // single User item holding the prompt.
        val prompt = request.conversation.filterIsInstance<AgentSpawnMessage.User>()
            .map { it.content }
            .firstOrNull { it.isNotBlank() }
        if (prompt == null) {
            clientEvents(toolError(toolCallId, "Spawn request contains no user prompt"))
            return
        }

        // Create the spawned session and attach the requested role (two round-trips; the server
        // offers no create-with-role variant). A create failure is a pre-session failure: no valid
        // session id exists yet, so the error carries no id.
        val session = sessionRepository.createSession(
            // Keep the subject recognizable while retaining a stable marker for spawned sessions.
            name = "$SPAWNED_SESSION_NAME_PREFIX${request.subject}"
        ).fold(
            ifLeft = { error -> reportSessionFailure(toolCallId, "create", error, clientEvents); return },
            ifRight = { it }
        )

        // From this point a valid spawned session id exists: wait-mode failures (attach, auth, VM
        // resolution, refused turn, blank summary) carry it in the error output, whereas
        // fire-and-forget reports the id only on success. Cleanup is likewise mode-aware.
        var forceCancelOnExit = false
        var spawnedChatViewModel: ChatViewModel? = null
        try {
            sessionRepository.updateSessionAgentRole(session.id, role.id).fold(
                ifLeft = { error ->
                    logger.error("Failed to attach role to spawned session ${session.id}: $error")
                    clientEvents(
                        spawnFailure(
                            toolCallId, session.id,
                            "Failed to attach role to spawned session: ${error.message}",
                            request.mode
                        )
                    )
                    return
                },
                ifRight = { }
            )

            // loadSession needs the authenticated user id to fetch user-scoped MCP servers.
            val userId = (authRepository.authState.value as? AuthState.Authenticated)?.userId
            if (userId == null) {
                clientEvents(
                    spawnFailure(
                        toolCallId, session.id,
                        "Spawned conversation failed: user is not authenticated",
                        request.mode
                    )
                )
                return
            }

            // Resolve the spawned session's own ChatViewModel up front so the finally block can
            // cancel the spawned send even if this coroutine is cancelled mid-turn (wait mode).
            spawnedChatViewModel = try {
                spawnedViewModelResolver.forSession(session.id)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                logger.error("Failed to resolve spawned ChatViewModel for session ${session.id}", error)
                clientEvents(
                    spawnFailure(
                        toolCallId, session.id,
                        "Spawned failed: could not resolve the spawned session",
                        request.mode
                    )
                )
                return
            }

            // Wait mode keeps today's join-then-cancel lifecycle; fire-and-forget never cancels.
            forceCancelOnExit = request.mode == OperatorToolMode.WAIT_FOR_RESPONSE

            val outcome = runTurnThroughViewModel(
                viewModel = spawnedChatViewModel,
                sessionId = session.id,
                userId = userId,
                message = prompt,
                mode = request.mode
            )
            clientEvents(formatSpawnOutcome(toolCallId, session.id, request.mode, outcome))
        } finally {
            // The spawned send runs in the spawned ViewModel's own scope, which outlives this
            // coroutine. In wait mode, when the primary socket closed or this coroutine was
            // cancelled mid-turn, explicitly cancel the spawned turn instead of orphaning it (a
            // no-op when the send already completed naturally). Fire-and-forget deliberately orphans
            // the background turn to its own session lifecycle (its VM scope and socket outlive the
            // executor coroutine): the approved immediate-mode decision forbids cancelling it even
            // when this executor coroutine is cancelled, so forceCancelSend is only reached for
            // wait-mode requests that resolved a ViewModel.
            val resolvedViewModel = spawnedChatViewModel
            if (forceCancelOnExit && resolvedViewModel != null) {
                resolvedViewModel.forceCancelSend()
            }
        }
    }

    /**
     * Formats the outcome of a driven spawn turn into the tool result for the calling LLM.
     *
     * Output shapes (markdown-styled so the calling model renders them uniformly):
     *  - wait success → `"**Spawned chat session id:** <id>\n\n**Response:**\n\n<summary>"`;
     *    a blank response renders as `"No response received."`;
     *  - wait failure with a valid session id → error result with
     *    `output = "**Spawned chat session id:** <id>"`;
     *  - fire-and-forget success → `"**Spawned chat session id:** <id>\n\n<status line>"` (the
     *    turn is still running, so a status line replaces the not-yet-available summary);
     *  - fire-and-forget failure → error result without an id (immediate mode reports the id only
     *    on success; pre-session spawn failures never carry an id either — they return earlier).
     *
     * @param toolCallId Correlation key of the originating tool call.
     * @param sessionId The spawned session's identifier.
     * @param mode The shared operator-tool execution mode.
     * @param outcome The turn outcome produced by [runTurnThroughViewModel].
     * @return The [ChatClientEvent.ToolExecutionResult] to emit on the primary socket.
     */
    private fun formatSpawnOutcome(
        toolCallId: Long,
        sessionId: Long,
        mode: OperatorToolMode,
        outcome: TurnOutcome
    ): ChatClientEvent.ToolExecutionResult = when (outcome) {
        is TurnOutcome.Succeeded ->
            if (mode == OperatorToolMode.FIRE_AND_FORGET) {
                // Immediate mode: the id is the essential payload; with no summary available yet,
                // the content slot carries a status line so the caller knows the turn continues
                // in the background.
                ChatClientEvent.ToolExecutionResult(
                    toolCallId = toolCallId,
                    output = """
                        **Spawned chat session id:** $sessionId

                        The spawned conversation started; its first turn continues in the background.
                    """.trimIndent()
                )
            } else {
                // The content is the assistant response the spawned conversation produced. A blank
                // response is normally rejected earlier by the wait-mode newness guard, but this
                // fallback keeps the output readable (instead of printing an empty section) if a
                // blank summary ever reaches the formatter.
                val response = outcome.content?.takeIf { it.isNotBlank() } ?: "No response received."
                ChatClientEvent.ToolExecutionResult(
                    toolCallId = toolCallId,
                    output = """
                        **Spawned chat session id:** $sessionId

                        **Response:**

                        $response
                    """.trimIndent()
                )
            }

        TurnOutcome.Refused ->
            spawnFailure(toolCallId, sessionId, "Spawned send was refused.", mode)

        TurnOutcome.NoAssistantMessage ->
            spawnFailure(toolCallId, sessionId, "Spawned conversation ended without an assistant summary.", mode)

        TurnOutcome.UnresolvedRole ->
            spawnFailure(toolCallId, sessionId, "Spawned session could not resolve its role, model or settings.", mode)
    }

    /**
     * Builds a wait-/fire-and-forget-aware error result after a spawned session id exists.
     *
     * Wait mode failures carry the spawned session id as a markdown label
     * (`**Spawned chat session id:** <id>`) so the caller can still reach the spawned session
     * (e.g. to resume it or message it); fire-and-forget failures never carry the id (immediate
     * mode reports the id only on success, per the approved id-on-failure rule).
     *
     * @param toolCallId Correlation key of the originating tool call.
     * @param sessionId The spawned session's identifier.
     * @param message Human-readable error message to feed back to the calling LLM.
     * @param mode The shared operator-tool execution mode.
     * @return The error result to emit on the primary socket.
     */
    private fun spawnFailure(
        toolCallId: Long,
        sessionId: Long,
        message: String,
        mode: OperatorToolMode
    ): ChatClientEvent.ToolExecutionResult =
        if (mode == OperatorToolMode.WAIT_FOR_RESPONSE) {
            ChatClientEvent.ToolExecutionResult(
                toolCallId = toolCallId,
                isError = true,
                errorMessage = message,
                output = "**Spawned chat session id:** $sessionId"
            )
        } else {
            ChatClientEvent.ToolExecutionResult(
                toolCallId = toolCallId,
                isError = true,
                errorMessage = message,
                output = null
            )
        }

    /**
     * Reports a session-creation failure to the calling LLM.
     *
     * @param toolCallId Correlation key of the originating tool call.
     * @param phase Human-readable phase label used in the error message.
     * @param error The repository error that occurred.
     * @param clientEvents Sink used to emit the error result.
     */
    private suspend fun reportSessionFailure(
        toolCallId: Long,
        phase: String,
        error: RepositoryError,
        clientEvents: suspend (ChatClientEvent.ToolExecutionResult) -> Unit
    ) {
        logger.error("Failed to $phase spawned session for tool call $toolCallId: $error")
        clientEvents(
            ChatClientEvent.ToolExecutionResult(
                toolCallId = toolCallId,
                isError = true,
                errorMessage = "Failed to $phase spawned session: ${error.message}"
            )
        )
    }
}
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
import eu.torvian.chatbot.common.models.api.core.ChatClientEvent
import eu.torvian.chatbot.common.models.tool.OperatorToolCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Default implementation of [AgentSpawnExecutor] for the `spawn_agent` operator tool.
 *
 * This executor is a thin **coordinator**: it does not run the spawned conversation itself. Instead
 * it creates the spawned session through [SessionRepository], resolves that session's own
 * [ChatViewModel] via [SpawnedChatViewModelResolver], and drives it with the exact public methods a
 * user would use ([ChatViewModel.loadSession], [ChatViewModel.updateInput],
 * [ChatViewModel.sendMessage]). Driving the spawned turn through its own ViewModel makes the
 * conversation behave like a user-created, user-driven session:
 *
 * - tool approvals flow through the existing pipeline (stored preferences auto-approve/auto-deny; a
 *   no-preference call stays pending and the user can switch to the spawned session and approve);
 * - pause/stop works from the spawned session's own UI because the turn is driven by that session's
 *   [ChatViewModel] with a live event collector;
 * - session state (messages, tool calls, turn state) is populated through ChatState;
 * - nested `spawn_agent` calls inside the spawned conversation run through the spawned session's own
 *   pipeline.
 *
 * The executor awaits the send job, aggregates the spawned turn's last assistant message via
 * [ChatViewModel.lastAssistantMessageContent], and reports it as a
 * [ChatClientEvent.ToolExecutionResult] on the primary socket. When the primary turn ends or is
 * cancelled mid-spawn, the spawned send is force-cancelled (it runs in the spawned VM's scope and
 * would otherwise be orphaned). Any failure is reported as an error result so the calling LLM hears
 * a readable message.
 *
 * @property sessionRepository Repository used to create the spawned session and attach the role.
 * @property authRepository Source of the authenticated user id required by [ChatViewModel.loadSession].
 * @property spawnedViewModelResolver Resolves the spawned session's [ChatViewModel], reusing the same
 *            instance the UI resolves for that session.
 */
class DefaultAgentSpawnExecutor(
    private val sessionRepository: SessionRepository,
    private val authRepository: AuthRepository,
    private val spawnedViewModelResolver: SpawnedChatViewModelResolver
) : AgentSpawnExecutor {

    companion object {
        /**
         * Prefix that distinguishes agent-created sessions from sessions created directly by a user.
         */
        private const val SPAWNED_SESSION_NAME_PREFIX = "Spawned: "

        /**
         * Maximum time to wait for the spawned session's role/model/settings to become resolvable
         * after loading. The load use case completes before the ChatState derivations propagate, so
         * the executor waits up to this long before refusing the send.
         */
        private val ROLE_RESOLUTION_TIMEOUT: Duration = 20.seconds
    }

    private val logger = kmpLogger<DefaultAgentSpawnExecutor>()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(
        toolCallId: Long,
        toolName: String,
        payload: String,
        clientEvents: suspend (ChatClientEvent.ToolExecutionResult) -> Unit
    ) {
        // Unknown operator tool names degrade gracefully: report a tool error instead of crashing,
        // so future operator tools remain compatible with older clients.
        if (toolName != OperatorToolCatalog.SPAWN_AGENT_NAME) {
            clientEvents(
                ChatClientEvent.ToolExecutionResult(
                    toolCallId = toolCallId,
                    isError = true,
                    errorMessage = "Unknown operator tool: $toolName"
                )
            )
            return
        }

        val request = runCatching { json.decodeFromString<AgentSpawnRequest>(payload) }
            .getOrElse { error ->
                logger.error("Failed to decode AgentSpawnRequest for tool call $toolCallId", error)
                clientEvents(
                    ChatClientEvent.ToolExecutionResult(
                        toolCallId = toolCallId,
                        isError = true,
                        errorMessage = "Failed to decode spawn request: ${error.message}"
                    )
                )
                return
            }

        val role = request.agentRoleToSpawn

        // The spawned conversation needs a first user message; in practice the request carries a
        // single User item holding the prompt.
        val prompt = request.conversation.filterIsInstance<AgentSpawnMessage.User>()
            .map { it.content }
            .firstOrNull { it.isNotBlank() }
        if (prompt == null) {
            clientEvents(
                ChatClientEvent.ToolExecutionResult(
                    toolCallId = toolCallId,
                    isError = true,
                    errorMessage = "Spawn request contains no user prompt"
                )
            )
            return
        }

        // Create the spawned session and attach the requested role (two round-trips; the server
        // offers no create-with-role variant).
        val session = sessionRepository.createSession(
            // Keep the subject recognizable while retaining a stable marker for spawned sessions.
            name = "$SPAWNED_SESSION_NAME_PREFIX${request.subject}"
        ).fold(
            ifLeft = { error -> reportSessionFailure(toolCallId, "create", error, clientEvents); return },
            ifRight = { it }
        )
        sessionRepository.updateSessionAgentRole(session.id, role.id).fold(
            ifLeft = { error -> reportSessionFailure(toolCallId, "attach role to", error, clientEvents); return },
            ifRight = { }
        )

        // loadSession needs the authenticated user id to fetch user-scoped MCP servers.
        val userId = (authRepository.authState.value as? AuthState.Authenticated)?.userId
        if (userId == null) {
            clientEvents(
                ChatClientEvent.ToolExecutionResult(
                    toolCallId = toolCallId,
                    isError = true,
                    errorMessage = "Spawned conversation failed: user is not authenticated"
                )
            )
            return
        }

        // Resolve the spawned session's own ChatViewModel up front so the finally block can cancel
        // the spawned send even if this coroutine is cancelled mid-turn.
        val spawnedChatViewModel = try {
            spawnedViewModelResolver.forSession(session.id)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            logger.error("Failed to resolve spawned ChatViewModel for session ${session.id}", error)
            clientEvents(
                toolError(toolCallId, "Spawned failed: could not resolve the spawned session")
            )
            return
        }

        try {
            val result = runSpawnedTurnThroughViewModel(
                spawnedChatViewModel = spawnedChatViewModel,
                sessionId = session.id,
                userId = userId,
                prompt = prompt,
                toolCallId = toolCallId
            )
            clientEvents(result)
        } finally {
            // The spawned send runs in the spawned ViewModel's own scope, which outlives this
            // coroutine. When the primary socket closed or this coroutine was cancelled mid-turn,
            // explicitly cancel the spawned turn instead of orphaning it. This is a no-op when the
            // send already completed normally (no active send job remains).
            spawnedChatViewModel.forceCancelSend()
        }
    }

    /**
     * Drives the spawned turn through the spawned session's [ChatViewModel] and aggregates the last
     * assistant message.
     *
     * Keeps the spawned ChatState flows subscribed for the whole turn: its derived flows
     * (`sessionDataState`, `currentAgentRole`, `currentModel`, `currentSettings`) use
     * `SharingStarted.WhileSubscribed`, so a headless VM with no UI subscriber never computes them
     * and [ChatViewModel.sendMessage]'s role guard would refuse to run. After the load job completes
     * the call waits (bounded) until the role's model/settings resolve, then sets the input and
     * sends; the send job's completion is the deterministic turn-completion signal.
     *
     * @param spawnedChatViewModel The spawned session's ChatViewModel (same instance the UI uses).
     * @param sessionId The spawned session's identifier.
     * @param userId The authenticated user's identifier.
     * @param prompt The first user message of the spawned conversation.
     * @param toolCallId Correlation key of the originating tool call, echoed into error results.
     * @return The [ChatClientEvent.ToolExecutionResult] to emit on the primary socket.
     */
    private suspend fun runSpawnedTurnThroughViewModel(
        spawnedChatViewModel: ChatViewModel,
        sessionId: Long,
        userId: Long,
        prompt: String,
        toolCallId: Long
    ): ChatClientEvent.ToolExecutionResult = coroutineScope {
        // Keep the derived ChatState flows subscribed while the turn runs: they use
        // `SharingStarted.WhileSubscribed`, so a headless VM with no UI subscriber never computes
        // them and sendMessage's role guard would refuse to run. The collectors live in a private
        // warm-up scope that is cancelled on every exit path (normal, early-return, and cancellation),
        // so they are neither awaited by this scope nor leaked past the turn.
        val warmUpScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            warmUpScope.launch { spawnedChatViewModel.sessionDataState.collect {} }
            warmUpScope.launch { spawnedChatViewModel.currentAgentRole.collect {} }
            warmUpScope.launch { spawnedChatViewModel.currentModel.collect {} }
            warmUpScope.launch { spawnedChatViewModel.currentSettings.collect {} }
            warmUpScope.launch { spawnedChatViewModel.displayedMessages.collect {} }

            val loadJob = spawnedChatViewModel.loadSession(sessionId, userId)
            loadJob.join()

            // The load use case completes before the derived ChatState flows propagate; wait (bounded)
            // until the role's model/settings are resolvable so sendMessage's guard does not refuse.
            val roleResolved = withTimeoutOrNull(ROLE_RESOLUTION_TIMEOUT) {
                spawnedChatViewModel.currentAgentRole.first { it != null }
                spawnedChatViewModel.currentModel.first { it != null }
                spawnedChatViewModel.currentSettings.first { it != null }
                true
            } ?: false
            if (!roleResolved) {
                logger.error("Spawned session $sessionId could not resolve role/model/settings; refusing to send")
                return@coroutineScope toolError(
                    toolCallId,
                    "Spawned session could not resolve its role, model or settings."
                )
            }

            spawnedChatViewModel.updateInput(prompt)
            val sendJob = spawnedChatViewModel.sendMessage()
                ?: return@coroutineScope toolError(toolCallId, "Spawned send was refused.")
            sendJob.join()

            val summary = spawnedChatViewModel.lastAssistantMessageContent()
            if (summary.isNullOrBlank()) {
                return@coroutineScope toolError(
                    toolCallId,
                    "Spawned conversation ended without an assistant summary."
                )
            }
            ChatClientEvent.ToolExecutionResult(
                toolCallId = toolCallId,
                output = summary
            )
        } finally {
            warmUpScope.cancel()
        }
    }

    /**
     * Builds an error [ChatClientEvent.ToolExecutionResult] for the given tool call.
     *
     * @param toolCallId Correlation key of the originating tool call.
     * @param message Human-readable error message to feed back to the calling LLM.
     * @return The error result to emit on the primary socket.
     */
    private fun toolError(toolCallId: Long, message: String): ChatClientEvent.ToolExecutionResult =
        ChatClientEvent.ToolExecutionResult(
            toolCallId = toolCallId,
            isError = true,
            errorMessage = message
        )

    /**
     * Reports a session-creation or role-attach failure to the calling LLM.
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
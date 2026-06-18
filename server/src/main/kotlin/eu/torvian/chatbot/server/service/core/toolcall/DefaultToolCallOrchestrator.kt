package eu.torvian.chatbot.server.service.core.toolcall

import arrow.core.getOrElse
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.api.tool.ToolCallApprovalResponse
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.data.dao.ToolCallDao
import eu.torvian.chatbot.server.data.dao.UserToolApprovalPreferenceDao
import eu.torvian.chatbot.server.service.mcp.LocalMCPExecutor
import eu.torvian.chatbot.server.service.mcp.LocalMCPExecutorEvent
import eu.torvian.chatbot.server.service.tool.ToolExecutorFactory
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

private const val APPROVAL_TIMEOUT_DURATION = 300L // 5 minutes
private const val APPROVAL_TIMEOUT_MESSAGE = "Approval timeout (no response within 5 minutes)"

/**
 * Default implementation of [ToolCallOrchestrator].
 *
 * Handles approval resolution, timeout handling, status persistence, and execution for both
 * Local MCP and non-Local-MCP tool calls.
 *
 * @property toolCallDao DAO for persisting tool-call status transitions and results.
 * @property toolExecutorFactory Factory that resolves the executor for non-Local-MCP tools.
 * @property localMcpExecutor Executor for Local MCP tools that dispatches to the worker.
 * @property userToolApprovalPreferenceDao DAO for user auto-approval/denial preferences.
 * @property transactionScope Transaction scope used for preference lookup.
 */
class DefaultToolCallOrchestrator(
    private val toolCallDao: ToolCallDao,
    private val toolExecutorFactory: ToolExecutorFactory,
    private val localMcpExecutor: LocalMCPExecutor,
    private val userToolApprovalPreferenceDao: UserToolApprovalPreferenceDao,
    private val transactionScope: TransactionScope,
) : ToolCallOrchestrator {

    private val logger: Logger = LogManager.getLogger(DefaultToolCallOrchestrator::class.java)

    override fun executeAndUpdateToolCalls(
        userId: Long,
        pendingToolCalls: List<ToolCall>,
        toolDefinitions: List<ToolDefinition>?,
        toolApprovalFlow: Flow<ToolCallApprovalSubmission>
    ): Flow<ToolCallExecutionEvent> = channelFlow {
        pendingToolCalls.forEach { pendingToolCall ->
            // Skip if already processed
            if (pendingToolCall.status != ToolCallStatus.PENDING) {
                send(ToolCallExecutionEvent.ToolCallCompleted(pendingToolCall))
                return@forEach
            }

            // Resolve tool definition
            val toolDef = toolDefinitions?.find { it.id == pendingToolCall.toolDefinitionId }
                ?: throw IllegalStateException("Tool definition ${pendingToolCall.toolDefinitionId} not found for pending tool call")

            // Step 1: Resolve approval
            val approvalOutcome = when (toolDef) {
                is LocalMCPToolDefinition -> resolveLocalMcpApproval(pendingToolCall, toolApprovalFlow)
                else -> resolveNonLocalMcpApproval(userId, pendingToolCall, toolDef, toolApprovalFlow)
            }

            // Handle denial or timeout
            when (approvalOutcome) {
                is ApprovalOutcome.Denied -> {
                    persistAndEmitDeniedToolCall(pendingToolCall, approvalOutcome.reason)
                    return@forEach
                }

                is ApprovalOutcome.TimedOut -> {
                    persistAndEmitTimedOutToolCall(pendingToolCall)
                    return@forEach
                }

                is ApprovalOutcome.Approved -> {
                    // Continue to execution
                }
            }

            // Step 2: Mark as executing and emit event
            persistAndEmitExecutingToolCall(pendingToolCall)

            // Step 3: Execute based on tool type
            val completedToolCall = when (toolDef) {
                is LocalMCPToolDefinition -> {
                    val localApproval = approvalOutcome.submission as? ToolCallApprovalSubmission.LocalMcpSigned
                        ?: throw IllegalStateException(
                            "Local MCP tool call ${pendingToolCall.id} did not receive LocalMcpSigned approval"
                        )
                    executeLocalMcpTool(pendingToolCall, toolDef, localApproval)
                }

                else -> executeNonLocalMcpTool(pendingToolCall, toolDef)
            }

            // Step 4: Persist and emit completion
            toolCallDao.updateToolCall(completedToolCall).getOrElse { error ->
                throw IllegalStateException("Failed to update tool call: $error")
            }
            send(ToolCallExecutionEvent.ToolCallCompleted(completedToolCall))
        }
    }

    /**
     * Resolves the user's approval decision for a Local MCP tool call.
     *
     * Local MCP tools always require user approval and wait for a signed authorization from the app.
     * The signed submission may approve or deny the tool call; both outcomes are resolved here.
     * If no response is received within the timeout window, returns [ApprovalOutcome.TimedOut].
     *
     * @receiver Producer scope used to emit [ToolCallExecutionEvent.ToolCallApprovalRequested] events.
     * @param pendingToolCall The pending tool call awaiting approval.
     * @param toolApprovalFlow Normalized client approval submissions emitted by the chat WebSocket.
     * @return The resolved approval outcome.
     * @throws IllegalStateException if persisting the AWAITING_APPROVAL status fails.
     */
    private suspend fun ProducerScope<ToolCallExecutionEvent>.resolveLocalMcpApproval(
        pendingToolCall: ToolCall,
        toolApprovalFlow: Flow<ToolCallApprovalSubmission>
    ): ApprovalOutcome {
        val awaitingApprovalToolCall = pendingToolCall.copy(status = ToolCallStatus.AWAITING_APPROVAL)
        toolCallDao.updateToolCall(awaitingApprovalToolCall).getOrElse { error ->
            throw IllegalStateException("Failed to update tool call to AWAITING_APPROVAL: $error")
        }
        send(ToolCallExecutionEvent.ToolCallApprovalRequested(awaitingApprovalToolCall))

        return try {
            withTimeout(APPROVAL_TIMEOUT_DURATION.seconds) {
                val submission = toolApprovalFlow.first { submission ->
                    submission.toolCallId == pendingToolCall.id &&
                            submission is ToolCallApprovalSubmission.LocalMcpSigned
                }
                // Check the approval decision in the signed authorization
                if (submission.approved) {
                    ApprovalOutcome.Approved(submission)
                } else {
                    ApprovalOutcome.Denied(submission.denialReason)
                }
            }
        } catch (_: TimeoutCancellationException) {
            ApprovalOutcome.TimedOut
        }
    }

    /**
     * Resolves approval for a non-Local-MCP tool call.
     *
     * May auto-approve or auto-deny based on the user's stored preferences, or request a manual
     * Standard approval from the client when no preference exists.
     *
     * @receiver Producer scope used to emit [ToolCallExecutionEvent.ToolCallApprovalRequested] events.
     * @param userId User whose approval preferences may be consulted.
     * @param pendingToolCall The pending tool call awaiting approval.
     * @param toolDef Definition of the tool being called.
     * @param toolApprovalFlow Normalized client approval submissions emitted by the chat WebSocket.
     * @return The resolved approval outcome.
     * @throws IllegalStateException if persisting the AWAITING_APPROVAL status fails.
     */
    private suspend fun ProducerScope<ToolCallExecutionEvent>.resolveNonLocalMcpApproval(
        userId: Long,
        pendingToolCall: ToolCall,
        toolDef: ToolDefinition,
        toolApprovalFlow: Flow<ToolCallApprovalSubmission>
    ): ApprovalOutcome {
        // Check for auto-approval preference
        val preference = toolDef.id.let { toolDefId ->
            transactionScope.transaction {
                userToolApprovalPreferenceDao.getPreference(userId, toolDefId).getOrNull()
            }
        }

        if (preference != null) {
            logger.info(
                "Auto-${if (preference.autoApprove) "approving" else "denying"} tool call ${pendingToolCall.id} for user $userId based on preference"
            )

            return if (preference.autoApprove) {
                // Auto-approved: return synthetic approval
                ApprovalOutcome.Approved(
                    ToolCallApprovalSubmission.Standard(
                        ToolCallApprovalResponse(
                            toolCallId = pendingToolCall.id,
                            approved = true,
                            denialReason = null
                        )
                    )
                )
            } else {
                // Auto-denied: return denial outcome
                ApprovalOutcome.Denied(preference.denialReason ?: "Auto-denied by user preference")
            }
        }

        // No preference: request user approval
        val awaitingApprovalToolCall = pendingToolCall.copy(status = ToolCallStatus.AWAITING_APPROVAL)
        toolCallDao.updateToolCall(awaitingApprovalToolCall).getOrElse { error ->
            throw IllegalStateException("Failed to update tool call to AWAITING_APPROVAL: $error")
        }
        send(ToolCallExecutionEvent.ToolCallApprovalRequested(awaitingApprovalToolCall))

        return try {
            withTimeout(APPROVAL_TIMEOUT_DURATION.seconds) {
                val submission = toolApprovalFlow.first { submission ->
                    submission.toolCallId == pendingToolCall.id &&
                            submission is ToolCallApprovalSubmission.Standard
                }
                if (submission.approved) {
                    ApprovalOutcome.Approved(submission)
                } else {
                    ApprovalOutcome.Denied(submission.denialReason ?: "User denied tool call")
                }
            }
        } catch (_: TimeoutCancellationException) {
            ApprovalOutcome.TimedOut
        }
    }

    /**
     * Persists a denied tool call and emits a completion event.
     *
     * Updates the tool call to [ToolCallStatus.USER_DENIED] and emits the resulting event so the
     * chat flow can report the denial.
     *
     * @receiver Producer scope used to emit [ToolCallExecutionEvent.ToolCallCompleted].
     * @param toolCall The tool call to mark as denied.
     * @param denialReason Optional reason for the denial.
     * @throws IllegalStateException if persisting the denied status fails.
     */
    private suspend fun ProducerScope<ToolCallExecutionEvent>.persistAndEmitDeniedToolCall(
        toolCall: ToolCall,
        denialReason: String?
    ) {
        val deniedToolCall = toolCall.copy(
            status = ToolCallStatus.USER_DENIED,
            denialReason = denialReason
        )
        toolCallDao.updateToolCall(deniedToolCall).getOrElse { error ->
            throw IllegalStateException("Failed to update denied tool call: $error")
        }
        send(ToolCallExecutionEvent.ToolCallCompleted(deniedToolCall))
    }

    /**
     * Persists a timed-out tool call and emits a completion event.
     *
     * Updates the tool call to [ToolCallStatus.USER_DENIED] with a timeout message and emits
     * the resulting event.
     *
     * @receiver Producer scope used to emit [ToolCallExecutionEvent.ToolCallCompleted].
     * @param toolCall The tool call that timed out waiting for approval.
     * @throws IllegalStateException if persisting the denied status fails.
     */
    private suspend fun ProducerScope<ToolCallExecutionEvent>.persistAndEmitTimedOutToolCall(toolCall: ToolCall) {
        val deniedToolCall = toolCall.copy(
            status = ToolCallStatus.USER_DENIED,
            denialReason = APPROVAL_TIMEOUT_MESSAGE
        )
        toolCallDao.updateToolCall(deniedToolCall).getOrElse { error ->
            throw IllegalStateException("Failed to update tool call after timeout: $error")
        }
        send(ToolCallExecutionEvent.ToolCallCompleted(deniedToolCall))
    }

    /**
     * Marks a tool call as EXECUTING and emits the event.
     *
     * Updates the tool call to [ToolCallStatus.EXECUTING] and emits the resulting event so the
     * chat flow can report the start of execution.
     *
     * @receiver Producer scope used to emit [ToolCallExecutionEvent.ToolCallExecuting].
     * @param toolCall The tool call to mark as executing.
     * @throws IllegalStateException if persisting the EXECUTING status fails.
     */
    private suspend fun ProducerScope<ToolCallExecutionEvent>.persistAndEmitExecutingToolCall(toolCall: ToolCall) {
        val executingToolCall = toolCall.copy(status = ToolCallStatus.EXECUTING)
        toolCallDao.updateToolCall(executingToolCall).getOrElse { error ->
            throw IllegalStateException("Failed to update tool call to EXECUTING: $error")
        }
        send(ToolCallExecutionEvent.ToolCallExecuting(executingToolCall))
    }

    /**
     * Executes a Local MCP tool and returns the updated tool call with results.
     *
     * @param toolCall The tool call to execute.
     * @param toolDef The Local MCP tool definition to use.
     * @param approval The signed approval authorization for this execution.
     * @return The tool call with the execution output, status, and duration populated.
     */
    private suspend fun executeLocalMcpTool(
        toolCall: ToolCall,
        toolDef: LocalMCPToolDefinition,
        approval: ToolCallApprovalSubmission.LocalMcpSigned
    ): ToolCall {
        val startTime = Clock.System.now()
        return when (val event = localMcpExecutor.executeTool(
            toolDefinition = toolDef,
            toolCall = toolCall,
            signedAuthorization = approval.signedRequest
        )) {
            is LocalMCPExecutorEvent.ToolExecutionResult -> {
                val durationMs = (Clock.System.now() - startTime).inWholeMilliseconds
                toolCall.copy(
                    output = event.result.output,
                    status = if (event.result.isError) ToolCallStatus.ERROR else ToolCallStatus.SUCCESS,
                    errorMessage = event.result.errorMessage,
                    durationMs = durationMs
                )
            }

            is LocalMCPExecutorEvent.ToolExecutionError -> {
                val durationMs = (Clock.System.now() - startTime).inWholeMilliseconds
                toolCall.copy(
                    status = ToolCallStatus.ERROR,
                    errorMessage = event.error.message,
                    durationMs = durationMs
                )
            }
        }
    }

    /**
     * Executes a non-Local-MCP tool and returns the updated tool call with results.
     *
     * @param toolCall The tool call to execute.
     * @param toolDef The non-Local-MCP tool definition to use.
     * @return The tool call with the execution output, status, and duration populated.
     * @throws IllegalStateException if no executor can be resolved for the tool type.
     */
    private suspend fun executeNonLocalMcpTool(
        toolCall: ToolCall,
        toolDef: ToolDefinition
    ): ToolCall {
        val startTime = Clock.System.now()
        val executor = toolExecutorFactory.getExecutor(toolDef.type).getOrElse { error ->
            throw IllegalStateException("Failed to get executor for tool type ${toolDef.type}: $error")
        }

        val result = executor.executeTool(toolDef, toolCall.input)
        val endTime = Clock.System.now()
        val durationMs = (endTime - startTime).inWholeMilliseconds

        return result.fold(
            ifLeft = { error ->
                toolCall.copy(
                    status = ToolCallStatus.ERROR,
                    errorMessage = error.toString(),
                    durationMs = durationMs
                )
            },
            ifRight = { output ->
                toolCall.copy(
                    output = output,
                    status = ToolCallStatus.SUCCESS,
                    durationMs = durationMs
                )
            }
        )
    }

    /**
     * Internal sealed type representing the outcome of approval resolution.
     *
     * Used to simplify control flow in [executeAndUpdateToolCalls].
     */
    private sealed interface ApprovalOutcome {
        /**
         * Tool call was approved (either explicitly by the user or by auto-approval preference).
         *
         * @property submission The approval submission containing authorization details.
         */
        data class Approved(val submission: ToolCallApprovalSubmission) : ApprovalOutcome

        /**
         * Tool call was explicitly denied by the user or auto-denied by preference.
         *
         * @property reason Optional reason for the denial.
         */
        data class Denied(val reason: String?) : ApprovalOutcome

        /**
         * Approval request timed out (user did not respond within the timeout window).
         */
        data object TimedOut : ApprovalOutcome
    }
}

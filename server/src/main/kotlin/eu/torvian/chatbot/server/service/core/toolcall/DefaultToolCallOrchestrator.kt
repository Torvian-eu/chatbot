package eu.torvian.chatbot.server.service.core.toolcall

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.tool.*
import eu.torvian.chatbot.server.data.dao.ToolCallDao
import eu.torvian.chatbot.server.runtime.TurnControlSignal
import eu.torvian.chatbot.server.service.builtin.BuiltInWorkerToolExecutor
import eu.torvian.chatbot.server.service.builtin.BuiltInWorkerToolExecutorEvent
import eu.torvian.chatbot.server.service.builtin.OperatorToolExecutor
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolExecutor
import eu.torvian.chatbot.server.service.mcp.LocalMCPExecutor
import eu.torvian.chatbot.server.service.mcp.LocalMCPExecutorEvent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import kotlin.time.Clock

/**
 * Default implementation of [ToolCallOrchestrator].
 *
 * Handles approval resolution and execution for Local MCP, Built-in Worker, Operator, and Server
 * Built-In tool calls. Local MCP and built-in tools always require an app-signed authorization,
 * mirroring Local MCP; operator tools are approved with a plain
 * [ToolCallApprovalSubmission.OperatorToolApproval] (no worker dispatch) and executed by the
 * operator over the chat WebSocket; server built-in tools are approved with a plain
 * [ToolCallApprovalSubmission.ServerBuiltInApproval] and executed in-process by
 * [ServerBuiltInToolExecutor].
 *
 * Any tool definition that is neither a [LocalMCPToolDefinition], a [BuiltInWorkerToolDefinition],
 * an [OperatorToolDefinition] nor a [ServerBuiltInToolDefinition] is a configuration error and
 * causes an [IllegalStateException].
 *
 * @property toolCallDao DAO for persisting tool-call status transitions and results.
 * @property localMcpExecutor Executor for Local MCP tools that dispatches to the worker.
 * @property builtInWorkerToolExecutor Executor for built-in worker tools that dispatches to the worker.
 * @property operatorToolExecutor Executor for operator tools that relays execution to the operator.
 * @property serverBuiltInToolExecutor Executor for server built-in tools that runs handlers in-process.
 */
class DefaultToolCallOrchestrator(
    private val toolCallDao: ToolCallDao,
    private val localMcpExecutor: LocalMCPExecutor,
    private val builtInWorkerToolExecutor: BuiltInWorkerToolExecutor,
    private val operatorToolExecutor: OperatorToolExecutor,
    private val serverBuiltInToolExecutor: ServerBuiltInToolExecutor,
) : ToolCallOrchestrator {

    private val logger: Logger = LogManager.getLogger(DefaultToolCallOrchestrator::class.java)

    override fun executeAndUpdateToolCalls(
        userId: Long,
        requestingAgentRoleId: Long,
        pendingToolCalls: List<ToolCall>,
        toolDefinitions: List<ToolDefinition>?,
        toolApprovalFlow: Flow<ToolCallApprovalSubmission>,
        operatorToolResultFlow: Flow<OperatorToolExecutionResult>,
        controlSignal: TurnControlSignal
    ): Flow<ToolCallExecutionEvent> = channelFlow {
        try {
            for (pendingToolCall in pendingToolCalls) {
                if (controlSignal.isCancelled) break
                // Skip if already processed. Existing terminal rows are still returned so the
                // caller can pair them with the assistant request in the next LLM context.
                if (pendingToolCall.status != ToolCallStatus.PENDING) {
                    send(ToolCallExecutionEvent.ToolCallCompleted(pendingToolCall))
                    continue
                }

                // Resolve tool definition
                val toolDef = toolDefinitions?.find { it.id == pendingToolCall.toolDefinitionId }
                    ?: throw IllegalStateException("Tool definition ${pendingToolCall.toolDefinitionId} not found for pending tool call")

                // Step 1: Resolve approval
                val approvalOutcome = when (toolDef) {
                    is LocalMCPToolDefinition -> resolveLocalMcpApproval(
                        pendingToolCall,
                        toolApprovalFlow,
                        controlSignal
                    )

                    is BuiltInWorkerToolDefinition -> resolveBuiltInWorkerApproval(
                        pendingToolCall,
                        toolApprovalFlow,
                        controlSignal
                    )

                    is OperatorToolDefinition -> resolveOperatorToolApproval(
                        pendingToolCall,
                        toolApprovalFlow,
                        controlSignal
                    )

                    is ServerBuiltInToolDefinition -> resolveServerBuiltInToolApproval(
                        pendingToolCall,
                        toolApprovalFlow,
                        controlSignal
                    )
                }
                // Handle denial
                when (approvalOutcome) {
                    is ApprovalOutcome.Denied -> {
                        persistAndEmitDeniedToolCall(pendingToolCall, approvalOutcome.reason)
                        continue
                    }

                    is ApprovalOutcome.Approved -> {
                        // Continue to execution
                    }

                    ApprovalOutcome.Cancelled -> {
                        break
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

                    is BuiltInWorkerToolDefinition -> {
                        val builtInApproval =
                            approvalOutcome.submission as? ToolCallApprovalSubmission.BuiltInSigned
                                ?: throw IllegalStateException(
                                    "Built-in worker tool call ${pendingToolCall.id} did not receive BuiltInSigned approval"
                                )
                        executeBuiltInWorkerTool(pendingToolCall, toolDef, builtInApproval)
                    }

                    is OperatorToolDefinition -> {
                        executeOperatorTool(
                            userId = userId,
                            requestingAgentRoleId = requestingAgentRoleId,
                            toolCall = pendingToolCall,
                            operatorToolResultFlow = operatorToolResultFlow
                        )
                    }

                    is ServerBuiltInToolDefinition -> {
                        executeServerBuiltInTool(
                            userId = userId,
                            toolDefinition = toolDef,
                            toolCall = pendingToolCall
                        )
                    }
                }

                // Step 4: Persist and emit completion
                toolCallDao.updateToolCall(completedToolCall).getOrElse { error ->
                    throw IllegalStateException("Failed to update tool call: $error")
                }
                send(ToolCallExecutionEvent.ToolCallCompleted(completedToolCall))
            }
        } finally {
            // The initial batch is the source of truth for cleanup: every row that was not
            // terminal when execution began must be finalized, including calls not yet reached by
            // the sequential loop. Conditional updates preserve a completion that won the race.
            withContext(NonCancellable) {
                pendingToolCalls
                    .forEach { unfinishedToolCall ->
                        try {
                            val cancelledToolCall = unfinishedToolCall.copy(
                                status = ToolCallStatus.CANCELLED,
                                output = null,
                                errorMessage = "Tool call was cancelled before a result was produced.",
                                denialReason = null,
                                durationMs = unfinishedToolCall.durationMs
                            )
                            toolCallDao.updateToolCallIfStatusIn(
                                toolCall = cancelledToolCall,
                                expectedStatuses = setOf(
                                    ToolCallStatus.PENDING,
                                    ToolCallStatus.AWAITING_APPROVAL,
                                    ToolCallStatus.EXECUTING
                                )
                            ).takeIf { updatedRows -> updatedRows > 0 }?.let {
                                // Notify connected clients after persistence so the badge can show the
                                // terminal state before the cancelled WebSocket flow finishes.
                                send(ToolCallExecutionEvent.ToolCallCompleted(cancelledToolCall))
                            }
                        } catch (exception: Exception) {
                            // Cleanup is best effort: one failed row or closed socket must not
                            // prevent the remaining calls from being finalized.
                            logger.error(
                                "Failed to finalize cancelled tool call ${unfinishedToolCall.id}",
                                exception
                            )
                        }
                    }
            }
        }
    }

    /**
     * Resolves the user's approval decision for a Local MCP tool call.
     */
    private suspend fun ProducerScope<ToolCallExecutionEvent>.resolveLocalMcpApproval(
        pendingToolCall: ToolCall,
        toolApprovalFlow: Flow<ToolCallApprovalSubmission>,
        controlSignal: TurnControlSignal
    ): ApprovalOutcome {
        val awaitingApprovalToolCall = pendingToolCall.copy(status = ToolCallStatus.AWAITING_APPROVAL)
        toolCallDao.updateToolCall(awaitingApprovalToolCall).getOrElse { error ->
            throw IllegalStateException("Failed to update tool call to AWAITING_APPROVAL: $error")
        }
        send(ToolCallExecutionEvent.ToolCallApprovalRequested(awaitingApprovalToolCall))

        val submission = awaitApprovalOrCancellation(
            toolApprovalFlow = toolApprovalFlow,
            controlSignal = controlSignal,
            matches = { submission ->
                submission.toolCallId == pendingToolCall.id &&
                        submission is ToolCallApprovalSubmission.LocalMcpSigned
            }
        )
        if (submission == null || controlSignal.isCancelled) return ApprovalOutcome.Cancelled
        return if (submission.approved) {
            ApprovalOutcome.Approved(submission)
        } else {
            ApprovalOutcome.Denied(submission.denialReason)
        }
    }

    /**
     * Resolves the user's approval decision for a built-in worker tool call.
     */
    private suspend fun ProducerScope<ToolCallExecutionEvent>.resolveBuiltInWorkerApproval(
        pendingToolCall: ToolCall,
        toolApprovalFlow: Flow<ToolCallApprovalSubmission>,
        controlSignal: TurnControlSignal
    ): ApprovalOutcome {
        val awaitingApprovalToolCall = pendingToolCall.copy(status = ToolCallStatus.AWAITING_APPROVAL)
        toolCallDao.updateToolCall(awaitingApprovalToolCall).getOrElse { error ->
            throw IllegalStateException("Failed to update tool call to AWAITING_APPROVAL: $error")
        }
        send(ToolCallExecutionEvent.ToolCallApprovalRequested(awaitingApprovalToolCall))

        val submission = awaitApprovalOrCancellation(
            toolApprovalFlow = toolApprovalFlow,
            controlSignal = controlSignal,
            matches = { submission ->
                submission.toolCallId == pendingToolCall.id &&
                        submission is ToolCallApprovalSubmission.BuiltInSigned
            }
        )
        if (submission == null || controlSignal.isCancelled) return ApprovalOutcome.Cancelled
        return if (submission.approved) {
            ApprovalOutcome.Approved(submission)
        } else {
            ApprovalOutcome.Denied(submission.denialReason)
        }
    }

    /**
     * Resolves the user's approval decision for an operator tool call.
     */
    private suspend fun ProducerScope<ToolCallExecutionEvent>.resolveOperatorToolApproval(
        pendingToolCall: ToolCall,
        toolApprovalFlow: Flow<ToolCallApprovalSubmission>,
        controlSignal: TurnControlSignal
    ): ApprovalOutcome {
        val awaitingApprovalToolCall = pendingToolCall.copy(status = ToolCallStatus.AWAITING_APPROVAL)
        toolCallDao.updateToolCall(awaitingApprovalToolCall).getOrElse { error ->
            throw IllegalStateException("Failed to update tool call to AWAITING_APPROVAL: $error")
        }
        send(ToolCallExecutionEvent.ToolCallApprovalRequested(awaitingApprovalToolCall))

        val submission = awaitApprovalOrCancellation(
            toolApprovalFlow = toolApprovalFlow,
            controlSignal = controlSignal,
            matches = { submission ->
                submission.toolCallId == pendingToolCall.id &&
                        submission is ToolCallApprovalSubmission.OperatorToolApproval
            }
        )
        if (submission == null || controlSignal.isCancelled) return ApprovalOutcome.Cancelled
        return if (submission.approved) {
            ApprovalOutcome.Approved(submission)
        } else {
            ApprovalOutcome.Denied(submission.denialReason)
        }
    }

    /**
     * Resolves the user's approval decision for a server built-in tool call.
     */
    private suspend fun ProducerScope<ToolCallExecutionEvent>.resolveServerBuiltInToolApproval(
        pendingToolCall: ToolCall,
        toolApprovalFlow: Flow<ToolCallApprovalSubmission>,
        controlSignal: TurnControlSignal
    ): ApprovalOutcome {
        val awaitingApprovalToolCall = pendingToolCall.copy(status = ToolCallStatus.AWAITING_APPROVAL)
        toolCallDao.updateToolCall(awaitingApprovalToolCall).getOrElse { error ->
            throw IllegalStateException("Failed to update tool call to AWAITING_APPROVAL: $error")
        }
        send(ToolCallExecutionEvent.ToolCallApprovalRequested(awaitingApprovalToolCall))

        val submission = awaitApprovalOrCancellation(
            toolApprovalFlow = toolApprovalFlow,
            controlSignal = controlSignal,
            matches = { submission ->
                submission.toolCallId == pendingToolCall.id &&
                        submission is ToolCallApprovalSubmission.ServerBuiltInApproval
            }
        )
        if (submission == null || controlSignal.isCancelled) return ApprovalOutcome.Cancelled
        return if (submission.approved) {
            ApprovalOutcome.Approved(submission)
        } else {
            ApprovalOutcome.Denied(submission.denialReason)
        }
    }

    /**
     * Waits for the matching approval or for cooperative cancellation of the active turn.
     *
     * @param toolApprovalFlow Client approval submissions.
     * @param controlSignal Signal that completes when the client stops the turn.
     * @param matches Predicate identifying the approval for the current tool call.
     * @return Matching approval, or `null` when cancellation wins the race.
     */
    private suspend fun awaitApprovalOrCancellation(
        toolApprovalFlow: Flow<ToolCallApprovalSubmission>,
        controlSignal: TurnControlSignal,
        matches: (ToolCallApprovalSubmission) -> Boolean
    ): ToolCallApprovalSubmission? = coroutineScope {
        val approval = async {
            toolApprovalFlow.first { matches(it) }
        }
        val cancellation = async {
            controlSignal.awaitCancelled()
            null
        }
        select {
            approval.onAwait { result -> result }
            cancellation.onAwait { null }
        }.also {
            approval.cancel()
            cancellation.cancel()
        }
    }

    /**
     * Persists a denied tool call and emits a completion event.
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
     * Marks a tool call as EXECUTING and emits the event.
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
                    errorCode = event.result.errorCode,
                    errorDetails = event.result.errorDetails,
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
     * Executes a built-in worker tool and returns the updated tool call with results.
     */
    private suspend fun executeBuiltInWorkerTool(
        toolCall: ToolCall,
        toolDef: BuiltInWorkerToolDefinition,
        approval: ToolCallApprovalSubmission.BuiltInSigned
    ): ToolCall {
        val startTime = Clock.System.now()
        return when (val event = builtInWorkerToolExecutor.executeTool(
            toolDefinition = toolDef,
            toolCall = toolCall,
            signedRequest = approval.signedRequest
        )) {
            is BuiltInWorkerToolExecutorEvent.ToolExecutionResult -> {
                val durationMs = (Clock.System.now() - startTime).inWholeMilliseconds
                toolCall.copy(
                    output = event.result.output,
                    status = if (event.result.isError) ToolCallStatus.ERROR else ToolCallStatus.SUCCESS,
                    errorMessage = event.result.errorMessage,
                    errorCode = event.result.errorCode,
                    errorDetails = event.result.errorDetails,
                    durationMs = durationMs
                )
            }

            is BuiltInWorkerToolExecutorEvent.ToolExecutionError -> {
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
     * Executes an operator tool by relaying execution to the operator and awaiting the result.
     */
    private suspend fun ProducerScope<ToolCallExecutionEvent>.executeOperatorTool(
        userId: Long,
        requestingAgentRoleId: Long,
        toolCall: ToolCall,
        operatorToolResultFlow: Flow<OperatorToolExecutionResult>
    ): ToolCall = operatorToolExecutor.executeTool(
        userId = userId,
        requestingAgentRoleId = requestingAgentRoleId,
        toolCall = toolCall,
        emitEvent = { event -> send(event) },
        operatorToolResultFlow = operatorToolResultFlow
    )

    /**
     * Executes a server built-in tool in-process and returns the updated tool call with results.
     *
     * The already-resolved [ServerBuiltInToolDefinition] is passed through so the executor can
     * dispatch on the canonical [ServerBuiltInToolDefinition.builtInToolName] without any further
     * lookup; the orchestrator resolved the definition for approval, so no extra query is
     * introduced.
     */
    private suspend fun executeServerBuiltInTool(
        userId: Long,
        toolDefinition: ServerBuiltInToolDefinition,
        toolCall: ToolCall
    ): ToolCall = serverBuiltInToolExecutor.executeTool(
        userId = userId,
        toolDefinition = toolDefinition,
        toolCall = toolCall
    )

    /**
     * Internal sealed type representing the outcome of approval resolution.
     */
    private sealed interface ApprovalOutcome {
        data class Approved(val submission: ToolCallApprovalSubmission) : ApprovalOutcome
        data class Denied(val reason: String?) : ApprovalOutcome
        data object Cancelled : ApprovalOutcome
    }
}

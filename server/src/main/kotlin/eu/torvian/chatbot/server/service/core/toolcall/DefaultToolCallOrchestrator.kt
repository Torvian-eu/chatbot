package eu.torvian.chatbot.server.service.core.toolcall

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.tool.*
import eu.torvian.chatbot.server.data.dao.ToolCallDao
import eu.torvian.chatbot.server.service.builtin.BuiltInWorkerToolExecutor
import eu.torvian.chatbot.server.service.builtin.BuiltInWorkerToolExecutorEvent
import eu.torvian.chatbot.server.service.mcp.LocalMCPExecutor
import eu.torvian.chatbot.server.service.mcp.LocalMCPExecutorEvent
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import kotlin.time.Clock

/**
 * Default implementation of [ToolCallOrchestrator].
 *
 * Handles approval resolution and execution for both Local MCP and Built-in Worker tool calls.
 * Built-in tools always require an app-signed authorization, mirroring Local MCP.
 *
 * Any tool definition that is neither a [LocalMCPToolDefinition] nor a
 * [BuiltInWorkerToolDefinition] is a configuration error and causes an [IllegalStateException].
 *
 * @property toolCallDao DAO for persisting tool-call status transitions and results.
 * @property localMcpExecutor Executor for Local MCP tools that dispatches to the worker.
 * @property builtInWorkerToolExecutor Executor for built-in worker tools that dispatches to the worker.
 */
class DefaultToolCallOrchestrator(
    private val toolCallDao: ToolCallDao,
    private val localMcpExecutor: LocalMCPExecutor,
    private val builtInWorkerToolExecutor: BuiltInWorkerToolExecutor,
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
                is BuiltInWorkerToolDefinition -> resolveBuiltInWorkerApproval(pendingToolCall, toolApprovalFlow)
            }
            // Handle denial
            when (approvalOutcome) {
                is ApprovalOutcome.Denied -> {
                    persistAndEmitDeniedToolCall(pendingToolCall, approvalOutcome.reason)
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

                is BuiltInWorkerToolDefinition -> {
                    val builtInApproval = approvalOutcome.submission as? ToolCallApprovalSubmission.BuiltInSigned
                        ?: throw IllegalStateException(
                            "Built-in worker tool call ${pendingToolCall.id} did not receive BuiltInSigned approval"
                        )
                    executeBuiltInWorkerTool(pendingToolCall, toolDef, builtInApproval)
                }

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

        val submission = toolApprovalFlow.first { submission ->
            submission.toolCallId == pendingToolCall.id &&
                    submission is ToolCallApprovalSubmission.LocalMcpSigned
        }
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
        toolApprovalFlow: Flow<ToolCallApprovalSubmission>
    ): ApprovalOutcome {
        val awaitingApprovalToolCall = pendingToolCall.copy(status = ToolCallStatus.AWAITING_APPROVAL)
        toolCallDao.updateToolCall(awaitingApprovalToolCall).getOrElse { error ->
            throw IllegalStateException("Failed to update tool call to AWAITING_APPROVAL: $error")
        }
        send(ToolCallExecutionEvent.ToolCallApprovalRequested(awaitingApprovalToolCall))

        val submission = toolApprovalFlow.first { submission ->
            submission.toolCallId == pendingToolCall.id &&
                    submission is ToolCallApprovalSubmission.BuiltInSigned
        }
        return if (submission.approved) {
            ApprovalOutcome.Approved(submission)
        } else {
            ApprovalOutcome.Denied(submission.denialReason)
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
                    errorDetails = event.result.errorDetails?.toString(),
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
     * Internal sealed type representing the outcome of approval resolution.
     */
    private sealed interface ApprovalOutcome {
        data class Approved(val submission: ToolCallApprovalSubmission) : ApprovalOutcome
        data class Denied(val reason: String?) : ApprovalOutcome
    }
}

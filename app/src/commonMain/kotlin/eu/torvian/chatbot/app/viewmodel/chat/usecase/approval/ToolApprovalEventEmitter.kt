package eu.torvian.chatbot.app.viewmodel.chat.usecase.approval

import eu.torvian.chatbot.app.repository.ToolRepository
import eu.torvian.chatbot.app.service.security.RequestSigningService
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.api.core.ChatClientEvent
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolExecutionAuthorization
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionAuthorization
import eu.torvian.chatbot.common.models.tool.*
import eu.torvian.chatbot.common.security.SignedRequest
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Emits the typed approval event that authorizes or denies one tool call.
 *
 * The event kind follows the tool's own kind: built-in worker and Local MCP tools are authorized with an
 * on-device signed request, while operator and server built-in tools are authorized with a plain event.
 *
 * @property toolRepository Repository used to resolve tool definitions and cached approval preferences.
 * @property requestSigningService Service that signs Local MCP and built-in worker authorization payloads.
 * @property state Shared chat state, read for the active session.
 * @property clientEventFlow Shared outbound stream that carries the approval events to the server.
 * @property notificationService Notification sink for authorization failures.
 * @property resolvePendingApproval Callback that clears the call from the approvals the turn still awaits.
 */
internal class ToolApprovalEventEmitter(
    private val toolRepository: ToolRepository,
    private val requestSigningService: RequestSigningService,
    private val state: ChatState,
    private val clientEventFlow: MutableSharedFlow<ChatClientEvent>,
    private val notificationService: NotificationService,
    private val resolvePendingApproval: (ToolCall) -> Unit
) {

    /** Diagnostics logger for approval-event emission. */
    private val logger = kmpLogger<ToolApprovalEventEmitter>()

    /**
     * Emits the typed approval event matching [toolCall]'s tool kind.
     *
     * Built-in worker and Local MCP tools are authorized with an on-device signed request; operator
     * tools are authorized with a plain [ChatClientEvent.OperatorToolCallApproval] because they are
     * executed by the operator over the chat socket, not dispatched to a worker; server built-in
     * tools are authorized with a plain [ChatClientEvent.ServerBuiltInToolCallApproval] because they
     * are executed in-process on the server.
     *
     * @param toolCall Tool call the user approved or denied.
     * @param approved Whether execution should proceed.
     * @param denialReason Optional denial reason supplied by the user or an auto-deny preference.
     */
    internal suspend fun emitApprovalEvent(
        toolCall: ToolCall,
        approved: Boolean,
        denialReason: String?
    ) {
        // The user has made their decision, so the call leaves the "requesting input" set before the
        // outcome is relayed, even if the relay below cannot be emitted.
        resolvePendingApproval(toolCall)
        when (val toolDefinition = findToolDefinition(toolCall)) {
            is BuiltInWorkerToolDefinition -> {
                // Built-in worker tools require an on-device cryptographic signature before execution.
                emitBuiltInApprovalEvent(
                    toolCall = toolCall,
                    toolDefinition = toolDefinition,
                    approved = approved,
                    denialReason = denialReason
                )
            }

            is OperatorToolDefinition -> {
                // Operator tools run over the chat socket, so the operator approval needs no signature.
                clientEventFlow.emit(
                    ChatClientEvent.OperatorToolCallApproval(
                        toolCallId = toolCall.id,
                        approved = approved,
                        denialReason = denialReason
                    )
                )
            }

            is ServerBuiltInToolDefinition -> {
                // Server built-in tools are executed in-process on the server, so the approval is
                // plain (no signature, no operator relay), mirroring the operator-tool UX.
                clientEventFlow.emit(
                    ChatClientEvent.ServerBuiltInToolCallApproval(
                        toolCallId = toolCall.id,
                        approved = approved,
                        denialReason = denialReason
                    )
                )
            }

            is LocalMCPToolDefinition -> {
                // Local MCP tools are dispatched to a worker and therefore require a signed authorization.
                emitLocalMcpApprovalEvent(
                    toolCall = toolCall,
                    toolDefinition = toolDefinition,
                    approved = approved,
                    denialReason = denialReason
                )
            }

            // A missing or future unsupported definition cannot be authorized safely.
            else -> {
                logger.warn(
                    "No tool definition resolved for tool call ${toolCall.id} (${toolCall.toolName}); cannot emit approval"
                )
                notificationService.genericError(
                    shortMessage = "Failed to authorize tool call",
                    detailedMessage = "No tool definition could be resolved for tool call ${toolCall.id} (${toolCall.toolName})."
                )
            }
        }
    }

    /**
     * Builds, signs, and emits a Local MCP authorization event for one tool call.
     *
     * The typed [LocalMCPToolExecutionAuthorization] is created locally and signed to produce
     * a detached [SignedRequest]. Only the signed request is emitted to the server, which relays it
     * to the worker. The worker verifies the signature and decodes the authorization from
     * the signed payload as the sole source of truth for execution parameters.
     *
     * @param toolCall Tool call the app is authorizing.
     * @param toolDefinition Resolved Local MCP tool definition.
     * @param approved Whether execution should proceed.
     * @param denialReason Optional denial reason supplied by the user or an auto-deny preference.
     */
    internal suspend fun emitLocalMcpApprovalEvent(
        toolCall: ToolCall,
        toolDefinition: LocalMCPToolDefinition,
        approved: Boolean,
        denialReason: String?
    ) {
        val currentSession = state.currentSession.value
        if (currentSession == null) {
            notificationService.genericError(
                shortMessage = "Failed to authorize Local MCP tool call",
                detailedMessage = "No active session is available for tool call ${toolCall.id}."
            )
            return
        }

        // Build typed authorization locally for signing
        val authorization = LocalMCPToolExecutionAuthorization(
            toolCallId = toolCall.id,
            sessionId = currentSession.id,
            messageId = toolCall.messageId,
            toolDefinitionId = toolDefinition.id,
            toolName = toolCall.toolName,
            serverId = toolDefinition.serverId,
            mcpToolName = toolDefinition.mcpToolName,
            input = toolCall.input,
            approved = approved,
            denialReason = denialReason
        )

        val signedRequest = requestSigningService.signRequest(
            request = authorization,
            serializer = LocalMCPToolExecutionAuthorization.serializer()
        ).fold(
            ifLeft = { error ->
                notificationService.genericError(
                    shortMessage = "Failed to authorize Local MCP tool call",
                    detailedMessage = error.message,
                    originalThrowable = error.cause
                )
                return
            },
            ifRight = { it }
        )

        // Emit only the signed request; the typed authorization is serialized in signedRequest.payload
        clientEventFlow.emit(
            ChatClientEvent.LocalMcpToolCallApproval(
                signedRequest = signedRequest
            )
        )
    }

    /**
     * Builds, signs, and emits a built-in worker tool authorization event for one tool call.
     *
     * The typed [BuiltInToolExecutionAuthorization] is created locally and signed on-device to produce a
     * detached [SignedRequest]. Only the signed request is emitted to the server, which relays it to the
     * worker. The worker verifies the signature and decodes the authorization from the signed payload as the
     * sole source of truth for execution parameters, ensuring the client device authorized the exact call.
     *
     * @param toolCall Tool call the app is authorizing.
     * @param toolDefinition Resolved built-in worker tool definition.
     * @param approved Whether execution should proceed.
     * @param denialReason Optional denial reason supplied by the user or an auto-deny preference.
     */
    internal suspend fun emitBuiltInApprovalEvent(
        toolCall: ToolCall,
        toolDefinition: BuiltInWorkerToolDefinition,
        approved: Boolean,
        denialReason: String?
    ) {
        val currentSession = state.currentSession.value
        if (currentSession == null) {
            notificationService.genericError(
                shortMessage = "Failed to authorize built-in tool call",
                detailedMessage = "No active session is available for tool call ${toolCall.id}."
            )
            return
        }

        // Build typed authorization locally for signing
        val authorization = BuiltInToolExecutionAuthorization(
            toolCallId = toolCall.id,
            sessionId = currentSession.id,
            messageId = toolCall.messageId,
            toolDefinitionId = toolDefinition.id,
            toolName = toolCall.toolName,
            workerId = toolDefinition.workerId,
            builtInToolName = toolDefinition.builtInToolName,
            input = toolCall.input,
            approved = approved,
            denialReason = denialReason
        )

        val signedRequest = requestSigningService.signRequest(
            request = authorization,
            serializer = BuiltInToolExecutionAuthorization.serializer()
        ).fold(
            ifLeft = { error ->
                notificationService.genericError(
                    shortMessage = "Failed to authorize built-in tool call",
                    detailedMessage = error.message,
                    originalThrowable = error.cause
                )
                return
            },
            ifRight = { it }
        )

        // Emit only the signed request; the typed authorization is serialized in signedRequest.payload
        clientEventFlow.emit(
            ChatClientEvent.BuiltInToolCallApproval(
                signedRequest = signedRequest
            )
        )
    }

    /**
     * Resolves the persisted definition for a tool call from the current cache or the repository.
     *
     * The caller performs the subtype dispatch because all approval paths share the same lookup
     * semantics, while the subtype determines whether signing or operator relaying is required.
     * A cached definition is authoritative for its ID; this avoids making one repository request
     * for each possible tool category.
     *
     * @param toolCall Tool call whose definition should be resolved.
     * @return Matching tool definition, or `null` when the call has no definition ID or resolution fails.
     */
    internal suspend fun findToolDefinition(toolCall: ToolCall): ToolDefinition? {
        val toolDefinitionId = toolCall.toolDefinitionId ?: return null
        val cachedDefinition = toolRepository.tools.value.dataOrNull
            ?.firstOrNull { toolDefinition -> toolDefinition.id == toolDefinitionId }
        if (cachedDefinition != null) {
            return cachedDefinition
        }

        return toolRepository.getToolById(toolDefinitionId).fold(
            ifLeft = { null },
            ifRight = { it }
        )
    }

    /**
     * Returns the cached approval preference for [toolDefinitionId], if one is currently loaded.
     *
     * @param toolDefinitionId Tool definition whose preference should be inspected.
     * @return Matching approval preference or `null` when no preference is cached.
     */
    internal fun findApprovalPreference(toolDefinitionId: Long): UserToolApprovalPreference? {
        return toolRepository.toolApprovalPreferences.value.dataOrNull
            ?.firstOrNull { preference -> preference.toolDefinitionId == toolDefinitionId }
    }
}

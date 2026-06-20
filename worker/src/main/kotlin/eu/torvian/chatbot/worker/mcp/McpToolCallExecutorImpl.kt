package eu.torvian.chatbot.worker.mcp

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallResult
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolExecutionAuthorization
import eu.torvian.chatbot.common.security.SignedRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Default implementation that maps Local MCP tool execution authorization and runtime calls to outcomes.
 *
 * The executor validates the signed authorization once, then executes the tool using the decoded
 * authorization as the single source of truth for execution parameters:
 * - malformed JSON input produces an immediate logical error result;
 * - runtime errors become logical tool-call errors;
 * - MCP error outcomes map to `isError = true` with structured details;
 * - successful outcomes use textual content when present, otherwise a small success JSON payload.
 *
 * @property authorizationValidator Validator that enforces fresh detached app authorization for every Local MCP execution.
 * @property runtimeService Runtime service responsible for tool-call orchestration.
 * @property json JSON parser for request argument payload.
 */
class McpToolCallExecutorImpl(
    private val authorizationValidator: LocalMCPToolExecutionAuthorizationValidator,
    private val runtimeService: McpRuntimeService,
    private val json: Json
) : McpToolCallExecutor {

    companion object {
        /** Worker logger used for security-relevant Local MCP execution decisions. */
        private val logger: Logger = LogManager.getLogger(McpToolCallExecutorImpl::class.java)
    }

    override suspend fun execute(signedRequest: SignedRequest): LocalMCPToolCallResult {
        when (val authorizationResult = authorizationValidator.validate(signedRequest)) {
            is LocalMCPToolExecutionAuthorizationValidationResult.Authorized -> {
                // Execute using the decoded authorization as source of truth
                return executeAuthorizedToolCall(authorizationResult.authorization)
            }
            is LocalMCPToolExecutionAuthorizationValidationResult.Rejected -> {
                logger.warn(
                    "Rejected Local MCP tool call authorization code={} details={}",
                    authorizationResult.code,
                    authorizationResult.details ?: "none"
                )
                return LocalMCPToolCallResult(
                    toolCallId = authorizationResult.toolCallId ?: 0,
                    isError = true,
                    errorMessage = authorizationResult.message,
                    errorCode = authorizationResult.code,
                    errorDetails = authorizationResult.details
                )
            }
        }
    }

    /**
     * Executes a tool call using the decoded and verified authorization as the sole source of truth.
     *
     * @param auth Decoded and verified authorization from the app signature.
     * @return Tool execution result with proper error handling.
     */
    private suspend fun executeAuthorizedToolCall(
        auth: LocalMCPToolExecutionAuthorization
    ): LocalMCPToolCallResult {

        // Parse input JSON from the authorized payload
        val arguments = auth.input
            ?.let { input ->
                try {
                    json.parseToJsonElement(input).jsonObject
                } catch (exception: Exception) {
                    return LocalMCPToolCallResult(
                        toolCallId = auth.toolCallId,
                        isError = true,
                        errorMessage = "Malformed JSON input: ${exception.message}"
                    )
                }
            }
            ?: JsonObject(emptyMap())

        return runtimeService.callTool(
            serverId = auth.serverId,
            toolName = auth.mcpToolName,
            arguments = arguments
        ).fold(
            ifLeft = { error ->
                LocalMCPToolCallResult(
                    toolCallId = auth.toolCallId,
                    isError = true,
                    errorMessage = error.message
                )
            },
            ifRight = { outcome ->
                when {
                    outcome?.isError == true -> {
                        LocalMCPToolCallResult(
                            toolCallId = auth.toolCallId,
                            isError = true,
                            errorMessage = outcome.structuredContent ?: "Unknown error"
                        )
                    }

                    else -> {
                        LocalMCPToolCallResult(
                            toolCallId = auth.toolCallId,
                            output = outcome?.textContent?.takeIf { it.isNotBlank() } ?: "{\"result\":\"Success\"}"
                        )
                    }
                }
            }
        )
    }
}
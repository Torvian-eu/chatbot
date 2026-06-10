package eu.torvian.chatbot.worker.mcp

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Default implementation that maps local MCP tool-call requests to runtime outcomes.
 *
 * This semantic mapper reuses the app-side mediator mapping logic in worker context:
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

    override suspend fun execute(request: LocalMCPToolCallRequest): LocalMCPToolCallResult {
        when (val authorizationResult = authorizationValidator.validate(request)) {
            LocalMCPToolExecutionAuthorizationValidationResult.Authorized -> Unit
            is LocalMCPToolExecutionAuthorizationValidationResult.Rejected -> {
                logger.warn(
                    "Rejected Local MCP tool call authorization for toolCallId={} code={} details={}",
                    request.toolCallId,
                    authorizationResult.code,
                    authorizationResult.details ?: "none"
                )
                return LocalMCPToolCallResult(
                    toolCallId = request.toolCallId,
                    isError = true,
                    errorMessage = authorizationResult.message,
                    errorCode = authorizationResult.code,
                    errorDetails = authorizationResult.details
                )
            }
        }

        val arguments = request.inputJson
            ?.let { input ->
                try {
                    json.parseToJsonElement(input).jsonObject
                } catch (exception: Exception) {
                    return LocalMCPToolCallResult(
                        toolCallId = request.toolCallId,
                        isError = true,
                        errorMessage = "Malformed JSON input: ${exception.message}"
                    )
                }
            }
            ?: JsonObject(emptyMap())

        return runtimeService.callTool(
            serverId = request.serverId,
            toolName = request.mcpToolName,
            arguments = arguments
        ).fold(
            ifLeft = { error ->
                LocalMCPToolCallResult(
                    toolCallId = request.toolCallId,
                    isError = true,
                    errorMessage = error.message
                )
            },
            ifRight = { outcome ->
                when {
                    outcome?.isError == true -> {
                        LocalMCPToolCallResult(
                            toolCallId = request.toolCallId,
                            isError = true,
                            errorMessage = outcome.structuredContent ?: "Unknown error"
                        )
                    }

                    else -> {
                        LocalMCPToolCallResult(
                            toolCallId = request.toolCallId,
                            output = outcome?.textContent?.takeIf { it.isNotBlank() } ?: "{\"result\":\"Success\"}"
                        )
                    }
                }
            }
        )
    }
}
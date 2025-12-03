package eu.torvian.chatbot.app.service.mcp

import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.tool.LocalMCPToolCallRequest
import eu.torvian.chatbot.common.models.tool.LocalMCPToolCallResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class LocalMCPToolCallMediatorImpl(
    private val mcpClientService: MCPClientService,
    private val json: Json
) : LocalMCPToolCallMediator {
    companion object {
        private val logger = kmpLogger<LocalMCPToolCallMediatorImpl>()
    }

    override fun mediate(requestFlow: Flow<LocalMCPToolCallRequest>): Flow<LocalMCPToolCallResult> {
        return requestFlow.map { request ->
            // Parse input JSON
            val arguments = request.inputJson?.let { jsonString ->
                try {
                    json.parseToJsonElement(jsonString).jsonObject
                } catch (e: Exception) {
                    logger.warn("Invalid JSON in arguments for tool call ${request.toolCallId} : ${e.message}")
                    // Invalid JSON - return error
                    return@map LocalMCPToolCallResult(
                        toolCallId = request.toolCallId,
                        isError = true,
                        errorMessage = "Malformed JSON input"
                    )
                }
            } ?: JsonObject(emptyMap())

            // Execute tool call
            mcpClientService.callTool(
                serverId = request.serverId,
                toolName = request.toolName,
                arguments = arguments
            ).fold(
                ifLeft = { error ->
                    logger.error("Failed to execute tool call ${request.toolCallId}: ${error.message}")
                    LocalMCPToolCallResult(
                        toolCallId = request.toolCallId,
                        isError = true,
                        errorMessage = "Failed to execute tool call"
                    )
                },
                ifRight = { result ->
                    if (result?.isError == true) {
                        logger.debug("Tool call ${request.toolCallId} failed: ${result.structuredContent}")
                        LocalMCPToolCallResult(
                            toolCallId = request.toolCallId,
                            isError = true,
                            errorMessage = result.structuredContent?.toString() ?: "Unknown error"
                        )
                    } else {
                        logger.debug("Tool call ${request.toolCallId} succeeded")
                        val textContent = result?.content
                            ?.filterIsInstance<TextContent>()
                            ?.firstOrNull()
                            ?.text
                            ?.takeIf { it.isNotBlank() }
                            ?: """{"result": "Success"}"""
                        LocalMCPToolCallResult(
                            toolCallId = request.toolCallId,
                            output = textContent
                        )
                    }
                }
            )
        }
    }
}

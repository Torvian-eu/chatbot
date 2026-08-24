package eu.torvian.chatbot.server.service.builtin

import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Default implementation of [ServerBuiltInToolExecutor].
 *
 * A thin, stateless dispatcher over the Koin-provided [tools] map: it resolves [ToolCall.toolName]
 * (unique within a user's tool set; equals the canonical [ServerBuiltInToolCatalog] name) to the
 * matching [ServerBuiltInTool] implementation and delegates the call. Each tool owns its user-scoped
 * handler and its own injected dependencies, so adding a new server built-in tool never requires
 * modifying this dispatcher — mirroring the worker-side
 * [eu.torvian.chatbot.worker.builtin.DefaultBuiltInToolCallExecutor] registry pattern.
 *
 * Unknown tool names and malformed inputs produce a terminal ERROR [ToolCall] carrying an
 * LLM-readable JSON error object — never thrown.
 *
 * @property json Shared JSON codec used to parse tool inputs.
 * @property tools Registry mapping the catalog tool name to its implementation.
 */
class DefaultServerBuiltInToolExecutor(
    private val json: Json,
    private val tools: Map<String, ServerBuiltInTool>,
) : ServerBuiltInToolExecutor {

    private companion object {
        /** Logger used for executor diagnostics. */
        private val logger: Logger = LogManager.getLogger(DefaultServerBuiltInToolExecutor::class.java)
    }

    override suspend fun executeTool(userId: Long, toolCall: ToolCall): ToolCall {
        val startTime = Clock.System.now()

        val tool = tools[toolCall.toolName]
        if (tool == null) {
            logger.warn(
                "Unsupported server built-in tool '${toolCall.toolName}' for tool call ${toolCall.id}"
            )
            return toolCall.toErrorResult(
                error = ServerBuiltInToolHandlerError.InvalidInput(
                    "Unsupported server built-in tool '${toolCall.toolName}'. Supported tools: " +
                        tools.keys.sorted().joinToString(", ") + "."
                ),
                startTime = startTime
            )
        }

        val input = parseInput(toolCall.input)
        if (input == null) {
            return toolCall.toErrorResult(
                error = ServerBuiltInToolHandlerError.InvalidInput(
                    "Tool input must be a JSON object, got: ${toolCall.input ?: "null"}"
                ),
                startTime = startTime
            )
        }

        return tool.execute(userId, input).fold(
            ifLeft = { error ->
                logger.warn(
                    "Server built-in tool '${toolCall.toolName}' failed for tool call ${toolCall.id}: $error"
                )
                toolCall.toErrorResult(error = error, startTime = startTime)
            },
            ifRight = { output ->
                toolCall.copy(
                    status = ToolCallStatus.SUCCESS,
                    output = output,
                    errorMessage = null,
                    durationMs = (Clock.System.now() - startTime).inWholeMilliseconds
                )
            }
        )
    }

    /**
     * Parses a tool input string into a [JsonObject].
     *
     * @param input Raw tool input; may be null for parameterless calls.
     * @return The parsed [JsonObject], or `null` when the input is blank or not a JSON object.
     */
    private fun parseInput(input: String?): JsonObject? {
        if (input.isNullOrBlank()) return null
        return runCatching { json.parseToJsonElement(input) as? JsonObject }.getOrNull()
    }

    /**
     * Converts a handler error into its LLM-readable JSON object string.
     *
     * @receiver The typed handler failure.
     * @return JSON like `{"error": "<code>", "message": "..."}`.
     */
    private fun ServerBuiltInToolHandlerError.toErrorJson(): String = buildJsonObject {
        when (this@toErrorJson) {
            is ServerBuiltInToolHandlerError.InvalidInput -> {
                put("error", "invalid_input")
                put("message", message)
            }

            is ServerBuiltInToolHandlerError.OperationFailed -> {
                put("error", code)
                put("message", message)
            }

            is ServerBuiltInToolHandlerError.NotFoundOrNotAccessible -> {
                put("error", "not_found_or_not_accessible")
                put("message", message)
            }
        }
    }.toString()

    /**
     * Builds a terminal ERROR [ToolCall] carrying the LLM-readable JSON error message.
     *
     * @receiver The tool call being executed.
     * @param error The typed handler failure to surface.
     * @param startTime Execution start instant used to compute duration.
     * @return The terminal [ToolCall] copy.
     */
    private fun ToolCall.toErrorResult(
        error: ServerBuiltInToolHandlerError,
        startTime: Instant
    ): ToolCall = copy(
        status = ToolCallStatus.ERROR,
        output = null,
        errorMessage = error.toErrorJson(),
        durationMs = (Clock.System.now() - startTime).inWholeMilliseconds
    )
}

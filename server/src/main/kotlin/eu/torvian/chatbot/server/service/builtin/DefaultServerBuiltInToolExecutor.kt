package eu.torvian.chatbot.server.service.builtin

import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition
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
 * A thin, stateless dispatcher over the Koin-provided [tools] map keyed by canonical catalog name.
 * It resolves [ServerBuiltInToolDefinition.builtInToolName] (the canonical, unprefixed name; the
 * registry key) to the matching [ServerBuiltInTool] implementation and delegates the call. The
 * public name the LLM emitted ([ToolCall.toolName]) is intentionally ignored for dispatch: it may
 * carry the user's prefix, and dispatch must stay stable across prefix changes. Each tool owns its
 * user-scoped handler and its own injected dependencies, so adding a new server built-in tool never
 * requires modifying this dispatcher — mirroring the worker-side
 * [eu.torvian.chatbot.worker.builtin.DefaultBuiltInToolCallExecutor] registry pattern.
 *
 * Unknown canonical names (a persisted `builtInToolName` absent from the registry, i.e. a
 * catalog/DB inconsistency such as a stale row before the startup reconcile pruned it) and
 * malformed inputs produce a terminal ERROR [ToolCall] carrying an LLM-readable JSON error
 * object — never thrown.
 *
 * @property json Shared JSON codec used to parse tool inputs.
 * @property tools Registry mapping the canonical catalog name to its implementation.
 */
class DefaultServerBuiltInToolExecutor(
    private val json: Json,
    private val tools: Map<String, ServerBuiltInTool>,
) : ServerBuiltInToolExecutor {

    private companion object {
        /** Logger used for executor diagnostics. */
        private val logger: Logger = LogManager.getLogger(DefaultServerBuiltInToolExecutor::class.java)
    }

    override suspend fun executeTool(
        userId: Long,
        toolDefinition: ServerBuiltInToolDefinition,
        toolCall: ToolCall
    ): ToolCall {
        val startTime = Clock.System.now()

        // Dispatch on the canonical name carried by the resolved definition, never on the public
        // LLM-emitted name: the public name may carry the user's prefix, and no preference lookup
        // should happen at execution time.
        val tool = tools[toolDefinition.builtInToolName]
        if (tool == null) {
            logger.warn(
                "Unsupported server built-in tool '${toolDefinition.builtInToolName}' for tool call ${toolCall.id}"
            )
            return toolCall.toErrorResult(
                error = ServerBuiltInToolHandlerError.InvalidInput(
                    "Unsupported server built-in tool '${toolDefinition.builtInToolName}'. Supported tools: " +
                        tools.keys.sorted().joinToString(", ") + "."
                ),
                startTime = startTime
            )
        }

        val input = parseInput(toolCall.input) ?: return toolCall.toErrorResult(
            error = ServerBuiltInToolHandlerError.InvalidInput(
                "Tool input must be a JSON object, got: ${toolCall.input ?: "null"}"
            ),
            startTime = startTime
        )

        return tool.execute(userId, input).fold(
            ifLeft = { error ->
                logger.warn(
                    "Server built-in tool '${toolDefinition.builtInToolName}' failed for tool call ${toolCall.id}: $error"
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

package eu.torvian.chatbot.worker.builtin

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import kotlinx.serialization.json.JsonObject

/**
 * Common contract implemented by every worker-side built-in tool.
 *
 * Built-in tools are dispatched directly over the `tool.call` worker protocol command (without
 * MCP). All file system access is mediated by [BuiltInToolExecutionContext] which guarantees that
 * operations stay inside the configured workspace.
 *
 * @property name Unprefixed public name of the tool (e.g. `read_text_file`).
 * @property description Human-readable description surfaced to the LLM.
 * @property inputSchema JSON Schema describing the tool's expected input arguments.
 */
interface BuiltInTool {
    /** Unprefixed public name of the tool. */
    val name: String

    /** Human-readable description of the tool. */
    val description: String

    /** JSON Schema describing the tool's input arguments. */
    val inputSchema: JsonObject

    /**
     * Executes the tool with the given [input] inside the provided [context].
     *
     * The implementation is responsible for translating logical failures into a [BuiltInToolExecutionResult]
     * with `isError = true` and a stable error code.
     *
     * @param input Validated JSON arguments for the tool.
     * @param context Execution context providing the worker workspace and command timeout.
     * @return Built-in tool result, possibly with `isError = true`.
     */
    suspend fun execute(input: JsonObject, context: BuiltInToolExecutionContext): BuiltInToolExecutionResult
}


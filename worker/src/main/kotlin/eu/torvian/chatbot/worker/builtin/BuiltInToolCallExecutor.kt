package eu.torvian.chatbot.worker.builtin

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult

/**
 * Dispatches built-in tool execution requests to the registered implementations.
 *
 * Tools are resolved strictly by their unprefixed built-in tool name (e.g. `read_text_file`).
 * Tool-name prefixing is a server-side catalog concern and is not applied at the worker runtime.
 */
interface BuiltInToolCallExecutor {
    /**
     * Executes the tool identified by [toolName] with the given [input].
     *
     * @param toolName Unprefixed built-in tool name (e.g. `read_text_file`).
     * @param input Raw JSON string containing the tool arguments. May be `null` or blank, which
     *   is treated as an empty arguments object.
     * @return Built-in tool execution result; returns an [BuiltInToolExecutionError.UNKNOWN_TOOL]
     *   result if no implementation matches the tool name.
     */
    suspend fun execute(toolName: String, input: String?): BuiltInToolExecutionResult
}

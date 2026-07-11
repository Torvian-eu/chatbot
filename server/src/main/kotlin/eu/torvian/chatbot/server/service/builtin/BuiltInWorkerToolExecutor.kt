package eu.torvian.chatbot.server.service.builtin

import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.security.SignedRequest

/**
 * Executes Built-in Worker tools through the worker that owns the tool definition.
 *
 * The executor forwards the app-signed authorization to the worker, which validates the signature
 * and executes the built-in tool inside its own `workspace` directory. The worker is the source of
 * truth for execution parameters; the server only orchestrates the dispatch.
 */
interface BuiltInWorkerToolExecutor {
    /**
     * @param toolDefinition The built-in tool definition, including the owning worker ID.
     * @param toolCall The persisted tool call being executed.
     * @param signedRequest Detached signature metadata and authorization payload from the app.
     * @return Either a successful result or a structured error.
     */
    suspend fun executeTool(
        toolDefinition: BuiltInWorkerToolDefinition,
        toolCall: ToolCall,
        signedRequest: SignedRequest
    ): BuiltInWorkerToolExecutorEvent
}


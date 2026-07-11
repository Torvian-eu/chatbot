package eu.torvian.chatbot.server.service.core.error.builtin

import eu.torvian.chatbot.server.service.core.error.tool.ValidateToolError

/**
 * Base interface for all built-in worker tool definition service errors.
 */
sealed interface BuiltInToolDefinitionServiceError

/**
 * Errors that can occur when retrieving built-in worker tools by worker ID.
 */
sealed interface GetBuiltInToolsError : BuiltInToolDefinitionServiceError {
    /**
     * The referenced worker was not found.
     *
     * @property workerId The worker identifier that was not found.
     */
    data class WorkerNotFound(val workerId: Long) : GetBuiltInToolsError

    /**
     * The authenticated user is not the owner of the referenced worker.
     *
     * @property workerId The worker identifier the user tried to access.
     * @property workerOwnerUserId The actual owner user identifier of the worker.
     */
    data class Forbidden(val workerId: Long, val workerOwnerUserId: Long) : GetBuiltInToolsError
}

/**
 * Errors that can occur when updating a built-in worker tool definition.
 */
sealed interface UpdateBuiltInToolError : BuiltInToolDefinitionServiceError {
    /**
     * The requested built-in tool was not found.
     *
     * @property toolId The tool-definition identifier that was not found.
     */
    data class ToolNotFound(val toolId: Long) : UpdateBuiltInToolError

    /**
     * The authenticated user is not the owner of the worker that owns this tool.
     *
     * @property workerId The worker identifier the tool belongs to.
     * @property workerOwnerUserId The actual owner user identifier of the worker.
     */
    data class Forbidden(val workerId: Long, val workerOwnerUserId: Long) : UpdateBuiltInToolError

    /**
     * The tool definition failed validation.
     *
     * @property error The validation error.
     */
    data class ValidationError(val error: ValidateToolError) : UpdateBuiltInToolError
}


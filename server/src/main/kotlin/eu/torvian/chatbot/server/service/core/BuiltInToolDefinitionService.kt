package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition
import eu.torvian.chatbot.server.service.core.error.builtin.GetBuiltInToolsError
import eu.torvian.chatbot.server.service.core.error.builtin.UpdateBuiltInToolError

/**
 * Service interface for managing built-in worker tool definitions.
 *
 * Provides business-logic operations for listing and updating built-in tools,
 * enforcing strict worker-ownership validation so that a user can only view or
 * modify tools belonging to their own workers.
 */
interface BuiltInToolDefinitionService {

    /**
     * Retrieves all built-in worker tool definitions owned by the specified worker.
     *
     * Ownership is verified before returning results: the authenticated user must be
     * the owner of the worker identified by [workerId].
     *
     * @param userId The authenticated user identifier.
     * @param workerId The worker whose built-in tools should be listed.
     * @return Either a [GetBuiltInToolsError] if ownership validation or lookup fails,
     *         or the list of [BuiltInWorkerToolDefinition] objects (empty if none).
     */
    suspend fun getBuiltInToolsForWorker(
        userId: Long,
        workerId: Long
    ): Either<GetBuiltInToolsError, List<BuiltInWorkerToolDefinition>>

    /**
     * Updates the global enabled state of a built-in worker tool definition.
     *
     * Ownership is verified against the worker that owns this tool definition.
     * Only the `isEnabled` property on the base [BuiltInWorkerToolDefinition] is
     * toggled; other fields are preserved.
     *
     * @param userId The authenticated user identifier.
     * @param toolId The tool-definition identifier to update.
     * @param isEnabled The new enabled state.
     * @return Either an [UpdateBuiltInToolError] if validation or ownership fails,
     *         or the updated [BuiltInWorkerToolDefinition].
     */
    suspend fun updateBuiltInTool(
        userId: Long,
        toolId: Long,
        isEnabled: Boolean
    ): Either<UpdateBuiltInToolError, BuiltInWorkerToolDefinition>
}


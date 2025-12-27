package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.core.ChatGroup

/**
 * Frontend API interface for interacting with Chat Group-related endpoints.
 *
 * This interface defines the operations available for managing chat session groups.
 * Implementations use the internal HTTP API. All methods are suspend functions
 * and return [Either<ApiResourceError, T>] to explicitly handle potential API errors.
 */
interface GroupApi {
    /**
     * Retrieves a list of all chat session groups.
     *
     * Corresponds to `GET /api/v1/groups`.
     * (E6.S4)
     *
     * @return [Either.Right] containing a list of [ChatGroup] on success,
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun getAllGroups(): Either<ApiResourceError, List<ChatGroup>>

    /**
     * Creates a new chat session group.
     *
     * Corresponds to `POST /api/v1/groups`.
     * (E6.S3)
     *
     * @param name The name for the new group.
     * @return [Either.Right] containing the newly created [ChatGroup] object on success,
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun createGroup(name: String): Either<ApiResourceError, ChatGroup>

    /**
     * Renames an existing chat session group.
     *
     * Corresponds to `PUT /api/v1/groups/{groupId}`.
     * (E6.S5)
     *
     * @param groupId The ID of the group to rename.
     * @param name The new name for the group.
     * @return [Either.Right] with [Unit] on successful update,
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun renameGroup(groupId: Long, name: String): Either<ApiResourceError, Unit>

    /**
     * Deletes a chat session group.
     * Sessions previously assigned to this group will become ungrouped by the backend.
     *
     * Corresponds to `DELETE /api/v1/groups/{groupId}`.
     * (E6.S6)
     *
     * @param groupId The ID of the group to delete.
     * @return [Either.Right] with [Unit] on successful deletion (typically HTTP 204 No Content),
     *         or [Either.Left] containing an [ApiResourceError] on failure (e.g., not found).
     */
    suspend fun deleteGroup(groupId: Long): Either<ApiResourceError, Unit>
}
package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.ChatSessionSummary
import eu.torvian.chatbot.common.models.tool.ToolCall

/**
 * Frontend API interface for interacting with Chat Session-related endpoints.
 *
 * This interface defines the operations available for managing chat sessions,
 * including creation, retrieval, deletion, and updating session metadata
 * like name, group, model, settings, and leaf message. Implementations use the internal HTTP API.
 * All methods are suspend functions and return [Either<ApiResourceError, T>] to explicitly
 * handle potential API errors.
 */
interface SessionApi {

    /**
     * Retrieves a list of summaries for all chat sessions.
     * Includes group affiliation for display in the session list.
     *
     * Corresponds to `GET /api/v1/sessions`.
     * (E2.S3)
     *
     * @return [Either.Right] containing a list of [ChatSessionSummary] on success,
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun getAllSessions(): Either<ApiResourceError, List<ChatSessionSummary>>

    /**
     * Creates a new, empty chat session.
     *
     * Corresponds to `POST /api/v1/sessions`.
     * (E2.S1)
     *
     * @param name Required non-blank session name.
     * @return [Either.Right] containing the newly created [ChatSession] object on success,
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun createSession(name: String): Either<ApiResourceError, ChatSession>

    /**
     * Retrieves the full details of a specific chat session, including all its messages.
     * Necessary for displaying the conversation history and threads.
     *
     * Corresponds to `GET /api/v1/sessions/{sessionId}`.
     * (E2.S4)
     *
     * @param sessionId The ID of the session to retrieve.
     * @return [Either.Right] containing the requested [ChatSession] object (with messages populated) on success,
     *         or [Either.Left] containing an [ApiResourceError] on failure (e.g., not-found).
     */
    suspend fun getSessionDetails(sessionId: Long): Either<ApiResourceError, ChatSession>

    /**
     * Deletes a chat session and all its associated messages.
     *
     * Corresponds to `DELETE /api/v1/sessions/{sessionId}`.
     * (E2.S6)
     *
     * @param sessionId The ID of the session to delete.
     * @return [Either.Right] with [Unit] on successful deletion (typically HTTP 204 No Content),
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun deleteSession(sessionId: Long): Either<ApiResourceError, Unit>

    /**
     * Updates the name of a specific chat session.
     *
     * Corresponds to `PUT /api/v1/sessions/{sessionId}/name`.
     * (E2.S5)
     *
     * @param sessionId The ID of the session to rename.
     * @param name The new name for the session.
     * @return [Either.Right] with [Unit] on successful update (typically HTTP 200 OK with no body),
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun updateSessionName(sessionId: Long, name: String): Either<ApiResourceError, Unit>

    /**
     * Updates the currently selected LLM model for a specific chat session.
     *
     * Corresponds to `PUT /api/v1/sessions/{sessionId}/model`.
     * (E4.S7)
     *
     * @param sessionId The ID of the session.
     * @param modelId The new optional model ID for the session.
     * @return [Either.Right] with [Unit] on successful update,
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun updateSessionModel(sessionId: Long, modelId: Long?): Either<ApiResourceError, Unit>

    /**
     * Updates the currently selected settings profile for a specific chat session.
     *
     * Corresponds to `PUT /api/v1/sessions/{sessionId}/settings`.
     * (E4.S7)
     *
     * @param sessionId The ID of the session.
     * @param settingsId The new optional settings ID for the session.
     * @return [Either.Right] with [Unit] on successful update,
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun updateSessionSettings(sessionId: Long, settingsId: Long?): Either<ApiResourceError, Unit>

    /**
     * Sets the current "active" leaf message for a session, affecting which branch is displayed.
     *
     * Corresponds to `PUT /api/v1/sessions/{sessionId}/leafMessage`.
     * (E1.S5)
     *
     * @param sessionId The ID of the session.
     * @param leafMessageId The new optional leaf message ID for the session.
     * @return [Either.Right] with [Unit] on successful update,
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun updateSessionLeafMessage(sessionId: Long, leafMessageId: Long?): Either<ApiResourceError, Unit>

    /**
     * Assigns a specific chat session to a chat group, or ungroups it.
     *
     * Corresponds to `PUT /api/v1/sessions/{sessionId}/group`.
     * (E6.S1, E6.S7)
     *
     * @param sessionId The ID of the session to assign.
     * @param groupId The new optional group ID for the session.
     * @return [Either.Right] with [Unit] on successful update,
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun updateSessionGroup(sessionId: Long, groupId: Long?): Either<ApiResourceError, Unit>

    /**
     * Clones an existing chat session with all its messages, tool calls, and configuration.
     *
     * Corresponds to `POST /api/v1/sessions/{sessionId}/clone`.
     *
     * @param sessionId The ID of the session to clone.
     * @param name The name for the cloned session.
     * @return [Either.Right] containing the newly created [ChatSession] object on success,
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun cloneSession(sessionId: Long, name: String): Either<ApiResourceError, ChatSession>

    /**
     * Retrieves all tool calls for a specific chat session.
     * Useful for building context about tool execution history and for audit trails.
     *
     * Corresponds to `GET /api/v1/sessions/{sessionId}/toolcalls`.
     *
     * @param sessionId The ID of the session to retrieve tool calls for.
     * @return [Either.Right] containing a list of [ToolCall] on success,
     *         or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun getSessionToolCalls(sessionId: Long): Either<ApiResourceError, List<ToolCall>>
}
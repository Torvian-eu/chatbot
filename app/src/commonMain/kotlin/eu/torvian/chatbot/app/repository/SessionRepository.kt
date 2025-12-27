package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.api.core.*
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.ChatSessionSummary
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import eu.torvian.chatbot.common.models.tool.ToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Type alias for the map structure holding tool calls for a session.
 * Map of (messageId -> List<ToolCall>)
 */
typealias ToolCallsMap = Map<Long, List<ToolCall>>

/**
 * Repository interface for managing chat sessions.
 *
 * This repository serves as the single source of truth for session data in the application,
 * providing reactive data streams through StateFlow and handling all session-related operations.
 * It abstracts the underlying API layer and provides comprehensive error handling through
 * the RepositoryError hierarchy.
 *
 * The repository maintains an internal cache of session data and automatically updates
 * all observers when changes occur, ensuring data consistency across the application.
 */
interface SessionRepository {

    /**
     * Reactive stream of all chat session summaries.
     *
     * This StateFlow provides real-time updates whenever the session data changes,
     * allowing ViewModels and other consumers to automatically react to data changes
     * without manual refresh operations.
     *
     * @return StateFlow containing the current state of all session summaries wrapped in DataState
     */
    val sessions: StateFlow<DataState<RepositoryError, List<ChatSessionSummary>>>

    /**
     * Retrieves a reactive stream for the detailed state of a specific chat session.
     *
     * This method always returns a non-nullable StateFlow. If the session details
     * have not yet been loaded or are not in the cache, the returned flow will
     * initially emit `DataState.Idle`. Consumers should then call `loadSessionDetails(sessionId)`
     * to trigger the actual data fetch and update the flow.
     *
     * @param sessionId The unique identifier of the session.
     * @return A StateFlow containing the current state of the session details wrapped in DataState.
     *         It will initially be `DataState.Idle` if the session's details have not been loaded.
     */
    suspend fun getSessionDetailsFlow(sessionId: Long): StateFlow<DataState<RepositoryError, ChatSession>>

    /**
     * Retrieves a reactive stream for the tool calls of a specific chat session.
     *
     * This method always returns a non-nullable StateFlow. If the tool calls
     * have not yet been loaded or are not in the cache, the returned flow will
     * initially emit `DataState.Idle`. Consumers should then call `loadSessionDetails(sessionId)`
     * to trigger the actual data fetch and update the flow.
     *
     * @param sessionId The unique identifier of the session.
     * @return A StateFlow containing the current state of the tool calls wrapped in DataState.
     *         It will initially be `DataState.Idle` if the tool calls have not been loaded.
     */
    suspend fun getToolCallsFlow(sessionId: Long): StateFlow<DataState<RepositoryError, ToolCallsMap>>

    /**
     * Loads all chat session summaries from the backend.
     *
     * This operation fetches the latest session data and updates the internal StateFlow.
     * If a load operation is already in progress, this method returns immediately
     * without starting a duplicate operation.
     *
     * @return Either.Right with Unit on successful load, or Either.Left with RepositoryError on failure
     */
    suspend fun loadSessions(): Either<RepositoryError, Unit>

    /**
     * Creates a new chat session.
     *
     * Upon successful creation, the new session is automatically added to the internal
     * StateFlow, triggering updates to all observers.
     *
     * @param request The session creation request containing optional name and other details
     * @return Either.Right with the created ChatSession on success, or Either.Left with RepositoryError on failure
     */
    suspend fun createSession(request: CreateSessionRequest): Either<RepositoryError, ChatSession>

    /**
     * Loads the full details of a specific chat session, including all its messages.
     *
     * This operation fetches the session details and updates the internal cache of
     * individual session detail flows. The specific flow for the `sessionId` will
     * emit the loading, success, or error state.
     *
     * @param sessionId The unique identifier of the session to load
     * @return Either.Right with the ChatSession on success, or Either.Left with RepositoryError on failure
     */
    suspend fun loadSessionDetails(sessionId: Long): Either<RepositoryError, ChatSession>


    /**
     * Loads all tool calls for a specific chat session.
     *
     * This operation fetches the tool calls and updates the internal cache of tool call flows.
     * The specific flow for the `sessionId` will emit the loading, success, or error state.
     *
     * @param sessionId The unique identifier of the session to load tool calls for
     * @return Either.Right with the ToolCallsMap on success, or Either.Left with RepositoryError on failure
     */
    suspend fun loadSessionToolCalls(sessionId: Long): Either<RepositoryError, ToolCallsMap>

    /**
     * Deletes a chat session and all its associated messages.
     *
     * Upon successful deletion, the session is automatically removed from the internal
     * StateFlows, triggering updates to all observers.
     *
     * @param sessionId The unique identifier of the session to delete
     * @return Either.Right with Unit on successful deletion, or Either.Left with RepositoryError on failure
     */
    suspend fun deleteSession(sessionId: Long): Either<RepositoryError, Unit>

    /**
     * Updates the name of a specific chat session.
     *
     * Upon successful update, the modified session replaces the existing one in the
     * internal StateFlow, triggering updates to all observers.
     *
     * @param sessionId The unique identifier of the session to rename
     * @param request The update request containing the new name
     * @return Either.Right with Unit on successful update, or Either.Left with RepositoryError on failure
     */
    suspend fun updateSessionName(sessionId: Long, request: UpdateSessionNameRequest): Either<RepositoryError, Unit>

    /**
     * Updates the currently selected LLM model for a specific chat session.
     *
     * Upon successful update, the modified session's details flow in the cache
     * will emit the updated session, triggering updates to all its observers.
     *
     * @param sessionId The unique identifier of the session
     * @param request The update request containing the new optional model ID
     * @return Either.Right with Unit on successful update, or Either.Left with RepositoryError on failure
     */
    suspend fun updateSessionModel(sessionId: Long, request: UpdateSessionModelRequest): Either<RepositoryError, Unit>

    /**
     * Updates the currently selected settings profile for a specific chat session.
     *
     * Upon successful update, the modified session's details flow in the cache
     * will emit the updated session, triggering updates to all its observers.
     *
     * @param sessionId The unique identifier of the session
     * @param request The update request containing the new optional settings ID
     * @return Either.Right with Unit on successful update, or Either.Left with RepositoryError on failure
     */
    suspend fun updateSessionSettings(
        sessionId: Long,
        request: UpdateSessionSettingsRequest
    ): Either<RepositoryError, Unit>

    /**
     * Sets the current "active" leaf message for a session, affecting which branch is displayed.
     *
     * Upon successful update, the modified session's details flow in the cache
     * will emit the updated session, triggering updates to all its observers.
     *
     * @param sessionId The unique identifier of the session
     * @param request The update request containing the new optional leaf message ID
     * @return Either.Right with Unit on successful update, or Either.Left with RepositoryError on failure
     */
    suspend fun updateSessionLeafMessage(
        sessionId: Long,
        request: UpdateSessionLeafMessageRequest
    ): Either<RepositoryError, Unit>

    /**
     * Assigns a specific chat session to a chat group, or ungroups it.
     *
     * Upon successful update, the modified session replaces the existing one in the
     * internal StateFlow, triggering updates to all observers.
     *
     * @param sessionId The unique identifier of the session to assign
     * @param request The update request containing the new optional group ID
     * @return Either.Right with Unit on successful update, or Either.Left with RepositoryError on failure
     */
    suspend fun updateSessionGroup(sessionId: Long, request: UpdateSessionGroupRequest): Either<RepositoryError, Unit>

    /**
     * Clones an existing chat session with all its messages, tool calls, and configuration.
     *
     * Upon successful creation, the new session is automatically added to the internal
     * StateFlow, triggering updates to all observers.
     *
     * @param sessionId The unique identifier of the session to clone
     * @param name The name for the cloned session
     * @return Either.Right with the cloned ChatSession on success, or Either.Left with RepositoryError on failure
     */
    suspend fun cloneSession(sessionId: Long, name: String): Either<RepositoryError, ChatSession>

    // --- Chat Message Operations ---

    /**
     * Processes a new user message in a session and gets the LLM response (non-streaming).
     *
     * This operation sends the message to the backend and returns a Flow of ChatEvent updates.
     * The repository will update the cached ChatSession as events are received.
     * Even though this is "non-streaming" (meaning the LLM doesn't stream tokens), the backend
     * still uses SSE to provide progress updates during tool execution and message processing.
     *
     * @param sessionId The ID of the session to send the message to
     * @param clientEvents A flow of events from the client to the server. The first event must be
     *                     [ChatClientEvent.ProcessNewMessage] with `isStreaming=false`. Subsequent events
     *                     can be [ChatClientEvent.LocalMCPToolResult] for tool execution.
     * @return Flow of Either containing ChatEvent updates or RepositoryError on failure
     */
    fun processNewMessage(
        sessionId: Long,
        clientEvents: Flow<ChatClientEvent>
    ): Flow<Either<RepositoryError, ChatEvent>>

    /**
     * Processes a new user message in a session with streaming LLM response.
     *
     * This operation sends the message to the backend and returns a Flow of streaming events.
     * The repository will update the cached ChatSession as the streaming progresses.
     *
     * @param sessionId The ID of the session to send the message to
     * @param clientEvents A flow of events from the client to the server. The first event must be
     *                     [ChatClientEvent.ProcessNewMessage] with `isStreaming=true`. Subsequent events
     *                     can be [ChatClientEvent.LocalMCPToolResult] for tool execution.
     * @return Flow of Either containing ChatStreamEvent updates or RepositoryError on failure
     */
    fun processNewMessageStreaming(
        sessionId: Long,
        clientEvents: Flow<ChatClientEvent>
    ): Flow<Either<RepositoryError, ChatStreamEvent>>

    /**
     * Updates the content of a specific message.
     *
     * Upon successful update, the message is automatically updated in the cached ChatSession,
     * triggering updates to all observers.
     *
     * @param messageId The ID of the message to update
     * @param sessionId The ID of the session containing the message
     * @param request The update request containing the new content
     * @return Either.Right with Unit on successful update, or Either.Left with RepositoryError on failure
     */
    suspend fun updateMessageContent(
        messageId: Long,
        sessionId: Long,
        request: UpdateMessageRequest
    ): Either<RepositoryError, Unit>

    /**
     * Deletes a specific message. This operation performs a non-recursive deletion,
     * meaning only the specified message is removed, not its children.
     *
     * Upon successful deletion, the message is automatically removed from the cached ChatSession,
     * triggering updates to all observers.
     *
     * @param messageId The ID of the message to delete
     * @param sessionId The ID of the session containing the message
     * @return Either.Right with Unit on successful deletion, or Either.Left with RepositoryError on failure
     */
    suspend fun deleteMessage(messageId: Long, sessionId: Long): Either<RepositoryError, Unit>

    /**
     * Deletes a message and all its descendants recursively from the server.
     *
     * Upon successful deletion, the message and all its replies are automatically removed
     * from the cached ChatSession, triggering updates to all observers.
     *
     * @param messageId The ID of the message to delete along with all replies
     * @param sessionId The ID of the session containing the message
     * @return Either.Right with Unit on successful deletion, or Either.Left with RepositoryError on failure
     */
    suspend fun deleteMessageRecursively(messageId: Long, sessionId: Long): Either<RepositoryError, Unit>

    /**
     * Inserts a new message relative to a target message, or as a new root message.
     *
     * @param sessionId The ID of the session.
     * @param targetMessageId The ID of the target message to insert relative to. If null, inserts a new root message (position is ignored).
     * @param position The position to insert the message relative to the target (ABOVE, BELOW, or APPEND). Ignored if targetMessageId is null.
     * @param role The role of the message sender (e.g., USER, ASSISTANT).
     * @param content The content of the new message.
     * @param modelId Optional ID of the model to use for the message.
     * @param settingsId Optional ID of the settings profile to use.
     * @return Either an error or the newly created message.
     */
    suspend fun insertMessage(
        sessionId: Long,
        targetMessageId: Long?,
        position: MessageInsertPosition,
        role: ChatMessage.Role,
        content: String,
        modelId: Long? = null,
        settingsId: Long? = null
    ): Either<RepositoryError, ChatMessage>
}

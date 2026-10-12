package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.api.core.ChatClientEvent
import eu.torvian.chatbot.common.models.api.core.ChatEvent
import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
import eu.torvian.chatbot.common.models.api.core.CompactionEvent
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import eu.torvian.chatbot.common.models.tool.ToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The tool calls of one session, grouped by the id of the message they belong to.
 */
typealias ToolCallsMap = Map<Long, List<ToolCall>>

/**
 * Repository interface for a chat session's transcript and turn.
 *
 * This is the conversation half of the session API: the messages, their tool calls, and the operations that send a
 * message and mutate the transcript. The other half, the session list and its metadata, is [SessionRepository]. The
 * two exist apart because they serve different customers and hold different caches, so a caller depends only on the
 * half it uses. A member belongs to the half whose cache it writes: an operation that only updates the cached
 * session — even when it is not about messages — belongs here.
 *
 * Implementations expose the transcript and tool calls as reactive `StateFlow`s and translate every API failure into
 * the [RepositoryError] hierarchy.
 */
interface ConversationRepository {

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
     * Settles one cached assistant message as interrupted by the user, without issuing any request.
     *
     * This is the client-side counterpart of the server's terminal state for an ending the client caused
     * itself: when *this* client cancelled a streaming turn and the socket teardown made the finalizing event
     * impossible, the cause is not in doubt (`INTERRUPTED_BY_USER` is what the server persists for a torn-down
     * socket), so the message the client already holds is settled immediately instead of being fetched again.
     *
     * Only the cause is set: no error code and no reason text (a user interruption carries none, and the
     * label is localized client-side from the cause). The mutation is confined to that single message — the rest
     * of the cached session is not replaced, re-derived or re-fetched — and the persisted state stays
     * authoritative, because the next [loadSessionDetails] overwrites the cache with the server's copy. A message
     * that already carries a terminal state is left untouched, so a terminal event delivered by the server always
     * wins over this local marking.
     *
     * @param sessionId The unique identifier of the session that holds the message
     * @param messageId The ID of the assistant message to settle as interrupted by the user
     */
    suspend fun markAssistantMessageInterrupted(sessionId: Long, messageId: Long)

    /**
     * Selects (or deselects) the agent role attached to a chat session.
     *
     * Upon successful update, the modified session's details flow in the cache
     * will emit the updated session, triggering updates to all its observers.
     *
     * @param sessionId The unique identifier of the session
     * @param agentRoleId The new optional agent role ID for the session, or null to deselect.
     * @return Either.Right with Unit on successful update, or Either.Left with RepositoryError on failure
     */
    suspend fun updateSessionAgentRole(
        sessionId: Long,
        agentRoleId: Long?
    ): Either<RepositoryError, Unit>

    /**
     * Selects (or deselects) the project attached to a chat session.
     *
     * Corresponds to `PUT /api/v1/sessions/{sessionId}/project`. The server may clear the session's
     * agent role atomically when the new project selection makes the pair illegal; the cached
     * session is updated in one round-trip from the response, so this repository never re-derives
     * the clear rule locally.
     *
     * @param sessionId The unique identifier of the session
     * @param projectId The new optional project ID for the session, or null to deselect.
     * @return Either.Right with Unit on successful update, or Either.Left with RepositoryError on failure
     */
    suspend fun updateSessionProject(
        sessionId: Long,
        projectId: Long?
    ): Either<RepositoryError, Unit>

    /**
     * Sets the current "active" leaf message for a session, affecting which branch is displayed.
     *
     * Upon successful update, the modified session's details flow in the cache
     * will emit the updated session, triggering updates to all its observers.
     *
     * @param sessionId The unique identifier of the session
     * @param leafMessageId The new optional leaf message ID for the session
     * @return Either.Right with Unit on successful update, or Either.Left with RepositoryError on failure
     */
    suspend fun updateSessionLeafMessage(
        sessionId: Long,
        leafMessageId: Long?
    ): Either<RepositoryError, Unit>

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
     *                     are approval responses only.
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
     *                     are approval responses only.
     * @return Flow of Either containing ChatStreamEvent updates or RepositoryError on failure
     */
    fun processNewMessageStreaming(
        sessionId: Long,
        clientEvents: Flow<ChatClientEvent>
    ): Flow<Either<RepositoryError, ChatStreamEvent>>

    /**
     * Requests a forced conversation compaction of a session's displayed thread.
     *
     * This is a non-transcript operation: it persists one compaction chunk server-side and never
     * creates, replaces or removes a message, so the session cache is deliberately not updated. The
     * flow completes after the server's terminal compaction event.
     *
     * @param sessionId The ID of the session whose displayed thread is compacted.
     * @return Flow of Either containing the terminal compaction event or RepositoryError on failure.
     */
    fun compactConversation(sessionId: Long): Flow<Either<RepositoryError, CompactionEvent>>

    /**
     * Updates the content and optionally file references of an existing message.
     *
     * Upon successful update, the message is automatically updated in the cached ChatSession,
     * triggering updates to all observers.
     *
     * @param messageId The ID of the message to update
     * @param sessionId The ID of the session containing the message
     * @param content The new content for the message
     * @param fileReferences Optional new list of file references. If null, keeps existing.
     * @return Either.Right with Unit on successful update, or Either.Left with RepositoryError on failure
     */
    suspend fun updateMessageContent(
        messageId: Long,
        sessionId: Long,
        content: String,
        fileReferences: List<FileReference>? = null
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
     * @param fileReferences Optional list of file references attached to the message.
     * @return Either an error or the newly created message.
     */
    suspend fun insertMessage(
        sessionId: Long,
        targetMessageId: Long?,
        position: MessageInsertPosition,
        role: ChatMessage.Role,
        content: String,
        modelId: Long? = null,
        settingsId: Long? = null,
        fileReferences: List<FileReference> = emptyList()
    ): Either<RepositoryError, ChatMessage>
}

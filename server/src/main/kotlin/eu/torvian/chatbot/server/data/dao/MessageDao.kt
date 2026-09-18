package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.core.MessageSearchResult
import eu.torvian.chatbot.common.models.api.core.MessageSearchScope
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import eu.torvian.chatbot.server.data.dao.error.InsertMessageError
import eu.torvian.chatbot.server.data.dao.error.MessageError
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * Data Access Object for ChatMessage entities.
 */
interface MessageDao {
    /**
     * Retrieves all messages for a specific session, ordered by creation time.
     * The list is flat; the service/UI layer is responsible for reconstructing the thread tree.
     * Includes threading data (`parentMessageId`, `childrenMessageIds`).
     * @param sessionId The ID of the session whose messages to retrieve.
     * @return A list of all [ChatMessage] objects for the session.
     */
    suspend fun getMessagesBySessionId(sessionId: Long): List<ChatMessage>

    /**
     * Retrieves a single message by its ID.
     * Used internally or by services needing a specific message object.
     * @param id The ID of the message to retrieve.
     * @return Either [MessageError.MessageNotFound] if not found, or the [ChatMessage] object.
     */
    suspend fun getMessageById(id: Long): Either<MessageError.MessageNotFound, ChatMessage>

    /**
     * Searches messages across all sessions owned by the specified user.
     *
     * Matching is performed against the raw message content and returns a presentation-ready snippet for
     * each hit so the API layer can respond without additional transformation.
     *
     * @param userId The owner whose sessions should be searched.
     * @param query Non-blank search string that should be matched literally.
     * @param scope Server-side scope controlling whether only the visible branch or all threads are searched.
     * @param limit Maximum number of results to return.
     * @return Search hits ordered by descending message creation time.
     */
    suspend fun searchMessagesByUserId(
        userId: Long,
        query: String,
        scope: MessageSearchScope,
        limit: Int,
    ): List<MessageSearchResult>

    /**
     * Inserts a new message.
     * Handles all re-parenting and child list updates atomically.
     *
     * @param sessionId The ID of the session.
     * @param targetMessageId The ID of the message to insert relative to. Null if inserting a root message.
     * @param position The position relative to the target (ABOVE, BELOW, or APPEND).
     *                 If targetMessageId is null, position is ignored (treated as root insert).
     * @param role The role of the new message.
     * @param content The content of the new message.
     * @param modelId Optional model ID (for assistant messages).
     * @param settingsId Optional settings ID (for assistant messages).
     * @param agentRoleId Optional agent role ID (for assistant messages, provenance).
     * @param reasoningItems Optional replay-safe reasoning items emitted with an assistant message. Opaque;
     *                       never rendered.
     * @param fileReferences Optional list of file references.
     * @param createdAt Optional creation timestamp. If null, uses current time.
     * @param updatedAt Optional update timestamp. If null, uses current time.
     * @param completion Completion state written to the assistant row. Ignored for user messages, since only
     *                   assistant messages carry the completion columns. Defaults to
     *                   [AssistantMessageCompletionState.Completed], which is what manual inserts want; the turn
     *                   lifecycle passes [AssistantMessageCompletionState.InFlight] for a streaming placeholder
     *                   and a terminal state when the message is finalized.
     * @return Either an error or the newly created message.
     */
    suspend fun insertMessage(
        sessionId: Long,
        targetMessageId: Long?,
        position: MessageInsertPosition,
        role: ChatMessage.Role,
        content: String,
        modelId: Long?,
        settingsId: Long?,
        agentRoleId: Long? = null,
        fileReferences: List<FileReference> = emptyList(),
        reasoningItems: List<JsonObject>? = null,
        createdAt: Instant? = null,
        updatedAt: Instant? = null,
        completion: AssistantMessageCompletionState = AssistantMessageCompletionState.Completed
    ): Either<InsertMessageError, ChatMessage>

    /**
     * Updates the content, file references, and updated timestamp of an existing message.
     *
     * For assistant messages the completion state is written together with the content in the same statement,
     * so content and state can never disagree: the public content-update path (a user editing the message) relies
     * on the default [AssistantMessageCompletionState.Completed] and thereby clears a previous incompletion state,
     * while the turn-finalization path passes the terminal state it determined. Assistant messages are the only
     * rows carrying completion columns, so the state write is a silent no-op for user messages.
     *
     * @param id The ID of the message to update.
     * @param content The new content.
     * @param fileReferences The new list of file references (optional, if null keeps existing).
     * @param completion Completion state to persist with the new content. Defaults to
     *                   [AssistantMessageCompletionState.Completed], i.e. the message counts as complete.
     * @return Either a [MessageError.MessageNotFound] or the updated [ChatMessage] object.
     */
    suspend fun updateMessageContent(
        id: Long,
        content: String,
        fileReferences: List<FileReference>? = null,
        completion: AssistantMessageCompletionState = AssistantMessageCompletionState.Completed
    ): Either<MessageError.MessageNotFound, ChatMessage>

    /**
     * Deletes a specific message and handles its impact on thread relationships.
     * The V1.1 strategy is to recursively delete all children of the deleted message.
     * Also removes the deleted message's ID from its parent's children list.
     * @param id The ID of the message to delete.
     * @return Either a [MessageError.MessageNotFound] or Unit if successful.
     */
    suspend fun deleteMessageRecursively(id: Long): Either<MessageError.MessageNotFound, Unit>

    /**
     * Deletes a single message without deleting its descendants.
     * Promotes all children of the deleted message to its parent (or to root if the deleted message is a root),
     * preserving child order, and updates parent/children links accordingly.
     * @param id The ID of the message to delete.
     * @return Either a [MessageError.MessageNotFound] or Unit if successful.
     */
    suspend fun deleteMessage(id: Long): Either<MessageError.MessageNotFound, Unit>

    /**
     * Updates the reasoning items attached to an assistant message.
     *
     * @param messageId The ID of the assistant message whose reasoning items to update.
     * @param reasoningItems The new replay-safe reasoning items, or `null` to clear them. Opaque; never
     *                       rendered.
     * @return Either a [MessageError.MessageNotFound] if the message is not an assistant message or not found,
     *         or the updated [ChatMessage.AssistantMessage] on success.
     */
    suspend fun updateAssistantMessageReasoning(
        messageId: Long,
        reasoningItems: List<JsonObject>?
    ): Either<MessageError.MessageNotFound, ChatMessage.AssistantMessage>
}

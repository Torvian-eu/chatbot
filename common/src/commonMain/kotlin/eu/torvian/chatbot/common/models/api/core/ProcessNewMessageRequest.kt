package eu.torvian.chatbot.common.models.api.core

import eu.torvian.chatbot.common.models.core.FileReference
import kotlinx.serialization.Serializable

/**
 * Request body for sending a new message to a chat session.
 *
 * @property content The user's message content. When null, no new user message is created and the
 *                   assistant continues from the [parentMessageId] message (which must be non-null).
 *                   This is used for "Branch & Continue" functionality.
 * @property parentMessageId The ID of the message this is a reply to (null for initial messages or
 *                           if replying to the root of a new thread branch). Must be non-null when
 *                           [content] is null.
 * @property isStreaming Whether the assistant message should be streamed (true) or not (false).
 * @property fileReferences Optional list of file references attached to the message.
 */
@Serializable
data class ProcessNewMessageRequest(
    val content: String? = null,
    val parentMessageId: Long? = null,
    val isStreaming: Boolean = true,
    val fileReferences: List<FileReference> = emptyList()
)
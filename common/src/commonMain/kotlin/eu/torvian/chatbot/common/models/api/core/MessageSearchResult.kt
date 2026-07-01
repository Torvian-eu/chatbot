package eu.torvian.chatbot.common.models.api.core

import eu.torvian.chatbot.common.models.core.ChatMessage
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Represents a single cross-session message search hit returned by the server.
 *
 * The snippet offsets are relative to [snippet], not to the original message content, so clients can
 * highlight the match directly without re-running server-side snippet calculations.
 *
 * @property sessionId Identifier of the session containing the matching message.
 * @property sessionName Human-readable name of the session containing the match.
 * @property messageId Identifier of the matching message.
 * @property messageRole Role of the matching message author.
 * @property snippet Extracted window around the first detected match.
 * @property matchStartIndex Inclusive match start offset inside [snippet].
 * @property matchEndExclusive Exclusive match end offset inside [snippet].
 * @property createdAt Timestamp when the matching message was created.
 */
@Serializable
data class MessageSearchResult(
    val sessionId: Long,
    val sessionName: String,
    val messageId: Long,
    val messageRole: ChatMessage.Role,
    val snippet: String,
    val matchStartIndex: Int,
    val matchEndExclusive: Int,
    val createdAt: Instant
)
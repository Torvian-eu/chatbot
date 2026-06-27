package eu.torvian.chatbot.app.chat.search

/**
 * Represents one concrete search occurrence inside a rendered chat message.
 *
 * @property messageId identifier of the message containing the match.
 * @property occurrenceIndexInMessage zero-based ordinal of this occurrence within the message.
 * @property startIndex inclusive start offset of the match within the message content.
 * @property endExclusive exclusive end offset of the match within the message content.
 */
data class MessageSearchMatch(
    val messageId: Long,
    val occurrenceIndexInMessage: Int,
    val startIndex: Int,
    val endExclusive: Int,
)

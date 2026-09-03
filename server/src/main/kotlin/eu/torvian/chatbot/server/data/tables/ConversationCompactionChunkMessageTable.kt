package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Exposed table definition for the ordered message-ID coverage of a compaction chunk.
 *
 * One row exists per covered source `ChatMessage`: [ordinal] is the chronological position of the
 * source unit in the summarized thread, [messageId] identifies the original message, and
 * [observedUpdatedAt] is the epoch-millisecond `updatedAt` timestamp observed when the chunk was
 * created. A chunk is eligible for a context only when every recorded (messageId, timestamp) pair
 * still matches the current parent chain.
 *
 * @property chunkId Owning chunk row; deleting the chunk cascades away its coverage.
 * @property ordinal 0-based chronological position within the summarized thread.
 * @property messageId Covered original message ID (deliberately no FK; see migration comments).
 * @property observedUpdatedAt Epoch-millisecond `updatedAt` observed for [messageId] at creation.
 */
object ConversationCompactionChunkMessageTable : Table("conversation_compaction_chunk_messages") {
    val chunkId = reference("chunk_id", ConversationCompactionChunkTable, onDelete = ReferenceOption.CASCADE)
    val ordinal = integer("ordinal")
    val messageId = long("message_id")
    val observedUpdatedAt = long("observed_updated_at")

    override val primaryKey = PrimaryKey(chunkId, ordinal)

    init {
        uniqueIndex(chunkId, messageId)
        index(false, messageId)
    }
}

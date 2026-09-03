package eu.torvian.chatbot.server.data.dao.error

/**
 * Logical failures specific to reading and atomically persisting conversation-compaction chunks.
 */
sealed interface ConversationCompactionChunkDaoError {

    /**
     * The source chain no longer matches the chunk candidate's recorded coverage.
     *
     * Raised when the transactional insert verification detects a missing message, a cross-session
     * message, a changed `updatedAt` timestamp, a broken parent chain, or a leaf mismatch. The whole
     * insert is rolled back so no partial chunk is ever exposed.
     *
     * @property reason Human-readable description of the verification failure.
     */
    data class SourceVerificationFailed(val reason: String) : ConversationCompactionChunkDaoError

    /**
     * A technical database failure prevented chunk persistence.
     *
     * @property reason Human-readable description of the persistence failure.
     * @property cause The underlying database exception, when available.
     */
    data class PersistenceFailed(val reason: String, val cause: Throwable?) : ConversationCompactionChunkDaoError
}

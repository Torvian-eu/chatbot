package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.ConversationCompactionChunkDaoError
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionChunk
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionChunkCandidate

/**
 * Data access operations for persisted conversation-compaction chunks.
 *
 * Chunk rows are immutable and retained; precedence is a context-selection rule, not a stored flag.
 */
interface ConversationCompactionChunkDao {

    /**
     * Loads every retained chunk for the session, each with its ordered coverage.
     *
     * @param sessionId Owning chat session.
     * @return Chunks for the session with coverage sorted by ordinal.
     */
    suspend fun getChunksBySessionId(sessionId: Long): List<ConversationCompactionChunk>

    /**
     * Atomically verifies the candidate's source chain and persists the chunk plus all coverage rows.
     *
     * Inside one transaction the DAO loads the covered messages, verifies same-session membership,
     * exact epoch-millisecond timestamps, the ordered parent chain ending at [expectedLeafMessageId],
     * and then inserts the chunk and every coverage row. Any verification or insert failure rolls the
     * whole operation back.
     *
     * @param candidate The chunk candidate to persist.
     * @param expectedLeafMessageId Message ID that must equal the final coverage entry (the current
     *            thread leaf), guarding against source races.
     * @return Either a logical [ConversationCompactionChunkDaoError] or the persisted chunk.
     */
    suspend fun insertVerifiedChunk(
        candidate: ConversationCompactionChunkCandidate,
        expectedLeafMessageId: Long
    ): Either<ConversationCompactionChunkDaoError, ConversationCompactionChunk>
}

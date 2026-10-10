package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.core.ThreadStats
import eu.torvian.chatbot.server.service.core.error.session.GetThreadStatsError

/**
 * Service interface for the read-only statistics of a session's current thread.
 */
interface ThreadStatsService {
    /**
     * Computes the statistics of the root→leaf branch of the session's current leaf message.
     *
     * @param sessionId The ID of the session whose thread is measured.
     * @return Either a [GetThreadStatsError] or the aggregate [ThreadStats] of the branch.
     */
    suspend fun getThreadStats(sessionId: Long): Either<GetThreadStatsError, ThreadStats>
}

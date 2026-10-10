package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.api.core.ThreadStats
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.ToolCallDao
import eu.torvian.chatbot.server.data.dao.error.SessionError
import eu.torvian.chatbot.server.service.core.ThreadStatsService
import eu.torvian.chatbot.server.service.core.error.session.GetThreadStatsError
import eu.torvian.chatbot.server.service.core.stats.ThreadStatsCalculator

/**
 * Implementation of the [ThreadStatsService] interface.
 *
 * Loads the three inputs the calculation needs — the session (for its current leaf message), its
 * messages and its tool calls — in one transaction so the statistics describe a single consistent
 * snapshot of the conversation, then delegates the maths to [ThreadStatsCalculator].
 */
class ThreadStatsServiceImpl(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val toolCallDao: ToolCallDao,
    private val transactionScope: TransactionScope,
) : ThreadStatsService {

    override suspend fun getThreadStats(sessionId: Long): Either<GetThreadStatsError, ThreadStats> =
        transactionScope.transaction {
            either {
                val session = withError({ daoError: SessionError.SessionNotFound ->
                    GetThreadStatsError.SessionNotFound(daoError.id)
                }) {
                    sessionDao.getSessionById(sessionId).bind()
                }

                ThreadStatsCalculator.compute(
                    sessionMessages = messageDao.getMessagesBySessionId(sessionId),
                    currentLeafMessageId = session.currentLeafMessageId,
                    sessionToolCalls = toolCallDao.getToolCallsBySessionId(sessionId)
                )
            }
        }
}

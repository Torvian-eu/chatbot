package eu.torvian.chatbot.server.service.core.impl

import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.server.data.dao.ToolCallDao
import eu.torvian.chatbot.server.service.core.ToolCallService
import eu.torvian.chatbot.server.utils.transactions.TransactionScope

/**
 * Implementation of the [ToolCallService] interface.
 * Handles tool call retrieval and transaction management.
 */
class ToolCallServiceImpl(
    private val toolCallDao: ToolCallDao,
    private val transactionScope: TransactionScope,
) : ToolCallService {

    override suspend fun getToolCallsBySessionId(sessionId: Long): List<ToolCall> {
        return transactionScope.transaction {
            toolCallDao.getToolCallsBySessionId(sessionId)
        }
    }
}


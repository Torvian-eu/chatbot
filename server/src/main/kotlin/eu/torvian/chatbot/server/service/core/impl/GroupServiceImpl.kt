package eu.torvian.chatbot.server.service.core.impl
import arrow.core.*
import arrow.core.raise.*
import arrow.core.raise.ensure

import eu.torvian.chatbot.common.models.ChatGroup
import eu.torvian.chatbot.server.data.dao.GroupDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.error.GroupError
import eu.torvian.chatbot.server.service.core.GroupService
import eu.torvian.chatbot.server.service.core.error.group.*
import eu.torvian.chatbot.server.utils.transactions.TransactionScope

/**
 * Implementation of the [GroupService] interface.
 */
class GroupServiceImpl(
    private val groupDao: GroupDao,
    private val sessionDao: SessionDao,
    private val transactionScope: TransactionScope,
) : GroupService {

    override suspend fun getAllGroups(): List<ChatGroup> {
        return transactionScope.transaction {
            groupDao.getAllGroups()
        }
    }

    override suspend fun createGroup(name: String): Either<CreateGroupError, ChatGroup> =
        transactionScope.transaction {
            either {
                ensure(!name.isBlank()) {
                    CreateGroupError.InvalidName("Group name cannot be blank.")
                }
                groupDao.insertGroup(name)
            }
        }

    override suspend fun renameGroup(id: Long, newName: String): Either<RenameGroupError, Unit> =
        transactionScope.transaction {
            either {
                ensure(!newName.isBlank()) {
                    RenameGroupError.InvalidName("New group name cannot be blank.")
                }
                withError({ daoError: GroupError.GroupNotFound ->
                    RenameGroupError.GroupNotFound(daoError.id)
                }) {
                    groupDao.renameGroup(id, newName).bind()
                }
            }
        }

    override suspend fun deleteGroup(id: Long): Either<DeleteGroupError, Unit> =
        transactionScope.transaction {
            either {
                // If the group exists, ungroup sessions.
                sessionDao.ungroupSessions(id)
                
                withError({ daoError: GroupError.GroupNotFound ->
                    DeleteGroupError.GroupNotFound(daoError.id)
                }) {
                    groupDao.deleteGroup(id).bind()
                }
            }
        }
}

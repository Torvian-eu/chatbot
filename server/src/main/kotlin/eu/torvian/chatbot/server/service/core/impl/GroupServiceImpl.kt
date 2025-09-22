package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.models.ChatGroup
import eu.torvian.chatbot.server.data.dao.GroupDao
import eu.torvian.chatbot.server.data.dao.GroupOwnershipDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.GroupError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.service.core.GroupService
import eu.torvian.chatbot.server.service.core.error.group.CreateGroupError
import eu.torvian.chatbot.server.service.core.error.group.DeleteGroupError
import eu.torvian.chatbot.server.service.core.error.group.RenameGroupError
import eu.torvian.chatbot.server.utils.transactions.TransactionScope

/**
 * Implementation of the [GroupService] interface.
 */
class GroupServiceImpl(
    private val groupDao: GroupDao,
    private val groupOwnershipDao: GroupOwnershipDao,
    private val sessionDao: SessionDao,
    private val transactionScope: TransactionScope,
) : GroupService {

    override suspend fun getAllGroups(userId: Long): List<ChatGroup> {
        return transactionScope.transaction {
            groupOwnershipDao.getAllGroupsForUser(userId)
        }
    }

    override suspend fun createGroup(userId: Long, name: String): Either<CreateGroupError, ChatGroup> =
        transactionScope.transaction {
            either {
                ensure(!name.isBlank()) {
                    CreateGroupError.InvalidName("Group name cannot be blank.")
                }

                val group = groupDao.insertGroup(name)

                // Set ownership for the newly created group
                withError({ ownershipError: SetOwnerError ->
                    when (ownershipError) {
                        is SetOwnerError.ForeignKeyViolation ->
                            CreateGroupError.OwnershipError("Failed to set group ownership")

                        is SetOwnerError.AlreadyOwned ->
                            CreateGroupError.OwnershipError("Group ownership conflict")
                    }
                }) {
                    groupOwnershipDao.setOwner(group.id, userId).bind()
                }

                group
            }
        }

    override suspend fun renameGroup(userId: Long, id: Long, newName: String): Either<RenameGroupError, Unit> =
        transactionScope.transaction {
            either {
                ensure(!newName.isBlank()) {
                    RenameGroupError.InvalidName("New group name cannot be blank.")
                }

                // Check ownership first
                val ownerId = groupOwnershipDao.getOwner(id).mapLeft { ownershipError ->
                    when (ownershipError) {
                        is GetOwnerError.ResourceNotFound ->
                            RenameGroupError.GroupNotFound(id)
                    }
                }.bind()

                ensure(ownerId == userId) {
                    RenameGroupError.AccessDenied("User does not own this group")
                }

                withError({ daoError: GroupError.GroupNotFound ->
                    RenameGroupError.GroupNotFound(daoError.id)
                }) {
                    groupDao.renameGroup(id, newName).bind()
                }
            }
        }

    override suspend fun deleteGroup(userId: Long, id: Long): Either<DeleteGroupError, Unit> =
        transactionScope.transaction {
            either {
                // Check ownership first
                val ownerId = groupOwnershipDao.getOwner(id).mapLeft { ownershipError ->
                    when (ownershipError) {
                        is GetOwnerError.ResourceNotFound ->
                            DeleteGroupError.GroupNotFound(id)
                    }
                }.bind()

                ensure(ownerId == userId) {
                    DeleteGroupError.AccessDenied("User does not own this group")
                }

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

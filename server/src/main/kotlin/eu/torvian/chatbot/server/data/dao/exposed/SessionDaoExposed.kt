package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.ChatSessionSummary
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.error.SessionError
import eu.torvian.chatbot.server.data.tables.ChatGroupTable
import eu.torvian.chatbot.server.data.tables.ChatSessionTable
import eu.torvian.chatbot.server.data.tables.SessionCurrentLeafTable
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.time.Instant

/**
 * Exposed implementation of the [SessionDao].
 */
class SessionDaoExposed(
    private val messageDao: MessageDao,
    private val transactionScope: TransactionScope
) : SessionDao {

    companion object {
        private val logger: Logger = LogManager.getLogger(SessionDaoExposed::class.java)
    }

    override suspend fun getAllSessions(): List<ChatSessionSummary> =
        transactionScope.transaction {
            ChatSessionTable
                .join(
                    ChatGroupTable,
                    JoinType.LEFT,
                    additionalConstraint = { ChatSessionTable.groupId eq ChatGroupTable.id })
                .selectAll()
                .orderBy(ChatSessionTable.updatedAt to SortOrder.DESC) // Order by recent activity (E2.S3 AC)
                .map { it.toChatSessionSummary() }
        }

    override suspend fun getSessionById(id: Long): Either<SessionError.SessionNotFound, ChatSession> =
        transactionScope.transaction {
            // Retrieve the session record with the current leaf message ID
            val sessionRow = ChatSessionTable
                .join(
                    SessionCurrentLeafTable,
                    JoinType.LEFT,
                    additionalConstraint = { ChatSessionTable.id eq SessionCurrentLeafTable.sessionId }
                )
                .selectAll()
                .where { ChatSessionTable.id eq id }
                .singleOrNull()
                ?: return@transaction SessionError.SessionNotFound(id).left()

            // Retrieve all messages for this session
            val messages = messageDao.getMessagesBySessionId(id)

            // Map row and messages to ChatSession DTO
            sessionRow.toChatSession(messages).right()
        }

    override suspend fun insertSession(
        name: String,
        groupId: Long?,
        currentModelId: Long?,
        currentSettingsId: Long?
    ): Either<SessionError.ForeignKeyViolation, ChatSession> =
        transactionScope.transaction {
            either {
                catch({
                    val now = System.currentTimeMillis()
                    val insertStatement = ChatSessionTable.insert {
                        it[ChatSessionTable.name] = name
                        it[ChatSessionTable.createdAt] = now
                        it[ChatSessionTable.updatedAt] = now
                        it[ChatSessionTable.groupId] = groupId
                        it[ChatSessionTable.currentModelId] = currentModelId
                        it[ChatSessionTable.currentSettingsId] = currentSettingsId
                    }
                    insertStatement.resultedValues?.first()?.toChatSession(emptyList())
                        ?: throw IllegalStateException("Failed to retrieve newly inserted session")
                }) { e: ExposedSQLException ->
                    val message =
                        "Failed to insert session with name $name, group $groupId, model $currentModelId, settings $currentSettingsId"
                    logger.error(message, e)
                    ensure(!e.isForeignKeyViolation()) { SessionError.ForeignKeyViolation(message) }
                    throw e
                }
            }
        }

    override suspend fun updateSessionName(id: Long, name: String): Either<SessionError.SessionNotFound, Unit> =
        transactionScope.transaction {
            either {
                val updatedRowCount = ChatSessionTable.update({ ChatSessionTable.id eq id }) {
                    it[ChatSessionTable.name] = name
                    it[ChatSessionTable.updatedAt] = System.currentTimeMillis()
                }
                ensure(updatedRowCount != 0) { SessionError.SessionNotFound(id) }
            }
        }

    override suspend fun updateSessionGroupId(id: Long, groupId: Long?): Either<SessionError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    val updatedRowCount = ChatSessionTable.update({ ChatSessionTable.id eq id }) {
                        it[ChatSessionTable.groupId] = groupId
                        it[ChatSessionTable.updatedAt] = System.currentTimeMillis()
                    }
                    ensure(updatedRowCount != 0) { SessionError.SessionNotFound(id) }
                }) { e: ExposedSQLException ->
                    val message = "Failed to update session group ID $groupId for session $id"
                    logger.error(message, e)
                    ensure(!e.isForeignKeyViolation()) { SessionError.ForeignKeyViolation(message) }
                    throw e
                }
            }
        }

    override suspend fun updateSessionCurrentModelId(id: Long, modelId: Long?): Either<SessionError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    val updatedRowCount = ChatSessionTable.update({ ChatSessionTable.id eq id }) {
                        it[ChatSessionTable.currentModelId] = modelId
                        it[ChatSessionTable.updatedAt] = System.currentTimeMillis()
                    }
                    ensure(updatedRowCount != 0) { SessionError.SessionNotFound(id) }
                }) { e: ExposedSQLException ->
                    val message = "Failed to update session current model ID $modelId for session $id"
                    logger.error(message, e)
                    ensure(!e.isForeignKeyViolation()) { SessionError.ForeignKeyViolation(message) }
                    throw e
                }
            }
        }

    override suspend fun updateSessionCurrentSettingsId(id: Long, settingsId: Long?): Either<SessionError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    val updatedRowCount = ChatSessionTable.update({ ChatSessionTable.id eq id }) {
                        it[ChatSessionTable.currentSettingsId] = settingsId
                        it[ChatSessionTable.updatedAt] = System.currentTimeMillis()
                    }
                    ensure(updatedRowCount != 0) { SessionError.SessionNotFound(id) }
                }) { e: ExposedSQLException ->
                    val message = "Failed to update session current settings ID $settingsId for session $id"
                    logger.error(message, e)
                    ensure(!e.isForeignKeyViolation()) { SessionError.ForeignKeyViolation(message) }
                    throw e
                }
            }
        }

    override suspend fun updateSessionLeafMessageId(id: Long, messageId: Long?): Either<SessionError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    // Update the session's timestamp
                    val updatedRowCount = ChatSessionTable.update({ ChatSessionTable.id eq id }) {
                        it[ChatSessionTable.updatedAt] = System.currentTimeMillis()
                    }
                    ensure(updatedRowCount != 0) { SessionError.SessionNotFound(id) }

                    if (messageId == null) {
                        // Delete any existing leaf message link
                        SessionCurrentLeafTable.deleteWhere { SessionCurrentLeafTable.sessionId eq id }
                    } else {
                        // Update the leaf message link
                        SessionCurrentLeafTable.upsert(where = { SessionCurrentLeafTable.sessionId eq id }) {
                            it[SessionCurrentLeafTable.sessionId] = id
                            it[SessionCurrentLeafTable.messageId] = messageId
                        }
                    }
                }) { e: ExposedSQLException ->
                    val message = "Failed to update session leaf message ID $messageId for session $id"
                    logger.error(message, e)
                    ensure(!e.isForeignKeyViolation()) { SessionError.ForeignKeyViolation(message) }
                    throw e
                }
            }
        }

    override suspend fun deleteSession(id: Long): Either<SessionError.SessionNotFound, Unit> =
        transactionScope.transaction {
            either {
                val deletedCount = ChatSessionTable.deleteWhere { ChatSessionTable.id eq id }
                ensure(deletedCount != 0) { SessionError.SessionNotFound(id) }
            }
        }

    override suspend fun ungroupSessions(groupId: Long) =
        transactionScope.transaction {
            ChatSessionTable.update({ ChatSessionTable.groupId eq groupId }) {
                it[ChatSessionTable.groupId] = null
            }
            return@transaction
        }

    // --- Mapping Functions ---
    /**
     * Maps an Exposed ResultRow from a join of ChatSessionTable and ChatGroupTable to a ChatSessionSummary DTO.
     */
    private fun ResultRow.toChatSessionSummary() = ChatSessionSummary(
        id = this[ChatSessionTable.id].value,
        name = this[ChatSessionTable.name],
        createdAt = Instant.fromEpochMilliseconds(this[ChatSessionTable.createdAt]),
        updatedAt = Instant.fromEpochMilliseconds(this[ChatSessionTable.updatedAt]),
        groupId = this[ChatSessionTable.groupId]?.value,
        groupName = this.getOrNull(ChatGroupTable.name)
    )

    /**
     * Maps an Exposed ResultRow from a join of ChatSessionTable and SessionCurrentLeafTable to a ChatSession DTO.
     *
     * @param messages List of all messages within this session.
     * */
    private fun ResultRow.toChatSession(messages: List<ChatMessage>) = ChatSession(
        id = this[ChatSessionTable.id].value,
        name = this[ChatSessionTable.name],
        createdAt = Instant.fromEpochMilliseconds(this[ChatSessionTable.createdAt]),
        updatedAt = Instant.fromEpochMilliseconds(this[ChatSessionTable.updatedAt]),
        groupId = this[ChatSessionTable.groupId]?.value,
        currentModelId = this[ChatSessionTable.currentModelId]?.value,
        currentSettingsId = this[ChatSessionTable.currentSettingsId]?.value,
        currentLeafMessageId = this.getOrNull(SessionCurrentLeafTable.messageId)?.value,
        messages = messages
    )
}
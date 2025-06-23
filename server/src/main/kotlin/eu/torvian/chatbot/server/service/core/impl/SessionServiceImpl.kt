package eu.torvian.chatbot.server.service.core.impl
import arrow.core.*
import arrow.core.raise.*
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.models.ChatSession
import eu.torvian.chatbot.common.models.ChatSessionSummary
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.error.SessionError
import eu.torvian.chatbot.server.service.core.SessionService
import eu.torvian.chatbot.server.service.core.error.session.*
import eu.torvian.chatbot.server.utils.transactions.TransactionScope

/**
 * Implementation of the [SessionService] interface.
 */
class SessionServiceImpl(
    private val sessionDao: SessionDao,
    private val transactionScope: TransactionScope,
) : SessionService {

    override suspend fun getAllSessionsSummaries(): List<ChatSessionSummary> {
        return transactionScope.transaction {
            sessionDao.getAllSessions()
        }
    }

    override suspend fun createSession(name: String?): Either<CreateSessionError, ChatSession> =
        transactionScope.transaction {
            either {
                ensure(!(name != null && name.isBlank())) {
                    CreateSessionError.InvalidName("Session name cannot be blank.")
                }

                withError({ daoError: SessionError.ForeignKeyViolation ->
                    CreateSessionError.InvalidRelatedEntity(daoError.message)
                }) {
                    sessionDao.insertSession(name ?: "New Chat").bind()
                }
            }
        }

    override suspend fun getSessionDetails(id: Long): Either<GetSessionDetailsError, ChatSession> =
        transactionScope.transaction {
            either {
                withError({ daoError: SessionError.SessionNotFound ->
                    GetSessionDetailsError.SessionNotFound(daoError.id)
                }) {
                    sessionDao.getSessionById(id).bind()
                }
            }
        }

    override suspend fun updateSessionName(id: Long, name: String): Either<UpdateSessionNameError, Unit> =
        transactionScope.transaction {
            either {
                ensure(!name.isBlank()) {
                    UpdateSessionNameError.InvalidName("Session name cannot be blank.")
                }
                withError({ daoError: SessionError.SessionNotFound ->
                    UpdateSessionNameError.SessionNotFound(daoError.id)
                }) {
                    sessionDao.updateSessionName(id, name).bind()
                }
            }
        }

    override suspend fun updateSessionGroupId(id: Long, groupId: Long?): Either<UpdateSessionGroupIdError, Unit> =
        transactionScope.transaction {
            either {
                withError({ daoError: SessionError ->
                    when(daoError) {
                        is SessionError.SessionNotFound -> UpdateSessionGroupIdError.SessionNotFound(daoError.id)
                        is SessionError.ForeignKeyViolation -> UpdateSessionGroupIdError.InvalidRelatedEntity(daoError.message)
                    }
                }) {
                    sessionDao.updateSessionGroupId(id, groupId).bind()
                }
            }
        }

    override suspend fun updateSessionCurrentModelId(id: Long, modelId: Long?): Either<UpdateSessionCurrentModelIdError, Unit> =
        transactionScope.transaction {
            either {
                withError({ daoError: SessionError ->
                    when(daoError) {
                        is SessionError.SessionNotFound -> UpdateSessionCurrentModelIdError.SessionNotFound(daoError.id)
                        is SessionError.ForeignKeyViolation -> UpdateSessionCurrentModelIdError.InvalidRelatedEntity(daoError.message)
                    }
                }) {
                    sessionDao.updateSessionCurrentModelId(id, modelId).bind()
                }
            }
        }

    override suspend fun updateSessionCurrentSettingsId(id: Long, settingsId: Long?): Either<UpdateSessionCurrentSettingsIdError, Unit> =
        transactionScope.transaction {
            either {
                withError({ daoError: SessionError ->
                    when(daoError) {
                        is SessionError.SessionNotFound -> UpdateSessionCurrentSettingsIdError.SessionNotFound(daoError.id)
                        is SessionError.ForeignKeyViolation -> UpdateSessionCurrentSettingsIdError.InvalidRelatedEntity(daoError.message)
                    }
                }) {
                    sessionDao.updateSessionCurrentSettingsId(id, settingsId).bind()
                }
            }
        }

    override suspend fun updateSessionLeafMessageId(id: Long, messageId: Long?): Either<UpdateSessionLeafMessageIdError, Unit> =
        transactionScope.transaction {
            either {
                withError({ daoError: SessionError ->
                    when(daoError) {
                        is SessionError.SessionNotFound -> UpdateSessionLeafMessageIdError.SessionNotFound(daoError.id)
                        is SessionError.ForeignKeyViolation -> UpdateSessionLeafMessageIdError.InvalidRelatedEntity(daoError.message)
                    }
                }) {
                    sessionDao.updateSessionLeafMessageId(id, messageId).bind()
                }
            }
        }

    override suspend fun deleteSession(id: Long): Either<DeleteSessionError, Unit> =
        transactionScope.transaction {
            either {
                withError({ daoError: SessionError.SessionNotFound ->
                    DeleteSessionError.SessionNotFound(daoError.id)
                }) {
                    sessionDao.deleteSession(id).bind()
                }
            }
        }
}

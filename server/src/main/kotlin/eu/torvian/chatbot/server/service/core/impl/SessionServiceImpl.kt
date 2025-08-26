package eu.torvian.chatbot.server.service.core.impl
import arrow.core.*
import arrow.core.raise.*
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import eu.torvian.chatbot.common.models.ChatModelSettings
import eu.torvian.chatbot.common.models.ChatSession
import eu.torvian.chatbot.common.models.ChatSessionSummary
import eu.torvian.chatbot.server.data.dao.ModelDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.SettingsDao
import eu.torvian.chatbot.server.data.dao.error.SessionError
import eu.torvian.chatbot.server.data.dao.error.SettingsError
import eu.torvian.chatbot.server.service.core.SessionService
import eu.torvian.chatbot.server.service.core.error.session.*
import eu.torvian.chatbot.server.utils.transactions.TransactionScope

/**
 * Implementation of the [SessionService] interface.
 */
class SessionServiceImpl(
    private val sessionDao: SessionDao,
    private val settingsDao: SettingsDao,
    private val modelDao: ModelDao,
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
                    // Update the model ID
                    sessionDao.updateSessionCurrentModelId(id, modelId).bind()

                    // Reset the currentSettingsId to null since settings are no longer valid for the new model
                    sessionDao.updateSessionCurrentSettingsId(id, null).bind()
                }
            }
        }

    override suspend fun updateSessionCurrentSettingsId(id: Long, settingsId: Long?): Either<UpdateSessionCurrentSettingsIdError, Unit> =
        transactionScope.transaction {
            either {
                if (settingsId != null) {
                    // First get the current session to check its model ID
                    val session = withError({ daoError: SessionError.SessionNotFound ->
                        UpdateSessionCurrentSettingsIdError.SessionNotFound(daoError.id)
                    }) {
                        sessionDao.getSessionById(id).bind()
                    }

                    // Then get the settings to check its model ID and type
                    val settings = withError({ daoError: SettingsError.SettingsNotFound ->
                        UpdateSessionCurrentSettingsIdError.InvalidRelatedEntity("Settings with ID $settingsId not found")
                    }) {
                        settingsDao.getSettingsById(settingsId).bind()
                    }

                    // Verify that the settings are of ChatModelSettings type
                    ensure(settings is ChatModelSettings) {
                        UpdateSessionCurrentSettingsIdError.InvalidSettingsType(
                            settingsId = settingsId,
                            actualType = settings::class.simpleName ?: "Unknown"
                        )
                    }

                    // Verify that the settings are valid for the current model
                    ensure(session.currentModelId != null && session.currentModelId == settings.modelId) {
                        UpdateSessionCurrentSettingsIdError.SettingsModelMismatch(
                            settingsId = settingsId,
                            settingsModelId = settings.modelId,
                            sessionModelId = session.currentModelId
                        )
                    }
                }

                // If validation passes (or settingsId is null), update the settings ID
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

    /**
     * Updates both the current model ID and settings ID of an existing chat session atomically.
     * This ensures consistency between model and settings, and automatically clears settings
     * if they're incompatible with the new model.
     *
     * @param id The ID of the session to update.
     * @param modelId The new optional model ID for the session.
     * @param settingsId The new optional settings ID for the session.
     * @return Either an error if the session, model, or settings are not found or incompatible,
     *         or Unit if successful.
     */
    override suspend fun updateSessionCurrentModelAndSettingsId(
        id: Long,
        modelId: Long?,
        settingsId: Long?
    ): Either<UpdateSessionCurrentModelAndSettingsIdError, Unit> =
        transactionScope.transaction {
            either {
                // First, verify the session exists
                withError({ daoError: SessionError.SessionNotFound ->
                    UpdateSessionCurrentModelAndSettingsIdError.SessionNotFound(daoError.id)
                }) {
                    sessionDao.getSessionById(id).bind()
                }

                // If modelId is provided, validate it exists
                if (modelId != null) {
                    withError({ _: eu.torvian.chatbot.server.data.dao.error.ModelError.ModelNotFound ->
                        UpdateSessionCurrentModelAndSettingsIdError.ModelNotFound(modelId)
                    }) {
                        modelDao.getModelById(modelId).bind()
                    }
                }

                // If settingsId is provided, validate it exists and is compatible with the model
                if (settingsId != null) {
                    val settings = withError({ _: SettingsError.SettingsNotFound ->
                        UpdateSessionCurrentModelAndSettingsIdError.SettingsNotFound(settingsId)
                    }) {
                        settingsDao.getSettingsById(settingsId).bind()
                    }

                    // Verify that the settings are of ChatModelSettings type
                    ensure(settings is ChatModelSettings) {
                        UpdateSessionCurrentModelAndSettingsIdError.InvalidSettingsType(
                            settingsId = settingsId,
                            actualType = settings::class.simpleName ?: "Unknown"
                        )
                    }

                    // If modelId is also provided, ensure compatibility
                    ensureNotNull(modelId) {
                        UpdateSessionCurrentModelAndSettingsIdError.InvalidRelatedEntity(
                            "Cannot assign settings without specifying a model"
                        )
                    }
                    ensure(settings.modelId == modelId) {
                        UpdateSessionCurrentModelAndSettingsIdError.SettingsModelMismatch(
                            settingsId = settingsId,
                            settingsModelId = settings.modelId,
                            providedModelId = modelId
                        )
                    }
                }

                // All validations passed, perform the updates
                withError({ daoError: SessionError ->
                    when(daoError) {
                        is SessionError.SessionNotFound -> UpdateSessionCurrentModelAndSettingsIdError.SessionNotFound(daoError.id)
                        is SessionError.ForeignKeyViolation -> UpdateSessionCurrentModelAndSettingsIdError.InvalidRelatedEntity(daoError.message)
                    }
                }) {
                    // Update model ID first
                    sessionDao.updateSessionCurrentModelId(id, modelId).bind()

                    // Update settings ID (will be null if modelId is null)
                    val finalSettingsId = if (modelId == null) null else settingsId
                    sessionDao.updateSessionCurrentSettingsId(id, finalSettingsId).bind()
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

package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.ChatSessionSummary
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModelType
import eu.torvian.chatbot.server.data.dao.*
import eu.torvian.chatbot.server.data.dao.error.*
import eu.torvian.chatbot.server.service.core.SessionService
import eu.torvian.chatbot.server.service.core.error.session.*

/**
 * Implementation of the [SessionService] interface.
 */
class SessionServiceImpl(
    private val sessionDao: SessionDao,
    private val sessionOwnershipDao: SessionOwnershipDao,
    private val settingsDao: SettingsDao,
    private val modelDao: ModelDao,
    private val messageDao: MessageDao,
    private val toolCallDao: ToolCallDao,
    private val sessionToolConfigDao: SessionToolConfigDao,
    private val transactionScope: TransactionScope,
) : SessionService {

    override suspend fun getAllSessionsSummaries(userId: Long): List<ChatSessionSummary> {
        return transactionScope.transaction {
            sessionOwnershipDao.getAllSessionsForUser(userId)
        }
    }

    override suspend fun createSession(userId: Long, name: String): Either<CreateSessionError, ChatSession> =
        transactionScope.transaction {
            either {
                ensure(name.isNotBlank()) {
                    CreateSessionError.InvalidName("Session name cannot be blank.")
                }

                val session = withError({ daoError: SessionError.ForeignKeyViolation ->
                    CreateSessionError.InvalidRelatedEntity(daoError.message)
                }) {
                    sessionDao.insertSession(name).bind()
                }

                // Set ownership for the newly created session
                withError({ ownershipError: SetOwnerError ->
                    when (ownershipError) {
                        is SetOwnerError.ForeignKeyViolation ->
                            CreateSessionError.InvalidRelatedEntity("Failed to set session ownership")

                        is SetOwnerError.AlreadyOwned ->
                            CreateSessionError.InvalidRelatedEntity("Session ownership conflict")
                    }
                }) {
                    sessionOwnershipDao.setOwner(session.id, userId).bind()
                }

                session
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
                    when (daoError) {
                        is SessionError.SessionNotFound -> UpdateSessionGroupIdError.SessionNotFound(daoError.id)
                        is SessionError.ForeignKeyViolation -> UpdateSessionGroupIdError.InvalidRelatedEntity(daoError.message)
                    }
                }) {
                    sessionDao.updateSessionGroupId(id, groupId).bind()
                }
            }
        }

    override suspend fun updateSessionCurrentModelId(
        id: Long,
        modelId: Long?
    ): Either<UpdateSessionCurrentModelIdError, Unit> =
        transactionScope.transaction {
            either {
                // If modelId is provided, validate that the model exists, is active, and is of CHAT type
                if (modelId != null) {
                    val model = withError({ _: ModelError.ModelNotFound ->
                        UpdateSessionCurrentModelIdError.InvalidRelatedEntity("Model with ID $modelId not found")
                    }) {
                        modelDao.getModelById(modelId).bind()
                    }
                    // Verify that the model is of CHAT type
                    ensure(model.type == LLMModelType.CHAT) {
                        UpdateSessionCurrentModelIdError.InvalidModelType(modelId, model.type.name)
                    }
                    // Guard against setting a deprecated (inactive) model
                    ensure(model.active) {
                        UpdateSessionCurrentModelIdError.DeprecatedModel(modelId)
                    }
                }

                withError({ daoError: SessionError ->
                    when (daoError) {
                        is SessionError.SessionNotFound -> UpdateSessionCurrentModelIdError.SessionNotFound(daoError.id)
                        is SessionError.ForeignKeyViolation -> UpdateSessionCurrentModelIdError.InvalidRelatedEntity(
                            daoError.message
                        )
                    }
                }) {
                    // Update the model ID
                    sessionDao.updateSessionCurrentModelId(id, modelId).bind()

                    // Reset the currentSettingsId to null since settings are no longer valid for the new model
                    sessionDao.updateSessionCurrentSettingsId(id, null).bind()
                }
            }
        }

    override suspend fun updateSessionCurrentSettingsId(
        id: Long,
        settingsId: Long?
    ): Either<UpdateSessionCurrentSettingsIdError, Unit> =
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
                    val settings = withError({ _: SettingsError.SettingsNotFound ->
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
                    when (daoError) {
                        is SessionError.SessionNotFound -> UpdateSessionCurrentSettingsIdError.SessionNotFound(daoError.id)
                        is SessionError.ForeignKeyViolation -> UpdateSessionCurrentSettingsIdError.InvalidRelatedEntity(
                            daoError.message
                        )
                    }
                }) {
                    sessionDao.updateSessionCurrentSettingsId(id, settingsId).bind()
                }
            }
        }

    override suspend fun updateSessionCurrentModelAndSettingsId(
        id: Long,
        modelId: Long?,
        settingsId: Long?
    ): Either<UpdateSessionCurrentModelAndSettingsIdError, Unit> =
        transactionScope.transaction {
            either {
                // If modelId is provided, validate it exists, is active, and is of CHAT type
                if (modelId != null) {
                    val model = withError({ _: ModelError.ModelNotFound ->
                        UpdateSessionCurrentModelAndSettingsIdError.ModelNotFound(modelId)
                    }) {
                        modelDao.getModelById(modelId).bind()
                    }
                    // Verify that the model is of CHAT type
                    ensure(model.type == LLMModelType.CHAT) {
                        UpdateSessionCurrentModelAndSettingsIdError.InvalidModelType(modelId, model.type.name)
                    }
                    // Guard against setting a deprecated (inactive) model
                    ensure(model.active) {
                        UpdateSessionCurrentModelAndSettingsIdError.DeprecatedModel(modelId)
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
                    when (daoError) {
                        is SessionError.SessionNotFound -> UpdateSessionCurrentModelAndSettingsIdError.SessionNotFound(
                            daoError.id
                        )

                        is SessionError.ForeignKeyViolation -> UpdateSessionCurrentModelAndSettingsIdError.InvalidRelatedEntity(
                            daoError.message
                        )
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

    override suspend fun updateSessionLeafMessageId(
        id: Long,
        messageId: Long?
    ): Either<UpdateSessionLeafMessageIdError, Unit> =
        transactionScope.transaction {
            either {
                withError({ daoError: SessionError ->
                    when (daoError) {
                        is SessionError.SessionNotFound -> UpdateSessionLeafMessageIdError.SessionNotFound(daoError.id)
                        is SessionError.ForeignKeyViolation -> UpdateSessionLeafMessageIdError.InvalidRelatedEntity(
                            daoError.message
                        )
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

    override suspend fun cloneSession(id: Long, name: String): Either<CloneSessionError, ChatSession> =
        transactionScope.transaction {
            either {
                // Validate name is not blank
                ensure(name.isNotBlank()) {
                    CloneSessionError.InvalidName("Session name cannot be blank.")
                }

                // Load original session
                val originalSession = withError({ daoError: SessionError.SessionNotFound ->
                    CloneSessionError.SessionNotFound(daoError.id)
                }) {
                    sessionDao.getSessionById(id).bind()
                }

                // Get original session's owner
                val ownerId = withError({ daoError: GetOwnerError ->
                    CloneSessionError.InternalError("Failed to get session ownership: $daoError")
                }) {
                    sessionOwnershipDao.getOwner(id).bind()
                }

                // Create new session with same configuration
                val newSession = withError({ daoError: SessionError.ForeignKeyViolation ->
                    CloneSessionError.InternalError("Failed to create cloned session: ${daoError.message}")
                }) {
                    sessionDao.insertSession(
                        name = name,
                        groupId = originalSession.groupId,
                        currentModelId = originalSession.currentModelId,
                        currentSettingsId = originalSession.currentSettingsId
                    ).bind()
                }

                // Set ownership for the cloned session
                withError({ ownershipError: SetOwnerError ->
                    CloneSessionError.InternalError("Failed to set session ownership: $ownershipError")
                }) {
                    sessionOwnershipDao.setOwner(newSession.id, ownerId).bind()
                }

                // Clone messages
                val originalMessages = messageDao.getMessagesBySessionId(id)
                val messageIdMap = mutableMapOf<Long, Long>() // oldId -> newId

                // Helper function to recursively clone messages from root to leaf
                suspend fun cloneMessageRecursively(message: ChatMessage) {
                    // Find the new parent ID (will be null for root messages)
                    val newParentId = message.parentMessageId?.let {
                        messageIdMap[it]
                            ?: raise(CloneSessionError.InternalError("Failed to clone messages: Parent message ${message.parentMessageId} not found"))
                    }

                    // Extract modelId and settingsId if this is an AssistantMessage
                    val modelId = (message as? ChatMessage.AssistantMessage)?.modelId
                    val settingsId = (message as? ChatMessage.AssistantMessage)?.settingsId

                    // Clone this message
                    val newMessage = withError({ daoError: InsertMessageError ->
                        CloneSessionError.InternalError("Failed to clone messages: $daoError")
                    }) {
                        messageDao.insertMessage(
                            sessionId = newSession.id,
                            targetMessageId = newParentId,
                            position = MessageInsertPosition.APPEND,
                            role = message.role,
                            content = message.content,
                            modelId = modelId,
                            settingsId = settingsId,
                            fileReferences = message.fileReferences,
                            createdAt = message.createdAt,
                            updatedAt = message.updatedAt
                        ).bind()
                    }

                    // Store the mapping
                    messageIdMap[message.id] = newMessage.id

                    // Recursively clone all children
                    val children = originalMessages.filter { it.parentMessageId == message.id }
                    for (child in children) {
                        cloneMessageRecursively(child)
                    }
                }

                // Start cloning from root messages (those with no parent)
                val rootMessages = originalMessages.filter { it.parentMessageId == null }
                for (rootMessage in rootMessages) {
                    cloneMessageRecursively(rootMessage)
                }

                // Update currentLeafMessageId to the mapped value
                val newLeafMessageId = originalSession.currentLeafMessageId?.let { messageIdMap[it] }
                if (newLeafMessageId != null) {
                    withError({ daoError: SessionError ->
                        CloneSessionError.InternalError("Failed to update leaf message: ${daoError}")
                    }) {
                        sessionDao.updateSessionLeafMessageId(newSession.id, newLeafMessageId).bind()
                    }
                }

                // Clone tool calls
                val originalToolCalls = toolCallDao.getToolCallsBySessionId(id)
                for (originalToolCall in originalToolCalls) {
                    val newMessageId = messageIdMap[originalToolCall.messageId]
                        ?: raise(CloneSessionError.InternalError("Failed to clone tool calls: Message ${originalToolCall.messageId} not found"))

                    withError({ daoError: InsertToolCallError ->
                        CloneSessionError.InternalError("Failed to clone tool calls: $daoError")
                    }) {
                        toolCallDao.insertToolCall(
                            messageId = newMessageId,
                            toolDefinitionId = originalToolCall.toolDefinitionId,
                            toolName = originalToolCall.toolName,
                            toolCallId = originalToolCall.toolCallId,
                            input = originalToolCall.input,
                            output = originalToolCall.output,
                            status = originalToolCall.status,
                            errorMessage = originalToolCall.errorMessage,
                            denialReason = originalToolCall.denialReason,
                            executedAt = originalToolCall.executedAt,
                            durationMs = originalToolCall.durationMs
                        ).bind()
                    }
                }

                // Clone session tool configurations
                val enabledTools = sessionToolConfigDao.getEnabledToolsForSession(id)
                if (enabledTools.isNotEmpty()) {
                    val toolIds = enabledTools.map { it.id }
                    withError({ daoError: SetToolsEnabledError ->
                        CloneSessionError.InternalError("Failed to clone tool configurations: ${daoError}")
                    }) {
                        sessionToolConfigDao.setToolsEnabledForSession(
                            sessionId = newSession.id,
                            toolDefinitionIds = toolIds,
                            enabled = true
                        ).bind()
                    }
                }

                // Load and return the complete cloned session with messages
                withError({ daoError: SessionError.SessionNotFound ->
                    CloneSessionError.SessionNotFound(daoError.id)
                }) {
                    sessionDao.getSessionById(newSession.id).bind()
                }
            }
        }
}

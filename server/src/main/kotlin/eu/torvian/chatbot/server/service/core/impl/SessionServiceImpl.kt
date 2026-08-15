package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.ChatSessionSummary
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
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
    private val messageDao: MessageDao,
    private val toolCallDao: ToolCallDao,
    private val sessionToolConfigDao: SessionToolConfigDao,
    private val agentRoleDao: AgentRoleDao,
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
                ensure(name.isNotBlank()) {
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

    override suspend fun updateSessionAgentRoleId(
        id: Long,
        agentRoleId: Long?
    ): Either<UpdateSessionAgentRoleIdError, Unit> =
        transactionScope.transaction {
            either {
                // When a role is selected, verify the role row exists so a session cannot be pointed at a
                // deleted role (ownership is enforced by the route layer).
                if (agentRoleId != null) {
                    withError({ _: AgentRoleError.NotFound ->
                        UpdateSessionAgentRoleIdError.AgentRoleNotFound(agentRoleId)
                    }) {
                        agentRoleDao.getRoleById(agentRoleId).bind()
                    }
                }

                withError({ daoError: SessionError ->
                    when (daoError) {
                        is SessionError.SessionNotFound -> UpdateSessionAgentRoleIdError.SessionNotFound(daoError.id)
                        is SessionError.ForeignKeyViolation -> UpdateSessionAgentRoleIdError.AgentRoleNotFound(
                            agentRoleId ?: 0L
                        )
                    }
                }) {
                    sessionDao.updateSessionAgentRoleId(id, agentRoleId).bind()
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
                        agentRoleId = originalSession.agentRoleId
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
                    val agentRoleId = (message as? ChatMessage.AssistantMessage)?.agentRoleId
                    val reasoningItems = (message as? ChatMessage.AssistantMessage)?.reasoningItems

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
                            agentRoleId = agentRoleId,
                            fileReferences = message.fileReferences,
                            reasoningItems = reasoningItems,
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
                        CloneSessionError.InternalError("Failed to update leaf message: $daoError")
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
                            durationMs = originalToolCall.durationMs,
                            errorCode = originalToolCall.errorCode,
                            errorDetails = originalToolCall.errorDetails
                        ).bind()
                    }
                }

                // Clone session tool configurations
                val enabledTools = sessionToolConfigDao.getEnabledToolsForSession(id)
                if (enabledTools.isNotEmpty()) {
                    val toolIds = enabledTools.map { it.id }
                    withError({ daoError: SetToolsEnabledError ->
                        CloneSessionError.InternalError("Failed to clone tool configurations: $daoError")
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

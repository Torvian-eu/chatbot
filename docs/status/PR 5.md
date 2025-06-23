Okay, Mark. Here is the code for **PR 5: Server Backend - Core Services (E2, E6 Basics) & Ktor Routes)**. This PR builds upon the foundational data models (PR 1), database schema/transaction scope (PR 2), and core DAOs (PR 4), and Secure Credential Manager (PR 3 Interface).

This PR implements the core business logic for basic session and group management within the Service layer, integrating the DAO interactions using the `TransactionScope` and handling errors using Arrow's `Either`. It also sets up the Ktor routes to expose these Service functions as API endpoints, handling request parsing, service calls, and response formatting, including mapping backend errors to appropriate HTTP status codes.

It includes KDoc documentation for all public components.

* * *

**PR 5: Server Backend - Core Services (E2, E6 Basics) & Ktor Routes**

*   **Assignee:** Alex
*   **Reviewer:** Eric, Maya
*   **Description:** Implement the backend Service layer methods corresponding to basic Session (E2.S1, E2.S3, E2.S4, E6.S1 assign), Group (E6.S3, E6.S4, E6.S6 delete logic), and Credential Status Check (E5.S4) features. These services orchestrate calls to the DAOs, use the `TransactionScope`, and handle errors using Arrow `Either`. Set up the corresponding Ktor API routes (`/api/v1/sessions`, `/api/v1/sessions/{id}`, `/api/v1/sessions/{id}/group`, `/api/v1/groups`, `/api/v1/groups/{id}`, `/api/v1/models/{id}/apikey/status`) in `ApiRoutes.kt` to call these service methods, handling request/response serialization and mapping service results/errors to appropriate HTTP responses.
*   **Stories Addressed:** E2.S1, E2.S3, E2.S4 backend, E6.S1 backend, E6.S3, E6.S4, E6.S6 backend, E5.S4 backend, E1.S4 (LLMClient interface/stub), E7.S3 (Ktor routing setup), E7.S6 (coroutines in services/routes).
*   **Key Files:**
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/service/SessionService.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/service/impl/SessionServiceImpl.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/service/GroupService.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/service/impl/GroupServiceImpl.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/service/ModelService.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/service/impl/ModelServiceImpl.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/service/MessageService.kt` (Interface)
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/service/impl/MessageServiceImpl.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/external/llm/LLMApiClient.kt` (Interface)
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/external/llm/LLMApiClientKtor.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/api/server/ApiRoutes.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/service/error/*Error.kt` (New Service-level error types)

---

```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/service/error/ServiceError.kt
package eu.torvian.chatbot.server.service.error

import eu.torvian.chatbot.server.data.dao.error.* // Import DAO error types

/**
 * Sealed interface representing possible domain-specific errors that can occur in the Service layer.
 * These errors might wrap DAO errors or represent business logic failures.
 */
sealed interface ServiceError {
    /**
     * Indicates that a specific entity (Session, Message, Group, Model, Settings, ApiSecret) was not found.
     */
    sealed interface NotFound : ServiceError {
        data class Session(val id: Long) : NotFound, ServiceError
        data class Message(val id: Long) : NotFound, ServiceError
        data class Group(val id: Long) : NotFound, ServiceError
        data class Model(val id: Long) : NotFound, ServiceError
        data class Settings(val id: Long) : NotFound, ServiceError
        data class ApiSecret(val alias: String) : NotFound, ServiceError
    }

    /**
     * Indicates an invalid operation or request data.
     */
    data class InvalidOperation(val message: String) : ServiceError

    /**
     * Indicates a failure related to external communication, such as with the LLM API.
     */
    data class ExternalServiceError(val message: String) : ServiceError

    /**
     * Indicates a configuration issue, such as missing required settings for an operation.
     */
    data class ConfigurationError(val message: String) : ServiceError

    /**
     * Indicates an error during credential management, possibly wrapping a lower-level error.
     */
    data class CredentialError(val message: String) : ServiceError

    /**
     * Represents errors originating from the Data Access Layer (DAO).
     * These wrap specific DAO error types.
     */
    sealed interface DaoError : ServiceError {
        data class Session(val error: SessionError) : DaoError
        data class Message(val error: MessageError) : DaoError
        data class Group(val error: GroupError) : DaoError
        data class Model(val error: ModelError) : DaoError
        data class Settings(val error: SettingsError) : DaoError
        data class ApiSecret(val error: ApiSecretError) : DaoError
    }

    // --- Mapping functions from DAO errors to Service errors ---

    fun SessionError.toServiceError(): DaoError.Session = DaoError.Session(this)
    fun MessageError.toServiceError(): DaoError.Message = DaoError.Message(this)
    fun GroupError.toServiceError(): DaoError.Group = DaoError.Group(this)
    fun ModelError.toServiceError(): DaoError.Model = DaoError.Model(this)
    fun SettingsError.toServiceError(): DaoError.Settings = DaoError.Settings(this)
    fun ApiSecretError.toServiceError(): DaoError.ApiSecret = DaoError.ApiSecret(this)
}
```

```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/service/SessionService.kt
package eu.torvian.chatbot.server.service

import arrow.core.Either
import eu.torvian.chatbot.common.models.ChatSession
import eu.torvian.chatbot.common.models.ChatSessionSummary
import eu.torvian.chatbot.server.service.error.ServiceError

/**
 * Service interface for managing chat sessions.
 * Contains core business logic related to sessions, independent of API or data access details.
 */
interface SessionService {
    /**
     * Retrieves summaries for all chat sessions, including group names.
     * @return A list of [ChatSessionSummary] objects. Returns an empty list if no sessions exist.
     */
    suspend fun getAllSessionsSummaries(): List<ChatSessionSummary>

    /**
     * Creates a new chat session.
     * @param name Optional name for the session. If null or blank, a default name may be generated.
     * @return Either a [ServiceError.InvalidOperation] if the request is invalid,
     *         or the newly created [ChatSession].
     */
    suspend fun createSession(name: String?): Either<ServiceError, ChatSession>

    /**
     * Retrieves full details for a specific chat session, including all messages.
     * @param id The ID of the session to retrieve.
     * @return Either a [ServiceError.NotFound.Session] if the session doesn't exist,
     *         or the [ChatSession] object with messages.
     */
    suspend fun getSessionDetails(id: Long): Either<ServiceError.NotFound.Session, ChatSession>

    /**
     * Updates details for a specific chat session.
     * Allows updating name, current model, current settings, and current leaf message ID.
     * @param session The [ChatSession] object with updated fields.
     * @return Either a [ServiceError] or Unit if successful.
     */
    suspend fun updateSessionDetails(session: ChatSession): Either<ServiceError, Unit>

    /**
     * Deletes a chat session and all its messages.
     * @param id The ID of the session to delete.
     * @return Either a [ServiceError.NotFound.Session] if the session doesn't exist, or Unit if successful.
     */
    suspend fun deleteSession(id: Long): Either<ServiceError.NotFound.Session, Unit>

    /**
     * Assigns a chat session to a specific group, or ungroups it.
     * @param id The ID of the session to assign.
     * @param groupId The ID of the target group, or null to ungroup.
     * @return Either a [ServiceError] if the session or group is not found,
     *         or the updated [ChatSessionSummary].
     */
    suspend fun assignSessionToGroup(id: Long, groupId: Long?): Either<ServiceError, ChatSessionSummary>
}
```

```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/service/impl/SessionServiceImpl.kt
package eu.torvian.chatbot.server.service.impl

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.right
import eu.torvian.chatbot.common.models.ChatSession
import eu.torvian.chatbot.common.models.ChatSessionSummary
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.error.SessionError
import eu.torvian.chatbot.server.service.SessionService
import eu.torvian.chatbot.server.service.error.ServiceError
import eu.torvian.chatbot.server.service.error.ServiceError.DaoError.Session.toServiceError // Import mapping extension
import eu.torvian.chatbot.server.service.error.ServiceError.NotFound.Session
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import java.util.logging.Logger

private val logger = Logger.getLogger(SessionServiceImpl::class.java.name)

/**
 * Implementation of the [SessionService].
 * Orchestrates calls to [SessionDao] and uses [TransactionScope] for transaction management.
 */
class SessionServiceImpl(
    private val sessionDao: SessionDao,
    private val transactionScope: TransactionScope
) : SessionService {

    override suspend fun getAllSessionsSummaries(): List<ChatSessionSummary> {
        // Read operation, can use transactionScope for consistency or just DAO call
        // Use transactionScope to ensure it runs on IO dispatcher
        return transactionScope.transaction {
            sessionDao.getAllSessions()
        }
    }

    override suspend fun createSession(name: String?): Either<ServiceError, ChatSession> {
        val sessionName = name?.takeIf { it.isNotBlank() } ?: "New Chat"
        logger.info("Attempting to create session: $sessionName")
        
        return transactionScope.transaction {
            sessionDao.insertSession(sessionName) // DAO returns Either<ForeignKeyViolation, ChatSession>
                .mapLeft { daoError -> // Map DAO error to Service error
                    when(daoError) {
                        is SessionError.ForeignKeyViolation -> ServiceError.InvalidOperation("Invalid data for new session: ${daoError.message}")
                        is SessionError.SessionNotFound -> ServiceError.InvalidOperation("Unexpected error: Session not found after insert") // Should not happen
                    }
                }
        }
    }

    override suspend fun getSessionDetails(id: Long): Either<ServiceError.NotFound.Session, ChatSession> {
        logger.fine("Attempting to get session details for ID: $id")
        return transactionScope.transaction {
            sessionDao.getSessionById(id) // DAO returns Either<SessionNotFound, ChatSession>
                .mapLeft { daoError -> // Map DAO error to Service error
                    when(daoError) {
                        is SessionError.SessionNotFound -> Session(id)
                        is SessionError.ForeignKeyViolation -> ServiceError.InvalidOperation("Unexpected foreign key issue fetching session") // Should not happen on read
                    }
                }
        }
    }

    override suspend fun updateSessionDetails(session: ChatSession): Either<ServiceError, Unit> {
         logger.fine("Attempting to update session details for ID: ${session.id}")
         return transactionScope.transaction {
             // DAO returns Either<SessionError, Unit> for update methods
             sessionDao.updateSessionName(session.id, session.name) // Update name
                 .flatMap { sessionDao.updateSessionGroupId(session.id, session.groupId) } // Update groupId
                 .flatMap { sessionDao.updateSessionCurrentModelId(session.id, session.currentModelId) } // Update modelId
                 .flatMap { sessionDao.updateSessionCurrentSettingsId(session.id, session.currentSettingsId) } // Update settingsId
                 .flatMap { sessionDao.updateSessionLeafMessageId(session.id, session.currentLeafMessageId) } // Update leafId
                 .mapLeft { daoError -> // Map DAO error to Service error
                     when(daoError) {
                         is SessionError.SessionNotFound -> Session(session.id)
                         is SessionError.ForeignKeyViolation -> ServiceError.InvalidOperation("Invalid foreign key for session update: ${daoError.message}")
                     }
                 }
         }
    }

    override suspend fun deleteSession(id: Long): Either<ServiceError.NotFound.Session, Unit> {
        logger.info("Attempting to delete session ID: $id")
        return transactionScope.transaction {
            sessionDao.deleteSession(id) // DAO returns Either<SessionNotFound, Unit>
                .mapLeft { daoError -> // Map DAO error to Service error
                    when(daoError) {
                         is SessionError.SessionNotFound -> Session(id)
                         is SessionError.ForeignKeyViolation -> ServiceError.InvalidOperation("Unexpected foreign key issue deleting session") // Should not happen
                    }
                }
        }
    }

    override suspend fun assignSessionToGroup(id: Long, groupId: Long?): Either<ServiceError, ChatSessionSummary> {
        logger.fine("Attempting to assign session $id to group ID: $groupId")
        return transactionScope.transaction {
             // First, update the session's group ID
             sessionDao.updateSessionGroupId(id, groupId) // DAO returns Either<SessionError, Unit>
                 .mapLeft { daoError -> // Map DAO error to Service error
                     when(daoError) {
                          is SessionError.SessionNotFound -> Session(id) // Session not found
                          is SessionError.ForeignKeyViolation -> ServiceError.NotFound.Group(groupId ?: -1) // Group not found if groupId is not null
                     }
                 }
                 // If update was successful (Right(Unit)), then fetch the updated session summary
                 .flatMap {
                      // Fetch the updated summary. DAO returns List<ChatSessionSummary>
                      val sessions = sessionDao.getAllSessions()
                      val updatedSummary = sessions.firstOrNull { it.id == id }
                      
                      // Check if we found the updated summary (should always be the case if update succeeded)
                      updatedSummary?.right() ?: Either.Left(ServiceError.InvalidOperation("Failed to retrieve session summary after group assignment"))
                 }
        }
    }
}
```

```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/service/GroupService.kt
package eu.torvian.chatbot.server.service

import arrow.core.Either
import eu.torvian.chatbot.common.models.ChatGroup
import eu.torvian.chatbot.server.service.error.ServiceError

/**
 * Service interface for managing chat session groups.
 * Defines business logic for group creation, retrieval, renaming, and deletion.
 */
interface GroupService {
    /**
     * Retrieves a list of all chat groups.
     * @return A list of [ChatGroup] objects. Returns an empty list if no groups exist.
     */
    suspend fun getAllGroups(): List<ChatGroup>

    /**
     * Creates a new chat group.
     * @param name The name for the new group. Must not be blank.
     * @return Either a [ServiceError.InvalidOperation] if the name is blank,
     *         or the newly created [ChatGroup].
     */
    suspend fun createGroup(name: String): Either<ServiceError, ChatGroup>

    /**
     * Renames an existing chat group.
     * @param id The ID of the group to rename.
     * @param newName The new name for the group. Must not be blank.
     * @return Either a [ServiceError] if the group is not found or the new name is invalid,
     *         or Unit if successful.
     */
    suspend fun renameGroup(id: Long, newName: String): Either<ServiceError, Unit>

    /**
     * Deletes a chat group by ID.
     * Sessions previously assigned to this group will become ungrouped.
     * @param id The ID of the group to delete.
     * @return Either a [ServiceError.NotFound.Group] if the group doesn't exist, or Unit if successful.
     */
    suspend fun deleteGroup(id: Long): Either<ServiceError.NotFound.Group, Unit>
}
```

```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/service/impl/GroupServiceImpl.kt
package eu.torvian.chatbot.server.service.impl

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import eu.torvian.chatbot.common.models.ChatGroup
import eu.torvian.chatbot.server.data.dao.GroupDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.error.GroupError
import eu.torvian.chatbot.server.service.GroupService
import eu.torvian.chatbot.server.service.error.ServiceError
import eu.torvian.chatbot.server.service.error.ServiceError.NotFound.Group
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import java.util.logging.Logger

private val logger = Logger.getLogger(GroupServiceImpl::class.java.name)

/**
 * Implementation of the [GroupService].
 * Orchestrates calls to [GroupDao], [SessionDao] and uses [TransactionScope].
 */
class GroupServiceImpl(
    private val groupDao: GroupDao,
    private val sessionDao: SessionDao, // Needed for ungrouping sessions on group delete
    private val transactionScope: TransactionScope
) : GroupService {

    override suspend fun getAllGroups(): List<ChatGroup> {
        // Read operation, use transactionScope for consistency
        return transactionScope.transaction {
            groupDao.getAllGroups()
        }
    }

    override suspend fun createGroup(name: String): Either<ServiceError, ChatGroup> {
        if (name.isBlank()) {
            logger.warning("Attempted to create group with blank name")
            return ServiceError.InvalidOperation("Group name cannot be blank").left()
        }
        logger.info("Attempting to create group: $name")

        return transactionScope.transaction {
            // DAO does not return Either for insert if it's expected to always succeed given valid input
            groupDao.insertGroup(name).right() // Assuming insertGroup is suspend and returns ChatGroup
        }
    }

    override suspend fun renameGroup(id: Long, newName: String): Either<ServiceError, Unit> {
        if (newName.isBlank()) {
            logger.warning("Attempted to rename group $id with blank name")
            return ServiceError.InvalidOperation("New group name cannot be blank").left()
        }
        logger.info("Attempting to rename group $id to: $newName")

        return transactionScope.transaction {
            groupDao.renameGroup(id, newName) // DAO returns Either<GroupError, Unit>
                .mapLeft { daoError -> // Map DAO error to Service error
                    when(daoError) {
                        is GroupError.GroupNotFound -> Group(id)
                    }
                }
        }
    }

    override suspend fun deleteGroup(id: Long): Either<ServiceError.NotFound.Group, Unit> {
        logger.info("Attempting to delete group ID: $id")
        return transactionScope.transaction {
            // Business logic: Ungroup all sessions in this group *before* deleting the group
            sessionDao.ungroupSessions(id) // This DAO method doesn't return Either as it shouldn't fail on 'NotFound' group ID for sessions

            // Then delete the group
            groupDao.deleteGroup(id) // DAO returns Either<GroupError.GroupNotFound, Unit>
                .mapLeft { daoError -> // Map DAO error to Service error
                    when(daoError) {
                        is GroupError.GroupNotFound -> Group(id)
                    }
                }
        }
    }
}
```

```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/service/ModelService.kt
package eu.torvian.chatbot.server.service

import arrow.core.Either
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.common.models.ModelSettings
import eu.torvian.chatbot.server.service.error.ServiceError

/**
 * Service interface for managing LLM Models and their Settings.
 */
interface ModelService {
    /**
     * Retrieves all LLM model configurations.
     */
    suspend fun getAllModels(): List<LLMModel>

    /**
     * Adds a new LLM model configuration.
     * Handles secure storage of the API key if provided.
     * @param name The display name for the model.
     * @param baseUrl The base URL for the LLM API.
     * @param type The type of LLM provider.
     * @param apiKey Optional raw API key string to be stored securely.
     * @return Either a [ServiceError] if model creation or key storage fails,
     *         or the newly created [LLMModel] (without the raw key).
     */
    suspend fun addModel(name: String, baseUrl: String, type: String, apiKey: String?): Either<ServiceError, LLMModel>

    /**
     * Updates an existing LLM model configuration.
     * Handles updating the API key if a new one is provided.
     * @param id The ID of the model to update.
     * @param name New display name (optional).
     * @param baseUrl New base URL (optional).
     * @param type New type (optional).
     * @param apiKey Optional new raw API key string to update the stored key.
     * @return Either a [ServiceError] if the model is not found, update fails, or key update fails,
     *         or the updated [LLMModel] (without the raw key).
     */
    suspend fun updateModel(id: Long, name: String?, baseUrl: String?, type: String?, apiKey: String?): Either<ServiceError, LLMModel>

    /**
     * Deletes an LLM model configuration.
     * Handles deletion of associated settings and the securely stored API key.
     * @param id The ID of the model to delete.
     * @return Either a [ServiceError.NotFound.Model] if the model doesn't exist, or Unit if successful.
     */
    suspend fun deleteModel(id: Long): Either<ServiceError.NotFound.Model, Unit>

    /**
     * Retrieves a specific settings profile by ID.
     * @param id The ID of the settings profile.
     * @return Either a [ServiceError.NotFound.Settings] if not found, or the [ModelSettings].
     */
    suspend fun getSettingsById(id: Long): Either<ServiceError.NotFound.Settings, ModelSettings>

    /**
     * Retrieves all settings profiles stored in the database.
     * @return A list of all [ModelSettings] objects.
     */
    suspend fun getAllSettings(): List<ModelSettings> // Non-suspend if just delegates to suspend DAO? Let's keep suspend for consistency

    /**
     * Retrieves all settings profiles associated with a specific LLM model.
     * @param modelId The ID of the LLM model.
     * @return A list of [ModelSettings] for the model, or an empty list if none exist.
     */
    suspend fun getSettingsByModelId(modelId: Long): List<ModelSettings> // Keep suspend

    /**
     * Adds a new settings profile for a model.
     * @param modelId The ID of the associated model.
     * @param name The name of the settings profile.
     * @param systemMessage System message (optional).
     * @param temperature Temperature (optional).
     * @param maxTokens Max tokens (optional).
     * @param customParamsJson Custom params JSON (optional).
     * @return Either a [ServiceError] if the model is not found or insertion fails,
     *         or the newly created [ModelSettings].
     */
    suspend fun addSettings(
        modelId: Long, name: String, systemMessage: String?, temperature: Float?,
        maxTokens: Int?, customParamsJson: String?
    ): Either<ServiceError, ModelSettings>

    /**
     * Updates an existing settings profile.
     * @param id The ID of the settings profile to update.
     * @param name New name (optional).
     * @param systemMessage New system message (optional).
     * @param temperature New temperature (optional).
     * @param maxTokens New max tokens (optional).
     * @param customParamsJson New custom params JSON (optional).
     * @return Either a [ServiceError] if not found or update fails, or Unit if successful.
     */
    suspend fun updateSettings(
        id: Long, name: String?, systemMessage: String?, temperature: Float?,
        maxTokens: Int?, customParamsJson: String?
    ): Either<ServiceError, Unit>

    /**
     * Deletes a settings profile.
     * @param id The ID of the settings profile to delete.
     * @return Either a [ServiceError.NotFound.Settings] if not found, or Unit if successful.
     */
    suspend fun deleteSettings(id: Long): Either<ServiceError.NotFound.Settings, Unit>
}
```

```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/service/impl/ModelServiceImpl.kt
package eu.torvian.chatbot.server.service.impl

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.common.models.ModelSettings
import eu.torvian.chatbot.server.data.dao.ModelDao
import eu.torvian.chatbot.server.data.dao.SettingsDao
import eu.torvian.chatbot.server.data.dao.error.ModelError
import eu.torvian.chatbot.server.data.dao.error.SettingsError
import eu.torvian.chatbot.server.external.security.CredentialManager
import eu.torvian.chatbot.server.service.ModelService
import eu.torvian.chatbot.server.service.error.ServiceError
import eu.torvian.chatbot.server.service.error.ServiceError.NotFound.Model
import eu.torvian.chatbot.server.service.error.ServiceError.NotFound.Settings
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import java.util.logging.Level
import java.util.logging.Logger

private val logger = Logger.getLogger(ModelServiceImpl::class.java.name)

/**
 * Implementation of the [ModelService].
 * Orchestrates calls to [ModelDao], [SettingsDao], [CredentialManager] and uses [TransactionScope].
 * Handles business logic for model and settings management, including secure key handling (Epic 5).
 */
class ModelServiceImpl(
    private val modelDao: ModelDao,
    private val settingsDao: SettingsDao,
    private val credentialManager: CredentialManager, // Injected for secure key handling (E5.S1 interface)
    private val transactionScope: TransactionScope // Injected
) : ModelService {

    override suspend fun getAllModels(): List<LLMModel> {
        return transactionScope.transaction {
            modelDao.getAllModels()
        }
    }

    override suspend fun addModel(name: String, baseUrl: String, type: String, apiKey: String?): Either<ServiceError, LLMModel> {
        logger.info("Attempting to add model: $name")
        // Use transactionScope here although CredentialManager might not be transactional,
        // but it ensures DB operations (insert model) are atomic.
        return transactionScope.transaction {
            // Securely store API key if provided
            val apiKeyId = if (apiKey != null) {
                credentialManager.storeCredential(apiKey)
                    .right() // Treat null from storeCredential as potential error
                    .mapLeft { ServiceError.CredentialError("Failed to securely store API key") }
                    .flatMap { alias -> alias?.right() ?: ServiceError.CredentialError("Secure storage returned null alias").left() }
                    .getOrHandle { error ->
                        logger.log(Level.SEVERE, "Failed to store credential during addModel for $name: $error")
                        return@transaction Either.Left(error) // Propagate error and stop
                    }
            } else {
                null // No API key provided
            }

            // Insert model with the apiKeyId
            modelDao.insertModel(name, baseUrl, type, apiKeyId).right() // Assuming insertModel is suspend and returns LLMModel
        }
    }


    override suspend fun updateModel(id: Long, name: String?, baseUrl: String?, type: String?, apiKey: String?): Either<ServiceError, LLMModel> {
        logger.info("Attempting to update model ID: $id")
        return transactionScope.transaction {
            // Get current model to know existing apiKeyId
            modelDao.getModelById(id) // DAO returns Either<ModelNotFound, LLMModel>
                .mapLeft { daoError -> when(daoError) { is ModelError.ModelNotFound -> Model(id) } } // Map error
                .flatMap { existingModel ->
                    // Handle API key update if a new key string is provided
                    val newApiKeyId = if (apiKey != null) {
                        // Delete old key if it existed and was different (optional: compare apiKey string if possible, or just delete based on alias)
                        // Simple approach for V1.1: If new key string provided, delete old alias and store new one.
                        if (existingModel.apiKeyId != null) {
                            credentialManager.deleteCredential(existingModel.apiKeyId) // Ignore failure to delete old key? Log it.
                                .right() // Treat boolean as Either<CredentialError, Boolean>
                                .mapLeft { ServiceError.CredentialError("Failed to securely delete old API key for alias ${existingModel.apiKeyId}") }
                                .getOrHandle { error -> logger.log(Level.WARNING, error.message); Unit.right().bind() } // Log and continue, or propagate? Log and continue.
                        }

                        // Store the new key
                        credentialManager.storeCredential(apiKey)
                            .right()
                             .mapLeft { ServiceError.CredentialError("Failed to securely store new API key during update for $id") }
                             .flatMap { alias -> alias?.right() ?: ServiceError.CredentialError("Secure storage returned null alias during update").left() }
                             .getOrHandle { error ->
                                 logger.log(Level.SEVERE, error.message);
                                 return@transaction Either.Left(error) // Propagate error and stop
                             }
                    } else {
                        // No new key provided, keep the existing apiKeyId
                        existingModel.apiKeyId
                    }

                    // Create updated model object (only update provided fields)
                    val updatedModel = existingModel.copy(
                        name = name ?: existingModel.name,
                        baseUrl = baseUrl ?: existingModel.baseUrl,
                        type = type ?: existingModel.type,
                        apiKeyId = newApiKeyId // Use the new alias (or kept old one)
                    )

                    // Update model in DB
                    modelDao.updateModel(updatedModel) // DAO returns Either<ModelError, Unit>
                        .mapLeft { daoError -> when(daoError) { is ModelError.ModelNotFound -> Model(id) } } // Map error
                        .flatMap { updatedModel.right() } // Return the updated model object on success
                }
        }
    }


    override suspend fun deleteModel(id: Long): Either<ServiceError.NotFound.Model, Unit> {
        logger.info("Attempting to delete model ID: $id")
        return transactionScope.transaction {
            // Get the model first to find the apiKeyId
            modelDao.getModelById(id) // DAO returns Either<ModelNotFound, LLMModel>
                .mapLeft { daoError -> when(daoError) { is ModelError.ModelNotFound -> Model(id) } } // Map error
                .flatMap { modelToDelete ->
                    // Delete the model from DB. ON DELETE CASCADE in schema handles settings deletion.
                    modelDao.deleteModel(id) // DAO returns Either<ModelNotFound, Unit>
                        .mapLeft { daoError -> when(daoError) { is ModelError.ModelNotFound -> Model(id) } } // Map error
                        .flatMap {
                            // If model deletion succeeds, attempt to delete the secure credential
                            if (modelToDelete.apiKeyId != null) {
                                credentialManager.deleteCredential(modelToDelete.apiKeyId) // Returns Boolean, wrap in Either
                                    .right()
                                    .mapLeft { ServiceError.CredentialError("Failed to securely delete API key for alias ${modelToDelete.apiKeyId}") }
                                    .flatMap { success -> if (success) Unit.right() else ServiceError.CredentialError("Credential Manager reported failure to delete alias ${modelToDelete.apiKeyId}").left() }
                                    .getOrHandle { error ->
                                        logger.log(Level.WARNING, error.message)
                                        Unit.right().bind() // Log error but allow model deletion to succeed
                                    }
                            }
                            Unit.right().bind() // Return Unit on overall success
                        }
                }
        }
    }

    override suspend fun getSettingsById(id: Long): Either<ServiceError.NotFound.Settings, ModelSettings> {
        logger.fine("Attempting to get settings details for ID: $id")
        return transactionScope.transaction {
            settingsDao.getSettingsById(id) // DAO returns Either<SettingsNotFound, ModelSettings>
                 .mapLeft { daoError -> when(daoError) { is SettingsError.SettingsNotFound -> Settings(id) } } // Map error
        }
    }

    override suspend fun getAllSettings(): List<ModelSettings> {
        return transactionScope.transaction {
            settingsDao.getAllSettings()
        }
    }

    override suspend fun getSettingsByModelId(modelId: Long): List<ModelSettings> {
        return transactionScope.transaction {
            settingsDao.getSettingsByModelId(modelId)
        }
    }

    override suspend fun addSettings(
        modelId: Long, name: String, systemMessage: String?, temperature: Float?,
        maxTokens: Int?, customParamsJson: String?
    ): Either<ServiceError, ModelSettings> {
        logger.info("Attempting to add settings '$name' for model ID: $modelId")
        return transactionScope.transaction {
            settingsDao.insertSettings(name, modelId, systemMessage, temperature, maxTokens, customParamsJson) // DAO returns Either<SettingsError.ModelNotFound, ModelSettings>
                 .mapLeft { daoError -> when(daoError) { is SettingsError.ModelNotFound -> Model(modelId) } } // Map error
        }
    }

    override suspend fun updateSettings(
        id: Long, name: String?, systemMessage: String?, temperature: Float?,
        maxTokens: Int?, customParamsJson: String?
    ): Either<ServiceError, Unit> {
        logger.fine("Attempting to update settings ID: $id")
        return transactionScope.transaction {
             // Fetch existing settings to create updated model object (only update provided fields)
             settingsDao.getSettingsById(id) // DAO returns Either<SettingsNotFound, ModelSettings>
                 .mapLeft { daoError -> when(daoError) { is SettingsError.SettingsNotFound -> Settings(id) } } // Map error
                 .flatMap { existingSettings ->
                      val updatedSettings = existingSettings.copy(
                           name = name ?: existingSettings.name,
                           systemMessage = systemMessage ?: existingSettings.systemMessage,
                           temperature = temperature ?: existingSettings.temperature,
                           maxTokens = maxTokens ?: existingSettings.maxTokens,
                           customParamsJson = customParamsJson ?: existingSettings.customParamsJson
                      )
                      settingsDao.updateSettings(updatedSettings) // DAO returns Either<SettingsError, Unit>
                           .mapLeft { daoError -> when(daoError) { is SettingsError.SettingsNotFound -> Settings(id) } } // Map error
                 }
        }
    }


    override suspend fun deleteSettings(id: Long): Either<ServiceError.NotFound.Settings, Unit> {
        logger.info("Attempting to delete settings ID: $id")
        return transactionScope.transaction {
            settingsDao.deleteSettings(id) // DAO returns Either<SettingsError.SettingsNotFound, Unit>
                 .mapLeft { daoError -> when(daoError) { is SettingsError.SettingsNotFound -> Settings(id) } } // Map error
        }
    }
}
```

```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/service/MessageService.kt
package eu.torvian.chatbot.server.service

import arrow.core.Either
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.server.service.error.ServiceError

/**
 * Service interface for managing Chat Messages and their threading relationships.
 * Contains core business logic for message processing and modification.
 */
interface MessageService {

    /**
     * Retrieves a list of all messages for a specific session, ordered by creation time.
     * @param sessionId The ID of the session.
     * @return A list of [ChatMessage] objects.
     */
    suspend fun getMessagesBySessionId(sessionId: Long): List<ChatMessage> // List doesn't use Either, assume session exists (checked by SessionService)

    /**
     * Processes a new incoming user message (including replies).
     *
     * Orchestrates saving the user message, building LLM context (thread-aware),
     * calling the LLM (stubbed in V1.1), saving the assistant message, and
     * updating thread relationships in the database.
     *
     * @param sessionId The ID of the session the message belongs to.
     * @param content The user's message content.
     * @param parentMessageId Optional ID of the message being replied to (null for root messages).
     * @return Either a [ServiceError] if processing fails (e.g., session/parent not found, LLM config error, LLM API error),
     *         or a list containing the newly created user and assistant messages ([userMsg, assistantMsg]).
     */
    suspend fun processNewMessage(sessionId: Long, content: String, parentMessageId: Long? = null): Either<ServiceError, List<ChatMessage>>

    /**
     * Updates the content of an existing message.
     * @param id The ID of the message to update.
     * @param content The new content.
     * @return Either a [ServiceError.NotFound.Message] if the message doesn't exist,
     *         or the updated [ChatMessage].
     */
    suspend fun updateMessageContent(id: Long, content: String): Either<ServiceError.NotFound.Message, ChatMessage>

    /**
     * Deletes a specific message and its children recursively.
     * Updates the parent's children list.
     * @param id The ID of the message to delete.
     * @return Either a [ServiceError.NotFound.Message] if the message doesn't exist, or Unit if successful.
     */
    suspend fun deleteMessage(id: Long): Either<ServiceError.NotFound.Message, Unit>

    /**
     * Checks if an API key is configured for a specific model.
     * Included here based on initial API/Service structure, but ideally resides in ModelService.
     * Moving this to ModelService in a later refactor is advisable.
     * @param modelId The ID of the model.
     * @return True if an API key ID is stored for the model, false otherwise.
     */
    suspend fun isApiKeyConfiguredForModel(modelId: Long): Boolean // Does not return Either, simple boolean check
}
```

```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/service/impl/MessageServiceImpl.kt
package eu.torvian.chatbot.server.service.impl

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.ModelDao
import eu.torvian.chatbot.server.data.dao.SessionDao // Needed to update session leaf ID
import eu.torvian.chatbot.server.data.dao.SettingsDao
import eu.torvian.chatbot.server.data.dao.error.MessageError
import eu.torvian.chatbot.server.external.llm.LLMApiClient
import eu.torvian.chatbot.server.external.security.CredentialManager
import eu.torvian.chatbot.server.service.MessageService
import eu.torvian.chatbot.server.service.error.ServiceError
import eu.torvian.chatbot.server.service.error.ServiceError.NotFound.Message
import eu.torvian.chatbot.server.service.error.toServiceError
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import java.util.logging.Level
import java.util.logging.Logger

private val logger = Logger.getLogger(MessageServiceImpl::class.java.name)

/**
 * Implementation of the [MessageService].
 * Handles core message business logic, including processing new messages
 * (orchestrating LLM calls and thread updates), and managing message lifecycle.
 * Uses [TransactionScope] for atomic operations.
 *
 * Note: `isApiKeyConfiguredForModel` is included here based on the initial [MessageService]
 * interface definition in the architecture, but logically belongs in [ModelService].
 * A refactor to move it there in a future PR is advisable.
 */
class MessageServiceImpl(
    private val sessionDao: SessionDao, // Needed for updating session leaf ID
    private val messageDao: MessageDao,
    private val modelDao: ModelDao, // Injected for LLM context
    private val settingsDao: SettingsDao, // Injected for LLM context
    private val llmApiClient: LLMApiClient, // Injected for LLM calls (stubbed in S1)
    private val transactionScope: TransactionScope, // Injected
    private val credentialManager: CredentialManager // Injected for real LLM calls (Sprint 2+)
) : MessageService {

    override suspend fun getMessagesBySessionId(sessionId: Long): List<ChatMessage> {
        // This method just retrieves data, can use transactionScope for consistency/IO dispatching
        return transactionScope.transaction {
            messageDao.getMessagesBySessionId(sessionId)
        }
    }

    /**
     * Processes a new incoming user message (including replies).
     * Orchestrates saving messages, building context, calling LLM (stubbed in S1),
     * and updating threading links.
     */
    override suspend fun processNewMessage(sessionId: Long, content: String, parentMessageId: Long?): Either<ServiceError, List<ChatMessage>> {
        return transactionScope.transaction { // Ensure all database ops for one message pair are atomic
            // Use Arrow's Either.flatMap to chain operations and propagate errors

            // 1. Validate session exists and get details (needed for model/settings/leaf update)
            sessionDao.getSessionById(sessionId) // DAO returns Either<SessionNotFound, ChatSession>
                .mapLeft { daoError ->
                    when (daoError) {
                        is SessionError.SessionNotFound -> ServiceError.NotFound.Session(sessionId)
                        is SessionError.ForeignKeyViolation -> ServiceError.InvalidOperation("Unexpected foreign key issue fetching session") // Should not happen
                    }
                }
                .flatMap { session -> // Use flatMap to work with the 'session' value if found
                    // 2. Validate parent message exists if parentMessageId is provided
                    // Check parent if needed. If parentId is null, proceed.
                    val parentCheck: Either<ServiceError, Unit> = if (parentMessageId != null) {
                        messageDao.getMessageById(parentMessageId) // DAO returns Either<MessageNotFound, ChatMessage>
                            .mapLeft { daoError ->
                                when (daoError) {
                                    is MessageError.MessageNotFound -> ServiceError.NotFound.Message(parentMessageId)
                                    is MessageError.ForeignKeyViolation -> ServiceError.InvalidOperation("Unexpected foreign key issue fetching parent message") // Should not happen
                                    else -> ServiceError.DaoError.Message(daoError) // Map other DAO errors
                                }
                            }
                            .flatMap { Unit.right() } // If parent found, return Right(Unit)
                    } else {
                        Unit.right() // No parentId, so no check needed, continue
                    }

                    parentCheck.flatMap { // Continue chain if parent check succeeded
                        // 3. Save User Message
                        messageDao.insertUserMessage(
                            sessionId = sessionId,
                            content = content,
                            parentMessageId = parentMessageId
                        ) // DAO returns Either<ForeignKeyViolation, ChatMessage>
                            .mapLeft { daoError ->
                                when (daoError) {
                                    is MessageError.ForeignKeyViolation -> ServiceError.NotFound.Session(sessionId) // User message foreign key violation implies session not found
                                    else -> ServiceError.DaoError.Message(daoError) // Map other DAO errors
                                }
                            }
                            .flatMap { userMessage -> // Use flatMap to work with the 'userMessage' value
                                // 4. Update parent's children list IF parentMessageId exists
                                val parentUpdateCheck: Either<ServiceError, Unit> = if (parentMessageId != null) {
                                    messageDao.addChildToMessage(parentMessageId, userMessage.id) // DAO returns Either<MessageError, Unit>
                                        .mapLeft { daoError -> ServiceError.DaoError.Message(daoError) } // Map DAO error
                                } else {
                                    Unit.right() // No parentId, no parent update needed
                                }

                                parentUpdateCheck.flatMap { // Continue chain after parent update
                                    // 5. Build LLM Context (Thread-aware)
                                    // Fetch all messages for context building
                                    val allMessagesInSession = messageDao.getMessagesBySessionId(sessionId) // DAO returns List (assumes session exists)
                                    // Use the Consultant's suggested in-memory map approach to build the branch efficiently
                                    val messageMap = allMessagesInSession.associateBy { it.id }
                                    val contextMessages = mutableListOf<ChatMessage>()

                                    // --- SPRINT 1 STUBBED CONTEXT BUILDING LOGIC ---
                                    // Simplified logic for S1: Just send the new user message plus its direct parent if it exists.
                                    // This needs refinement in Sprint 2 (part of E1.S4 L).
                                    if (parentMessageId != null) {
                                         val parentMsg = messageMap[parentMessageId]
                                         if (parentMsg != null) {
                                             // Minimal context for stubbing: just the parent
                                             contextMessages.add(parentMsg)
                                         }
                                    }
                                    contextMessages.add(userMessage) // Add the new user message as the last message in context
                                    // --- END SPRINT 1 STUBBED CONTEXT BUILDING ---

                                    // 6. Retrieve Model and Settings (using selected IDs from session)
                                    val modelId = session.currentModelId ?: return@transaction Either.Left(ServiceError.ConfigurationError("No model selected for session ${session.id}"))
                                    val settingsId = session.currentSettingsId ?: return@transaction Either.Left(ServiceError.ConfigurationError("No settings profile selected for session ${session.id}"))

                                    // Fetch model and settings
                                    modelDao.getModelById(modelId) // DAO returns Either<ModelNotFound, LLMModel>
                                        .mapLeft { daoError -> when(daoError) { is ModelError.ModelNotFound -> ServiceError.NotFound.Model(modelId) } } // Map error
                                        .flatMap { model ->
                                            settingsDao.getSettingsById(settingsId) // DAO returns Either<SettingsNotFound, ModelSettings>
                                                .mapLeft { daoError -> when(daoError) { is SettingsError.SettingsNotFound -> ServiceError.NotFound.Settings(settingsId) } } // Map error
                                                .flatMap { settings ->

                                                    // 7. Retrieve API Key (Sprint 2+) - STUBBED FOR S1
                                                    // For Sprint 1, the CredentialManager call is here but stubbed
                                                    val apiKeyResult: Either<ServiceError, String> = model.apiKeyId?.let { alias ->
                                                        credentialManager.getCredential(alias) // Returns suspend Either<ApiSecretError, EncryptedSecret> from PR 3
                                                             // Need to call decryption service here
                                                             // Assuming CredentialManager.getCredential now returns String Directly for S1 stub purpose
                                                             // Let's refine CredentialManager interface later to return EncryptedSecret + inject DecryptionService here
                                                             .right() // Treat null from getCredential as error for S1 stub
                                                             .mapLeft { ServiceError.CredentialError("Failed to retrieve API key for model ${model.name}") }
                                                             .flatMap { key -> key?.right() ?: ServiceError.CredentialError("API key not found for model ${model.name} alias ${alias}").left() }
                                                             .getOrHandle { error ->
                                                                 logger.log(Level.SEVERE, "API key retrieval error for model ${model.name}: $error")
                                                                 return@transaction Either.Left(error) // Propagate error
                                                             }.right() // Wrap the retrieved key (or error)
                                                    } ?: ServiceError.ConfigurationError("Model ${model.name} has no API key configured").left() // Model requires key but apiKeyId is null

                                                     apiKeyResult.flatMap { apiKey -> // Continue chain with retrieved API key

                                                        // 8. Call LLM (STUBBED FOR SPRINT 1)
                                                        // LLMApiClientKtor is injected and its completeChat is suspend (stubbed impl in S1)
                                                        Either.catch { // Use Either.catch for potential exceptions from external service calls
                                                            llmApiClient.completeChat(
                                                                messages = contextMessages, // Pass the context (simple stub context in S1)
                                                                modelConfig = model,
                                                                settings = settings,
                                                                apiKey = apiKey // Use the retrieved (or stubbed) API key
                                                            )
                                                        }.mapLeft { e -> // Map any exception to ServiceError
                                                            logger.log(Level.SEVERE, "Exception during LLM API call", e)
                                                            ServiceError.ExternalServiceError(e.message ?: "Unknown LLM API error")
                                                        }
                                                        .flatMap { llmResponse -> // Continue chain with LLM response

                                                            // 9. Save Assistant Message
                                                            val assistantMessageContent = llmResponse.choices.firstOrNull()?.message?.content ?: "Error: No content in LLM response"
                                                            messageDao.insertAssistantMessage(
                                                                sessionId = sessionId,
                                                                content = assistantMessageContent,
                                                                parentMessageId = userMessage.id, // Assistant is a child of the user message it's responding to
                                                                modelId = model.id, // Save model/settings used
                                                                settingsId = settings.id
                                                            ) // DAO returns Either<ForeignKeyViolation, ChatMessage>
                                                                .mapLeft { daoError ->
                                                                    ServiceError.DaoError.Message(daoError) // Map DAO error
                                                                }
                                                                .flatMap { assistantMessage -> // Use flatMap to work with the assistant message

                                                                    // 10. Update User Message's children list
                                                                    messageDao.addChildToMessage(userMessage.id, assistantMessage.id) // DAO returns Either<MessageError, Unit>
                                                                        .mapLeft { daoError -> ServiceError.DaoError.Message(daoError) } // Map DAO error
                                                                        .flatMap {
                                                                            // 11. Update Session's currentLeafMessageId
                                                                            // We need to update the session record.
                                                                            sessionDao.updateSessionLeafMessageId(sessionId, assistantMessage.id) // DAO returns Either<SessionError, Unit>
                                                                                .mapLeft { daoError -> ServiceError.DaoError.Session(daoError) } // Map DAO error
                                                                                .flatMap {
                                                                                    // 12. Return User and Assistant Messages
                                                                                    listOf(userMessage, assistantMessage).right() // Return success with the list of messages
                                                                                }
                                                                        }
                                                                }
                                                        }
                                                    }
                                                }
                                        }
                                }
                            }
                    }
                } // End flatMap chain
        } // Transaction ends here (commit on success, rollback on exception)
    }


    override suspend fun updateMessageContent(id: Long, content: String): Either<ServiceError.NotFound.Message, ChatMessage> {
        if (content.isBlank()) {
             logger.warning("Attempted to update message $id with blank content")
             return ServiceError.InvalidOperation("Message content cannot be blank").left().flatMap { it.left() } // Arrow requires Left type to match
        }
        logger.fine("Attempting to update message content for ID: $id")
        return transactionScope.transaction {
             messageDao.updateMessageContent(id, content) // DAO returns Either<MessageNotFound, ChatMessage>
                 .mapLeft { daoError -> when(daoError) { is MessageError.MessageNotFound -> Message(id) } } // Map error
        }
    }

    override suspend fun deleteMessage(id: Long): Either<ServiceError.NotFound.Message, Unit> {
        logger.info("Attempting to delete message ID: $id")
         return transactionScope.transaction { // Atomic deletion (recursive handled in DAO)
            // The DAO handles recursive deletion and updating the parent's children list within its call.
            messageDao.deleteMessage(id) // DAO returns Either<MessageNotFound, Unit>
                 .mapLeft { daoError -> when(daoError) { is MessageError.MessageNotFound -> Message(id) } } // Map error
         }
    }

    // This method is here based on the interface definition, but logically belongs in ModelService.
    override suspend fun isApiKeyConfiguredForModel(modelId: Long): Boolean {
        logger.fine("Checking if API key is configured for model ID: $modelId")
        // This is a simple read operation, no transaction needed *unless* it's part of a larger unit of work.
        // For a simple check, we can potentially bypass TransactionScope if the DAO call is already on IO dispatcher.
        // However, using TransactionScope provides consistency and ensures IO dispatching.
        // The DAO call itself returns Either, but this Service method is specified to return Boolean.
        // We map the DAO result to a boolean.
        return transactionScope.transaction {
             modelDao.getModelById(modelId) // DAO returns Either<ModelError.ModelNotFound, LLMModel>
                 .map { it.apiKeyId != null } // Map Right to boolean
                 .getOrHandle { false } // Handle Left (NotFound or other error) by returning false
        }
    }
}
```

```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/external/llm/LLMApiClient.kt
package eu.torvian.chatbot.server.external.llm

import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.common.models.ModelSettings
import eu.torvian.chatbot.server.external.models.OpenAiApiModels.* // Use external DTOs

/**
 * Interface for interacting with external LLM APIs (OpenAI-compatible).
 *
 * All methods are suspending as they involve network I/O.
 */
interface LLMApiClient {
    /**
     * Sends a chat completion request to the LLM API.
     *
     * @param messages The list of messages forming the conversation context (thread-aware, built by Service).
     * @param modelConfig Configuration details for the target LLM endpoint.
     * @param settings Specific settings profile to use for this completion request.
     * @param apiKey The decrypted API key for authentication.
     * @return The response from the LLM API, including the assistant's message.
     * @throws Exception If the API call fails (e.g., network error, API error response).
     */
    suspend fun completeChat(
        messages: List<ChatMessage>, // History + current user message. Service builds this list considering threads.
        modelConfig: LLMModel,      // Base URL, Type (for endpoint structure)
        settings: ModelSettings,    // Temperature, System Message, etc.
        apiKey: String              // The _decrypted_ API key
    ): ChatCompletionResponse // Using a DTO from external.models
}
```

```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/external/llm/LLMApiClientKtor.kt
package eu.torvian.chatbot.server.external.llm

import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.common.models.ModelSettings
import eu.torvian.chatbot.server.external.models.OpenAiApiModels.* // Use external DTOs
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.datetime.Clock
import kotlinx.coroutines.delay
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

private val logger = Logger.getLogger(LLMApiClientKtor::class.java.name)

/**
 * Ktor implementation of the [LLMApiClient] for OpenAI-compatible endpoints.
 *
 * NOTE: For Sprint 1, this implementation is **STUBBED** to return a canned response
 * without making an actual network call. The real HTTP request logic will be added in Sprint 2.
 */
class LLMApiClientKtor(private val httpClient: HttpClient) : LLMApiClient {

    override suspend fun completeChat(
        messages: List<ChatMessage>,
        modelConfig: LLMModel,
        settings: ModelSettings,
        apiKey: String
    ): ChatCompletionResponse {
        logger.info("LLMApiClientKtor (STUBBED): Received request for model ${modelConfig.name} with settings ${settings.name}")
        logger.info("LLMApiClientKtor (STUBBED): Context messages received: ${messages.size}")
        messages.forEachIndexed { index, msg ->
            logger.fine("LLMApiClientKtor (STUBBED) Context[$index]: ${msg.role}: ${msg.content.take(50)}...")
        }

        // --- STUBBED IMPLEMENTATION FOR SPRINT 1 ---
        // Simulate latency
        delay(1000) // Simulate network delay

        // Construct a fake, canned response based on the input
        val fakeMessageContent = "This is a stubbed assistant response from ${modelConfig.name}. " +
                                  "You just sent: \"${messages.lastOrNull()?.content?.take(100) ?: "nothing?"}...\" " +
                                  "Parent ID sent by client: ${messages.lastOrNull()?.parentMessageId}. " +
                                  "Model: ${modelConfig.name}, Settings: ${settings.name}."


        val choice = ChatCompletionResponse.Choice(
            index = 0,
            message = ChatCompletionResponse.Choice.Message(
                role = "assistant",
                content = fakeMessageContent,
                tool_calls = null // V1.1 doesn't support Tool Calling yet
            ),
            finish_reason = "stop"
        )

        val usage = ChatCompletionResponse.Usage(
            prompt_tokens = messages.sumOf { it.content.length / 4 + 1 } + 10, // Rough token estimate + overhead
            completion_tokens = fakeMessageContent.length / 4 + 10,
            total_tokens = (messages.sumOf { it.content.length / 4 + 1 } + 10) + (fakeMessageContent.length / 4 + 10)
        )

        val response = ChatCompletionResponse(
            id = "stubbed-chatcmpl-${UUID.randomUUID()}",
            `object` = "chat.completion",
            created = Clock.System.now().epochSeconds,
            model = modelConfig.name,
            choices = listOf(choice),
            usage = usage
        )

        logger.info("LLMApiClientKtor (STUBBED): Returning fake response.")
        return response

        // --- REAL IMPLEMENTATION FOR SPRINT 2+ ---
        /*
        val endpoint = "${modelConfig.baseUrl}/v1/chat/completions" // Assuming OpenAI compatible path
        val apiMessages = messages.map { // Need mapping function ChatMessage -> OpenAiApiModels.ChatCompletionRequest.Message
            OpenAiApiModels.ChatCompletionRequest.Message(role = it.role.name.lowercase(), content = it.content)
        }
        val requestBody = ChatCompletionRequest(
            model = modelConfig.name,
            messages = apiMessages,
            temperature = settings.temperature,
            max_tokens = settings.maxTokens,
            // ... map other settings parameters ...
            // customParamsJson also needs parsing and mapping if needed by the API
        )

        val response = httpClient.post {
            url(endpoint)
            contentType(ContentType.Application.Json)
            headers {
                append("Authorization", "Bearer $apiKey")
            }
            setBody(requestBody)
        }

        if (response.status.isSuccess()) {
            return response.body<ChatCompletionResponse>()
        } else {
            val errorBody = response.body<String>() // Or map to an error DTO if API provides one
            logger.log(Level.SEVERE, "LLM API call failed: ${response.status}. Body: $errorBody")
            throw RuntimeException("LLM API error: ${response.status} - $errorBody") // Propagate error as exception for Service layer to catch
        }
        */
    }
}
```

```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/external/security/CredentialManager.kt
package eu.torvian.chatbot.server.external.security

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.ApiSecretError
import eu.torvian.chatbot.server.domain.security.EncryptedSecret // Assuming EncryptedSecret DTO exists

/**
 * Interface for managing sensitive credentials using a secure storage mechanism.
 * The implementation should handle encryption/decryption and storage/retrieval.
 *
 * All methods are suspending as they involve I/O or potentially blocking operations (OS interaction).
 */
interface CredentialManager {
    /**
     * Stores a credential securely.
     *
     * This method handles the encryption of the raw credential string and persists it
     * using an internal storage mechanism (e.g., database, OS credential manager).
     *
     * @param credential The sensitive string (e.g., API key) to store.
     * @return Either a [ApiSecretError.SecretAlreadyExists] if a secret with the same alias already exists (shouldn't happen with generated UUIDs, but included for robustness),
     *         or the generated UUID alias/reference ID that can be used to retrieve the credential.
     */
    suspend fun storeCredential(credential: String): Either<ApiSecretError, String>

    /**
     * Retrieves a securely stored credential using its alias/reference ID.
     *
     * This method handles the retrieval of encrypted data and its decryption.
     *
     * @param alias The alias/reference ID returned by [storeCredential].
     * @return Either a [ApiSecretError.SecretNotFound] if not found, or the decrypted credential string.
     */
    suspend fun getCredential(alias: String): Either<ApiSecretError.SecretNotFound, String>

    /**
     * Deletes a securely stored credential using its alias/reference ID.
     *
     * @param alias The alias/reference ID of the credential to delete.
     * @return Either a [ApiSecretError.SecretNotFound] if not found, or Unit if deletion was successful (or credential wasn't found).
     */
    suspend fun deleteCredential(alias: String): Either<ApiSecretError.SecretNotFound, Unit>
}
```

```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/api/server/ApiRoutes.kt
package eu.torvian.chatbot.server.api.server

import arrow.core.Either
import eu.torvian.chatbot.common.models.* // Common DTOs
import eu.torvian.chatbot.server.service.* // Service Interfaces
import eu.torvian.chatbot.server.service.error.ServiceError // Service error types
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.logging.Level
import java.util.logging.Logger

private val logger = Logger.getLogger("ApiRoutes")

/**
 * Configures the Ktor server routing for the application API (v1).
 *
 * @param sessionService The injected SessionService instance.
 * @param messageService The injected MessageService instance.
 * @param modelService The injected ModelService instance.
 * @param groupService The injected GroupService instance.
 */
fun Application.configureRouting(
    sessionService: SessionService,
    messageService: MessageService, // Use MessageService interface
    modelService: ModelService,
    groupService: GroupService
) {
    routing {
        route("/api/v1") {

            // --- Session Routes (/sessions) ---
            route("/sessions") {
                // GET /api/v1/sessions (E2.S3)
                get {
                    try {
                        val sessions = sessionService.getAllSessionsSummaries() // Calls suspend service
                        call.respond(HttpStatusCode.OK, sessions)
                    } catch (e: Exception) { // Catch unexpected exceptions
                        logger.log(Level.SEVERE, "Error getting sessions", e)
                        call.respond(HttpStatusCode.InternalServerError, "Failed to retrieve sessions: ${e.message}")
                    }
                }

                // POST /api/v1/sessions (E2.S1)
                post {
                    try {
                        val request = call.receive<CreateSessionRequest>()
                        sessionService.createSession(request.name) // Calls suspend service
                            .fold( // Handle Either result
                                { error -> mapServiceErrorToHttpResponse(error, call) }, // Map ServiceError to HTTP response
                                { session -> call.respond(HttpStatusCode.Created, session) } // Respond with session on success
                            )
                    } catch (e: Exception) { // Catch unexpected exceptions (e.g., Ktor receive exception)
                        logger.log(Level.SEVERE, "Error receiving or processing create session request", e)
                        call.respond(HttpStatusCode.InternalServerError, "Failed to process create session request: ${e.message}")
                    }
                }

                route("/{sessionId}") {
                    // GET /api/v1/sessions/{sessionId} (E2.S4)
                    get {
                        val sessionId = call.parameters["sessionId"]?.toLongOrNull()
                            ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid session ID format")
                        try {
                             sessionService.getSessionDetails(sessionId) // Calls suspend service
                                 .fold( // Handle Either result
                                     { error -> mapServiceErrorToHttpResponse(error, call) }, // Map ServiceError
                                     { session -> call.respond(HttpStatusCode.OK, session) } // Respond with session
                                 )
                        } catch (e: Exception) { // Catch unexpected exceptions
                             logger.log(Level.SEVERE, "Error getting session $sessionId details", e)
                             call.respond(HttpStatusCode.InternalServerError, "Failed to retrieve session details: ${e.message}")
                        }
                    }

                    // PUT /api/v1/sessions/{sessionId} (E2.S5, E4.S7, E1.S4 leaf update)
                    put {
                         val sessionId = call.parameters["sessionId"]?.toLongOrNull()
                             ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid session ID format")
                         try {
                             val request = call.receive<UpdateSessionRequest>() // Use UpdateSessionRequest DTO
                             // Need to fetch session, apply updates from request, and call service.updateSessionDetails
                             // This endpoint's full implementation will come later.
                             call.respond(HttpStatusCode.NotImplemented) // Placeholder
                         } catch (e: Exception) {
                            logger.log(Level.SEVERE, "Error processing update session $sessionId request", e)
                             call.respond(HttpStatusCode.InternalServerError, "Failed to process update session request: ${e.message}")
                         }
                    }

                    // DELETE /api/v1/sessions/{sessionId} (E2.S6)
                    delete {
                         val sessionId = call.parameters["sessionId"]?.toLongOrNull()
                             ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid session ID format")
                         try {
                              sessionService.deleteSession(sessionId) // Calls suspend service
                                  .fold( // Handle Either result
                                      { error -> mapServiceErrorToHttpResponse(error, call) }, // Map ServiceError
                                      { call.respond(HttpStatusCode.NoContent) } // 204 No Content on success
                                  )
                         }
                         catch (e: Exception) { // Catch unexpected exceptions
                              logger.log(Level.SEVERE, "Error deleting session $sessionId", e)
                             call.respond(HttpStatusCode.InternalServerError, "Failed to delete session: ${e.message}")
                         }
                    }

                    // PUT /api/v1/sessions/{sessionId}/group (E6.S1)
                    put("/group") {
                        val sessionId = call.parameters["sessionId"]?.toLongOrNull()
                            ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid session ID format")
                        try {
                            val request = call.receive<AssignSessionToGroupRequest>() // Request body contains groupId (nullable)
                            sessionService.assignSessionToGroup(sessionId, request.groupId) // Calls suspend service
                                .fold( // Handle Either result
                                     { error -> mapServiceErrorToHttpResponse(error, call) }, // Map ServiceError
                                     { updatedSummary -> call.respond(HttpStatusCode.OK, updatedSummary) } // Respond with updated summary
                                )
                        } catch (e: Exception) { // Catch unexpected exceptions
                            logger.log(Level.SEVERE, "Error assigning session $sessionId to group", e)
                           call.respond(HttpStatusCode.InternalServerError, "Failed to assign session to group: ${e.message}")
                        }
                    }
                }
            } // End /sessions routes

            // --- Message Routes (nested under sessions or separate) ---
            // Defined under session for clarity in OpenAPI, but logic operates on messageId
            route("/sessions/{sessionId}/messages") {
                 // POST /api/v1/sessions/{sessionId}/messages (E1.S1 backend, E1.S4 Sprint 1 scope)
                 post {
                     val sessionId = call.parameters["sessionId"]?.toLongOrNull()
                         ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid session ID format")
                     try {
                         val request = call.receive<ProcessNewMessageRequest>()
                         messageService.processNewMessage(sessionId, request.content, request.parentMessageId) // Calls suspend message service
                             .fold( // Handle Either result
                                 { error -> mapServiceErrorToHttpResponse(error, call) }, // Map ServiceError (includes LLM errors)
                                 { messages -> call.respond(HttpStatusCode.OK, messages) } // Returns list [userMsg, assistantMsg]
                             )
                     } catch (e: Exception) { // Catch unexpected exceptions
                         logger.log(Level.SEVERE, "Error processing new message in session $sessionId", e)
                         call.respond(HttpStatusCode.InternalServerError, e.message ?: "Failed to process message")
                     }
                 }
            } // End /sessions/{sessionId}/messages route

            // --- Message Management Routes (/messages) ---
            route("/messages") {
                 route("/{messageId}") {
                     // PUT /api/v1/messages/{messageId} (E3.S3)
                     put {
                         val messageId = call.parameters["messageId"]?.toLongOrNull()
                            ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid message ID format")
                         try {
                             val request = call.receive<UpdateMessageRequest>() // Use UpdateMessageRequest DTO
                             messageService.updateMessageContent(messageId, request.content) // Calls suspend message service
                                 .fold( // Handle Either result
                                     { error -> mapServiceErrorToHttpResponse(error, call) }, // Map ServiceError
                                     { message -> call.respond(HttpStatusCode.OK, message) } // Respond with updated message
                                 )
                         } catch (e: Exception) {
                            logger.log(Level.SEVERE, "Error processing update message $messageId request", e)
                             call.respond(HttpStatusCode.InternalServerError, "Failed to process update message request: ${e.message}")
                         }
                     }

                     // DELETE /api/v1/messages/{messageId} (E3.S4)
                     delete {
                          val messageId = call.parameters["messageId"]?.toLongOrNull()
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid message ID format")
                          try {
                              messageService.deleteMessage(messageId) // Calls suspend message service (recursive delete)
                                  .fold( // Handle Either result
                                      { error -> mapServiceErrorToHttpResponse(error, call) }, // Map ServiceError
                                      { call.respond(HttpStatusCode.NoContent) } // 204 No Content on successful deletion
                                  )
                          }
                          catch (e: Exception) { // Catch unexpected exceptions
                              logger.log(Level.SEVERE, "Error deleting message $messageId", e)
                             call.respond(HttpStatusCode.InternalServerError, "Failed to delete message: ${e.message}")
                          }
                     }
                 }
            } // End /messages routes


            // --- Model Routes (/models) ---
            route("/models") {
                // GET /api/v1/models (E4.S2)
                get {
                    try {
                        val models = modelService.getAllModels() // Calls suspend service
                        call.respond(HttpStatusCode.OK, models)
                    } catch (e: Exception) {
                        logger.log(Level.SEVERE, "Error getting models", e)
                        call.respond(HttpStatusCode.InternalServerError, "Failed to retrieve models: ${e.message}")
                    }
                }

                // POST /api/v1/models (E4.S1 backend - part involves secure key storage)
                post {
                    try {
                         val request = call.receive<AddModelRequest>() // Use AddModelRequest DTO
                         modelService.addModel(request.name, request.baseUrl, request.type, request.apiKey) // Calls suspend service
                              .fold( // Handle Either result
                                  { error -> mapServiceErrorToHttpResponse(error, call) }, // Map ServiceError (includes credential errors)
                                  { model -> call.respond(HttpStatusCode.Created, model) } // Respond with model (without raw key)
                              )
                    } catch (e: Exception) { // Catch unexpected exceptions
                        logger.log(Level.SEVERE, "Error processing add model request", e)
                        call.respond(HttpStatusCode.InternalServerError, "Failed to process add model request: ${e.message}")
                    }
                }

                route("/{modelId}") {
                    // PUT /api/v1/models/{modelId} (E4.S3 backend - part involves secure key update)
                    put {
                         val modelId = call.parameters["modelId"]?.toLongOrNull()
                            ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid model ID format")
                         try {
                             val request = call.receive<UpdateModelRequest>() // Use UpdateModelRequest DTO
                             // Ensure ID in request body matches path param for safety/consistency
                             if (request.id != modelId) {
                                 return@put call.respond(HttpStatusCode.BadRequest, "Model ID in path and body must match")
                             }
                             modelService.updateModel(request.id, request.name, request.baseUrl, request.type, request.apiKey) // Calls suspend service
                                  .fold( // Handle Either result
                                      { error -> mapServiceErrorToHttpResponse(error, call) }, // Map ServiceError
                                      { model -> call.respond(HttpStatusCode.OK, model) } // Respond with updated model
                                  )
                         } catch (e: Exception) {
                            logger.log(Level.SEVERE, "Error processing update model $modelId request", e)
                             call.respond(HttpStatusCode.InternalServerError, "Failed to process update model request: ${e.message}")
                         }
                    }
                    // DELETE /api/v1/models/{modelId} (E4.S4 backend - part involves secure key deletion)
                    delete {
                         val modelId = call.parameters["modelId"]?.toLongOrNull()
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid model ID format")
                         try {
                              modelService.deleteModel(modelId) // Calls suspend service
                                  .fold( // Handle Either result
                                      { error -> mapServiceErrorToHttpResponse(error, call) }, // Map ServiceError (includes credential errors)
                                      { call.respond(HttpStatusCode.NoContent) } // 204 No Content
                                  )
                         }
                         catch (e: Exception) { // Catch unexpected exceptions
                              logger.log(Level.SEVERE, "Error deleting model $modelId", e)
                             call.respond(HttpStatusCode.InternalServerError, "Failed to delete model: ${e.message}")
                         }
                    }

                    // GET /api/v1/models/{modelId}/apikey/status (E5.S4)
                    get("/apikey/status") {
                        val modelId = call.parameters["modelId"]?.toLongOrNull()
                            ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid model ID format")
                        try {
                            // Note: isApiKeyConfiguredForModel is in MessageService in architecture, but logically ModelService is better.
                            // Using messageService as per current architecture definition for V1.1, but flagging this for refactor.
                            val isConfigured = messageService.isApiKeyConfiguredForModel(modelId) // Calls suspend service
                            call.respond(HttpStatusCode.OK, ApiKeyStatusResponse(isConfigured))
                        } catch (e: Exception) {
                            logger.log(Level.SEVERE, "Error checking API key status for model $modelId", e)
                           call.respond(HttpStatusCode.InternalServerError, "Failed to check API key status: ${e.message}")
                        }
                    }
                }
            } // End /models routes

            // --- Settings Routes (/settings) ---
            route("/settings") {
                 route("/{settingsId}") {
                     // GET /api/v1/settings/{settingsId}
                     get {
                         val settingsId = call.parameters["settingsId"]?.toLongOrNull()
                            ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid settings ID format")
                         try {
                              modelService.getSettingsById(settingsId) // Calls suspend service
                                   .fold( // Handle Either result
                                       { error -> mapServiceErrorToHttpResponse(error, call) }, // Map ServiceError
                                       { settings -> call.respond(HttpStatusCode.OK, settings) } // Respond with settings
                                   )
                         } catch (e: Exception) {
                            logger.log(Level.SEVERE, "Error getting settings $settingsId details", e)
                             call.respond(HttpStatusCode.InternalServerError, "Failed to retrieve settings details: ${e.message}")
                         }
                     }
                     // PUT /api/v1/settings/{settingsId} (E4.S6)
                     put {
                         val settingsId = call.parameters["settingsId"]?.toLongOrNull()
                            ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid settings ID format")
                         try {
                             val request = call.receive<UpdateSettingsRequest>() // Use UpdateSettingsRequest DTO
                             // Ensure ID in request body matches path param
                              if (request.id != settingsId) {
                                  return@put call.respond(HttpStatusCode.BadRequest, "Settings ID in path and body must match")
                              }
                             modelService.updateSettings(request.id, request.name, request.systemMessage, request.temperature, request.maxTokens, request.customParamsJson) // Calls suspend service
                                  .fold( // Handle Either result
                                      { error -> mapServiceErrorToHttpResponse(error, call) }, // Map ServiceError
                                      { call.respond(HttpStatusCode.OK) } // Respond with 200 OK on success (no body for PUT update)
                                  )
                         } catch (e: Exception) {
                            logger.log(Level.SEVERE, "Error processing update settings $settingsId request", e)
                             call.respond(HttpStatusCode.InternalServerError, "Failed to process update settings request: ${e.message}")
                         }
                     }
                     // DELETE /api/v1/settings/{settingsId} (E4.S5)
                     delete {
                         val settingsId = call.parameters["settingsId"]?.toLongOrNull()
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid settings ID format")
                         try {
                              modelService.deleteSettings(settingsId) // Calls suspend service
                                  .fold( // Handle Either result
                                      { error -> mapServiceErrorToHttpResponse(error, call) }, // Map ServiceError
                                      { call.respond(HttpStatusCode.NoContent) } // 204 No Content
                                  )
                         }
                         catch (e: Exception) { // Catch unexpected exceptions
                              logger.log(Level.SEVERE, "Error deleting settings $settingsId", e)
                             call.respond(HttpStatusCode.InternalServerError, "Failed to delete settings: ${e.message}")
                         }
                     }
                 }
             } // End /settings routes

             // --- Settings under Models Routes ---
             route("/models/{modelId}/settings") {
                 // POST /api/v1/models/{modelId}/settings (E4.S5)
                 post {
                      val modelId = call.parameters["modelId"]?.toLongOrNull()
                         ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid model ID format")
                      try {
                          val request = call.receive<AddModelSettingsRequest>() // Use AddModelSettingsRequest DTO
                          modelService.addSettings(modelId, request.name, request.systemMessage, request.temperature, request.maxTokens, request.customParamsJson) // Calls suspend service
                              .fold( // Handle Either result
                                  { error -> mapServiceErrorToHttpResponse(error, call) }, // Map ServiceError
                                  { settings -> call.respond(HttpStatusCode.Created, settings) } // Respond with new settings
                              )
                      } catch (e: Exception) {
                         logger.log(Level.SEVERE, "Error processing add settings for model $modelId request", e)
                         call.respond(HttpStatusCode.InternalServerError, "Failed to process add settings request: ${e.message}")
                     }
                 }
             } // End settings under models


            // --- Group Routes (/groups) ---
            route("/groups") {
                // GET /api/v1/groups (E6.S4)
                get {
                    try {
                        val groups = groupService.getAllGroups() // Calls suspend service
                        call.respond(HttpStatusCode.OK, groups)
                    } catch (e: Exception) { // Catch unexpected exceptions
                        logger.log(Level.SEVERE, "Error getting groups", e)
                        call.respond(HttpStatusCode.InternalServerError, "Failed to retrieve groups: ${e.message}")
                    }
                }

                // POST /api/v1/groups (E6.S3)
                post {
                    try {
                        val request = call.receive<CreateGroupRequest>()
                        groupService.createGroup(request.name) // Calls suspend service
                            .fold( // Handle Either result
                                { error -> mapServiceErrorToHttpResponse(error, call) }, // Map ServiceError
                                { group -> call.respond(HttpStatusCode.Created, group) } // Respond with new group
                            )
                    } catch (e: Exception) { // Catch unexpected exceptions
                        logger.log(Level.SEVERE, "Error processing create group request", e)
                        call.respond(HttpStatusCode.InternalServerError, "Failed to process create group request: ${e.message}")
                    }
                }

                route("/{groupId}") {
                    // PUT /api/v1/groups/{groupId} (E6.S5)
                    put {
                        val groupId = call.parameters["groupId"]?.toLongOrNull()
                            ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid group ID format")
                         try {
                             val request = call.receive<RenameGroupRequest>() // Use RenameGroupRequest DTO
                             groupService.renameGroup(groupId, request.name) // Calls suspend service
                                  .fold( // Handle Either result
                                       { error -> mapServiceErrorToHttpResponse(error, call) }, // Map ServiceError
                                       { call.respond(HttpStatusCode.OK) } // Respond with 200 OK on success (no body for PUT update)
                                  )
                         } catch (e: Exception) {
                            logger.log(Level.SEVERE, "Error processing rename group $groupId request", e)
                             call.respond(HttpStatusCode.InternalServerError, "Failed to process rename group request: ${e.message}")
                         }
                    }

                    // DELETE /api/v1/groups/{groupId} (E6.S6 backend - handles session ungrouping)
                    delete {
                        val groupId = call.parameters["groupId"]?.toLongOrNull()
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid group ID format")
                        try {
                             groupService.deleteGroup(groupId) // Calls suspend service
                                 .fold( // Handle Either result
                                     { error -> mapServiceErrorToHttpResponse(error, call) }, // Map ServiceError
                                     { call.respond(HttpStatusCode.NoContent) } // 204 No Content
                                 )
                        }
                        catch (e: Exception) { // Catch unexpected exceptions
                            logger.log(Level.SEVERE, "Error deleting group $groupId", e)
                           call.respond(HttpStatusCode.InternalServerError, "Failed to delete group: ${e.message}")
                        }
                    }
                }
            } // End /groups routes

        } // End /api/v1 routes
    }
}

/**
 * Helper function to map a [ServiceError] to an appropriate [HttpStatusCode] and response body.
 *
 * @param error The service error to map.
 * @param call The Ktor [ApplicationCall] to respond to.
 */
private suspend fun mapServiceErrorToHttpResponse(error: ServiceError, call: ApplicationCall) {
    logger.log(Level.WARNING, "Mapping service error to HTTP response: $error")
    when (error) {
        is ServiceError.NotFound -> {
            val entityType = when(error) {
                is ServiceError.NotFound.Session -> "Session"
                is ServiceError.NotFound.Message -> "Message"
                is ServiceError.NotFound.Group -> "Group"
                is ServiceError.NotFound.Model -> "Model"
                is ServiceError.NotFound.Settings -> "Settings"
                is ServiceError.NotFound.ApiSecret -> "Api Secret"
            }
            val entityId = when(error) {
                is ServiceError.NotFound.Session -> error.id
                is ServiceError.NotFound.Message -> error.id
                is ServiceError.NotFound.Group -> error.id
                is ServiceError.NotFound.Model -> error.id
                is ServiceError.NotFound.Settings -> error.id
                is ServiceError.NotFound.ApiSecret -> error.alias
            }
            call.respond(HttpStatusCode.NotFound, "$entityType with ID $entityId not found.")
        }
        is ServiceError.InvalidOperation -> call.respond(HttpStatusCode.BadRequest, error.message)
        is ServiceError.ConfigurationError -> call.respond(HttpStatusCode.BadRequest, error.message) // 400 for client-side config issue
        is ServiceError.CredentialError -> call.respond(HttpStatusCode.InternalServerError, "Credential error: ${error.message}") // 500 for server-side credential issue
        is ServiceError.ExternalServiceError -> call.respond(HttpStatusCode.InternalServerError, "External service error: ${error.message}") // 500 for LLM API error etc.
        is ServiceError.DaoError -> {
            // Map specific DAO errors if needed, otherwise default to 500
            val errorMessage = when(error.error) {
                 is SessionError.ForeignKeyViolation -> "Related entity not found for session operation"
                 is MessageError.ForeignKeyViolation -> "Related entity not found for message operation"
                 else -> "Database error" // Generic for other DAO errors not explicitly handled
            }
            // For simplicity in V1.1, map most DAO errors to 500, specific FkViolation might be 400/404
            // Let's map ForeignKeyViolation to 400, others to 500 for now.
            when (error.error) {
                 is SessionError.ForeignKeyViolation, is MessageError.ForeignKeyViolation -> call.respond(HttpStatusCode.BadRequest, errorMessage)
                 else -> call.respond(HttpStatusCode.InternalServerError, "Database error: ${error.error}") // Avoid exposing raw DB error messages
            }

        }
    }
}
```

This concludes the code for PR 5. Alex will implement these Services, update the Ktor routes, ensure correct DI wiring with the DAOs (from PR 4), TransactionScope (from PR 2), LLMClient/CredentialManager (from PR 5 interfaces, implementations in PR 5 stub and PR 3), and submit the PR.
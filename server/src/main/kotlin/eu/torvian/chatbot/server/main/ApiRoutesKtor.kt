package eu.torvian.chatbot.server.main

import arrow.core.Either
import eu.torvian.chatbot.common.api.*
import eu.torvian.chatbot.common.api.resources.*
import eu.torvian.chatbot.common.models.*
import eu.torvian.chatbot.server.service.core.*
import eu.torvian.chatbot.server.service.core.error.group.*
import eu.torvian.chatbot.server.service.core.error.message.*
import eu.torvian.chatbot.server.service.core.error.model.*
import eu.torvian.chatbot.server.service.core.error.provider.*
import eu.torvian.chatbot.server.service.core.error.session.*
import eu.torvian.chatbot.server.service.core.error.settings.*
import io.ktor.http.*
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing

/**
 * Ktor route configuration using type-safe Resources plugin.
 * Implements the ApiRoutes interface and uses injected dependencies.
 */
class ApiRoutesKtor(
    private val application: Application,
    private val sessionService: SessionService,
    private val groupService: GroupService,
    private val llmProviderService: LLMProviderService,
    private val llmModelService: LLMModelService,
    private val modelSettingsService: ModelSettingsService,
    private val messageService: MessageService
) : ApiRoutes {
    /**
     * Configures the API routes using the Ktor Resources plugin.
     * Gets the root Route from the Application instance and defines routes within it.
     */
    override fun configureRouting() {
        application.routing {
            configureSessionRoutes()
            configureGroupRoutes()
            configureProviderRoutes()
            configureModelRoutes()
            configureSettingsRoutes()
            configureMessageRoutes()
        }
    }

    /**
     * Configures routes related to Sessions (/api/v1/sessions).
     */
    private fun Route.configureSessionRoutes() {
        // GET /api/v1/sessions - List all sessions
        get<SessionsResource> {
            call.respond(sessionService.getAllSessionsSummaries())
        }

        // POST /api/v1/sessions - Create a new session
        post<SessionsResource> {
            val request = call.receive<CreateSessionRequest>()
            call.respondEither(
                sessionService.createSession(request.name),
                HttpStatusCode.Created
            ) { error ->
                when (error) {
                    is CreateSessionError.InvalidName ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid session name provided", "reason" to error.reason)
                    is CreateSessionError.InvalidRelatedEntity ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid related entity ID provided", "details" to error.message)
                }
            }
        }

        // GET /api/v1/sessions/{sessionId} - Get session by ID
        get<SessionsResource.ById> { resource ->
            val sessionId = resource.sessionId
            call.respondEither(sessionService.getSessionDetails(sessionId)) { error ->
                when (error) {
                    is GetSessionDetailsError.SessionNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found", "sessionId" to error.id.toString())
                }
            }
        }

        // DELETE /api/v1/sessions/{sessionId} - Delete session by ID
        delete<SessionsResource.ById> { resource ->
            val sessionId = resource.sessionId
            call.respondEither(
                sessionService.deleteSession(sessionId),
                HttpStatusCode.NoContent
            ) { error ->
                when (error) {
                    is DeleteSessionError.SessionNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found", "sessionId" to error.id.toString())
                }
            }
        }

        // --- Granular PUT routes using nested resources ---

        // PUT /api/v1/sessions/{sessionId}/name - Update the name of a session
        put<SessionsResource.ById.Name> { resource ->
            val sessionId = resource.parent.sessionId
            val request = call.receive<UpdateSessionNameRequest>()
            call.respondEither(sessionService.updateSessionName(sessionId, request.name)) { error ->
                when (error) {
                    is UpdateSessionNameError.SessionNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found", "sessionId" to error.id.toString())
                    is UpdateSessionNameError.InvalidName ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid session name provided", "reason" to error.reason)
                }
            }
        }

        // PUT /api/v1/sessions/{sessionId}/model - Update the current model ID of a session
        put<SessionsResource.ById.Model> { resource ->
            val sessionId = resource.parent.sessionId
            val request = call.receive<UpdateSessionModelRequest>()
            call.respondEither(
                sessionService.updateSessionCurrentModelId(sessionId, request.modelId)
            ) { error ->
                when (error) {
                    is UpdateSessionCurrentModelIdError.SessionNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found", "sessionId" to error.id.toString())
                    is UpdateSessionCurrentModelIdError.InvalidRelatedEntity ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid model ID provided", "modelId" to request.modelId.toString())
                }
            }
        }

        // PUT /api/v1/sessions/{sessionId}/settings - Update the current settings ID of a session
        put<SessionsResource.ById.Settings> { resource ->
            val sessionId = resource.parent.sessionId
            val request = call.receive<UpdateSessionSettingsRequest>()
            call.respondEither(
                sessionService.updateSessionCurrentSettingsId(sessionId, request.settingsId)
            ) { error ->
                when (error) {
                    is UpdateSessionCurrentSettingsIdError.SessionNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found", "sessionId" to error.id.toString())
                    is UpdateSessionCurrentSettingsIdError.InvalidRelatedEntity ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid settings ID provided", "settingsId" to request.settingsId.toString())
                }
            }
        }

        // PUT /api/v1/sessions/{sessionId}/leafMessage - Update the current leaf message ID of a session
        put<SessionsResource.ById.LeafMessage> { resource ->
            val sessionId = resource.parent.sessionId
            val request = call.receive<UpdateSessionLeafMessageRequest>()
            call.respondEither(
                sessionService.updateSessionLeafMessageId(sessionId, request.leafMessageId)
            ) { error ->
                when (error) {
                    is UpdateSessionLeafMessageIdError.SessionNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found", "sessionId" to error.id.toString())
                    is UpdateSessionLeafMessageIdError.InvalidRelatedEntity ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid leaf message ID provided", "leafMessageId" to request.leafMessageId.toString())
                }
            }
        }

        // PUT /api/v1/sessions/{sessionId}/group - Assign session to group or ungroup
        put<SessionsResource.ById.Group> { resource ->
            val sessionId = resource.parent.sessionId
            val request = call.receive<UpdateSessionGroupRequest>()
            call.respondEither(
                sessionService.updateSessionGroupId(sessionId, request.groupId)
            ) { error ->
                when (error) {
                    is UpdateSessionGroupIdError.SessionNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found", "sessionId" to error.id.toString())
                    is UpdateSessionGroupIdError.InvalidRelatedEntity ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid group ID provided", "groupId" to request.groupId.toString())
                }
            }
        }

        // POST /api/v1/sessions/{sessionId}/messages - Process a new message for a session
        post<SessionsResource.ById.Messages> { resource ->
            val sessionId = resource.parent.sessionId
            val request = call.receive<ProcessNewMessageRequest>()
            call.respondEither(
                messageService.processNewMessage(
                    sessionId,
                    request.content,
                    request.parentMessageId
                ), HttpStatusCode.Created
            ) { error ->
                when (error) {
                    is ProcessNewMessageError.SessionNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found", "sessionId" to error.sessionId.toString())
                    is ProcessNewMessageError.ParentNotInSession ->
                        apiError(CommonApiErrorCodes.INVALID_STATE, "Parent message does not belong to this session", "sessionId" to error.sessionId.toString(), "parentId" to error.parentId.toString())
                    is ProcessNewMessageError.ModelConfigurationError ->
                        apiError(ChatbotApiErrorCodes.MODEL_CONFIGURATION_ERROR, "LLM configuration error", "details" to error.message)
                    is ProcessNewMessageError.ExternalServiceError ->
                        apiError(ChatbotApiErrorCodes.EXTERNAL_SERVICE_ERROR, "LLM API Error", "details" to error.message)
                }
            }
        }
    }

    /**
     * Configures routes related to Groups (/api/v1/groups).
     */
    private fun Route.configureGroupRoutes() {
        // GET /api/v1/groups - List all groups
        get<GroupsResource> {
            call.respond(groupService.getAllGroups())
        }

        // POST /api/v1/groups - Create a new group
        post<GroupsResource> {
            val request = call.receive<CreateGroupRequest>()
            call.respondEither(groupService.createGroup(request.name), HttpStatusCode.Created) { error ->
                when (error) {
                    is CreateGroupError.InvalidName ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid group name", "reason" to error.reason)
                }
            }
        }

        // DELETE /api/v1/groups/{groupId} - Delete group by ID
        delete<GroupsResource.ById> { resource ->
            val groupId = resource.groupId
            call.respondEither(groupService.deleteGroup(groupId), HttpStatusCode.NoContent) { error ->
                when (error) {
                    is DeleteGroupError.GroupNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Group not found", "groupId" to error.id.toString())
                }
            }
        }

        // PUT /api/v1/groups/{groupId} - Rename group by ID
        put<GroupsResource.ById> { resource ->
            val groupId = resource.groupId
            val request = call.receive<RenameGroupRequest>()
            call.respondEither(groupService.renameGroup(groupId, request.name)) { error ->
                when (error) {
                    is RenameGroupError.GroupNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Group not found", "groupId" to error.id.toString())
                    is RenameGroupError.InvalidName ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid group name", "reason" to error.reason)
                }
            }
        }
    }

    /**
     * Configures routes related to Providers (/api/v1/providers).
     */
    private fun Route.configureProviderRoutes() {
        // GET /api/v1/providers - List all providers
        get<ProvidersResource> {
            call.respond(llmProviderService.getAllProviders())
        }

        // POST /api/v1/providers - Add new provider
        post<ProvidersResource> {
            val request = call.receive<AddProviderRequest>()
            call.respondEither(
                llmProviderService.addProvider(
                    request.name,
                    request.description,
                    request.baseUrl,
                    request.type,
                    request.credential
                ), HttpStatusCode.Created
            ) { error ->
                when (error) {
                    is AddProviderError.InvalidInput ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid provider input", "reason" to error.reason)
                }
            }
        }

        // GET /api/v1/providers/{providerId} - Get provider by ID
        get<ProvidersResource.ById> { resource ->
            val providerId = resource.providerId
            call.respondEither(llmProviderService.getProviderById(providerId)) { error ->
                when (error) {
                    is GetProviderError.ProviderNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Provider not found", "providerId" to error.id.toString())
                }
            }
        }

        // PUT /api/v1/providers/{providerId} - Update provider by ID
        put<ProvidersResource.ById> { resource ->
            val providerId = resource.providerId
            val provider = call.receive<LLMProvider>()
            if (provider.id != providerId) {
                val errorApiCode = CommonApiErrorCodes.INVALID_ARGUMENT
                val error = apiError(
                    apiCode = errorApiCode,
                    message = "Provider ID in path and body must match",
                    "pathId" to providerId.toString(),
                    "bodyId" to provider.id.toString()
                )
                return@put call.respond(HttpStatusCode.fromValue(error.statusCode), error)
            }
            call.respondEither(llmProviderService.updateProvider(provider)) { error ->
                when (error) {
                    is UpdateProviderError.ProviderNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Provider not found", "providerId" to error.id.toString())
                    is UpdateProviderError.InvalidInput ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid provider input", "reason" to error.reason)
                    is UpdateProviderError.ApiKeyAlreadyInUse ->
                        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "API key already in use", "apiKeyId" to error.apiKeyId)
                }
            }
        }

        // DELETE /api/v1/providers/{providerId} - Delete provider by ID
        delete<ProvidersResource.ById> { resource ->
            val providerId = resource.providerId
            call.respondEither(
                llmProviderService.deleteProvider(providerId),
                HttpStatusCode.NoContent
            ) { error ->
                when (error) {
                    is DeleteProviderError.ProviderNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Provider not found", "providerId" to error.id.toString())
                    is DeleteProviderError.ProviderInUse ->
                        apiError(CommonApiErrorCodes.RESOURCE_IN_USE, "Provider is still in use by models", "providerId" to error.id.toString(), "modelNames" to error.modelNames.joinToString())
                }
            }
        }

        // PUT /api/v1/providers/{providerId}/credential - Update provider credential
        put<ProvidersResource.ById.Credential> { resource ->
            val providerId = resource.parent.providerId
            val request = call.receive<UpdateProviderCredentialRequest>()
            call.respondEither(
                llmProviderService.updateProviderCredential(
                    providerId,
                    request.credential
                )
            ) { error ->
                when (error) {
                    is UpdateProviderCredentialError.ProviderNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Provider not found", "providerId" to error.id.toString())
                    is UpdateProviderCredentialError.InvalidInput ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid credential input", "reason" to error.reason)
                }
            }
        }

        // GET /api/v1/providers/{providerId}/models - Get models for this provider
        get<ProvidersResource.ById.Models> { resource ->
            val providerId = resource.parent.providerId
            call.respond(llmModelService.getModelsByProviderId(providerId))
        }
    }

    /**
     * Configures routes related to Models (/api/v1/models).
     */
    private fun Route.configureModelRoutes() {
        // GET /api/v1/models - List all models
        get<ModelResources> {
            call.respond(llmModelService.getAllModels())
        }

        // POST /api/v1/models - Add new model
        post<ModelResources> {
            val request = call.receive<AddModelRequest>()
            call.respondEither(
                llmModelService.addModel(
                    request.name,
                    request.providerId,
                    request.active,
                    request.displayName
                ), HttpStatusCode.Created
            ) { error ->
                when (error) {
                    is AddModelError.InvalidInput ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid model input", "reason" to error.reason)
                    is AddModelError.ProviderNotFound ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Provider not found for model", "providerId" to error.providerId.toString())
                    is AddModelError.ModelNameAlreadyExists ->
                        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "Model name already exists", "name" to error.name)
                }
            }
        }

        // GET /api/v1/models/{modelId} - Get model by ID
        get<ModelResources.ById> { resource ->
            val modelId = resource.modelId
            call.respondEither(llmModelService.getModelById(modelId)) { error ->
                when (error) {
                    is GetModelError.ModelNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Model not found", "modelId" to error.id.toString())
                }
            }
        }

        // PUT /api/v1/models/{modelId} - Update model by ID
        put<ModelResources.ById> { resource ->
            val modelId = resource.modelId
            val model = call.receive<LLMModel>()
            if (model.id != modelId) {
                val errorApiCode = CommonApiErrorCodes.INVALID_ARGUMENT
                val error = apiError(
                    apiCode = errorApiCode,
                    message = "Model ID in path and body must match",
                    "pathId" to modelId.toString(),
                    "bodyId" to model.id.toString()
                )
                return@put call.respond(HttpStatusCode.fromValue(error.statusCode), error)
            }
            call.respondEither(llmModelService.updateModel(model)) { error ->
                when (error) {
                    is UpdateModelError.ModelNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Model not found", "modelId" to error.id.toString())
                    is UpdateModelError.InvalidInput ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid model input", "reason" to error.reason)
                    is UpdateModelError.ProviderNotFound ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Provider not found for model", "providerId" to error.providerId.toString())
                    is UpdateModelError.ModelNameAlreadyExists ->
                        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "Model name already exists", "name" to error.name)
                }
            }
        }

        // DELETE /api/v1/models/{modelId} - Delete model by ID
        delete<ModelResources.ById> { resource ->
            val modelId = resource.modelId
            call.respondEither(llmModelService.deleteModel(modelId), HttpStatusCode.NoContent) { error ->
                when (error) {
                    is DeleteModelError.ModelNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Model not found", "modelId" to error.id.toString())
                }
            }
        }

        // --- Nested settings routes under model ---

        // GET /api/v1/models/{modelId}/settings - List settings for this model
        get<ModelResources.ById.Settings> { resource ->
            val modelId = resource.parent.modelId
            call.respond(modelSettingsService.getSettingsByModelId(modelId))
        }

        // POST /api/v1/models/{modelId}/settings - Add new settings for this model
        post<ModelResources.ById.Settings> { resource ->
            val modelId = resource.parent.modelId
            val request = call.receive<AddModelSettingsRequest>()
            call.respondEither(
                modelSettingsService.addSettings(
                    request.name,
                    modelId,
                    request.systemMessage,
                    request.temperature,
                    request.maxTokens,
                    request.customParamsJson
                ), HttpStatusCode.Created
            ) { error ->
                when (error) {
                    is AddSettingsError.ModelNotFound ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Model not found for settings", "modelId" to error.modelId.toString())
                    is AddSettingsError.InvalidInput ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid settings input", "reason" to error.reason)
                }
            }
        }

        // GET /api/v1/models/{modelId}/apikey/status - Get API key status for model
        get<ModelResources.ById.ApiKeyStatus> { resource ->
            val modelId = resource.parent.modelId
            val isConfigured = llmModelService.isApiKeyConfiguredForModel(modelId)
            call.respond(ApiKeyStatusResponse(isConfigured))
        }
    }

    /**
     * Configures top-level routes related to Settings (/api/v1/settings).
     */
    private fun Route.configureSettingsRoutes() {
        // GET /api/v1/settings/{settingsId} - Get settings by ID
        get<SettingsResource.ById> { resource ->
            val settingsId = resource.settingsId
            call.respondEither(modelSettingsService.getSettingsById(settingsId)) { error ->
                when (error) {
                    is GetSettingsByIdError.SettingsNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Settings not found", "settingsId" to error.id.toString())
                }
            }
        }

        // PUT /api/v1/settings/{settingsId} - Update settings by ID
        put<SettingsResource.ById> { resource ->
            val settingsId = resource.settingsId
            val settings = call.receive<ModelSettings>()
            if (settings.id != settingsId) {
                val errorApiCode = CommonApiErrorCodes.INVALID_ARGUMENT
                val error = apiError(
                    apiCode = errorApiCode,
                    message = "Settings ID in path and body must match",
                    "pathId" to settingsId.toString(),
                    "bodyId" to settings.id.toString()
                )
                return@put call.respond(HttpStatusCode.fromValue(error.statusCode), error)
            }
            call.respondEither(modelSettingsService.updateSettings(settings)) { error ->
                when (error) {
                    is UpdateSettingsError.SettingsNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Settings not found", "settingsId" to error.id.toString())
                    is UpdateSettingsError.InvalidInput ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid settings input", "reason" to error.reason)
                    is UpdateSettingsError.ModelNotFound ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Model not found for settings", "modelId" to error.modelId.toString())
                }
            }
        }

        // DELETE /api/v1/settings/{settingsId} - Delete settings by ID
        delete<SettingsResource.ById> { resource ->
            val settingsId = resource.settingsId
            call.respondEither(
                modelSettingsService.deleteSettings(settingsId),
                HttpStatusCode.NoContent
            ) { error ->
                when (error) {
                    is DeleteSettingsError.SettingsNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Settings not found", "settingsId" to error.id.toString())
                }
            }
        }
    }

    /**
     * Configures top-level Message routes (/api/v1/messages)
     */
    private fun Route.configureMessageRoutes() {
        // PUT /api/v1/messages/{messageId}/content - Update message content by ID
        put<MessagesResource.ById.Content> { resource ->
            val messageId = resource.parent.messageId
            val request = call.receive<UpdateMessageRequest>()
            call.respondEither(
                messageService.updateMessageContent(
                    messageId,
                    request.content
                )
            ) { error ->
                when (error) {
                    is UpdateMessageContentError.MessageNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Message not found", "messageId" to error.id.toString())
                }
            }
        }

        // DELETE /api/v1/messages/{messageId} - Delete message by ID
        delete<MessagesResource.ById> { resource ->
            val messageId = resource.messageId
            call.respondEither(
                messageService.deleteMessage(messageId),
                HttpStatusCode.NoContent
            ) { error ->
                when (error) {
                    is DeleteMessageError.MessageNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Message not found", "messageId" to error.id.toString())
                }
            }
        }
    }
}

/**
 * Extension function on ApplicationCall to respond with the result of an Either.
 * Maps Left to an error response using the ApiError structure.
 *
 * @param either The result of an operation, either a success value (R) or an error (L).
 * @param successCode The HTTP status code to use for a successful (Right) response. Defaults to OK.
 * @param errorMapping A function to map the error object (L) to an ApiError object.
 *                     The HTTP status code will be taken from the ApiError object itself.
 *                     Defaults to a generic INTERNAL ApiError with status 500.
 */
private suspend inline fun <reified R : Any, reified L : Any> ApplicationCall.respondEither(
    either: Either<L, R>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    noinline errorMapping: (L) -> ApiError =
        { error -> apiError(CommonApiErrorCodes.INTERNAL, "An unexpected error occurred: ${error.toString()}") } // Default mapping returns ApiError
) {
    when (either) {
        is Either.Right -> respond(successCode, either.value)
        is Either.Left -> {
            val apiError = errorMapping(either.value)
            val status = HttpStatusCode.fromValue(apiError.statusCode)
            respond(status, apiError)
        }
    }
}

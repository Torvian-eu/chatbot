package eu.torvian.chatbot.server.main

import arrow.core.Either
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
    private val application: Application, // Application instance injected via Koin
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
            // Call service and respond directly (assuming no failure case for getAllSessionsSummaries)
            call.respond(this@ApiRoutesKtor.sessionService.getAllSessionsSummaries())
        }

        // POST /api/v1/sessions - Create a new session
        post<SessionsResource> {
            val request = call.receive<CreateSessionRequest>()
            call.respondEither(
                this@ApiRoutesKtor.sessionService.createSession(request.name),
                HttpStatusCode.Created
            ) {
                when (it) {
                    is CreateSessionError.InvalidName -> HttpStatusCode.BadRequest to it.reason
                    is CreateSessionError.InvalidRelatedEntity -> HttpStatusCode.BadRequest to it.message
                }
            }
        }

        // GET /api/v1/sessions/{sessionId} - Get session by ID
        get<SessionsResource.ById> { resource ->
            val sessionId = resource.sessionId
            call.respondEither(this@ApiRoutesKtor.sessionService.getSessionDetails(sessionId)) {
                when (it) {
                    is GetSessionDetailsError.SessionNotFound -> HttpStatusCode.NotFound to "Session not found: ${it.id}"
                }
            }
        }

        // DELETE /api/v1/sessions/{sessionId} - Delete session by ID
        delete<SessionsResource.ById> { resource ->
            val sessionId = resource.sessionId
            call.respondEither(
                this@ApiRoutesKtor.sessionService.deleteSession(sessionId),
                HttpStatusCode.NoContent
            ) {
                when (it) {
                    is DeleteSessionError.SessionNotFound -> HttpStatusCode.NotFound to "Session not found: ${it.id}"
                }
            }
        }

        // --- Granular PUT routes using nested resources ---

        // PUT /api/v1/sessions/{sessionId}/name - Update the name of a session
        put<SessionsResource.ById.Name> { resource ->
            val sessionId = resource.parent.sessionId
            val request = call.receive<UpdateSessionNameRequest>()
            call.respondEither(this@ApiRoutesKtor.sessionService.updateSessionName(sessionId, request.name)) {
                when (it) {
                    is UpdateSessionNameError.SessionNotFound -> HttpStatusCode.NotFound to "Session not found: ${it.id}"
                    is UpdateSessionNameError.InvalidName -> HttpStatusCode.BadRequest to it.reason
                }
            }
        }

        // PUT /api/v1/sessions/{sessionId}/model - Update the current model ID of a session
        put<SessionsResource.ById.Model> { resource ->
            val sessionId = resource.parent.sessionId
            val request = call.receive<UpdateSessionModelRequest>()
            call.respondEither(
                this@ApiRoutesKtor.sessionService.updateSessionCurrentModelId(
                    sessionId,
                    request.modelId
                )
            ) {
                when (it) {
                    is UpdateSessionCurrentModelIdError.SessionNotFound -> HttpStatusCode.NotFound to "Session not found: ${it.id}"
                    is UpdateSessionCurrentModelIdError.InvalidRelatedEntity -> HttpStatusCode.BadRequest to "Invalid model ID provided: ${request.modelId}"
                }
            }
        }

        // PUT /api/v1/sessions/{sessionId}/settings - Update the current settings ID of a session
        put<SessionsResource.ById.Settings> { resource ->
            val sessionId = resource.parent.sessionId
            val request = call.receive<UpdateSessionSettingsRequest>()
            call.respondEither(
                this@ApiRoutesKtor.sessionService.updateSessionCurrentSettingsId(
                    sessionId,
                    request.settingsId
                )
            ) {
                when (it) {
                    is UpdateSessionCurrentSettingsIdError.SessionNotFound -> HttpStatusCode.NotFound to "Session not found: ${it.id}"
                    is UpdateSessionCurrentSettingsIdError.InvalidRelatedEntity -> HttpStatusCode.BadRequest to "Invalid settings ID provided: ${request.settingsId}"
                }
            }
        }

        // PUT /api/v1/sessions/{sessionId}/leafMessage - Update the current leaf message ID of a session
        put<SessionsResource.ById.LeafMessage> { resource ->
            val sessionId = resource.parent.sessionId
            val request = call.receive<UpdateSessionLeafMessageRequest>()
            call.respondEither(
                this@ApiRoutesKtor.sessionService.updateSessionLeafMessageId(
                    sessionId,
                    request.leafMessageId
                )
            ) {
                when (it) {
                    is UpdateSessionLeafMessageIdError.SessionNotFound -> HttpStatusCode.NotFound to "Session not found: ${it.id}"
                    is UpdateSessionLeafMessageIdError.InvalidRelatedEntity -> HttpStatusCode.BadRequest to "Invalid leaf message ID provided: ${request.leafMessageId}"
                }
            }
        }

        // PUT /api/v1/sessions/{sessionId}/group - Assign session to group or ungroup
        put<SessionsResource.ById.Group> { resource ->
            val sessionId = resource.parent.sessionId
            val request = call.receive<UpdateSessionGroupRequest>()
            call.respondEither(
                this@ApiRoutesKtor.sessionService.updateSessionGroupId(
                    sessionId,
                    request.groupId
                )
            ) {
                when (it) {
                    is UpdateSessionGroupIdError.SessionNotFound -> HttpStatusCode.NotFound to "Session not found: ${it.id}"
                    is UpdateSessionGroupIdError.InvalidRelatedEntity -> HttpStatusCode.BadRequest to "Invalid group ID provided: ${request.groupId}"
                }
            }
        }

        // POST /api/v1/sessions/{sessionId}/messages - Process a new message for a session
        post<SessionsResource.ById.Messages> { resource ->
            val sessionId = resource.parent.sessionId
            val request = call.receive<ProcessNewMessageRequest>()
            call.respondEither(
                this@ApiRoutesKtor.messageService.processNewMessage(
                    sessionId,
                    request.content,
                    request.parentMessageId
                ), HttpStatusCode.Created
            ) {
                when (it) {
                    is ProcessNewMessageError.SessionNotFound -> HttpStatusCode.NotFound to "Session not found: ${it.sessionId}"
                    is ProcessNewMessageError.ParentNotInSession -> HttpStatusCode.BadRequest to "Parent message does not belong to this session (parent: ${it.parentId}, session: ${it.sessionId})"
                    is ProcessNewMessageError.ModelConfigurationError -> HttpStatusCode.BadRequest to "LLM configuration error: ${it.message}"
                    is ProcessNewMessageError.ExternalServiceError -> HttpStatusCode.InternalServerError to "LLM API Error: ${it.message}"
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
            call.respond(this@ApiRoutesKtor.groupService.getAllGroups())
        }

        // POST /api/v1/groups - Create a new group
        post<GroupsResource> {
            val request = call.receive<CreateGroupRequest>()
            call.respondEither(this@ApiRoutesKtor.groupService.createGroup(request.name), HttpStatusCode.Created) {
                when (it) {
                    is CreateGroupError.InvalidName -> HttpStatusCode.BadRequest to it.reason
                    // Add mapping for AlreadyExists if implemented later
                }
            }
        }

        // DELETE /api/v1/groups/{groupId} - Delete group by ID
        delete<GroupsResource.ById> { resource ->
            val groupId = resource.groupId
            call.respondEither(this@ApiRoutesKtor.groupService.deleteGroup(groupId), HttpStatusCode.NoContent) {
                when (it) {
                    is DeleteGroupError.GroupNotFound -> HttpStatusCode.NotFound to "Group not found: ${it.id}"
                }
            }
        }

        // PUT /api/v1/groups/{groupId} - Rename group by ID
        put<GroupsResource.ById> { resource ->
            val groupId = resource.groupId
            val request = call.receive<RenameGroupRequest>()
            call.respondEither(this@ApiRoutesKtor.groupService.renameGroup(groupId, request.name)) {
                when (it) {
                    is RenameGroupError.GroupNotFound -> HttpStatusCode.NotFound to "Group not found: ${it.id}"
                    is RenameGroupError.InvalidName -> HttpStatusCode.BadRequest to it.reason
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
            call.respond(this@ApiRoutesKtor.llmProviderService.getAllProviders())
        }

        // POST /api/v1/providers - Add new provider
        post<ProvidersResource> {
            val request = call.receive<AddProviderRequest>()
            call.respondEither(
                this@ApiRoutesKtor.llmProviderService.addProvider(
                    request.name,
                    request.description,
                    request.baseUrl,
                    request.type,
                    request.credential
                ), HttpStatusCode.Created
            ) {
                when (it) {
                    is AddProviderError.InvalidInput -> HttpStatusCode.BadRequest to it.reason
                }
            }
        }

        // GET /api/v1/providers/{providerId} - Get provider by ID
        get<ProvidersResource.ById> { resource ->
            val providerId = resource.providerId
            call.respondEither(this@ApiRoutesKtor.llmProviderService.getProviderById(providerId)) {
                when (it) {
                    is GetProviderError.ProviderNotFound -> HttpStatusCode.NotFound to "Provider not found: ${it.id}"
                }
            }
        }

        // PUT /api/v1/providers/{providerId} - Update provider by ID
        put<ProvidersResource.ById> { resource ->
            val providerId = resource.providerId
            val provider = call.receive<LLMProvider>()
            // Manual check if body ID matches path ID (best practice)
            if (provider.id != providerId) {
                return@put call.respond(HttpStatusCode.BadRequest, "Provider ID in path and body must match")
            }
            call.respondEither(this@ApiRoutesKtor.llmProviderService.updateProvider(provider)) {
                when (it) {
                    is UpdateProviderError.ProviderNotFound -> HttpStatusCode.NotFound to "Provider not found: ${it.id}"
                    is UpdateProviderError.InvalidInput -> HttpStatusCode.BadRequest to it.reason
                    is UpdateProviderError.ApiKeyAlreadyInUse -> HttpStatusCode.Conflict to "API key already in use: ${it.apiKeyId}"
                }
            }
        }

        // DELETE /api/v1/providers/{providerId} - Delete provider by ID
        delete<ProvidersResource.ById> { resource ->
            val providerId = resource.providerId
            call.respondEither(
                this@ApiRoutesKtor.llmProviderService.deleteProvider(providerId),
                HttpStatusCode.NoContent
            ) {
                when (it) {
                    is DeleteProviderError.ProviderNotFound -> HttpStatusCode.NotFound to "Provider not found: ${it.id}"
                    is DeleteProviderError.ProviderInUse -> HttpStatusCode.Conflict to "Provider is still in use by models: ${
                        it.modelNames.joinToString(
                            ", "
                        )
                    }"
                }
            }
        }

        // PUT /api/v1/providers/{providerId}/credential - Update provider credential
        put<ProvidersResource.ById.Credential> { resource ->
            val providerId = resource.parent.providerId // Access provider ID from parent resource
            val request = call.receive<UpdateProviderCredentialRequest>()
            call.respondEither(
                this@ApiRoutesKtor.llmProviderService.updateProviderCredential(
                    providerId,
                    request.credential
                )
            ) {
                when (it) {
                    is UpdateProviderCredentialError.ProviderNotFound -> HttpStatusCode.NotFound to "Provider not found: ${it.id}"
                    is UpdateProviderCredentialError.InvalidInput -> HttpStatusCode.BadRequest to it.reason
                }
            }
        }

        // GET /api/v1/providers/{providerId}/models - Get models for this provider
        get<ProvidersResource.ById.Models> { resource ->
            val providerId = resource.parent.providerId // Access provider ID from parent resource
            // Assuming getModelsByProviderId does not return Either
            call.respond(this@ApiRoutesKtor.llmModelService.getModelsByProviderId(providerId))
        }
    }

    /**
     * Configures routes related to Models (/api/v1/models).
     */
    private fun Route.configureModelRoutes() {
        // GET /api/v1/models - List all models
        get<ModelResources> {
            call.respond(this@ApiRoutesKtor.llmModelService.getAllModels())
        }

        // POST /api/v1/models - Add new model
        post<ModelResources> {
            val request = call.receive<AddModelRequest>()
            call.respondEither(
                this@ApiRoutesKtor.llmModelService.addModel(
                    request.name,
                    request.providerId,
                    request.active,
                    request.displayName
                ), HttpStatusCode.Created
            ) {
                when (it) {
                    is AddModelError.InvalidInput -> HttpStatusCode.BadRequest to it.reason
                    is AddModelError.ProviderNotFound -> HttpStatusCode.BadRequest to "Provider not found: ${it.providerId}"
                    is AddModelError.ModelNameAlreadyExists -> HttpStatusCode.Conflict to "Model name already exists: ${it.name}"
                }
            }
        }

        // GET /api/v1/models/{modelId} - Get model by ID
        get<ModelResources.ById> { resource ->
            val modelId = resource.modelId
            call.respondEither(this@ApiRoutesKtor.llmModelService.getModelById(modelId)) {
                when (it) {
                    is GetModelError.ModelNotFound -> HttpStatusCode.NotFound to "Model not found: ${it.id}"
                }
            }
        }

        // PUT /api/v1/models/{modelId} - Update model by ID
        put<ModelResources.ById> { resource ->
            val modelId = resource.modelId
            val model = call.receive<LLMModel>()
            // Manual check if body ID matches path ID
            if (model.id != modelId) {
                return@put call.respond(HttpStatusCode.BadRequest, "Model ID in path and body must match")
            }
            call.respondEither(this@ApiRoutesKtor.llmModelService.updateModel(model)) {
                when (it) {
                    is UpdateModelError.ModelNotFound -> HttpStatusCode.NotFound to "Model not found: ${it.id}"
                    is UpdateModelError.InvalidInput -> HttpStatusCode.BadRequest to it.reason
                    is UpdateModelError.ProviderNotFound -> HttpStatusCode.BadRequest to "Provider not found: ${it.providerId}"
                    is UpdateModelError.ModelNameAlreadyExists -> HttpStatusCode.Conflict to "Model name already exists: ${it.name}"
                }
            }
        }

        // DELETE /api/v1/models/{modelId} - Delete model by ID
        delete<ModelResources.ById> { resource ->
            val modelId = resource.modelId
            call.respondEither(this@ApiRoutesKtor.llmModelService.deleteModel(modelId), HttpStatusCode.NoContent) {
                when (it) {
                    is DeleteModelError.ModelNotFound -> HttpStatusCode.NotFound to "Model not found: ${it.id}"
                }
            }
        }

        // Nested settings routes under model
        // GET /api/v1/models/{modelId}/settings - List settings for this model
        get<ModelResources.ById.Settings> { resource ->
            val modelId = resource.parent.modelId // Access model ID from parent resource
            // Assuming getSettingsByModelId does not return Either
            call.respond(this@ApiRoutesKtor.modelSettingsService.getSettingsByModelId(modelId))
        }

        // POST /api/v1/models/{modelId}/settings - Add new settings for this model
        post<ModelResources.ById.Settings> { resource ->
            val modelId = resource.parent.modelId // Access model ID from parent resource
            val request = call.receive<AddModelSettingsRequest>()
            call.respondEither(
                this@ApiRoutesKtor.modelSettingsService.addSettings(
                    request.name,
                    modelId,
                    request.systemMessage,
                    request.temperature,
                    request.maxTokens,
                    request.customParamsJson
                ), HttpStatusCode.Created
            ) {
                when (it) {
                    is AddSettingsError.ModelNotFound -> HttpStatusCode.BadRequest to "Model not found for settings: ${it.modelId}"
                    is AddSettingsError.InvalidInput -> HttpStatusCode.BadRequest to it.reason
                }
            }
        }

        // GET /api/v1/models/{modelId}/apikey/status - Get API key status for model
        get<ModelResources.ById.ApiKeyStatus> { resource ->
            val modelId = resource.parent.modelId // Access model ID from parent resource
            val isConfigured = this@ApiRoutesKtor.llmModelService.isApiKeyConfiguredForModel(modelId)
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
            call.respondEither(this@ApiRoutesKtor.modelSettingsService.getSettingsById(settingsId)) {
                when (it) {
                    is GetSettingsByIdError.SettingsNotFound -> HttpStatusCode.NotFound to "Settings not found: ${it.id}"
                }
            }
        }

        // PUT /api/v1/settings/{settingsId} - Update settings by ID
        put<SettingsResource.ById> { resource ->
            val settingsId = resource.settingsId
            val settings = call.receive<ModelSettings>()
            // Manual check if body ID matches path ID
            if (settings.id != settingsId) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    "Settings ID in path and body must match"
                )
            }
            call.respondEither(this@ApiRoutesKtor.modelSettingsService.updateSettings(settings)) {
                when (it) {
                    is UpdateSettingsError.SettingsNotFound -> HttpStatusCode.NotFound to "Settings not found: ${it.id}"
                    is UpdateSettingsError.InvalidInput -> HttpStatusCode.BadRequest to it.reason
                    is UpdateSettingsError.ModelNotFound -> HttpStatusCode.BadRequest to "Model not found for settings: ${it.modelId}"
                }
            }
        }

        // DELETE /api/v1/settings/{settingsId} - Delete settings by ID
        delete<SettingsResource.ById> { resource ->
            val settingsId = resource.settingsId
            call.respondEither(
                this@ApiRoutesKtor.modelSettingsService.deleteSettings(settingsId),
                HttpStatusCode.NoContent
            ) {
                when (it) {
                    is DeleteSettingsError.SettingsNotFound -> HttpStatusCode.NotFound to "Settings not found: ${it.id}"
                }
            }
        }
    }

    /**
     * Configures top-level Message routes (/api/v1/messages)
     */
    private fun Route.configureMessageRoutes() {
        // PUT /api/v1/messages/{messageId}/content - Update message content by ID
        put<MessagesResource.ById.Content> { resource -> // Uses the top-level MessagesResource
            val messageId = resource.parent.messageId // Access message ID from the parent resource
            val request = call.receive<UpdateMessageRequest>()
            call.respondEither(
                this@ApiRoutesKtor.messageService.updateMessageContent(
                    messageId,
                    request.content
                )
            ) {
                when (it) {
                    is UpdateMessageContentError.MessageNotFound -> HttpStatusCode.NotFound to "Message not found: ${it.id}"
                }
            }
        }

        // DELETE /api/v1/messages/{messageId} - Delete message by ID
        delete<MessagesResource.ById> { resource -> // Uses the top-level MessagesResource
            val messageId = resource.messageId
            call.respondEither(
                this@ApiRoutesKtor.messageService.deleteMessage(messageId),
                HttpStatusCode.NoContent
            ) {
                when (it) {
                    is DeleteMessageError.MessageNotFound -> HttpStatusCode.NotFound to "Message not found: ${it.id}"
                }
            }
        }
    }
}

/**
 * Extension function on ApplicationCall to respond with the result of an Either.
 * Maps Left to an error response and Right to a success response.
 *
 * @param either The result of an operation, either a success value (R) or an error (L).
 * @param successCode The HTTP status code to use for a successful (Right) response. Defaults to OK.
 * @param errorMapping A function to map the error object (L) to an HTTP status code and a message string.
 *                     Defaults to InternalServerError with the error's string representation.
 */
private suspend inline fun <reified R : Any, reified L : Any> ApplicationCall.respondEither(
    either: Either<L, R>,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    noinline errorMapping: (L) -> Pair<HttpStatusCode, String> = { HttpStatusCode.InternalServerError to it.toString() }
) {
    when (either) {
        is Either.Right -> respond(successCode, either.value)
        is Either.Left -> {
            val (status, message) = errorMapping(either.value)
            // Consider logging the error details on the server side
            respond(status, message)
        }
    }
}
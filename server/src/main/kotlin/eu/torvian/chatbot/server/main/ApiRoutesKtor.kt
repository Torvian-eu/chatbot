package eu.torvian.chatbot.server.main

import arrow.core.Either
import eu.torvian.chatbot.common.models.*
import eu.torvian.chatbot.server.service.core.*
import eu.torvian.chatbot.server.service.core.error.group.*
import eu.torvian.chatbot.server.service.core.error.message.*
import eu.torvian.chatbot.server.service.core.error.model.*
import eu.torvian.chatbot.server.service.core.error.provider.*
import eu.torvian.chatbot.server.service.core.error.settings.*
import eu.torvian.chatbot.server.service.core.error.session.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlin.text.toLongOrNull

class ApiRoutesKtor(
    private val application: Application,
    private val sessionService: SessionService,
    private val groupService: GroupService,
    private val llmProviderService: LLMProviderService,
    private val llmModelService: LLMModelService,
    private val modelSettingsService: ModelSettingsService,
    private val messageService: MessageService
) : ApiRoutes {
    override fun configureRouting() {
        application.routing {
            route("/api/v1") {
                authenticate {
                    configureSessionRoutes()
                    configureGroupRoutes()
                    configureProviderRoutes()
                    configureModelRoutes()
                    configureSettingsRoutes()
                    configureMessageRoutes()
                }
            } // End /api/v1
        }
    }

    private fun Route.configureSessionRoutes() {
        // --- Session Routes ---
        route("/sessions") {
            get { // List all sessions
                call.respond(sessionService.getAllSessionsSummaries())
            }
            post { // Create a new session
                val request = call.receive<CreateSessionRequest>()
                call.respondEither(sessionService.createSession(request.name), HttpStatusCode.Created) {
                    when (it) {
                        is CreateSessionError.InvalidName -> HttpStatusCode.BadRequest to it.reason
                        is CreateSessionError.InvalidRelatedEntity -> HttpStatusCode.BadRequest to it.message // Assuming client sends invalid FK ID
                    }
                }
            }
            route("/{sessionId}") {
                get { // Get session by ID
                    val sessionId = call.parameters["sessionId"]?.toLongOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid session ID")
                    call.respondEither(sessionService.getSessionDetails(sessionId)) {
                        when (it) {
                            is GetSessionDetailsError.SessionNotFound -> HttpStatusCode.NotFound to "Session not found: ${it.id}"
                        }
                    }
                }

                // Granular PUT routes for specific updates
                put("/name") { // Update the name of a session
                    val sessionId = call.parameters["sessionId"]?.toLongOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid session ID")
                    val request = call.receive<UpdateSessionNameRequest>()
                    call.respondEither(sessionService.updateSessionName(sessionId, request.name), HttpStatusCode.OK) {
                        when (it) {
                            is UpdateSessionNameError.SessionNotFound -> HttpStatusCode.NotFound to "Session not found: ${it.id}"
                            is UpdateSessionNameError.InvalidName -> HttpStatusCode.BadRequest to it.reason
                        }
                    }
                }
                put("/model") { // Update the current model ID of a session
                    val sessionId = call.parameters["sessionId"]?.toLongOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid session ID")
                    val request = call.receive<UpdateSessionModelRequest>()
                    call.respondEither(
                        sessionService.updateSessionCurrentModelId(
                            sessionId,
                            request.modelId
                        ), HttpStatusCode.OK
                    ) {
                        when (it) {
                            is UpdateSessionCurrentModelIdError.SessionNotFound -> HttpStatusCode.NotFound to "Session not found: ${it.id}"
                            is UpdateSessionCurrentModelIdError.InvalidRelatedEntity -> HttpStatusCode.BadRequest to "Invalid model ID provided: ${request.modelId}"
                        }
                    }
                }
                put("/settings") { // Update the current settings ID of a session
                    val sessionId = call.parameters["sessionId"]?.toLongOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid session ID")
                    val request = call.receive<UpdateSessionSettingsRequest>()
                    call.respondEither(
                        sessionService.updateSessionCurrentSettingsId(
                            sessionId,
                            request.settingsId
                        ), HttpStatusCode.OK
                    ) {
                        when (it) {
                            is UpdateSessionCurrentSettingsIdError.SessionNotFound -> HttpStatusCode.NotFound to "Session not found: ${it.id}"
                            is UpdateSessionCurrentSettingsIdError.InvalidRelatedEntity -> HttpStatusCode.BadRequest to "Invalid settings ID provided: ${request.settingsId}"
                        }
                    }
                }
                put("/leafMessage") { // Update the current leaf message ID of a session
                    val sessionId = call.parameters["sessionId"]?.toLongOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid session ID")
                    val request = call.receive<UpdateSessionLeafMessageRequest>()
                    call.respondEither(
                        sessionService.updateSessionLeafMessageId(
                            sessionId,
                            request.leafMessageId
                        ), HttpStatusCode.OK
                    ) {
                        when (it) {
                            is UpdateSessionLeafMessageIdError.SessionNotFound -> HttpStatusCode.NotFound to "Session not found: ${it.id}"
                            is UpdateSessionLeafMessageIdError.InvalidRelatedEntity -> HttpStatusCode.BadRequest to "Invalid leaf message ID provided: ${request.leafMessageId}"
                        }
                    }
                }

                put("/group") { // Assign session to group or ungroup
                    val sessionId = call.parameters["sessionId"]?.toLongOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid session ID")
                    val request = call.receive<UpdateSessionGroupRequest>()

                    call.respondEither(
                        sessionService.updateSessionGroupId(
                            sessionId,
                            request.groupId
                        ), HttpStatusCode.OK
                    ) {
                        when (it) {
                            is UpdateSessionGroupIdError.SessionNotFound -> HttpStatusCode.NotFound to "Session not found: ${it.id}"
                            is UpdateSessionGroupIdError.InvalidRelatedEntity -> HttpStatusCode.BadRequest to "Invalid group ID provided: ${request.groupId}"
                        }
                    }
                }

                delete { // Delete session by ID
                    val sessionId = call.parameters["sessionId"]?.toLongOrNull()
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid session ID")
                    call.respondEither(sessionService.deleteSession(sessionId), HttpStatusCode.NoContent) {
                        when (it) {
                            is DeleteSessionError.SessionNotFound -> HttpStatusCode.NotFound to "Session not found: ${it.id}"
                        }
                    }
                }
            } // End sessions/{sessionId}
        }
    }

    private fun Route.configureGroupRoutes() {
        // --- Group Routes ---
        route("/groups") {
            get { // List all groups
                call.respond(groupService.getAllGroups())
            }
            post { // Create a new group
                val request = call.receive<CreateGroupRequest>()
                call.respondEither(groupService.createGroup(request.name), HttpStatusCode.Created) {
                    when (it) {
                        is CreateGroupError.InvalidName -> HttpStatusCode.BadRequest to it.reason
                        // Add mapping for AlreadyExists if implemented later
                    }
                }
            }
            route("/{groupId}") {
                delete { // Delete group by ID
                    val groupId = call.parameters["groupId"]?.toLongOrNull()
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid group ID")
                    call.respondEither(groupService.deleteGroup(groupId), HttpStatusCode.NoContent) {
                        when (it) {
                            is DeleteGroupError.GroupNotFound -> HttpStatusCode.NotFound to "Group not found: ${it.id}"
                        }
                    }
                }
                put { // Rename group by ID
                    val groupId = call.parameters["groupId"]?.toLongOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid group ID")
                    val request = call.receive<RenameGroupRequest>()
                    call.respondEither(groupService.renameGroup(groupId, request.name), HttpStatusCode.OK) {
                        when (it) {
                            is RenameGroupError.GroupNotFound -> HttpStatusCode.NotFound to "Group not found: ${it.id}"
                            is RenameGroupError.InvalidName -> HttpStatusCode.BadRequest to it.reason
                        }
                    }
                }

            }
        }
    }

    private fun Route.configureProviderRoutes() {
        // --- Provider Routes ---
        route("/providers") {
            get { // List all providers
                call.respond(llmProviderService.getAllProviders())
            }
            post { // Add new provider
                val request = call.receive<AddProviderRequest>()
                call.respondEither(
                    llmProviderService.addProvider(
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
            route("/{providerId}") {
                get { // Get provider by ID
                    val providerId = call.parameters["providerId"]?.toLongOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid provider ID")
                    call.respondEither(llmProviderService.getProviderById(providerId)) {
                        when (it) {
                            is GetProviderError.ProviderNotFound -> HttpStatusCode.NotFound to "Provider not found: ${it.id}"
                        }
                    }
                }
                put { // Update provider by ID
                    val providerId = call.parameters["providerId"]?.toLongOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid provider ID")
                    val provider = call.receive<LLMProvider>()

                    if (provider.id != providerId) {
                        return@put call.respond(HttpStatusCode.BadRequest, "Provider ID in path and body must match")
                    }
                    call.respondEither(llmProviderService.updateProvider(provider), HttpStatusCode.OK) {
                        when (it) {
                            is UpdateProviderError.ProviderNotFound -> HttpStatusCode.NotFound to "Provider not found: ${it.id}"
                            is UpdateProviderError.InvalidInput -> HttpStatusCode.BadRequest to it.reason
                            is UpdateProviderError.ApiKeyAlreadyInUse -> HttpStatusCode.Conflict to "API key already in use: ${it.apiKeyId}"
                        }
                    }
                }
                delete { // Delete provider by ID
                    val providerId = call.parameters["providerId"]?.toLongOrNull()
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid provider ID")
                    call.respondEither(llmProviderService.deleteProvider(providerId), HttpStatusCode.NoContent) {
                        when (it) {
                            is DeleteProviderError.ProviderNotFound -> HttpStatusCode.NotFound to "Provider not found: ${it.id}"
                            is DeleteProviderError.ProviderInUse -> HttpStatusCode.Conflict to "Provider is still in use by models: ${it.modelNames.joinToString(", ")}"
                        }
                    }
                }

                // Update provider credential
                route("/credential") {
                    put { // Update provider credential
                        val providerId = call.parameters["providerId"]?.toLongOrNull()
                            ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid provider ID")
                        val request = call.receive<UpdateProviderCredentialRequest>()
                        call.respondEither(
                            llmProviderService.updateProviderCredential(
                                providerId,
                                request.credential
                            ), HttpStatusCode.OK
                        ) {
                            when (it) {
                                is UpdateProviderCredentialError.ProviderNotFound -> HttpStatusCode.NotFound to "Provider not found: ${it.id}"
                                is UpdateProviderCredentialError.InvalidInput -> HttpStatusCode.BadRequest to it.reason
                            }
                        }
                    }
                }

                // Get models for this provider
                route("/models") {
                    get { // Get models by provider ID
                        val providerId = call.parameters["providerId"]?.toLongOrNull()
                            ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid provider ID")
                        call.respond(llmModelService.getModelsByProviderId(providerId))
                    }
                }
            } // End /providers/{providerId}
        }
    }

    private fun Route.configureModelRoutes() {
        // --- Model Routes ---
        route("/models") {
            get { // List all models
                call.respond(llmModelService.getAllModels())
            }
            post { // Add new model
                val request = call.receive<AddModelRequest>()
                call.respondEither(
                    llmModelService.addModel(
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
            route("/{modelId}") {
                get { // Get model by ID
                    val modelId = call.parameters["modelId"]?.toLongOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid model ID")
                    call.respondEither(llmModelService.getModelById(modelId)) {
                        when (it) {
                            is GetModelError.ModelNotFound -> HttpStatusCode.NotFound to "Model not found: ${it.id}"
                        }
                    }
                }
                put { // Update model by ID
                    val modelId = call.parameters["modelId"]?.toLongOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid model ID")
                    val model = call.receive<LLMModel>()

                    if (model.id != modelId) {
                        return@put call.respond(HttpStatusCode.BadRequest, "Model ID in path and body must match")
                    }
                    call.respondEither(llmModelService.updateModel(model), HttpStatusCode.OK) {
                        when (it) {
                            is UpdateModelError.ModelNotFound -> HttpStatusCode.NotFound to "Model not found: ${it.id}"
                            is UpdateModelError.InvalidInput -> HttpStatusCode.BadRequest to it.reason
                            is UpdateModelError.ProviderNotFound -> HttpStatusCode.BadRequest to "Provider not found: ${it.providerId}"
                            is UpdateModelError.ModelNameAlreadyExists -> HttpStatusCode.Conflict to "Model name already exists: ${it.name}"
                        }
                    }
                }
                delete { // Delete model by ID
                    val modelId = call.parameters["modelId"]?.toLongOrNull()
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid model ID")
                    call.respondEither(llmModelService.deleteModel(modelId), HttpStatusCode.NoContent) {
                        when (it) {
                            is DeleteModelError.ModelNotFound -> HttpStatusCode.NotFound to "Model not found: ${it.id}"
                        }
                    }
                }

                // Nested settings routes under model
                route("/settings") {
                    get { // List settings for this model
                        val modelId = call.parameters["modelId"]?.toLongOrNull()
                            ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid model ID")
                        call.respond(modelSettingsService.getSettingsByModelId(modelId)) // Service doesn't use Either for this List retrieval
                    }
                    post { // Add new settings for this model
                        val modelId = call.parameters["modelId"]?.toLongOrNull()
                            ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid model ID")
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
                        ) {
                            when (it) {
                                is AddSettingsError.ModelNotFound -> HttpStatusCode.BadRequest to "Model not found for settings: ${it.modelId}" // Mapped from FK violation
                                is AddSettingsError.InvalidInput -> HttpStatusCode.BadRequest to it.reason
                            }
                        }
                    }
                }

                route("/apikey/status") {
                    get { // Get API key status for model
                        val modelId = call.parameters["modelId"]?.toLongOrNull()
                            ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid model ID")
                        val isConfigured = llmModelService.isApiKeyConfiguredForModel(modelId)
                        call.respond(ApiKeyStatusResponse(isConfigured))
                    }
                }
            } // End /models/{modelId}
        }
    }

    private fun Route.configureSettingsRoutes() {
        // --- Settings Routes (top-level for getting/updating/deleting specific settings) ---
        route("/settings") {
            route("/{settingsId}") {
                get { // Get settings by ID
                    val settingsId = call.parameters["settingsId"]?.toLongOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid settings ID")
                    call.respondEither(modelSettingsService.getSettingsById(settingsId)) {
                        when (it) {
                            is GetSettingsByIdError.SettingsNotFound -> HttpStatusCode.NotFound to "Settings not found: ${it.id}"
                        }
                    }
                }
                put { // Update settings by ID
                    val settingsId = call.parameters["settingsId"]?.toLongOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid settings ID")
                    val settings = call.receive<ModelSettings>()
                    if (settings.id != settingsId) {
                        return@put call.respond(
                            HttpStatusCode.BadRequest,
                            "Settings ID in path and body must match"
                        )
                    }
                    call.respondEither(modelSettingsService.updateSettings(settings), HttpStatusCode.OK) {
                        when (it) {
                            is UpdateSettingsError.SettingsNotFound -> HttpStatusCode.NotFound to "Settings not found: ${it.id}"
                            is UpdateSettingsError.InvalidInput -> HttpStatusCode.BadRequest to it.reason
                            is UpdateSettingsError.ModelNotFound -> HttpStatusCode.BadRequest to "Model not found for settings: ${it.modelId}"
                        }
                    }
                }
                delete { // Delete settings by ID
                    val settingsId = call.parameters["settingsId"]?.toLongOrNull()
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid settings ID")
                    call.respondEither(modelSettingsService.deleteSettings(settingsId), HttpStatusCode.NoContent) {
                        when (it) {
                            is DeleteSettingsError.SettingsNotFound -> HttpStatusCode.NotFound to "Settings not found: ${it.id}"
                        }
                    }
                }
            } // End /settings/{settingsId}
        }
    }

    private fun Route.configureMessageRoutes() {
        // --- Message Routes ---
        route("/sessions/{sessionId}/messages") {
            post { // Process a new message
                val sessionId = call.parameters["sessionId"]?.toLongOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid session ID")
                val request = call.receive<ProcessNewMessageRequest>()
                call.respondEither(
                    messageService.processNewMessage(
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

        route("/messages/{messageId}") {
            put("/content") {
                val messageId = call.parameters["messageId"]?.toLongOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid message ID")
                val request = call.receive<UpdateMessageRequest>()
                call.respondEither(messageService.updateMessageContent(messageId, request.content), HttpStatusCode.OK) {
                    when (it) {
                        is UpdateMessageContentError.MessageNotFound -> HttpStatusCode.NotFound to "Message not found: ${it.id}"
                    }
                }
            }
            delete {
                val messageId = call.parameters["messageId"]?.toLongOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid message ID")
                call.respondEither(messageService.deleteMessage(messageId), HttpStatusCode.NoContent) {
                    when (it) {
                        is DeleteMessageError.MessageNotFound -> HttpStatusCode.NotFound to "Message not found: ${it.id}"
                    }
                }
            }
        } // End /messages/{messageId}
    }
}

/**
 * Extension function to respond with the result of an Either, mapping Left to an error and Right to success.
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
            // Log the error on the server side for debugging
            // application.environment.log.error("API Error: $message (Status: $status, Details: $either)")
            respond(status, message)
        }
    }
}

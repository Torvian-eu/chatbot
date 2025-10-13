package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.CommonPermissions
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.ProviderResource
import eu.torvian.chatbot.common.models.api.access.GrantAccessRequest
import eu.torvian.chatbot.common.models.api.access.RevokeAccessRequest
import eu.torvian.chatbot.common.models.api.llm.AddProviderRequest
import eu.torvian.chatbot.common.models.api.llm.UpdateProviderCredentialRequest
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.LLMModelService
import eu.torvian.chatbot.server.service.core.LLMProviderService
import eu.torvian.chatbot.server.service.core.error.access.*
import eu.torvian.chatbot.server.service.core.error.provider.*
import eu.torvian.chatbot.server.service.security.AuthorizationService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import kotlin.let
import kotlin.to

/**
 * Configures routes related to Providers (/api/v1/providers) using Ktor Resources.
 */
fun Route.configureProviderRoutes(
    llmProviderService: LLMProviderService,
    llmModelService: LLMModelService,
    authorizationService: AuthorizationService
) {
    authenticate(AuthSchemes.USER_JWT) {
        // GET /api/v1/providers - List all providers (filtered by user access)
        get<ProviderResource> {
            val userId = call.getUserId()

            // If user has MANAGE_LLM_PROVIDERS permission, show all providers
            if (authorizationService.hasPermission(userId, CommonPermissions.MANAGE_LLM_PROVIDERS)) {
                call.respond(llmProviderService.getAllProviders())
            }
            // Otherwise, show only providers accessible to the user
            else {
                call.respond(llmProviderService.getAllAccessibleProviders(userId, AccessMode.READ))
            }
        }

        // GET /api/v1/providers/{providerId} - Get provider by ID
        get<ProviderResource.ById> { resource ->
            val userId = call.getUserId()
            val providerId = resource.providerId

            either {
                // Check READ access
                requireProviderAccess(authorizationService, userId, providerId, AccessMode.READ)

                // Get provider
                withError({ error: GetProviderError -> error.toApiError() }) {
                    llmProviderService.getProviderById(providerId).bind()
                }
            }.let { result ->
                call.respondEither(result, HttpStatusCode.OK)
            }
        }

        // POST /api/v1/providers - Add new provider
        post<ProviderResource> {
            val userId = call.getUserId()
            val request = call.receive<AddProviderRequest>()
            either {
                // Require CREATE or MANAGE permission for providers
                requireAnyPermission(
                    authorizationService,
                    userId,
                    CommonPermissions.MANAGE_LLM_PROVIDERS,
                    CommonPermissions.CREATE_LLM_PROVIDER
                )

                withError({ error: AddProviderError -> error.toApiError() }) {
                    llmProviderService.addProvider(
                        userId,
                        request.name,
                        request.description,
                        request.baseUrl,
                        request.type,
                        request.credential
                    ).bind()
                }
            }.let { result ->
                call.respondEither(result, HttpStatusCode.Created)
            }
        }

        // PUT /api/v1/providers/{providerId} - Update provider by ID
        put<ProviderResource.ById> { resource ->
            val userId = call.getUserId()
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

            either {
                // Check WRITE access
                requireProviderAccess(authorizationService, userId, providerId, AccessMode.WRITE)

                // Update provider
                withError({ error: UpdateProviderError -> error.toApiError() }) {
                    llmProviderService.updateProvider(provider).bind()
                }
            }.let { result ->
                call.respondEither(result, HttpStatusCode.OK)
            }
        }

        // DELETE /api/v1/providers/{providerId} - Delete provider by ID
        delete<ProviderResource.ById> { resource ->
            val userId = call.getUserId()
            val providerId = resource.providerId

            either {
                // Check WRITE access
                requireProviderAccess(authorizationService, userId, providerId, AccessMode.WRITE)

                // Delete provider
                withError({ error: DeleteProviderError -> error.toApiError() }) {
                    llmProviderService.deleteProvider(providerId).bind()
                }
            }.let { result ->
                call.respondEither(result, HttpStatusCode.NoContent)
            }
        }

        // PUT /api/v1/providers/{providerId}/credential - Update provider credential
        put<ProviderResource.ById.Credential> { resource ->
            val userId = call.getUserId()
            val providerId = resource.parent.providerId
            val request = call.receive<UpdateProviderCredentialRequest>()

            either {
                // Check WRITE access
                requireProviderAccess(authorizationService, userId, providerId, AccessMode.WRITE)

                // Update credential
                withError({ error: UpdateProviderCredentialError -> error.toApiError() }) {
                    llmProviderService.updateProviderCredential(providerId, request.credential).bind()
                }
            }.let { result ->
                call.respondEither(result, HttpStatusCode.OK)
            }
        }

        // GET /api/v1/providers/{providerId}/models - Get models for this provider (filtered by user access)
        get<ProviderResource.ById.Models> { resource ->
            val userId = call.getUserId()
            val providerId = resource.parent.providerId

            // If user has MANAGE_LLM_MODELS permission, show all models
            if (authorizationService.hasPermission(userId, CommonPermissions.MANAGE_LLM_MODELS)) {
                call.respond(llmModelService.getModelsByProviderId(providerId))
            }
            // Otherwise, show only models accessible to the user
            else {
                call.respond(llmModelService.getAccessibleModelsByProviderId(userId, providerId, AccessMode.READ))
            }
        }

        // --- Access Management ---

        // POST /api/v1/providers/{providerId}/access - Grant access to a group
        post<ProviderResource.ById.Access> { resource ->
            val userId = call.getUserId()
            val providerId = resource.parent.providerId
            val request = call.receive<GrantAccessRequest>()

            either {
                requireProviderAccess(authorizationService, userId, providerId, AccessMode.MANAGE)

                // Grant access
                val accessMode = AccessMode.of(request.accessMode)
                withError({ error: GrantResourceAccessError -> error.toApiError() }) {
                    llmProviderService.grantProviderAccess(
                        providerId,
                        request.groupId,
                        accessMode
                    ).bind()
                }
            }.let { result ->
                call.respondEither(result, HttpStatusCode.OK)
            }
        }

        // DELETE /api/v1/providers/{providerId}/access - Revoke access from a group
        delete<ProviderResource.ById.Access> { resource ->
            val userId = call.getUserId()
            val providerId = resource.parent.providerId
            val request = call.receive<RevokeAccessRequest>()

            either {
                requireProviderAccess(authorizationService, userId, providerId, AccessMode.MANAGE)

                // Revoke access
                val accessMode = AccessMode.of(request.accessMode)
                withError({ error: RevokeResourceAccessError -> error.toApiError() }) {
                    llmProviderService.revokeProviderAccess(
                        providerId,
                        request.groupId,
                        accessMode
                    ).bind()
                }
            }.let { result ->
                call.respondEither(result, HttpStatusCode.OK)
            }
        }

        // --- Convenience Endpoints ---

        // POST /api/v1/providers/{providerId}/make-public - Make provider public
        post<ProviderResource.ById.MakePublic> { resource ->
            val userId = call.getUserId()
            val providerId = resource.parent.providerId

            either {
                // Require MANAGE access to change visibility
                requireProviderAccess(authorizationService, userId, providerId, AccessMode.MANAGE)

                // Make public
                withError({ error: MakeResourcePublicError -> error.toApiError() }) {
                    llmProviderService.makeProviderPublic(providerId).bind()
                }
            }.let { result ->
                call.respondEither(result, HttpStatusCode.OK)
            }
        }

        // POST /api/v1/providers/{providerId}/make-private - Make provider private
        post<ProviderResource.ById.MakePrivate> { resource ->
            val userId = call.getUserId()
            val providerId = resource.parent.providerId

            either {
                // Require MANAGE access to change visibility
                requireProviderAccess(authorizationService, userId, providerId, AccessMode.MANAGE)

                // Make private
                withError({ error: MakeResourcePrivateError -> error.toApiError() }) {
                    llmProviderService.makeProviderPrivate(providerId).bind()
                }
            }.let { result ->
                call.respondEither(result, HttpStatusCode.OK)
            }
        }

        // --- Details Endpoints ---

        // GET /api/v1/providers/{providerId}/details - Get provider details
        get<ProviderResource.ById.Details> { resource ->
            val userId = call.getUserId()
            val providerId = resource.parent.providerId

            either {
                // Require MANAGE access to view details
                requireProviderAccess(authorizationService, userId, providerId, AccessMode.MANAGE)

                // Get provider details
                withError({ error: GetProviderError -> error.toApiError() }) {
                    llmProviderService.getProviderDetails(providerId).bind()
                }
            }.let { result ->
                call.respondEither(result)
            }
        }

        // GET /api/v1/providers/details - List all providers with details (filtered by user access)
        get<ProviderResource.Details> {
            val userId = call.getUserId()

            either {
                withError({ error: GetProviderError -> error.toApiError() }) {
                    val providers =
                        // If user has MANAGE_LLM_PROVIDERS permission, show all providers
                        if (authorizationService.hasPermission(userId, CommonPermissions.MANAGE_LLM_PROVIDERS)) {
                            llmProviderService.getAllProviders()
                        }
                        // Otherwise, show only providers owned or accessible to the user
                        else {
                            llmProviderService.getAllAccessibleProviders(userId, AccessMode.MANAGE)
                        }
                    // Get details for each provider
                    providers.map { provider ->
                        llmProviderService.getProviderDetails(provider.id).bind()
                    }
                }
            }.let { result ->
                call.respondEither(result)
            }
        }
    }
}

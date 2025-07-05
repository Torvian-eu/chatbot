package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.ProviderResource
import eu.torvian.chatbot.common.models.AddProviderRequest
import eu.torvian.chatbot.common.models.LLMProvider
import eu.torvian.chatbot.common.models.UpdateProviderCredentialRequest
import eu.torvian.chatbot.server.service.core.LLMModelService
import eu.torvian.chatbot.server.service.core.LLMProviderService
import eu.torvian.chatbot.server.service.core.error.provider.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import kotlin.to

/**
 * Configures routes related to Providers (/api/v1/providers) using Ktor Resources.
 */
fun Route.configureProviderRoutes(
    llmProviderService: LLMProviderService,
    llmModelService: LLMModelService
) {
    // GET /api/v1/providers - List all providers
    get<ProviderResource> {
        call.respond(llmProviderService.getAllProviders())
    }

    // POST /api/v1/providers - Add new provider
    post<ProviderResource> {
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
    get<ProviderResource.ById> { resource ->
        val providerId = resource.providerId
        call.respondEither(llmProviderService.getProviderById(providerId)) { error ->
            when (error) {
                is GetProviderError.ProviderNotFound ->
                    apiError(CommonApiErrorCodes.NOT_FOUND, "Provider not found", "providerId" to error.id.toString())
            }
        }
    }

    // PUT /api/v1/providers/{providerId} - Update provider by ID
    put<ProviderResource.ById> { resource ->
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
            // Use respond directly for the invalid argument case before calling service
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
    delete<ProviderResource.ById> { resource ->
        val providerId = resource.providerId
        call.respondEither(
            llmProviderService.deleteProvider(providerId),
            HttpStatusCode.NoContent
        ) { error ->
            when (error) {
                is DeleteProviderError.ProviderNotFound ->
                    apiError(CommonApiErrorCodes.NOT_FOUND, "Provider not found", "providerId" to error.id.toString())

                is DeleteProviderError.ProviderInUse ->
                    apiError(
                        CommonApiErrorCodes.RESOURCE_IN_USE,
                        "Provider is still in use by models",
                        "providerId" to error.id.toString(),
                        "modelNames" to error.modelNames.joinToString()
                    )
            }
        }
    }

    // PUT /api/v1/providers/{providerId}/credential - Update provider credential
    put<ProviderResource.ById.Credential> { resource ->
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
    get<ProviderResource.ById.Models> { resource ->
        val providerId = resource.parent.providerId
        call.respond(llmModelService.getModelsByProviderId(providerId))
    }
}
package eu.torvian.chatbot.server.ktor.routes

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.CredentialProfileResource
import eu.torvian.chatbot.common.api.resources.CredentialSecretResource
import eu.torvian.chatbot.common.api.resources.WorkerCredentialResource
import eu.torvian.chatbot.common.models.api.credential.AssignedCredentialsDto
import eu.torvian.chatbot.common.models.api.credential.CreateCredentialProfileRequest
import eu.torvian.chatbot.common.models.api.credential.CreateCredentialSecretRequest
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.CredentialSecretMetadataDto
import eu.torvian.chatbot.common.models.api.credential.UpdateCredentialProfileRequest
import eu.torvian.chatbot.common.models.api.credential.UpdateCredentialSecretRequest
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.ktor.auth.getWorkerId
import eu.torvian.chatbot.server.service.core.WorkerCredentialProfileService
import eu.torvian.chatbot.server.service.core.WorkerCredentialSecretService
import eu.torvian.chatbot.server.service.core.error.credential.WorkerCredentialServiceError
import eu.torvian.chatbot.server.service.core.error.credential.toApiError
import eu.torvian.chatbot.server.worker.credential.CredentialSyncService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/**
 * Configures the credential profile and secret endpoints.
 *
 * User-JWT writes answer 200/201 even when the worker push failed: the row is durably stored and the
 * response carries the classified delivery outcome, because answering non-2xx would make a client
 * discard a mutation that cannot be recreated from anything the client still holds.
 *
 * @param credentialSyncService Write orchestration that persists first and dispatches best-effort.
 * @param profileService Read access to stored profiles.
 * @param secretService Read access to stored secret metadata.
 * @param json JSON codec used to decode the exact raw request body after it has been captured for
 *   signature persistence.
 */
fun Route.configureCredentialRoutes(
    credentialSyncService: CredentialSyncService,
    profileService: WorkerCredentialProfileService,
    secretService: WorkerCredentialSecretService,
    json: Json,
) {
    authenticate(AuthSchemes.USER_JWT) {
        post<CredentialProfileResource> {
            val userId = call.getUserId()
            val result = either {
                val (request, signedRequest) =
                    receiveDetachedSignedRequest<CreateCredentialProfileRequest>(json).bind()
                withError({ error: WorkerCredentialServiceError -> error.toApiError() }) {
                    credentialSyncService.createProfile(userId, request, signedRequest).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.Created)
        }

        get<CredentialProfileResource> { resource ->
            val userId = call.getUserId()
            val workerId = resource.workerId
            val result: Either<ApiError, List<CredentialProfileDto>> = if (workerId == null) {
                missingWorkerIdError()
            } else {
                either {
                    withError({ error: WorkerCredentialServiceError -> error.toApiError() }) {
                        profileService.getProfiles(userId, workerId).bind()
                    }
                }
            }
            call.respondEither(result)
        }

        get<CredentialProfileResource.ById> { resource ->
            val userId = call.getUserId()
            val result = either {
                withError({ error: WorkerCredentialServiceError -> error.toApiError() }) {
                    profileService.getProfile(userId, resource.id).bind()
                }
            }
            call.respondEither(result)
        }

        put<CredentialProfileResource.ById> { resource ->
            val userId = call.getUserId()
            val result = either {
                val (request, signedRequest) =
                    receiveDetachedSignedRequest<UpdateCredentialProfileRequest>(json).bind()
                withError({ error: WorkerCredentialServiceError -> error.toApiError() }) {
                    credentialSyncService.updateProfile(userId, resource.id, request, signedRequest).bind()
                }
            }
            call.respondEither(result)
        }

        delete<CredentialProfileResource.ById> { resource ->
            val userId = call.getUserId()
            val result = either {
                withError({ error: WorkerCredentialServiceError -> error.toApiError() }) {
                    credentialSyncService.deleteProfile(userId, resource.id).bind()
                }
            }
            call.respondEither(result)
        }

        post<CredentialSecretResource> {
            val userId = call.getUserId()
            val result = either {
                val request = call.receive<CreateCredentialSecretRequest>()
                withError({ error: WorkerCredentialServiceError -> error.toApiError() }) {
                    credentialSyncService.createSecret(userId, request).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.Created)
        }

        get<CredentialSecretResource> { resource ->
            val userId = call.getUserId()
            val workerId = resource.workerId
            val result: Either<ApiError, List<CredentialSecretMetadataDto>> = if (workerId == null) {
                missingWorkerIdError()
            } else {
                either {
                    withError({ error: WorkerCredentialServiceError -> error.toApiError() }) {
                        secretService.getSecrets(userId, workerId).bind()
                    }
                }
            }
            call.respondEither(result)
        }

        get<CredentialSecretResource.ById> { resource ->
            val userId = call.getUserId()
            val result = either {
                withError({ error: WorkerCredentialServiceError -> error.toApiError() }) {
                    secretService.getSecret(userId, resource.id).bind()
                }
            }
            call.respondEither(result)
        }

        put<CredentialSecretResource.ById> { resource ->
            val userId = call.getUserId()
            val result = either {
                val request = call.receive<UpdateCredentialSecretRequest>()
                withError({ error: WorkerCredentialServiceError -> error.toApiError() }) {
                    credentialSyncService.updateSecret(userId, resource.id, request).bind()
                }
            }
            call.respondEither(result)
        }

        delete<CredentialSecretResource.ById> { resource ->
            val userId = call.getUserId()
            val result = either {
                withError({ error: WorkerCredentialServiceError -> error.toApiError() }) {
                    credentialSyncService.deleteSecret(userId, resource.id).bind()
                }
            }
            call.respondEither(result)
        }
    }

    authenticate(AuthSchemes.WORKER_JWT) {
        get<WorkerCredentialResource.Assigned> {
            val workerId = call.getWorkerId()
            val result = either {
                val profiles = withError({ error: WorkerCredentialServiceError -> error.toApiError() }) {
                    profileService.getAssignedProfiles(workerId).bind()
                }
                val secrets = withError({ error: WorkerCredentialServiceError -> error.toApiError() }) {
                    secretService.getAssignedSecrets(workerId).bind()
                }
                AssignedCredentialsDto(workerId = workerId, profiles = profiles, secrets = secrets)
            }
            call.respondEither(result)
        }
    }
}

/**
 * Builds the response used when a list request omits the worker filter.
 *
 * @return A left value describing the missing required query parameter.
 */
private fun missingWorkerIdError(): Either<ApiError, Nothing> = apiError(
    CommonApiErrorCodes.MISSING_FIELD,
    "The workerId query parameter is required",
    "workerId" to "required"
).left()

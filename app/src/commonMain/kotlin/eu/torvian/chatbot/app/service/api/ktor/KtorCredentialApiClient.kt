package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.CredentialApi
import eu.torvian.chatbot.app.service.security.RequestSigningService
import eu.torvian.chatbot.common.api.resources.CredentialProfileResource
import eu.torvian.chatbot.common.api.resources.CredentialSecretResource
import eu.torvian.chatbot.common.models.api.credential.CreateCredentialProfileRequest
import eu.torvian.chatbot.common.models.api.credential.CreateCredentialSecretRequest
import eu.torvian.chatbot.common.models.api.credential.CredentialDeleteResultDto
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileWriteResultDto
import eu.torvian.chatbot.common.models.api.credential.CredentialSecretMetadataDto
import eu.torvian.chatbot.common.models.api.credential.CredentialSecretWriteResultDto
import eu.torvian.chatbot.common.models.api.credential.UpdateCredentialProfileRequest
import eu.torvian.chatbot.common.models.api.credential.UpdateCredentialSecretRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.resources.delete
import io.ktor.client.plugins.resources.get
import io.ktor.client.plugins.resources.post
import io.ktor.client.plugins.resources.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json

/**
 * Ktor implementation of [CredentialApi].
 *
 * Profile writes reuse the generic detached-signing helpers, so the exact body the server persists is
 * the body that was signed. Secret writes are plain requests: no device signature can exist for a
 * server-produced ciphertext, and the plaintext must not be persisted as a signed payload.
 *
 * @param client Ktor HTTP client used for transport.
 * @param json JSON codec used to serialize signed profile bodies.
 * @param requestSigningService Detached request-signing service used for profile mutations.
 */
class KtorCredentialApiClient(
    client: HttpClient,
    json: Json,
    requestSigningService: RequestSigningService,
) : SignedJsonApiResourceClient(client, json, requestSigningService), CredentialApi {

    override suspend fun getProfiles(workerId: Long): Either<ApiResourceError, List<CredentialProfileDto>> =
        safeApiCall {
            client.get(CredentialProfileResource(workerId = workerId)).body<List<CredentialProfileDto>>()
        }

    override suspend fun getProfile(profileId: Long): Either<ApiResourceError, CredentialProfileDto> =
        safeApiCall {
            client.get(CredentialProfileResource.ById(id = profileId)).body<CredentialProfileDto>()
        }

    override suspend fun createProfile(
        request: CreateCredentialProfileRequest,
    ): Either<ApiResourceError, CredentialProfileWriteResultDto> =
        safeSignedJsonApiCall(request, CreateCredentialProfileRequest.serializer()) { signedRequest ->
            client.post(CredentialProfileResource()) {
                applyDetachedSignedJsonBody(signedRequest)
            }.body<CredentialProfileWriteResultDto>()
        }

    override suspend fun updateProfile(
        profileId: Long,
        request: UpdateCredentialProfileRequest,
    ): Either<ApiResourceError, CredentialProfileWriteResultDto> =
        safeSignedJsonApiCall(request, UpdateCredentialProfileRequest.serializer()) { signedRequest ->
            client.put(CredentialProfileResource.ById(id = profileId)) {
                applyDetachedSignedJsonBody(signedRequest)
            }.body<CredentialProfileWriteResultDto>()
        }

    override suspend fun deleteProfile(profileId: Long): Either<ApiResourceError, CredentialDeleteResultDto> =
        safeApiCall {
            client.delete(CredentialProfileResource.ById(id = profileId)).body<CredentialDeleteResultDto>()
        }

    override suspend fun getSecrets(
        workerId: Long,
    ): Either<ApiResourceError, List<CredentialSecretMetadataDto>> = safeApiCall {
        client.get(CredentialSecretResource(workerId = workerId)).body<List<CredentialSecretMetadataDto>>()
    }

    override suspend fun createSecret(
        request: CreateCredentialSecretRequest,
    ): Either<ApiResourceError, CredentialSecretWriteResultDto> = safeApiCall {
        client.post(CredentialSecretResource()) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body<CredentialSecretWriteResultDto>()
    }

    override suspend fun updateSecret(
        secretId: Long,
        request: UpdateCredentialSecretRequest,
    ): Either<ApiResourceError, CredentialSecretWriteResultDto> = safeApiCall {
        client.put(CredentialSecretResource.ById(id = secretId)) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body<CredentialSecretWriteResultDto>()
    }

    override suspend fun deleteSecret(secretId: Long): Either<ApiResourceError, CredentialDeleteResultDto> =
        safeApiCall {
            client.delete(CredentialSecretResource.ById(id = secretId)).body<CredentialDeleteResultDto>()
        }
}

package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.WorkerResource
import eu.torvian.chatbot.common.models.api.worker.RegisterWorkerRequest
import eu.torvian.chatbot.common.models.api.worker.RegisterWorkerResponse
import eu.torvian.chatbot.common.models.api.worker.UpdateWorkerRequest
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.WorkerService
import eu.torvian.chatbot.server.service.core.error.worker.DeleteWorkerError
import eu.torvian.chatbot.server.service.core.error.worker.RegisterWorkerError
import eu.torvian.chatbot.server.service.core.error.worker.UpdateWorkerError
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.patch
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * Configures worker lifecycle routes (registration, update, delete).
 *
 * Delegation/job endpoints are intentionally excluded for now.
 *
 * @param workerService Service containing worker registration/activation logic.
 */
fun Route.configureWorkerRoutes(
    workerService: WorkerService
) {
    // Registration is user-initiated and creates a worker identity.
    authenticate(AuthSchemes.USER_JWT) {
        get<WorkerResource> {
            val ownerUserId = call.getUserId()
            call.respond(workerService.listWorkersByOwner(ownerUserId))
        }

        post<WorkerResource.Register> {
            val request = call.receive<RegisterWorkerRequest>()
            val ownerUserId = call.getUserId()
            call.respondEither(
                workerService.registerWorker(
                    ownerUserId = ownerUserId,
                    workerUid = request.workerUid,
                    displayName = request.displayName,
                    certificatePem = request.certificatePem,
                    allowedScopes = request.allowedScopes
                ).map { worker -> RegisterWorkerResponse(worker = worker) }
                    .mapLeft { it.toApiError() },
                HttpStatusCode.Created
            )
        }

        patch<WorkerResource.Id> { resource ->
            val request = call.receive<UpdateWorkerRequest>()
            val ownerUserId = call.getUserId()
            call.respondEither(
                workerService.updateWorker(
                    ownerUserId = ownerUserId,
                    workerId = resource.id,
                    displayName = request.displayName,
                    allowedScopes = request.allowedScopes
                ).mapLeft { it.toApiError() }
            )
        }

        delete<WorkerResource.Id> { resource ->
            val ownerUserId = call.getUserId()
            call.respondEither(
                workerService.deleteWorker(
                    ownerUserId = ownerUserId,
                    workerId = resource.id
                ).mapLeft { it.toApiError() },
                HttpStatusCode.NoContent
            )
        }
    }
}

private fun RegisterWorkerError.toApiError(): ApiError = when (this) {
    is RegisterWorkerError.InvalidInput,
    is RegisterWorkerError.InvalidCertificate ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid worker registration", "reason" to toString())

    is RegisterWorkerError.CertificateAlreadyRegistered ->
        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "Certificate already registered")

    is RegisterWorkerError.WorkerUidAlreadyRegistered ->
        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "Worker UID already registered")
}

private fun UpdateWorkerError.toApiError(): ApiError = when (this) {
    is UpdateWorkerError.NotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Worker not found", "workerId" to workerId.toString())

    is UpdateWorkerError.Forbidden ->
        apiError(CommonApiErrorCodes.PERMISSION_DENIED, "You do not own this worker", "workerId" to workerId.toString())
}

private fun DeleteWorkerError.toApiError(): ApiError = when (this) {
    is DeleteWorkerError.NotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Worker not found", "workerId" to workerId.toString())

    is DeleteWorkerError.Forbidden ->
        apiError(CommonApiErrorCodes.PERMISSION_DENIED, "You do not own this worker", "workerId" to workerId.toString())
}

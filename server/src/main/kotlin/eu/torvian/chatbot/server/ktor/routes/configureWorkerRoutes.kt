package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.WorkerResource
import eu.torvian.chatbot.common.models.api.worker.RegisterWorkerRequest
import eu.torvian.chatbot.common.models.api.worker.RegisterWorkerResponse
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.WorkerService
import eu.torvian.chatbot.server.service.core.error.worker.RegisterWorkerError
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.routing.Route

/**
 * Configures worker lifecycle routes (registration only).
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
        post<WorkerResource.Register> {
            val request = call.receive<RegisterWorkerRequest>()
            val ownerUserId = call.getUserId()
            call.respondEither(
                workerService.registerWorker(
                    ownerUserId = ownerUserId,
                    displayName = request.displayName,
                    certificatePem = request.certificatePem,
                    allowedScopes = request.allowedScopes
                ).map { worker ->
                    RegisterWorkerResponse(worker = worker)
                },
                HttpStatusCode.Created
            ) { error ->
                when (error) {
                    is RegisterWorkerError.InvalidInput,
                    is RegisterWorkerError.InvalidCertificate ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid worker registration", "reason" to error.toString())

                    is RegisterWorkerError.CertificateAlreadyRegistered ->
                        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "Certificate already registered")
                }
            }
        }
    }
}



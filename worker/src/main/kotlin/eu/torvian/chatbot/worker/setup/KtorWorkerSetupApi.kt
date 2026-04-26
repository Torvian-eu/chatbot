package eu.torvian.chatbot.worker.setup

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.api.resources.AuthResource
import eu.torvian.chatbot.common.api.resources.WorkerResource
import eu.torvian.chatbot.common.models.api.auth.LoginRequest
import eu.torvian.chatbot.common.models.api.auth.LoginResponse
import eu.torvian.chatbot.common.models.api.worker.RegisterWorkerRequest
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import io.ktor.client.plugins.logging.Logger as KtorLogger

/**
 * Ktor-backed setup API implementation.
 *
 * This implementation talks to the server's authentication and worker-registration
 * endpoints using a type-safe Ktor client.
 *
 * @property client HTTP client configured for the worker setup base URL.
 */
class KtorWorkerSetupApi(
    private val client: HttpClient
) : WorkerSetupApi {

    override suspend fun login(username: String, password: String): Either<WorkerSetupError, String> {
        logger.debug("Logging in worker setup user {}", username)
        return execute("login") {
            client.post(AuthResource.Login()) {
                setBody(LoginRequest(username = username, password = password))
            }.body<LoginResponse>().accessToken
        }.mapLeft { error ->
            when (error) {
                is WorkerSetupError.UnexpectedHttpStatus -> {
                    if (error.statusCode == 401) {
                        WorkerSetupError.LoginFailed("Invalid credentials")
                    } else {
                        error
                    }
                }

                else -> error
            }
        }
    }

    override suspend fun registerWorker(
        accessToken: String,
        workerUid: String,
        displayName: String,
        certificatePem: String
    ): Either<WorkerSetupError, Unit> {
        logger.debug("Registering worker during setup (workerUid={}, displayName={})", workerUid, displayName)
        return execute("register worker") {
            client.post(WorkerResource.Register()) {
                bearerAuth(accessToken)
                setBody(
                    RegisterWorkerRequest(
                        workerUid = workerUid,
                        displayName = displayName,
                        certificatePem = certificatePem
                    )
                )
            }.body<Unit>()
        }.mapLeft { error ->
            when (error) {
                is WorkerSetupError.UnexpectedHttpStatus -> WorkerSetupError.WorkerRegistrationFailed(
                    "Unexpected status ${error.statusCode} during ${error.operation}${error.description?.let { ": $it" } ?: ""}"
                )

                is WorkerSetupError.TransportFailure -> WorkerSetupError.WorkerRegistrationFailed(
                    "Transport failure during ${error.operation}: ${error.reason}"
                )

                else -> error
            }
        }
    }

    override suspend fun logout(accessToken: String): Either<WorkerSetupError, Unit> {
        logger.debug("Logging out setup session")
        return execute("logout") {
            client.post(AuthResource.Logout()) {
                bearerAuth(accessToken)
            }.body<Unit>()
        }.mapLeft { error ->
            when (error) {
                is WorkerSetupError.UnexpectedHttpStatus -> WorkerSetupError.LogoutFailed(
                    "Unexpected status ${error.statusCode} during ${error.operation}${error.description?.let { ": $it" } ?: ""}"
                )

                is WorkerSetupError.TransportFailure -> WorkerSetupError.LogoutFailed(
                    "Transport failure during ${error.operation}: ${error.reason}"
                )

                else -> error
            }
        }
    }

    override fun close() {
        client.close()
    }

    /**
     * Executes a setup API call and translates transport or HTTP failures into logical errors.
     *
     * @param operation Human-readable operation label used in diagnostics.
     * @param block Suspended HTTP call to execute.
     * @return Either a logical setup error or the successful response payload.
     */
    private suspend fun <T> execute(operation: String, block: suspend () -> T): Either<WorkerSetupError, T> {
        return try {
            block().right()
        } catch (e: ResponseException) {
            val bodyText = runCatching { e.response.bodyAsText() }.getOrNull()
            logger.warn("HTTP failure during {} (status={})", operation, e.response.status.value)
            WorkerSetupError.UnexpectedHttpStatus(
                operation = operation,
                statusCode = e.response.status.value,
                description = bodyText?.takeIf { it.isNotBlank() }
            ).left()
        } catch (e: Exception) {
            logger.warn("Unexpected transport failure during {}", operation, e)
            WorkerSetupError.TransportFailure(operation, e.message ?: e::class.simpleName.orEmpty()).left()
        }
    }

    companion object {
        private val logger: Logger = LogManager.getLogger(KtorWorkerSetupApi::class.java)
    }
}

/**
 * Creates a Ktor HTTP client for worker setup calls.
 *
 * The client is configured with JSON support, the resources plugin, and the provided
 * server base URL.
 *
 * @param serverBaseUrl Base URL of the target server.
 * @return Configured Ktor HTTP client ready for worker setup calls.
 */
fun createWorkerSetupHttpClient(serverBaseUrl: String): HttpClient {
    return HttpClient(OkHttp) {
        expectSuccess = true
        install(Resources)
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(Logging) {
            logger = object : KtorLogger {
                override fun log(message: String) {
                    setupHttpClientLogger.debug(message)
                }
            }
            level = LogLevel.INFO
        }
        defaultRequest {
            url(serverBaseUrl)
            contentType(ContentType.Application.Json)
        }
    }
}

private val setupHttpClientLogger: Logger = LogManager.getLogger("WorkerSetupHttpClient")



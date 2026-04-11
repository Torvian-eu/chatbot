package eu.torvian.chatbot.worker.koin

import eu.torvian.chatbot.worker.auth.ChallengeSigner
import eu.torvian.chatbot.worker.auth.FileServiceTokenStore
import eu.torvian.chatbot.worker.auth.KtorWorkerAuthApi
import eu.torvian.chatbot.worker.auth.PemChallengeSigner
import eu.torvian.chatbot.worker.auth.ServiceTokenStore
import eu.torvian.chatbot.worker.auth.WorkerAuthApi
import eu.torvian.chatbot.worker.auth.WorkerAuthManager
import eu.torvian.chatbot.worker.auth.WorkerAuthManagerImpl
import eu.torvian.chatbot.worker.config.WorkerRuntimeConfig
import eu.torvian.chatbot.worker.runtime.WorkerRuntime
import eu.torvian.chatbot.worker.runtime.WorkerRuntimeImpl
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.resources.Resources
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger as Log4jLogger
import org.koin.dsl.module
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * Koin module that wires worker runtime dependencies.
 *
 * @param config Loaded worker runtime config.
 * @param tokenPath Resolved token cache path.
 * @param privateKeyPem Private-key PEM used for challenge signing.
 */
fun workerModule(
    config: WorkerRuntimeConfig,
    tokenPath: Path,
    privateKeyPem: String
) = module {
    single<HttpClient> { createWorkerHttpClient(config.serverBaseUrl) }
    single<ServiceTokenStore> { FileServiceTokenStore(tokenPath) }
    single<WorkerAuthApi> { KtorWorkerAuthApi(get()) }
    single<ChallengeSigner> { PemChallengeSigner(privateKeyPem) }
    single<WorkerAuthManager> {
        WorkerAuthManagerImpl(
            workerId = config.workerId,
            certificateFingerprint = config.certificateFingerprint,
            refreshSkew = config.refreshSkewSeconds.seconds,
            tokenStore = get(),
            authApi = get(),
            signer = get()
        )
    }
    single<WorkerRuntime> {
        WorkerRuntimeImpl(
            workerId = config.workerId,
            refreshSkew = config.refreshSkewSeconds.seconds,
            authManager = get()
        )
    }
}

/**
 * Creates a Ktor [HttpClient] configured for worker authentication API calls.
 *
 * Configured with:
 * - OkHTTP engine
 * - JSON content negotiation
 * - Resources plugin for type-safe requests
 * - Logging of requests and responses at INFO level
 *
 * @param serverBaseUrl Base URL of the server to target with auth requests.
 */
private fun createWorkerHttpClient(serverBaseUrl: String): HttpClient {
    return HttpClient(OkHttp) {
        // Enable automatic throwing of exceptions for non-successful HTTP responses
        expectSuccess = true

        // Install the Resources plugin for type-safe API calls
        install(Resources)

        // Configure content negotiation to use JSON with lenient parsing
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }

        // Add logging for debugging API calls
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    workerLogger.debug(message)
                }
            }
            level = LogLevel.INFO
        }

        // Set default request parameters to target the server base URL and use JSON content type
        defaultRequest {
            url(serverBaseUrl)
            contentType(ContentType.Application.Json)
        }
    }
}

private val workerLogger: Log4jLogger = LogManager.getLogger("WorkerKoinModule")


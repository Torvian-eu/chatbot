package eu.torvian.chatbot.worker.koin

import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolCommandTypes
import eu.torvian.chatbot.worker.auth.ChallengeSigner
import eu.torvian.chatbot.worker.auth.FileServiceTokenStore
import eu.torvian.chatbot.worker.auth.KtorWorkerAuthApi
import eu.torvian.chatbot.worker.auth.PemChallengeSigner
import eu.torvian.chatbot.worker.auth.ServiceTokenStore
import eu.torvian.chatbot.worker.auth.WorkerAuthApi
import eu.torvian.chatbot.worker.auth.WorkerAuthManager
import eu.torvian.chatbot.worker.auth.WorkerAuthManagerImpl
import eu.torvian.chatbot.worker.config.WorkerRuntimeConfig
import eu.torvian.chatbot.worker.mcp.NotConfiguredWorkerMcpToolCallGateway
import eu.torvian.chatbot.worker.mcp.WorkerMcpToolCallGateway
import eu.torvian.chatbot.worker.mcp.WorkerToolCallExecutor
import eu.torvian.chatbot.worker.mcp.WorkerToolCallExecutorImpl
import eu.torvian.chatbot.worker.protocol.factory.WorkerMcpToolCallInteractionFactory
import eu.torvian.chatbot.worker.protocol.factory.WorkerToolCallInteractionFactory
import eu.torvian.chatbot.worker.protocol.handshake.InMemoryWorkerSessionHandshakeStateStore
import eu.torvian.chatbot.worker.protocol.handshake.WorkerSessionHandshakeStateStore
import eu.torvian.chatbot.worker.protocol.handshake.WorkerSessionHelloStarter
import eu.torvian.chatbot.worker.protocol.ids.UuidWorkerInteractionIdProvider
import eu.torvian.chatbot.worker.protocol.ids.UuidWorkerMessageIdProvider
import eu.torvian.chatbot.worker.protocol.ids.WorkerInteractionIdProvider
import eu.torvian.chatbot.worker.protocol.ids.WorkerMessageIdProvider
import eu.torvian.chatbot.worker.protocol.registry.InMemoryWorkerActiveInteractionRegistry
import eu.torvian.chatbot.worker.protocol.registry.WorkerActiveInteractionRegistry
import eu.torvian.chatbot.worker.protocol.routing.WorkerCommandMessageHandler
import eu.torvian.chatbot.worker.protocol.routing.WorkerCommandRequestProcessor
import eu.torvian.chatbot.worker.protocol.routing.WorkerIncomingMessageProcessor
import eu.torvian.chatbot.worker.protocol.routing.WorkerProtocolMessageRouter
import eu.torvian.chatbot.worker.protocol.transport.WorkerOutboundMessageEmitter
import eu.torvian.chatbot.worker.protocol.transport.WorkerOutboundMessageEmitterHolder
import eu.torvian.chatbot.worker.protocol.transport.WorkerWebSocketConnectionLoop
import eu.torvian.chatbot.worker.protocol.transport.WorkerWebSocketMessageCodec
import eu.torvian.chatbot.worker.protocol.transport.WorkerWebSocketSessionRunner
import eu.torvian.chatbot.worker.protocol.transport.WorkerWebSocketTransportConfig
import eu.torvian.chatbot.worker.protocol.transport.WorkerTransportConnectionLoopRunner
import eu.torvian.chatbot.worker.runtime.WorkerRuntime
import eu.torvian.chatbot.worker.runtime.WorkerRuntimeImpl
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager
import org.koin.dsl.module
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import org.apache.logging.log4j.Logger as Log4jLogger

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
            workerUid = config.workerUid,
            certificateFingerprint = config.certificateFingerprint,
            refreshSkew = config.refreshSkewSeconds.seconds,
            tokenStore = get(),
            authApi = get(),
            signer = get()
        )
    }
    single<Json> { Json { ignoreUnknownKeys = true } }
    single<WorkerMessageIdProvider> { UuidWorkerMessageIdProvider() }
    single<WorkerInteractionIdProvider> { UuidWorkerInteractionIdProvider() }
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<WorkerMcpToolCallGateway> { NotConfiguredWorkerMcpToolCallGateway() }
    single<WorkerToolCallExecutor> { WorkerToolCallExecutorImpl(gateway = get(), json = get()) }
    single<WorkerOutboundMessageEmitterHolder> { WorkerOutboundMessageEmitterHolder() }
    single<WorkerOutboundMessageEmitter> { get<WorkerOutboundMessageEmitterHolder>() }
    single<WorkerWebSocketTransportConfig> {
        WorkerWebSocketTransportConfig.fromServerBaseUrl(
            serverBaseUrl = config.serverBaseUrl,
            workerUid = config.workerUid
        )
    }
    single<WorkerWebSocketMessageCodec> { WorkerWebSocketMessageCodec() }
    single<WorkerWebSocketSessionRunner> {
        WorkerWebSocketSessionRunner(
            client = get(),
            transportConfig = get(),
            codec = get(),
            outboundEmitterHolder = get(),
            helloStarter = get(),
            incomingMessageProcessor = get()
        )
    }
    single<WorkerWebSocketConnectionLoop> {
        WorkerWebSocketConnectionLoop(
            authManager = get(),
            sessionRunner = get(),
            transportConfig = get()
        )
    }
    single<WorkerTransportConnectionLoopRunner> { get<WorkerWebSocketConnectionLoop>() }
    single<WorkerActiveInteractionRegistry> { InMemoryWorkerActiveInteractionRegistry() }
    single<WorkerSessionHandshakeStateStore> { InMemoryWorkerSessionHandshakeStateStore() }
    single<WorkerSessionHelloStarter> {
        WorkerSessionHelloStarter(
            interactionScope = get(),
            registry = get(),
            interactionIdProvider = get(),
            emitter = get(),
            handshakeStateStore = get(),
            messageIdProvider = get()
        )
    }
    single<WorkerMcpToolCallInteractionFactory> {
        WorkerMcpToolCallInteractionFactory(
            toolCallExecutor = get(),
            messageIdProvider = get()
        )
    }
    single<WorkerToolCallInteractionFactory> {
        WorkerToolCallInteractionFactory(
            messageIdProvider = get()
        )
    }
    single<WorkerCommandRequestProcessor> {
        WorkerCommandRequestProcessor(
            interactionScope = get(),
            interactionFactoriesByCommandType = mapOf(
                WorkerProtocolCommandTypes.TOOL_CALL to get<WorkerToolCallInteractionFactory>(),
                WorkerProtocolCommandTypes.MCP_TOOL_CALL to get<WorkerMcpToolCallInteractionFactory>()
            ),
            emitter = get(),
            registry = get(),
            messageIdProvider = get()
        )
    }
    single<WorkerCommandMessageHandler> {
        WorkerCommandMessageHandler(
            registry = get(),
            emitter = get(),
            messageIdProvider = get()
        )
    }
    single<WorkerProtocolMessageRouter> {
        WorkerProtocolMessageRouter(
            registry = get(),
            commandRequestProcessor = get<WorkerCommandRequestProcessor>(),
            commandMessageHandler = get(),
            emitter = get(),
            messageIdProvider = get()
        )
    }
    single<WorkerIncomingMessageProcessor> { get<WorkerProtocolMessageRouter>() }
    single<WorkerRuntime> {
        WorkerRuntimeImpl(
            workerUid = config.workerUid,
            connectionLoop = get()
        )
    }
}

/**
 * Creates a Ktor [HttpClient] configured for worker auth and worker-protocol transport calls.
 *
 * Configured with:
 * - OkHTTP engine
 * - JSON content negotiation
 * - Resources plugin for type-safe requests
 * - WebSockets plugin for worker protocol transport
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

        // Enable WebSocket support for worker protocol transport sessions.
        install(WebSockets)

        // Set default request parameters to target the server base URL and use JSON content type
        defaultRequest {
            url(serverBaseUrl)
            contentType(ContentType.Application.Json)
        }
    }
}

private val workerLogger: Log4jLogger = LogManager.getLogger("WorkerKoinModule")

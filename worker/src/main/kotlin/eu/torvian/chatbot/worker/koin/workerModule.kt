package eu.torvian.chatbot.worker.koin

import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolCommandTypes
import eu.torvian.chatbot.worker.auth.ChallengeSigner
import eu.torvian.chatbot.worker.auth.DefaultWorkerAuthenticatedRequestExecutor
import eu.torvian.chatbot.worker.auth.FileServiceTokenStore
import eu.torvian.chatbot.worker.auth.KtorWorkerAuthApi
import eu.torvian.chatbot.worker.auth.PemChallengeSigner
import eu.torvian.chatbot.worker.auth.ServiceTokenStore
import eu.torvian.chatbot.worker.auth.WorkerAuthApi
import eu.torvian.chatbot.worker.auth.WorkerAuthManager
import eu.torvian.chatbot.worker.auth.WorkerAuthManagerImpl
import eu.torvian.chatbot.worker.auth.WorkerAuthenticatedRequestExecutor
import eu.torvian.chatbot.worker.config.WorkerRuntimeConfig
import eu.torvian.chatbot.worker.mcp.InMemoryMcpServerConfigStore
import eu.torvian.chatbot.worker.mcp.JvmMcpProcessManager
import eu.torvian.chatbot.worker.mcp.McpRuntimeService
import eu.torvian.chatbot.worker.mcp.McpRuntimeServiceImpl
import eu.torvian.chatbot.worker.mcp.McpServerConfigStore
import eu.torvian.chatbot.worker.mcp.McpProcessManager
import eu.torvian.chatbot.worker.mcp.McpClientService
import eu.torvian.chatbot.worker.mcp.McpClientServiceImpl
import eu.torvian.chatbot.worker.mcp.McpRuntimeCommandExecutor
import eu.torvian.chatbot.worker.mcp.McpRuntimeCommandExecutorImpl
import eu.torvian.chatbot.worker.mcp.McpToolCallExecutor
import eu.torvian.chatbot.worker.mcp.McpToolCallExecutorImpl
import eu.torvian.chatbot.worker.mcp.api.AssignedConfigBootstrapper
import eu.torvian.chatbot.worker.mcp.api.KtorWorkerMcpServerApi
import eu.torvian.chatbot.worker.mcp.api.WorkerMcpServerApi
import eu.torvian.chatbot.worker.protocol.factory.McpRuntimeCommandInteractionFactory
import eu.torvian.chatbot.worker.protocol.factory.McpToolCallInteractionFactory
import eu.torvian.chatbot.worker.protocol.factory.ToolCallInteractionFactory
import eu.torvian.chatbot.worker.protocol.handshake.HelloStarter
import eu.torvian.chatbot.worker.protocol.handshake.InMemorySessionHandshakeContext
import eu.torvian.chatbot.worker.protocol.handshake.SessionHandshakeContext
import eu.torvian.chatbot.worker.protocol.ids.UuidInteractionIdProvider
import eu.torvian.chatbot.worker.protocol.ids.UuidMessageIdProvider
import eu.torvian.chatbot.worker.protocol.ids.InteractionIdProvider
import eu.torvian.chatbot.worker.protocol.ids.MessageIdProvider
import eu.torvian.chatbot.worker.protocol.registry.InMemoryInteractionRegistry
import eu.torvian.chatbot.worker.protocol.registry.InteractionRegistry
import eu.torvian.chatbot.worker.protocol.routing.CommandRequestProcessor
import eu.torvian.chatbot.worker.protocol.routing.IncomingMessageProcessor
import eu.torvian.chatbot.worker.protocol.routing.WorkerProtocolMessageRouter
import eu.torvian.chatbot.worker.protocol.transport.OutboundMessageEmitter
import eu.torvian.chatbot.worker.protocol.transport.OutboundMessageEmitterHolder
import eu.torvian.chatbot.worker.protocol.transport.WebSocketConnectionLoop
import eu.torvian.chatbot.worker.protocol.transport.WebSocketMessageCodec
import eu.torvian.chatbot.worker.protocol.transport.WebSocketSessionRunner
import eu.torvian.chatbot.worker.protocol.transport.WebSocketTransportConfig
import eu.torvian.chatbot.worker.protocol.transport.TransportConnectionLoopRunner
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
    single<MessageIdProvider> { UuidMessageIdProvider() }
    single<InteractionIdProvider> { UuidInteractionIdProvider() }
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<McpServerConfigStore> { InMemoryMcpServerConfigStore() }
    single<McpProcessManager> { JvmMcpProcessManager() }
    single<WorkerAuthenticatedRequestExecutor> {
        DefaultWorkerAuthenticatedRequestExecutor(
            authManager = get()
        )
    }
    single<WorkerMcpServerApi> {
        KtorWorkerMcpServerApi(
            client = get(),
            authenticatedRequestExecutor = get()
        )
    }
    single<AssignedConfigBootstrapper> {
        AssignedConfigBootstrapper(
            mcpServerApi = get(),
            configStore = get()
        )
    }
    single<McpClientService> { McpClientServiceImpl(processManager = get()) }
    single<McpRuntimeService> {
        McpRuntimeServiceImpl(
            configStore = get(),
            clientService = get(),
            processManager = get()
        )
    }
    single<McpToolCallExecutor> { McpToolCallExecutorImpl(runtimeService = get(), json = get()) }
    single<McpRuntimeCommandExecutor> {
        McpRuntimeCommandExecutorImpl(
            runtimeService = get(),
            configStore = get()
        )
    }
    single<OutboundMessageEmitterHolder> { OutboundMessageEmitterHolder() }
    single<OutboundMessageEmitter> { get<OutboundMessageEmitterHolder>() }
    single<WebSocketTransportConfig> {
        WebSocketTransportConfig.fromServerBaseUrl(
            serverBaseUrl = config.serverBaseUrl,
            workerUid = config.workerUid
        )
    }
    single<WebSocketMessageCodec> { WebSocketMessageCodec() }
    single<WebSocketSessionRunner> {
        WebSocketSessionRunner(
            client = get(),
            transportConfig = get(),
            codec = get(),
            outboundEmitterHolder = get(),
            helloStarter = get(),
            handshakeContext = get(),
            incomingMessageProcessor = get()
        )
    }
    single<WebSocketConnectionLoop> {
        WebSocketConnectionLoop(
            authManager = get(),
            sessionRunner = get(),
            bootstrapper = get(),
            transportConfig = get()
        )
    }
    single<TransportConnectionLoopRunner> { get<WebSocketConnectionLoop>() }
    single<InteractionRegistry> { InMemoryInteractionRegistry() }
    single<SessionHandshakeContext> { InMemorySessionHandshakeContext() }
    single<HelloStarter> {
        HelloStarter(
            interactionScope = get(),
            registry = get(),
            interactionIdProvider = get(),
            emitter = get(),
            handshakeContext = get(),
            messageIdProvider = get()
        )
    }
    single<McpToolCallInteractionFactory> {
        McpToolCallInteractionFactory(
            toolCallExecutor = get(),
            messageIdProvider = get()
        )
    }
    single<McpRuntimeCommandInteractionFactory> {
        McpRuntimeCommandInteractionFactory(
            executor = get(),
            messageIdProvider = get()
        )
    }
    single<ToolCallInteractionFactory> {
        ToolCallInteractionFactory(
            messageIdProvider = get()
        )
    }
    single<CommandRequestProcessor> {
        CommandRequestProcessor(
            interactionScope = get(),
            interactionFactoriesByCommandType = mapOf(
                WorkerProtocolCommandTypes.TOOL_CALL to get<ToolCallInteractionFactory>(),
                WorkerProtocolCommandTypes.MCP_TOOL_CALL to get<McpToolCallInteractionFactory>(),
                WorkerProtocolCommandTypes.MCP_SERVER_START to get<McpRuntimeCommandInteractionFactory>(),
                WorkerProtocolCommandTypes.MCP_SERVER_STOP to get<McpRuntimeCommandInteractionFactory>(),
                WorkerProtocolCommandTypes.MCP_SERVER_TEST_CONNECTION to get<McpRuntimeCommandInteractionFactory>(),
                WorkerProtocolCommandTypes.MCP_SERVER_TEST_DRAFT_CONNECTION to get<McpRuntimeCommandInteractionFactory>(),
                WorkerProtocolCommandTypes.MCP_SERVER_DISCOVER_TOOLS to get<McpRuntimeCommandInteractionFactory>(),
                WorkerProtocolCommandTypes.MCP_SERVER_GET_RUNTIME_STATUS to get<McpRuntimeCommandInteractionFactory>(),
                WorkerProtocolCommandTypes.MCP_SERVER_LIST_RUNTIME_STATUSES to get<McpRuntimeCommandInteractionFactory>(),
                WorkerProtocolCommandTypes.MCP_SERVER_CREATE to get<McpRuntimeCommandInteractionFactory>(),
                WorkerProtocolCommandTypes.MCP_SERVER_UPDATE to get<McpRuntimeCommandInteractionFactory>(),
                WorkerProtocolCommandTypes.MCP_SERVER_DELETE to get<McpRuntimeCommandInteractionFactory>()
            ),
            emitter = get(),
            registry = get(),
            messageIdProvider = get()
        )
    }
    single<WorkerProtocolMessageRouter> {
        WorkerProtocolMessageRouter(
            registry = get(),
            handshakeContext = get(),
            commandRequestProcessor = get<CommandRequestProcessor>(),
            emitter = get(),
            messageIdProvider = get()
        )
    }
    single<IncomingMessageProcessor> { get<WorkerProtocolMessageRouter>() }
    single<WorkerRuntime> {
        WorkerRuntimeImpl(
            workerUid = config.workerUid,
            connectionLoop = get(),
            mcpClientService = get()
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

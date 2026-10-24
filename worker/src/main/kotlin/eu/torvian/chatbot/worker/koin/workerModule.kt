package eu.torvian.chatbot.worker.koin

import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolCommandTypes
import eu.torvian.chatbot.common.security.AsymmetricCryptoProvider
import eu.torvian.chatbot.common.security.JvmAsymmetricCryptoProvider
import eu.torvian.chatbot.worker.auth.*
import eu.torvian.chatbot.worker.builtin.*
import eu.torvian.chatbot.worker.builtin.command.ChildEnvironmentProvider
import eu.torvian.chatbot.worker.builtin.command.CommandIsolationResolution
import eu.torvian.chatbot.worker.builtin.command.CommandLauncher
import eu.torvian.chatbot.worker.builtin.command.CommandTerminator
import eu.torvian.chatbot.worker.builtin.command.childEnvironmentProvider
import eu.torvian.chatbot.worker.builtin.impl.*
import eu.torvian.chatbot.worker.builtin.net.DnsResolver
import eu.torvian.chatbot.worker.builtin.net.JvmDnsResolver
import eu.torvian.chatbot.worker.builtin.net.KtorWebFetchService
import eu.torvian.chatbot.worker.builtin.net.PublicUrlValidator
import eu.torvian.chatbot.worker.builtin.net.WebFetchService
import eu.torvian.chatbot.worker.builtin.terminal.TerminalSessionManager
import eu.torvian.chatbot.worker.builtin.terminal.secret.SecretMaterialFactory
import eu.torvian.chatbot.worker.config.RuntimeConfig
import eu.torvian.chatbot.worker.config.TrustedSigner
import eu.torvian.chatbot.worker.credential.CredentialProfileValidator
import eu.torvian.chatbot.worker.credential.CredentialSecretDecryptor
import eu.torvian.chatbot.worker.credential.CredentialSyncCommandExecutor
import eu.torvian.chatbot.worker.credential.CredentialSyncCommandExecutorImpl
import eu.torvian.chatbot.worker.credential.DefaultCredentialProfileValidator
import eu.torvian.chatbot.worker.credential.DefaultCredentialSecretDecryptor
import eu.torvian.chatbot.worker.credential.HelperSecretTreeFactory
import eu.torvian.chatbot.worker.credential.InMemoryWorkerCredentialStore
import eu.torvian.chatbot.worker.credential.SecretTreeFactory
import eu.torvian.chatbot.worker.credential.WorkerCredentialStore
import eu.torvian.chatbot.worker.credential.api.AssignedCredentialBootstrapper
import eu.torvian.chatbot.worker.credential.api.KtorWorkerCredentialApi
import eu.torvian.chatbot.worker.credential.api.WorkerCredentialApi
import eu.torvian.chatbot.worker.mcp.*
import eu.torvian.chatbot.worker.mcp.api.AssignedConfigBootstrapper
import eu.torvian.chatbot.worker.mcp.api.KtorWorkerMcpServerApi
import eu.torvian.chatbot.worker.mcp.api.WorkerMcpServerApi
import eu.torvian.chatbot.worker.protocol.factory.CredentialSyncCommandInteractionFactory
import eu.torvian.chatbot.worker.protocol.factory.McpRuntimeCommandInteractionFactory
import eu.torvian.chatbot.worker.protocol.factory.McpToolCallInteractionFactory
import eu.torvian.chatbot.worker.protocol.factory.ToolCallInteractionFactory
import eu.torvian.chatbot.worker.protocol.handshake.HelloStarter
import eu.torvian.chatbot.worker.protocol.handshake.InMemorySessionHandshakeContext
import eu.torvian.chatbot.worker.protocol.handshake.SessionHandshakeContext
import eu.torvian.chatbot.worker.protocol.ids.InteractionIdProvider
import eu.torvian.chatbot.worker.protocol.ids.MessageIdProvider
import eu.torvian.chatbot.worker.protocol.ids.UuidInteractionIdProvider
import eu.torvian.chatbot.worker.protocol.ids.UuidMessageIdProvider
import eu.torvian.chatbot.worker.protocol.registry.InMemoryInteractionRegistry
import eu.torvian.chatbot.worker.protocol.registry.InteractionRegistry
import eu.torvian.chatbot.worker.protocol.routing.CommandRequestProcessor
import eu.torvian.chatbot.worker.protocol.routing.IncomingMessageProcessor
import eu.torvian.chatbot.worker.protocol.routing.WorkerProtocolMessageRouter
import eu.torvian.chatbot.worker.protocol.transport.*
import eu.torvian.chatbot.worker.runtime.WorkerRuntime
import eu.torvian.chatbot.worker.runtime.WorkerRuntimeImpl
import eu.torvian.chatbot.worker.service.api.WorkerMetadataService
import eu.torvian.chatbot.worker.service.security.DefaultVerificationService
import eu.torvian.chatbot.worker.service.security.VerificationService
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
import org.koin.dsl.onClose
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration.Companion.seconds
import org.apache.logging.log4j.Logger as Log4jLogger

/**
 * Koin module that wires worker runtime dependencies.
 *
 * @param config Loaded worker runtime config.
 * @param tokenPath Resolved token cache path.
 * @param privateKeyPem Private-key PEM used for challenge signing.
 * @param commandIsolation Isolation state resolved once at startup; decides how built-in tools
 *   spawn children, which environment they receive and how their process tree is terminated.
 */
fun workerModule(
    config: RuntimeConfig,
    tokenPath: Path,
    privateKeyPem: String,
    commandIsolation: CommandIsolationResolution,
) = module {
    single<RuntimeConfig> { config }
    single<CommandLauncher> { commandIsolation.launcher }
    single<ChildEnvironmentProvider> {
        childEnvironmentProvider(
            config = config.commandIsolation.environment,
            resolution = commandIsolation,
        )
    }
    single<CommandTerminator> { commandIsolation.terminator }
    single<List<TrustedSigner>> { get<RuntimeConfig>().trustedSigners }
    single<AsymmetricCryptoProvider> { JvmAsymmetricCryptoProvider() }
    single<VerificationService> {
        DefaultVerificationService(
            trustedSigners = get(),
            cryptoProvider = get()
        )
    }
    single<HttpClient> { createWorkerHttpClient(config.server.baseUrl) }
    single<WorkerMetadataService> { WorkerMetadataService(get()) }
    single<ServiceTokenStore> { FileServiceTokenStore(tokenPath) }
    single<WorkerAuthApi> { KtorWorkerAuthApi(get()) }
    single<ChallengeSigner> { PemChallengeSigner(privateKeyPem) }
    single<WorkerAuthManager> {
        WorkerAuthManagerImpl(
            workerUid = config.identity.uid,
            certificateFingerprint = config.identity.certificateFingerprint,
            refreshSkew = config.auth.refreshSkewSeconds.seconds,
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

    // Credential profiles and encrypted secrets: memory-only cache plus its data path.
    single<WorkerCredentialStore> { InMemoryWorkerCredentialStore() }
    single<CredentialSecretDecryptor> {
        DefaultCredentialSecretDecryptor(
            privateKeyPem = privateKeyPem,
            workerCertificateFingerprint = config.identity.certificateFingerprint,
            maxSecretBytes = config.credentials.maxSecretBytes,
        )
    }
    single<SecretTreeFactory> {
        HelperSecretTreeFactory(
            secretDir = config.credentials.secretDir,
            vaultHelperPath = config.credentials.vaultHelperPath,
            logger = LogManager.getLogger(HelperSecretTreeFactory::class.java),
        )
    }
    single {
        SecretMaterialFactory(
            trees = get(),
            askpassScriptPath = config.credentials.askpassScriptPath,
        )
    }
    single<CredentialProfileValidator> {
        DefaultCredentialProfileValidator(
            verificationService = get(),
            deploymentEnvironmentKeys = config.commandIsolation.environment.set.keys,
        )
    }
    single<WorkerCredentialApi> {
        KtorWorkerCredentialApi(
            client = get(),
            authenticatedRequestExecutor = get(),
        )
    }
    single {
        AssignedCredentialBootstrapper(
            api = get(),
            profileValidator = get(),
            store = get(),
            pinnedWorkerId = config.identity.workerId,
        )
    }
    single<CredentialSyncCommandExecutor> {
        CredentialSyncCommandExecutorImpl(
            profileValidator = get(),
            store = get(),
            pinnedWorkerId = config.identity.workerId,
        )
    }
    single<CredentialSyncCommandInteractionFactory> {
        CredentialSyncCommandInteractionFactory(executor = get())
    }
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
    single<SignedMcpServerConfigValidator> {
        DefaultSignedMcpServerConfigValidator(
            verificationService = get()
        )
    }
    single<SignedMcpServerDraftConfigValidator> {
        DefaultSignedMcpServerDraftConfigValidator(
            verificationService = get()
        )
    }
    single<AssignedConfigBootstrapper> {
        AssignedConfigBootstrapper(
            mcpServerApi = get(),
            signedMcpServerConfigValidator = get(),
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
    single<LocalMCPToolExecutionAuthorizationValidator> {
        DefaultLocalMCPToolExecutionAuthorizationValidator(
            verificationService = get()
        )
    }
    single<McpToolCallExecutor> {
        McpToolCallExecutorImpl(
            authorizationValidator = get(),
            runtimeService = get(),
            json = get()
        )
    }
    single<McpRuntimeCommandExecutor> {
        McpRuntimeCommandExecutorImpl(
            runtimeService = get(),
            signedMcpServerConfigValidator = get(),
            signedMcpServerDraftConfigValidator = get(),
            configStore = get()
        )
    }
    single<OutboundMessageEmitterHolder> { OutboundMessageEmitterHolder() }
    single<OutboundMessageEmitter> { get<OutboundMessageEmitterHolder>() }
    single<WebSocketTransportConfig> {
        WebSocketTransportConfig.fromServerBaseUrl(
            serverBaseUrl = config.server.baseUrl,
            workerUid = config.identity.uid
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
            credentialBootstrapper = get(),
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

    single<BuiltInToolAuthorizationValidator> {
        DefaultBuiltInToolAuthorizationValidator(
            verificationService = get(),
        )
    }

    single<ToolCallInteractionFactory> {
        ToolCallInteractionFactory(
            authorizationValidator = get(),
            toolCallExecutor = get(),
            messageIdProvider = get(),
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
                WorkerProtocolCommandTypes.MCP_SERVER_DELETE to get<McpRuntimeCommandInteractionFactory>(),
                WorkerProtocolCommandTypes.CREDENTIAL_PROFILE_CREATE to get<CredentialSyncCommandInteractionFactory>(),
                WorkerProtocolCommandTypes.CREDENTIAL_PROFILE_UPDATE to get<CredentialSyncCommandInteractionFactory>(),
                WorkerProtocolCommandTypes.CREDENTIAL_PROFILE_DELETE to get<CredentialSyncCommandInteractionFactory>(),
                WorkerProtocolCommandTypes.CREDENTIAL_SECRET_CREATE to get<CredentialSyncCommandInteractionFactory>(),
                WorkerProtocolCommandTypes.CREDENTIAL_SECRET_UPDATE to get<CredentialSyncCommandInteractionFactory>(),
                WorkerProtocolCommandTypes.CREDENTIAL_SECRET_DELETE to get<CredentialSyncCommandInteractionFactory>()
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
            workerUid = config.identity.uid,
            connectionLoop = get(),
            mcpClientService = get()
        )
    }
    single<TerminalSessionManager> {
        TerminalSessionManager(
            config = get<RuntimeConfig>().terminalSessions,
            resolution = commandIsolation,
            childEnvironment = get(),
            workspace = Paths.get(get<RuntimeConfig>().workspace.path),
            logger = LogManager.getLogger(TerminalSessionManager::class.java),
        )
    } onClose { manager ->
        // Tests and stopKoin() paths release the reaper scope; SIGTERM is covered by WorkerMain's hook.
        manager?.close()
    }

    single<BuiltInToolExecutionContext> {
        val cfg = get<RuntimeConfig>()
        BuiltInToolExecutionContext(
            workspace = Paths.get(cfg.workspace.path),
            defaultCommandTimeoutSeconds = cfg.builtInTools.defaultCommandTimeoutSeconds,
            defaultSearchTimeoutSeconds = cfg.builtInTools.defaultSearchTimeoutSeconds,
        )
    }

    single<Map<String, BuiltInTool>> {
        // Registry of worker-side built-in tools, keyed by unprefixed tool name.
        // Keep this in sync with BuiltInToolCatalog.
        listOf(
            ReadTextFileTool(),
            WriteFileTool(),
            EditFileTool(),
            CreateDirectoryTool(),
            ListDirectoryTool(),
            MoveFileTool(),
            SearchFilesTool(),
            SearchTextTool(),
            RunCommandTool(launcher = get(), childEnvironment = get(), terminator = get()),
            TerminalSessionTool(manager = get()),
            FetchWebContentTool(fetchService = get()),
            DownloadFileTool(fetchService = get()),
        ).associateBy { it.name }
    }

    single<BuiltInToolCallExecutor> {
        DefaultBuiltInToolCallExecutor(
            context = get(),
            tools = get(),
        )
    }

    // Shared worker-side web-access foundation (consumed by future built-in tools such as
    // fetch_web_content and download_file). No built-in tools are registered here yet.
    single<DnsResolver> { JvmDnsResolver() }
    single<PublicUrlValidator> { PublicUrlValidator(dns = get()) }
    single<WebFetchService> { KtorWebFetchService(validator = get()) }
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

package eu.torvian.chatbot.server.worker.protocol.routing

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.api.resources.WsResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.models.api.worker.protocol.builders.sessionHello
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.WorkerProtocolJson
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolMessageTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerSessionHelloPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerSessionWelcomePayload
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.domain.security.JwtConfig
import eu.torvian.chatbot.server.ktor.routes.ApiRoutesKtor
import eu.torvian.chatbot.server.ktor.routes.configureWorkerWebSocketRoutes
import eu.torvian.chatbot.server.service.core.WorkerService
import eu.torvian.chatbot.server.service.security.CertificateService
import eu.torvian.chatbot.server.worker.session.WorkerSessionRegistry
import eu.torvian.chatbot.server.testutils.auth.TestAuthHelper
import eu.torvian.chatbot.server.testutils.auth.authenticate
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import eu.torvian.chatbot.server.testutils.ktor.KtorTestApp
import eu.torvian.chatbot.server.testutils.ktor.myTestApplication
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WorkerServerWorkerWebSocketRoutesTest {
    private lateinit var container: DIContainer
    private lateinit var workerTestApplication: KtorTestApp
    private lateinit var testDataManager: TestDataManager
    private lateinit var authHelper: TestAuthHelper
    private lateinit var workerService: WorkerService
    private lateinit var jwtConfig: JwtConfig
    private lateinit var registry: WorkerSessionRegistry
    private lateinit var certificateService: CertificateService

    private val ownerUser = UserEntity(
        id = 11L,
        username = "worker-owner",
        email = "worker-owner@example.com",
        passwordHash = "hashed-password",
        status = UserStatus.ACTIVE,
        createdAt = TestDefaults.DEFAULT_INSTANT,
        updatedAt = TestDefaults.DEFAULT_INSTANT,
        lastLogin = null
    )

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        val apiRoutesKtor: ApiRoutesKtor = container.get()

        workerTestApplication = myTestApplication(
            container = container,
            routing = {
                apiRoutesKtor.configureWorkerRoutes(this)
                configureWorkerWebSocketRoutes(
                    workerSessionRegistry = container.get(),
                    messageCodec = container.get(),
                    messageRouter = container.get(),
                    pendingCommandRegistry = container.get()
                )
            }
        )

        testDataManager = container.get()
        authHelper = TestAuthHelper(container)
        workerService = container.get()
        jwtConfig = container.get()
        registry = container.get()
        certificateService = container.get()

        testDataManager.createTables(
            setOf(
                Table.USERS,
                Table.WORKERS
            )
        )
    }

    /**
     * Releases the shared test container so each repetition gets a fresh database and DI graph.
     */
    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    /**
     * Verifies the worker hello handshake completes cleanly when the client receives the welcome
     * frame concurrently with sending the initial hello frame.
     */
    @Test
    fun `WS worker connect - authenticated worker completes hello welcome handshake`() = workerTestApplication {
        testDataManager.insertUser(ownerUser)

        val worker = workerService.registerWorker(
            ownerUserId = ownerUser.id,
            workerUid = "worker-websocket-test",
            displayName = "build-agent",
            certificatePem = createCertificatePem(),
            allowedScopes = listOf("messages:read")
        ).fold(
            ifLeft = { error("Worker registration failed in test: $it") },
            ifRight = { it }
        )
        val workerToken = jwtConfig.generateServiceAccessToken(
            workerId = worker.id,
            workerUid = worker.workerUid,
            ownerUserId = worker.ownerUserId,
            scopes = worker.allowedScopes
        )

        var welcomeMessage: WorkerProtocolMessage? = null
        client.webSocket(
            urlString = href(WsResource.Workers.Connect()),
            request = {
                authenticate(workerToken)
            }
        ) {
            val hello = sessionHello(
                id = "hello-message-1",
                interactionId = "interaction-1",
                payload = WorkerSessionHelloPayload(
                    workerUid = worker.workerUid,
                    capabilities = listOf("mcp.tools", "mcp.tools", ""),
                    supportedProtocolVersions = listOf(1),
                    workerVersion = "1.0.0"
                )
            )
            send(Frame.Text(WorkerProtocolJson.json.encodeToString(hello)))

            val frame = incoming.receive()
            val textFrame = frame as? Frame.Text ?: error("Expected welcome text frame")
            welcomeMessage = WorkerProtocolJson.json.decodeFromString<WorkerProtocolMessage>(textFrame.readText())

            val registeredSession = registry.get(worker.id)
            assertEquals(worker.id, registeredSession?.workerContext?.workerId)
            val readyState = registeredSession?.readyState()
            assertNotNull(readyState)
            assertEquals(1, readyState.selectedProtocolVersion)
            assertEquals(listOf("mcp.tools"), readyState.acceptedCapabilities)
        }

        assertNotNull(welcomeMessage)
        assertEquals(WorkerProtocolMessageTypes.SESSION_WELCOME, welcomeMessage.type)
        val welcomePayload = WorkerProtocolJson.json.decodeFromJsonElement<WorkerSessionWelcomePayload>(
            welcomeMessage.payload ?: error("Expected welcome payload")
        )
        assertEquals(worker.workerUid, welcomePayload.workerUid)
        assertEquals(1, welcomePayload.selectedProtocolVersion)
        assertEquals(listOf("mcp.tools"), welcomePayload.acceptedCapabilities)
        withTimeout(5_000) {
            while (registry.get(worker.id) != null) {
                delay(50)
            }
        }
        assertNull(registry.get(worker.id))
    }

    /**
     * Verifies that an authenticated worker is closed with a policy violation when the hello
     * payload announces a different worker UID than the JWT-authenticated principal.
     */
    @Test
    fun `WS worker connect - hello worker uid mismatch is rejected`() = workerTestApplication {
        testDataManager.insertUser(ownerUser)

        val worker = workerService.registerWorker(
            ownerUserId = ownerUser.id,
            workerUid = "worker-uid-mismatch-test",
            displayName = "build-agent",
            certificatePem = createCertificatePem(),
            allowedScopes = listOf("messages:read")
        ).fold(
            ifLeft = { error("Worker registration failed in test: $it") },
            ifRight = { it }
        )
        val workerToken = jwtConfig.generateServiceAccessToken(
            workerId = worker.id,
            workerUid = worker.workerUid,
            ownerUserId = worker.ownerUserId,
            scopes = worker.allowedScopes
        )

        client.webSocket(
            urlString = href(WsResource.Workers.Connect()),
            request = {
                authenticate(workerToken)
            }
        ) {
            val hello = sessionHello(
                id = "hello-message-mismatch",
                interactionId = "interaction-mismatch",
                payload = WorkerSessionHelloPayload(
                    workerUid = "different-worker-uid",
                    capabilities = listOf("mcp.tools"),
                    supportedProtocolVersions = listOf(1),
                    workerVersion = "1.0.0"
                )
            )
            send(Frame.Text(WorkerProtocolJson.json.encodeToString(hello)))

            val reason = withTimeout(5_000) {
                closeReason.await() ?: error("Expected close reason for rejected hello handshake")
            }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason.code)
            assertEquals("Worker hello handshake failed", reason.message)

            withTimeout(5_000) {
                while (registry.get(worker.id) != null) {
                    delay(50)
                }
            }
            assertNull(registry.get(worker.id))
        }
    }

    private fun createCertificatePem(): String {
        val keyPair = certificateService.generateRSAKeyPair()
        val certificate = certificateService.generateSelfSignedCertificate(
            keyPair = keyPair,
            subjectDN = "CN=worker-websocket-test"
        )
        return certificateService.certificateToPem(certificate)
    }
}







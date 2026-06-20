package eu.torvian.chatbot.server.ktor.routes

import arrow.core.right
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.resources.LocalMCPServerResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.KoinDIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.api.mcp.*
import eu.torvian.chatbot.common.models.worker.WorkerDto
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.server.data.dao.LocalMCPServerSignatureDao
import eu.torvian.chatbot.server.data.dao.UserDeviceDao
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.domain.security.JwtConfig
import eu.torvian.chatbot.server.service.core.WorkerService
import eu.torvian.chatbot.server.service.security.CertificateService
import eu.torvian.chatbot.server.testutils.auth.TestAuthHelper
import eu.torvian.chatbot.server.testutils.auth.applyDetachedSignedRequestHeaders
import eu.torvian.chatbot.server.testutils.auth.authenticate
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import eu.torvian.chatbot.server.testutils.ktor.KtorTestApp
import eu.torvian.chatbot.server.testutils.ktor.myTestApplication
import eu.torvian.chatbot.server.worker.mcp.configsync.LocalMCPServerWorkerSyncService
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.LocalMCPRuntimeControlService
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.dsl.module
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Integration tests for Local MCP server routes.
 */
class LocalMCPServerRoutesTest {
    private lateinit var container: DIContainer
    private lateinit var app: KtorTestApp
    private lateinit var testDataManager: TestDataManager
    private lateinit var authHelper: TestAuthHelper
    private lateinit var certificateService: CertificateService
    private lateinit var workerService: WorkerService
    private lateinit var jwtConfig: JwtConfig
    private lateinit var localMCPServerWorkerSyncService: LocalMCPServerWorkerSyncService

    private val user1 = TestDefaults.user1
    private val user2 = TestDefaults.user2

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        localMCPServerWorkerSyncService = mockk()
        coEvery { localMCPServerWorkerSyncService.syncCreated(any()) } returns Unit.right()
        coEvery { localMCPServerWorkerSyncService.syncUpdated(any(), any()) } returns Unit.right()
        coEvery { localMCPServerWorkerSyncService.syncDeleted(any(), any()) } returns Unit.right()

        // Keep route tests deterministic by replacing worker-backed runtime control with a local dummy.
        (container as KoinDIContainer).addModule(
            module {
                single<LocalMCPServerWorkerSyncService> { localMCPServerWorkerSyncService }
                single<LocalMCPRuntimeControlService> { createDummyLocalMCPRuntimeControlService() }
            }
        )
        val apiRoutesKtor: ApiRoutesKtor = container.get()

        app = myTestApplication(
            container = container,
            routing = {
                apiRoutesKtor.configureLocalMCPServerRoutes(this)
            }
        )

        testDataManager = container.get()
        authHelper = TestAuthHelper(container)
        certificateService = container.get()
        workerService = container.get()
        jwtConfig = container.get()

        testDataManager.createTables(
            setOf(
                Table.USERS,
                Table.USER_SESSIONS,
                Table.WORKERS,
                Table.USER_DEVICES,
                Table.API_SECRETS,
                Table.LOCAL_MCP_SERVERS,
                Table.LOCAL_MCP_SERVER_SIGNATURES,
                Table.LOCAL_MCP_TOOL_DEFINITIONS,
                Table.TOOL_DEFINITIONS
            )
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `user can create Local MCP server and assigned worker can read it`() = app {
        val userToken = createUserAndGetTokenWithSignerDevice(user1)
        val worker = registerWorker(owner = user1, workerUid = "worker-route-local-mcp")
        val createSignedRequest = signedRequest(
            payload = """
            {
              "workerId": ${worker.id},
              "name": "filesystem",
              "command": "npx",
              "arguments": ["-y", "@modelcontextprotocol/server-filesystem"],
              "environmentVariables": [{"key": "LOG_LEVEL", "value": "debug"}],
              "secretEnvironmentVariables": [{"key": "API_KEY", "value": "secret-value"}]
            }
            """.trimIndent()
        )
        val workerToken = jwtConfig.generateServiceAccessToken(
            workerId = worker.id,
            workerUid = worker.workerUid,
            ownerUserId = worker.ownerUserId,
            scopes = worker.allowedScopes,
            currentTime = Clock.System.now().toEpochMilliseconds()
        )

        val createResponse = client.post(href(LocalMCPServerResource())) {
            authenticate(userToken)
            contentType(ContentType.Application.Json)
            applyDetachedSignedRequestHeaders(createSignedRequest)
            setBody(createSignedRequest.payload)
        }

        assertEquals(HttpStatusCode.Created, createResponse.status)
        val createdServer = createResponse.body<LocalMCPServerDto>()
        assertEquals(worker.id, createdServer.workerId)
        assertEquals("secret-value", createdServer.secretEnvironmentVariables.single().value)
        val signatures = container.get<LocalMCPServerSignatureDao>().getSignaturesByServerId(createdServer.id)
        assertEquals(1, signatures.size)
        assertEquals(createSignedRequest.signature, signatures.single().signature)
        assertEquals(createSignedRequest.payload, signatures.single().payloadJson)

        val workerListResponse = client.get(href(LocalMCPServerResource.Assigned())) {
            authenticate(workerToken)
        }
        assertEquals(HttpStatusCode.OK, workerListResponse.status)
        val workerServers = workerListResponse.body<List<SignedLocalMCPServerDto>>()
        assertEquals(1, workerServers.size)
        assertEquals(createdServer.id, workerServers.single().server.id)
        assertEquals("secret-value", workerServers.single().server.secretEnvironmentVariables.single().value)
        assertEquals(createSignedRequest, workerServers.single().signedRequest)
    }

    @Test
    fun `user can update Local MCP server and route syncs previous and current states`() = app {
        val userToken = createUserAndGetTokenWithSignerDevice(user1)
        val originalWorker = registerWorker(owner = user1, workerUid = "worker-route-update-original")
        val reassignedWorker = registerWorker(owner = user1, workerUid = "worker-route-update-new")
        val createSignedRequest = signedRequest(
            CreateLocalMCPServerRequest(
                workerId = originalWorker.id,
                name = "filesystem-update",
                command = "npx"
            )
        )

        val createResponse = client.post(href(LocalMCPServerResource())) {
            authenticate(userToken)
            contentType(ContentType.Application.Json)
            applyDetachedSignedRequestHeaders(createSignedRequest)
            setBody(createSignedRequest.payload)
        }
        assertEquals(HttpStatusCode.Created, createResponse.status)
        val createdServer = createResponse.body<LocalMCPServerDto>()
        val updateSignedRequest = signedRequest(
            payload = """
            {
              "workerId": ${reassignedWorker.id},
              "name": "filesystem-update-renamed",
              "command": "node"
            }
            """.trimIndent()
        )

        val updateResponse = client.put(href(LocalMCPServerResource.ById(id = createdServer.id))) {
            authenticate(userToken)
            contentType(ContentType.Application.Json)
            applyDetachedSignedRequestHeaders(updateSignedRequest)
            setBody(updateSignedRequest.payload)
        }

        assertEquals(HttpStatusCode.OK, updateResponse.status)
        val updatedServer = updateResponse.body<LocalMCPServerDto>()
        assertEquals(reassignedWorker.id, updatedServer.workerId)
        assertEquals("filesystem-update-renamed", updatedServer.name)
        val signatures = container.get<LocalMCPServerSignatureDao>().getSignaturesByServerId(createdServer.id)
        assertEquals(1, signatures.size)
        assertEquals(updateSignedRequest.signature, signatures.single().signature)
        assertEquals(updateSignedRequest.payload, signatures.single().payloadJson)

        coVerify(exactly = 1) {
            localMCPServerWorkerSyncService.syncUpdated(
                signedServer = SignedLocalMCPServerDto(server = updatedServer, signedRequest = updateSignedRequest),
                previousWorkerId = originalWorker.id
            )
        }
    }

    @Test
    fun `user cannot assign server to another users worker`() = app {
        val userToken = createUserAndGetTokenWithSignerDevice(user1)
        testDataManager.insertUser(user2)
        val otherWorker = registerWorker(owner = user2, workerUid = "other-owner-worker")
        val signedRequest = signedRequest(
            CreateLocalMCPServerRequest(
                workerId = otherWorker.id,
                name = "forbidden",
                command = "npx"
            )
        )

        val response = client.post(href(LocalMCPServerResource())) {
            authenticate(userToken)
            contentType(ContentType.Application.Json)
            applyDetachedSignedRequestHeaders(signedRequest)
            setBody(signedRequest.payload)
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val errorCode = response.body<ApiError>().code
        assertEquals(CommonApiErrorCodes.PERMISSION_DENIED.code, errorCode)
    }

    @Test
    fun `missing detached signing headers returns bad request`() = app {
        val userToken = createUserAndGetTokenWithSignerDevice(user1)
        val worker = registerWorker(owner = user1, workerUid = "worker-route-missing-signature-headers")

        val response = client.post(href(LocalMCPServerResource())) {
            authenticate(userToken)
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    CreateLocalMCPServerRequest(
                        workerId = worker.id,
                        name = "filesystem",
                        command = "npx"
                    )
                )
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertTrue(error.message.contains("Detached signing headers"))
    }

    @Test
    fun `malformed detached signed payload returns bad request`() = app {
        val userToken = createUserAndGetTokenWithSignerDevice(user1)
        val malformedSignedRequest = signedRequest(payload = "{not-json")

        val response = client.post(href(LocalMCPServerResource())) {
            authenticate(userToken)
            contentType(ContentType.Application.Json)
            applyDetachedSignedRequestHeaders(malformedSignedRequest)
            setBody(malformedSignedRequest.payload)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, response.body<ApiError>().code)
    }

    /**
     * Verifies that runtime-control endpoints are reachable for authenticated owners and
     * return deterministic dummy payloads.
     */
    @Test
    fun `runtime control endpoints return dummy success responses for authenticated owner`() = app {
        val userToken = createUserAndGetTokenWithSignerDevice(user1)
        val worker = registerWorker(owner = user1, workerUid = "worker-route-runtime-control")
        val signedRequest = signedRequest(
            CreateLocalMCPServerRequest(
                workerId = worker.id,
                name = "filesystem-runtime",
                command = "npx"
            )
        )

        val createResponse = client.post(href(LocalMCPServerResource())) {
            authenticate(userToken)
            contentType(ContentType.Application.Json)
            applyDetachedSignedRequestHeaders(signedRequest)
            setBody(signedRequest.payload)
        }
        assertEquals(HttpStatusCode.Created, createResponse.status)
        val createdServer = createResponse.body<LocalMCPServerDto>()
        val byId = LocalMCPServerResource.ById(id = createdServer.id)

        val startResponse = client.post(href(LocalMCPServerResource.ById.Start(parent = byId))) {
            authenticate(userToken)
        }
        assertEquals(HttpStatusCode.OK, startResponse.status)

        val stopResponse = client.post(href(LocalMCPServerResource.ById.Stop(parent = byId))) {
            authenticate(userToken)
        }
        assertEquals(HttpStatusCode.OK, stopResponse.status)

        val testResponse = client.post(href(LocalMCPServerResource.ById.TestConnection(parent = byId))) {
            authenticate(userToken)
        }
        assertEquals(HttpStatusCode.OK, testResponse.status)
        val testPayload = testResponse.body<TestLocalMCPServerConnectionResponse>()
        assertEquals(createdServer.id, testPayload.serverId)
        assertEquals(true, testPayload.success)
        assertEquals(3, testPayload.discoveredToolCount)

        val refreshResponse = client.post(href(LocalMCPServerResource.ById.RefreshTools(parent = byId))) {
            authenticate(userToken)
        }
        assertEquals(HttpStatusCode.OK, refreshResponse.status)
        val refreshPayload = refreshResponse.body<RefreshMCPToolsResponse>()
        assertEquals(0, refreshPayload.addedTools.size)
        assertEquals(0, refreshPayload.updatedTools.size)
        assertEquals(0, refreshPayload.deletedTools.size)

        val listStatusResponse = client.get(href(LocalMCPServerResource.RuntimeStatuses())) {
            authenticate(userToken)
        }
        assertEquals(HttpStatusCode.OK, listStatusResponse.status)
        val listStatusPayload = listStatusResponse.body<List<LocalMcpServerRuntimeStatusDto>>()
        assertEquals(1, listStatusPayload.size)
        assertEquals(createdServer.id, listStatusPayload.single().serverId)

        val byIdStatusResponse = client.get(href(LocalMCPServerResource.ById.RuntimeStatus(parent = byId))) {
            authenticate(userToken)
        }
        assertEquals(HttpStatusCode.OK, byIdStatusResponse.status)
        val byIdStatusPayload = byIdStatusResponse.body<LocalMcpServerRuntimeStatusDto>()
        assertEquals(createdServer.id, byIdStatusPayload.serverId)
        assertEquals(LocalMcpServerRuntimeStateDto.STOPPED, byIdStatusPayload.state)
    }

    /**
     * Verifies that draft connection testing is reachable for authenticated owners and returns
     * a deterministic dummy payload.
     */
    @Test
    fun `draft connection endpoint returns dummy success response for authenticated owner`() = app {
        val userToken = authHelper.createUserAndGetToken(user1)
        val worker = registerWorker(user1, "worker-draft-test")

        val draftRequest = TestLocalMCPServerDraftConnectionRequest(
            workerId = worker.id,
            name = "draft-filesystem",
            command = "npx",
            arguments = listOf("-y", "@modelcontextprotocol/server-filesystem"),
            environmentVariables = listOf(LocalMCPEnvironmentVariableDto("LOG_LEVEL", "debug")),
            secretEnvironmentVariables = listOf(LocalMCPEnvironmentVariableDto("API_KEY", "secret-value")),
            workingDirectory = "C:/data"
        )
        val signedRequest = signedRequest(draftRequest)

        val response = client.post(href(LocalMCPServerResource.TestDraftConnection())) {
            authenticate(userToken)
            contentType(ContentType.Application.Json)
            applyDetachedSignedRequestHeaders(signedRequest)
            setBody(signedRequest.payload)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = response.body<TestLocalMCPServerConnectionResponse>()
        assertTrue(payload.success)
        assertEquals(3, payload.discoveredToolCount)
    }

    /**
     * Registers a worker for test setup.
     *
     * @param owner Owner user entity.
     * @param workerUid Worker UID.
     * @return Registered worker model.
     */
    private suspend fun registerWorker(owner: UserEntity, workerUid: String): WorkerDto {
        val keyPair = certificateService.generateRSAKeyPair()
        val certificate = certificateService.generateSelfSignedCertificate(
            keyPair = keyPair,
            subjectDN = "CN=$workerUid"
        )
        val certificatePem = certificateService.certificateToPem(certificate)

        val worker = workerService.registerWorker(
            ownerUserId = owner.id,
            workerUid = workerUid,
            displayName = workerUid,
            certificatePem = certificatePem,
            allowedScopes = listOf("messages:read")
        ).getOrNull()

        assertTrue(worker != null)
        return worker
    }

    /**
     * Creates a user token and registers the signer device required by signed Local MCP routes.
     *
     * @param user User entity to insert and authenticate.
     * @return JWT access token for the inserted user.
     */
    private suspend fun createUserAndGetTokenWithSignerDevice(user: UserEntity): String {
        val token = authHelper.createUserAndGetToken(user)
        container.get<UserDeviceDao>().insertDevice(user.id, "test-device-id", "Route test device")
        return token
    }

    private val json = Json { encodeDefaults = true }

    /**
     * Serializes a Local MCP request DTO into the exact JSON string used as the signed HTTP body for route tests.
     *
     * @param request Request DTO to serialize as the exact signed payload string.
     * @return Detached signed request accepted by Local MCP create and update routes.
     */
    private inline fun <reified T> signedRequest(request: T): SignedRequest = signedRequest(
        payload = json.encodeToString(request)
    )

    /**
     * Creates deterministic detached signing metadata for route tests.
     *
     * @param payload Exact HTTP body string that should be persisted without reserialization.
     * @return Detached signed request accepted by Local MCP create and update routes.
     */
    private fun signedRequest(payload: String): SignedRequest = SignedRequest(
        payload = payload,
        signature = "signature-base64",
        signerId = "test-device-id",
        timestamp = Clock.System.now().toEpochMilliseconds(),
        nonce = "nonce-route-test"
    )

    /**
     * Creates a deterministic runtime-control stub so route tests do not depend on worker-backed execution.
     *
     * @return A dummy runtime-control service that always reports success.
     */
    private fun createDummyLocalMCPRuntimeControlService(): LocalMCPRuntimeControlService =
        object : LocalMCPRuntimeControlService {
            override suspend fun startServer(userId: Long, serverId: Long) = Unit.right()

            override suspend fun stopServer(userId: Long, serverId: Long) = Unit.right()

            override suspend fun testConnection(
                userId: Long,
                serverId: Long
            ) = TestLocalMCPServerConnectionResponse(
                serverId = serverId,
                success = true,
                discoveredToolCount = 3,
                message = null
            ).right()

            override suspend fun testDraftConnection(
                userId: Long,
                request: TestLocalMCPServerDraftConnectionRequest,
                signedRequest: SignedRequest
            ) = TestLocalMCPServerConnectionResponse(
                serverId = null,
                success = true,
                discoveredToolCount = 3,
                message = null
            ).right()

            override suspend fun refreshTools(
                userId: Long,
                serverId: Long
            ) = RefreshMCPToolsResponse(
                addedTools = emptyList(),
                updatedTools = emptyList(),
                deletedTools = emptyList()
            ).right()

            override suspend fun getRuntimeStatus(
                userId: Long,
                serverId: Long
            ) = LocalMcpServerRuntimeStatusDto(
                serverId = serverId,
                state = LocalMcpServerRuntimeStateDto.STOPPED
            ).right()

            override suspend fun listRuntimeStatuses(
                userId: Long
            ) = listOf(
                LocalMcpServerRuntimeStatusDto(
                    serverId = 1L,
                    state = LocalMcpServerRuntimeStateDto.STOPPED
                )
            ).right()
        }
}



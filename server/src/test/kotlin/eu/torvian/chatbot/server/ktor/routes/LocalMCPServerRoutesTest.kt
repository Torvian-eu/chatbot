package eu.torvian.chatbot.server.ktor.routes

import arrow.core.right
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.resources.LocalMCPServerResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.KoinDIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.api.mcp.CreateLocalMCPServerRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPEnvironmentVariableDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.RefreshMCPToolsResponse
import eu.torvian.chatbot.common.models.api.mcp.TestLocalMCPServerConnectionResponse
import eu.torvian.chatbot.common.models.worker.WorkerDto
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.domain.security.JwtConfig
import eu.torvian.chatbot.server.service.core.WorkerService
import eu.torvian.chatbot.server.service.security.CertificateService
import eu.torvian.chatbot.server.testutils.auth.TestAuthHelper
import eu.torvian.chatbot.server.testutils.auth.authenticate
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import eu.torvian.chatbot.server.testutils.ktor.KtorTestApp
import eu.torvian.chatbot.server.testutils.ktor.myTestApplication
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.LocalMCPRuntimeControlService
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.test.runTest
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

    private val user1 = TestDefaults.user1
    private val user2 = TestDefaults.user2

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        // Keep route tests deterministic by replacing worker-backed runtime control with a local dummy.
        (container as KoinDIContainer).addModule(
            module {
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
                Table.API_SECRETS,
                Table.LOCAL_MCP_SERVERS,
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
        val userToken = authHelper.createUserAndGetToken(user1)
        val worker = registerWorker(owner = user1, workerUid = "worker-route-local-mcp")
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
            setBody(
                CreateLocalMCPServerRequest(
                    workerId = worker.id,
                    name = "filesystem",
                    command = "npx",
                    arguments = listOf("-y", "@modelcontextprotocol/server-filesystem"),
                    environmentVariables = listOf(LocalMCPEnvironmentVariableDto("LOG_LEVEL", "debug")),
                    secretEnvironmentVariables = listOf(LocalMCPEnvironmentVariableDto("API_KEY", "secret-value"))
                )
            )
        }

        assertEquals(HttpStatusCode.Created, createResponse.status)
        val createdServer = createResponse.body<LocalMCPServerDto>()
        assertEquals(worker.id, createdServer.workerId)
        assertEquals("secret-value", createdServer.secretEnvironmentVariables.single().value)

        val workerListResponse = client.get(href(LocalMCPServerResource.Assigned())) {
            authenticate(workerToken)
        }
        assertEquals(HttpStatusCode.OK, workerListResponse.status)
        val workerServers = workerListResponse.body<List<LocalMCPServerDto>>()
        assertEquals(1, workerServers.size)
        assertEquals(createdServer.id, workerServers.single().id)
        assertEquals("secret-value", workerServers.single().secretEnvironmentVariables.single().value)
    }

    @Test
    fun `user cannot assign server to another users worker`() = app {
        val userToken = authHelper.createUserAndGetToken(user1)
        testDataManager.insertUser(user2)
        val otherWorker = registerWorker(owner = user2, workerUid = "other-owner-worker")

        val response = client.post(href(LocalMCPServerResource())) {
            authenticate(userToken)
            contentType(ContentType.Application.Json)
            setBody(
                CreateLocalMCPServerRequest(
                    workerId = otherWorker.id,
                    name = "forbidden",
                    command = "npx"
                )
            )
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val errorCode = response.body<eu.torvian.chatbot.common.api.ApiError>().code
        assertEquals(CommonApiErrorCodes.PERMISSION_DENIED.code, errorCode)
    }

    /**
     * Verifies that runtime-control endpoints are reachable for authenticated owners and
     * return deterministic dummy payloads.
     */
    @Test
    fun `runtime control endpoints return dummy success responses for authenticated owner`() = app {
        val userToken = authHelper.createUserAndGetToken(user1)
        val worker = registerWorker(owner = user1, workerUid = "worker-route-runtime-control")

        val createResponse = client.post(href(LocalMCPServerResource())) {
            authenticate(userToken)
            contentType(ContentType.Application.Json)
            setBody(
                CreateLocalMCPServerRequest(
                    workerId = worker.id,
                    name = "filesystem-runtime",
                    command = "npx"
                )
            )
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

            override suspend fun refreshTools(
                userId: Long,
                serverId: Long
            ) = RefreshMCPToolsResponse(
                addedTools = emptyList(),
                updatedTools = emptyList(),
                deletedTools = emptyList()
            ).right()
        }
}



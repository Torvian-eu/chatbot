package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.resources.BuiltInToolResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.common.models.worker.WorkerDto
import eu.torvian.chatbot.server.data.dao.BuiltInToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.ToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.WorkerDao
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.service.security.CertificateService
import eu.torvian.chatbot.server.testutils.auth.TestAuthHelper
import eu.torvian.chatbot.server.testutils.auth.authenticate
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import eu.torvian.chatbot.server.testutils.ktor.KtorTestApp
import eu.torvian.chatbot.server.testutils.ktor.myTestApplication
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for built-in worker tool management routes (/api/v1/built-in-tools).
 *
 * Validates ownership enforcement, listing, and toggling of built-in tools.
 */
class BuiltInToolRoutesTest {

    private lateinit var container: DIContainer
    private lateinit var app: KtorTestApp
    private lateinit var testDataManager: TestDataManager
    private lateinit var authHelper: TestAuthHelper
    private lateinit var certificateService: CertificateService
    private lateinit var toolDefinitionDao: ToolDefinitionDao
    private lateinit var builtInToolDefinitionDao: BuiltInToolDefinitionDao
    private lateinit var workerDao: WorkerDao

    private val user1 = TestDefaults.user1
    private val user2 = TestDefaults.user2

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        val apiRoutesKtor: ApiRoutesKtor = container.get()

        app = myTestApplication(
            container = container,
            routing = {
                apiRoutesKtor.configureBuiltInToolRoutes(this)
            }
        )

        testDataManager = container.get()
        authHelper = TestAuthHelper(container)
        certificateService = container.get()
        toolDefinitionDao = container.get()
        builtInToolDefinitionDao = container.get()
        workerDao = container.get()

        testDataManager.createTables(
            setOf(
                Table.USERS,
                Table.USER_SESSIONS,
                Table.WORKERS,
                Table.TOOL_DEFINITIONS,
                Table.BUILT_IN_TOOL_DEFINITIONS
            )
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `user can list built-in tools of their own worker`() = app {
        val token = authHelper.createUserAndGetToken(user1)
        val worker = registerWorker(user1, "builtin-list-own")
        seedBuiltInTools(worker.id)

        val response = client.get(
            href(BuiltInToolResource.ByWorkerId(workerId = worker.id))
        ) {
            authenticate(token)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val tools = response.body<List<BuiltInWorkerToolDefinition>>()
        assertEquals(2, tools.size)
        assertTrue(tools.all { it.workerId == worker.id })
    }

    @Test
    fun `user cannot list built-in tools of another users worker`() = app {
        authHelper.createUserAndGetToken(user1)
        val worker = registerWorker(user1, "builtin-list-forbidden")
        seedBuiltInTools(worker.id)

        val user2Token = authHelper.createUserAndGetToken(
            user = user2,
            session = authHelper.createTestSession(id = 999L, userId = user2.id)
        )

        val response = client.get(
            href(BuiltInToolResource.ByWorkerId(workerId = worker.id))
        ) {
            authenticate(user2Token)
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.PERMISSION_DENIED.code, error.code)
    }

    @Test
    fun `user can toggle enabled state of a built-in tool`() = app {
        val token = authHelper.createUserAndGetToken(user1)
        val worker = registerWorker(user1, "builtin-toggle")
        val toolIds = seedBuiltInTools(worker.id)
        val toolId = toolIds.first()

        // Fetch the current tool so we can send a full definition body.
        val listed = client.get(href(BuiltInToolResource.ByWorkerId(workerId = worker.id))) {
            authenticate(token)
        }.body<List<BuiltInWorkerToolDefinition>>()
        val tool = listed.first { it.id == toolId }

        // Toggle to disabled
        val disableResponse = client.put(href(BuiltInToolResource.ById(toolId = toolId))) {
            authenticate(token)
            contentType(ContentType.Application.Json)
            setBody(tool.copy(isEnabled = false))
        }

        assertEquals(HttpStatusCode.OK, disableResponse.status)
        val disabledTool = disableResponse.body<BuiltInWorkerToolDefinition>()
        assertEquals(false, disabledTool.isEnabled)
        assertEquals(toolId, disabledTool.id)

        // Toggle back to enabled
        val enableResponse = client.put(href(BuiltInToolResource.ById(toolId = toolId))) {
            authenticate(token)
            contentType(ContentType.Application.Json)
            setBody(disabledTool.copy(isEnabled = true))
        }

        assertEquals(HttpStatusCode.OK, enableResponse.status)
        val enabledTool = enableResponse.body<BuiltInWorkerToolDefinition>()
        assertEquals(true, enabledTool.isEnabled)
        assertEquals(toolId, enabledTool.id)
    }

    @Test
    fun `user cannot toggle enabled state of another users tool`() = app {
        authHelper.createUserAndGetToken(user1)
        val worker = registerWorker(user1, "builtin-toggle-forbidden")
        val toolIds = seedBuiltInTools(worker.id)

        val user2Token = authHelper.createUserAndGetToken(
            user = user2,
            session = authHelper.createTestSession(id = 998L, userId = user2.id)
        )

        val response = client.put(href(BuiltInToolResource.ById(toolId = toolIds.first()))) {
            authenticate(user2Token)
            contentType(ContentType.Application.Json)
            setBody(
                BuiltInWorkerToolDefinition(
                    id = toolIds.first(),
                    name = "read_text_file",
                    description = "Read contents of a file as text",
                    config = buildJsonObject { },
                    inputSchema = buildJsonObject { put("type", JsonPrimitive("object")) },
                    outputSchema = null,
                    isEnabled = false,
                    createdAt = kotlin.time.Clock.System.now(),
                    updatedAt = kotlin.time.Clock.System.now(),
                    workerId = worker.id,
                    builtInToolName = "read_text_file"
                )
            )
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.PERMISSION_DENIED.code, error.code)
    }

    @Test
    fun `listing non-existent worker returns 404`() = app {
        val token = authHelper.createUserAndGetToken(user1)

        val response = client.get(
            href(BuiltInToolResource.ByWorkerId(workerId = 99999L))
        ) {
            authenticate(token)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
    }

    @Test
    fun `updating non-existent tool returns 404`() = app {
        val token = authHelper.createUserAndGetToken(user1)

        val response = client.put(href(BuiltInToolResource.ById(toolId = 99999L))) {
            authenticate(token)
            contentType(ContentType.Application.Json)
            setBody(
                BuiltInWorkerToolDefinition(
                    id = 99999L,
                    name = "read_text_file",
                    description = "Read contents of a file as text",
                    config = buildJsonObject { },
                    inputSchema = buildJsonObject { put("type", JsonPrimitive("object")) },
                    outputSchema = null,
                    isEnabled = false,
                    createdAt = kotlin.time.Clock.System.now(),
                    updatedAt = kotlin.time.Clock.System.now(),
                    workerId = 1L,
                    builtInToolName = "read_text_file"
                )
            )
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
    }

    /**
     * Registers a worker for the test by inserting it directly via the DAO.
     */
    private suspend fun registerWorker(owner: UserEntity, workerUid: String): WorkerDto {
        val keyPair = certificateService.generateRSAKeyPair()
        val certificate = certificateService.generateSelfSignedCertificate(
            keyPair = keyPair,
            subjectDN = "CN=$workerUid"
        )
        val certificatePem = certificateService.certificateToPem(certificate)
        val fingerprint = certificateService.computeCertificateFingerprint(certificate)

        val result = workerDao.createWorker(
            ownerUserId = owner.id,
            workerUid = workerUid,
            displayName = workerUid,
            certificatePem = certificatePem,
            certificateFingerprint = fingerprint,
            allowedScopes = listOf("messages:read"),
            toolNamePrefix = null
        )
        val entity = result.getOrNull()
            ?: throw IllegalStateException("Failed to register worker: $result")
        return WorkerDto(
            id = entity.id,
            workerUid = entity.workerUid,
            ownerUserId = entity.ownerUserId,
            displayName = entity.displayName,
            certificateFingerprint = entity.certificateFingerprint,
            allowedScopes = entity.allowedScopes,
            createdAt = entity.createdAt,
            lastSeenAt = entity.lastSeenAt,
            toolNamePrefix = entity.toolNamePrefix
        )
    }

    /**
     * Seeds two built-in tool definitions for a worker, inserting rows into both
     * [ToolDefinitionTable] and [BuiltInToolDefinitionTable].
     *
     * @param workerId The worker to assign tools to.
     * @return The list of tool-definition IDs that were created.
     */
    private suspend fun seedBuiltInTools(workerId: Long): List<Long> {
        val tool1 = toolDefinitionDao.insertToolDefinition(
            name = "read_text_file",
            description = "Read contents of a file as text",
            type = ToolType.BUILTIN_WORKER,
            config = buildJsonObject { },
            inputSchema = buildJsonObject { put("type", JsonPrimitive("object")) },
            outputSchema = null,
            isEnabled = true
        )
        builtInToolDefinitionDao.insertTool(
            toolDefinitionId = tool1.id,
            workerId = workerId,
            builtInToolName = "read_text_file"
        )

        val tool2 = toolDefinitionDao.insertToolDefinition(
            name = "run_command",
            description = "Execute a command-line command",
            type = ToolType.BUILTIN_WORKER,
            config = buildJsonObject { },
            inputSchema = buildJsonObject { put("type", JsonPrimitive("object")) },
            outputSchema = null,
            isEnabled = true
        )
        builtInToolDefinitionDao.insertTool(
            toolDefinitionId = tool2.id,
            workerId = workerId,
            builtInToolName = "run_command"
        )

        return listOf(tool1.id, tool2.id)
    }
}

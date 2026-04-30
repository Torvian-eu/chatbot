package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.resources.WorkerResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.api.worker.RegisterWorkerRequest
import eu.torvian.chatbot.common.models.api.worker.RegisterWorkerResponse
import eu.torvian.chatbot.common.models.api.worker.UpdateWorkerRequest
import eu.torvian.chatbot.common.models.user.UserStatus
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
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkerRoutesTest {
    private lateinit var container: DIContainer
    private lateinit var workerTestApplication: KtorTestApp
    private lateinit var testDataManager: TestDataManager
    private lateinit var authHelper: TestAuthHelper
    private lateinit var certificateService: CertificateService

    private val testUser = UserEntity(
        id = 1L,
        username = "worker-owner",
        email = "owner@example.com",
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
            }
        )

        testDataManager = container.get()
        authHelper = TestAuthHelper(container)
        certificateService = container.get()

        testDataManager.createTables(
            setOf(
                Table.USERS,
                Table.USER_SESSIONS,
                Table.WORKERS
            )
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `POST workers register - authenticated user can register worker`() = workerTestApplication {
        val authToken = authHelper.createUserAndGetToken(testUser)
        val fixture = createCertificateFixture()

        val response = client.post(href(WorkerResource.Register())) {
            authenticate(authToken)
            contentType(ContentType.Application.Json)
            setBody(
                RegisterWorkerRequest(
                    workerUid = "worker-route-test",
                    displayName = "build-agent",
                    certificatePem = fixture.certificatePem,
                    allowedScopes = listOf("messages:read")
                )
            )
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.body<RegisterWorkerResponse>()
        assertEquals(testUser.id, body.worker.ownerUserId)
        assertEquals("build-agent", body.worker.displayName)
        assertEquals(fixture.fingerprint, body.worker.certificateFingerprint)
        assertEquals(listOf("messages:read"), body.worker.allowedScopes)
    }

    @Test
    fun `POST workers register - unauthenticated request returns 401`() = workerTestApplication {
        val fixture = createCertificateFixture()

        val response = client.post(href(WorkerResource.Register())) {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterWorkerRequest(
                    workerUid = "worker-route-test",
                    displayName = "build-agent",
                    certificatePem = fixture.certificatePem,
                    allowedScopes = listOf("messages:read")
                )
            )
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST workers register - invalid certificate returns 400`() = workerTestApplication {
        val authToken = authHelper.createUserAndGetToken(testUser)

        val response = client.post(href(WorkerResource.Register())) {
            authenticate(authToken)
            contentType(ContentType.Application.Json)
            setBody(
                RegisterWorkerRequest(
                    workerUid = "worker-route-test",
                    displayName = "build-agent",
                    certificatePem = "not-a-valid-certificate",
                    allowedScopes = listOf("messages:read")
                )
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertEquals("Invalid worker registration", error.message)
    }

    @Test
    fun `POST workers register - duplicate certificate returns 409`() = workerTestApplication {
        val authToken = authHelper.createUserAndGetToken(testUser)
        val fixture = createCertificateFixture()

        val request = RegisterWorkerRequest(
            workerUid = "worker-route-test",
            displayName = "build-agent",
            certificatePem = fixture.certificatePem,
            allowedScopes = listOf("messages:read")
        )

        val firstResponse = client.post(href(WorkerResource.Register())) {
            authenticate(authToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        assertEquals(HttpStatusCode.Created, firstResponse.status)

        val secondResponse = client.post(href(WorkerResource.Register())) {
            authenticate(authToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        assertEquals(HttpStatusCode.Conflict, secondResponse.status)
        val error = secondResponse.body<ApiError>()
        assertEquals(CommonApiErrorCodes.ALREADY_EXISTS.code, error.code)
        assertEquals("Certificate already registered", error.message)
    }

    @Test
    fun `GET workers - authenticated user sees only their own workers`() = workerTestApplication {
        val userA = testUser.copy(id = 1L, username = "owner-a", email = "owner-a@example.com")
        val userB = testUser.copy(id = 2L, username = "owner-b", email = "owner-b@example.com")
        val sessionA = authHelper.createTestSession(id = 1L, userId = userA.id)
        val sessionB = authHelper.createTestSession(id = 2L, userId = userB.id)
        val tokenA = authHelper.createUserAndGetToken(userA, sessionA)
        val tokenB = authHelper.createUserAndGetToken(userB, sessionB)
        val fixtureA = createCertificateFixture()
        val fixtureB = createCertificateFixture()
        val fixtureA2 = createCertificateFixture()

        // Register two workers for user A
        client.post(href(WorkerResource.Register())) {
            authenticate(tokenA)
            contentType(ContentType.Application.Json)
            setBody(
                RegisterWorkerRequest(
                    workerUid = "worker-a-1",
                    displayName = "worker-a-1",
                    certificatePem = fixtureA.certificatePem,
                    allowedScopes = listOf("messages:read")
                )
            )
        }
        client.post(href(WorkerResource.Register())) {
            authenticate(tokenA)
            contentType(ContentType.Application.Json)
            setBody(
                RegisterWorkerRequest(
                    workerUid = "worker-a-2",
                    displayName = "worker-a-2",
                    certificatePem = fixtureA2.certificatePem,
                    allowedScopes = listOf("messages:read")
                )
            )
        }

        // Register one worker for user B
        client.post(href(WorkerResource.Register())) {
            authenticate(tokenB)
            contentType(ContentType.Application.Json)
            setBody(
                RegisterWorkerRequest(
                    workerUid = "worker-b-1",
                    displayName = "worker-b-1",
                    certificatePem = fixtureB.certificatePem,
                    allowedScopes = listOf("messages:read")
                )
            )
        }

        // User A should see exactly 2 workers
        val listResponseA = client.get(href(WorkerResource())) {
            authenticate(tokenA)
        }
        assertEquals(HttpStatusCode.OK, listResponseA.status)
        val workersA = listResponseA.body<List<eu.torvian.chatbot.common.models.worker.WorkerDto>>()
        assertEquals(2, workersA.size)
        assertTrue(workersA.all { it.ownerUserId == userA.id })

        // User B should see exactly 1 worker
        val listResponseB = client.get(href(WorkerResource())) {
            authenticate(tokenB)
        }
        assertEquals(HttpStatusCode.OK, listResponseB.status)
        val workersB = listResponseB.body<List<eu.torvian.chatbot.common.models.worker.WorkerDto>>()
        assertEquals(1, workersB.size)
        assertEquals(userB.id, workersB.single().ownerUserId)
    }

    @Test
    fun `GET workers - unauthenticated request returns 401`() = workerTestApplication {
        val response = client.get(href(WorkerResource()))
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `PATCH workers id - owner can update their worker`() = workerTestApplication {
        val authToken = authHelper.createUserAndGetToken(testUser)
        val fixture = createCertificateFixture()

        // Register a worker first
        val registerResponse = client.post(href(WorkerResource.Register())) {
            authenticate(authToken)
            contentType(ContentType.Application.Json)
            setBody(
                RegisterWorkerRequest(
                    workerUid = "worker-update-test",
                    displayName = "original-name",
                    certificatePem = fixture.certificatePem,
                    allowedScopes = listOf("messages:read")
                )
            )
        }
        assertEquals(HttpStatusCode.Created, registerResponse.status)
        val createdWorker = registerResponse.body<RegisterWorkerResponse>().worker

        // Update the worker
        val updateResponse = client.patch(href(WorkerResource.Id(id = createdWorker.id))) {
            authenticate(authToken)
            contentType(ContentType.Application.Json)
            setBody(
                UpdateWorkerRequest(
                    displayName = "updated-name",
                    allowedScopes = listOf("messages:read", "messages:write")
                )
            )
        }

        assertEquals(HttpStatusCode.OK, updateResponse.status)
        val updatedWorker = updateResponse.body<eu.torvian.chatbot.common.models.worker.WorkerDto>()
        assertEquals("updated-name", updatedWorker.displayName)
        assertEquals(listOf("messages:read", "messages:write"), updatedWorker.allowedScopes)
        // Immutable fields should remain unchanged
        assertEquals(createdWorker.workerUid, updatedWorker.workerUid)
        assertEquals(createdWorker.certificateFingerprint, updatedWorker.certificateFingerprint)
    }

    @Test
    fun `PATCH workers id - non-owner returns 403`() = workerTestApplication {
        // Use different user IDs to avoid conflict with testUser (id=1L) from setUp
        val userA = testUser.copy(id = 2L, username = "owner-a", email = "owner-a@example.com")
        val userB = testUser.copy(id = 3L, username = "owner-b", email = "owner-b@example.com")
        val sessionA = authHelper.createTestSession(id = 2L, userId = userA.id)
        val sessionB = authHelper.createTestSession(id = 3L, userId = userB.id)
        val tokenA = authHelper.createUserAndGetToken(userA, sessionA)
        val tokenB = authHelper.createUserAndGetToken(userB, sessionB)
        val fixture = createCertificateFixture()

        // User A registers a worker
        val registerResponse = client.post(href(WorkerResource.Register())) {
            authenticate(tokenA)
            contentType(ContentType.Application.Json)
            setBody(
                RegisterWorkerRequest(
                    workerUid = "worker-owned-by-a",
                    displayName = "worker-a",
                    certificatePem = fixture.certificatePem,
                    allowedScopes = listOf("messages:read")
                )
            )
        }
        val workerId = registerResponse.body<RegisterWorkerResponse>().worker.id

        // User B tries to update User A's worker
        val updateResponse = client.patch(href(WorkerResource.Id(id = workerId))) {
            authenticate(tokenB)
            contentType(ContentType.Application.Json)
            setBody(
                UpdateWorkerRequest(
                    displayName = "hacked-name",
                    allowedScopes = listOf("admin")
                )
            )
        }

        assertEquals(HttpStatusCode.Forbidden, updateResponse.status)
        val error = updateResponse.body<ApiError>()
        assertEquals(CommonApiErrorCodes.PERMISSION_DENIED.code, error.code)
    }

    @Test
    fun `PATCH workers id - not found returns 404`() = workerTestApplication {
        val authToken = authHelper.createUserAndGetToken(testUser)

        val response = client.patch(href(WorkerResource.Id(id = 9999L))) {
            authenticate(authToken)
            contentType(ContentType.Application.Json)
            setBody(
                UpdateWorkerRequest(
                    displayName = "some-name",
                    allowedScopes = emptyList()
                )
            )
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
    }

    @Test
    fun `DELETE workers id - owner can delete their worker`() = workerTestApplication {
        val authToken = authHelper.createUserAndGetToken(testUser)
        val fixture = createCertificateFixture()

        // Register a worker first
        val registerResponse = client.post(href(WorkerResource.Register())) {
            authenticate(authToken)
            contentType(ContentType.Application.Json)
            setBody(
                RegisterWorkerRequest(
                    workerUid = "worker-delete-test",
                    displayName = "to-be-deleted",
                    certificatePem = fixture.certificatePem,
                    allowedScopes = listOf("messages:read")
                )
            )
        }
        assertEquals(HttpStatusCode.Created, registerResponse.status)
        val workerId = registerResponse.body<RegisterWorkerResponse>().worker.id

        // Delete the worker
        val deleteResponse = client.delete(href(WorkerResource.Id(id = workerId))) {
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

        // Verify worker is gone
        val listResponse = client.get(href(WorkerResource())) {
            authenticate(authToken)
        }
        val workers = listResponse.body<List<eu.torvian.chatbot.common.models.worker.WorkerDto>>()
        assertTrue(workers.none { it.id == workerId })
    }

    @Test
    fun `DELETE workers id - non-owner returns 403`() = workerTestApplication {
        // Use different user IDs to avoid conflict with testUser (id=1L) from setUp
        val userA = testUser.copy(id = 4L, username = "owner-a-del", email = "owner-a-del@example.com")
        val userB = testUser.copy(id = 5L, username = "owner-b-del", email = "owner-b-del@example.com")
        val sessionA = authHelper.createTestSession(id = 4L, userId = userA.id)
        val sessionB = authHelper.createTestSession(id = 5L, userId = userB.id)
        val tokenA = authHelper.createUserAndGetToken(userA, sessionA)
        val tokenB = authHelper.createUserAndGetToken(userB, sessionB)
        val fixture = createCertificateFixture()

        // User A registers a worker
        val registerResponse = client.post(href(WorkerResource.Register())) {
            authenticate(tokenA)
            contentType(ContentType.Application.Json)
            setBody(
                RegisterWorkerRequest(
                    workerUid = "worker-owned-by-a-delete",
                    displayName = "worker-a",
                    certificatePem = fixture.certificatePem,
                    allowedScopes = listOf("messages:read")
                )
            )
        }
        val workerId = registerResponse.body<RegisterWorkerResponse>().worker.id

        // User B tries to delete User A's worker
        val deleteResponse = client.delete(href(WorkerResource.Id(id = workerId))) {
            authenticate(tokenB)
        }

        assertEquals(HttpStatusCode.Forbidden, deleteResponse.status)
        val error = deleteResponse.body<ApiError>()
        assertEquals(CommonApiErrorCodes.PERMISSION_DENIED.code, error.code)
    }

    @Test
    fun `DELETE workers id - not found returns 404`() = workerTestApplication {
        val authToken = authHelper.createUserAndGetToken(testUser)

        val response = client.delete(href(WorkerResource.Id(id = 9999L))) {
            authenticate(authToken)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
    }

    private data class CertificateFixture(
        val certificatePem: String,
        val fingerprint: String
    )

    private fun createCertificateFixture(): CertificateFixture {
        val keyPair = certificateService.generateRSAKeyPair()
        val certificate = certificateService.generateSelfSignedCertificate(
            keyPair = keyPair,
            subjectDN = "CN=worker-routes-test"
        )
        return CertificateFixture(
            certificatePem = certificateService.certificateToPem(certificate),
            fingerprint = certificateService.computeCertificateFingerprint(certificate)
        )
    }
}

package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.resources.WorkerResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.api.worker.RegisterWorkerRequest
import eu.torvian.chatbot.common.models.api.worker.RegisterWorkerResponse
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



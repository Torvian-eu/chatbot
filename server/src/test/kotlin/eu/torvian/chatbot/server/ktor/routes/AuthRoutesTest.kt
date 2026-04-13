package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.CommonUserGroups
import eu.torvian.chatbot.common.api.resources.AuthResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.api.auth.LoginRequest
import eu.torvian.chatbot.common.models.api.auth.LoginResponse
import eu.torvian.chatbot.common.models.api.auth.RefreshTokenRequest
import eu.torvian.chatbot.common.models.api.auth.RegisterRequest
import eu.torvian.chatbot.common.models.api.auth.ServiceTokenChallengeRequest
import eu.torvian.chatbot.common.models.api.auth.ServiceTokenChallengeResponse
import eu.torvian.chatbot.common.models.api.auth.ServiceTokenRequest
import eu.torvian.chatbot.common.models.api.auth.ServiceTokenResponse
import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.service.core.UserGroupService
import eu.torvian.chatbot.server.service.core.WorkerService
import eu.torvian.chatbot.server.service.security.CertificateService
import eu.torvian.chatbot.server.service.security.PasswordService
import eu.torvian.chatbot.server.testutils.auth.TestAuthHelper
import eu.torvian.chatbot.server.testutils.auth.authenticate
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import eu.torvian.chatbot.server.testutils.ktor.KtorTestApp
import eu.torvian.chatbot.server.testutils.ktor.myTestApplication
import com.auth0.jwt.JWT
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for Authentication API routes.
 *
 * This test suite verifies the HTTP endpoints for authentication management:
 * - POST /api/v1/auth/register - User registration
 * - POST /api/v1/auth/login - User login
 * - POST /api/v1/auth/logout - User logout
 * - GET /api/v1/auth/me - Get current user profile
 */
class AuthRoutesTest {
    private lateinit var container: DIContainer
    private lateinit var authTestApplication: KtorTestApp
    private lateinit var testDataManager: TestDataManager
    private lateinit var authHelper: TestAuthHelper
    private lateinit var certificateService: CertificateService
    private lateinit var workerService: WorkerService

    // Test data
    private val testUser = UserEntity(
        id = 1L,
        username = "testuser",
        email = "test@example.com",
        passwordHash = "hashed-password",
        status = UserStatus.ACTIVE,
        createdAt = TestDefaults.DEFAULT_INSTANT,
        updatedAt = TestDefaults.DEFAULT_INSTANT,
        lastLogin = null
    )
    private val testGroup = TestDefaults.chatGroup1.copy(id = 1L)

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        val apiRoutesKtor: ApiRoutesKtor = container.get()

        authTestApplication = myTestApplication(
            container = container,
            routing = {
                apiRoutesKtor.configureAuthRoutes(this)
            }
        )

        testDataManager = container.get()
        authHelper = TestAuthHelper(container)
        certificateService = container.get()
        workerService = container.get()

        // Setup required tables
        testDataManager.createTables(
            setOf(
                Table.USERS,
                Table.CHAT_GROUPS,
                Table.USER_SESSIONS,
                Table.ROLE_PERMISSIONS,
                Table.PERMISSIONS,
                Table.ROLES,
                Table.USER_ROLE_ASSIGNMENTS,
                Table.USER_GROUPS,
                Table.USER_GROUP_MEMBERSHIPS,
                Table.WORKERS,
                Table.WORKER_AUTH_CHALLENGES
            )
        )

        // Create the "All Users" group required for user registration
        val userGroupService = container.get<UserGroupService>()
        userGroupService.createGroup(
            name = CommonUserGroups.ALL_USERS,
            description = "Special group for all users"
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    // ========== Registration Tests ==========

    @Test
    fun `POST auth register - successful registration returns 201`() = authTestApplication {
        // Arrange
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup)
            )
        )

        val registerRequest = RegisterRequest(
            username = "newuser",
            password = "Gsfaf^3gd",
            email = "newuser@example.com"
        )

        // Act
        val response = client.post(href(AuthResource.Register())) {
            contentType(ContentType.Application.Json)
            setBody(registerRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.Created, response.status)
        val user = response.body<User>()
        assertEquals("newuser", user.username)
        assertEquals("newuser@example.com", user.email)
        assertEquals(UserStatus.DISABLED, user.status)
        assertNotNull(user.id)
    }

    @Test
    fun `POST auth register - duplicate username returns 409`() = authTestApplication {
        // Arrange
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup)
            )
        )
        testDataManager.insertUser(testUser)

        val registerRequest = RegisterRequest(
            username = testUser.username,
            password = "Gsasf^3gdk",
            email = "different@example.com"
        )

        // Act
        val response = client.post(href(AuthResource.Register())) {
            contentType(ContentType.Application.Json)
            setBody(registerRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.Conflict, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.ALREADY_EXISTS.code, error.code)
        assertEquals("Username already exists", error.message)
    }

    @Test
    fun `POST auth register - duplicate email returns 409`() = authTestApplication {
        // Arrange
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup)
            )
        )
        testDataManager.insertUser(testUser)

        val registerRequest = RegisterRequest(
            username = "differentuser",
            password = "Gsfaf^3gdr",
            email = testUser.email
        )

        // Act
        val response = client.post(href(AuthResource.Register())) {
            contentType(ContentType.Application.Json)
            setBody(registerRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.Conflict, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.ALREADY_EXISTS.code, error.code)
        assertEquals("Email already exists", error.message)
    }

    @Test
    fun `POST auth register - weak password returns 400`() = authTestApplication {
        // Arrange
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup)
            )
        )

        val registerRequest = RegisterRequest(
            username = "newuser",
            password = "weak",
            email = "newuser@example.com"
        )

        // Act
        val response = client.post(href(AuthResource.Register())) {
            contentType(ContentType.Application.Json)
            setBody(registerRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertEquals("Password too weak", error.message)
    }

    @Test
    fun `POST auth register - invalid input returns 400`() = authTestApplication {
        // Arrange
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup)
            )
        )

        val registerRequest = RegisterRequest(
            username = "", // Invalid empty username
            password = "securePassword123!",
            email = "newuser@example.com"
        )

        // Act
        val response = client.post(href(AuthResource.Register())) {
            contentType(ContentType.Application.Json)
            setBody(registerRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertEquals("Invalid input", error.message)
    }

    // ========== Login Tests ==========

    @Test
    fun `POST auth login - successful login returns 200 with tokens`() = authTestApplication {
        // Arrange
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup)
            )
        )

        // Get the password service from DI container and hash the password properly
        val passwordService: PasswordService = container.get()
        val plainPassword = "correctPassword"
        val hashedPassword = passwordService.hashPassword(plainPassword)

        val testUserWithHashedPassword = testUser.copy(passwordHash = hashedPassword)
        testDataManager.insertUser(testUserWithHashedPassword)

        val loginRequest = LoginRequest(
            username = testUser.username,
            password = plainPassword
        )

        // Act
        val response = client.post(href(AuthResource.Login())) {
            contentType(ContentType.Application.Json)
            setBody(loginRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val loginResponse = response.body<LoginResponse>()
        assertEquals(testUser.username, loginResponse.user.username)
        assertEquals(testUser.email, loginResponse.user.email)
        assertNotNull(loginResponse.accessToken)
        assertNotNull(loginResponse.expiresAt)
        val accessExpiryMs = JWT.decode(loginResponse.accessToken).expiresAt.time
        val loginExpiryMs = loginResponse.expiresAt.toEpochMilliseconds()
        assertTrue(loginExpiryMs >= accessExpiryMs)
        assertTrue(loginExpiryMs - accessExpiryMs < 1000)
    }

    @Test
    fun `POST auth login - invalid credentials returns 401`() = authTestApplication {
        // Arrange
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup)
            )
        )
        testDataManager.insertUser(testUser)

        val loginRequest = LoginRequest(
            username = testUser.username,
            password = "wrongPassword"
        )

        // Act
        val response = client.post(href(AuthResource.Login())) {
            contentType(ContentType.Application.Json)
            setBody(loginRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_CREDENTIALS.code, error.code)
        assertEquals("Invalid credentials", error.message)
    }

    @Test
    fun `POST auth login - non-existent user returns 401`() = authTestApplication {
        // Arrange
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup)
            )
        )

        val loginRequest = LoginRequest(
            username = "nonexistentuser",
            password = "anyPassword"
        )

        // Act
        val response = client.post(href(AuthResource.Login())) {
            contentType(ContentType.Application.Json)
            setBody(loginRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_CREDENTIALS.code, error.code)
        assertEquals("Invalid credentials", error.message)
    }

    // ========== Logout Tests ==========

    @Test
    fun `POST auth logout - successful logout returns 204`() = authTestApplication {
        // Arrange
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup)
            )
        )

        val authToken = authHelper.createUserAndGetToken(testUser)

        // Act
        val response = client.post(href(AuthResource.Logout())) {
            authenticate(authToken)
        }

        // Assert
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `POST auth logout - without authentication returns 401`() = authTestApplication {
        // Act
        val response = client.post(href(AuthResource.Logout()))

        // Assert
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST auth logout - with invalid token returns 401`() = authTestApplication {
        // Act
        val response = client.post(href(AuthResource.Logout())) {
            authenticate("invalid.token.here")
        }

        // Assert
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ========== Logout All Tests ==========

    @Test
    fun `POST auth logout-all - successful logout from all sessions returns 204`() = authTestApplication {
        // Arrange
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup)
            )
        )

        val authToken = authHelper.createUserAndGetToken(testUser)

        // Act
        val response = client.post(href(AuthResource.LogoutAll())) {
            authenticate(authToken)
        }

        // Assert
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `POST auth logout-all - without authentication returns 401`() = authTestApplication {
        // Act
        val response = client.post(href(AuthResource.LogoutAll()))

        // Assert
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST auth logout-all - with invalid token returns 401`() = authTestApplication {
        // Act
        val response = client.post(href(AuthResource.LogoutAll())) {
            authenticate("invalid.token.here")
        }

        // Assert
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ========== Refresh Token Tests ==========

    @Test
    fun `POST auth refresh - successful refresh returns 200 with new tokens`() = authTestApplication {
        // Arrange
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup)
            )
        )

        // Get the password service from DI container and hash the password properly
        val passwordService: PasswordService = container.get()
        val plainPassword = "correctPassword"
        val hashedPassword = passwordService.hashPassword(plainPassword)

        val testUserWithHashedPassword = testUser.copy(passwordHash = hashedPassword)
        testDataManager.insertUser(testUserWithHashedPassword)

        // Login to get a refresh token
        val loginRequest = LoginRequest(
            username = testUser.username,
            password = plainPassword
        )

        val loginResponse = client.post(href(AuthResource.Login())) {
            contentType(ContentType.Application.Json)
            setBody(loginRequest)
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status) // Ensure login succeeds
        val loginResult = loginResponse.body<LoginResponse>()
        val refreshRequest = RefreshTokenRequest(refreshToken = loginResult.refreshToken)

        // Act
        val response = client.post(href(AuthResource.Refresh())) {
            contentType(ContentType.Application.Json)
            setBody(refreshRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val refreshResponse = response.body<LoginResponse>()
        assertEquals(testUser.username, refreshResponse.user.username)
        assertEquals(testUser.email, refreshResponse.user.email)
        assertNotNull(refreshResponse.accessToken)
        assertNotNull(refreshResponse.refreshToken)
        assertNotNull(refreshResponse.expiresAt)
        // The new tokens should be different from the original ones
        assertNotEquals(loginResult.accessToken, refreshResponse.accessToken)
        assertNotEquals(loginResult.refreshToken, refreshResponse.refreshToken)
    }

    @Test
    fun `POST auth refresh - with invalid refresh token returns 401`() = authTestApplication {
        // Arrange
        val refreshRequest = RefreshTokenRequest(refreshToken = "invalid.refresh.token")

        // Act
        val response = client.post(href(AuthResource.Refresh())) {
            contentType(ContentType.Application.Json)
            setBody(refreshRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_CREDENTIALS.code, error.code)
        assertEquals("Invalid refresh token", error.message)
    }

    @Test
    fun `POST auth refresh - with access token instead of refresh token returns 401`() = authTestApplication {
        // Arrange
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup)
            )
        )

        val authToken = authHelper.createUserAndGetToken(testUser)
        val refreshRequest = RefreshTokenRequest(refreshToken = authToken) // Using access token instead

        // Act
        val response = client.post(href(AuthResource.Refresh())) {
            contentType(ContentType.Application.Json)
            setBody(refreshRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_CREDENTIALS.code, error.code)
        assertEquals("Invalid refresh token", error.message)
    }

    // ========== Get Current User Tests ==========

    // ========== Worker Service Token Tests ==========

    @Test
    fun `POST auth service-token challenge - successful issuance returns 200`() = authTestApplication {
        val fixture = registerWorkerForServiceToken()

        val response = client.post(href(AuthResource.ServiceTokenChallenge())) {
            contentType(ContentType.Application.Json)
            setBody(
                ServiceTokenChallengeRequest(
                    workerUid = fixture.workerUid,
                    certificateFingerprint = fixture.fingerprint
                )
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<ServiceTokenChallengeResponse>()
        assertEquals(fixture.workerUid, body.workerUid)
        assertTrue(body.challenge.challengeId.isNotBlank())
        assertTrue(body.challenge.challenge.startsWith("worker:${fixture.workerUid}:"))
    }

    @Test
    fun `POST auth service-token challenge - mismatched worker id returns 404`() = authTestApplication {
        val fixture = registerWorkerForServiceToken()

        val response = client.post(href(AuthResource.ServiceTokenChallenge())) {
            contentType(ContentType.Application.Json)
            setBody(
                ServiceTokenChallengeRequest(
                    workerUid = "${fixture.workerUid}-other",
                    certificateFingerprint = fixture.fingerprint
                )
            )
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertEquals("Worker not found", error.message)
    }

    @Test
    fun `POST auth service-token - successful exchange returns 200 with service jwt`() = authTestApplication {
        val fixture = registerWorkerForServiceToken()

        val challengeResponse = client.post(href(AuthResource.ServiceTokenChallenge())) {
            contentType(ContentType.Application.Json)
            setBody(
                ServiceTokenChallengeRequest(
                    workerUid = fixture.workerUid,
                    certificateFingerprint = fixture.fingerprint
                )
            )
        }
        assertEquals(HttpStatusCode.OK, challengeResponse.status)
        val challengeBody = challengeResponse.body<ServiceTokenChallengeResponse>()
        val signatureBase64 = signChallenge(challengeBody.challenge.challenge, fixture.privateKey)

        val response = client.post(href(AuthResource.ServiceToken())) {
            contentType(ContentType.Application.Json)
            setBody(
                ServiceTokenRequest(
                    workerUid = fixture.workerUid,
                    challengeId = challengeBody.challenge.challengeId,
                    signatureBase64 = signatureBase64
                )
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val tokenBody = response.body<ServiceTokenResponse>()
        assertEquals(fixture.workerUid, tokenBody.worker.workerUid)
        assertEquals(testUser.id, tokenBody.worker.ownerUserId)
        assertEquals(listOf("messages:read"), tokenBody.worker.allowedScopes)

        val decoded = JWT.decode(tokenBody.accessToken)
        assertEquals("service", decoded.getClaim("principalType").asString())
        assertEquals("access", decoded.getClaim("tokenType").asString())
        assertEquals(fixture.workerId, decoded.getClaim("workerId").asLong())
        assertEquals(testUser.id, decoded.getClaim("ownerUserId").asLong())
        assertEquals(listOf("messages:read"), decoded.getClaim("scope").asList(String::class.java))

        val tokenExpiryMs = decoded.expiresAt.time
        val responseExpiryMs = tokenBody.expiresAt.toEpochMilliseconds()
        assertTrue(responseExpiryMs >= tokenExpiryMs)
        assertTrue(responseExpiryMs - tokenExpiryMs < 1000)
    }

    @Test
    fun `POST auth service-token - invalid signature returns 401`() = authTestApplication {
        val fixture = registerWorkerForServiceToken()

        val challengeResponse = client.post(href(AuthResource.ServiceTokenChallenge())) {
            contentType(ContentType.Application.Json)
            setBody(
                ServiceTokenChallengeRequest(
                    workerUid = fixture.workerUid,
                    certificateFingerprint = fixture.fingerprint
                )
            )
        }
        assertEquals(HttpStatusCode.OK, challengeResponse.status)
        val challengeBody = challengeResponse.body<ServiceTokenChallengeResponse>()

        val invalidSignature = signChallenge(
            challengeBody.challenge.challenge,
            certificateService.generateRSAKeyPair().private
        )

        val response = client.post(href(AuthResource.ServiceToken())) {
            contentType(ContentType.Application.Json)
            setBody(
                ServiceTokenRequest(
                    workerUid = fixture.workerUid,
                    challengeId = challengeBody.challenge.challengeId,
                    signatureBase64 = invalidSignature
                )
            )
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_CREDENTIALS.code, error.code)
        assertEquals("Invalid worker authentication", error.message)
    }

    @Test
    fun `POST auth service-token - unknown worker returns 404`() = authTestApplication {
        val response = client.post(href(AuthResource.ServiceToken())) {
            contentType(ContentType.Application.Json)
            setBody(
                ServiceTokenRequest(
                    workerUid = "missing-worker",
                    challengeId = "missing",
                    signatureBase64 = "invalid"
                )
            )
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertEquals("Worker not found", error.message)
    }

    @Test
    fun `GET auth me - returns current user profile`() = authTestApplication {
        // Arrange
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup)
            )
        )

        val authToken = authHelper.createUserAndGetToken(testUser)

        // Act
        val response = client.get(href(AuthResource.Me())) {
            authenticate(authToken)
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val user = response.body<User>()
        assertEquals(testUser.id, user.id)
        assertEquals(testUser.username, user.username)
        assertEquals(testUser.email, user.email)
    }

    @Test
    fun `GET auth me - without authentication returns 401`() = authTestApplication {
        // Act
        val response = client.get(href(AuthResource.Me()))

        // Assert
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET auth me - with invalid token returns 401`() = authTestApplication {
        // Act
        val response = client.get(href(AuthResource.Me())) {
            authenticate("invalid.token.here")
        }

        // Assert
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET auth me - with refresh token returns 401`() = authTestApplication {
        // Arrange
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup)
            )
        )

        val passwordService: PasswordService = container.get()
        val plainPassword = "correctPassword"
        val hashedPassword = passwordService.hashPassword(plainPassword)
        val testUserWithHashedPassword = testUser.copy(passwordHash = hashedPassword)
        testDataManager.insertUser(testUserWithHashedPassword)

        val loginResponse = client.post(href(AuthResource.Login())) {
            contentType(ContentType.Application.Json)
            setBody(
                LoginRequest(
                    username = testUser.username,
                    password = plainPassword
                )
            )
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status)
        val refreshToken = loginResponse.body<LoginResponse>().refreshToken

        // Act
        val response = client.get(href(AuthResource.Me())) {
            authenticate(refreshToken)
        }

        // Assert
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET auth me - with token for non-existent user returns 401`() = authTestApplication {
        // Arrange - Create token for user that doesn't exist in database
        val authToken = authHelper.generateToken(999L, 1L)

        // Act
        val response = client.get(href(AuthResource.Me())) {
            authenticate(authToken)
        }

        // Assert
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    private data class WorkerCredentialFixture(
        val workerId: Long,
        val workerUid: String,
        val fingerprint: String,
        val privateKey: PrivateKey
    )

    private suspend fun registerWorkerForServiceToken(ownerUserId: Long = testUser.id): WorkerCredentialFixture {
        testDataManager.insertUser(testUser.copy(id = ownerUserId))

        val keyPair = certificateService.generateRSAKeyPair()
        val certificate = certificateService.generateSelfSignedCertificate(
            keyPair = keyPair,
            subjectDN = "CN=worker-route-test"
        )
        val certificatePem = certificateService.certificateToPem(certificate)
        val fingerprint = certificateService.computeCertificateFingerprint(certificate)

        val worker = workerService.registerWorker(
            ownerUserId = ownerUserId,
            workerUid = "worker-route-test",
            displayName = "route-test-worker",
            certificatePem = certificatePem,
            allowedScopes = listOf("messages:read")
        ).getOrNull()
        assertNotNull(worker)

        return WorkerCredentialFixture(
            workerId = worker.id,
            workerUid = worker.workerUid,
            fingerprint = fingerprint,
            privateKey = keyPair.private
        )
    }

    private fun signChallenge(challenge: String, privateKey: PrivateKey): String {
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(challenge.toByteArray())
        return Base64.getEncoder().encodeToString(signer.sign())
    }
}

package eu.torvian.chatbot.server.service.security

import arrow.core.left
import arrow.core.right
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.user.Permission
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.server.data.dao.UserSessionDao
import eu.torvian.chatbot.server.data.dao.WorkerDao
import eu.torvian.chatbot.server.data.dao.error.UserSessionError
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.data.entities.UserSessionEntity
import eu.torvian.chatbot.server.data.entities.WorkerEntity
import eu.torvian.chatbot.server.data.entities.mappers.toUser
import eu.torvian.chatbot.server.domain.security.JwtConfig
import eu.torvian.chatbot.server.service.core.UserService
import eu.torvian.chatbot.server.service.core.error.auth.UserNotFoundError
import eu.torvian.chatbot.server.service.security.error.RefreshTokenError
import io.ktor.server.auth.jwt.*
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class TokenServiceImplTest {

    private val userService = mockk<UserService>()
    private val userSessionDao = mockk<UserSessionDao>()
    private val workerDao = mockk<WorkerDao>()
    private val authorizationService = mockk<AuthorizationService>()
    private val transactionScope = mockk<TransactionScope>()

    private val jwtConfig = JwtConfig(
        secret = "test-secret-key-for-testing-purposes-only",
        tokenExpirationMs = 15 * 60 * 1000L,
        refreshExpirationMs = 7 * 24 * 60 * 60 * 1000L
    )

    private val tokenService = TokenServiceImpl(
        userService,
        jwtConfig,
        userSessionDao,
        workerDao,
        authorizationService,
        transactionScope
    )

    private val testUser = UserEntity(
        id = 1L,
        username = "testuser",
        passwordHash = "hashedpassword",
        email = "test@example.com",
        status = UserStatus.ACTIVE,
        createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        updatedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        lastLogin = null
    )

    private val testSession = UserSessionEntity(
        id = 100L,
        userId = testUser.id,
        deviceId = "test-device-id",
        expiresAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000),
        ipAddress = "192.168.1.1",
        isRestricted = false,
        createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        lastAccessed = Instant.fromEpochMilliseconds(System.currentTimeMillis())
    )

    @BeforeEach
    fun setUp() {
        clearMocks(userService, userSessionDao, workerDao, authorizationService, transactionScope)
        coEvery { transactionScope.transaction<Any>(any()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
    }

    @Test
    fun `validateCredential should successfully validate valid credential`() = runTest {
        // Given
        val token = jwtConfig.generateAccessToken(testUser.id, testSession.id, isRestricted = false)
        val decodedJWT = JWT.decode(token)
        val credential = JWTCredential(decodedJWT)

        coEvery { userSessionDao.getSessionById(testSession.id) } returns testSession.right()
        coEvery { userService.getUserById(testUser.id) } returns testUser.toUser().right()
        coEvery { userSessionDao.updateLastAccessed(testSession.id, any()) } returns Unit.right()

        // When
        val result = tokenService.validateCredential(credential)

        // Then
        assertNotNull(result)
        assertEquals(testUser.toUser(), result.user)
        assertEquals(testSession.id, result.sessionId)

        coVerify { userSessionDao.getSessionById(testSession.id) }
        coVerify { userService.getUserById(testUser.id) }
        coVerify { userSessionDao.updateLastAccessed(testSession.id, any()) }
    }

    @Test
    fun `validateCredential should return null when session not found`() = runTest {
        // Given
        val token = jwtConfig.generateAccessToken(testUser.id, testSession.id, isRestricted = false)
        val decodedJWT = JWT.decode(token)
        val credential = JWTCredential(decodedJWT)

        coEvery { userSessionDao.getSessionById(testSession.id) } returns
                UserSessionError.SessionNotFound(testSession.id).left()

        // When
        val result = tokenService.validateCredential(credential)

        // Then
        assertNull(result)
        coVerify { userSessionDao.getSessionById(testSession.id) }
    }

    @Test
    fun `validateCredential should return null when session expired`() = runTest {
        // Given
        val expiredSession =
            testSession.copy(expiresAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 1000))
        val token = jwtConfig.generateAccessToken(testUser.id, expiredSession.id, isRestricted = false)
        val decodedJWT = JWT.decode(token)
        val credential = JWTCredential(decodedJWT)

        coEvery { userSessionDao.getSessionById(expiredSession.id) } returns expiredSession.right()

        // When
        val result = tokenService.validateCredential(credential)

        // Then
        assertNull(result)
        coVerify { userSessionDao.getSessionById(expiredSession.id) }
    }

    @Test
    fun `validateCredential should return null when user not found`() = runTest {
        // Given
        val token = jwtConfig.generateAccessToken(testUser.id, testSession.id, isRestricted = false)
        val decodedJWT = JWT.decode(token)
        val credential = JWTCredential(decodedJWT)

        coEvery { userSessionDao.getSessionById(testSession.id) } returns testSession.right()
        coEvery { userService.getUserById(testUser.id) } returns UserNotFoundError.ById(testUser.id).left()

        // When
        val result = tokenService.validateCredential(credential)

        // Then
        assertNull(result)
        coVerify { userSessionDao.getSessionById(testSession.id) }
        coVerify { userService.getUserById(testUser.id) }
    }

    @Test
    fun `validateCredential should return null when token type is refresh`() = runTest {
        // Given
        val token = jwtConfig.generateRefreshToken(testUser.id, testSession.id)
        val decodedJWT = JWT.decode(token)
        val credential = JWTCredential(decodedJWT)

        // When
        val result = tokenService.validateCredential(credential)

        // Then
        assertNull(result)
        coVerify(exactly = 0) { userSessionDao.getSessionById(any()) }
        coVerify(exactly = 0) { userService.getUserById(any()) }
    }

    @Test
    fun `validateWorkerCredential should successfully validate valid worker token`() = runTest {
        // Given
        val worker = WorkerEntity(
            id = 200L,
            workerUid = "worker-200",
            ownerUserId = testUser.id,
            displayName = "worker-1",
            certificatePem = "pem",
            certificateFingerprint = "fingerprint",
            allowedScopes = listOf("messages:read"),
            createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
            lastSeenAt = null
        )
        val token = jwtConfig.generateServiceAccessToken(
            worker.id,
            worker.workerUid,
            worker.ownerUserId,
            listOf("messages:read")
        )

        coEvery { workerDao.getWorkerById(worker.id) } returns worker.right()

        // When
        val result = tokenService.validateWorkerCredential(JWTCredential(JWT.decode(token)))

        // Then
        assertNotNull(result)
        assertEquals(worker.id, result.workerId)
        assertEquals(worker.workerUid, result.workerUid)
        assertEquals(worker.ownerUserId, result.ownerUserId)
        assertEquals(listOf("messages:read"), result.scopes)
        coVerify { workerDao.getWorkerById(worker.id) }
    }

    @Test
    fun `validateWorkerCredential should return null when token type is refresh`() = runTest {
        // Given
        val token = JWT.create()
            .withIssuer(jwtConfig.issuer)
            .withAudience(jwtConfig.workerAudience)
            .withSubject("worker:200")
            .withClaim("principalType", "service")
            .withClaim("tokenType", "refresh")
            .withClaim("workerId", 200L)
            .withClaim("ownerUserId", testUser.id)
            .withArrayClaim("scope", arrayOf("messages:read"))
            .withIssuedAt(java.util.Date())
            .withExpiresAt(java.util.Date(System.currentTimeMillis() + 3600000))
            .sign(Algorithm.HMAC256(jwtConfig.secret))
        val credential = JWTCredential(JWT.decode(token))

        // When
        val result = tokenService.validateWorkerCredential(credential)

        // Then
        assertNull(result)
        coVerify(exactly = 0) { workerDao.getWorkerById(any()) }
    }

    @Test
    fun `refreshToken should successfully refresh tokens`() = runTest {
        // Given
        val refreshToken = jwtConfig.generateRefreshToken(testUser.id, testSession.id)
        val permissions = emptyList<Permission>()
        val newSession = testSession.copy(
            id = 101L,
            expiresAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000)
        )

        coEvery { userSessionDao.getSessionById(testSession.id) } returns testSession.right()
        coEvery { userService.getUserById(testUser.id) } returns testUser.toUser().right()
        coEvery { userSessionDao.deleteSession(testSession.id) } returns Unit.right()
        coEvery { userSessionDao.insertSession(any(), any(), any(), any(), any()) } returns newSession.right()
        coEvery { authorizationService.getUserPermissions(testUser.id) } returns permissions

        // When
        val result = tokenService.refreshToken(refreshToken, "192.168.1.1")

        // Then
        assertTrue(result.isRight())
        val loginResult = result.getOrNull()!!
        assertEquals(testUser.toUser(), loginResult.user)
        assertNotNull(loginResult.accessToken)
        assertNotNull(loginResult.refreshToken)
        coVerify { userSessionDao.getSessionById(testSession.id) }
        coVerify { userService.getUserById(testUser.id) }
        coVerify { userSessionDao.deleteSession(testSession.id) }
    }

    @Test
    fun `refreshToken should fail with invalid refresh token`() = runTest {
        // Given
        val invalidToken = "invalid.token.here"

        // When
        val result = tokenService.refreshToken(invalidToken, "192.168.1.1")

        // Then
        assertTrue(result.isLeft())
        assertEquals(RefreshTokenError.InvalidRefreshToken, result.leftOrNull())
    }
}

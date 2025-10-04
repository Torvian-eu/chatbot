package eu.torvian.chatbot.server.testutils.auth

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.common.models.UserStatus
import eu.torvian.chatbot.server.data.entities.UserSessionEntity
import eu.torvian.chatbot.server.domain.security.JwtConfig
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import io.ktor.client.request.*
import kotlinx.datetime.Instant

/**
 * Helper class for setting up authentication in tests.
 * 
 * This class provides utilities for creating test users, sessions, and JWT tokens
 * for use in integration tests that require authentication.
 */
class TestAuthHelper(private val container: DIContainer) {
    private val jwtConfig: JwtConfig = container.get()
    private val testDataManager: TestDataManager = container.get()
    
    /**
     * Default test user for authentication tests.
     */
    val defaultTestUser = UserEntity(
        id = 1L,
        username = "testuser",
        email = "test@example.com",
        passwordHash = "hashed-password",
        status = UserStatus.ACTIVE,
        createdAt = TestDefaults.DEFAULT_INSTANT,
        updatedAt = TestDefaults.DEFAULT_INSTANT,
        lastLogin = null
    )
    
    /**
     * Default test session for the default test user.
     */
    val defaultTestSession = UserSessionEntity(
        id = 1L,
        userId = defaultTestUser.id,
        expiresAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() + (24 * 60 * 60 * 1000)), // 24 hours from now
        createdAt = TestDefaults.DEFAULT_INSTANT,
        lastAccessed = TestDefaults.DEFAULT_INSTANT
    )

    val securePassword = "Gdsdw35!dfg"
    
    /**
     * Creates a test user and session, then returns a valid JWT token.
     * 
     * @param user The user entity to create (defaults to defaultTestUser)
     * @param session The session entity to create (defaults to defaultTestSession)
     * @return A valid JWT access token for the user
     */
    suspend fun createUserAndGetToken(
        user: UserEntity = defaultTestUser,
        session: UserSessionEntity = defaultTestSession
    ): String {
        // Insert test user and session
        testDataManager.insertUser(user)
        testDataManager.insertUserSession(session)
        
        // Generate JWT token
        return jwtConfig.generateAccessToken(user.id, session.id)
    }

    /**
     * Creates a test session for an existing user, then returns a valid JWT token.
     *
     * @param userId The user ID to create the session for
     * @param sessionId The session ID to create
     * @return A valid JWT access token for the user
     */
    suspend fun createSessionAndGetToken(
        userId: Long = defaultTestUser.id,
        sessionId: Long = 1L
    ): String {
        // Insert test session
        testDataManager.insertUserSession(UserSessionEntity(
            id = sessionId,
            userId = userId,
            expiresAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() + (24 * 60 * 60 * 1000)), // 24 hours from now
            createdAt = TestDefaults.DEFAULT_INSTANT,
            lastAccessed = TestDefaults.DEFAULT_INSTANT
        ))

        // Generate JWT token
        return jwtConfig.generateAccessToken(userId, sessionId)
    }
    
    /**
     * Generates a JWT token for an existing user and session.
     * 
     * @param userId The user ID
     * @param sessionId The session ID
     * @return A valid JWT access token
     */
    fun generateToken(userId: Long, sessionId: Long): String {
        return jwtConfig.generateAccessToken(userId, sessionId)
    }
    

    
    /**
     * Creates a test user with custom properties.
     *
     * @param id User ID (defaults to 1L)
     * @param username Username (defaults to "testuser")
     * @param email Email (defaults to "test@example.com")
     * @return UserEntity for testing
     */
    fun createTestUser(
        id: Long = 1L,
        username: String = "testuser",
        email: String = "test@example.com"
    ): UserEntity {
        return UserEntity(
            id = id,
            username = username,
            email = email,
            passwordHash = "hashed-password",
            status = UserStatus.ACTIVE,
            createdAt = TestDefaults.DEFAULT_INSTANT,
            updatedAt = TestDefaults.DEFAULT_INSTANT,
            lastLogin = null
        )
    }
    
    /**
     * Creates a test session with custom properties.
     *
     * @param id Session ID (defaults to 1L)
     * @param userId User ID (defaults to 1L)
     * @param expiresAt Expiration timestamp (defaults to 24 hours from now)
     * @return UserSessionEntity for testing
     */
    fun createTestSession(
        id: Long = 1L,
        userId: Long = 1L,
        expiresAt: Long = System.currentTimeMillis() + (24 * 60 * 60 * 1000)
    ): UserSessionEntity {
        return UserSessionEntity(
            id = id,
            userId = userId,
            expiresAt = Instant.fromEpochMilliseconds(expiresAt),
            createdAt = TestDefaults.DEFAULT_INSTANT,
            lastAccessed = TestDefaults.DEFAULT_INSTANT
        )
    }
}

/**
 * Extension function to add authentication header to HTTP requests.
 *
 * @param token The JWT token to use for authentication
 */
fun HttpRequestBuilder.authenticate(token: String) {
    header("Authorization", "Bearer $token")
}

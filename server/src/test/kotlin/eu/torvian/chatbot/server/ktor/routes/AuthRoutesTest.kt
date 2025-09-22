package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.resources.AuthResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.User
import eu.torvian.chatbot.common.models.auth.LoginRequest
import eu.torvian.chatbot.common.models.auth.LoginResponse
import eu.torvian.chatbot.common.models.auth.RefreshTokenRequest
import eu.torvian.chatbot.common.models.auth.RegisterRequest
import eu.torvian.chatbot.server.data.entities.UserEntity
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
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

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

    // Test data
    private val testUser = UserEntity(
        id = 1L,
        username = "testuser",
        email = "test@example.com",
        passwordHash = "hashed-password",
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

        // Setup required tables
        testDataManager.createTables(
            setOf(
                Table.USERS,
                Table.CHAT_GROUPS,
                Table.USER_SESSIONS
            )
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
}

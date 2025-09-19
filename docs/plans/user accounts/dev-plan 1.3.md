# Development Plan: Phase 1, Step 3 - Backend Services & JWT Authentication

## Overview

This plan details the implementation of core authentication services, API endpoints, service layer refactoring, and JWT authentication for Ktor routes as part of Phase 1, Step 3 of the User Accounts feature.

## Prerequisites

- Phase 1, Steps 1-2 completed (database schema and core DAOs implemented)
- User-related tables and ownership DAOs available
- JWT dependencies already present in project

## Implementation Tasks

### Task 1: Core Authentication Services Implementation

#### 1.1 PasswordService Implementation
**File**: `server/src/main/kotlin/eu/torvian/chatbot/server/service/security/PasswordService.kt`

```kotlin
interface PasswordService {
    fun hashPassword(plainPassword: String): String
    fun verifyPassword(plainPassword: String, hashedPassword: String): Boolean
    fun validatePasswordStrength(password: String): Either<PasswordValidationError, Unit>
}

class BCryptPasswordService : PasswordService {
    private val saltRounds = 12
    
    override fun hashPassword(plainPassword: String): String =
        BCrypt.hashpw(plainPassword, BCrypt.gensalt(saltRounds))
    
    override fun verifyPassword(plainPassword: String, hashedPassword: String): Boolean =
        BCrypt.checkpw(plainPassword, hashedPassword)
    
    override fun validatePasswordStrength(password: String): Either<PasswordValidationError, Unit> {
        // Implement password strength validation
    }
}
```

#### 1.2 UserService Implementation
**File**: `server/src/main/kotlin/eu/torvian/chatbot/server/service/core/UserService.kt`

```kotlin
interface UserService {
    suspend fun registerUser(username: String, password: String, email: String?): Either<RegisterUserError, UserEntity>
    suspend fun getUserByUsername(username: String): Either<UserNotFoundError, UserEntity>
    suspend fun getUserById(id: Long): Either<UserNotFoundError, UserEntity>
    suspend fun updateLastLogin(userId: Long): Either<UserNotFoundError, Unit>
}

class UserServiceImpl(
    private val userDao: UserDao,
    private val passwordService: PasswordService,
    private val userGroupDao: UserGroupDao,
    private val transactionScope: TransactionScope
) : UserService {
    // Implementation with automatic "All Users" group assignment
}
```

#### 1.3 AuthenticationService Implementation
**File**: `server/src/main/kotlin/eu/torvian/chatbot/server/service/security/AuthenticationService.kt`

```kotlin
interface AuthenticationService {
    suspend fun login(username: String, password: String): Either<LoginError, LoginResult>
    suspend fun logout(userId: Long): Either<LogoutError, Unit>
    suspend fun validateToken(token: String): Either<TokenValidationError, UserContext>
    suspend fun refreshToken(refreshToken: String): Either<RefreshTokenError, LoginResult>
}

data class LoginResult(
    val user: UserEntity,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Instant
)

class AuthenticationServiceImpl(
    private val userService: UserService,
    private val passwordService: PasswordService,
    private val jwtConfig: JwtConfig,
    private val userSessionDao: UserSessionDao,
    private val transactionScope: TransactionScope
) : AuthenticationService {
    // JWT token generation and validation logic
}
```

**Estimated Time**: 3-4 days

### Task 2: Authentication API Endpoints

#### 2.1 Authentication Route Configuration
**File**: `server/src/main/kotlin/eu/torvian/chatbot/server/ktor/routes/configureAuthRoutes.kt`

```kotlin
fun Route.configureAuthRoutes(
    authenticationService: AuthenticationService,
    userService: UserService
) {
    route("/api/v1/auth") {
        // POST /api/v1/auth/register - User registration
        post("/register") {
            val request = call.receive<RegisterRequest>()
            call.respondEither(
                userService.registerUser(request.username, request.password, request.email),
                HttpStatusCode.Created
            ) { error ->
                when (error) {
                    is RegisterUserError.UsernameAlreadyExists ->
                        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "Username already exists")
                    is RegisterUserError.EmailAlreadyExists ->
                        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "Email already exists")
                    is RegisterUserError.InvalidInput ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid input", "reason" to error.reason)
                }
            }
        }

        // POST /api/v1/auth/login - User login
        post("/login") {
            val request = call.receive<LoginRequest>()
            call.respondEither(
                authenticationService.login(request.username, request.password)
            ) { error ->
                when (error) {
                    is LoginError.InvalidCredentials ->
                        apiError(CommonApiErrorCodes.UNAUTHORIZED, "Invalid credentials")
                    is LoginError.UserNotFound ->
                        apiError(CommonApiErrorCodes.UNAUTHORIZED, "Invalid credentials")
                }
            }
        }

        // POST /api/v1/auth/logout - User logout
        authenticate(AuthSchemes.USER_JWT) {
            post("/logout") {
                val userId = call.getUserId()
                call.respondEither(
                    authenticationService.logout(userId),
                    HttpStatusCode.NoContent
                ) { error ->
                    apiError(CommonApiErrorCodes.INTERNAL, "Logout failed")
                }
            }
        }

        // GET /api/v1/auth/me - Get current user profile
        authenticate(AuthSchemes.USER_JWT) {
            get("/me") {
                val userId = call.getUserId()
                call.respondEither(
                    userService.getUserById(userId)
                ) { error ->
                    when (error) {
                        is UserNotFoundError ->
                            apiError(CommonApiErrorCodes.NOT_FOUND, "User not found")
                    }
                }
            }
        }
    }
}
```

#### 2.2 Request/Response Models
**Files**: `common/src/commonMain/kotlin/eu/torvian/chatbot/common/models/auth/`

```kotlin
@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val email: String? = null
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)

@Serializable
data class LoginResponse(
    val user: User,
    val accessToken: String,
    val expiresAt: Instant
)
```

**Estimated Time**: 2-3 days

### Task 3: JWT Authentication Infrastructure

#### 3.1 JWT Configuration Setup
**File**: `server/src/main/kotlin/eu/torvian/chatbot/server/domain/security/JwtConfig.kt`

```kotlin
data class JwtConfig(
    val issuer: String = "chatbot-server",
    val audience: String = "chatbot-users",
    val realm: String = "chatbot-realm",
    val secret: String,
    val tokenExpirationMs: Long = 24 * 60 * 60 * 1000L // 24 hours
) {
    private val algorithm: Algorithm = Algorithm.HMAC256(secret)

    val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(issuer)
        .withAudience(audience)
        .build()

    fun generateToken(userId: Long, role: UserRole): String {
        val now = System.currentTimeMillis()
        return JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(userId.toString())
            .withClaim("role", role.name)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + tokenExpirationMs))
            .sign(algorithm)
    }
}
```

#### 3.2 Authentication Utilities
**File**: `server/src/main/kotlin/eu/torvian/chatbot/server/ktor/auth/AuthUtils.kt`

```kotlin
fun ApplicationCall.getUserId(): Long {
    val principal = principal<JWTPrincipal>()
    val userId = principal?.payload?.subject?.toLongOrNull()
        ?: throw IllegalStateException("User ID not found in authenticated request")
    return userId
}

fun ApplicationCall.getUserRole(): UserRole {
    val principal = principal<JWTPrincipal>()
    val roleString = principal?.payload?.getClaim("role")?.asString()
        ?: throw IllegalStateException("User role not found in authenticated request")
    return UserRole.valueOf(roleString)
}
```

#### 3.3 Ktor Authentication Configuration
**Update**: `server/src/main/kotlin/eu/torvian/chatbot/server/ktor/configureKtor.kt`

```kotlin
fun Application.configureKtor() {
    // Existing configuration...
    
    // Install Authentication
    val jwtConfig: JwtConfig by inject()
    install(Authentication) {
        jwt(AuthSchemes.USER_JWT) {
            realm = jwtConfig.realm
            verifier(jwtConfig.verifier)
            validate { credential ->
                if (credential.payload.audience.contains(jwtConfig.audience)) {
                    JWTPrincipal(credential.payload)
                } else null
            }
        }
    }
}
```

**Estimated Time**: 1-2 days

### Task 4: Service Layer Refactoring

#### 4.1 SessionServiceImpl Refactoring
**File**: `server/src/main/kotlin/eu/torvian/chatbot/server/service/core/impl/SessionServiceImpl.kt`

**Key Changes**:
- Add `userId` parameter to all methods
- Replace direct DAO calls with ownership DAO calls
- Add ownership verification for operations
- Update transaction handling for ownership operations

```kotlin
class SessionServiceImpl(
    private val sessionDao: SessionDao,
    private val sessionOwnershipDao: SessionOwnershipDao, // New dependency
    private val messageDao: MessageDao,
    private val transactionScope: TransactionScope,
) : SessionService {

    override suspend fun getAllSessionsSummaries(userId: Long): List<ChatSessionSummary> {
        return transactionScope.transaction {
            sessionOwnershipDao.getAllSessionsForUser(userId)
                .map { it.toSummary() }
        }
    }

    override suspend fun createSession(userId: Long, name: String): Either<CreateSessionError, ChatSession> =
        transactionScope.transaction {
            either {
                ensure(!name.isBlank()) {
                    CreateSessionError.InvalidName("Session name cannot be blank.")
                }

                // Step 1: Create the session
                val newSession = sessionDao.insertSession(name)

                // Step 2: Set ownership
                sessionOwnershipDao.setOwner(newSession.id, userId).mapLeft { daoError ->
                    when (daoError) {
                        is SetOwnerError.ForeignKeyViolation ->
                            CreateSessionError.InvalidRelatedEntity("User with ID $userId may not exist.")
                        is SetOwnerError.AlreadyOwned ->
                            CreateSessionError.InvalidRelatedEntity("Session with ID ${newSession.id} is already owned.")
                    }
                }.bind()

                newSession
            }
        }

    // Similar updates for other methods...
}
```

#### 4.2 GroupServiceImpl Refactoring
**File**: `server/src/main/kotlin/eu/torvian/chatbot/server/service/core/impl/GroupServiceImpl.kt`

**Key Changes**:
- Add `userId` parameter to all methods
- Replace direct DAO calls with ownership DAO calls
- Add ownership verification for operations

```kotlin
class GroupServiceImpl(
    private val groupDao: GroupDao,
    private val groupOwnershipDao: GroupOwnershipDao, // New dependency
    private val sessionDao: SessionDao,
    private val transactionScope: TransactionScope,
) : GroupService {

    override suspend fun getAllGroups(userId: Long): List<ChatGroup> {
        return transactionScope.transaction {
            groupOwnershipDao.getAllGroupsForUser(userId)
        }
    }

    override suspend fun createGroup(userId: Long, name: String): Either<CreateGroupError, ChatGroup> =
        transactionScope.transaction {
            either {
                ensure(!name.isBlank()) {
                    CreateGroupError.InvalidName("Group name cannot be blank.")
                }

                // Step 1: Create the group
                val newGroup = groupDao.insertGroup(name)

                // Step 2: Set ownership
                groupOwnershipDao.setOwner(newGroup.id, userId).mapLeft { daoError ->
                    when (daoError) {
                        is SetOwnerError.ForeignKeyViolation ->
                            CreateGroupError.OwnershipError("Failed to set owner: User with ID $userId may not exist.")
                        is SetOwnerError.AlreadyOwned ->
                            CreateGroupError.OwnershipError("Failed to set owner: Group with ID ${newGroup.id} is already owned.")
                    }
                }.bind()

                newGroup
            }
        }

    // Similar updates for other methods...
}
```

**Estimated Time**: 2-3 days

### Task 5: Route Authentication Implementation

#### 5.1 Group Routes Authentication
**Update**: `server/src/main/kotlin/eu/torvian/chatbot/server/ktor/routes/configureGroupRoutes.kt`

```kotlin
fun Route.configureGroupRoutes(groupService: GroupService) {
    authenticate(AuthSchemes.USER_JWT) {
        // GET /api/v1/groups - List all groups for authenticated user
        get<GroupResource> {
            val userId = call.getUserId()
            call.respond(groupService.getAllGroups(userId))
        }

        // POST /api/v1/groups - Create a new group
        post<GroupResource> {
            val userId = call.getUserId()
            val request = call.receive<CreateGroupRequest>()
            call.respondEither(
                groupService.createGroup(userId, request.name), 
                HttpStatusCode.Created
            ) { error ->
                when (error) {
                    is CreateGroupError.InvalidName ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid group name", "reason" to error.reason)
                    is CreateGroupError.OwnershipError ->
                        apiError(CommonApiErrorCodes.INTERNAL, "Failed to set ownership", "reason" to error.reason)
                }
            }
        }

        // DELETE /api/v1/groups/{groupId} - Delete group by ID
        delete<GroupResource.ById> { resource ->
            val userId = call.getUserId()
            val groupId = resource.groupId
            call.respondEither(
                groupService.deleteGroup(userId, groupId), 
                HttpStatusCode.NoContent
            ) { error ->
                when (error) {
                    is DeleteGroupError.GroupNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Group not found", "groupId" to error.id.toString())
                    is DeleteGroupError.AccessDenied ->
                        apiError(CommonApiErrorCodes.FORBIDDEN, "Access denied", "reason" to error.reason)
                }
            }
        }

        // PUT /api/v1/groups/{groupId} - Rename group by ID
        put<GroupResource.ById> { resource ->
            val userId = call.getUserId()
            val groupId = resource.groupId
            val request = call.receive<RenameGroupRequest>()
            call.respondEither(
                groupService.renameGroup(userId, groupId, request.name)
            ) { error ->
                when (error) {
                    is RenameGroupError.GroupNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Group not found", "groupId" to error.id.toString())
                    is RenameGroupError.InvalidName ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid group name", "reason" to error.reason)
                    is RenameGroupError.AccessDenied ->
                        apiError(CommonApiErrorCodes.FORBIDDEN, "Access denied", "reason" to error.reason)
                }
            }
        }
    }
}
```

#### 5.2 Session Routes Authentication
**Update**: `server/src/main/kotlin/eu/torvian/chatbot/server/ktor/routes/configureSessionRoutes.kt`

```kotlin
fun Route.configureSessionRoutes(
    sessionService: SessionService,
    messageService: MessageService
) {
    val json: Json by inject()
    val logger: Logger = LogManager.getLogger("SessionRoutes")

    authenticate(AuthSchemes.USER_JWT) {
        // GET /api/v1/sessions - List all sessions for authenticated user
        get<SessionResource> {
            val userId = call.getUserId()
            call.respond(sessionService.getAllSessionsSummaries(userId))
        }

        // POST /api/v1/sessions - Create a new session
        post<SessionResource> {
            val userId = call.getUserId()
            val request = call.receive<CreateSessionRequest>()
            call.respondEither(
                sessionService.createSession(userId, request.name),
                HttpStatusCode.Created
            ) { error ->
                when (error) {
                    is CreateSessionError.InvalidName ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid session name provided", "reason" to error.reason)
                    is CreateSessionError.InvalidRelatedEntity ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid related entity ID provided", "details" to error.message)
                }
            }
        }

        // GET /api/v1/sessions/{sessionId} - Get session by ID with ownership check
        get<SessionResource.ById> { resource ->
            val userId = call.getUserId()
            val sessionId = resource.sessionId
            call.respondEither(sessionService.getSessionDetails(userId, sessionId)) { error ->
                when (error) {
                    is GetSessionDetailsError.SessionNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found", "sessionId" to error.id.toString())
                    is GetSessionDetailsError.AccessDenied ->
                        apiError(CommonApiErrorCodes.FORBIDDEN, "Access denied", "reason" to error.reason)
                }
            }
        }

        // DELETE /api/v1/sessions/{sessionId} - Delete session by ID
        delete<SessionResource.ById> { resource ->
            val userId = call.getUserId()
            val sessionId = resource.sessionId
            call.respondEither(
                sessionService.deleteSession(userId, sessionId),
                HttpStatusCode.NoContent
            ) { error ->
                when (error) {
                    is DeleteSessionError.SessionNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found", "sessionId" to error.id.toString())
                    is DeleteSessionError.AccessDenied ->
                        apiError(CommonApiErrorCodes.FORBIDDEN, "Access denied", "reason" to error.reason)
                }
            }
        }

        // All other session endpoints follow similar pattern with userId extraction and ownership checks
        // PUT /api/v1/sessions/{sessionId}/name, /model, /settings, /leafMessage, /group
        // POST /api/v1/sessions/{sessionId}/messages
    }
}
```

**Estimated Time**: 2-3 days

### Task 6: Dependency Injection Updates

#### 6.1 Service Module Updates
**Update**: `server/src/main/kotlin/eu/torvian/chatbot/server/koin/serviceModule.kt`

```kotlin
fun serviceModule() = module {
    // --- Core Services ---
    single<SessionService> { SessionServiceImpl(get(), get(), get(), get()) } // Add sessionOwnershipDao
    single<GroupService> { GroupServiceImpl(get(), get(), get(), get()) } // Add groupOwnershipDao
    single<LLMModelService> { LLMModelServiceImpl(get(), get(), get()) }
    single<ModelSettingsService> { ModelSettingsServiceImpl(get(), get(), get()) }
    single<LLMProviderService> { LLMProviderServiceImpl(get(), get(), get(), get()) }
    single<MessageService> { MessageServiceImpl(get(), get(), get(), get(), get(), get(), get(), get()) }

    // --- Security Services ---
    single<CryptoProvider> { AESCryptoProvider(get()) }
    single<EncryptionService> { EncryptionService(get()) }
    single<CredentialManager> { DbEncryptedCredentialManager(get(), get()) }

    // --- New Authentication Services ---
    single<PasswordService> { BCryptPasswordService() }
    single<UserService> { UserServiceImpl(get(), get(), get(), get()) }
    single<AuthenticationService> { AuthenticationServiceImpl(get(), get(), get(), get(), get()) }

    // --- Setup Services ---
    single<InitialSetupService> { InitialSetupService(get(), get()) }
}
```

#### 6.2 Configuration Module Updates
**Update**: `server/src/main/kotlin/eu/torvian/chatbot/server/koin/configModule.kt`

```kotlin
fun configModule(
    databaseConfig: DatabaseConfig,
    encryptionConfig: EncryptionConfig,
    jwtConfig: JwtConfig
) = module {
    single { databaseConfig }
    single { encryptionConfig }
    single { jwtConfig }
}
```

#### 6.3 Main Module Updates
**Update**: `server/src/main/kotlin/eu/torvian/chatbot/server/main/chatBotServerModule.kt`

```kotlin
fun Application.configureKoin() {
    // Existing configuration...

    val jwtConfig = JwtConfig(
        secret = environment.config.propertyOrNull("jwt.secret")?.getString()
            ?: "default-secret-change-in-production-G2CgJOQQtIC+yfz+LLoDp/osBLUVzW9JE9BrQA0dQFo="
    )

    install(Koin) {
        modules(
            configModule(databaseConfig, encryptionConfig, jwtConfig),
            databaseModule(),
            miscModule(),
            daoModule(),
            serviceModule(),
            mainModule(this@configureKoin)
        )
    }
}
```

#### 6.4 API Routes Updates
**Update**: `server/src/main/kotlin/eu/torvian/chatbot/server/ktor/routes/ApiRoutesKtor.kt`

```kotlin
class ApiRoutesKtor(
    private val sessionService: SessionService,
    private val groupService: GroupService,
    private val llmProviderService: LLMProviderService,
    private val llmModelService: LLMModelService,
    private val modelSettingsService: ModelSettingsService,
    private val messageService: MessageService,
    private val authenticationService: AuthenticationService, // New
    private val userService: UserService // New
) {
    fun configureAllRoutes(route: Route) {
        configureAuthRoutes(route) // New
        configureSessionRoutes(route)
        configureGroupRoutes(route)
        configureProviderRoutes(route)
        configureModelRoutes(route)
        configureSettingsRoutes(route)
        configureMessageRoutes(route)
    }

    fun configureAuthRoutes(route: Route) {
        route.configureAuthRoutes(authenticationService, userService)
    }

    // Existing methods...
}
```

**Estimated Time**: 1 day

### Task 7: Error Handling and Models

#### 7.1 Authentication Error Types
**Files**: `server/src/main/kotlin/eu/torvian/chatbot/server/service/core/error/auth/`

```kotlin
// RegisterUserError.kt
sealed interface RegisterUserError {
    data class UsernameAlreadyExists(val username: String) : RegisterUserError
    data class EmailAlreadyExists(val email: String) : RegisterUserError
    data class InvalidInput(val reason: String) : RegisterUserError
    data class PasswordTooWeak(val reason: String) : RegisterUserError
}

// LoginError.kt
sealed interface LoginError {
    data object InvalidCredentials : LoginError
    data object UserNotFound : LoginError
    data class AccountLocked(val reason: String) : LoginError
}

// TokenValidationError.kt
sealed interface TokenValidationError {
    data object InvalidToken : TokenValidationError
    data object ExpiredToken : TokenValidationError
    data object MalformedToken : TokenValidationError
}
```

#### 7.2 Updated Service Error Types
**Update existing error types to include access control**:

```kotlin
// Add to existing DeleteSessionError, RenameGroupError, etc.
data class AccessDenied(val reason: String) : DeleteSessionError, RenameGroupError, GetSessionDetailsError
```

**Estimated Time**: 1 day

### Task 8: Testing Implementation

#### 8.1 Service Layer Tests
**Files**: `server/src/test/kotlin/eu/torvian/chatbot/server/service/`

```kotlin
// AuthenticationServiceTest.kt
class AuthenticationServiceTest {
    @Test
    fun `login with valid credentials returns success`() = runTest {
        // Test implementation
    }

    @Test
    fun `login with invalid credentials returns error`() = runTest {
        // Test implementation
    }

    @Test
    fun `token validation works correctly`() = runTest {
        // Test implementation
    }
}

// UserServiceTest.kt
class UserServiceTest {
    @Test
    fun `register user with valid data succeeds`() = runTest {
        // Test implementation
    }

    @Test
    fun `register user with duplicate username fails`() = runTest {
        // Test implementation
    }
}
```

#### 8.2 Route Tests with Authentication
**Update existing route tests**:

```kotlin
// GroupRoutesTest.kt - Add authentication setup
class GroupRoutesTest {
    private fun createAuthenticatedTestApp(): TestApplication {
        return TestApplication {
            application {
                configureKtor()
                // Configure test authentication
            }
        }
    }

    @Test
    fun `GET groups requires authentication`() = runTest {
        // Test unauthenticated access returns 401
    }

    @Test
    fun `GET groups returns user-specific groups`() = runTest {
        // Test authenticated access returns correct data
    }
}
```

**Estimated Time**: 3-4 days

## Implementation Timeline

### Week 1
- **Days 1-2**: Task 1 - Core Authentication Services Implementation
- **Days 3-4**: Task 2 - Authentication API Endpoints
- **Day 5**: Task 3 - JWT Authentication Infrastructure

### Week 2
- **Days 1-3**: Task 4 - Service Layer Refactoring (SessionService, GroupService)
- **Days 4-5**: Task 5 - Route Authentication Implementation

### Week 3
- **Day 1**: Task 6 - Dependency Injection Updates
- **Day 2**: Task 7 - Error Handling and Models
- **Days 3-5**: Task 8 - Testing Implementation

## Dependencies and Prerequisites

### Required DAOs (from previous steps)
- `UserDao` - User CRUD operations
- `UserSessionDao` - Session management
- `SessionOwnershipDao` - Session ownership tracking
- `GroupOwnershipDao` - Group ownership tracking
- `UserGroupDao` - User group management

### Configuration Requirements
- JWT secret configuration
- Password hashing configuration
- Token expiration settings

## Risk Mitigation

### High-Risk Areas
1. **Service Layer Breaking Changes**: All service methods now require userId
2. **Authentication Token Management**: JWT token generation and validation
3. **Database Transaction Complexity**: Ownership operations within transactions

### Mitigation Strategies
1. **Comprehensive Testing**: Unit tests for all service methods with user context
2. **Gradual Rollout**: Implement authentication infrastructure before protecting routes
3. **Error Handling**: Robust error handling for authentication failures
4. **Documentation**: Clear documentation of API changes and authentication requirements

## Success Criteria

- [ ] All authentication services implemented and tested
- [ ] Authentication API endpoints functional
- [ ] JWT authentication configured and working
- [ ] SessionService and GroupService refactored with user context
- [ ] Group and Session routes protected with JWT authentication
- [ ] All existing functionality preserved with user scoping
- [ ] Comprehensive test coverage for new functionality
- [ ] No breaking changes to existing data structures

## Deliverables

1. **Core Services**: UserService, AuthenticationService, PasswordService
2. **API Endpoints**: /auth/register, /auth/login, /auth/logout, /auth/me
3. **JWT Infrastructure**: Configuration, utilities, middleware
4. **Refactored Services**: SessionServiceImpl, GroupServiceImpl with user context
5. **Protected Routes**: Group and Session routes with JWT authentication
6. **Test Suite**: Comprehensive tests for all new functionality
7. **Documentation**: API documentation and implementation guide

**Total Estimated Effort**: 15-18 days (3 weeks)

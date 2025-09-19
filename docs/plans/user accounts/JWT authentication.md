# Report: JWT Authentication Implementation for Ktor Routes (Phase 1, Step 3)

## Executive Summary

This report analyzes the changes required to implement JWT authentication for the current Ktor routes as part of **Phase 1, Step 3** of the User Accounts feature implementation. The analysis covers the current route structure, required authentication infrastructure, and specific modifications needed for each route configuration.

## Current State Analysis

### Existing Route Structure
The current application has the following route configuration files:
- `configureSessionRoutes.kt` - 11 endpoints for session management
- `configureGroupRoutes.kt` - 4 endpoints for group management  
- `configureProviderRoutes.kt` - 5 endpoints for LLM provider management
- `configureModelRoutes.kt` - 6 endpoints for model management
- `configureSettingsRoutes.kt` - 5 endpoints for settings management
- `configureMessageRoutes.kt` - 1 endpoint for message management

### Current Authentication Status
- **No authentication middleware** is currently applied to any routes
- All routes are **publicly accessible** without any user context
- Service methods are called **without user ID parameters**
- JWT dependencies are already available in `gradle/libs.versions.toml` but not utilized

### Service Layer Readiness
Based on the documentation analysis, the service layer has been designed to support user context:
- Services like `GroupService` already expect `userId` parameters in their method signatures
- Error types include `AccessDenied` for authorization failures
- The multi-user architecture is planned with ownership and access control

## Required Authentication Infrastructure

### 1. JWT Configuration Components

The following components need to be implemented based on the example code provided:

```kotlin
// JWT Configuration
data class JwtConfig(
    val issuer: String,
    val audience: String, 
    val realm: String,
    val secret: String,
    val tokenExpirationMs: Long
)

// JWT Claims Structure
data class JwtClaims(
    val issuer: String,
    val audience: String,
    val subject: String, // userId
    val role: UserRole,
    val issuedAt: Long,
    val expiresAt: Long
)

// Authentication Schemes
object AuthSchemes {
    const val USER_JWT = "auth-user-jwt"
    const val ADMIN_JWT = "auth-admin-jwt"
}
```

### 2. Ktor Authentication Setup

The main application configuration needs to be updated to install and configure JWT authentication:

```kotlin
// In configureKtor() or similar setup function
install(Authentication) {
    jwt(AuthSchemes.USER_JWT) {
        realm = jwtConfig.realm
        verifier(jwtConfig.verifier)
        validate { credential ->
            // Validate JWT and return JWTPrincipal
            if (credential.payload.audience.contains(jwtConfig.audience)) {
                JWTPrincipal(credential.payload)
            } else null
        }
    }
}
```

### 3. User Context Extraction Utility

A utility function to extract user ID from JWT tokens:

```kotlin
fun ApplicationCall.getUserId(): Long {
    val principal = principal<JWTPrincipal>()
    val userId = principal?.payload?.subject?.toLongOrNull()
        ?: throw IllegalStateException("User ID not found in authenticated request")
    return userId
}
```

## Route-Specific Changes Required

### 1. Group Routes (`configureGroupRoutes.kt`)

**Current Issues:**
- `getAllGroups()` called without user context
- `createGroup(name)` missing userId parameter
- `renameGroup(id, name)` missing userId parameter  
- `deleteGroup(id)` missing userId parameter

**Required Changes:**
```kotlin
fun Route.configureGroupRoutes(groupService: GroupService) {
    authenticate(AuthSchemes.USER_JWT) {
        get<GroupResource> {
            val userId = call.getUserId()
            call.respond(groupService.getAllGroups(userId))
        }
        
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
        
        // Similar updates for PUT and DELETE endpoints...
    }
}
```

### 2. Session Routes (`configureSessionRoutes.kt`)

**Current Issues:**
- All 11 endpoints lack user authentication
- Service calls missing userId parameters
- No ownership verification for session access

**Required Changes:**
- Wrap all routes in `authenticate(AuthSchemes.USER_JWT)`
- Extract `userId` using `call.getUserId()` in each endpoint
- Update service method calls to include userId parameter
- Add error handling for new `AccessDenied` error types

### 3. Provider Routes (`configureProviderRoutes.kt`)

**Current Issues:**
- Provider management lacks user context
- No distinction between private and public providers
- Missing permission checks for provider creation/modification

**Required Changes:**
- Authentication wrapper for all routes
- User context extraction
- Service method updates to include userId
- Permission-based access control for admin operations

### 4. Model Routes (`configureModelRoutes.kt`)

**Current Issues:**
- Similar to provider routes - lacks user context and permissions

**Required Changes:**
- Same pattern as provider routes
- User context for model access filtering
- Permission checks for model management operations

### 5. Settings Routes (`configureSettingsRoutes.kt`)

**Current Issues:**
- Settings access not user-scoped
- No ownership verification

**Required Changes:**
- User authentication wrapper
- User context in service calls
- Ownership-based access control

### 6. Message Routes (`configureMessageRoutes.kt`)

**Current Issues:**
- Message operations lack user context
- No verification that user owns the session containing the message

**Required Changes:**
- Authentication wrapper
- User context extraction
- Indirect ownership verification through session ownership

## Implementation Strategy

### Phase 1: Core Authentication Infrastructure
1. **Create JWT configuration classes** in `server/src/main/kotlin/eu/torvian/chatbot/server/domain/security/`
2. **Update Koin DI modules** to provide JWT configuration
3. **Modify `configureKtor()`** to install Authentication plugin with JWT
4. **Create user context utilities** for extracting userId from JWT

### Phase 2: Route Authentication
1. **Update each route configuration file** to wrap routes in `authenticate()` blocks
2. **Add user context extraction** to each endpoint
3. **Update service method calls** to include userId parameters
4. **Update error handling** to include new authentication/authorization errors

### Phase 3: Testing and Validation
1. **Update existing route tests** to include authentication
2. **Create authentication test utilities**
3. **Verify all endpoints require valid JWT tokens**
4. **Test error scenarios** (invalid tokens, expired tokens, access denied)

## Dependencies and Configuration

### Required Dependencies
The project already includes the necessary JWT dependencies:
- `ktor-server-auth`
- `ktor-server-auth-jwt`
- `jbcrypt` (for password hashing)

### Configuration Updates
- **JWT secret configuration** needs to be added to application config
- **Token expiration settings** should be configurable
- **CORS updates** may be needed to handle Authorization headers

## Risk Assessment

### High Risk Areas
1. **Breaking Changes**: All API endpoints will require authentication, breaking existing clients
2. **Service Layer Compatibility**: Services must be updated to handle userId parameters
3. **Error Handling**: New error types need proper mapping to HTTP responses

### Mitigation Strategies
1. **Gradual Rollout**: Implement authentication infrastructure first, then gradually protect routes
2. **Backward Compatibility**: Consider temporary dual-mode operation during transition
3. **Comprehensive Testing**: Ensure all authentication scenarios are thoroughly tested

## Estimated Effort

- **JWT Infrastructure Setup**: 1-2 days
- **Route Authentication Implementation**: 2-3 days  
- **Service Layer Integration**: 1-2 days
- **Testing and Validation**: 2-3 days
- **Documentation and Error Handling**: 1 day

**Total Estimated Effort**: 7-11 days

## Detailed Implementation Examples

### Example: Complete Group Routes Authentication

```kotlin
// Updated configureGroupRoutes.kt
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

### Example: Session Routes Authentication Pattern

```kotlin
// Key changes needed in configureSessionRoutes.kt
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

        // All other session endpoints follow similar pattern...
    }
}
```

### Example: JWT Configuration Setup

```kotlin
// server/src/main/kotlin/eu/torvian/chatbot/server/domain/security/JwtConfig.kt
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

// server/src/main/kotlin/eu/torvian/chatbot/server/ktor/auth/AuthUtils.kt
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

### Example: Updated Koin Configuration

```kotlin
// Update configModule.kt to include JWT config
fun configModule(
    databaseConfig: DatabaseConfig,
    encryptionConfig: EncryptionConfig,
    jwtConfig: JwtConfig
) = module {
    single { databaseConfig }
    single { encryptionConfig }
    single { jwtConfig }
}

// Update chatBotServerModule.kt
fun Application.configureKoin() {
    val jwtConfig = JwtConfig(
        secret = environment.config.propertyOrNull("jwt.secret")?.getString()
            ?: "default-secret-change-in-production"
    )

    install(Koin) {
        modules(
            configModule(databaseConfig, encryptionConfig, jwtConfig),
            // ... other modules
        )
    }
}
```

## File-by-File Change Summary

### Files to Create:
1. `server/src/main/kotlin/eu/torvian/chatbot/server/domain/security/JwtConfig.kt`
2. `server/src/main/kotlin/eu/torvian/chatbot/server/domain/security/AuthSchemes.kt`
3. `server/src/main/kotlin/eu/torvian/chatbot/server/ktor/auth/AuthUtils.kt`

### Files to Modify:
1. `server/src/main/kotlin/eu/torvian/chatbot/server/main/chatBotServerModule.kt` - Add JWT config
2. `server/src/main/kotlin/eu/torvian/chatbot/server/koin/configModule.kt` - Include JWT config
3. `server/src/main/kotlin/eu/torvian/chatbot/server/ktor/configureKtor.kt` - Install Authentication
4. `server/src/main/kotlin/eu/torvian/chatbot/server/ktor/routes/configureGroupRoutes.kt` - Add auth
5. `server/src/main/kotlin/eu/torvian/chatbot/server/ktor/routes/configureSessionRoutes.kt` - Add auth
6. `server/src/main/kotlin/eu/torvian/chatbot/server/ktor/routes/configureProviderRoutes.kt` - Add auth
7. `server/src/main/kotlin/eu/torvian/chatbot/server/ktor/routes/configureModelRoutes.kt` - Add auth
8. `server/src/main/kotlin/eu/torvian/chatbot/server/ktor/routes/configureSettingsRoutes.kt` - Add auth
9. `server/src/main/kotlin/eu/torvian/chatbot/server/ktor/routes/configureMessageRoutes.kt` - Add auth

## Next Steps

1. **Review and approve** this implementation plan
2. **Create JWT configuration infrastructure**
3. **Implement authentication middleware setup**
4. **Begin route-by-route authentication implementation**
5. **Update service layer integration**
6. **Comprehensive testing of authenticated endpoints**

This implementation will establish the foundation for the multi-user system by ensuring all API access is properly authenticated and user-scoped.

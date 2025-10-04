# Report: Adding User Permissions to AuthState.Authenticated

**Date:** 2025-10-04  
**Objective:** Enable the application to store and access user permissions in `AuthState.Authenticated` for permission-based UI rendering (e.g., showing/hiding admin panels).

---

## Executive Summary

To achieve permission-based UI rendering, we need to modify the authentication flow to retrieve, store, and expose user permissions throughout the application. This report identifies all components that require updates and proposes a comprehensive implementation strategy.

**Key Finding:** The server already has the capability to retrieve user permissions via `AuthorizationService.getUserPermissions()`, but there is currently **no API endpoint** to expose this to the client application.

---

## Current State Analysis

### ✅ What Already Exists

1. **Server-Side Permission Infrastructure**
   - `AuthorizationService.getUserPermissions(userId: Long): List<Permission>` exists
   - `Permission` model with `id`, `action`, and `subject` fields
   - `CommonPermissions` object with predefined permission specifications
   - Role-based permission system with permission aggregation

2. **Client-Side Models**
   - `Permission` data class in common module
   - `PermissionSpec` for action-subject pairs
   - `User` model (but without permissions)

3. **Authentication Infrastructure**
   - `AuthState` sealed class with `Authenticated`, `Unauthenticated`, and `Loading` states
   - `TokenStorage` interface for secure storage
   - `LoginResponse` with user data and tokens
   - `DefaultAuthRepository` managing auth state

### ❌ What's Missing

1. **No API endpoint** to retrieve user permissions from the server
2. **No PermissionApi** client interface in the app module
3. **AuthState.Authenticated** does not store permissions
4. **TokenStorage** does not persist permissions
5. **LoginResponse** does not include permissions
6. **No permission checking utilities** for UI components
7. **No UserManagementScreen** composable (ViewModel exists but no UI)

---

## Proposed Implementation Strategy

### Option A: Retrieve Permissions During Login (Recommended)

**Advantages:**
- Single network call during login
- Permissions immediately available after authentication
- Simpler client-side logic
- Consistent with existing login flow

**Disadvantages:**
- Permissions may become stale if changed on server
- Requires logout/login to refresh permissions

### Option B: Separate Permission API Endpoint

**Advantages:**
- Permissions can be refreshed without re-authentication
- Better separation of concerns
- Prepares for future PermissionRepository for admin features

**Disadvantages:**
- Additional network call on app startup
- More complex implementation
- Two sources of truth for permissions

**Recommendation:** Start with **Option A** (include in login response), then add **Option B** (separate API) later for the admin permission management features.

---

## Required Changes

### 1. Common Module (Shared Models)

#### 1.1 Update `LoginResponse`
**File:** `common/src/commonMain/kotlin/eu/torvian/chatbot/common/models/auth/LoginResponse.kt`

**Change:** Add `permissions` field to store user permissions.

```kotlin
@Serializable
data class LoginResponse(
    val user: User,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Instant,
    val permissions: List<Permission> // NEW FIELD
)
```

---

### 2. Server Module (Backend)

#### 2.1 Update `LoginResult`
**File:** `server/src/main/kotlin/eu/torvian/chatbot/server/domain/security/LoginResult.kt`

**Change:** Add `permissions` field.

```kotlin
data class LoginResult(
    val user: User,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Instant,
    val permissions: List<Permission> // NEW FIELD
)
```

#### 2.2 Update `toLoginResponse` Mapper
**File:** `server/src/main/kotlin/eu/torvian/chatbot/server/domain/security/mappers/toLoginResponse.kt`

**Change:** Map permissions from LoginResult to LoginResponse.

```kotlin
fun LoginResult.toLoginResponse(): LoginResponse {
    return LoginResponse(
        user = this.user,
        accessToken = this.accessToken,
        refreshToken = this.refreshToken,
        expiresAt = this.expiresAt,
        permissions = this.permissions // NEW FIELD
    )
}
```

#### 2.3 Update `AuthenticationService.login()`
**File:** `server/src/main/kotlin/eu/torvian/chatbot/server/service/security/AuthenticationServiceImpl.kt`

**Change:** Retrieve user permissions during login and include in LoginResult.

```kotlin
suspend fun login(username: String, password: String): Either<LoginError, LoginResult> = either {
    // ... existing validation and token generation code ...
    
    // NEW: Retrieve user permissions
    val permissions = authorizationService.getUserPermissions(userEntity.id)
    
    LoginResult(
        user = userEntity.toUser(),
        accessToken = tokens.accessToken,
        refreshToken = tokens.refreshToken,
        expiresAt = tokens.expiresAt,
        permissions = permissions // NEW FIELD
    )
}
```

#### 2.4 Update `AuthenticationService.refreshToken()`
**File:** `server/src/main/kotlin/eu/torvian/chatbot/server/service/security/AuthenticationServiceImpl.kt`

**Change:** Also include permissions when refreshing tokens.

```kotlin
suspend fun refreshToken(refreshToken: String): Either<RefreshTokenError, LoginResult> = either {
    // ... existing refresh token validation code ...
    
    // NEW: Retrieve user permissions
    val permissions = authorizationService.getUserPermissions(userId)
    
    LoginResult(
        user = user,
        accessToken = newAccessToken,
        refreshToken = refreshToken,
        expiresAt = expiresAt,
        permissions = permissions // NEW FIELD
    )
}
```

#### 2.5 (Optional) Add Permissions Endpoint
**File:** `common/src/commonMain/kotlin/eu/torvian/chatbot/common/api/resources/AuthResource.kt`

**Change:** Add new resource for retrieving current user's permissions.

```kotlin
@Resource("auth")
class AuthResource(val parent: Api = Api()) {
    // ... existing resources ...
    
    /**
     * Resource for getting current user's permissions: /api/v1/auth/me/permissions
     */
    @Resource("me/permissions")
    class MyPermissions(val parent: AuthResource = AuthResource())
}
```

**File:** `server/src/main/kotlin/eu/torvian/chatbot/server/ktor/routes/configureAuthRoutes.kt`

**Change:** Add route handler.

```kotlin
authenticate(AuthSchemes.USER_JWT) {
    get<AuthResource.MyPermissions> {
        val userId = call.getUserId()
        val permissions = authorizationService.getUserPermissions(userId)
        call.respond(permissions)
    }
}
```

---

### 3. App Module (Client)

#### 3.1 Update `AuthState.Authenticated`
**File:** `app/src/commonMain/kotlin/eu/torvian/chatbot/app/repository/AuthState.kt`

**Change:** Add `permissions` field to store user permissions.

```kotlin
sealed class AuthState {
    object Unauthenticated : AuthState()
    
    data class Authenticated(
        val userId: Long,
        val username: String,
        val permissions: List<Permission> // NEW FIELD
    ) : AuthState()
    
    object Loading : AuthState()
}
```

#### 3.2 Update `TokenStorage` Interface
**File:** `app/src/commonMain/kotlin/eu/torvian/chatbot/app/service/auth/TokenStorage.kt`

**Change:** Add `permissions` parameter to `saveAuthData` and add `getPermissions` method.

```kotlin
interface TokenStorage {
    suspend fun saveAuthData(
        accessToken: String,
        refreshToken: String,
        expiresAt: Instant,
        user: User,
        permissions: List<Permission> // NEW PARAMETER
    ): Either<TokenStorageError, Unit>
    
    suspend fun getUserData(): Either<TokenStorageError, User>
    
    // NEW METHOD
    suspend fun getPermissions(): Either<TokenStorageError, List<Permission>>
    
    suspend fun clearAuthData(): Either<TokenStorageError, Unit>
    suspend fun getAccessToken(): Either<TokenStorageError, String>
    suspend fun getRefreshToken(): Either<TokenStorageError, String>
    suspend fun getExpiry(): Either<TokenStorageError, Instant>
}
```

#### 3.3 Update `FileSystemTokenStorage`
**File:** `app/src/commonMain/kotlin/eu/torvian/chatbot/app/service/auth/FileSystemTokenStorage.kt`

**Change:** Store and retrieve permissions along with user data.

```kotlin
@Serializable
private data class TokenData(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val user: User,
    val permissions: List<Permission> // NEW FIELD
)

override suspend fun saveAuthData(
    accessToken: String,
    refreshToken: String,
    expiresAt: Instant,
    user: User,
    permissions: List<Permission> // NEW PARAMETER
): Either<TokenStorageError, Unit> = either {
    val tokenData = TokenData(
        accessToken, 
        refreshToken, 
        expiresAt.epochSeconds, 
        user, 
        permissions // NEW FIELD
    )
    // ... rest of encryption and storage logic ...
}

override suspend fun getPermissions(): Either<TokenStorageError, List<Permission>> =
    loadTokenData().map { it.permissions }
```

#### 3.4 Update `DefaultAuthRepository.login()`
**File:** `app/src/commonMain/kotlin/eu/torvian/chatbot/app/repository/impl/DefaultAuthRepository.kt`

**Change:** Extract permissions from login response and pass to storage and auth state.

```kotlin
override suspend fun login(request: LoginRequest): Either<RepositoryError, Unit> = either {
    _authState.value = AuthState.Loading

    val loginResponse = withError({ apiError ->
        _authState.value = AuthState.Unauthenticated
        apiError.toRepositoryError("Login failed")
    }) {
        authApi.login(request).bind()
    }

    // Save authentication data WITH PERMISSIONS
    withError({ tokenError ->
        _authState.value = AuthState.Unauthenticated
        RepositoryError.OtherError("Failed to save authentication data: ${tokenError.message}")
    }) {
        tokenStorage.saveAuthData(
            accessToken = loginResponse.accessToken,
            refreshToken = loginResponse.refreshToken,
            expiresAt = loginResponse.expiresAt,
            user = loginResponse.user,
            permissions = loginResponse.permissions // NEW PARAMETER
        ).bind()
    }

    // Update auth state with permissions
    _authState.value = AuthState.Authenticated(
        userId = loginResponse.user.id,
        username = loginResponse.user.username,
        permissions = loginResponse.permissions // NEW FIELD
    )
}
```

#### 3.5 Update `DefaultAuthRepository.checkInitialAuthState()`
**File:** `app/src/commonMain/kotlin/eu/torvian/chatbot/app/repository/impl/DefaultAuthRepository.kt`

**Change:** Load permissions from storage during optimistic authentication.

```kotlin
override suspend fun checkInitialAuthState() {
    logger.info("Checking initial authentication state on startup")

    // Load user data and permissions
    tokenStorage.getUserData().fold(
        ifLeft = { error ->
            logger.debug("Failed to load cached user data: ${error.message}")
            _authState.value = AuthState.Unauthenticated
        },
        ifRight = { user ->
            // Also load permissions
            tokenStorage.getPermissions().fold(
                ifLeft = { error ->
                    logger.warn("Failed to load permissions: ${error.message}")
                    _authState.value = AuthState.Unauthenticated
                },
                ifRight = { permissions ->
                    logger.info("Loaded cached user and permissions: ${user.username}")
                    _authState.value = AuthState.Authenticated(
                        userId = user.id,
                        username = user.username,
                        permissions = permissions // NEW FIELD
                    )
                }
            )
        }
    )
}
```

#### 3.6 Update Token Refresh in `createAuthenticatedHttpClient`
**File:** `app/src/commonMain/kotlin/eu/torvian/chatbot/app/service/auth/createAuthenticatedHttpClient.kt`

**Change:** Update stored permissions after token refresh.

```kotlin
// After successful refresh
tokenStorage.saveAuthData(
    accessToken = refreshResponse.accessToken,
    refreshToken = refreshResponse.refreshToken,
    expiresAt = refreshResponse.expiresAt,
    user = refreshResponse.user,
    permissions = refreshResponse.permissions // NEW PARAMETER
)
```

#### 3.7 (Optional) Create `PermissionApi` Interface
**File:** `app/src/commonMain/kotlin/eu/torvian/chatbot/app/service/api/PermissionApi.kt` (NEW FILE)

**Purpose:** Separate API client for permission operations (for future admin features).

```kotlin
package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.Permission

/**
 * API client interface for permission operations.
 */
interface PermissionApi {
    /**
     * Retrieves the current user's permissions.
     */
    suspend fun getCurrentUserPermissions(): Either<ApiResourceError, List<Permission>>
    
    /**
     * Retrieves permissions for a specific user (admin only).
     */
    suspend fun getUserPermissions(userId: Long): Either<ApiResourceError, List<Permission>>
}
```

#### 3.8 Create Permission Checking Utilities
**File:** `app/src/commonMain/kotlin/eu/torvian/chatbot/app/utils/permissions/PermissionChecker.kt` (NEW FILE)

**Purpose:** Utility functions for checking permissions.

```kotlin
package eu.torvian.chatbot.app.utils.permissions

import eu.torvian.chatbot.common.api.PermissionSpec
import eu.torvian.chatbot.common.models.Permission

/**
 * Checks if a list of permissions contains a specific permission.
 */
fun List<Permission>.hasPermission(spec: PermissionSpec): Boolean {
    return any { it.action == spec.action && it.subject == spec.subject }
}

/**
 * Checks if a list of permissions contains a specific permission by action and subject.
 */
fun List<Permission>.hasPermission(action: String, subject: String): Boolean {
    return any { it.action == action && it.subject == subject }
}
```

#### 3.9 Create Permission-Based Composable Utilities
**File:** `app/src/commonMain/kotlin/eu/torvian/chatbot/app/compose/permissions/PermissionGate.kt` (NEW FILE)

**Purpose:** Composable utilities for conditional UI rendering based on permissions.

```kotlin
package eu.torvian.chatbot.app.compose.permissions

import androidx.compose.runtime.Composable
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.utils.permissions.hasPermission
import eu.torvian.chatbot.common.api.PermissionSpec

/**
 * Renders content only if the user has the required permission.
 */
@Composable
fun RequiresPermission(
    authState: AuthState,
    permission: PermissionSpec,
    content: @Composable () -> Unit
) {
    if (authState is AuthState.Authenticated && 
        authState.permissions.hasPermission(permission)) {
        content()
    }
}

/**
 * Renders different content based on whether the user has a permission.
 */
@Composable
fun PermissionGate(
    authState: AuthState,
    permission: PermissionSpec,
    hasPermission: @Composable () -> Unit,
    noPermission: @Composable () -> Unit = {}
) {
    if (authState is AuthState.Authenticated && 
        authState.permissions.hasPermission(permission)) {
        hasPermission()
    } else {
        noPermission()
    }
}
```

#### 3.10 Update UI Components
**Example:** Update `MainApplicationFlow.kt` or navigation to conditionally show admin tabs.

```kotlin
// In navigation or tab bar
RequiresPermission(
    authState = authState,
    permission = CommonPermissions.MANAGE_USERS
) {
    NavigationRailItem(
        icon = { Icon(Icons.Default.People, "Users") },
        label = { Text("Users") },
        selected = currentRoute == "users",
        onClick = { navController.navigate("users") }
    )
}
```

---

## Implementation Checklist

### Phase 1: Backend Changes (Server Module)
- [ ] Update `LoginResult` to include `permissions` field
- [ ] Update `toLoginResponse()` mapper to include permissions
- [ ] Update `AuthenticationServiceImpl.login()` to retrieve and include permissions
- [ ] Update `AuthenticationServiceImpl.refreshToken()` to retrieve and include permissions
- [ ] Add tests for permission inclusion in login/refresh responses

### Phase 2: Shared Models (Common Module)
- [ ] Update `LoginResponse` to include `permissions` field
- [ ] (Optional) Add `AuthResource.MyPermissions` for separate endpoint

### Phase 3: Client Storage (App Module - Storage Layer)
- [ ] Update `TokenStorage` interface to include permissions
- [ ] Update `FileSystemTokenStorage.TokenData` to include permissions
- [ ] Update `FileSystemTokenStorage.saveAuthData()` signature and implementation
- [ ] Add `FileSystemTokenStorage.getPermissions()` method
- [ ] Update WASM token storage implementation similarly
- [ ] Add tests for permission storage and retrieval

### Phase 4: Client Repository (App Module - Repository Layer)
- [ ] Update `AuthState.Authenticated` to include `permissions` field
- [ ] Update `DefaultAuthRepository.login()` to extract and store permissions
- [ ] Update `DefaultAuthRepository.checkInitialAuthState()` to load permissions
- [ ] Update token refresh logic in `createAuthenticatedHttpClient.kt`
- [ ] Add tests for auth state with permissions

### Phase 5: Client Utilities (App Module - Utils)
- [ ] Create `PermissionChecker.kt` with permission checking utilities
- [ ] Create `PermissionGate.kt` with composable permission utilities
- [ ] Add tests for permission checking logic

### Phase 6: UI Integration (App Module - Compose)
- [ ] Update `MainApplicationFlow.kt` to pass permissions to composables
- [ ] Add permission-based conditional rendering for admin features
- [ ] Create User Management screen (composable) with permission gate
- [ ] Update settings screen to conditionally show admin tabs
- [ ] Add visual feedback for permission-restricted features

### Phase 7: (Optional) Separate Permission API
- [ ] Create `PermissionApi` interface
- [ ] Implement `KtorPermissionApiClient`
- [ ] Add route handler in `configureAuthRoutes.kt`
- [ ] Create `PermissionRepository` for admin features
- [ ] Add permission refresh functionality

---

## Impact Analysis

### Breaking Changes
1. **TokenStorage.saveAuthData()** signature changes - all implementations must be updated
2. **AuthState.Authenticated** structure changes - all pattern matches must be updated
3. **LoginResponse** structure changes - affects serialization compatibility

### Backward Compatibility Considerations
- Old clients will fail to deserialize new `LoginResponse` with permissions
- Old token storage files will not have permissions field
- Migration strategy needed for existing users

### Migration Strategy
1. Make `permissions` field in `LoginResponse` **optional with default empty list** initially
2. Update `TokenData` to handle missing permissions field with `@Serializable` default
3. Force re-authentication for users with old token format (acceptable for this feature)

---

## Testing Requirements

### Unit Tests
- [ ] Test permission storage and retrieval in `FileSystemTokenStorage`
- [ ] Test permission loading in `DefaultAuthRepository`
- [ ] Test permission checking utilities
- [ ] Test `AuthState.Authenticated` with permissions

### Integration Tests
- [ ] Test login flow includes permissions
- [ ] Test token refresh updates permissions
- [ ] Test optimistic authentication loads permissions
- [ ] Test permission-based UI rendering

### Manual Testing
- [ ] Verify admin user sees admin UI elements
- [ ] Verify non-admin user does not see admin UI elements
- [ ] Verify permissions persist across app restarts
- [ ] Verify permissions update after login/logout

---

## Timeline Estimate

- **Phase 1-2 (Backend + Models):** 2-3 hours
- **Phase 3 (Storage Layer):** 2-3 hours
- **Phase 4 (Repository Layer):** 2-3 hours
- **Phase 5 (Utilities):** 1-2 hours
- **Phase 6 (UI Integration):** 3-4 hours
- **Phase 7 (Optional API):** 2-3 hours
- **Testing:** 3-4 hours

**Total:** 15-22 hours (2-3 days)

---

## Recommendations

1. **Start with Option A** (include permissions in login response) for immediate functionality
2. **Implement Phase 1-6** first for basic permission-based UI
3. **Add Phase 7** (separate API) later when building admin permission management features
4. **Use feature flags** to gradually roll out permission-based UI elements
5. **Add logging** for permission checks to help debug authorization issues
6. **Consider caching** permissions client-side with TTL for performance
7. **Plan for permission changes** - add mechanism to refresh permissions without full logout

---

## Security Considerations

1. **Server-side enforcement is paramount** - UI permission checks are for UX only
2. **Never trust client-side permission checks** for security decisions
3. **Always validate permissions on the backend** for every API call
4. **Permissions should be read-only on client** - no client-side modification
5. **Token storage encryption** already handles permission security
6. **Consider permission audit logging** on the server side

---

## Future Enhancements

1. **Real-time permission updates** via WebSocket when permissions change
2. **Permission caching strategy** with TTL and manual refresh
3. **Granular permission checking** for individual resources (e.g., "can edit session X")
4. **Permission groups** for simpler UI checks
5. **Admin permission management UI** (full CRUD for permissions and roles)
6. **Permission debugging UI** for development
7. **Permission-based feature flags** for A/B testing

---

## Conclusion

Implementing user permissions in `AuthState.Authenticated` is a **moderate-complexity change** that touches multiple layers of the application. The proposed approach of including permissions in the login response is the simplest and most efficient solution for immediate needs, while leaving the door open for a more sophisticated permission management system in the future.

The key insight is that **the server already has all the permission infrastructure** - we just need to expose it to the client and integrate it into the authentication flow.


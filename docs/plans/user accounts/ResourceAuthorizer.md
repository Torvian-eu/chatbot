Reference implementation: ResourceAuthorizer + AuthorizationService.requireAccess

Purpose
-------
This document is a self-contained reference implementation for a resource-scoped authorization layer suitable for the server module. It is written for developers who haven't seen earlier discussions; it contains rationale, a small API, copy-paste-ready Kotlin examples, DI wiring (Koin), route usage, caching guidance, test skeletons, and a migration plan.

Key goals
- Provide a single, focused abstraction for resource-level access checks (owner, grants, group membership).
- Keep domain/logical errors in Arrow Either types; do not encode technical DB errors as domain errors.
- Make caller code simple: call a `requireAccess(...)` function which returns Either<domainError, Unit> so handlers can map 404/403 easily.
- Support read vs write semantics via a small enum `AccessMode` where WRITE implies read.

Assumptions (explicit)
- This document does not assume the existence of any particular services beyond common DAOs. If your project uses different DAO names or shapes, adapt the examples.
- The examples use Kotlin + Arrow Either style and Koin for DI, matching the project's current conventions.
- Technical/database errors should surface as exceptions and be handled by the application's global exception handler.

Summary / Quick API
- Add enum: `AccessMode { READ, WRITE }` (WRITE implies READ)
- Add `ResourceAuthorizer` abstraction (per-resource implementation)
  - suspend fun requireAccess(userId: Long, resourceId: Long, accessMode: AccessMode): Either<ResourceAuthorizerError, Unit>
- Add `AuthorizationService.requireAccess(userId, resourceType, resourceId, accessMode)` as a resource-level helper that delegates to the registered authorizer map and returns Either<AuthorizationError, Unit>.

Reference Kotlin code (copy/paste-ready)
----------------------------------------
Note: adapt package names and DAO imports to your codebase.

1) AccessMode.kt

```kotlin
package eu.torvian.chatbot.server.service.authorizer

/**
 * Represents the kind of access being requested. WRITE implies READ.
 */
enum class AccessMode {
    READ,
    WRITE
}
```

2) ResourceAuthorizerError.kt

```kotlin
package eu.torvian.chatbot.server.service.authorizer

sealed interface ResourceAuthorizerError {
    data class ResourceNotFound(val id: Long) : ResourceAuthorizerError
    data class AccessDenied(val reason: String = "Access denied") : ResourceAuthorizerError
}
```

3) ResourceAuthorizer.kt

```kotlin
package eu.torvian.chatbot.server.service.authorizer

import arrow.core.Either

/**
 * Small pluggable authorizer for a resource type (e.g. "group", "session", "llm_model").
 * Implementations encapsulate resource-specific policy (owner, grants, groups, public flags).
 */
interface ResourceAuthorizer {
    val resourceType: String // e.g. "group"

    /**
     * Require that `userId` has the requested `accessMode` to `resourceId`.
     * Returns Right(Unit) on success, Left(ResourceNotFound) or Left(AccessDenied) on logical errors.
     * Technical errors (DB failures) should throw exceptions and be handled by the global handler.
     */
    suspend fun requireAccess(userId: Long, resourceId: Long, accessMode: AccessMode): Either<ResourceAuthorizerError, Unit>
}
```

4) Example: GroupAuthorizer.kt

- Purpose: group ownership implies both read and write for the owner.
- Adapts to a `GroupOwnershipDao` that exposes `suspend fun getOwner(groupId: Long): Either<GetOwnerError, Long>`.

```kotlin
package eu.torvian.chatbot.server.service.authorizer

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.server.data.dao.GroupOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError

class GroupAuthorizer(
    private val groupOwnershipDao: GroupOwnershipDao,
) : ResourceAuthorizer {
    override val resourceType: String = "group"

    override suspend fun requireAccess(userId: Long, resourceId: Long, accessMode: AccessMode): Either<ResourceAuthorizerError, Unit> =
        groupOwnershipDao.getOwner(resourceId).mapLeft { daoErr ->
            when (daoErr) {
                is GetOwnerError.ResourceNotFound -> ResourceAuthorizerError.ResourceNotFound(resourceId)
            }
        }.flatMap { ownerId ->
            // Owner has both READ and WRITE.
            if (ownerId == userId) Unit.right() else ResourceAuthorizerError.AccessDenied("User is not the owner").left()
        }
}
```

5) Example: SessionAuthorizer.kt (sketch)

- Purpose: session ownership model; owner -> read/write by default.
- Map DAO logical-not-found to ResourceNotFound.

```kotlin
package eu.torvian.chatbot.server.service.authorizer

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.server.data.dao.SessionOwnershipDao

class SessionAuthorizer(
    private val sessionOwnershipDao: SessionOwnershipDao,
) : ResourceAuthorizer {
    override val resourceType: String = "session"

    override suspend fun requireAccess(userId: Long, resourceId: Long, accessMode: AccessMode): Either<ResourceAuthorizerError, Unit> =
        sessionOwnershipDao.getOwner(resourceId).mapLeft { daoErr ->
            // Map DAO logical not-found to ResourceNotFound
            ResourceAuthorizerError.ResourceNotFound(resourceId)
        }.flatMap { ownerId ->
            if (ownerId == userId) Unit.right() else ResourceAuthorizerError.AccessDenied("User does not own session").left()
        }
}
```

6) Example: LLMModelAuthorizer.kt (access table + group grants)

- Purpose: demonstrate richer policy: owner OR grants via an access table that encodes allowed mode (READ/WRITE).
- This is a sketch. Replace DAO method names/types to match your codebase.

```kotlin
package eu.torvian.chatbot.server.service.authorizer

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.server.data.dao.LLMModelDao
import eu.torvian.chatbot.server.data.dao.LLMModelAccessDao
import eu.torvian.chatbot.server.data.dao.UserGroupDao

class LLMModelAuthorizer(
    private val llmModelDao: LLMModelDao,
    private val llmModelAccessDao: LLMModelAccessDao,
    private val userGroupDao: UserGroupDao,
) : ResourceAuthorizer {
    override val resourceType: String = "llm_model"

    override suspend fun requireAccess(userId: Long, resourceId: Long, accessMode: AccessMode): Either<ResourceAuthorizerError, Unit> {
        val modelEither = llmModelDao.getById(resourceId).mapLeft { ResourceAuthorizerError.ResourceNotFound(resourceId) }

        return modelEither.flatMap { model ->
            // Owner shortcut
            if (model.ownerId == userId) return@flatMap Unit.right()

            // Load grants
            val grantsEither = llmModelAccessDao.getAccessEntriesForModel(resourceId)
                .mapLeft { ResourceAuthorizerError.AccessDenied("Access info unavailable") }

            when (grantsEither) {
                is Either.Left -> Either.Left(grantsEither.value)
                is Either.Right -> {
                    val userGroups = userGroupDao.getGroupsForUser(userId)
                    val allowed = grantsEither.value.any { grant ->
                        val groupMatch = userGroups.any { it.id == grant.userGroupId }
                        val modeMatch = when (accessMode) {
                            AccessMode.READ -> grant.allowedMode == AccessMode.READ || grant.allowedMode == AccessMode.WRITE
                            AccessMode.WRITE -> grant.allowedMode == AccessMode.WRITE
                        }
                        groupMatch && modeMatch
                    }
                    if (allowed) Unit.right() else ResourceAuthorizerError.AccessDenied("No grant for user for requested mode").left()
                }
            }
        }
    }
}
```

DI wiring (Koin)
-----------------
Register authorizers and the authorizer map in your `serviceModule()` (or other DI module).

```kotlin
// inside serviceModule() = module {
    single<ResourceAuthorizer> { GroupAuthorizer(get()) }
    single<ResourceAuthorizer> { SessionAuthorizer(get()) }
    single<ResourceAuthorizer> { LLMModelAuthorizer(get(), get(), get()) }
    single<Map<String, ResourceAuthorizer>> { getAll<ResourceAuthorizer>().associateBy { it.resourceType } }

    // AuthorizationServiceImpl should accept Map<String, ResourceAuthorizer> and other deps
    // single<AuthorizationService> { AuthorizationServiceImpl(get(), /* other deps */) }
// }
```

AuthorizationService integration
--------------------------------
Add an additive helper to `AuthorizationService` that performs a resource-level check only. Keep role-level permission checks separate (callers should call both if needed).

API signature (suggested)

```kotlin
suspend fun requireAccess(userId: Long, resourceType: String, resourceId: Long, accessMode: AccessMode): Either<AuthorizationError, Unit>
```

Mapping behavior
- If no authorizer is registered for `resourceType` -> return PermissionDenied (policy: no authorizer means deny).
- Map ResourceAuthorizerError.ResourceNotFound -> AuthorizationError.ResourceNotFound
- Map ResourceAuthorizerError.AccessDenied -> AuthorizationError.PermissionDenied
- Let technical exceptions bubble up to be handled globally (HTTP 500).

AuthorizationServiceImpl pseudo-implementation

```kotlin
class AuthorizationServiceImpl(
    private val authorizers: Map<String, ResourceAuthorizer>,
    // other deps
) : AuthorizationService {
    override suspend fun requireAccess(userId: Long, resourceType: String, resourceId: Long, accessMode: AccessMode): Either<AuthorizationError, Unit> {
        val authorizer = authorizers[resourceType] ?: return Either.Left(AuthorizationError.PermissionDenied("No authorizer for type"))
        return when (val res = authorizer.requireAccess(userId, resourceId, accessMode)) {
            is Either.Left -> when (res.value) {
                is ResourceAuthorizerError.ResourceNotFound -> Either.Left(AuthorizationError.ResourceNotFound(resourceType, resourceId))
                is ResourceAuthorizerError.AccessDenied -> Either.Left(AuthorizationError.PermissionDenied(res.value.reason))
            }
            is Either.Right -> Either.Right(Unit)
        }
    }
}
```

Route usage (Ktor) examples
--------------------------
- Read (GET group):

```kotlin
val userId = call.getUserId()
val res = authorizationService.requireAccess(userId, "group", groupId, AccessMode.READ)
res.fold({ err ->
  when (err) {
    is AuthorizationError.ResourceNotFound -> call.respond(HttpStatusCode.NotFound, ...)
    is AuthorizationError.PermissionDenied -> call.respond(HttpStatusCode.Forbidden, ...)
  }
}, { _ ->
  // proceed to return group
})
```

- Write (DELETE group):

```kotlin
val userId = call.getUserId()
val res = authorizationService.requireAccess(userId, "group", groupId, AccessMode.WRITE)
res.fold({ err ->
  when (err) {
    is AuthorizationError.ResourceNotFound -> call.respond(HttpStatusCode.NotFound, ...)
    is AuthorizationError.PermissionDenied -> call.respond(HttpStatusCode.Forbidden, ...)
  }
}, { _ ->
  // proceed to delete group
})
```

Caching guidance
----------------
- Cache per (userId, resourceId, accessMode) key. Cache both success and deny results with a short TTL (10-30s).
- Do not cache ResourceNotFound indefinitely; use short TTL.
- Do not convert technical errors to Arrow domain errors; do not cache exceptions as domain results.

Example caching decorator (sketch)

```kotlin
class CachingAuthorizer(
    private val delegate: ResourceAuthorizer,
    private val ttlSeconds: Long = 15,
) : ResourceAuthorizer {
    override val resourceType = delegate.resourceType
    private val cache = mutableMapOf<Triple<Long, Long, AccessMode>, Pair<Long, Either<ResourceAuthorizerError, Unit>>>()

    override suspend fun requireAccess(userId: Long, resourceId: Long, accessMode: AccessMode): Either<ResourceAuthorizerError, Unit> {
        val now = System.currentTimeMillis() / 1000
        val key = Triple(userId, resourceId, accessMode)
        cache[key]?.let { (expiry, value) -> if (expiry >= now) return value }
        val res = delegate.requireAccess(userId, resourceId, accessMode)
        cache[key] = Pair(now + ttlSeconds, res)
        return res
    }
}
```

Tests skeletons
---------------
- Unit tests for each authorizer (happy path, resource not found, denied path).
- Unit tests for `AuthorizationService.requireAccess` mapping and missing-authorizer policy.

Example test outline (JUnit + MockK):

```kotlin
class GroupAuthorizerTest {
    @Test
    fun `requireAccess allows owner`() = runBlocking {
        val dao = mockk<GroupOwnershipDao>()
        coEvery { dao.getOwner(1L) } returns 123L.right()
        val auth = GroupAuthorizer(dao)
        val res = auth.requireAccess(123L, 1L, AccessMode.WRITE)
        assertTrue(res.isRight())
    }
}
```

Migration plan (concrete)
-------------------------
1. Add `ResourceAuthorizer` interface and `ResourceAuthorizerError` and `AccessMode` enum files.
2. Implement `GroupAuthorizer` and `SessionAuthorizer` using existing DAOs; add `LLMModelAuthorizer` if LLM models and access tables exist.
3. Register authorizers in DI and provide `Map<String, ResourceAuthorizer>`.
4. Add additive `AuthorizationService.requireAccess(...)` to `AuthorizationService` and implement in the service implementation (accept authorizer map via DI).
5. Replace inline ownership checks in routes with `authorizationService.requireAccess(...)` calls.
6. Run `gradlew.bat server:test` and fix test failures.
7. Optionally: deprecate and remove older service-level ownership lookups in a later cleanup commit.

Commands to run locally
-----------------------
Run server tests (Windows cmd.exe):

```cmd
gradlew.bat server:test
```

Notes & caveats
---------------
- Authorizers should be thin and avoid depending on high-level services that may themselves depend on authorization.
- Keep domain errors and technical errors separated: Arrow for logical errors, exceptions for technical faults.
- Tune caching conservatively; cache only for short time and invalidate on ownership changes if practical.

Appendix: mapping to HTTP errors
--------------------------------
- AuthorizationError.ResourceNotFound -> HTTP 404
- AuthorizationError.PermissionDenied -> HTTP 403
- Unhandled exceptions -> HTTP 500 (global handler)

End of reference implementation document.


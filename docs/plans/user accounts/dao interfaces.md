## Draft of DAO Interface

### Summary of Changes

To transition the application to multi-user, we introduce new DAO interfaces to manage user/group ownership and resource access. Existing entity DAOs (SessionDao, LLMProviderDao, etc.) stay unchanged.

- Ownership DAOs manage one-to-one owner relationships (owners tables).
- Access DAOs manage many-to-many group access (access tables).
- DAOs are deliberately minimal: no extra existence pre-checks (no extra SELECTs). DB constraints determine errors and are mapped to small, pragmatic error types.
- getAll...ForUser returns List (empty list if none).
- Error types are operation-specific and reflect what is realistic to observe from Exposed + SQLite (or similar) without additional queries.

---

### Error Types

#### Ownership errors
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/error/OwnershipErrors.kt
package eu.torvian.chatbot.server.data.dao.error

/**
 * Errors that can occur when reading the owner of a resource.
 */
sealed interface GetOwnerError {
    /**
     * The requested resource (or its ownership row) was not found.
     *
     * @param resourceIdentifier String representation of the resource id (e.g., sessionId or alias).
     */
    data class ResourceNotFound(val resourceIdentifier: String) : GetOwnerError
}

/**
 * Errors that can occur when inserting/setting an ownership link in the owners table.
 *
 * Note: Many databases (and Exposed+SQLite) report only a generic foreign-key constraint
 * violation; therefore we expose a single ForeignKeyViolation bundling resource + referenced id.
 * Higher layers can perform additional existence checks if they need to report a more
 * specific error to clients.
 */
sealed interface SetOwnerError {
    /**
     * A foreign key violation occurred while creating the owner link. This indicates that
     * either the resource row or the referenced user row did not exist at insert time.
     *
     * @param resourceIdentifier String representation of the resource id.
     * @param referencedId The id of the referenced user that was inserted for the owner link.
     */
    data class ForeignKeyViolation(val resourceIdentifier: String, val referencedId: Long) : SetOwnerError

    /**
     * The owners table already contains a row for this resource (unique/PK violation).
     * Ownership for this resource is already set and cannot be created again via setOwner.
     */
    data object AlreadyOwned : SetOwnerError
}
```

#### Access errors
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/error/AccessErrors.kt
package eu.torvian.chatbot.server.data.dao.error

/**
 * Errors that can occur when querying which groups have access to a resource.
 */
sealed interface GetAccessError {
    /**
     * The requested resource was not found (no such row).
     *
     * @param resourceIdentifier String representation of the resource id.
     */
    data class ResourceNotFound(val resourceIdentifier: String) : GetAccessError
}

/**
 * Errors that can occur when granting access to a resource for a group.
 *
 * As with ownership, FK violation details are noisy on some DBs; we use ForeignKeyViolation
 * to indicate either the resource or group was missing at insert time.
 */
sealed interface GrantAccessError {
    /**
     * Either the resource or the group did not exist (FK violation). Higher layers can run
     * existence checks to determine which one if they need a more precise error message.
     *
     * @param resourceIdentifier String representation of the resource id.
     * @param groupId The group id that was supplied when attempting to grant access.
     */
    data class ForeignKeyViolation(val resourceIdentifier: String, val groupId: Long) : GrantAccessError

    /**
     * The access entry already exists (unique constraint on (resource, group)).
     */
    data object AlreadyGranted : GrantAccessError
}

/**
 * Errors that can occur when revoking access to a resource from a group.
 */
sealed interface RevokeAccessError {
    /**
     * Either the resource or the group did not exist (FK violation discovered during delete or check).
     *
     * @param resourceIdentifier String representation of the resource id.
     * @param groupId The group id that was supplied when attempting to revoke access.
     */
    data class ForeignKeyViolation(val resourceIdentifier: String, val groupId: Long) : RevokeAccessError

    /**
     * The access entry did not exist (nothing to revoke). This is useful for idempotency decisions.
     */
    data object AccessNotGranted : RevokeAccessError
}
```

---

### Ownership DAO Interfaces

All getAll...ForUser return List. getOwner returns Either<GetOwnerError, Long>. setOwner returns Either<SetOwnerError, Unit>.

#### SessionOwnershipDao
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/SessionOwnershipDao.kt
package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.ChatSessionSummary
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError

/**
 * DAO for managing ownership links between chat sessions and users.
 *
 * The DAO operates on the table `chat_session_owners` (session_id, user_id).
 * Implementation notes:
 *  - getAllSessionsForUser returns an empty list when the user has no sessions (or the user id does not exist).
 *  - getOwner returns ResourceNotFound when the session row or owner row does not exist.
 *  - setOwner attempts to insert a (session_id, user_id) row. Constraint violations are mapped to SetOwnerError.
 */
interface SessionOwnershipDao {
    /**
     * Retrieves all chat session summaries owned by the given user.
     *
     * @param userId ID of the user.
     * @return List of [ChatSessionSummary] owned by the user; empty list if none.
     */
    suspend fun getAllSessionsForUser(userId: Long): List<ChatSessionSummary>

    /**
     * Returns the user id owning the given session.
     *
     * @param sessionId ID of the session.
     * @return Either [GetOwnerError.ResourceNotFound] if no such session/owner exists, or the owner's user id.
     */
    suspend fun getOwner(sessionId: Long): Either<GetOwnerError, Long>

    /**
     * Creates an ownership link between the session and a user.
     *
     * This performs an insert into `chat_session_owners`. Implementations should map DB constraint
     * violations into [SetOwnerError].
     *
     * @param sessionId ID of the session to own.
     * @param userId ID of the user to become the owner.
     * @return Either [SetOwnerError] or Unit on success.
     */
    suspend fun setOwner(sessionId: Long, userId: Long): Either<SetOwnerError, Unit>
}
```

#### GroupOwnershipDao
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/GroupOwnershipDao.kt
package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.ChatGroup
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError

/**
 * DAO for managing ownership links between chat groups and users.
 *
 * Operates on `chat_group_owners` (group_id, user_id).
 */
interface GroupOwnershipDao {
    /**
     * Retrieves all chat groups owned by the given user.
     *
     * @param userId ID of the user.
     * @return List of [ChatGroup] owned by the user; empty list if none.
     */
    suspend fun getAllGroupsForUser(userId: Long): List<ChatGroup>

    /**
     * Returns the user id of the owner for the specified group.
     *
     * @param groupId ID of the chat group.
     * @return Either [GetOwnerError.ResourceNotFound] or the owner's user id.
     */
    suspend fun getOwner(groupId: Long): Either<GetOwnerError, Long>

    /**
     * Creates an ownership link between a group and a user.
     *
     * @param groupId ID of the group.
     * @param userId ID of the user to set as owner.
     * @return Either [SetOwnerError] or Unit on success.
     */
    suspend fun setOwner(groupId: Long, userId: Long): Either<SetOwnerError, Unit>
}
```

#### ApiSecretOwnershipDao
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/ApiSecretOwnershipDao.kt
package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError

/**
 * DAO for managing ownership links between API secrets and users.
 *
 * Operates on `api_secret_owners` (secret_alias, user_id).
 */
interface ApiSecretOwnershipDao {
    /**
     * Returns the user id owning the API secret identified by alias.
     *
     * @param alias Alias of the API secret.
     * @return Either [GetOwnerError.ResourceNotFound] or the owner's user id.
     */
    suspend fun getOwner(alias: String): Either<GetOwnerError, Long>

    /**
     * Creates an ownership link between an API secret and a user.
     *
     * @param alias Alias of the secret.
     * @param userId ID of the user to set as owner.
     * @return Either [SetOwnerError] or Unit on success.
     */
    suspend fun setOwner(alias: String, userId: Long): Either<SetOwnerError, Unit>
}
```

#### ProviderOwnershipDao
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/ProviderOwnershipDao.kt
package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.LLMProvider
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError

/**
 * DAO for managing ownership links between LLM providers and users.
 *
 * Operates on `llm_provider_owners` (provider_id, user_id).
 */
interface ProviderOwnershipDao {
    /**
     * Retrieves all providers owned by the given user.
     *
     * @param userId ID of the user.
     * @return List of [LLMProvider] owned by the user; empty list if none.
     */
    suspend fun getAllProvidersForUser(userId: Long): List<LLMProvider>

    /**
     * Returns the user id owning the specified provider.
     *
     * @param providerId ID of the provider.
     * @return Either [GetOwnerError.ResourceNotFound] or the owner's user id.
     */
    suspend fun getOwner(providerId: Long): Either<GetOwnerError, Long>

    /**
     * Creates an ownership link between provider and user.
     *
     * @param providerId ID of the provider.
     * @param userId ID of the user to assign as owner.
     * @return Either [SetOwnerError] or Unit on success.
     */
    suspend fun setOwner(providerId: Long, userId: Long): Either<SetOwnerError, Unit>
}
```

#### ModelOwnershipDao
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/ModelOwnershipDao.kt
package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError

/**
 * DAO for managing ownership links between LLM models and users.
 *
 * Operates on `llm_model_owners` (model_id, user_id).
 */
interface ModelOwnershipDao {
    /**
     * Retrieves all models owned by the given user.
     *
     * @param userId ID of the user.
     * @return List of [LLMModel] owned by the user; empty list if none.
     */
    suspend fun getAllModelsForUser(userId: Long): List<LLMModel>

    /**
     * Returns the user id owning the specified model.
     *
     * @param modelId ID of the model.
     * @return Either [GetOwnerError.ResourceNotFound] or the owner's user id.
     */
    suspend fun getOwner(modelId: Long): Either<GetOwnerError, Long>

    /**
     * Creates an ownership link between model and user.
     *
     * @param modelId ID of the model.
     * @param userId ID of the user to assign as owner.
     * @return Either [SetOwnerError] or Unit on success.
     */
    suspend fun setOwner(modelId: Long, userId: Long): Either<SetOwnerError, Unit>
}
```

#### SettingsOwnershipDao
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/SettingsOwnershipDao.kt
package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.ModelSettings
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError

/**
 * DAO for managing ownership links between model settings profiles and users.
 *
 * Operates on `model_settings_owners` (settings_id, user_id).
 */
interface SettingsOwnershipDao {
    /**
     * Retrieves all settings profiles owned by the given user.
     *
     * @param userId ID of the user.
     * @return List of [ModelSettings] owned by the user; empty list if none.
     */
    suspend fun getAllSettingsForUser(userId: Long): List<ModelSettings>

    /**
     * Returns the user id owning the specified settings profile.
     *
     * @param settingsId ID of the settings profile.
     * @return Either [GetOwnerError.ResourceNotFound] or the owner's user id.
     */
    suspend fun getOwner(settingsId: Long): Either<GetOwnerError, Long>

    /**
     * Creates an ownership link between a settings profile and a user.
     *
     * @param settingsId ID of the settings profile.
     * @param userId ID of the user to assign as owner.
     * @return Either [SetOwnerError] or Unit on success.
     */
    suspend fun setOwner(settingsId: Long, userId: Long): Either<SetOwnerError, Unit>
}
```

---

### Access DAO Interfaces

These manage many-to-many group access (llm_provider_access, llm_model_access, etc.). Query methods return lists. Grant/revoke map DB constraint or no-row conditions into the compact errors above.

#### ProviderAccessDao
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/ProviderAccessDao.kt
package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.LLMProvider
import eu.torvian.chatbot.server.data.dao.error.GetAccessError
import eu.torvian.chatbot.server.data.dao.error.GrantAccessError
import eu.torvian.chatbot.server.data.dao.error.RevokeAccessError

/**
 * DAO for managing group-based access to LLM providers.
 *
 * Operates on `llm_provider_access` (provider_id, user_group_id).
 * Typical usage:
 *  - To list accessible providers for a user, service obtains the user's group ids
 *    and calls getAllProvidersForUserGroups(userGroupIds).
 *  - grantAccessToGroup attempts to insert (provider_id, group_id).
 *  - revokeAccessFromGroup attempts to delete the (provider_id, group_id) row.
 */
interface ProviderAccessDao {
    /**
     * Returns all providers accessible to the given user groups.
     *
     * @param userGroupIds List of group IDs the user belongs to.
     * @return List of [LLMProvider] accessible to those groups; empty list if none.
     */
    suspend fun getAllProvidersForUserGroups(userGroupIds: List<Long>): List<LLMProvider>

    /**
     * Returns the IDs of groups that currently have access to the provider.
     *
     * @param providerId Provider id to check.
     * @return Either [GetAccessError.ResourceNotFound] if provider missing or list of group ids that have access.
     */
    suspend fun getAccessibleGroupIds(providerId: Long): Either<GetAccessError, List<Long>>

    /**
     * Grants group access to a provider by inserting a row into access table.
     *
     * Constraint/FK violations are mapped to [GrantAccessError.ForeignKeyViolation]; if the access
     * row already exists, returns [GrantAccessError.AlreadyGranted].
     *
     * @param providerId Provider id.
     * @param groupId Group id to grant access.
     * @return Either [GrantAccessError] or Unit on success.
     */
    suspend fun grantAccessToGroup(providerId: Long, groupId: Long): Either<GrantAccessError, Unit>

    /**
     * Revokes a group's access to a provider by deleting the access row.
     *
     * If the specified row does not exist, returns [RevokeAccessError.AccessNotGranted].
     * If FK issues occur (resource/group missing) returns [RevokeAccessError.ForeignKeyViolation].
     *
     * @param providerId Provider id.
     * @param groupId Group id to revoke.
     * @return Either [RevokeAccessError] or Unit on success.
     */
    suspend fun revokeAccessFromGroup(providerId: Long, groupId: Long): Either<RevokeAccessError, Unit>
}
```

#### ModelAccessDao
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/ModelAccessDao.kt
package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.server.data.dao.error.GetAccessError
import eu.torvian.chatbot.server.data.dao.error.GrantAccessError
import eu.torvian.chatbot.server.data.dao.error.RevokeAccessError

/**
 * DAO for managing group-based access to LLM models.
 *
 * Operates on `llm_model_access` (model_id, user_group_id).
 */
interface ModelAccessDao {
    /**
     * Returns all models accessible to the given user groups.
     *
     * @param userGroupIds List of group IDs.
     * @return List of [LLMModel] accessible to those groups.
     */
    suspend fun getAllModelsForUserGroups(userGroupIds: List<Long>): List<LLMModel>

    /**
     * Returns group ids that have access to the model.
     *
     * @param modelId Model id to check.
     * @return Either [GetAccessError.ResourceNotFound] or list of group ids.
     */
    suspend fun getAccessibleGroupIds(modelId: Long): Either<GetAccessError, List<Long>>

    /**
     * Grants access to the given model for a group.
     *
     * @param modelId Model id.
     * @param groupId Group id.
     * @return Either [GrantAccessError] or Unit on success.
     */
    suspend fun grantAccessToGroup(modelId: Long, groupId: Long): Either<GrantAccessError, Unit>

    /**
     * Revokes access for the group from the model.
     *
     * @param modelId Model id.
     * @param groupId Group id.
     * @return Either [RevokeAccessError] or Unit on success.
     */
    suspend fun revokeAccessFromGroup(modelId: Long, groupId: Long): Either<RevokeAccessError, Unit>
}
```

#### SettingsAccessDao
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/SettingsAccessDao.kt
package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.ModelSettings
import eu.torvian.chatbot.server.data.dao.error.GetAccessError
import eu.torvian.chatbot.server.data.dao.error.GrantAccessError
import eu.torvian.chatbot.server.data.dao.error.RevokeAccessError

/**
 * DAO for managing group-based access to model settings profiles.
 *
 * Operates on `model_settings_access` (settings_id, user_group_id) — create this table if you want
 * group-based sharing of settings profiles.
 */
interface SettingsAccessDao {
    /**
     * Returns all settings profiles accessible to the given user groups.
     *
     * @param userGroupIds List of group IDs.
     * @return List of [ModelSettings] accessible to those groups.
     */
    suspend fun getAllSettingsForUserGroups(userGroupIds: List<Long>): List<ModelSettings>

    /**
     * Returns group ids that have access to the settings profile.
     *
     * @param settingsId Settings profile id.
     * @return Either [GetAccessError.ResourceNotFound] or list of group ids.
     */
    suspend fun getAccessibleGroupIds(settingsId: Long): Either<GetAccessError, List<Long>>

    /**
     * Grants access to the settings profile for the group.
     *
     * @param settingsId Settings id.
     * @param groupId Group id.
     * @return Either [GrantAccessError] or Unit on success.
     */
    suspend fun grantAccessToGroup(settingsId: Long, groupId: Long): Either<GrantAccessError, Unit>

    /**
     * Revokes access from the group for the settings profile.
     *
     * @param settingsId Settings id.
     * @param groupId Group id.
     * @return Either [RevokeAccessError] or Unit on success.
     */
    suspend fun revokeAccessFromGroup(settingsId: Long, groupId: Long): Either<RevokeAccessError, Unit>
}
```

---

### Unchanged DAO Interfaces

The following application DAOs remain unchanged (they will be adapted by services to use the new ownership/access DAOs for filtering/authorization):

- SessionDao
- GroupDao
- SettingsDao
- ApiSecretDao
- LLMProviderDao
- ModelDao
- MessageDao

---

### Implementation notes for the implementers

- Map Exposed/DB constraint exceptions to the compact error types:
    - Insert into owners/access table can throw a ConstraintViolationException (or SQLException). Map unique/PK violations to AlreadyOwned / AlreadyGranted; map FK violations (indistinguishable which FK) to ForeignKeyViolation(resourceIdentifier, referencedId).
    - For setOwner/resource-based inserts use resourceId as String in errors for consistency (e.g., sessionId.toString()) so the error type is generic.
- For getOwner: implement as a SELECT join from owners table; if no row return GetOwnerError.ResourceNotFound(resourceIdentifier).
- getAll...ForUser should be implemented as a simple SELECT where user_id = userId and return empty list if none.
- Service layer may run targeted existence checks (userExists, groupExists, resourceExists) if you want to return more user-friendly error messages (e.g., "user not found" vs "session not found"). Keep such checks at service level to avoid extra DB calls in normal successful paths.
- Ensure DB indices on owner/access tables for fast lookups: indexes on user_id, group_id, resource_id as needed.


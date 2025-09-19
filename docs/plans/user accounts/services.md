Of course. Here is a draft of the necessary changes for the `GroupService` interface, its implementation, and its associated error types to make it multi-user aware.

The core principle is that every operation is now performed **in the context of a specific user**. This means methods require a `userId`, and operations like renaming or deleting must first verify that the user owns the target resource.

---

### 1. New/Updated Service Error Types

We need to introduce a new error type, `AccessDenied`, for when a user tries to operate on a group they do not own.

```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/service/core/error/group/CreateGroupError.kt
// (No changes needed here, but shown for context)
package eu.torvian.chatbot.server.service.core.error.group

/**
 * Represents possible errors during the creation of a chat group.
 */
sealed interface CreateGroupError {
    /**
     * Indicates that the provided name is invalid (e.g., blank).
     */
    data class InvalidName(val reason: String) : CreateGroupError

    /**
     * Indicates a failure while setting the ownership link in the database.
     * This is typically an internal server error.
     */
    data class OwnershipError(val reason: String) : CreateGroupError
}
```

```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/service/core/error/group/DeleteGroupError.kt
package eu.torvian.chatbot.server.service.core.error.group

/**
 * Represents possible errors when deleting a chat group.
 */
sealed interface DeleteGroupError {
    /**
     * Indicates that the group with the specified ID was not found.
     */
    data class GroupNotFound(val id: Long) : DeleteGroupError

    /**
     * Indicates the user does not have permission to delete this group.
     */
    data class AccessDenied(val reason: String) : DeleteGroupError
}
```

```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/service/core/error/group/RenameGroupError.kt
package eu.torvian.chatbot.server.service.core.error.group

/**
 * Represents possible errors when renaming a chat group.
 */
sealed interface RenameGroupError {
    /**
     * Indicates that the group with the specified ID was not found.
     */
    data class GroupNotFound(val id: Long) : RenameGroupError

    /**
     * Indicates that the provided new name is invalid (e.g., blank).
     */
    data class InvalidName(val reason: String) : RenameGroupError

    /**
     * Indicates the user does not have permission to rename this group.
     */
    data class AccessDenied(val reason: String) : RenameGroupError
}
```

---

### 2. Updated `GroupService` Interface

Every method now requires a `userId` to specify the user context for the operation.

```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/service/core/GroupService.kt
package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.ChatGroup
import eu.torvian.chatbot.server.service.core.error.group.CreateGroupError
import eu.torvian.chatbot.server.service.core.error.group.DeleteGroupError
import eu.torvian.chatbot.server.service.core.error.group.RenameGroupError

/**
 * Service interface for managing chat session groups.
 * Defines business logic for group creation, retrieval, renaming, and deletion,
 * all scoped to a specific user.
 */
interface GroupService {
    /**
     * Retrieves a list of all chat groups owned by a specific user.
     * @param userId The ID of the user whose groups are to be retrieved.
     * @return A list of [ChatGroup] objects. Returns an empty list if the user has no groups.
     */
    suspend fun getAllGroups(userId: Long): List<ChatGroup>

    /**
     * Creates a new chat group and assigns ownership to the specified user.
     * @param userId The ID of the user who will own the new group.
     * @param name The name for the new group. Must not be blank.
     * @return Either a [CreateGroupError] if the name is invalid or creation fails,
     *         or the newly created [ChatGroup].
     */
    suspend fun createGroup(userId: Long, name: String): Either<CreateGroupError, ChatGroup>

    /**
     * Renames an existing chat group, verifying ownership first.
     * @param userId The ID of the user attempting the rename.
     * @param id The ID of the group to rename.
     * @param newName The new name for the group. Must not be blank.
     * @return Either a [RenameGroupError] if the group is not found, the user does not own it,
     *         or the new name is invalid, or Unit if successful.
     */
    suspend fun renameGroup(userId: Long, id: Long, newName: String): Either<RenameGroupError, Unit>

    /**
     * Deletes a chat group by ID, verifying ownership first.
     * Sessions previously assigned to this group will become ungrouped.
     * @param userId The ID of the user attempting the deletion.
     * @param id The ID of the group to delete.
     * @return Either a [DeleteGroupError] if the group doesn't exist or the user
     *         does not own it, or Unit if successful.
     */
    suspend fun deleteGroup(userId: Long, id: Long): Either<DeleteGroupError, Unit>
}
```

---

### 3. Updated `GroupServiceImpl` Implementation

The implementation now depends on the new `GroupOwnershipDao` to handle ownership checks and user-scoped queries.

```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/service/core/impl/GroupServiceImpl.kt
package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.models.ChatGroup
import eu.torvian.chatbot.server.data.dao.GroupDao
import eu.torvian.chatbot.server.data.dao.GroupOwnershipDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.service.core.GroupService
import eu.torvian.chatbot.server.service.core.error.group.CreateGroupError
import eu.torvian.chatbot.server.service.core.error.group.DeleteGroupError
import eu.torvian.chatbot.server.service.core.error.group.RenameGroupError
import eu.torvian.chatbot.server.utils.transactions.TransactionScope

/**
 * Implementation of the [GroupService] interface for a multi-user environment.
 */
class GroupServiceImpl(
    private val groupDao: GroupDao,
    private val groupOwnershipDao: GroupOwnershipDao, // New dependency
    private val sessionDao: SessionDao,
    private val transactionScope: TransactionScope,
) : GroupService {

    override suspend fun getAllGroups(userId: Long): List<ChatGroup> {
        return transactionScope.transaction {
            // Delegate directly to the ownership DAO to get user-specific groups
            groupOwnershipDao.getAllGroupsForUser(userId)
        }
    }

    override suspend fun createGroup(userId: Long, name: String): Either<CreateGroupError, ChatGroup> =
        transactionScope.transaction {
            either {
                ensure(!name.isBlank()) {
                    CreateGroupError.InvalidName("Group name cannot be blank.")
                }

                // Step 1: Create the group in the main table
                val newGroup = groupDao.insertGroup(name)

                // Step 2: Set the ownership in the association table
                groupOwnershipDao.setOwner(newGroup.id, userId).mapLeft { daoError ->
                    // Map DAO error to a service-level error
                    when (daoError) {
                        is SetOwnerError.ForeignKeyViolation ->
                            CreateGroupError.OwnershipError("Failed to set owner: User with ID $userId may not exist.")
                        is SetOwnerError.AlreadyOwned ->
                            CreateGroupError.OwnershipError("Failed to set owner: Group with ID ${newGroup.id} is already owned.")
                    }
                }.bind() // Raise the error if setting owner fails

                newGroup
            }
        }

    override suspend fun renameGroup(userId: Long, id: Long, newName: String): Either<RenameGroupError, Unit> =
        transactionScope.transaction {
            either {
                ensure(!newName.isBlank()) {
                    RenameGroupError.InvalidName("New group name cannot be blank.")
                }

                // Step 1: Verify the user owns this group before proceeding
                checkOwnership(userId, id).mapLeft { error ->
                    when (error) {
                        is OwnershipCheckError.NotFound -> RenameGroupError.GroupNotFound(id)
                        is OwnershipCheckError.AccessDenied -> RenameGroupError.AccessDenied(error.reason)
                    }
                }.bind()

                // Step 2: Perform the rename if ownership is confirmed
                groupDao.renameGroup(id, newName).mapLeft {
                    // This should be rare if checkOwnership passed, but handle for completeness
                    RenameGroupError.GroupNotFound(id)
                }.bind()
            }
        }

    override suspend fun deleteGroup(userId: Long, id: Long): Either<DeleteGroupError, Unit> =
        transactionScope.transaction {
            either {
                // Step 1: Verify ownership before deleting
                checkOwnership(userId, id).mapLeft { error ->
                    when (error) {
                        is OwnershipCheckError.NotFound -> DeleteGroupError.GroupNotFound(id)
                        is OwnershipCheckError.AccessDenied -> DeleteGroupError.AccessDenied(error.reason)
                    }
                }.bind()

                // Step 2: Ungroup sessions and delete the group
                sessionDao.ungroupSessions(id)
                groupDao.deleteGroup(id).mapLeft {
                    DeleteGroupError.GroupNotFound(id)
                }.bind()
            }
        }

    /**
     * Internal helper to verify if a user is the owner of a specific group.
     */
    private suspend fun checkOwnership(userId: Long, groupId: Long): Either<OwnershipCheckError, Unit> {
        return groupOwnershipDao.getOwner(groupId).fold(
            ifLeft = { daoError ->
                when (daoError) {
                    is GetOwnerError.ResourceNotFound -> Either.Left(OwnershipCheckError.NotFound)
                }
            },
            ifRight = { ownerId ->
                if (ownerId == userId) {
                    Either.Right(Unit)
                } else {
                    Either.Left(OwnershipCheckError.AccessDenied("User $userId is not the owner of group $groupId."))
                }
            }
        )
    }

    /**
     * Sealed interface for private ownership check results.
     */
    private sealed interface OwnershipCheckError {
        data object NotFound : OwnershipCheckError
        data class AccessDenied(val reason: String) : OwnershipCheckError
    }
}
```
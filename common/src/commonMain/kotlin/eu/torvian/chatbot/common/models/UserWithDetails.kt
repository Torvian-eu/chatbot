package eu.torvian.chatbot.common.models

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Rich user projection with assigned roles and group memberships.
 *
 * This model is intended for admin UI screens to display all relevant user
 * information without additional API calls. It can be downgraded to the
 * lightweight [User] model via [toUser].
 *
 * @property id Unique identifier for the user
 * @property username User's unique name
 * @property email Optional email address
 * @property status Current account status
 * @property roles Roles assigned to the user
 * @property userGroups Groups the user belongs to
 * @property createdAt Account creation timestamp
 * @property lastLogin Last successful login timestamp (nullable)
 */
@Serializable
data class UserWithDetails(
    val id: Long,
    val username: String,
    val email: String?,
    val status: UserStatus,
    val roles: List<Role>,
    val userGroups: List<UserGroup>,
    val createdAt: Instant,
    val lastLogin: Instant?
) {
    /** Returns the lightweight [User] representation of this object. */
    fun toUser(): User = User(id, username, email, status, createdAt, lastLogin)

    /** Checks if the user has the given role by its name. */
    fun hasRole(roleName: String): Boolean = roles.any { it.name == roleName }

    /** Checks if the user belongs to the given group by its name. */
    fun belongsToGroup(groupName: String): Boolean = userGroups.any { it.name == groupName }

    /** True when the user is allowed to sign in. */
    val canLogin: Boolean get() = status == UserStatus.ACTIVE

    /** True when the account requires administrative attention. */
    val needsAttention: Boolean get() = status != UserStatus.ACTIVE
}

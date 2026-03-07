package eu.torvian.chatbot.app.service.auth

import eu.torvian.chatbot.common.models.user.Permission
import eu.torvian.chatbot.common.models.user.User
import kotlin.time.Instant

/**
 * Represents metadata about a stored user account.
 *
 * This model is used for displaying available accounts in the UI and tracking
 * when each account was last accessed. It does not contain sensitive authentication
 * data like tokens.
 *
 * @property user The authenticated user data for optimistic authentication
 * @property permissions The list of permissions granted to the user
 * @property lastUsed The timestamp when this account was last actively used
 */
data class AccountData(
    val user: User,
    val permissions: List<Permission>,
    val lastUsed: Instant
)
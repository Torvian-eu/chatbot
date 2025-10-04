package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Represents the current operational status of a user account.
 *
 * This status is used by both the server and the client to determine whether
 * a user can authenticate and access the system, or requires administrative
 * attention. Keep this in sync with any server-side authorization checks.
 */
@Serializable
enum class UserStatus {
    /** The user account is active and allowed to sign in. */
    ACTIVE,

    /** The user account is disabled by an administrator and cannot sign in. */
    DISABLED,

    /** The user account is locked due to security reasons (e.g., too many failed attempts). */
    LOCKED
}

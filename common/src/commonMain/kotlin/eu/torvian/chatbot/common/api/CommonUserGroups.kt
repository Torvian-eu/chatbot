package eu.torvian.chatbot.common.api

/**
 * Centralized constants for well-known user group keys used across the application.
 *
 * This object defines stable, machine-facing identifiers for special user groups
 * that have specific behavioral semantics in the system. Display names and descriptions
 * should be handled at the UI/service layer to support localization.
 *
 * Similar to [CommonRoles], this provides a single source of truth for group identifiers
 * shared between server, client, and test code.
 */
object CommonUserGroups {
    /**
     * The canonical key for the special "All Users" group.
     *
     * This group has special semantics:
     * - All users are automatically members (implicit membership)
     * - Cannot be deleted
     * - Users cannot be removed from it
     * - Resources shared with this group are effectively "public"
     *
     * Use this constant for programmatic checks rather than hardcoding the string.
     */
    const val ALL_USERS: String = "all_users"
}


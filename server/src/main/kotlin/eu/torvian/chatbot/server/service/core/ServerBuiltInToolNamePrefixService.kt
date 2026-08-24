package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.server.service.core.error.serverbuiltin.UpdateServerBuiltInToolNamePrefixError

/**
 * Manages a user's server built-in tool name prefix (the well-known
 * `server_builtin_tool_name_prefix` preference).
 *
 * Updating or deleting the prefix persists the preference row and renames the user's persisted
 * server built-in tool public names **atomically** in one transaction, so dispatch (which keys on
 * the untouched canonical `builtInToolName`) can never be left inconsistent with the stored
 * preference. The generic preference service stays tool-domain-free; this dedicated service owns
 * the tool-specific side effects.
 */
interface ServerBuiltInToolNamePrefixService {

    /**
     * Validates and stores the user's prefix and renames their persisted tool names to match.
     *
     * A blank prefix means "no prefix" (canonical names). The preference is written as a
     * GLOBAL-scope row; a rename failure rolls the whole operation back.
     *
     * @param userId Owning user identifier.
     * @param prefix The requested prefix (blank is valid and means no prefix).
     * @return Either an [UpdateServerBuiltInToolNamePrefixError] or Unit on success.
     */
    suspend fun updatePrefix(
        userId: Long,
        prefix: String
    ): Either<UpdateServerBuiltInToolNamePrefixError, Unit>

    /**
     * Deletes the user's prefix preference and resets their tool names to the server default.
     *
     * Idempotent: when no preference row exists, only the rename to the default prefix is applied.
     * The scope query parameter of the underlying DELETE endpoint is ignored for this key — the
     * row is always the global one.
     *
     * @param userId Owning user identifier.
     * @return Either an [UpdateServerBuiltInToolNamePrefixError] or Unit on success.
     */
    suspend fun deletePrefix(
        userId: Long
    ): Either<UpdateServerBuiltInToolNamePrefixError, Unit>
}

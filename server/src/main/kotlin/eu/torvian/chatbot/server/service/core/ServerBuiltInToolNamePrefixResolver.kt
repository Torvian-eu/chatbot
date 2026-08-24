package eu.torvian.chatbot.server.service.core

/**
 * Resolves the effective server built-in tool name prefix for a user.
 *
 * The prefix is a per-user, global-scope user preference (well-known key
 * [eu.torvian.chatbot.common.models.api.me.PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX]):
 * a stored value wins, a blank stored value means "no prefix" (canonical names), and an absent
 * row falls back to [DEFAULT_SERVER_BUILTIN_TOOL_NAME_PREFIX]. The resolved value is consumed by
 * the seeder (seeding/repair/rename) and the prefix service (delete → default) only; the executor
 * never performs a preference lookup — it dispatches on the persisted canonical
 * `builtInToolName`.
 *
 * This component is deliberately separate from the mutating prefix service to avoid a
 * seeder ⇄ service dependency cycle.
 */
interface ServerBuiltInToolNamePrefixResolver {

    companion object {
        /**
         * Hardcoded server default prefix in this stage.
         *
         * A user without a stored preference gets `chatbot-<canonical>` public names. The constant
         * lives on this contract so every consumer (seeder, prefix service, tests, UI hint) shares
         * one value; a future stage makes it configurable via `tools.builtInToolNamePrefix` and
         * only swaps the Koin binding (Appendix A of the prefix architecture report).
         */
        const val DEFAULT_SERVER_BUILTIN_TOOL_NAME_PREFIX = "chatbot-"
    }

    /**
     * Resolves the effective prefix for [userId].
     *
     * Reads the user's global preference rows; returns the stored value normalized (blank →
     * empty string) when present, otherwise [DEFAULT_SERVER_BUILTIN_TOOL_NAME_PREFIX]. The call
     * is transaction-safe: the DAO read joins an active transaction when one exists.
     *
     * @param userId Owning user identifier.
     * @return The effective prefix; `""` means no prefix (canonical names).
     */
    suspend fun resolvePrefix(userId: Long): String
}

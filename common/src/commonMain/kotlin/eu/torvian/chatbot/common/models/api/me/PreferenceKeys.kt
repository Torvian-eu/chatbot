package eu.torvian.chatbot.common.models.api.me

/**
 * Well-known user-preference keys shared between the server, the clients, and the web/desktop UIs.
 *
 * Keeping the keys as shared constants prevents typos from silently splitting the preference store
 * between modules and keeps the server-side route branching and the client-side settings UI in sync.
 */
object PreferenceKeys {

    /**
     * Global-scope user preference holding the user's server built-in tool name prefix.
     *
     * The value is the raw prefix string concatenated (without a separator) to the canonical
     * catalog name to form the public/LLM-facing tool name, e.g. `chatbot-list_agent_roles` for
     * the default prefix `"chatbot-"`. A blank value means "no prefix" (canonical names); an
     * absent row means the server default prefix applies. The preference must be stored in the
     * GLOBAL scope because server-side execution needs a single effective value per user.
     */
    const val SERVER_BUILTIN_TOOL_NAME_PREFIX = "server_builtin_tool_name_prefix"
}

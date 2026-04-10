package eu.torvian.chatbot.server.testutils.data

/**
 * Enum representing database tables in the chatbot project.
 */
enum class Table {
    /**
     * Table for storing chat sessions.
     */
    CHAT_SESSIONS,

    /**
     * Table for storing chat messages.
     */
    CHAT_MESSAGES,

    /**
     * Table for storing assistant messages.
     */
    ASSISTANT_MESSAGES,

    /**
     * Table for storing chat groups.
     */
    CHAT_GROUPS,

    /**
     * Table for storing LLM models.
     */
    LLM_MODELS,

    /**
     * Table for storing model settings.
     */
    MODEL_SETTINGS,

    /**
     * Table for storing API secrets.
     */
    API_SECRETS,

    /**
     * Table for storing LLM provider configurations.
     */
    LLM_PROVIDERS,

    /**
     * Table for storing session current leaf message relationships.
     * This table breaks the circular dependency between sessions and messages.
     */
    SESSION_CURRENT_LEAF,

    // User management tables
    /**
     * Table for storing user accounts.
     */
    USERS,

    /**
     * Table for storing user roles.
     */
    ROLES,

    /**
     * Table for storing permissions.
     */
    PERMISSIONS,

    /**
     * Table for linking roles to permissions.
     */
    ROLE_PERMISSIONS,

    /**
     * Table for assigning roles to users.
     */
    USER_ROLE_ASSIGNMENTS,

    /**
     * Table for storing user authentication sessions.
     */
    USER_SESSIONS,

    /**
     * Table for storing user-defined groups.
     */
    USER_GROUPS,

    /**
     * Table for linking users to groups.
     */
    USER_GROUP_MEMBERSHIPS,

    /**
     * Table for storing worker identities.
     */
    WORKERS,

    /**
     * Table for storing one-time worker authentication challenges.
     */
    WORKER_AUTH_CHALLENGES,

    // Ownership tables
    /**
     * Table for linking chat sessions to their owners.
     */
    CHAT_SESSION_OWNERS,

    /**
     * Table for linking chat groups to their owners.
     */
    CHAT_GROUP_OWNERS,

    /**
     * Table for linking LLM providers to their owners.
     */
    LLM_PROVIDER_OWNERS,

    /**
     * Table for linking LLM models to their owners.
     */
    LLM_MODEL_OWNERS,

    /**
     * Table for linking model settings to their owners.
     */
    MODEL_SETTINGS_OWNERS,

    /**
     * Table for linking API secrets to their owners.
     */
    API_SECRET_OWNERS,

    // Access tables
    /**
     * Table for granting group access to LLM providers.
     */
    LLM_PROVIDER_ACCESS,

    /**
     * Table for granting group access to LLM models.
     */
    LLM_MODEL_ACCESS,

    /**
     * Table for granting group access to model settings.
     */
    MODEL_SETTINGS_ACCESS,

    // Tool tables
    /**
     * Table for storing tool definitions.
     */
    TOOL_DEFINITIONS,

    /**
     * Table for storing tool call records.
     */
    TOOL_CALLS,

    /**
     * Table for storing session-specific tool configurations.
     */
    SESSION_TOOL_CONFIG,

    /**
     * Table for storing local MCP server configurations (server-side ID generation).
     */
    LOCAL_MCP_SERVERS,

    /**
     * Table for linking MCP tools to their source servers.
     */
    LOCAL_MCP_TOOL_DEFINITIONS,
}
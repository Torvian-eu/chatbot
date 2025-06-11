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
     * Table for storing session current leaf message relationships.
     * This table breaks the circular dependency between sessions and messages.
     */
    SESSION_CURRENT_LEAF,
}
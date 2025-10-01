package eu.torvian.chatbot.server.service.authorizer

/**
 * Represents the kind of access being requested. WRITE implies READ.
 */
enum class AccessMode {
    READ,
    WRITE
}


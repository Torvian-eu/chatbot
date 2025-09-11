package eu.torvian.chatbot.app.domain.events

/**
 * Represents a generic, uncategorized application warning.
 *
 * @param message A human-readable message describing the warning
 */
class GenericAppWarning(
    message: String
) : AppWarning(
    message = message
)
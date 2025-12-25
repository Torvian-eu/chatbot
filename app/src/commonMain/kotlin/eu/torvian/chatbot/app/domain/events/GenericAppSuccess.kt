package eu.torvian.chatbot.app.domain.events

/**
 * Represents a generic, uncategorized application success notification.
 *
 * @param message A human-readable message describing the success
 */
class GenericAppSuccess(
    message: String
) : AppSuccess(
    message = message
)


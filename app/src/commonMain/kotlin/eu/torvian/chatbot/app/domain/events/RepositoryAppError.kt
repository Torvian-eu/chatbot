package eu.torvian.chatbot.app.domain.events

import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_repository
import eu.torvian.chatbot.app.repository.RepositoryError
import org.jetbrains.compose.resources.getString

/**
 * Represents an error originating from the Repository layer.
 * Includes the specific RepositoryError for detailed handling.
 *
 * @property repositoryError The repository error object
 * @param message A human-readable message for display to the user
 * @param shortMessage A short version of the error message
 * @param isRetryable Whether the error is retryable (defaults to false)
 */
class RepositoryAppError(
    val repositoryError: RepositoryError,
    message: String,
    shortMessage: String? = null,
    isRetryable: Boolean = false
) : AppError(
    message = message,
    shortMessage = shortMessage,
    isRetryable = isRetryable
)

/**
 * Factory function to create a RepositoryAppError with a localized message.
 *
 * @param repositoryAppError The repository error object
 * @param shortMessage A short version of the error message
 * @param isRetryable Whether the error is retryable (defaults to false)
 */
suspend fun repositoryAppError(
    repositoryError: RepositoryError,
    shortMessage: String,
    isRetryable: Boolean = false
): RepositoryAppError {
    val message = getString(Res.string.error_repository, shortMessage, repositoryError.message)
    return RepositoryAppError(repositoryError, message, shortMessage, isRetryable)
}
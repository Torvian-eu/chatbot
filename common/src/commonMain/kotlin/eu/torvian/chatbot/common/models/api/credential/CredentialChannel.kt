package eu.torvian.chatbot.common.models.api.credential

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a credential profile delivers its secret to the profile's own process.
 *
 * The channel is chosen by the operator, never by the model: it decides whether the value reaches the
 * process through an askpass script, an environment variable, or a read-only file. All three avoid
 * writing the value to the process's terminal, which a PTY would echo.
 */
@Serializable
enum class CredentialChannel {
    /**
     * Points an operator-named environment variable at the worker's askpass script, which answers a
     * tool prompt by reading the secret from a private file.
     */
    @SerialName("askpass")
    ASKPASS,

    /**
     * Places the secret value in one environment variable of the profile's process.
     */
    @SerialName("env")
    ENV,

    /**
     * Places the secret value in a read-only file and sets one environment variable to its path.
     */
    @SerialName("file")
    FILE,
    ;

    companion object {
        /**
         * Every channel name accepted by the schema, in canonical order.
         *
         * Exposed so validation messages and the settings UI enumerate the set without repeating it.
         */
        val all: List<CredentialChannel> = entries.toList()
    }
}

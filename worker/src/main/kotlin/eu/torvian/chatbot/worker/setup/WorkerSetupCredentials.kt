package eu.torvian.chatbot.worker.setup

/**
 * Credentials used by setup to authenticate as a user while registering the worker.
 *
 * These values are obtained from environment variables or an interactive terminal prompt
 * and are used only for the setup-time login/logout sequence.
 *
 * @property username User account name used to log in to the server.
 * @property password Plaintext password used to authenticate the user.
 */
data class WorkerSetupCredentials(
    val username: String,
    val password: String
)
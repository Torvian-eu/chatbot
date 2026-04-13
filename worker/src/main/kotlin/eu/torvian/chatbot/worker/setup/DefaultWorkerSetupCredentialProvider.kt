package eu.torvian.chatbot.worker.setup

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.io.Console

/**
 * Default credential resolver used by setup.
 *
 * Resolution order:
 * 1. Environment variables `CHATBOT_WORKER_SETUP_USERNAME` and `CHATBOT_WORKER_SETUP_PASSWORD`
 * 2. Interactive terminal prompt
 *
 * @property readEnv Function used to resolve environment variables; overridable for tests.
 * @property lineReader Function used to read a line from the terminal; overridable for tests.
 * @property consoleProvider Function used to access a console for hidden password entry; overridable for tests.
 */
class DefaultWorkerSetupCredentialProvider(
    private val readEnv: (String) -> String? = System::getenv,
    private val lineReader: () -> String? = { readlnOrNull() },
    private val consoleProvider: () -> Console? = System::console
) : WorkerSetupCredentialProvider {

    override suspend fun resolveCredentials(): Either<WorkerSetupError, WorkerSetupCredentials> {
        val envUsername = readEnv(USERNAME_ENV_VAR)?.trim().orEmpty()
        val envPassword = readEnv(PASSWORD_ENV_VAR).orEmpty()

        if (envUsername.isNotBlank() || envPassword.isNotBlank()) {
            return if (envUsername.isBlank() || envPassword.isBlank()) {
                WorkerSetupError.CredentialsUnavailable(
                    "Both $USERNAME_ENV_VAR and $PASSWORD_ENV_VAR must be set when using environment credentials"
                ).left()
            } else {
                WorkerSetupCredentials(envUsername, envPassword).right()
            }
        }

        val hasConsole = consoleProvider() != null
        printSetupExplanation()
        if (!hasConsole) {
            printNonInteractiveHint()
        }

        print("Server account username: ")
        val username = lineReader()?.trim().orEmpty()
        if (username.isBlank()) {
            return WorkerSetupError.CredentialsUnavailable(
                "No username was provided. If interactive input is unavailable (common with gradle run), " +
                    "set $USERNAME_ENV_VAR and $PASSWORD_ENV_VAR, or run the installed worker executable directly."
            ).left()
        }

        val password = readPasswordInteractive()
        if (password.isBlank()) {
            return WorkerSetupError.CredentialsUnavailable(
                "No password was provided. If interactive input is unavailable (common with gradle run), " +
                    "set $USERNAME_ENV_VAR and $PASSWORD_ENV_VAR, or run the installed worker executable directly."
            ).left()
        }

        return WorkerSetupCredentials(username = username, password = password).right()
    }

    /**
     * Reads the password using the safest available terminal mechanism.
     *
     * When a console is available, the password is read without echoing characters.
     * Otherwise, the provider falls back to a plain line read.
     *
     * @return The entered password, or an empty string when no input was provided.
     */
    private fun readPasswordInteractive(): String {
        val console = consoleProvider()
        if (console != null) {
            val passwordChars = console.readPassword("Server account password: ") ?: return ""
            return passwordChars.concatToString()
        }

        print("Server account password: ")
        return lineReader().orEmpty()
    }

    /**
     * Prints a short explanation of what setup will do.
     *
     * The message is intentionally simple and non-technical so operators understand that
     * their existing server account will be used to register this worker automatically.
     */
    private fun printSetupExplanation() {
        println(
            "This setup will sign in with an existing server account, create a secure worker identity, " +
                "register the worker with the server, and then sign out again."
        )
    }

    /**
     * Prints a practical fallback hint for terminals where interactive input may not work.
     */
    private fun printNonInteractiveHint() {
        println(
            "Interactive input may be unavailable in this run mode. " +
                "If prompts do not accept input, use environment variables " +
                "$USERNAME_ENV_VAR and $PASSWORD_ENV_VAR."
        )
    }

    companion object {
        /**
         * Environment variable that can supply the setup username non-interactively.
         */
        const val USERNAME_ENV_VAR = "CHATBOT_WORKER_SETUP_USERNAME"

        /**
         * Environment variable that can supply the setup password non-interactively.
         */
        const val PASSWORD_ENV_VAR = "CHATBOT_WORKER_SETUP_PASSWORD"
    }
}
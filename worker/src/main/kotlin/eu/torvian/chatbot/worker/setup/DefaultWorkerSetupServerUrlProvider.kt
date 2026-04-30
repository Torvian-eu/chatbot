package eu.torvian.chatbot.worker.setup

import arrow.core.Either
import arrow.core.left
import arrow.core.right

/**
 * Default server-URL resolver used by setup.
 *
 * Resolution order:
 * 1. Environment variable `CHATBOT_WORKER_SETUP_SERVER_URL`
 * 2. Interactive terminal prompt pre-filled with the configured default
 *
 * @property readEnv Function used to resolve environment variables; overridable for tests.
 * @property lineReader Function used to read a line from the terminal; overridable for tests.
 */
class DefaultWorkerSetupServerUrlProvider(
    private val readEnv: (String) -> String? = System::getenv,
    private val lineReader: () -> String? = { readlnOrNull() }
) : WorkerSetupServerUrlProvider {

    override suspend fun resolveServerUrl(defaultServerUrl: String): Either<WorkerSetupError, String> {
        val envServerUrl = readEnv(SERVER_URL_ENV_VAR)?.trim().orEmpty()

        if (envServerUrl.isNotBlank()) {
            return envServerUrl.right()
        }

        print("Server URL [$defaultServerUrl]: ")
        val input = lineReader()?.trim().orEmpty()
        val resolved = input.ifBlank { defaultServerUrl }

        if (resolved.isBlank()) {
            return WorkerSetupError.ServerUrlUnavailable(
                "No server URL was provided and the default was blank. " +
                    "Set $SERVER_URL_ENV_VAR or provide a value interactively."
            ).left()
        }

        return resolved.right()
    }

    companion object {
        /**
         * Environment variable that can supply the server URL non-interactively.
         */
        const val SERVER_URL_ENV_VAR = "CHATBOT_WORKER_SETUP_SERVER_URL"
    }
}

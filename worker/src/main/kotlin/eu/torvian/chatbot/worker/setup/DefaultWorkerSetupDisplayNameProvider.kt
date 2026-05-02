package eu.torvian.chatbot.worker.setup

import arrow.core.Either
import arrow.core.left
import arrow.core.right

/**
 * Default display-name resolver used by setup.
 *
 * Resolution order:
 * 1. Environment variable `CHATBOT_WORKER_SETUP_DISPLAY_NAME`
 * 2. Interactive terminal prompt pre-filled with the configured default
 *
 * @property readEnv Function used to resolve environment variables; overridable for tests.
 * @property lineReader Function used to read a line from the terminal; overridable for tests.
 */
class DefaultWorkerSetupDisplayNameProvider(
    private val readEnv: (String) -> String? = System::getenv,
    private val lineReader: () -> String? = { readlnOrNull() }
) : WorkerSetupDisplayNameProvider {

    override suspend fun resolveDisplayName(defaultDisplayName: String): Either<WorkerSetupError, String> {
        val envDisplayName = readEnv(DISPLAY_NAME_ENV_VAR)?.trim().orEmpty()

        if (envDisplayName.isNotBlank()) {
            return envDisplayName.right()
        }

        print("Worker display name [$defaultDisplayName]: ")
        val input = lineReader()?.trim().orEmpty()
        val resolved = input.ifBlank { defaultDisplayName }

        if (resolved.isBlank()) {
            return WorkerSetupError.DisplayNameUnavailable(
                "No display name was provided and the default was blank. " +
                    "Set $DISPLAY_NAME_ENV_VAR or provide a value interactively."
            ).left()
        }

        return resolved.right()
    }

    companion object {
        /**
         * Environment variable that can supply the worker display name non-interactively.
         */
        const val DISPLAY_NAME_ENV_VAR = "CHATBOT_WORKER_SETUP_DISPLAY_NAME"
    }
}

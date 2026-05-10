package eu.torvian.chatbot.worker.setup

import arrow.core.Either
import arrow.core.right

/**
 * Default device ID resolver used by setup.
 *
 * Resolution order:
 * 1. Environment variable `CHATBOT_WORKER_SETUP_DEVICE_ID`
 * 2. Interactive terminal prompt
 *
 * @property readEnv Function used to resolve environment variables; overridable for tests.
 * @property lineReader Function used to read a line from the terminal; overridable for tests.
 */
class DefaultWorkerSetupDeviceIdProvider(
    private val readEnv: (String) -> String? = System::getenv,
    private val lineReader: () -> String? = { readlnOrNull() }
) : WorkerSetupDeviceIdProvider {

    override suspend fun resolveDeviceId(): Either<WorkerSetupError, String?> {
        // First check environment variable
        val envDeviceId = readEnv(DEVICE_ID_ENV_VAR)?.trim()
        if (envDeviceId != null) {
            return envDeviceId.right()
        }

        // Prompt user for optional device ID
        print("Trusted Device ID (optional, required if server is in STRICT mode): ")
        val input = lineReader()?.trim()
        return input.right()
    }

    companion object {
        /**
         * Environment variable that can supply the trusted device ID non-interactively.
         */
        const val DEVICE_ID_ENV_VAR = "CHATBOT_WORKER_SETUP_DEVICE_ID"
    }
}

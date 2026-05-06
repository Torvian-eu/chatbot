package eu.torvian.chatbot.worker.setup

import arrow.core.Either

/**
 * Resolves a Trusted Device ID for setup to bypass STRICT account security mode.
 *
 * When the server is in STRICT mode, login requires a known device. This provider
 * allows the user to specify an existing trusted device's ID (e.g., from their Desktop app)
 * so the worker can authenticate during setup. If no device ID is provided, the
 * worker's own uid will be used as the device ID.
 */
interface WorkerSetupDeviceIdProvider {
    /**
     * Resolves a Trusted Device ID for the setup-time login flow.
     *
     * @return Either a logical setup error or the device ID (trimmed, possibly empty/null).
     */
    suspend fun resolveDeviceId(): Either<WorkerSetupError, String?>
}

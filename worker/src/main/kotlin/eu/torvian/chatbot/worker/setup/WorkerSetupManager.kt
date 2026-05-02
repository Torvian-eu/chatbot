package eu.torvian.chatbot.worker.setup

import arrow.core.Either
import eu.torvian.chatbot.worker.config.AppConfigDto
import java.nio.file.Path

/**
 * Orchestrates worker setup and configuration persistence.
 */
interface WorkerSetupManager {
    /**
     * Executes the setup flow for a worker configuration directory.
     *
     * @param configDir Target worker config directory path.
     * @param mergedConfig Merged worker configuration loaded by the caller.
     * @param serverUrlOverride Optional setup-time override for `worker.server.baseUrl`.
     * @return Either a logical setup error or `Unit` on successful setup.
     */
    suspend fun run(
        configDir: Path,
        mergedConfig: AppConfigDto,
        serverUrlOverride: String? = null
    ): Either<WorkerSetupError, Unit>
}


package eu.torvian.chatbot.worker.service.api

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.common.api.resources.ServerInfo
import eu.torvian.chatbot.common.models.api.metadata.ServerInfoResponse
import eu.torvian.chatbot.worker.VersionInfo
import eu.torvian.chatbot.worker.main.WorkerMainError
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.resources.get
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Retrieves public server metadata and checks whether the worker can safely speak to it.
 *
 * The service performs a lightweight handshake during worker startup so the operator gets an
 * early warning when the worker and server are built for different protocol versions. The check
 * is intentionally non-fatal: the worker continues to boot even if the lookup fails or the
 * versions do not match.
 *
 * @property client Ktor client injected by Koin and configured for the worker's server base URL.
 */
class WorkerMetadataService(
    private val client: HttpClient
) {
    /**
     * Checks the worker/server version pair and warns when the normalized versions diverge.
     *
     * The comparison ignores snapshot/build suffixes by comparing only the base version part
     * before the first `-`, mirroring the app's compatibility logic.
     *
     * Network and deserialization problems are logged and treated as non-fatal so startup can
     * continue without interruption.
     *
     * @return A successful result even when the compatibility probe fails or versions differ.
     */
    suspend fun checkCompatibility(): Either<WorkerMainError, Unit> {
        return try {
            val serverInfo = client.get(ServerInfo()).body<ServerInfoResponse>()
            val localVersion = VersionInfo.VERSION.split("-").first()
            val remoteVersion = serverInfo.version.split("-").first()

            if (localVersion != remoteVersion) {
                logger.warn(
                    "⚠️ VERSION MISMATCH: Worker (v$localVersion) vs Server (v$remoteVersion). Incompatible tool execution protocols may cause failures!"
                )
            }

            Unit.right()
        } catch (e: Exception) {
            logger.warn("Unable to verify worker/server version compatibility; continuing startup.", e)
            Unit.right()
        }
    }

    companion object {
        private val logger: Logger = LogManager.getLogger(WorkerMetadataService::class.java)
    }
}



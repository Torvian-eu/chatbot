package eu.torvian.chatbot.app.service.mcp

import arrow.core.Either
import eu.torvian.chatbot.app.domain.models.LocalMCPServer
import kotlinx.io.Sink
import kotlinx.io.Source

/**
 * Android implementation of LocalMCPServerProcessManager.
 *
 * This implementation provides a placeholder for Android-specific process management.
 * It does not support actual process creation or lifecycle management due to platform limitations.
 *
 * This implementation always returns errors for all operations.
 */
class LocalMCPProcessManagerAndroid : LocalMCPServerProcessManager {
    override suspend fun startServer(config: LocalMCPServer): Either<StartServerError, ProcessStatus> {
        TODO("Not yet implemented")
    }

    override suspend fun stopServer(serverId: Long): Either<StopServerError, Unit> {
        TODO("Not yet implemented")
    }

    override suspend fun getServerStatus(serverId: Long): ProcessStatus {
        TODO("Not yet implemented")
    }

    override suspend fun restartServer(config: LocalMCPServer): Either<RestartServerError, ProcessStatus> {
        TODO("Not yet implemented")
    }

    override suspend fun stopAllServers(): Int {
        TODO("Not yet implemented")
    }

    override fun getProcessInputStream(serverId: Long): Source? {
        TODO("Not yet implemented")
    }

    override fun getProcessOutputStream(serverId: Long): Sink? {
        TODO("Not yet implemented")
    }

    override fun getProcessErrorStream(serverId: Long): Source? {
        TODO("Not yet implemented")
    }

    override fun close() {
        TODO("Not yet implemented")
    }
}

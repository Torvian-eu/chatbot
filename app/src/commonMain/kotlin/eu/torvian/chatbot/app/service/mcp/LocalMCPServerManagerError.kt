package eu.torvian.chatbot.app.service.mcp

import eu.torvian.chatbot.app.repository.RepositoryError

/**
 * Common interface for all LocalMCPServerManager errors.
 *
 * Provides standard properties for logging and debugging.
 */
sealed interface LocalMCPServerManagerError {
    /**
     * Comprehensive error message suitable for logging and debugging.
     */
    val message: String

    /**
     * Optional underlying cause (technical exception or domain error).
     */
    val cause: Any?
}

/**
 * Errors that can occur when testing connection to an MCP server.
 *
 * Used by: testConnection()
 */
sealed class TestConnectionError : LocalMCPServerManagerError {
    /**
     * Failed to execute server-owned runtime-control connection testing.
     */
    data class RuntimeControlFailed(
        val serverId: Long,
        val repositoryError: RepositoryError
    ) : TestConnectionError() {
        override val message: String =
            "Failed to test MCP server connection through server runtime control (ID: $serverId): ${repositoryError.message}"
        override val cause: Any = repositoryError
    }

    /**
     * Failed to execute a draft worker-scoped runtime-control connection test.
     */
    data class DraftRuntimeControlFailed(
        val workerId: Long,
        val repositoryError: RepositoryError
    ) : TestConnectionError() {
        override val message: String =
            "Failed to test draft MCP server connection through server runtime control (worker ID: $workerId): ${repositoryError.message}"
        override val cause: Any = repositoryError
    }
}

sealed class CreateServerError : LocalMCPServerManagerError {
    /**
     * Failed to persist the server configuration to repository.
     */
    data class ServerPersistenceFailed(
        val repositoryError: RepositoryError
    ) : CreateServerError() {
        override val message: String =
            "Failed to persist MCP server configuration: ${repositoryError.message}"
        override val cause: Any = repositoryError
    }
}

/**
 * Errors that can occur when refreshing tools from an MCP server.
 *
 * Used by: refreshTools()
 */
sealed class RefreshToolsError : LocalMCPServerManagerError {
    /**
     * Failed to persist tool changes to repository.
     */
    data class RefreshPersistFailed(
        val serverId: Long,
        val repositoryError: RepositoryError
    ) : RefreshToolsError() {
        override val message: String =
            "Failed to persist tool changes for MCP server (ID: $serverId): ${repositoryError.message}"
        override val cause: Any = repositoryError
    }
}

sealed class ManageStartServerError : LocalMCPServerManagerError {
    /**
     * Failed to execute server-owned runtime-control start operation.
     */
    data class RuntimeControlFailed(
        val serverId: Long,
        val repositoryError: RepositoryError
    ) : ManageStartServerError() {
        override val message: String =
            "Failed to start MCP server through server runtime control (ID: $serverId): ${repositoryError.message}"
        override val cause: Any = repositoryError
    }
}

sealed class ManageStopServerError : LocalMCPServerManagerError {
    /**
     * Failed to execute server-owned runtime-control stop operation.
     */
    data class RuntimeControlFailed(
        val serverId: Long,
        val repositoryError: RepositoryError
    ) : ManageStopServerError() {
        override val message: String =
            "Failed to stop MCP server through server runtime control (ID: $serverId): ${repositoryError.message}"
        override val cause: Any = repositoryError
    }
}

/**
 * Errors that can occur when deleting an MCP server.
 *
 * Used by: deleteServer()
 */
sealed class DeleteServerError : LocalMCPServerManagerError {
    /**
     * Failed to delete server configuration from repository.
     * Note: Server-side deletion automatically handles associated tool deletion.
     */
    data class ServerDeletionFailed(
        val serverId: Long,
        val repositoryError: RepositoryError
    ) : DeleteServerError() {
        override val message: String =
            "Failed to delete MCP server configuration (ID: $serverId): ${repositoryError.message}"
        override val cause: Any = repositoryError
    }
}

/**
 * Errors that can occur when updating an MCP server.
 *
 * Used by: updateServer()
 */
sealed class UpdateServerError : LocalMCPServerManagerError {
    /**
     * Failed to update server configuration in repository.
     */
    data class ServerUpdateFailed(
        val serverId: Long,
        val repositoryError: RepositoryError
    ) : UpdateServerError() {
        override val message: String =
            "Failed to update MCP server configuration (ID: $serverId): ${repositoryError.message}"
        override val cause: Any = repositoryError
    }
}

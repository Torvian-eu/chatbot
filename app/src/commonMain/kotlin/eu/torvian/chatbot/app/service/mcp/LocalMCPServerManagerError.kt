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
     * Failed to retrieve server configuration from repository.
     */
    data class ConfigNotFound(
        val serverId: Long,
        val repositoryError: RepositoryError
    ) : TestConnectionError() {
        override val message: String =
            "Failed to retrieve MCP server configuration (ID: $serverId): ${repositoryError.message}"
        override val cause: Any = repositoryError
    }

    /**
     * Failed to start and connect to the MCP server.
     */
    data class ConnectionFailed(
        val serverId: Long,
        val startError: StartAndConnectError
    ) : TestConnectionError() {
        override val message: String =
            "Failed to connect to MCP server (ID: $serverId): ${startError.message}"
        override val cause: Any = startError
    }

    /**
     * Failed to discover tools from the MCP server.
     */
    data class DiscoveryFailed(
        val serverId: Long,
        val discoverError: DiscoverToolsError
    ) : TestConnectionError() {
        override val message: String =
            "Failed to discover tools from MCP server (ID: $serverId): ${discoverError.message}"
        override val cause: Any = discoverError
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
     * Failed to retrieve server configuration from repository.
     */
    data class ConfigNotFound(
        val serverId: Long,
        val repositoryError: RepositoryError
    ) : RefreshToolsError() {
        override val message: String =
            "Failed to retrieve MCP server configuration (ID: $serverId): ${repositoryError.message}"
        override val cause: Any = repositoryError
    }

    /**
     * Failed to connect to the MCP server.
     */
    data class ConnectionFailed(
        val serverId: Long,
        val startError: StartAndConnectError
    ) : RefreshToolsError() {
        override val message: String =
            "Failed to connect to MCP server (ID: $serverId): ${startError.message}"
        override val cause: Any = startError
    }

    /**
     * Failed to discover current tools via MCP client.
     */
    data class DiscoveryFailed(
        val serverId: Long,
        val discoverError: DiscoverToolsError
    ) : RefreshToolsError() {
        override val message: String =
            "Failed to discover current tools from MCP server (ID: $serverId): ${discoverError.message}"
        override val cause: Any = discoverError
    }

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
     * Failed to retrieve server configuration from repository.
     */
    data class ConfigNotFound(
        val serverId: Long,
        val repositoryError: RepositoryError
    ) : ManageStartServerError() {
        override val message: String =
            "Failed to retrieve MCP server configuration (ID: $serverId): ${repositoryError.message}"
        override val cause: Any = repositoryError
    }

    /**
     * Failed to start and connect to the MCP server.
     */
    data class StartFailed(
        val serverId: Long,
        val startError: StartAndConnectError
    ) : ManageStartServerError() {
        override val message: String =
            "Failed to start MCP server (ID: $serverId): ${startError.message}"
        override val cause: Any = startError
    }
}

sealed class ManageStopServerError : LocalMCPServerManagerError {
    /**
     * Failed to stop the MCP server.
     */
    data class StopFailed(
        val serverId: Long,
        val stopError: MCPStopServerError
    ) : ManageStopServerError() {
        override val message: String =
            "Failed to stop MCP server (ID: $serverId): ${stopError.message}"
        override val cause: Any = stopError
    }
}

/**
 * Errors that can occur when deleting an MCP server.
 *
 * Used by: deleteServer()
 */
sealed class DeleteServerError : LocalMCPServerManagerError {
    /**
     * Failed to stop the MCP server before deletion.
     */
    data class StopFailed(
        val serverId: Long,
        val stopError: MCPStopServerError
    ) : DeleteServerError() {
        override val message: String =
            "Failed to stop MCP server before deletion (ID: $serverId): ${stopError.message}"
        override val cause: Any = stopError
    }

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

sealed class ManageCallToolError : LocalMCPServerManagerError {
    /**
     * Failed to retrieve server configuration from repository.
     */
    data class ConfigNotFound(
        val serverId: Long,
        val repositoryError: RepositoryError
    ) : ManageCallToolError() {
        override val message: String =
            "Failed to retrieve MCP server configuration (ID: $serverId): ${repositoryError.message}"
        override val cause: Any = repositoryError
    }

    /**
     * Failed to start and connect to the MCP server.
     */
    data class StartFailed(
        val serverId: Long,
        val startError: StartAndConnectError
    ) : ManageCallToolError() {
        override val message: String =
            "Failed to start MCP server (ID: $serverId): ${startError.message}"
        override val cause: Any = startError
    }

    /**
     * Failed to call the tool on the MCP server.
     */
    data class CallFailed(
        val serverId: Long,
        val callError: CallToolError
    ) : ManageCallToolError() {
        override val message: String =
            "Failed to call tool on MCP server (ID: $serverId): ${callError.message}"
        override val cause: Any = callError
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

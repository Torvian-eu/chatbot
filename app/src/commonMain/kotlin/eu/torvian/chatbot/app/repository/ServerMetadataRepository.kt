package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.common.models.api.metadata.ServerInfoResponse
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository for managing server-side metadata and versioning information.
 */
interface ServerMetadataRepository {
    /**
     * Observable state of the server information.
     * Initialized to null and updated via [refreshMetadata].
     */
    val serverInfo: StateFlow<ServerInfoResponse?>

    /**
     * Refreshes the server metadata by calling the API and updating [serverInfo].
     *
     * @return Either an [ApiResourceError] on failure, or [Unit] on success.
     */
    suspend fun refreshMetadata(): Either<ApiResourceError, Unit>
}


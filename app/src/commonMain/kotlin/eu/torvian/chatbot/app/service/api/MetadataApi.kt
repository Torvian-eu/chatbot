package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.metadata.ServerInfoResponse

/**
 * API service for fetching server metadata.
 */
interface MetadataApi {
    /**
     * Fetches server information from the public metadata endpoint.
     *
     * @return Either an [ApiResourceError] or the [ServerInfoResponse].
     */
    suspend fun getServerInfo(): Either<ApiResourceError, ServerInfoResponse>
}


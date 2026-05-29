package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.MetadataApi
import eu.torvian.chatbot.common.api.resources.ServerInfo
import eu.torvian.chatbot.common.models.api.metadata.ServerInfoResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*

/**
 * Ktor implementation of [MetadataApi].
 *
 * @property client The Ktor HttpClient to use for making requests.
 */
class KtorMetadataApiClient(
    client: HttpClient
) : BaseApiResourceClient(client), MetadataApi {

    override suspend fun getServerInfo(): Either<ApiResourceError, ServerInfoResponse> = safeApiCall {
        client.get(ServerInfo()).body()
    }
}


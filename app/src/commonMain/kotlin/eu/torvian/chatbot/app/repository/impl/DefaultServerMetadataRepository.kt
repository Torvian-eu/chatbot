package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import eu.torvian.chatbot.app.repository.ServerMetadataRepository
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.MetadataApi
import eu.torvian.chatbot.app.utils.misc.KmpLogger
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.api.metadata.ServerInfoResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Default implementation of [ServerMetadataRepository].
 *
 * @property metadataApi The API service for fetching metadata.
 */
class DefaultServerMetadataRepository(
    private val metadataApi: MetadataApi
) : ServerMetadataRepository {

    companion object {
        private val logger: KmpLogger = kmpLogger<DefaultServerMetadataRepository>()
    }

    private val _serverInfo = MutableStateFlow<ServerInfoResponse?>(null)
    override val serverInfo: StateFlow<ServerInfoResponse?> = _serverInfo.asStateFlow()

    override suspend fun refreshMetadata(): Either<ApiResourceError, Unit> {
        return metadataApi.getServerInfo().map { info ->
            logger.info("Server metadata refreshed: ${info.appName} v${info.version}")
            _serverInfo.value = info
        }
    }
}


package eu.torvian.chatbot.common.models.api.worker.protocol.codec

import kotlinx.serialization.json.Json

/**
 * Shared JSON configuration for worker protocol messages.
 *
 * The settings intentionally favor compatibility and forward evolution:
 * - unknown fields are ignored
 * - default values are encoded so protocol evolution stays explicit
 * - explicit nulls are omitted for more compact payloads
 *
 * @property json Serializer configuration used by worker protocol encoding and decoding.
 */
object WorkerProtocolJson {
    /**
     * JSON instance for worker protocol envelope and payload conversion.
     *
     * `ignoreUnknownKeys` allows older binaries to keep working when newer peers
     * add optional fields to protocol payloads.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
}
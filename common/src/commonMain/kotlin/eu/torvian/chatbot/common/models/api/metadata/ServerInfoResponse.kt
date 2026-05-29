package eu.torvian.chatbot.common.models.api.metadata

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Response DTO for public server info endpoint.
 *
 * @property appName Human-readable application name
 * @property version Build/version string (from VersionInfo)
 * @property startTime Server start time as an ISO instant
 */
@Serializable
data class ServerInfoResponse(
    val appName: String,
    val version: String,
    val startTime: Instant
)


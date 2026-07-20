package eu.torvian.chatbot.common.models.api.worker

import kotlinx.serialization.Serializable

/**
 * Request body for updating a worker's metadata.
 *
 * Note: workerUid and certificateFingerprint are immutable and cannot be changed.
 *
 * @property displayName New display name for the worker.
 * @property allowedScopes Updated list of allowed scopes for the worker.
 * @property toolNamePrefix Optional prefix applied to the public names of the worker's built-in tools.
 *   Must match `^[a-zA-Z0-9_-]+$` (letters, digits, underscores, dashes); a blank value clears the prefix.
 */
@Serializable
data class UpdateWorkerRequest(
    val displayName: String,
    val allowedScopes: List<String> = emptyList(),
    val toolNamePrefix: String? = null
)

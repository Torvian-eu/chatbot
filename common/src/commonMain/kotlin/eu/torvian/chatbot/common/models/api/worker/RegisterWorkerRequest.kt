package eu.torvian.chatbot.common.models.api.worker

import kotlinx.serialization.Serializable

/**
 * Request body for worker registration.
 *
 * @property workerUid Generated worker UID created during worker setup.
 * @property displayName Friendly worker label shown in management UIs.
 * @property certificatePem PEM-encoded public certificate used for worker identity.
 * @property allowedScopes Requested worker scopes to be stored with the worker record.
 */
@Serializable
data class RegisterWorkerRequest(
    val workerUid: String,
    val displayName: String,
    val certificatePem: String,
    val allowedScopes: List<String> = emptyList()
)


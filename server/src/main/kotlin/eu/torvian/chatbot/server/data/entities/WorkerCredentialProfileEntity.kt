package eu.torvian.chatbot.server.data.entities

import eu.torvian.chatbot.common.models.api.credential.CredentialChannel
import kotlin.time.Instant

/**
 * Persisted credential profile row.
 *
 * @property id Row identifier.
 * @property workerId Worker the profile is bound to.
 * @property name Operator-facing name, unique per worker.
 * @property description Optional explanation shown to the model.
 * @property secretRef Reference of the secret that supplies the value.
 * @property command Executable the profile starts.
 * @property args Argument list passed to [command] verbatim.
 * @property channel Delivery channel.
 * @property envName Variable receiving the value or the file path.
 * @property fileName Single path component of the secret file.
 * @property askpassUsername Username the askpass script answers with.
 * @property environmentVariables Non-secret environment entries applied to the profile's process.
 * @property askpassVariables Names that receive the askpass script path.
 * @property isEnabled Whether the profile may be attached.
 * @property createdAt Creation timestamp.
 * @property updatedAt Last update timestamp.
 */
data class WorkerCredentialProfileEntity(
    val id: Long,
    val workerId: Long,
    val name: String,
    val description: String?,
    val secretRef: String,
    val command: String,
    val args: List<String>,
    val channel: CredentialChannel,
    val envName: String?,
    val fileName: String?,
    val askpassUsername: String?,
    val environmentVariables: Map<String, String>,
    val askpassVariables: List<String>,
    val isEnabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * Fields needed to insert a credential profile.
 *
 * The signature is written separately, keyed by the returned profile id.
 *
 * @property workerId Worker the profile is bound to.
 * @property name Operator-facing name.
 * @property description Optional explanation shown to the model.
 * @property secretRef Reference of the secret that supplies the value.
 * @property command Executable the profile starts.
 * @property args Argument list passed to [command] verbatim.
 * @property channel Delivery channel.
 * @property envName Variable receiving the value or the file path.
 * @property fileName Single path component of the secret file.
 * @property askpassUsername Username the askpass script answers with.
 * @property environmentVariables Non-secret environment entries.
 * @property askpassVariables Names that receive the askpass script path.
 * @property isEnabled Whether the profile may be attached.
 */
data class CreateWorkerCredentialProfileEntity(
    val workerId: Long,
    val name: String,
    val description: String?,
    val secretRef: String,
    val command: String,
    val args: List<String>,
    val channel: CredentialChannel,
    val envName: String?,
    val fileName: String?,
    val askpassUsername: String?,
    val environmentVariables: Map<String, String>,
    val askpassVariables: List<String>,
    val isEnabled: Boolean,
)

/**
 * Replacement values for an existing credential profile.
 *
 * Everything the operator authored is replaced, including the worker binding, because the signed body
 * carries it and a profile may move between workers.
 *
 * @property workerId Worker the profile is bound to after the update.
 * @property name Operator-facing name.
 * @property description Optional explanation shown to the model.
 * @property secretRef Reference of the secret that supplies the value.
 * @property command Executable the profile starts.
 * @property args Argument list passed to [command] verbatim.
 * @property channel Delivery channel.
 * @property envName Variable receiving the value or the file path.
 * @property fileName Single path component of the secret file.
 * @property askpassUsername Username the askpass script answers with.
 * @property environmentVariables Non-secret environment entries.
 * @property askpassVariables Names that receive the askpass script path.
 * @property isEnabled Whether the profile may be attached.
 */
data class UpdateWorkerCredentialProfileEntity(
    val workerId: Long,
    val name: String,
    val description: String?,
    val secretRef: String,
    val command: String,
    val args: List<String>,
    val channel: CredentialChannel,
    val envName: String?,
    val fileName: String?,
    val askpassUsername: String?,
    val environmentVariables: Map<String, String>,
    val askpassVariables: List<String>,
    val isEnabled: Boolean,
)

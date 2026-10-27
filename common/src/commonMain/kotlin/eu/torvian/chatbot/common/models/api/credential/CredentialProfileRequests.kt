package eu.torvian.chatbot.common.models.api.credential

import kotlinx.serialization.Serializable

/**
 * Field set of a credential profile that the operator authors and the app signs.
 *
 * Both request bodies carry it, and both are persisted verbatim as the payload of a device signature,
 * so this is the object that must stay in lockstep with the worker's signature verification: a field
 * present here but missing from the comparison there is signed yet unverified.
 *
 * @property workerId Worker the profile is bound to.
 * @property name Operator-facing name, and the only profile field the model may name.
 * @property description Optional explanation shown to the model in the profile advertisement.
 * @property secretRef Reference of the encrypted secret on the same worker.
 * @property command Executable the profile starts.
 * @property args Argument list passed to [command] verbatim.
 * @property channel How the secret reaches the started process.
 * @property envName Variable that receives the value or the file path; absent for askpass.
 * @property fileName Single path component of the secret file; file channel only.
 * @property askpassUsername Username the askpass script answers with; askpass channel only.
 * @property isEnabled Whether the profile may be attached.
 * @property environmentVariables Supplementary non-secret environment variables for the profile's
 *   process.
 * @property askpassVariables Names that receive the askpass script path; askpass channel only.
 * @property agentRoleIds Agent roles allowed to attach the profile. The relation is many-to-many and
 *   unordered; an empty set makes the profile attachable by no role at all.
 */
@Serializable
data class CredentialProfileFields(
    val workerId: Long,
    val name: String,
    val description: String? = null,
    val secretRef: String,
    val command: String,
    val args: List<String> = emptyList(),
    val channel: CredentialChannel,
    val envName: String? = null,
    val fileName: String? = null,
    val askpassUsername: String? = null,
    val isEnabled: Boolean = true,
    val environmentVariables: Map<String, String> = emptyMap(),
    val askpassVariables: List<String> = emptyList(),
    val agentRoleIds: Set<Long> = emptySet(),
)

/**
 * Body the app signs and the server persists when a credential profile is created.
 *
 * @property workerId Worker the profile is bound to.
 * @property name Operator-facing name.
 * @property description Optional explanation shown to the model.
 * @property secretRef Reference of the encrypted secret on the same worker.
 * @property command Executable the profile starts.
 * @property args Argument list passed to [command] verbatim.
 * @property channel How the secret reaches the started process.
 * @property envName Variable that receives the value or the file path.
 * @property fileName Single path component of the secret file.
 * @property askpassUsername Username the askpass script answers with.
 * @property isEnabled Whether the profile may be attached.
 * @property environmentVariables Supplementary non-secret environment variables.
 * @property askpassVariables Names that receive the askpass script path.
 * @property agentRoleIds Agent roles allowed to attach the profile; signing them makes the link set
 *   part of the authority the worker verifies. An empty set is a draft nobody may attach.
 */
@Serializable
data class CreateCredentialProfileRequest(
    val workerId: Long,
    val name: String,
    val description: String? = null,
    val secretRef: String,
    val command: String,
    val args: List<String> = emptyList(),
    val channel: CredentialChannel,
    val envName: String? = null,
    val fileName: String? = null,
    val askpassUsername: String? = null,
    val isEnabled: Boolean = true,
    val environmentVariables: Map<String, String> = emptyMap(),
    val askpassVariables: List<String> = emptyList(),
    val agentRoleIds: Set<Long> = emptySet(),
) {
    /**
     * @return The signed field set this request carries.
     */
    fun toFields(): CredentialProfileFields = CredentialProfileFields(
        workerId = workerId,
        name = name,
        description = description,
        secretRef = secretRef,
        command = command,
        args = args,
        channel = channel,
        envName = envName,
        fileName = fileName,
        askpassUsername = askpassUsername,
        isEnabled = isEnabled,
        environmentVariables = environmentVariables,
        askpassVariables = askpassVariables,
        agentRoleIds = agentRoleIds,
    )
}

/**
 * Body the app signs and the server persists when a credential profile is updated.
 *
 * The shape mirrors [CreateCredentialProfileRequest] so the worker can verify either payload with one
 * comparison.
 *
 * @property workerId Worker the profile is bound to; a different value moves the profile.
 * @property name Operator-facing name.
 * @property description Optional explanation shown to the model.
 * @property secretRef Reference of the encrypted secret on the same worker.
 * @property command Executable the profile starts.
 * @property args Argument list passed to [command] verbatim.
 * @property channel How the secret reaches the started process.
 * @property envName Variable that receives the value or the file path.
 * @property fileName Single path component of the secret file.
 * @property askpassUsername Username the askpass script answers with.
 * @property isEnabled Whether the profile may be attached.
 * @property environmentVariables Supplementary non-secret environment variables.
 * @property askpassVariables Names that receive the askpass script path.
 * @property agentRoleIds Agent roles allowed to attach the profile, replacing the previous set.
 */
@Serializable
data class UpdateCredentialProfileRequest(
    val workerId: Long,
    val name: String,
    val description: String? = null,
    val secretRef: String,
    val command: String,
    val args: List<String> = emptyList(),
    val channel: CredentialChannel,
    val envName: String? = null,
    val fileName: String? = null,
    val askpassUsername: String? = null,
    val isEnabled: Boolean = true,
    val environmentVariables: Map<String, String> = emptyMap(),
    val askpassVariables: List<String> = emptyList(),
    val agentRoleIds: Set<Long> = emptySet(),
) {
    /**
     * @return The signed field set this request carries.
     */
    fun toFields(): CredentialProfileFields = CredentialProfileFields(
        workerId = workerId,
        name = name,
        description = description,
        secretRef = secretRef,
        command = command,
        args = args,
        channel = channel,
        envName = envName,
        fileName = fileName,
        askpassUsername = askpassUsername,
        isEnabled = isEnabled,
        environmentVariables = environmentVariables,
        askpassVariables = askpassVariables,
        agentRoleIds = agentRoleIds,
    )
}

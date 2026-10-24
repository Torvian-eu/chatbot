package eu.torvian.chatbot.common.models.api.credential

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * One credential profile: a fixed command bound to one secret reference and one delivery channel.
 *
 * The same type serves the operator API and the worker bootstrap, because a profile carries no secret
 * value at all. The value lives in a separate encrypted secret that [secretRef] names, and only the
 * worker can decrypt it.
 *
 * @property id Server-side row identifier, used by delete notifications and updates.
 * @property workerId Worker this profile is bound to; a profile is only usable from a session on it.
 * @property name Operator-facing name, and the only profile field the model may name.
 * @property description Optional explanation shown to the model in the profile advertisement.
 * @property secretRef Reference of the encrypted secret on the same worker that supplies the value.
 * @property command Executable the profile starts; fixed by the operator, never by the model.
 * @property args Argument list passed to [command] verbatim.
 * @property channel How the secret reaches the started process.
 * @property envName Environment variable that receives the value ([CredentialChannel.ENV]) or the
 *   file path ([CredentialChannel.FILE]); must be absent for [CredentialChannel.ASKPASS].
 * @property fileName Single path component of the secret file for [CredentialChannel.FILE].
 * @property askpassUsername Username the askpass script answers with; askpass-only.
 * @property isEnabled Whether the profile may be attached; a disabled profile stays stored and listed.
 * @property environmentVariables Supplementary non-secret environment variables applied to the
 *   profile's process only. Values are not secret and are stored and displayed in plaintext.
 * @property askpassVariables Names that receive the askpass script path; askpass-only and required
 *   there. Naming them from the profile keeps tool-specific variable names out of worker code.
 * @property updatedAt Last modification timestamp.
 */
@Serializable
data class CredentialProfileDto(
    val id: Long,
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
    val updatedAt: Instant,
)

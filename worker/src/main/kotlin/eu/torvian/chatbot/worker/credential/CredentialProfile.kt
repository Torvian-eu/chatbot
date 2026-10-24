package eu.torvian.chatbot.worker.credential

import eu.torvian.chatbot.common.models.api.credential.CredentialChannel
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileFields
import eu.torvian.chatbot.common.models.api.credential.WorkerCredentialSecretDto

/**
 * One validated credential profile held in the worker's memory-only cache.
 *
 * The fixed command and argument list belong to the operator: the model names a profile only, so this
 * type is the complete authority on what an `attach` will run.
 *
 * @property id Server-side row identifier, used by delete notifications.
 * @property workerId Worker the profile is bound to.
 * @property name Operator-facing name and the only profile field the model may name.
 * @property description Optional explanation shown to the model in the profile advertisement.
 * @property secretRef Reference of the encrypted secret on the same worker.
 * @property command Executable the profile starts.
 * @property args Argument list passed to [command] verbatim.
 * @property channel How the secret reaches the started process.
 * @property envName Variable that receives the value or the file path.
 * @property fileName Single path component of the secret file.
 * @property askpassUsername Username the askpass script answers with.
 * @property isEnabled Whether the profile may be attached.
 * @property environmentVariables Supplementary non-secret environment entries for the profile process.
 * @property askpassVariables Names that receive the askpass script path.
 */
data class CredentialProfile(
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
    val isEnabled: Boolean,
    val environmentVariables: Map<String, String>,
    val askpassVariables: List<String>,
) {
    /**
     * @return The shared field set of this profile, used for re-validation on the worker.
     */
    fun toFields(): CredentialProfileFields =
        CredentialProfileFields(
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
        )
}

/**
 * One encrypted credential secret held in the worker's memory-only cache.
 *
 * Nothing is decrypted here: the value is unwrapped only inside `attach`.
 *
 * @property id Server-side row identifier, used by delete notifications.
 * @property workerId Worker this secret is bound to.
 * @property reference Reference a profile uses to resolve the secret.
 * @property encryptedSecret Ciphertext of the value, Base64-encoded.
 * @property encryptedDek Data key wrapped with the worker certificate public key, Base64-encoded.
 * @property algorithm Cipher identifier of the outer key wrap.
 * @property workerCertificateFingerprint Fingerprint of the certificate whose public key wrapped the
 *   data key.
 */
data class EncryptedWorkerSecret(
    val id: Long,
    val workerId: Long,
    val reference: String,
    val encryptedSecret: String,
    val encryptedDek: String,
    val algorithm: String,
    val workerCertificateFingerprint: String,
)

/**
 * Converts a relayed profile DTO into the cached domain value.
 *
 * @receiver Profile DTO as persisted on the server.
 * @return Profile value without any secret material.
 */
fun CredentialProfileDto.toCredentialProfile(): CredentialProfile = CredentialProfile(
    id = id,
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
)

/**
 * Converts a relayed secret DTO into the cached domain value.
 *
 * @receiver Encrypted secret DTO.
 * @return Secret value carrying ciphertext only.
 */
fun WorkerCredentialSecretDto.toEncryptedWorkerSecret(): EncryptedWorkerSecret = EncryptedWorkerSecret(
    id = id,
    workerId = workerId,
    reference = reference,
    encryptedSecret = encryptedSecret,
    encryptedDek = encryptedDek,
    algorithm = algorithm,
    workerCertificateFingerprint = workerCertificateFingerprint,
)

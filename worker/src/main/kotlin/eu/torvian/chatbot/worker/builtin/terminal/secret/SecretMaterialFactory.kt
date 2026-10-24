package eu.torvian.chatbot.worker.builtin.terminal.secret

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.models.api.credential.CredentialChannel
import eu.torvian.chatbot.worker.credential.CredentialProfile
import eu.torvian.chatbot.worker.credential.SecretMaterialError
import eu.torvian.chatbot.worker.credential.SecretTreeFactory
import eu.torvian.chatbot.worker.credential.SecretValue

/**
 * Builds the channel-specific material for one attach.
 *
 * The channel is the profile's choice, so the dispatch lives here rather than in the session: `attach`
 * decrypts once, hands the value over, and closes it as soon as the material exists. A channel that
 * cannot deliver the profile's configuration fails before anything is spawned, so a session is never
 * left with a running process whose secret is missing.
 *
 * @property trees Factory that creates and removes per-attach trees.
 * @property askpassScriptPath Absolute path of the askpass helper `askpass` profiles are handed.
 */
internal class SecretMaterialFactory(
    private val trees: SecretTreeFactory,
    private val askpassScriptPath: String,
) {
    /**
     * Builds the material [profile] describes.
     *
     * @param profile Profile being attached; only its non-secret fields are read.
     * @param value Decrypted secret of the profile.
     * @param deadline Monotonic deadline the preparation, including any helper call, must respect.
     * @return Either a typed preparation failure or the material the caller closes on every exit path.
     */
    fun create(
        profile: CredentialProfile,
        value: SecretValue,
        deadline: Long,
    ): Either<SecretMaterialError, SecretMaterial> = either {
        when (profile.channel) {
            CredentialChannel.ASKPASS -> AskpassSecretMaterial.create(
                trees = trees,
                askpassScriptPath = askpassScriptPath,
                askpassVariables = profile.askpassVariables,
                askpassUsername = profile.askpassUsername,
                value = value,
                deadline = deadline,
            ).bind()

            CredentialChannel.ENV -> EnvSecretMaterial.create(
                envName = profile.envName,
                value = value,
            ).bind()

            CredentialChannel.FILE -> FileSecretMaterial.create(
                trees = trees,
                fileName = profile.fileName,
                envName = profile.envName,
                value = value,
                deadline = deadline,
            ).bind()
        }
    }
}

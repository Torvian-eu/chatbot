package eu.torvian.chatbot.worker.builtin.terminal.secret

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.worker.credential.SecretMaterialError
import eu.torvian.chatbot.worker.credential.SecretValue

/**
 * Material for the `env` channel: exactly one environment entry carrying the value.
 *
 * No file is created. The JVM holds the value as a `String` until the child is spawned, and that copy
 * cannot be wiped; only the character copy this material owns is zeroed on close.
 *
 * @property chars Private copy of the value, zeroed when the material is closed.
 * @property environment Environment the profile's command receives.
 */
internal class EnvSecretMaterial private constructor(
    private val chars: CharArray,
    override val environment: Map<String, String>,
) : SecretMaterial {

    /** Zeroes the private copy; idempotent. */
    override fun close(deadline: Long) {
        chars.fill('\u0000')
    }

    internal companion object {
        /**
         * Builds the single-variable material for an `env` profile.
         *
         * @param envName Variable that receives the value.
         * @param value Decrypted secret to deliver.
         * @return Either a typed preparation failure or the material to close after the profile exits.
         */
        fun create(envName: String?, value: SecretValue): Either<SecretMaterialError, SecretMaterial> = either {
            ensure(!envName.isNullOrBlank()) {
                SecretMaterialError.PreparationFailed("env profiles must name the variable that receives the value")
            }

            val chars = value.copyChars()
            EnvSecretMaterial(chars = chars, environment = mapOf(envName to chars.concatToString()))
        }
    }
}

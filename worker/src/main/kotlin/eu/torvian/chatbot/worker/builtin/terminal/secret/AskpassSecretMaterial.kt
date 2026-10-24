package eu.torvian.chatbot.worker.builtin.terminal.secret

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.worker.credential.SecretMaterialError
import eu.torvian.chatbot.worker.credential.SecretTree
import eu.torvian.chatbot.worker.credential.SecretTreeFactory
import eu.torvian.chatbot.worker.credential.SecretValue
import java.io.File

/**
 * Material for the `askpass` channel: one credential-only file plus the variables that reach it.
 *
 * The worker sets no tool-specific variable. Each name in the profile's `askpassVariables` receives the
 * askpass script path, and the script itself reads the two `TORVIAN_ASKPASS_*` entries. A profile that
 * omits the tool's own knobs (for example OpenSSH's `SSH_ASKPASS_REQUIRE=force`) attaches successfully
 * and then waits on a terminal prompt; that is profile data, not worker behaviour.
 *
 * @property trees Factory that owns the per-attach tree, used again to remove it.
 * @property tree Tree holding the value.
 * @property environment Environment the profile's command receives.
 */
internal class AskpassSecretMaterial private constructor(
    private val trees: SecretTreeFactory,
    private val tree: SecretTree,
    override val environment: Map<String, String>,
) : SecretMaterial {

    /** Removes the secret tree; idempotent. */
    override fun close(deadline: Long) {
        trees.clear(tree.directoryPath, deadline)
    }

    internal companion object {
        /** File name the secret is written to, which the askpass script reads. */
        const val FILE_NAME = "askpass"

        /**
         * Creates the per-attach file and the environment that points at it.
         *
         * @param trees Factory that creates and removes the tree.
         * @param askpassScriptPath Absolute path of the askpass helper the profile's variables receive.
         * @param askpassVariables Variable names that must receive [askpassScriptPath].
         * @param askpassUsername Username answer for prompts that ask for one, or `null` to omit it.
         * @param value Decrypted secret to write.
         * @param deadline Monotonic deadline the helper call must respect.
         * @return Either a typed preparation failure or the material to close after the profile exits.
         */
        fun create(
            trees: SecretTreeFactory,
            askpassScriptPath: String,
            askpassVariables: List<String>,
            askpassUsername: String?,
            value: SecretValue,
            deadline: Long,
        ): Either<SecretMaterialError, SecretMaterial> = either {
            ensure(askpassVariables.isNotEmpty()) {
                SecretMaterialError.PreparationFailed(
                    "askpass profiles must name at least one variable that receives the askpass script"
                )
            }
            // Re-checked here as well as at startup: the script lives outside the worker's control, so
            // one that was removed or made non-executable after the worker started would otherwise
            // surface as a profile that silently never prompts.
            val script = File(askpassScriptPath)
            ensure(!(!script.isFile || !script.canExecute())) {
                SecretMaterialError.PreparationFailed(
                    "askpass helper $askpassScriptPath does not exist or is not executable"
                )
            }

            val tree = trees.create(FILE_NAME, value, deadline).bind()

            val environment = buildMap {
                askpassVariables.forEach { name -> put(name, askpassScriptPath) }
                put(AskpassEnvironment.FILE_VARIABLE, tree.secretPath)
                askpassUsername?.let { put(AskpassEnvironment.USERNAME_VARIABLE, it) }
            }
            AskpassSecretMaterial(trees = trees, tree = tree, environment = environment)
        }
    }
}

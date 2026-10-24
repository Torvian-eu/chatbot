package eu.torvian.chatbot.worker.builtin.terminal.secret

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.worker.credential.SecretMaterialError
import eu.torvian.chatbot.worker.credential.SecretTree
import eu.torvian.chatbot.worker.credential.SecretTreeFactory
import eu.torvian.chatbot.worker.credential.SecretValue

/**
 * Material for the `file` channel: one credential-only file and a variable holding its path.
 *
 * Use this for tools that take a credentials-file path, such as a service-account document. As with the
 * askpass channel, the worker never creates or removes the file itself: the credential helper owns the
 * tree, because the worker cannot write into a directory owned by the credential account.
 *
 * @property trees Factory that owns the per-attach tree, used again to remove it.
 * @property tree Tree holding the value.
 * @property environment Environment the profile's command receives.
 */
internal class FileSecretMaterial private constructor(
    private val trees: SecretTreeFactory,
    private val tree: SecretTree,
    override val environment: Map<String, String>,
) : SecretMaterial {

    /** Removes the secret tree; idempotent. */
    override fun close(deadline: Long) {
        trees.clear(tree.directoryPath, deadline)
    }

    internal companion object {
        /**
         * Creates the per-attach file and the variable that points at it.
         *
         * @param trees Factory that creates and removes the tree.
         * @param fileName Single path component of the file to create.
         * @param envName Variable that receives the absolute path of the created file.
         * @param value Decrypted secret to write.
         * @param deadline Monotonic deadline the helper call must respect.
         * @return Either a typed preparation failure or the material to close after the profile exits.
         */
        fun create(
            trees: SecretTreeFactory,
            fileName: String?,
            envName: String?,
            value: SecretValue,
            deadline: Long,
        ): Either<SecretMaterialError, SecretMaterial> = either {
            ensure(!fileName.isNullOrBlank()) {
                SecretMaterialError.PreparationFailed("file profiles must name the file to create")
            }
            ensure(!envName.isNullOrBlank()) {
                SecretMaterialError.PreparationFailed("file profiles must name the variable that receives the path")
            }

            val tree = trees.create(fileName, value, deadline).bind()
            FileSecretMaterial(trees = trees, tree = tree, environment = mapOf(envName to tree.secretPath))
        }
    }
}

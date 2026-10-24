package eu.torvian.chatbot.worker.builtin.terminal

import eu.torvian.chatbot.worker.builtin.command.ChildEnvironmentProvider
import eu.torvian.chatbot.worker.builtin.terminal.secret.SecretMaterialFactory
import eu.torvian.chatbot.worker.credential.CredentialSecretDecryptor
import eu.torvian.chatbot.worker.credential.WorkerCredentialStore
import java.nio.file.Path

/**
 * Everything a session needs to start a credential profile target.
 *
 * The bundle is present only when the worker resolved a credential account: on a host with command
 * isolation off, or on a platform without the mechanism, it is null and `attach` reports
 * `PERMISSION_DENIED` instead of falling back to delivery under an account the model can read.
 *
 * @property store In-memory cache of the profiles and encrypted secrets assigned to this worker.
 * @property decryptor Lazy decryptor for a profile's secret.
 * @property materialFactory Builds the channel-specific secret delivery for one attach.
 * @property vaultEnvironment Environment policy for the credential account, separate from the one
 *   ordinary commands receive.
 * @property secretWorkingDirectory Working directory for profile targets, never the shared workspace.
 */
internal class CredentialAttachDependencies(
    val store: WorkerCredentialStore,
    val decryptor: CredentialSecretDecryptor,
    val materialFactory: SecretMaterialFactory,
    val vaultEnvironment: ChildEnvironmentProvider,
    val secretWorkingDirectory: Path,
)

package eu.torvian.chatbot.worker.builtin.command

import org.apache.logging.log4j.Logger

/**
 * Launcher, terminator and identity for targets that carry a credential.
 *
 * The account is separate from the one ordinary commands use, so a shell the model controls cannot
 * read a secret that a profile target has loaded. Launcher and terminator are built together from one
 * helper path, so a credential target can never be spawned through one helper and signalled through
 * another.
 *
 * @property launcher Helper-prefixing launcher that drops to the credential account.
 * @property terminator Group-signalling terminator using the same helper.
 * @property user Account the helper drops to before it execs.
 * @property home Home directory of that account, used for the child's `HOME`.
 */
class SecretCommandAccount private constructor(
    val launcher: CommandLauncher,
    val terminator: CommandTerminator,
    val user: String,
    val home: String,
) {
    internal companion object {
        /**
         * Builds the account state for one helper path and account.
         *
         * @param helperPath Absolute path of the root-owned credential helper.
         * @param user Account the helper drops to.
         * @param home Home directory of that account.
         * @param logger Logger receiving a WARN when a termination signal cannot be delivered.
         * @return The account state, whose terminator signals through [helperPath].
         */
        fun create(helperPath: String, user: String, home: String, logger: Logger): SecretCommandAccount {
            val launcher = SetuidHelperLauncher(helperPath)
            return SecretCommandAccount(
                launcher = launcher,
                terminator = HelperSignalRelay(HelperProcessGroupSignaller(launcher, logger)),
                user = user,
                home = home,
            )
        }
    }
}

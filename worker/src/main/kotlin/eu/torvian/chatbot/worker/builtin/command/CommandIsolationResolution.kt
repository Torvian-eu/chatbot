package eu.torvian.chatbot.worker.builtin.command

import org.apache.logging.log4j.Logger

/**
 * Resolved command-isolation state for the current worker process.
 *
 * This is the single source of truth for "is a privilege drop active, and as whom": the launcher,
 * the terminator and the child-environment policy are derived from it, and consumers must not
 * re-derive that state from configuration or platform checks. A worker process resolves it once at
 * startup, because the mechanism cannot change while the process lives.
 *
 * The two variants keep the state consistent by construction: a privilege-dropping launcher always
 * arrives together with the account the child becomes and with a terminator that reaches that
 * account's processes through the same helper, so neither a launcher that claims an account nor one
 * that drops without naming it can be built. The account's home directory travels with it for the
 * same reason: it is read from the account database rather than assumed from the account name.
 */
sealed interface CommandIsolationResolution {
    /** argv wrapper applied to every spawned child. */
    val launcher: CommandLauncher

    /** Strategy terminating a spawned child and its descendants. */
    val terminator: CommandTerminator

    /** OS account children run as, or `null` when no privilege drop is active. */
    val isolatedUser: String?

    /** Home directory of [isolatedUser], or `null` when no privilege drop is active. */
    val isolatedHome: String?

    /**
     * No privilege drop is active: children run as the worker account, which owns and signals them.
     */
    object None : CommandIsolationResolution {
        override val launcher: CommandLauncher = NoopLauncher()
        override val terminator: CommandTerminator = ProcessHandleCommandTerminator()
        override val isolatedUser: String? = null
        override val isolatedHome: String? = null
    }

    /**
     * Children run as [isolatedUser] through the setuid helper at [launcher]'s path.
     *
     * The launcher and the terminator always address the same helper binary: the state is built
     * from a helper path, which produces both collaborators together, so a helper-backed launcher
     * can never be paired with a strategy that reaches no isolated process or with one bound to a
     * different binary.
     *
     * @property launcher Helper-prefixing launcher; the only object that knows the helper path.
     * @property terminator Group-signalling terminator using the same helper.
     * @property isolatedUser Account the helper drops to before it execs.
     * @property isolatedHome Home directory of that account, used for the child's `HOME`.
     */
    class Isolated private constructor(
        override val launcher: SetuidHelperLauncher,
        override val terminator: CommandTerminator,
        override val isolatedUser: String,
        override val isolatedHome: String,
    ) : CommandIsolationResolution {
        internal companion object {
            /**
             * Builds the isolated state for one helper path and account.
             *
             * @param helperPath Absolute path of the root-owned helper.
             * @param user Account the helper drops to.
             * @param home Home directory of that account.
             * @param logger Logger receiving a WARN when a termination signal cannot be delivered.
             * @return The isolated state, whose terminator signals through [helperPath].
             */
            fun create(helperPath: String, user: String, home: String, logger: Logger): Isolated {
                val launcher = SetuidHelperLauncher(helperPath)
                return Isolated(
                    launcher = launcher,
                    terminator = HelperSignalRelay(HelperProcessGroupSignaller(launcher, logger)),
                    isolatedUser = user,
                    isolatedHome = home,
                )
            }
        }
    }

    companion object {
        /**
         * @return The resolution used when children run as the worker account.
         */
        fun none(): CommandIsolationResolution = None

        /**
         * Resolves a privilege drop to [user] through the helper at [helperPath].
         *
         * @param helperPath Absolute path of the root-owned helper.
         * @param user Account the helper drops to.
         * @param home Home directory of that account.
         * @param logger Logger receiving a WARN when a termination signal cannot be delivered.
         * @return The resolved isolated state.
         */
        fun isolated(
            helperPath: String,
            user: String,
            home: String,
            logger: Logger,
        ): CommandIsolationResolution = Isolated.create(helperPath, user, home, logger)
    }
}

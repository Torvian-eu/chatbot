package eu.torvian.chatbot.worker.builtin.command

import eu.torvian.chatbot.worker.config.CommandEnvironmentConfig

/**
 * Builds the complete environment for a child process spawned by a built-in tool.
 *
 * The caller clears the inherited environment before applying the result, so an implementation must
 * return a self-sufficient map rather than a set of overrides.
 */
interface ChildEnvironmentProvider {
    /**
     * @return The complete environment map for the child.
     */
    fun build(): Map<String, String>
}

/**
 * Passes the worker's own environment to children, so they behave like a plain process spawn.
 *
 * @property parent Environment to hand over verbatim; defaults to the worker's own environment.
 */
class InheritChildEnvironmentProvider(
    private val parent: Map<String, String> = System.getenv(),
) : ChildEnvironmentProvider {
    override fun build(): Map<String, String> = parent
}

/**
 * Builds the filtered environment used while a privilege drop is active.
 *
 * Filtering exists because a child under a different account must not inherit the credentials the
 * worker was started with. Account identity variables are always derived from the isolated account:
 * tools read per-account configuration from `HOME`, and the worker's own `HOME` belongs to an
 * account the child cannot write.
 *
 * Configured values are applied on top of the pass-through, so a deployment can supply a variable the
 * worker does not define, or replace one it does. They are applied before the identity variables, so
 * a configured entry cannot redirect the child away from its own account.
 *
 * @property config Environment policy resolved from worker configuration: names to forward and values
 *   to set.
 * @property isolatedUser Account children run as, used to derive identity variables.
 * @property isolatedHome Home directory of [isolatedUser], used for the child's `HOME`.
 * @property parent Worker environment the allowlisted names are read from; defaults to the
 *   worker's own environment.
 */
class AllowlistChildEnvironmentProvider(
    private val config: CommandEnvironmentConfig,
    private val isolatedUser: String,
    private val isolatedHome: String,
    private val parent: Map<String, String> = System.getenv(),
) : ChildEnvironmentProvider {
    override fun build(): Map<String, String> {
        val environment = LinkedHashMap<String, String>()
        if (config.allowPassthrough.contains(WILDCARD)) {
            environment.putAll(parent)
        } else {
            // A name the worker does not define is omitted rather than set to an empty value, so a
            // child tool can tell "unset" apart from "explicitly empty".
            config.allowPassthrough.forEach { name ->
                parent[name]?.let { value -> environment[name] = value }
            }
        }

        // Written after the pass-through so a configured value is what the child sees, including for
        // a name the worker environment also defines.
        environment.putAll(config.set)

        // Written last so a deployment listing or setting these names cannot redirect the child away
        // from its own account identity.
        environment[HOME_VARIABLE] = isolatedHome
        environment[USER_VARIABLE] = isolatedUser
        environment[LOGNAME_VARIABLE] = isolatedUser
        return environment
    }

    private companion object {
        /** Allowlist entry that forwards every variable of the worker environment. */
        const val WILDCARD = "*"

        /** Variable tools use to locate per-account configuration. */
        const val HOME_VARIABLE = "HOME"

        /** Variable holding the effective account name. */
        const val USER_VARIABLE = "USER"

        /** Variable holding the login name of the effective account. */
        const val LOGNAME_VARIABLE = "LOGNAME"
    }
}

/**
 * Chooses the environment policy for the resolved isolation state.
 *
 * The decision is derived from the resolution rather than from configuration, so "is isolation
 * active" is answered in exactly one place.
 *
 * @param config Allowlist policy to apply while a privilege drop is active.
 * @param resolution Resolved isolation state of this worker process.
 * @return The allowlist provider when a drop is active, otherwise the inherit provider.
 */
fun childEnvironmentProvider(
    config: CommandEnvironmentConfig,
    resolution: CommandIsolationResolution,
): ChildEnvironmentProvider {
    val isolatedUser = resolution.isolatedUser
    val isolatedHome = resolution.isolatedHome
    return if (isolatedUser != null && isolatedHome != null) {
        AllowlistChildEnvironmentProvider(
            config = config,
            isolatedUser = isolatedUser,
            isolatedHome = isolatedHome,
        )
    } else {
        InheritChildEnvironmentProvider()
    }
}

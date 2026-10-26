package eu.torvian.chatbot.worker.builtin.terminal.secret

/**
 * Channel-specific secret delivery state owned by one profile target.
 *
 * The environment is added to the credential child's environment; closing deletes any file the channel
 * created and zeroes the copies it holds. Implementations must be idempotent, because cleanup runs on
 * several exit paths.
 */
internal interface SecretMaterial {
    /** Environment entries to add to the credential child's environment. */
    val environment: Map<String, String>

    /**
     * Deletes files and zeroes copies; idempotent.
     *
     * Cleanup runs on exit paths that must not fail, so a removal that does not succeed is reported at
     * WARN and never thrown, and the material reports no secret content.
     *
     * @param deadline Monotonic deadline the deletion, including any helper call, must respect.
     */
    fun close(deadline: Long)
}

/**
 * Environment entries the askpass script itself reads.
 *
 * These two names are Torvian's contract with its own script rather than any tool's, which is why they
 * are the only askpass variables hardcoded in worker code. Tool-specific names come from the profile.
 */
internal object AskpassEnvironment {
    /** Variable holding the path of the per-attach secret file. */
    const val FILE_VARIABLE = "TORVIAN_ASKPASS_FILE"

    /** Variable holding the username answer, when the profile sets one. */
    const val USERNAME_VARIABLE = "TORVIAN_ASKPASS_USERNAME"
}

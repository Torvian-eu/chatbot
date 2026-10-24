package eu.torvian.chatbot.worker.config

/**
 * Worker process variables whose values must not reach an isolated child.
 *
 * Membership covers what would let a child act on the worker's own identity: the key that unwraps
 * every credential and signs challenge authentication, and the password the worker uses to
 * authenticate itself to the server.
 */
internal object SensitiveEnvironmentNames {

    /**
     * Names carrying the worker's own credential material.
     *
     * The check is by name, so a deployment that exports a secret under a name of its own invention is
     * outside what this set can protect.
     */
    val NAMES: Set<String> = setOf(
        "CHATBOT_WORKER_PRIVATE_KEY_PEM",
        "CHATBOT_WORKER_SETUP_PASSWORD",
    )

    /**
     * Reports whether [name] is one of the names an isolated child must never receive.
     *
     * @param name Candidate environment variable name.
     * @return true when [name] carries the worker's own credential material.
     */
    fun contains(name: String): Boolean = name in NAMES
}

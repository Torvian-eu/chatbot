package eu.torvian.chatbot.worker.main

/**
 * Parsed command-line options for the standalone worker process.
 *
 * @property configPathOverride Optional path to a custom configuration file, overriding the default location.
 * @property runOnce If true, the worker will execute its main task once and then exit, instead of running continuously. Useful for testing or one-off operations.
 */
data class WorkerCliOptions(
    val configPathOverride: String? = null,
    val runOnce: Boolean = false
)


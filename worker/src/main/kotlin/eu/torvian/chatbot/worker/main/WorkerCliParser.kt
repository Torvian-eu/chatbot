package eu.torvian.chatbot.worker.main

/**
 * Lightweight parser for worker command-line options.
 *
 * Unknown flags are currently ignored to preserve existing phase-3 behavior.
 */
object WorkerCliParser {
    fun parse(args: Array<String>): WorkerCliOptions {
        val configPathOverride = args.firstOrNull { it.startsWith("--config=") }?.substringAfter("--config=")
        val runOnce = args.any { it == "--once" }
        return WorkerCliOptions(
            configPathOverride = configPathOverride,
            runOnce = runOnce
        )
    }
}


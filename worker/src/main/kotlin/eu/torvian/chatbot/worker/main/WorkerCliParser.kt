package eu.torvian.chatbot.worker.main

/**
 * Lightweight parser for worker command-line options.
 *
 * Unknown flags are currently ignored to preserve existing phase-3 behavior.
 */
object WorkerCliParser {
    /**
     * Parses worker command-line arguments into a flat options object.
     *
     * Validation of incompatible combinations is intentionally handled by [WorkerMain] so tests
     * can exercise parser behavior and orchestration behavior independently.
     *
     * @param args Raw command-line arguments passed to the worker process.
     * @return Parsed worker CLI options.
     */
    fun parse(args: Array<String>): WorkerCliOptions {
        val configPathOverride = args.firstOrNull { it.startsWith("--config=") }?.substringAfter("--config=")
        val runOnce = args.any { it == "--once" }
        val setup = args.any { it == "--setup" }
        val serverUrlOverride = args.firstOrNull { it.startsWith("--server-url=") }?.substringAfter("--server-url=")
        val addTrustedSigner = args.any { it == "--add-trusted-signer" }
        val signerId = args.firstOrNull { it.startsWith("--signer-id=") }?.substringAfter("--signer-id=")
        val publicKeyBase64 = args.firstOrNull { it.startsWith("--public-key-base64=") }
            ?.substringAfter("--public-key-base64=")
        val permissionsCsv = args.firstOrNull { it.startsWith("--permissions=") }?.substringAfter("--permissions=")
        return WorkerCliOptions(
            configPathOverride = configPathOverride,
            runOnce = runOnce,
            setup = setup,
            serverUrlOverride = serverUrlOverride,
            addTrustedSigner = addTrustedSigner,
            signerId = signerId,
            publicKeyBase64 = publicKeyBase64,
            permissionsCsv = permissionsCsv
        )
    }
}


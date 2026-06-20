package eu.torvian.chatbot.worker.config

import java.nio.file.Path

/**
 * Result returned after a trusted signer has been saved into `application.json`.
 *
 * @property signerId Normalized signer identifier that was persisted.
 * @property applicationConfigPath Absolute path of the mutated `application.json` file.
 * @property replacedExisting Whether an existing signer entry with the same identifier was replaced.
 */
data class TrustedSignerUpdateResult(
    val signerId: String,
    val applicationConfigPath: Path,
    val replacedExisting: Boolean
)
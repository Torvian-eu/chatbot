package eu.torvian.chatbot.app.config

import arrow.core.Either
import arrow.core.left
import eu.torvian.chatbot.common.security.CryptoError

/**
 * WasmJs implementation of secure key generation.
 *
 * Note: This is a stub implementation. WasmJS doesn't support setup flow.
 *
 * @return A [CryptoError] indicating this platform is not supported for key generation.
 */
actual fun generateSecureKey(): Either<CryptoError, String> {
    return CryptoError.KeyGenerationError(
        "Key generation not supported on WasmJs platform.",
        null
    ).left()
}


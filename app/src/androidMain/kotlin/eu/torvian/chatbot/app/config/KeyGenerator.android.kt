package eu.torvian.chatbot.app.config

import arrow.core.Either
import arrow.core.left
import eu.torvian.chatbot.common.security.CryptoError

/**
 * Android implementation of secure key generation.
 *
 * Note: This is a stub implementation. In a real Android app, you would use
 * the Android Keystore system to generate and store keys securely.
 *
 * @return A [CryptoError] indicating this platform is not supported for key generation.
 */
actual fun generateSecureKey(): Either<CryptoError, String> {
    return CryptoError.KeyGenerationError(
        "Key generation not supported on Android platform. Use device-specific secure storage.",
        null
    ).left()
}


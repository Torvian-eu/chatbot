package eu.torvian.chatbot.app.config

import arrow.core.Either
import eu.torvian.chatbot.common.security.AESCryptoProvider
import eu.torvian.chatbot.common.security.CryptoError

/**
 * Desktop implementation of secure key generation using AESCryptoProvider.
 *
 * Generates a 256-bit (32-byte) AES key using SecureRandom and encodes it as Base64.
 *
 * @return Either a [CryptoError] if key generation fails, or a Base64-encoded key string.
 */
actual fun generateSecureKey(): Either<CryptoError, String> {
    return AESCryptoProvider.generateRandomKey()
}


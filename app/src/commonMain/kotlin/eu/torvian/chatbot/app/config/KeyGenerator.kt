package eu.torvian.chatbot.app.config

import arrow.core.Either
import eu.torvian.chatbot.common.security.CryptoError

/**
 * Generates a cryptographically secure random encryption key suitable for use as a master key.
 *
 * The key is a 256-bit (32-byte) AES key encoded as a Base64 string.
 *
 * @return Either a [CryptoError] if key generation fails, or a Base64-encoded key string.
 */
expect fun generateSecureKey(): Either<CryptoError, String>


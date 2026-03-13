package eu.torvian.chatbot.app.config

import arrow.core.Either
import eu.torvian.chatbot.common.security.AESCryptoProvider
import eu.torvian.chatbot.common.security.CryptoError

/**
 * Android implementation of secure key generation using [AESCryptoProvider].
 *
 * Generates a 256-bit (32-byte) AES key using [java.security.SecureRandom]
 * and encodes it as a Base64 string.
 *
 * TODO: Consider using the Android Keystore system to generate and store the key securely.
 *   The Keystore keeps private key material in a hardware-backed secure enclave (where
 *   available), preventing extraction even if the device is compromised.
 *   See: https://developer.android.com/privacy-and-security/keystore
 *
 * @return Either a [CryptoError] if key generation fails, or a Base64-encoded key string.
 */
actual fun generateSecureKey(): Either<CryptoError, String> {
    return AESCryptoProvider.generateRandomKey()
}

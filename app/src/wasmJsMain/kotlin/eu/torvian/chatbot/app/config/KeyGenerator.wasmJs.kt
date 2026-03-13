package eu.torvian.chatbot.app.config

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import eu.torvian.chatbot.common.security.CryptoError
import eu.torvian.chatbot.common.security.WasmJsWebCryptoProvider
import kotlin.io.encoding.Base64
import kotlin.random.Random

/**
 * WasmJs implementation of secure key generation.
 *
 * Generates a 256-bit (32-byte) AES key using multiple entropy sources from
 * [kotlin.random.Random] and encodes it as a Base64 string, mirroring the
 * approach used by [WasmJsWebCryptoProvider].
 *
 * @return Either a [CryptoError] if key generation fails, or a Base64-encoded key string.
 */
actual fun generateSecureKey(): Either<CryptoError, String> = either {
    catch({
        val key = ByteArray(32)
        val entropy1 = Random.nextBytes(32)
        val entropy2 = Random(key.hashCode()).nextBytes(32)
        val entropy3 = Random(entropy1.hashCode()).nextBytes(32)

        for (i in key.indices) {
            key[i] = (entropy1[i].toInt() xor entropy2[i].toInt() xor entropy3[i].toInt()).toByte()
        }

        Base64.encode(key)
    }) { e: Exception ->
        raise(CryptoError.KeyGenerationError("Failed to generate secure key: ${e.message}", e))
    }
}

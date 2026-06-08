package eu.torvian.chatbot.common.security

import arrow.core.Either

/**
 * Interface for asymmetric cryptographic operations used for request signing.
 *
 * This interface defines the contract for providers that implement digital signature
 * operations using asymmetric key pairs. It is used to sign sensitive requests in the
 * app and verify them in the worker.
 *
 * All methods are suspend functions to accommodate asynchronous cryptographic APIs like
 * the Web Crypto API in browsers.
 *
 * **Important distinction for [verify]:**
 * - Invalid signature mismatch (signature doesn't match the data) => `Right(false)`
 * - Operational error (malformed key, unsupported algorithm, etc.) => `Left(AsymmetricCryptoError)`
 *
 * This distinction is critical for worker security behavior, where a signature mismatch
 * should be treated as a trust decision, while operational errors indicate system problems.
 */
interface AsymmetricCryptoProvider {
    /**
     * Generates a new asymmetric signing key pair.
     *
     * Intended for app/device identity setup. The generated keys can be used to
     * sign requests that the worker will later verify.
     *
     * @return Either an [AsymmetricCryptoError] on failure, or an [AsymmetricKeyPair]
     *   containing the encoded public and private key bytes.
     */
    suspend fun generateKeyPair(): Either<AsymmetricCryptoError, AsymmetricKeyPair>

    /**
     * Signs the exact [data] string using the provided private key bytes.
     *
     * The input string must be treated as-is; no normalization or re-serialization
     * should occur inside the interface contract. The exact bytes signed must match
     * what will be verified later.
     *
     * @param data The exact string to sign. This is typically the raw JSON payload
     *   that will be stored in a [SignedRequest].
     * @param privateKey The encoded private key bytes to use for signing.
     * @return Either an [AsymmetricCryptoError] on failure, or a Base64-encoded
     *   signature string on success.
     */
    suspend fun sign(data: String, privateKey: ByteArray): Either<AsymmetricCryptoError, String>

    /**
     * Verifies whether [signature] is a valid signature for the exact [data] string.
     *
     * **Important:** This method distinguishes between signature mismatch and operational errors:
     * - Returns `Right(true)` if the signature is valid for the data under the given public key.
     * - Returns `Right(false)` if the signature is invalid (mismatch) - this is a valid result,
     *   not an error, and indicates the data was not signed by the holder of the corresponding
     *   private key.
     * - Returns `Left(...)` for technical/operational failures such as malformed key,
     *   malformed signature, or unsupported algorithm.
     *
     * This distinction allows the worker to make trust decisions based on signature validity
     * while properly handling system errors.
     *
     * @param data The exact string that was signed.
     * @param signature The Base64-encoded signature to verify.
     * @param publicKey The encoded public key bytes to use for verification.
     * @return Either an [AsymmetricCryptoError] for operational failures, or a Boolean
     *   indicating whether the signature is valid.
     */
    suspend fun verify(
        data: String,
        signature: String,
        publicKey: ByteArray
    ): Either<AsymmetricCryptoError, Boolean>
}

@file:OptIn(ExperimentalWasmJsInterop::class)

package eu.torvian.chatbot.common.security

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import kotlinx.coroutines.await
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.JsAny
import kotlin.js.JsBoolean
import kotlin.js.Promise
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array

/**
 * External interface representing a CryptoKeyPair returned by `window.crypto.subtle.generateKey`.
 * Contains the public and private CryptoKey objects.
 */
external interface JsCryptoKeyPair : JsAny {
    val publicKey: JsCryptoKey
    val privateKey: JsCryptoKey
}

/**
 * External interface representing a CryptoKey for Ed25519 operations.
 */
external interface JsCryptoKey : JsAny

// Top-level JS bridges to the Web Crypto Subtle API for Ed25519 operations.
/**
 * Generate an Ed25519 key pair using window.crypto.subtle.generateKey.
 */
private fun jsGenerateEd25519Key(): Promise<JsCryptoKeyPair> = js(
    """window.crypto.subtle.generateKey(
        { name: "Ed25519" },
        true,
        ["sign", "verify"]
    )"""
)

/**
 * Export a public key in "raw" format (32 bytes for Ed25519 public key).
 */
private fun jsExportKeyRaw(key: JsCryptoKey): Promise<ArrayBuffer> = js(
    """window.crypto.subtle.exportKey("raw", key)"""
)

/**
 * Export a private key in "pkcs8" format.
 */
private fun jsExportKeyPkcs8(key: JsCryptoKey): Promise<ArrayBuffer> = js(
    """window.crypto.subtle.exportKey("pkcs8", key)"""
)

/**
 * Import a public key from raw bytes for Ed25519 verification.
 * The passed value is an Int8Array containing the raw public key bytes.
 */
private fun jsImportPublicKeyRaw(keyData: Int8Array): Promise<JsCryptoKey> = js(
    """window.crypto.subtle.importKey("raw", keyData, { name: "Ed25519" }, true, ["verify"])"""
)

/**
 * Import a private key from pkcs8 bytes for Ed25519 signing.
 * The passed value is an Int8Array containing the pkcs8 private key bytes.
 */
private fun jsImportPrivateKeyPkcs8(keyData: Int8Array): Promise<JsCryptoKey> = js(
    """window.crypto.subtle.importKey("pkcs8", keyData, { name: "Ed25519" }, true, ["sign"])"""
)

/**
 * Sign data using an imported private key.
 * The data parameter is an Int8Array containing the bytes to sign.
 */
private fun jsSignEd25519(key: JsCryptoKey, data: Int8Array): Promise<ArrayBuffer> = js(
    """window.crypto.subtle.sign({ name: "Ed25519" }, key, data)"""
)

/**
 * Verify a signature using an imported public key.
 * The signature and data parameters are Int8Arrays.
 */
private fun jsVerifyEd25519(key: JsCryptoKey, signature: Int8Array, data: Int8Array): Promise<JsBoolean> = js(
    """window.crypto.subtle.verify({ name: "Ed25519" }, key, signature, data)"""
)

/**
 * Set a value in an Int8Array at the specified index using JS interop.
 */
private fun setInInt8Array(array: Int8Array, index: Int, value: Byte): Unit =
    js("array[index] = value")

/**
 * Get a value from an Int8Array at the specified index using JS interop.
 */
private fun getFromInt8Array(array: Int8Array, index: Int): Byte =
    js("array[index]")

/**
 * Convert a Kotlin ByteArray to an Int8Array for passing to Web Crypto API.
 */
private fun ByteArray.toJsInt8Array(): Int8Array {
    val size = this.size
    val res = Int8Array(size)
    for (i in 0 until size) {
        val value = this[i]
        setInInt8Array(res, i, value)
    }
    return res
}

/**
 * Convert a JS ArrayBuffer to a Kotlin ByteArray.
 * Uses Int8Array to create a view and read the bytes.
 */
private fun ArrayBuffer.toByteArray(): ByteArray {
    val source = Int8Array(this, 0, this.byteLength)
    val size = source.length
    val out = ByteArray(size)
    for (i in 0 until size) {
        out[i] = getFromInt8Array(source, i)
    }
    return out
}

/**
 * WASM/JS implementation of [AsymmetricCryptoProvider] that uses the browser
 * Web Crypto Subtle API to provide Ed25519 signing and verification.
 */
@OptIn(ExperimentalEncodingApi::class)
class WasmJsAsymmetricCryptoProvider : AsymmetricCryptoProvider {

    override suspend fun generateKeyPair(): Either<AsymmetricCryptoError, AsymmetricKeyPair> = either {
        catch({
            // Generate native Ed25519 key pair via Web Crypto
            val jsKeyPair = jsGenerateEd25519Key().await()

            // Export public (raw, 32 bytes) and private (pkcs8) keys
            val pubBuf = jsExportKeyRaw(jsKeyPair.publicKey).await()
            val privBuf = jsExportKeyPkcs8(jsKeyPair.privateKey).await()

            val publicKey = pubBuf.toByteArray()
            val privateKey = privBuf.toByteArray()

            AsymmetricKeyPair(
                publicKey = publicKey,
                privateKey = privateKey
            )
        }) { e: Exception ->
            raise(AsymmetricCryptoError.KeyGenerationFailed(
                "Failed to generate key pair: ${e.message}",
                e
            ))
        }
    }

    override suspend fun sign(data: String, privateKey: ByteArray): Either<AsymmetricCryptoError, String> = either {
        catch({
            // Sign using Web Crypto Ed25519
            val dataBytes = data.encodeToByteArray()

            // Import private key (pkcs8) - convert ByteArray to Int8Array
            val jsPriv = jsImportPrivateKeyPkcs8(privateKey.toJsInt8Array()).await()

            // Sign the data - convert ByteArray to Int8Array
            val sigBuf = jsSignEd25519(jsPriv, dataBytes.toJsInt8Array()).await()
            val sigBytes = sigBuf.toByteArray()

            Base64.encode(sigBytes)
        }) { e: Exception ->
            raise(AsymmetricCryptoError.SignatureGenerationFailed(
                "Failed to sign data: ${e.message}",
                e
            ))
        }
    }

    override suspend fun verify(
        data: String,
        signature: String,
        publicKey: ByteArray
    ): Either<AsymmetricCryptoError, Boolean> = either {
        catch({
            val dataBytes = data.encodeToByteArray()
            val signatureBytes = Base64.decode(signature)

            // Import public key (raw) - convert ByteArray to Int8Array
            val jsPub = jsImportPublicKeyRaw(publicKey.toJsInt8Array()).await()

            // subtle.verify(algorithm, key, signature, data) - convert ByteArray to Int8Array
            val ok = jsVerifyEd25519(jsPub, signatureBytes.toJsInt8Array(), dataBytes.toJsInt8Array()).await()
            ok.toBoolean()
        }) { e: Exception ->
            raise(AsymmetricCryptoError.SignatureVerificationFailed(
                "Failed to verify signature: ${e.message}",
                e
            ))
        }
    }
}

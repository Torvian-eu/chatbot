package eu.torvian.chatbot.common.security

/**
 * A pair of asymmetric cryptographic keys for signing operations.
 *
 * The key bytes are provider-defined encoded representations. Callers should treat them as
 * opaque bytes and not make assumptions about the specific encoding format (e.g., PKCS#8,
 * X.509, raw Ed25519 bytes). The actual encoding format depends on the implementation
 * of [AsymmetricCryptoProvider].
 *
 * @property publicKey Encoded public key bytes used for signature verification.
 *   The worker will use this to verify signatures from trusted apps/devices.
 * @property privateKey Encoded private key bytes used for signing.
 *   The app/device holds this locally to sign sensitive requests.
 */
data class AsymmetricKeyPair(
    val publicKey: ByteArray,
    val privateKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as AsymmetricKeyPair

        if (!publicKey.contentEquals(other.publicKey)) return false
        if (!privateKey.contentEquals(other.privateKey)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = publicKey.contentHashCode()
        result = 31 * result + privateKey.contentHashCode()
        return result
    }
}

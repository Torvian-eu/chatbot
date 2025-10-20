package eu.torvian.chatbot.app.service.security

/**
 * Holds human-readable certificate information used to present a trust prompt to the user.
 *
 * This DTO is intentionally minimal: UI layers extract the fields and format them
 * for display. `oldFingerprint` is null for the first-time connection scenario.
 *
 * @property fingerprint SHA-256 fingerprint of the presented certificate (hex colon-separated).
 * @property oldFingerprint The previous fingerprint if a certificate changed, otherwise null.
 * @property subject Certificate subject (issued-to) string.
 * @property issuer Certificate issuer (issued-by) string.
 * @property validUntil Human-readable expiration date string.
 */
data class CertificateDetails(
    val fingerprint: String,
    val oldFingerprint: String?, // Null if this is the first connection
    val subject: String,
    val issuer: String,
    val validUntil: String
)

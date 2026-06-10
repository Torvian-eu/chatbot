package eu.torvian.chatbot.worker.service.security

/**
 * Controls context-specific checks applied during detached signed-request verification.
 *
 * Persistent signed configuration can disable expiration checks because the signature is intended
 * to remain valid while stored, whereas transient commands should keep the default replay window.
 *
 * @property checkExpiration Whether signed-request timestamps must be close to the current worker time.
 * @property expirationWindowSeconds Maximum accepted timestamp skew, in seconds, when expiration is checked.
 */
data class VerificationOptions(
    val checkExpiration: Boolean = true,
    val expirationWindowSeconds: Long = 300
)
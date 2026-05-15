package eu.torvian.chatbot.server.service.security

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.auth.UserTrustedDeviceInfo
import eu.torvian.chatbot.server.service.security.error.RequestDeviceVerificationError
import eu.torvian.chatbot.server.service.security.error.RevokeTrustedDeviceError
import eu.torvian.chatbot.server.service.security.error.VerifyDeviceError

/**
 * Service for device trust and verification operations.
 *
 * Manages the device-based security model including trusted device tracking,
 * device verification via email tokens, and device revocation.
 */
interface DeviceTrustService {
    /**
     * Retrieves the list of trusted devices for a user.
     *
     * Returns all devices that have been trusted for the user, either through
     * Trust on First Use (first device) or by acknowledging security alerts.
     *
     * Restricted sessions cannot list trusted devices - this prevents enumeration
     * attacks on unverified devices.
     *
     * @param userId The unique identifier of the authenticated user.
     * @param requesterIsRestricted Whether the requester's session is restricted (device not verified)
     * @return Either [RevokeTrustedDeviceError] if restricted, or the list of trusted devices on success
     */
    suspend fun getTrustedDevices(
        userId: Long,
        requesterIsRestricted: Boolean
    ): Either<RevokeTrustedDeviceError, List<UserTrustedDeviceInfo>>

    /**
     * Revokes (deletes) a specific trusted device for a user.
     *
     * This removes the device from the trusted devices list, causing future logins
     * from that device to require verification (if security mode is WARNING or STRICT).
     *
     * Restricted sessions cannot revoke devices - this prevents malicious actors on
     * unverified devices from removing trust from other devices.
     *
     * @param userId The unique identifier of the authenticated user.
     * @param deviceId The device identifier to revoke.
     * @param requesterIsRestricted Whether the requester's session is restricted (device not verified)
     * @return Either an error ([RevokeTrustedDeviceError]) or Unit on success
     */
    suspend fun revokeTrustedDevice(
        userId: Long,
        deviceId: String,
        requesterIsRestricted: Boolean
    ): Either<RevokeTrustedDeviceError, Unit>

    /**
     * Requests a device verification email for a specific device.
     *
     * This allows users on restricted (untrusted) sessions to request a verification email
     * that will allow them to promote their device to "Trusted" via an email link.
     *
     * Rate limiting: A user can only request one verification email per device every 60 minutes.
     *
     * @param userId The unique identifier of the authenticated user.
     * @param deviceId The device identifier to verify.
     * @return Either [RequestDeviceVerificationError] if the request fails, or Unit on success
     */
    suspend fun requestDeviceVerificationEmail(
        userId: Long,
        deviceId: String
    ): Either<RequestDeviceVerificationError, Unit>

    /**
     * Requests a device verification email for a specific device from a public endpoint.
     *
     * This is for users blocked by STRICT mode on new devices who cannot authenticate normally.
     * The endpoint relies on rate-limiting, trust-checks, and audit record verification to prevent abuse.
     *
     * Security behavior:
     * - If the device is already trusted for the user: returns success without sending email (silent skip)
     * - If the user doesn't exist: returns success without sending email (prevents account enumeration)
     * - If no PENDING security audit record exists for the user/device: returns success without sending email (silent skip)
     * - If rate limit is exceeded: returns [RequestDeviceVerificationError.RateLimitExceeded]
     * - If user has no email: returns [RequestDeviceVerificationError.UserHasNoEmail]
     *
     * Rate limiting: A user can only request one verification email per device every 60 minutes.
     *
     * @param username The username of the account.
     * @param deviceId The device identifier to verify.
     * @return Either [RequestDeviceVerificationError] if the request fails, or Unit on success
     */
    suspend fun requestPublicDeviceVerification(
        username: String,
        deviceId: String
    ): Either<RequestDeviceVerificationError, Unit>

    /**
     * Verifies a device using a token from an email verification link.
     *
     * This method:
     * 1. Validates the token (not expired, not already used)
     * 2. Adds the device to the trusted devices list
     * 3. Resolves any PENDING security alerts for this device as TRUSTED
     * 4. Deletes the verification token (single-use)
     *
     * @param token The verification token from the email link.
     * @return Either [VerifyDeviceError] if verification fails, or Unit on success
     */
    suspend fun verifyDeviceByToken(token: String): Either<VerifyDeviceError, Unit>
}


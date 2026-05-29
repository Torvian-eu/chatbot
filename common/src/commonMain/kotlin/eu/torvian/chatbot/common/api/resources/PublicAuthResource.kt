package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.*

/**
 * Resource definitions for public authentication endpoints.
 * These endpoints do not require JWT authentication.
 */
@Resource("auth")
class PublicAuthResource(val parent: PublicApi = PublicApi()) {
    /**
     * Resource for verifying a device via email link: /api/public/auth/verify-device
     *
     * This is a GET endpoint that users access via the verification email link.
     * The token is passed as a query parameter.
     *
     * @property token The verification token from the email link.
     */
    @Resource("verify-device")
    class VerifyDevice(val parent: PublicAuthResource = PublicAuthResource(), val token: String)

    /**
     * Resource for requesting a device verification email from a new device: /api/public/auth/request-device-verification
     *
     * This is a POST endpoint for users blocked by STRICT mode on new devices.
     * Requires valid username and password to prevent abuse.
     */
    @Resource("request-device-verification")
    class RequestPublicDeviceVerification(val parent: PublicAuthResource = PublicAuthResource())
}

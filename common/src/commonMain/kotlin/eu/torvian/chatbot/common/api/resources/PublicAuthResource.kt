package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.*

/**
 * Top-level resource for the public API endpoints.
 * These endpoints do not require authentication.
 */
@Resource("/api/public")
class PublicApi

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
}

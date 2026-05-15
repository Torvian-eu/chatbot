package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.*

/**
 * Resource definitions for the /api/v1/auth endpoints.
 */
@Resource("auth")
class AuthResource(val parent: Api = Api()) {
    
    /**
     * Resource for user registration: /api/v1/auth/register
     */
    @Resource("register")
    class Register(val parent: AuthResource = AuthResource())
    
    /**
     * Resource for user login: /api/v1/auth/login
     */
    @Resource("login")
    class Login(val parent: AuthResource = AuthResource())
    
    /**
     * Resource for user logout and session revocation: /api/v1/auth/logout
     *
     * When [sessionId] is omitted, the server logs out the current authenticated session.
     * When a value is provided, the server revokes that specific session for the authenticated user.
     */
    @Resource("logout")
    class Logout(
        val parent: AuthResource = AuthResource(),
        val sessionId: Long? = null
    )

    /**
     * Resource for user logout from all sessions: /api/v1/auth/logout-all
     */
    @Resource("logout-all")
    class LogoutAll(val parent: AuthResource = AuthResource())

    /**
     * Resource for listing the authenticated user's active sessions: /api/v1/auth/sessions
     */
    @Resource("sessions")
    class Sessions(val parent: AuthResource = AuthResource())

    /**
     * Resource for getting current user profile: /api/v1/auth/me
     */
    @Resource("me")
    class Me(val parent: AuthResource = AuthResource())

    /**
     * Resource for retrieving unacknowledged security alerts: /api/v1/auth/security-alerts
     */
    @Resource("security-alerts")
    class SecurityAlerts(val parent: AuthResource = AuthResource())

    /**
     * Resource for resolving a single security alert: /api/v1/auth/security-alerts/{alertId}/resolve
     *
     * @property alertId The unique identifier of the security alert to resolve.
     */
    @Resource("resolve")
    class ResolveAlert(
        val parent: SecurityAlerts = SecurityAlerts(),
        val alertId: Long
    )

    /**
     * Resource for refreshing tokens: /api/v1/auth/refresh
     */
    @Resource("refresh")
    class Refresh(val parent: AuthResource = AuthResource())

    /**
     * Resource for exchanging worker proof for a short-lived service token.
     */
    @Resource("service-token")
    class ServiceToken(val parent: AuthResource = AuthResource())

    @Resource("service-token/challenge")
    class ServiceTokenChallenge(val parent: AuthResource = AuthResource())

    /**
     * Resource for listing trusted devices: /api/v1/auth/trusted-devices
     */
    @Resource("trusted-devices")
    class TrustedDevices(val parent: AuthResource = AuthResource())

    /**
     * Resource for revoking a specific trusted device: /api/v1/auth/trusted-devices/{deviceId}
     *
     * @property parent Parent trusted devices resource.
     * @property deviceId The device identifier to revoke.
     */
    @Resource("{deviceId}")
    class RevokeTrustedDevice(val parent: TrustedDevices = TrustedDevices(), val deviceId: String)

    /**
     * Resource for changing the authenticated user's password: /api/v1/auth/change-password
     *
     * Requires the current password for verification. This action is blocked for restricted sessions.
     */
    @Resource("change-password")
    class ChangePassword(val parent: AuthResource = AuthResource())

    /**
     * Resource for changing the authenticated user's email address: /api/v1/auth/change-email
     *
     * Requires the current password for verification. This action is blocked for restricted sessions.
     */
    @Resource("change-email")
    class ChangeEmail(val parent: AuthResource = AuthResource())

    /**
     * Resource for completing a server-required password change: /api/v1/auth/complete-required-password-change
     *
     * This endpoint is only valid when the authenticated user's requiresPasswordChange flag is true.
     * It does not require the current password, but is blocked for restricted sessions.
     */
    @Resource("complete-required-password-change")
    class CompleteRequiredPasswordChange(val parent: AuthResource = AuthResource())

    /**
     * Resource for the public auth policy endpoint: /api/v1/auth/policy
     *
     * Returns the server's account validation policy (password and username rules).
     * This endpoint is publicly accessible and does not require authentication.
     */
    @Resource("policy")
    class Policy(val parent: AuthResource = AuthResource())

    /**
     * Resource for requesting a device verification email: /api/v1/auth/request-device-verification
     *
     * This endpoint allows users on restricted sessions to request a verification email
     * that will allow them to promote their device to "Trusted" via an email link.
     * Requires authentication (even for restricted sessions).
     */
    @Resource("request-device-verification")
    class RequestDeviceVerification(val parent: AuthResource = AuthResource())
}

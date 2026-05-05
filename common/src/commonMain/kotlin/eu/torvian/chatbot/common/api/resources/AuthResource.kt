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
     * Resource for acknowledging unrecognized IP logins: /api/v1/auth/acknowledge-ips
     */
    @Resource("acknowledge-ips")
    class AcknowledgeIps(val parent: AuthResource = AuthResource())

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
}

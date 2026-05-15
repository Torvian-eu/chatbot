package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.resources.PublicAuthResource
import eu.torvian.chatbot.common.models.api.auth.RequestPublicDeviceVerificationRequest
import eu.torvian.chatbot.server.service.security.DeviceTrustService
import eu.torvian.chatbot.server.service.security.error.VerifyDeviceError
import eu.torvian.chatbot.server.service.security.error.toApiError
import eu.torvian.chatbot.server.service.security.error.toErrorHeaders
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route

/**
 * Configures public routes that do not require authentication.
 */
fun Route.configurePublicAuthRoutes(
    deviceTrustService: DeviceTrustService,
) {
    // GET /api/public/auth/verify-device?token=... - Verify device via email link
    // This is a public endpoint that verifies the token and promotes the device to trusted
    get<PublicAuthResource.VerifyDevice> { resource ->
        val token = resource.token

        val result = deviceTrustService.verifyDeviceByToken(token)

        result.fold(
            ifLeft = { error ->
                when (error) {
                    is VerifyDeviceError.InvalidOrExpiredToken -> {
                        // Return HTML page with error message
                        call.respondText(
                            """
                            <!DOCTYPE html>
                            <html>
                            <head>
                                <title>Device Verification Failed</title>
                                <style>
                                    body { font-family: Arial, sans-serif; max-width: 600px; margin: 50px auto; padding: 20px; }
                                    .error { color: #d32f2f; background: #ffebee; padding: 20px; border-radius: 8px; }
                                    .success { color: #388e3c; background: #e8f5e9; padding: 20px; border-radius: 8px; }
                                </style>
                            </head>
                            <body>
                                <h1>Device Verification Failed</h1>
                                <div class="error">
                                    <p>The verification link is invalid or has expired.</p>
                                    <p>Please request a new verification email from the application.</p>
                                </div>
                            </body>
                            </html>
                            """.trimIndent(),
                            contentType = ContentType.Text.Html,
                            status = HttpStatusCode.BadRequest
                        )
                    }
                }
            },
            ifRight = {
                // On success, return HTML page with success message
                call.respondText(
                    """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <title>Device Verified Successfully</title>
                        <style>
                            body { font-family: Arial, sans-serif; max-width: 600px; margin: 50px auto; padding: 20px; }
                            .error { color: #d32f2f; background: #ffebee; padding: 20px; border-radius: 8px; }
                            .success { color: #388e3c; background: #e8f5e9; padding: 20px; border-radius: 8px; }
                        </style>
                    </head>
                    <body>
                        <h1>Device Verified Successfully!</h1>
                        <div class="success">
                            <p>Your device has been trusted.</p>
                            <p>You can now refresh the application and use it without restrictions.</p>
                        </div>
                    </body>
                    </html>
                    """.trimIndent(),
                    contentType = ContentType.Text.Html,
                    status = HttpStatusCode.Found
                )
            }
        )
    }

    // POST /api/public/auth/request-device-verification - Request a device verification email from a new device
    // This is a public endpoint for users blocked by STRICT mode on new devices
    // Relies on rate-limiting and trust-checks to prevent abuse
    post<PublicAuthResource.RequestPublicDeviceVerification> {
        val request = call.receive<RequestPublicDeviceVerificationRequest>()

        call.respondEither(
            deviceTrustService.requestPublicDeviceVerification(
                username = request.username,
                deviceId = request.deviceId
            ),
            successCode = HttpStatusCode.Accepted,
            errorHeaders = { it.toErrorHeaders() },
            errorMapping = { it.toApiError() }
        )
    }
}

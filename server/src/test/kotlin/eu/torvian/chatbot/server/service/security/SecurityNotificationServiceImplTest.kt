package eu.torvian.chatbot.server.service.security

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.server.service.email.MailService
import eu.torvian.chatbot.server.service.email.error.MailError
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SecurityNotificationServiceImplTest {

    private val mailService = mockk<MailService>()
    private val serverUrl = "https://chatbot.example.com"

    private lateinit var securityNotificationService: SecurityNotificationServiceImpl

    @BeforeEach
    fun setUp() {
        securityNotificationService = SecurityNotificationServiceImpl(mailService, serverUrl)
    }

    @Test
    fun `sendDeviceVerification sends email with correct verification link`() = runTest {
        // Given
        val userEmail = "user@example.com"
        val token = "test-verification-token-12345"
        coEvery { mailService.sendMail(any(), any(), any()) } returns Unit.right()

        // When
        val result = securityNotificationService.sendDeviceVerification(userEmail, token)

        // Then
        assertEquals(Unit.right(), result)
        coVerify(exactly = 1) {
            mailService.sendMail(
                to = userEmail,
                subject = "Verify Your Device",
                body = match { body ->
                    body.contains("https://chatbot.example.com/api/v1/public/auth/verify-device?token=$token") &&
                    body.contains("new device is trying to access your account") &&
                    body.contains("This link will expire in 1 hour")
                }
            )
        }
    }

    @Test
    fun `sendDeviceVerification returns error when mail service fails`() = runTest {
        // Given
        val userEmail = "user@example.com"
        val token = "test-verification-token-12345"
        coEvery { mailService.sendMail(any(), any(), any()) } returns MailError.NetworkError("Connection failed").left()

        // When
        val result = securityNotificationService.sendDeviceVerification(userEmail, token)

        // Then
        assertEquals(MailError.NetworkError("Connection failed").left(), result)
    }

    @Test
    fun `sendDeviceVerification uses serverUrl for verification link`() = runTest {
        // Given - custom serverUrl
        val customServerUrl = "https://custom.domain.com:8443"
        val service = SecurityNotificationServiceImpl(mailService, customServerUrl)
        val userEmail = "user@example.com"
        val token = "test-token"
        coEvery { mailService.sendMail(any(), any(), any()) } returns Unit.right()

        // When
        service.sendDeviceVerification(userEmail, token)

        // Then
        coVerify {
            mailService.sendMail(
                to = userEmail,
                subject = any(),
                body = match { body ->
                    body.contains("https://custom.domain.com:8443/api/v1/public/auth/verify-device?token=$token")
                }
            )
        }
    }
}

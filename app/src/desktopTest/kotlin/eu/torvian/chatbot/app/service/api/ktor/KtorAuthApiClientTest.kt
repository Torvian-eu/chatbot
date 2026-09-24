package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.AuthApi
import eu.torvian.chatbot.common.api.resources.AuthResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.security.AccountValidationPolicy
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Mock-engine tests for the public auth-policy API call.
 *
 * Pins the wire contract of the self-registration flag: it decodes to `true` when advertised and
 * fail-closed to `false` when a server payload omits it entirely.
 */
class KtorAuthApiClientTest {
    private val json = Json {
        prettyPrint = true
    }

    private fun createTestClient(mockEngine: MockEngine): AuthApi {
        val httpClient = HttpClient(mockEngine) {
            configureHttpClient("http://localhost", json)
        }
        return KtorAuthApiClient(httpClient, httpClient)
    }

    /**
     * Full policy wire payload with an explicit self-registration flag line.
     */
    private fun policyJson(selfRegistrationEnabledLine: String): String = """
        {
            "passwordConfig": {
                "minLength": 8,
                "maxLength": 128,
                "requireLowercase": true,
                "requireUppercase": true,
                "requireDigit": true,
                "requireSpecialChar": true,
                "checkCommonPasswords": true
            },
            "usernameConfig": {
                "minLength": 3,
                "maxLength": 50,
                "allowedRegexPattern": "^[a-zA-Z0-9_-]+${'$'}"
            },
            "maxFailedAttempts": 10,
            "lockoutWindowMinutes": 5$selfRegistrationEnabledLine
        }
    """.trimIndent()

    @Test
    fun `getAuthPolicy - decodes selfRegistrationEnabled true`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(href(AuthResource.Policy()), request.url.fullPath)
            respond(
                content = policyJson(""",
                "selfRegistrationEnabled": true"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)

        when (val result = apiClient.getAuthPolicy()) {
            is Either.Right -> {
                val policy: AccountValidationPolicy = result.value
                assertTrue(policy.selfRegistrationEnabled)
                assertEquals(8, policy.passwordConfig.minLength)
                assertEquals(3, policy.usernameConfig.minLength)
                assertEquals(10, policy.maxFailedAttempts)
                assertEquals(5, policy.lockoutWindowMinutes)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `getAuthPolicy - absent selfRegistrationEnabled decodes to false`() = runTest {
        // Payload as sent by servers that do not advertise the flag; decoding must fail closed.
        val mockEngine = MockEngine { request ->
            respond(
                content = policyJson(""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)

        when (val result = apiClient.getAuthPolicy()) {
            is Either.Right -> {
                val policy: AccountValidationPolicy = result.value
                assertEquals(false, policy.selfRegistrationEnabled)
                assertEquals(8, policy.passwordConfig.minLength)
                assertEquals(10, policy.maxFailedAttempts)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }
}

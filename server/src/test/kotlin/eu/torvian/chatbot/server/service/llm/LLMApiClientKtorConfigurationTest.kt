package eu.torvian.chatbot.server.service.llm

import arrow.core.Either
import arrow.core.left
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies llmapiclientktor configuration tests at the HTTP client boundary.
 */
@DisplayName("LLMApiClientKtor configuration tests")
class LLMApiClientKtorConfigurationTest : LLMApiClientKtorTestBase() {

    @Test
    fun `completeChat should return ConfigurationError if strategy is not found`() = runTest {
        // Arrange
        val unknownProviderType = LLMProviderType.ANTHROPIC // Assuming ANTHROPIC is not in the strategies map
        val providerWithUnknownType = testProvider.copy(type = unknownProviderType)

        // Create a simple mock HTTP client (won't be used in this test)
        httpClient = createMockHttpClient { _ ->
            respond(
                content = ByteReadChannel(""),
                status = HttpStatusCode.OK
            )
        }
        client = LLMApiClientKtor(httpClient, strategies)

        // Act
        val result: Either<LLMCompletionError, LLMCompletionResult> = client.completeChat(
            testMessages,
            testModel,
            providerWithUnknownType, // Use provider with unknown type
            testSettings,
            testApiKey
        )

        // Assert
        assertTrue(result.isLeft(), "Result should be Left when strategy is not found")
        val error = result.leftOrNull()
        assertIs<LLMCompletionError.ConfigurationError>(error, "Error should be ConfigurationError")
        assertEquals("No ChatCompletionStrategy found for provider type: $unknownProviderType", error.message)

        // Verify interactions - no strategy methods should be called
        verify(exactly = 0) { mockStrategy.prepareRequest(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { mockStrategy.processSuccessResponse(any()) }
        verify(exactly = 0) { mockStrategy.processErrorResponse(any(), any()) }
    }

    @Test
    fun `completeChat should return ConfigurationError if strategy prepareRequest fails`() = runTest {
        // Arrange
        val configErrorMessage = "API key is missing"
        val configError = LLMCompletionError.ConfigurationError(configErrorMessage)

        // Mock strategy to return a configuration error
        every {
            mockStrategy.prepareRequest(testMessages, testModel, testProvider, testSettings, testApiKey)
        } returns configError.left()

        // Create a simple mock HTTP client (won't be used in this test)
        httpClient = createMockHttpClient { _ ->
            respond(
                content = ByteReadChannel(""),
                status = HttpStatusCode.OK
            )
        }
        client = LLMApiClientKtor(httpClient, strategies)

        // Act
        val result: Either<LLMCompletionError, LLMCompletionResult> = client.completeChat(
            testMessages,
            testModel,
            testProvider,
            testSettings,
            testApiKey
        )

        // Assert
        assertTrue(result.isLeft(), "Result should be Left when strategy preparation fails")
        val error = result.leftOrNull()
        assertIs<LLMCompletionError.ConfigurationError>(error, "Error should be ConfigurationError")
        assertEquals(configErrorMessage, error.message)

        // Verify interactions
        verify(exactly = 1) {
            mockStrategy.prepareRequest(
                testMessages,
                testModel,
                testProvider,
                testSettings,
                testApiKey
            )
        }
        verify(exactly = 0) { mockStrategy.processSuccessResponse(any()) }
        verify(exactly = 0) { mockStrategy.processErrorResponse(any(), any()) }
    }
}

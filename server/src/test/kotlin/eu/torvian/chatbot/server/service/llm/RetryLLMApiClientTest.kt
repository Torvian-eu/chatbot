package eu.torvian.chatbot.server.service.llm

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RetryLLMApiClientTest {

    private val mockInner = mockk<LLMApiClient>()

    private val testMessages = listOf(
        RawChatMessage.User("Hello")
    )

    private val testModelConfig = LLMModel(
        id = 1L,
        name = "test-model",
        providerId = 1L,
        active = true,
        displayName = "Test Model",
        capabilities = null
    )

    private val testProvider = LLMProvider(
        id = 1L,
        apiKeyId = "test-api-key-id",
        name = "test-provider",
        description = "Test Provider",
        baseUrl = "https://api.test.com",
        type = LLMProviderType.OPENAI
    )

    private val testSettings = ChatModelSettings(
        id = 1L,
        modelId = 1L,
        name = "test-settings",
        systemMessage = null,
        temperature = 0.7f,
        maxTokens = 500,
        topP = 0.9f,
        stopSequences = null,
        stream = true,
        customParams = null
    )

    private fun createTestResult(content: String = "Test response"): LLMCompletionResult {
        return LLMCompletionResult(
            choices = listOf(
                LLMCompletionResult.CompletionChoice(
                    role = "assistant",
                    content = content,
                    finishReason = "stop",
                    index = 0
                )
            ),
            usage = LLMCompletionResult.UsageStats(
                promptTokens = 10,
                completionTokens = 20,
                totalTokens = 30
            )
        )
    }

    @Test
    fun `non-streaming succeeds on first attempt without retry`() = runTest {
        val expectedResult = createTestResult()

        coEvery {
            mockInner.completeChat(any(), any(), any(), any(), any(), any())
        } returns expectedResult.right()

        val client = RetryLLMApiClient(mockInner, maxRetries = 3)
        val result = client.completeChat(
            testMessages, testModelConfig, testProvider, testSettings, "test-key"
        )

        assertTrue(result is Either.Right)
        assertEquals(expectedResult, result.value)

        coVerify(exactly = 1) {
            mockInner.completeChat(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `non-streaming retries on 429 error and succeeds on second attempt`() = runTest {
        val expectedResult = createTestResult()

        coEvery {
            mockInner.completeChat(any(), any(), any(), any(), any(), any())
        } returnsMany listOf(
            LLMCompletionError.ApiError(429, "Rate limited", null).left(),
            expectedResult.right()
        )

        val client = RetryLLMApiClient(mockInner, maxRetries = 3, baseDelayMs = 1)
        val result = client.completeChat(
            testMessages, testModelConfig, testProvider, testSettings, "test-key"
        )

        assertTrue(result is Either.Right)
        assertEquals(expectedResult, result.value)

        coVerify(exactly = 2) {
            mockInner.completeChat(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `non-streaming retries on 503 error and succeeds on third attempt`() = runTest {
        val expectedResult = createTestResult()

        coEvery {
            mockInner.completeChat(any(), any(), any(), any(), any(), any())
        } returnsMany listOf(
            LLMCompletionError.ApiError(503, "Service unavailable", null).left(),
            LLMCompletionError.ApiError(503, "Service unavailable", null).left(),
            expectedResult.right()
        )

        val client = RetryLLMApiClient(mockInner, maxRetries = 3, baseDelayMs = 1)
        val result = client.completeChat(
            testMessages, testModelConfig, testProvider, testSettings, "test-key"
        )

        assertTrue(result is Either.Right)
        assertEquals(expectedResult, result.value)

        coVerify(exactly = 3) {
            mockInner.completeChat(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `non-streaming exhausts retries and returns last error`() = runTest {
        val retryableError = LLMCompletionError.ApiError(429, "Rate limited", null)

        coEvery {
            mockInner.completeChat(any(), any(), any(), any(), any(), any())
        } returns retryableError.left()

        val client = RetryLLMApiClient(mockInner, maxRetries = 2, baseDelayMs = 1)
        val result = client.completeChat(
            testMessages, testModelConfig, testProvider, testSettings, "test-key"
        )

        assertTrue(result is Either.Left)
        assertEquals(retryableError, result.value)

        // Initial attempt + 2 retries = 3 total attempts
        coVerify(exactly = 3) {
            mockInner.completeChat(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `non-streaming does not retry on non-retryable error`() = runTest {
        val nonRetryableError = LLMCompletionError.ApiError(401, "Unauthorized", null)

        coEvery {
            mockInner.completeChat(any(), any(), any(), any(), any(), any())
        } returns nonRetryableError.left()

        val client = RetryLLMApiClient(mockInner, maxRetries = 3, baseDelayMs = 1)
        val result = client.completeChat(
            testMessages, testModelConfig, testProvider, testSettings, "test-key"
        )

        assertTrue(result is Either.Left)
        assertEquals(nonRetryableError, result.value)

        // Only one attempt — no retries for non-retryable errors
        coVerify(exactly = 1) {
            mockInner.completeChat(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `non-streaming does not retry on AuthenticationError`() = runTest {
        val authError = LLMCompletionError.AuthenticationError("Invalid API key")

        coEvery {
            mockInner.completeChat(any(), any(), any(), any(), any(), any())
        } returns authError.left()

        val client = RetryLLMApiClient(mockInner, maxRetries = 3, baseDelayMs = 1)
        val result = client.completeChat(
            testMessages, testModelConfig, testProvider, testSettings, "test-key"
        )

        assertTrue(result is Either.Left)
        assertEquals(authError, result.value)

        coVerify(exactly = 1) {
            mockInner.completeChat(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `non-streaming does not retry on ConfigurationError`() = runTest {
        val configError = LLMCompletionError.ConfigurationError("Unsupported provider")

        coEvery {
            mockInner.completeChat(any(), any(), any(), any(), any(), any())
        } returns configError.left()

        val client = RetryLLMApiClient(mockInner, maxRetries = 3, baseDelayMs = 1)
        val result = client.completeChat(
            testMessages, testModelConfig, testProvider, testSettings, "test-key"
        )

        assertTrue(result is Either.Left)
        assertEquals(configError, result.value)

        coVerify(exactly = 1) {
            mockInner.completeChat(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `non-streaming does not retry on NetworkError`() = runTest {
        val networkError = LLMCompletionError.NetworkError("Connection failed", null)

        coEvery {
            mockInner.completeChat(any(), any(), any(), any(), any(), any())
        } returns networkError.left()

        val client = RetryLLMApiClient(mockInner, maxRetries = 3, baseDelayMs = 1)
        val result = client.completeChat(
            testMessages, testModelConfig, testProvider, testSettings, "test-key"
        )

        assertTrue(result is Either.Left)
        assertEquals(networkError, result.value)

        coVerify(exactly = 1) {
            mockInner.completeChat(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `streaming succeeds on first attempt without retry`() = runTest {
        val expectedChunks = listOf(
            LLMStreamChunk.ContentChunk("Hello", null).right(),
            LLMStreamChunk.Done.right()
        )

        coEvery {
            mockInner.completeChatStreaming(any(), any(), any(), any(), any(), any())
        } returns flow {
            expectedChunks.forEach { emit(it) }
        }

        val client = RetryLLMApiClient(mockInner, maxRetries = 3)
        val result = client.completeChatStreaming(
            testMessages, testModelConfig, testProvider, testSettings, "test-key"
        ).toList()

        assertEquals(expectedChunks, result)

        @Suppress("UnusedFlow")
        verify(exactly = 1) {
            mockInner.completeChatStreaming(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `streaming retries on 429 before content emission and succeeds on second attempt`() = runTest {
        val successChunks = listOf(
            LLMStreamChunk.ContentChunk("Hello", null).right(),
            LLMStreamChunk.Done.right()
        )

        var callCount = 0
        coEvery {
            mockInner.completeChatStreaming(any(), any(), any(), any(), any(), any())
        } answers {
            callCount++
            if (callCount == 1) {
                // First attempt returns error before any content
                flow {
                    emit(LLMCompletionError.ApiError(429, "Rate limited", null).left())
                }
            } else {
                // Second attempt succeeds
                flow {
                    successChunks.forEach { emit(it) }
                }
            }
        }

        val client = RetryLLMApiClient(mockInner, maxRetries = 3, baseDelayMs = 1)
        val result = client.completeChatStreaming(
            testMessages, testModelConfig, testProvider, testSettings, "test-key"
        ).toList()

        // First error should NOT be emitted (it was retried), only success chunks
        assertEquals(successChunks, result)

        @Suppress("UnusedFlow")
        coVerify(exactly = 2) {
            mockInner.completeChatStreaming(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `streaming does not retry 429 after content has been emitted`() = runTest {
        val chunksWithError = listOf(
            LLMStreamChunk.ContentChunk("Hello", null).right(),
            LLMCompletionError.ApiError(429, "Rate limited", null).left()
        )

        coEvery {
            mockInner.completeChatStreaming(any(), any(), any(), any(), any(), any())
        } returns flow {
            chunksWithError.forEach { emit(it) }
        }

        val client = RetryLLMApiClient(mockInner, maxRetries = 3, baseDelayMs = 1)
        val result = client.completeChatStreaming(
            testMessages, testModelConfig, testProvider, testSettings, "test-key"
        ).toList()

        // Error should be emitted because content was already streamed
        assertEquals(chunksWithError, result)

        // Only one attempt — retry not triggered because content was already emitted
        @Suppress("UnusedFlow")
        coVerify(exactly = 1) {
            mockInner.completeChatStreaming(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `streaming exhausts retries on repeated 429 before content`() = runTest {
        val retryableError = LLMCompletionError.ApiError(429, "Rate limited", null).left()

        coEvery {
            mockInner.completeChatStreaming(any(), any(), any(), any(), any(), any())
        } returns flow {
            emit(retryableError)
        }

        val client = RetryLLMApiClient(mockInner, maxRetries = 2, baseDelayMs = 1)
        val result = client.completeChatStreaming(
            testMessages, testModelConfig, testProvider, testSettings, "test-key"
        ).toList()

        // After exhausting retries, the error should be emitted
        assertEquals(listOf(retryableError), result)

        // Initial attempt + 2 retries = 3 total attempts
        @Suppress("UnusedFlow")
        coVerify(exactly = 3) {
            mockInner.completeChatStreaming(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `streaming retries on embedded Error chunk before content and succeeds on second attempt`() = runTest {
        val successChunks = listOf(
            LLMStreamChunk.ContentChunk("Hello", null).right(),
            LLMStreamChunk.Done.right()
        )

        var callCount = 0
        coEvery {
            mockInner.completeChatStreaming(any(), any(), any(), any(), any(), any())
        } answers {
            callCount++
            if (callCount == 1) {
                // First attempt returns an embedded stream Error (501/502-style OpenRouter case)
                // before any content is emitted.
                flow {
                    emit(
                        LLMStreamChunk.Error(
                            LLMCompletionError.ApiError(502, "Upstream error", "data: {...}")
                        ).right()
                    )
                }
            } else {
                // Second attempt succeeds.
                flow {
                    successChunks.forEach { emit(it) }
                }
            }
        }

        val client = RetryLLMApiClient(
            mockInner,
            maxRetries = 3,
            baseDelayMs = 1,
            retryableStatusCodes = setOf(429, 502, 503)
        )
        val result = client.completeChatStreaming(
            testMessages, testModelConfig, testProvider, testSettings, "test-key"
        ).toList()

        // The embedded error was retried, so only the success chunks surface.
        assertEquals(successChunks, result)

        @Suppress("UnusedFlow")
        coVerify(exactly = 2) {
            mockInner.completeChatStreaming(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `streaming emits non-retryable embedded Error chunk without retry`() = runTest {
        val nonRetryableErrorChunk = LLMStreamChunk.Error(
            LLMCompletionError.ApiError(401, "Unauthorized", "data: {...}")
        ).right()

        coEvery {
            mockInner.completeChatStreaming(any(), any(), any(), any(), any(), any())
        } returns flow {
            emit(nonRetryableErrorChunk)
        }

        val client = RetryLLMApiClient(mockInner, maxRetries = 3, baseDelayMs = 1)
        val result = client.completeChatStreaming(
            testMessages, testModelConfig, testProvider, testSettings, "test-key"
        ).toList()

        // Non-retryable embedded error propagates immediately without a retry.
        assertEquals(listOf(nonRetryableErrorChunk), result)

        @Suppress("UnusedFlow")
        coVerify(exactly = 1) {
            mockInner.completeChatStreaming(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `streaming does not retry on non-retryable error`() = runTest {
        val nonRetryableError = LLMCompletionError.ApiError(401, "Unauthorized", null).left()

        coEvery {
            mockInner.completeChatStreaming(any(), any(), any(), any(), any(), any())
        } returns flow {
            emit(nonRetryableError)
        }

        val client = RetryLLMApiClient(mockInner, maxRetries = 3, baseDelayMs = 1)
        val result = client.completeChatStreaming(
            testMessages, testModelConfig, testProvider, testSettings, "test-key"
        ).toList()

        assertEquals(listOf(nonRetryableError), result)

        // Only one attempt — no retries for non-retryable errors
        @Suppress("UnusedFlow")
        coVerify(exactly = 1) {
            mockInner.completeChatStreaming(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `discoverModels does not retry on error`() = runTest {
        val discoveryError = ModelDiscoveryError.ApiError(429, "Rate limited", null)

        coEvery {
            mockInner.discoverModels(any(), any())
        } returns discoveryError.left()

        val client = RetryLLMApiClient(mockInner, maxRetries = 3, baseDelayMs = 1)
        val result = client.discoverModels(testProvider, "test-key")

        assertTrue(result is Either.Left)
        assertEquals(discoveryError, result.value)

        // Model discovery is not retried — only one attempt
        coVerify(exactly = 1) {
            mockInner.discoverModels(any(), any())
        }
    }

    @Test
    fun `custom retryable status codes are respected`() = runTest {
        val customRetryableError = LLMCompletionError.ApiError(502, "Bad gateway", null)
        val expectedResult = createTestResult("Success")

        coEvery {
            mockInner.completeChat(any(), any(), any(), any(), any(), any())
        } returnsMany listOf(
            customRetryableError.left(),
            expectedResult.right()
        )

        // Create client that retries on 502 (in addition to default 429/503)
        val client = RetryLLMApiClient(
            mockInner,
            maxRetries = 3,
            baseDelayMs = 1,
            retryableStatusCodes = setOf(429, 503, 502)
        )
        val result = client.completeChat(
            testMessages, testModelConfig, testProvider, testSettings, "test-key"
        )

        assertTrue(result is Either.Right)
        assertEquals(expectedResult, result.value)

        coVerify(exactly = 2) {
            mockInner.completeChat(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `maxRetries counts retries after initial non-streaming attempt`() = runTest {
        val retryableError = LLMCompletionError.ApiError(429, "Rate limited", null)

        coEvery {
            mockInner.completeChat(any(), any(), any(), any(), any(), any())
        } returns retryableError.left()

        val client = RetryLLMApiClient(mockInner, maxRetries = 0, baseDelayMs = 1)
        val result = client.completeChat(
            testMessages, testModelConfig, testProvider, testSettings, "test-key"
        )

        assertTrue(result is Either.Left)
        assertEquals(retryableError, result.value)

        // maxRetries = 0 means only the initial attempt is executed.
        coVerify(exactly = 1) {
            mockInner.completeChat(any(), any(), any(), any(), any(), any())
        }
    }
}


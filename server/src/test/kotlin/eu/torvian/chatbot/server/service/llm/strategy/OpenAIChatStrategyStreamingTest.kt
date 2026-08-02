package eu.torvian.chatbot.server.service.llm.strategy

import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMStreamChunk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies openaichatstrategy streaming tests behavior at its public strategy boundary.
 */
@DisplayName("OpenAIChatStrategy streaming tests")
class OpenAIChatStrategyStreamingTest : OpenAIChatStrategyTestBase() {

    @Test
    @DisplayName("processStreamingResponse should successfully process typical OpenAI streaming response")
    fun processStreamingResponse_happyPath() = runTest {
        // Given
        val streamLines = listOf(
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1677652288,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}",
            "",
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1677652288,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}",
            "",
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1677652288,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\" there!\"},\"finish_reason\":null}]}",
            "",
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1677652288,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
            "",
            "data: [DONE]",
            ""
        )
        val responseStream = flowOf(*streamLines.toTypedArray())

        // When
        val result = strategy.processStreamingResponse(responseStream).toList()

        // Then
        assertEquals(4, result.size, "Should have 2 content chunks + 1 finish chunk + 1 done chunk")

        // First chunk should have "Hello" content (role-only chunk is ignored)
        assertTrue(result[0].isRight())
        val firstChunk = result[0].getOrNull()
        assertTrue(firstChunk is LLMStreamChunk.ContentChunk)
        assertEquals("Hello", firstChunk.deltaContent)
        assertNull(firstChunk.finishReason)

        // Second chunk should have " there!" content
        assertTrue(result[1].isRight())
        val secondChunk = result[1].getOrNull()
        assertTrue(secondChunk is LLMStreamChunk.ContentChunk)
        assertEquals(" there!", secondChunk.deltaContent)
        assertNull(secondChunk.finishReason)

        // Third chunk should be empty content with finish reason
        assertTrue(result[2].isRight())
        val thirdChunk = result[2].getOrNull()
        assertTrue(thirdChunk is LLMStreamChunk.ContentChunk)
        assertEquals("", thirdChunk.deltaContent)
        assertEquals("stop", thirdChunk.finishReason)

        // Final chunk should be Done
        assertTrue(result[3].isRight())
        val finalChunk = result[3].getOrNull()
        assertTrue(finalChunk is LLMStreamChunk.Done)
    }

    @Test
    @DisplayName("processStreamingResponse should handle malformed JSON gracefully")
    fun processStreamingResponse_malformedJson() = runTest {
        // Given
        val streamLines = listOf(
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1677652288,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}",
            "data: {\"invalid_json\": malformed",
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1677652288,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\" World!\"},\"finish_reason\":null}]}",
            "data: [DONE]"
        )
        val responseStream = flowOf(*streamLines.toTypedArray())

        // When
        val result = strategy.processStreamingResponse(responseStream).toList()

        // Then
        assertEquals(4, result.size, "Should have 2 content chunks + 1 error + 1 done chunk")

        // First chunk should be successful
        assertTrue(result[0].isRight())
        val firstChunk = result[0].getOrNull()
        assertTrue(firstChunk is LLMStreamChunk.ContentChunk)
        assertEquals("Hello", firstChunk.deltaContent)

        // Second chunk should be an error
        assertTrue(result[1].isLeft())
        val error = result[1].leftOrNull()
        assertIs<LLMCompletionError.InvalidResponseError>(error)
        assertTrue(error.message.contains("Failed to parse OpenAI streaming JSON chunk"))

        // Third chunk should be successful
        assertTrue(result[2].isRight())
        val thirdChunk = result[2].getOrNull()
        assertTrue(thirdChunk is LLMStreamChunk.ContentChunk)
        assertEquals(" World!", thirdChunk.deltaContent)

        // Final chunk should be Done
        assertTrue(result[3].isRight())
        val finalChunk = result[3].getOrNull()
        assertTrue(finalChunk is LLMStreamChunk.Done)
    }

    @Test
    @DisplayName("processStreamingResponse should ignore non-data SSE lines")
    fun processStreamingResponse_ignoreNonDataLines() = runTest {
        // Given
        val streamLines = listOf(
            "event: message",
            "id: 123",
            ": This is a comment",
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1677652288,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}",
            "retry: 3000",
            "",
            "data: [DONE]"
        )
        val responseStream = flowOf(*streamLines.toTypedArray())

        // When
        val result = strategy.processStreamingResponse(responseStream).toList()

        // Then
        assertEquals(2, result.size, "Should have 1 content chunk + 1 done chunk")

        // First chunk should be the content
        assertTrue(result[0].isRight())
        val firstChunk = result[0].getOrNull()
        assertTrue(firstChunk is LLMStreamChunk.ContentChunk)
        assertEquals("Hello", firstChunk.deltaContent)

        // Final chunk should be Done
        assertTrue(result[1].isRight())
        val finalChunk = result[1].getOrNull()
        assertTrue(finalChunk is LLMStreamChunk.Done)
    }

    @Test
    @DisplayName("processStreamingResponse should handle empty data chunks gracefully")
    fun processStreamingResponse_emptyDataChunks() = runTest {
        // Given
        val streamLines = listOf(
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1677652288,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":null}]}",
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1677652288,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}",
            "data: [DONE]"
        )
        val responseStream = flowOf(*streamLines.toTypedArray())

        // When
        val result = strategy.processStreamingResponse(responseStream).toList()

        // Then
        assertEquals(2, result.size, "Should have 1 content chunk + 1 done chunk (empty delta ignored)")

        // First chunk should be the content
        assertTrue(result[0].isRight())
        val firstChunk = result[0].getOrNull()
        assertTrue(firstChunk is LLMStreamChunk.ContentChunk)
        assertEquals("Hello", firstChunk.deltaContent)

        // Final chunk should be Done
        assertTrue(result[1].isRight())
        val finalChunk = result[1].getOrNull()
        assertTrue(finalChunk is LLMStreamChunk.Done)
    }

    @Test
    @DisplayName("processStreamingResponse should handle abrupt stream ending without [DONE]")
    fun processStreamingResponse_abruptEnding() = runTest {
        // Given
        val streamLines = listOf(
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1677652288,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}",
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1677652288,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\" World\"},\"finish_reason\":null}]}"
            // No [DONE] signal
        )
        val responseStream = flowOf(*streamLines.toTypedArray())

        // When
        val result = strategy.processStreamingResponse(responseStream).toList()

        // Then
        assertEquals(3, result.size, "Should have 2 content chunks + 1 done chunk")

        // First chunk
        assertTrue(result[0].isRight())
        val firstChunk = result[0].getOrNull()
        assertTrue(firstChunk is LLMStreamChunk.ContentChunk)
        assertEquals("Hello", firstChunk.deltaContent)

        // Second chunk
        assertTrue(result[1].isRight())
        val secondChunk = result[1].getOrNull()
        assertTrue(secondChunk is LLMStreamChunk.ContentChunk)
        assertEquals(" World", secondChunk.deltaContent)

        // Final chunk should still be Done (emitted after collect completes)
        assertTrue(result[2].isRight())
        val finalChunk = result[2].getOrNull()
        assertTrue(finalChunk is LLMStreamChunk.Done)
    }

    @Test
    @DisplayName("processStreamingResponse should handle multiple choices in a single chunk")
    fun processStreamingResponse_multipleChoices() = runTest {
        // Given
        val streamLines = listOf(
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1677652288,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null},{\"index\":1,\"delta\":{\"content\":\"Hi\"},\"finish_reason\":null}]}",
            "data: [DONE]"
        )
        val responseStream = flowOf(*streamLines.toTypedArray())

        // When
        val result = strategy.processStreamingResponse(responseStream).toList()

        // Then
        assertEquals(3, result.size, "Should have 2 content chunks (one per choice) + 1 done chunk")

        // First choice content
        assertTrue(result[0].isRight())
        val firstChunk = result[0].getOrNull()
        assertTrue(firstChunk is LLMStreamChunk.ContentChunk)
        assertEquals("Hello", firstChunk.deltaContent)

        // Second choice content
        assertTrue(result[1].isRight())
        val secondChunk = result[1].getOrNull()
        assertTrue(secondChunk is LLMStreamChunk.ContentChunk)
        assertEquals("Hi", secondChunk.deltaContent)

        // Final chunk should be Done
        assertTrue(result[2].isRight())
        val finalChunk = result[2].getOrNull()
        assertTrue(finalChunk is LLMStreamChunk.Done)
    }

    @Test
    @DisplayName("processStreamingResponse should emit UsageChunk when usage data is present")
    fun processStreamingResponse_withUsageData_emitsUsageChunk() = runTest {
        // Given
        val streamLines = listOf(
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1677652288,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}",
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1677652288,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}",
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1677652288,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1677652288,\"model\":\"gpt-4o\",\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}",
            "data: [DONE]"
        )
        val responseStream = flowOf(*streamLines.toTypedArray())

        // When
        val result = strategy.processStreamingResponse(responseStream).toList()

        // Then
        assertEquals(4, result.size, "Should have 1 content chunk + 1 finish chunk + 1 usage chunk + 1 done chunk")

        // First chunk should be content
        assertTrue(result[0].isRight())
        val firstChunk = result[0].getOrNull()
        assertTrue(firstChunk is LLMStreamChunk.ContentChunk)
        assertEquals("Hello", firstChunk.deltaContent)

        // Second chunk should be finish reason
        assertTrue(result[1].isRight())
        val secondChunk = result[1].getOrNull()
        assertTrue(secondChunk is LLMStreamChunk.ContentChunk)
        assertEquals("", secondChunk.deltaContent)
        assertEquals("stop", secondChunk.finishReason)

        // Third chunk should be usage stats
        assertTrue(result[2].isRight())
        val usageChunk = result[2].getOrNull()
        assertTrue(usageChunk is LLMStreamChunk.UsageChunk)
        assertEquals(10, usageChunk.promptTokens)
        assertEquals(5, usageChunk.completionTokens)
        assertEquals(15, usageChunk.totalTokens)

        // Final chunk should be Done
        assertTrue(result[3].isRight())
        val finalChunk = result[3].getOrNull()
        assertTrue(finalChunk is LLMStreamChunk.Done)
    }

    @Test
    @DisplayName("processStreamingResponse should handle stream without usage data")
    fun processStreamingResponse_withoutUsageData_noUsageChunk() = runTest {
        // Given - stream without usage data (legacy behavior)
        val streamLines = listOf(
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1677652288,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}",
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1677652288,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}",
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1677652288,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
            "data: [DONE]"
        )
        val responseStream = flowOf(*streamLines.toTypedArray())

        // When
        val result = strategy.processStreamingResponse(responseStream).toList()

        // Then
        assertEquals(3, result.size, "Should have 1 content chunk + 1 finish chunk + 1 done chunk (no usage chunk)")

        // Verify no usage chunk is present
        val usageChunks = result.mapNotNull { it.getOrNull() }.filterIsInstance<LLMStreamChunk.UsageChunk>()
        assertTrue(usageChunks.isEmpty(), "Should not emit any usage chunks when usage data is not present")

        // Final chunk should still be Done
        assertTrue(result[2].isRight())
        val finalChunk = result[2].getOrNull()
        assertTrue(finalChunk is LLMStreamChunk.Done)
    }

    @Test
    @DisplayName("processStreamingResponse should handle usage data in chunk with empty choices")
    fun processStreamingResponse_usageDataWithEmptyChoices_emitsUsageChunk() = runTest {
        // Given - usage data appears in a chunk with empty choices array (common OpenAI behavior)
        val streamLines = listOf(
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1677652288,\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello World\"},\"finish_reason\":\"stop\"}]}",
            "data: {\"id\":\"chatcmpl-123\",\"object\":\"chat.completion.chunk\",\"created\":1677652288,\"model\":\"gpt-4o\",\"choices\":[],\"usage\":{\"prompt_tokens\":25,\"completion_tokens\":10,\"total_tokens\":35}}",
            "data: [DONE]"
        )
        val responseStream = flowOf(*streamLines.toTypedArray())

        // When
        val result = strategy.processStreamingResponse(responseStream).toList()

        // Then
        assertEquals(3, result.size, "Should have 1 content chunk + 1 usage chunk + 1 done chunk")

        // First chunk should be content with finish reason
        assertTrue(result[0].isRight())
        val contentChunk = result[0].getOrNull()
        assertTrue(contentChunk is LLMStreamChunk.ContentChunk)
        assertEquals("Hello World", contentChunk.deltaContent)
        assertEquals("stop", contentChunk.finishReason)

        // Second chunk should be usage stats (even though choices is empty)
        assertTrue(result[1].isRight())
        val usageChunk = result[1].getOrNull()
        assertTrue(usageChunk is LLMStreamChunk.UsageChunk)
        assertEquals(25, usageChunk.promptTokens)
        assertEquals(10, usageChunk.completionTokens)
        assertEquals(35, usageChunk.totalTokens)

        // Final chunk should be Done
        assertTrue(result[2].isRight())
        val finalChunk = result[2].getOrNull()
        assertTrue(finalChunk is LLMStreamChunk.Done)
    }
}

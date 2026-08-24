package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.core.LLMModelService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ListModelsTool].
 *
 * Covers the READ-accessible model listing and the strict rejection of any input parameter (the
 * tool accepts none).
 */
class ListModelsToolTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val userId = 7L

    private fun assertSuccess(result: Either<ServerBuiltInToolHandlerError, String>): String {
        assertTrue(result.isRight(), "Expected success but got: ${result.leftOrNull()}")
        return assertNotNull(result.getOrNull())
    }

    @Test
    fun `returns the models accessible by the current user`() = runTest {
        val llmModelService = mockk<LLMModelService>()
        val models = listOf(
            LLMModel(id = 1L, name = "gpt-4o", providerId = 1L, active = true),
            LLMModel(id = 2L, name = "llama3", providerId = 2L, active = false)
        )
        coEvery { llmModelService.getAllAccessibleModels(userId, AccessMode.READ) } returns models
        val tool = ListModelsTool(llmModelService, json)

        val output = assertSuccess(tool.execute(userId, buildJsonObject { }))
        val decoded = json.decodeFromString<List<LLMModel>>(output)

        assertEquals(2, decoded.size)
        assertEquals("gpt-4o", decoded.first().name)
        coVerify(exactly = 1) { llmModelService.getAllAccessibleModels(userId, AccessMode.READ) }
    }

    @Test
    fun `rejects unknown parameters without calling the service`() = runTest {
        val llmModelService = mockk<LLMModelService>()
        val tool = ListModelsTool(llmModelService, json)

        val result = tool.execute(userId, buildJsonObject { put("provider", "openai") })

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Unknown parameter: 'provider'"))
        coVerify(exactly = 0) { llmModelService.getAllAccessibleModels(any(), any()) }
    }
}

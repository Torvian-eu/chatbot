package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.core.LLMModelService
import eu.torvian.chatbot.server.service.core.ModelSettingsService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ListModelSettingsTool].
 *
 * Covers the `model_id` validation, the accessibility pre-check on the model, the settings listing
 * for an accessible model, and strict rejection of unknown parameters.
 */
class ListModelSettingsToolTest {

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
    fun `requires the model_id property`() = runTest {
        val tool = ListModelSettingsTool(mockk(), mockk(), json)

        val result = tool.execute(userId, buildJsonObject { })

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Missing required argument: model_id"))
    }

    @Test
    fun `requires an accessible model`() = runTest {
        val llmModelService = mockk<LLMModelService>()
        val modelSettingsService = mockk<ModelSettingsService>()
        // The model is not in the user's accessible set -> collapsed error.
        coEvery { llmModelService.getAllAccessibleModels(userId, AccessMode.READ) } returns emptyList()
        val tool = ListModelSettingsTool(llmModelService, modelSettingsService, json)

        val result = tool.execute(userId, buildJsonObject { put("model_id", 5L) })

        val error = assertIs<ServerBuiltInToolHandlerError.NotFoundOrNotAccessible>(result.leftOrNull())
        assertTrue(error.message.contains("not found or not accessible by the current user"))
        coVerify(exactly = 0) { modelSettingsService.getAccessibleSettingsByModelId(any(), any(), any()) }
    }

    @Test
    fun `returns the settings for an accessible model`() = runTest {
        val llmModelService = mockk<LLMModelService>()
        val modelSettingsService = mockk<ModelSettingsService>()
        val model = LLMModel(id = 5L, name = "gpt-4o", providerId = 1L, active = true)
        coEvery { llmModelService.getAllAccessibleModels(userId, AccessMode.READ) } returns listOf(model)
        val settings = ChatModelSettings(id = 6L, modelId = 5L, name = "Default")
        coEvery { modelSettingsService.getAccessibleSettingsByModelId(userId, 5L, AccessMode.READ) } returns
            listOf(settings)
        val tool = ListModelSettingsTool(llmModelService, modelSettingsService, json)

        val output = assertSuccess(tool.execute(userId, buildJsonObject { put("model_id", 5L) }))

        assertTrue(output.contains("\"type\":\"chat\""))
        assertTrue(output.contains("\"name\":\"Default\""))
        coVerify(exactly = 1) {
            modelSettingsService.getAccessibleSettingsByModelId(userId, 5L, AccessMode.READ)
        }
    }

    @Test
    fun `rejects unknown parameters without calling the services`() = runTest {
        val llmModelService = mockk<LLMModelService>()
        val modelSettingsService = mockk<ModelSettingsService>()
        val tool = ListModelSettingsTool(llmModelService, modelSettingsService, json)

        val result = tool.execute(userId, buildJsonObject { put("model_id", 5L); put("verbose", true) })

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Unknown parameter: 'verbose'"))
        coVerify(exactly = 0) { llmModelService.getAllAccessibleModels(any(), any()) }
        coVerify(exactly = 0) { modelSettingsService.getAccessibleSettingsByModelId(any(), any(), any()) }
    }
}

package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.core.ModelPresetService
import eu.torvian.chatbot.server.service.core.error.preset.ModelPresetError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Unit tests for [ReadModelPresetTool].
 *
 * Covers the `model_preset_id` validation, the full [ModelPresetDto] JSON output, the collapse of
 * not-found/not-accessible into one message, and strict rejection of unknown parameters.
 */
class ReadModelPresetToolTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val userId = 7L

    /**
     * Fully-populated execution context for handler-level tests: the handlers ignore the session
     * fields, so fixed non-null values keep the fixture simple while matching the real contract.
     */
    private fun context(userId: Long = this.userId): ToolCallExecutionContext =
        ToolCallExecutionContext(
            userId = userId,
            sessionId = 1L,
            sessionName = "Session",
            agentRoleId = 1L
        )

    private fun samplePreset(id: Long = 3L) = ModelPresetDto(
        id = id,
        name = "smart_model",
        displayName = "Smart model",
        description = "Bundles the smart model with the default settings profile",
        modelId = 11L,
        modelSettingsId = 21L,
        createdAt = Instant.parse("2024-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2024-01-02T00:00:00Z")
    )

    private fun assertSuccess(result: Either<ServerBuiltInToolHandlerError, String>): String {
        assertTrue(result.isRight(), "Expected success but got: ${result.leftOrNull()}")
        return assertNotNull(result.getOrNull())
    }

    @Test
    fun `requires the model_preset_id property`() = runTest {
        val tool = ReadModelPresetTool(mockk(), json)

        val result = tool.execute(buildJsonObject { }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Missing required argument: model_preset_id"))
    }

    @Test
    fun `rejects a non-integer model_preset_id`() = runTest {
        val tool = ReadModelPresetTool(mockk(), json)

        val result = tool.execute(buildJsonObject { put("model_preset_id", "abc") }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Argument 'model_preset_id' must be an integer"))
    }

    /**
     * Verifies that success returns the full preset JSON (the read-side payload convention).
     */
    @Test
    fun `returns the full preset DTO JSON`() = runTest {
        val modelPresetService = mockk<ModelPresetService>()
        coEvery { modelPresetService.getPresetById(userId, 3L) } returns samplePreset().right()
        val tool = ReadModelPresetTool(modelPresetService, json)

        val output = assertSuccess(
            tool.execute(buildJsonObject { put("model_preset_id", 3L) }, context())
        )

        val decoded = json.parseToJsonElement(output).jsonObject
        assertEquals(3L, decoded.getValue("id").jsonPrimitive.long)
        assertEquals("smart_model", decoded.getValue("name").jsonPrimitive.content)
        assertEquals("Smart model", decoded.getValue("displayName").jsonPrimitive.content)
        assertEquals(11L, decoded.getValue("modelId").jsonPrimitive.long)
        assertEquals(21L, decoded.getValue("modelSettingsId").jsonPrimitive.long)
        assertEquals("2024-01-01T00:00:00Z", decoded.getValue("createdAt").jsonPrimitive.content)
        assertEquals("2024-01-02T00:00:00Z", decoded.getValue("updatedAt").jsonPrimitive.content)
        coVerify(exactly = 1) { modelPresetService.getPresetById(userId, 3L) }
    }

    /**
     * Verifies that a foreign or nonexistent preset collapses into one not-found message that does
     * not disclose existence (id-enumeration guard).
     */
    @Test
    fun `collapses not-found and not-accessible into one message`() = runTest {
        val modelPresetService = mockk<ModelPresetService>()
        coEvery { modelPresetService.getPresetById(userId, 99L) } returns ModelPresetError.NotFound(99L).left()
        val tool = ReadModelPresetTool(modelPresetService, json)

        val result = tool.execute(buildJsonObject { put("model_preset_id", 99L) }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.NotFoundOrNotAccessible>(result.leftOrNull())
        assertTrue(error.message.contains("not found or not accessible by the current user"))
        assertTrue(error.message.contains("99"))
    }

    /**
     * Verifies that unknown parameters fail validation before the service is called.
     */
    @Test
    fun `rejects unknown parameters without calling the service`() = runTest {
        val modelPresetService = mockk<ModelPresetService>()
        val tool = ReadModelPresetTool(modelPresetService, json)

        val result = tool.execute(
            buildJsonObject { put("model_preset_id", 3L); put("role_id", 1L) },
            context()
        )

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Unknown parameter: 'role_id'"))
        coVerify(exactly = 0) { modelPresetService.getPresetById(any(), any()) }
    }
}

package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.core.ModelPresetService
import eu.torvian.chatbot.server.service.core.error.preset.DeleteModelPresetError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [DeleteModelPresetTool].
 *
 * Covers the `model_preset_id` validation, the success confirmation mentioning the preset id, the
 * collapse of not-found/not-accessible into one message, and strict rejection of unknown
 * parameters without touching the service.
 */
class DeleteModelPresetToolTest {

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

    private fun assertSuccess(result: Either<ServerBuiltInToolHandlerError, String>): String {
        assertTrue(result.isRight(), "Expected success but got: ${result.leftOrNull()}")
        return assertNotNull(result.getOrNull())
    }

    @Test
    fun `requires the model_preset_id property`() = runTest {
        val tool = DeleteModelPresetTool(mockk())

        val result = tool.execute(buildJsonObject { }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Missing required argument: model_preset_id"))
    }

    @Test
    fun `rejects a non-integer model_preset_id`() = runTest {
        val tool = DeleteModelPresetTool(mockk())

        val result = tool.execute(buildJsonObject { put("model_preset_id", "abc") }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Argument 'model_preset_id' must be an integer"))
    }

    /**
     * Verifies that success returns a plain-text confirmation that mentions the preset id, and that
     * deleting a preset bound to roles is not reported as an error (the roles survive as
     * non-sendable).
     */
    @Test
    fun `returns a confirmation mentioning the preset id on success`() = runTest {
        val modelPresetService = mockk<ModelPresetService>()
        coEvery { modelPresetService.deletePreset(userId, 3L) } returns Unit.right()
        val tool = DeleteModelPresetTool(modelPresetService)

        val output = assertSuccess(
            tool.execute(buildJsonObject { put("model_preset_id", 3L) }, context())
        )

        assertTrue(output.contains("Deleted model preset (id: 3)"))
        coVerify(exactly = 1) { modelPresetService.deletePreset(userId, 3L) }
    }

    /**
     * Verifies that a foreign or nonexistent preset collapses into one not-found message that does
     * not disclose existence (id-enumeration guard).
     */
    @Test
    fun `collapses not-found and not-accessible into one message`() = runTest {
        val modelPresetService = mockk<ModelPresetService>()
        coEvery { modelPresetService.deletePreset(userId, 99L) } returns
            DeleteModelPresetError.NotFound(99L).left()
        val tool = DeleteModelPresetTool(modelPresetService)

        val result = tool.execute(buildJsonObject { put("model_preset_id", 99L) }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.NotFoundOrNotAccessible>(result.leftOrNull())
        assertTrue(error.message.contains("not found or not accessible by the current user"))
        assertTrue(error.message.contains("99"))
    }

    @Test
    fun `rejects unknown parameters without calling the service`() = runTest {
        val modelPresetService = mockk<ModelPresetService>()
        val tool = DeleteModelPresetTool(modelPresetService)

        val result = tool.execute(
            buildJsonObject { put("model_preset_id", 3L); put("cascade", true) },
            context()
        )

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Unknown parameter: 'cascade'"))
        coVerify(exactly = 0) { modelPresetService.deletePreset(any(), any()) }
    }

    /**
     * Verifies that the tool description states the approved non-destructive role behaviour.
     */
    @Test
    fun `describes the non-destructive role behaviour`() {
        val tool = DeleteModelPresetTool(mockk())

        assertTrue(tool.description.contains("not deleted"))
        assertTrue(tool.description.contains("non-sendable"))
    }
}

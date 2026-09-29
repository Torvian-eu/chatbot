package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.core.InstructionService
import eu.torvian.chatbot.server.service.core.error.instruction.DeleteInstructionError
import eu.torvian.chatbot.server.service.core.error.instruction.GetInstructionError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [DeleteInstructionTool].
 *
 * Covers the deletion summary, the in-use refusal naming the linking roles, the
 * not-found/not-accessible collapse before any delete, and strict input validation.
 */
class DeleteInstructionToolTest {

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

    private fun instruction(linkedRoleIds: Set<Long>) = AgentInstructionDto(
        id = 7L,
        type = AgentInstructionTypes.MAIN,
        name = "Project rules",
        message = "Follow the project rules.",
        linkedRoleIds = linkedRoleIds
    )

    /**
     * Asserts a successful execution and returns its output.
     *
     * @param result The handler result.
     * @return The handler output.
     */
    private fun assertSuccess(result: Either<ServerBuiltInToolHandlerError, String>): String {
        assertTrue(result.isRight(), "Expected success but got: ${result.leftOrNull()}")
        return assertNotNull(result.getOrNull())
    }

    /**
     * Verifies that the summary names the label and the id of the deleted row.
     */
    @Test
    fun `summarizes the deleted row by name and id`() = runTest {
        val instructionService = mockk<InstructionService>()
        coEvery { instructionService.getInstructionById(userId, 7L) } returns
            instruction(emptySet()).right()
        coEvery { instructionService.deleteInstruction(userId, 7L) } returns Unit.right()
        val tool = DeleteInstructionTool(instructionService)

        val output = assertSuccess(tool.execute(buildJsonObject { put("instruction_id", 7L) }, context()))

        assertEquals("Deleted instruction 'Project rules' (id: 7).", output)
        coVerify(exactly = 1) { instructionService.deleteInstruction(userId, 7L) }
    }

    /**
     * Verifies that a row some role still links fails with the in-use code naming those roles, and
     * that the refusal is not retried as a delete.
     */
    @Test
    fun `refuses a linked row with instruction_in_use naming the roles`() = runTest {
        val instructionService = mockk<InstructionService>()
        coEvery { instructionService.getInstructionById(userId, 7L) } returns
            instruction(setOf(2L, 5L)).right()
        coEvery { instructionService.deleteInstruction(userId, 7L) } returns
            DeleteInstructionError.LinkedToRoles(7L, listOf(2L, 5L)).left()
        val tool = DeleteInstructionTool(instructionService)

        val result = tool.execute(buildJsonObject { put("instruction_id", 7L) }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.OperationFailed>(result.leftOrNull())
        assertEquals("instruction_in_use", error.code)
        assertTrue(error.message.contains("2, 5"), "The refusal must name the linking roles: ${error.message}")
        coVerify(exactly = 1) { instructionService.deleteInstruction(userId, 7L) }
    }

    /**
     * Verifies that a missing or foreign row fails before the delete is attempted.
     */
    @Test
    fun `collapses not-found and not-accessible before deleting`() = runTest {
        val instructionService = mockk<InstructionService>()
        coEvery { instructionService.getInstructionById(userId, 99L) } returns
            GetInstructionError.NotFound(99L).left()
        val tool = DeleteInstructionTool(instructionService)

        val result = tool.execute(buildJsonObject { put("instruction_id", 99L) }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.NotFoundOrNotAccessible>(result.leftOrNull())
        assertEquals("Instruction 99 not found or not accessible by the current user.", error.message)
        coVerify(exactly = 0) { instructionService.deleteInstruction(any(), any()) }
    }

    /**
     * Verifies the delete failure mapping (the row can disappear between the read and the delete).
     */
    @Test
    fun `maps a delete failure to not-found-or-not-accessible`() = runTest {
        val instructionService = mockk<InstructionService>()
        coEvery { instructionService.getInstructionById(userId, 7L) } returns
            instruction(setOf(2L)).right()
        coEvery { instructionService.deleteInstruction(userId, 7L) } returns
            DeleteInstructionError.NotFound(7L).left()
        val tool = DeleteInstructionTool(instructionService)

        val result = tool.execute(buildJsonObject { put("instruction_id", 7L) }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.NotFoundOrNotAccessible>(result.leftOrNull())
        assertEquals("Instruction 7 not found or not accessible by the current user.", error.message)
    }

    /**
     * Verifies the input contract: a missing id and unknown parameters fail before any service call.
     */
    @Test
    fun `rejects invalid input without calling the service`() = runTest {
        val instructionService = mockk<InstructionService>()
        val tool = DeleteInstructionTool(instructionService)

        val missing = tool.execute(buildJsonObject { }, context())
        val missingError = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(missing.leftOrNull())
        assertTrue(missingError.message.contains("Missing required argument: instruction_id"))

        val unknown = tool.execute(
            buildJsonObject { put("instruction_id", 7L); put("force", true) },
            context()
        )
        val unknownError = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(unknown.leftOrNull())
        assertTrue(unknownError.message.contains("Unknown parameter: 'force'"))

        coVerify(exactly = 0) { instructionService.getInstructionById(any(), any()) }
        coVerify(exactly = 0) { instructionService.deleteInstruction(any(), any()) }
    }

    /**
     * Verifies that the description distinguishes deleting from unassigning and states the refusal.
     */
    @Test
    fun `describes the refusal and the difference between deleting and unassigning`() {
        val tool = DeleteInstructionTool(mockk())

        assertTrue(tool.description.contains("update_agent_role"))
        assertTrue(tool.description.contains("library"))
        assertTrue(tool.description.contains("current user"))
        // The description must warn the model that a linked row cannot be deleted.
        assertTrue(tool.description.contains("instruction_in_use"))
    }
}

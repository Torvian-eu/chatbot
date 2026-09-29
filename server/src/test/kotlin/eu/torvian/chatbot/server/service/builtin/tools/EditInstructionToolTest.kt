package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.api.instruction.UpdateInstructionRequest
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.core.InstructionService
import eu.torvian.chatbot.server.service.core.error.instruction.GetInstructionError
import eu.torvian.chatbot.server.service.core.error.instruction.UpdateInstructionError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [EditInstructionTool].
 *
 * Covers the message-only write (the captured request carries the row's own kind, label and
 * kind-specific data), the batch semantics inherited from the worker `edit_file` tool (all
 * non-overlapping occurrences, original-text matching, deterministic overlap resolution, a no-match
 * spec failing the whole call), the refusal of a generated-message row, the report contents
 * including the propagation note and the diff, the byte cap, and every input and service failure.
 */
class EditInstructionToolTest {

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

    /**
     * Creates the addressed row fixture.
     *
     * @param id Instruction row id.
     * @param type Instruction kind key.
     * @param name Instruction label.
     * @param message Stored text the batch is applied to.
     * @param linkedRoleIds Ids of the roles that link the row.
     * @return An instruction row suitable for edit assertions.
     */
    private fun instruction(
        id: Long = 7L,
        type: String = AgentInstructionTypes.MAIN,
        name: String = "Project rules",
        message: String = "Follow the project rules.",
        linkedRoleIds: Set<Long> = setOf(2L, 5L)
    ) = AgentInstructionDto(
        id = id,
        type = type,
        name = name,
        message = message,
        custom = buildJsonObject { put("modelId", 3L) },
        linkedRoleIds = linkedRoleIds
    )

    /**
     * Builds the `edits` input array from `oldText` to `newText` pairs.
     *
     * @param pairs Replacement specs in caller order.
     * @return The JSON array accepted by the tool's `edits` property.
     */
    private fun edits(vararg pairs: Pair<String, String>): JsonArray = buildJsonArray {
        pairs.forEach { (oldText, newText) ->
            add(buildJsonObject { put("oldText", oldText); put("newText", newText) })
        }
    }

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
     * Verifies that only the message changes: the request carries the row's own kind, label and
     * stored custom data, and the report names the operation, the linked roles and the diff.
     */
    @Test
    fun `writes the edited message and leaves kind label and custom untouched`() = runTest {
        val instructionService = mockk<InstructionService>()
        val request = slot<UpdateInstructionRequest>()
        coEvery { instructionService.getInstructionById(userId, 7L) } returns instruction().right()
        coEvery { instructionService.updateInstruction(userId, capture(request)) } returns
            instruction(message = "Follow the house rules.").right()
        val tool = EditInstructionTool(instructionService)

        val output = assertSuccess(
            tool.execute(
                buildJsonObject {
                    put("instruction_id", 7L)
                    put("edits", edits("project" to "house"))
                },
                context()
            )
        )

        assertEquals("Follow the house rules.", request.captured.message)
        assertEquals(AgentInstructionTypes.MAIN, request.captured.type)
        assertEquals("Project rules", request.captured.name)
        assertEquals(3L, request.captured.custom?.get("modelId")?.jsonPrimitive?.long)
        assertEquals(7L, request.captured.id)

        assertTrue(output.contains("Edited instruction 'Project rules' (id: 7, type: main):"))
        assertTrue(
            output.contains(
                "This instruction is shared: the change applies to every linked agent role (ids: 2, 5)."
            )
        )
        assertTrue(output.contains("- requested edit specs: 1"))
        assertTrue(output.contains("- matched occurrences: 1"))
        assertTrue(output.contains("- applied occurrences: 1"))
        assertTrue(output.contains("- rejected occurrences: 0"))
        assertTrue(output.contains("- Follow the project rules."))
        assertTrue(output.contains("+ Follow the house rules."))
    }

    /**
     * Verifies that several specs are applied in one call, that every non-overlapping occurrence of
     * a spec is replaced, and that matching uses the original text so caller order is irrelevant.
     */
    @Test
    fun `applies a multi-spec batch against the original message`() = runTest {
        val instructionService = mockk<InstructionService>()
        val request = slot<UpdateInstructionRequest>()
        coEvery { instructionService.getInstructionById(userId, 7L) } returns
            instruction(message = "alpha beta alpha").right()
        coEvery { instructionService.updateInstruction(userId, capture(request)) } returns
            instruction(message = "one two one").right()
        val tool = EditInstructionTool(instructionService)

        // Reversed nesting order proves matching is not sequential: the second spec operates on the
        // text the first spec did not touch.
        val output = assertSuccess(
            tool.execute(
                buildJsonObject {
                    put("instruction_id", 7L)
                    put("edits", edits("alpha" to "one", "beta" to "two"))
                },
                context()
            )
        )

        assertEquals("one two one", request.captured.message)
        assertTrue(output.contains("- matched occurrences: 3"), "all occurrences are matched")
        assertTrue(output.contains("- applied occurrences: 3"))
    }

    /**
     * Verifies that an unmatched `oldText` abandons the whole batch, naming the offending index,
     * without writing anything.
     */
    @Test
    fun `fails the batch when an oldText matches nothing`() = runTest {
        val instructionService = mockk<InstructionService>()
        coEvery { instructionService.getInstructionById(userId, 7L) } returns instruction().right()
        val tool = EditInstructionTool(instructionService)

        val result = tool.execute(
            buildJsonObject {
                put("instruction_id", 7L)
                put("edits", edits("project" to "house", "nonexistent text" to "x"))
            },
            context()
        )

        val error = assertIs<ServerBuiltInToolHandlerError.OperationFailed>(result.leftOrNull())
        assertEquals("old_text_not_found", error.code)
        assertTrue(error.message.contains("Edit at index 1 (0-based)"))
        coVerify(exactly = 0) { instructionService.updateInstruction(any(), any()) }
    }

    /**
     * Verifies that overlapping occurrences are resolved by the documented priority (longer span,
     * then lower edit index, then earlier start) and that the losing occurrence is reported.
     */
    @Test
    fun `resolves overlapping occurrences deterministically and reports the losers`() = runTest {
        val instructionService = mockk<InstructionService>()
        val request = slot<UpdateInstructionRequest>()
        coEvery { instructionService.getInstructionById(userId, 7L) } returns
            instruction(message = "abcdef").right()
        coEvery { instructionService.updateInstruction(userId, capture(request)) } returns
            instruction(message = "Xef").right()
        val tool = EditInstructionTool(instructionService)

        // Equal-length spans: the lower edit index wins, so only the first spec is applied.
        val output = assertSuccess(
            tool.execute(
                buildJsonObject {
                    put("instruction_id", 7L)
                    put("edits", edits("abcd" to "X", "cdef" to "Y"))
                },
                context()
            )
        )

        assertEquals("Xef", request.captured.message)
        assertTrue(output.contains("- matched occurrences: 2"))
        assertTrue(output.contains("- applied occurrences: 1"))
        assertTrue(output.contains("- rejected occurrences: 1"))
        assertTrue(output.contains("Rejected occurrences (overlapping, lower priority):"))
        assertTrue(output.contains("edit spec index 1 (0-based)"))
    }

    /**
     * Verifies the no-op path: a batch that matched only replacements with identical text reports no
     * changes rather than an empty diff body.
     */
    @Test
    fun `reports no changes for a no-op replacement`() = runTest {
        val instructionService = mockk<InstructionService>()
        coEvery { instructionService.getInstructionById(userId, 7L) } returns instruction().right()
        coEvery { instructionService.updateInstruction(userId, any()) } returns instruction().right()
        val tool = EditInstructionTool(instructionService)

        val output = assertSuccess(
            tool.execute(
                buildJsonObject {
                    put("instruction_id", 7L)
                    put("edits", edits("project" to "project"))
                },
                context()
            )
        )

        assertTrue(output.contains("(no changes)"))
    }

    /**
     * Verifies the propagation note for a row a single role links: the change still reaches that
     * role, and the row is not called shared (the derived flag is true only for two or more roles).
     */
    @Test
    fun `names the single linked role without calling the row shared`() = runTest {
        val instructionService = mockk<InstructionService>()
        coEvery { instructionService.getInstructionById(userId, 7L) } returns
            instruction(linkedRoleIds = setOf(2L)).right()
        coEvery { instructionService.updateInstruction(userId, any()) } returns instruction().right()
        val tool = EditInstructionTool(instructionService)

        val output = assertSuccess(
            tool.execute(
                buildJsonObject { put("instruction_id", 7L); put("edits", edits("project" to "house")) },
                context()
            )
        )

        assertTrue(output.contains("The change applies to every linked agent role (ids: 2)."))
        assertTrue(!output.contains("shared"))
    }

    /**
     * Verifies the propagation note for a row no role links.
     */
    @Test
    fun `reports that no role links the row`() = runTest {
        val instructionService = mockk<InstructionService>()
        coEvery { instructionService.getInstructionById(userId, 7L) } returns
            instruction(linkedRoleIds = emptySet()).right()
        coEvery { instructionService.updateInstruction(userId, any()) } returns instruction().right()
        val tool = EditInstructionTool(instructionService)

        val output = assertSuccess(
            tool.execute(
                buildJsonObject { put("instruction_id", 7L); put("edits", edits("project" to "house")) },
                context()
            )
        )

        assertTrue(output.contains("No agent role links this instruction yet."))
    }

    /**
     * Verifies that a generated-message row is refused before any write, with the reason stating
     * that its text is generated per linked role.
     */
    @Test
    fun `refuses to edit a generated-message row`() = runTest {
        val instructionService = mockk<InstructionService>()
        coEvery { instructionService.getInstructionById(userId, 9L) } returns
            instruction(
                id = 9L,
                type = AgentInstructionTypes.SPAWNABLE_AGENTS,
                name = "Spawn info",
                message = "",
                linkedRoleIds = setOf(2L)
            ).right()
        val tool = EditInstructionTool(instructionService)

        val result = tool.execute(
            buildJsonObject { put("instruction_id", 9L); put("edits", edits("nothing" to "x")) },
            context()
        )

        val error = assertIs<ServerBuiltInToolHandlerError.OperationFailed>(result.leftOrNull())
        assertEquals("instruction_message_not_editable", error.code)
        assertTrue(error.message.contains("generated for each linked agent role"))
        coVerify(exactly = 0) { instructionService.updateInstruction(any(), any()) }
    }

    /**
     * Verifies the input contract: malformed batches, a missing id, unknown parameters and a
     * malformed id fail before the row is read.
     */
    @Test
    fun `rejects malformed input without reading the row`() = runTest {
        val instructionService = mockk<InstructionService>()
        val tool = EditInstructionTool(instructionService)

        val blankOldText = tool.execute(
            buildJsonObject { put("instruction_id", 7L); put("edits", edits("   " to "x")) },
            context()
        )
        val blankError = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(blankOldText.leftOrNull())
        assertTrue(blankError.message.contains("empty or whitespace-only 'oldText'"))

        val missingNewText = tool.execute(
            buildJsonObject {
                put("instruction_id", 7L)
                put("edits", buildJsonArray { add(buildJsonObject { put("oldText", "project") }) })
            },
            context()
        )
        val missingNewError =
            assertIs<ServerBuiltInToolHandlerError.InvalidInput>(missingNewText.leftOrNull())
        assertTrue(missingNewError.message.contains("Edit at index 0 missing 'newText'"))

        val emptyBatch = tool.execute(
            buildJsonObject { put("instruction_id", 7L); put("edits", buildJsonArray { }) },
            context()
        )
        val emptyError = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(emptyBatch.leftOrNull())
        assertTrue(emptyError.message.contains("At least one edit is required"))

        val nonArrayBatch = tool.execute(
            buildJsonObject { put("instruction_id", 7L); put("edits", "project") },
            context()
        )
        val nonArrayError = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(nonArrayBatch.leftOrNull())
        assertTrue(nonArrayError.message.contains("must be an array of {oldText, newText} objects"))

        val missingId = tool.execute(
            buildJsonObject { put("edits", edits("project" to "house")) },
            context()
        )
        val missingIdError = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(missingId.leftOrNull())
        assertTrue(missingIdError.message.contains("Missing required argument: instruction_id"))

        val unknown = tool.execute(
            buildJsonObject {
                put("instruction_id", 7L)
                put("edits", edits("project" to "house"))
                put("replace_all", true)
            },
            context()
        )
        val unknownError = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(unknown.leftOrNull())
        assertTrue(unknownError.message.contains("Unknown parameter: 'replace_all'"))

        coVerify(exactly = 0) { instructionService.getInstructionById(any(), any()) }
        coVerify(exactly = 0) { instructionService.updateInstruction(any(), any()) }
    }

    /**
     * Verifies the collapsed not-found wording for a missing or foreign row, with no write.
     */
    @Test
    fun `collapses not-found and not-accessible into one message`() = runTest {
        val instructionService = mockk<InstructionService>()
        coEvery { instructionService.getInstructionById(userId, 99L) } returns
            GetInstructionError.NotFound(99L).left()
        val tool = EditInstructionTool(instructionService)

        val result = tool.execute(
            buildJsonObject { put("instruction_id", 99L); put("edits", edits("project" to "house")) },
            context()
        )

        val error = assertIs<ServerBuiltInToolHandlerError.NotFoundOrNotAccessible>(result.leftOrNull())
        assertEquals("Instruction 99 not found or not accessible by the current user.", error.message)
        coVerify(exactly = 0) { instructionService.updateInstruction(any(), any()) }
    }

    /**
     * Verifies the service failure mappings: a row that disappeared before the write, and content the
     * service refuses (for example a `model_specific` row whose stored custom text is unusable).
     */
    @Test
    fun `maps service failures to readable handler errors`() = runTest {
        val cases = mapOf(
            UpdateInstructionError.NotFound(7L) to "not_found",
            UpdateInstructionError.ValidationFailed("A 'model_specific' instruction must include custom.modelId") to
                "instruction_validation_failed"
        )
        cases.forEach { (serviceError, expectedCode) ->
            val instructionService = mockk<InstructionService>()
            coEvery { instructionService.getInstructionById(userId, 7L) } returns instruction().right()
            coEvery { instructionService.updateInstruction(userId, any()) } returns serviceError.left()
            val tool = EditInstructionTool(instructionService)

            val result = tool.execute(
                buildJsonObject { put("instruction_id", 7L); put("edits", edits("project" to "house")) },
                context()
            )

            when (expectedCode) {
                "not_found" -> {
                    val error = assertIs<ServerBuiltInToolHandlerError.NotFoundOrNotAccessible>(
                        result.leftOrNull()
                    )
                    assertEquals(
                        "Instruction 7 not found or not accessible by the current user.",
                        error.message
                    )
                }

                else -> {
                    val error = assertIs<ServerBuiltInToolHandlerError.OperationFailed>(result.leftOrNull())
                    assertEquals(expectedCode, error.code)
                }
            }
        }
    }

    /**
     * Verifies the byte cap: a long diff is truncated at a UTF-8 boundary, the notice points at
     * `read_instruction`, and the write still persists the complete edited message.
     */
    @Test
    fun `truncates a long report and still writes the complete message`() = runTest {
        val originalMessage = (1..300).joinToString("\n") { "original line $it with padding text" }
        val replacement = (1..300).joinToString("\n") { "replacement line $it with padding text" }
        val instructionService = mockk<InstructionService>()
        val request = slot<UpdateInstructionRequest>()
        coEvery { instructionService.getInstructionById(userId, 7L) } returns
            instruction(message = originalMessage).right()
        coEvery { instructionService.updateInstruction(userId, capture(request)) } returns
            instruction(message = replacement).right()
        val tool = EditInstructionTool(instructionService)

        val output = assertSuccess(
            tool.execute(
                buildJsonObject {
                    put("instruction_id", 7L)
                    put("edits", edits(originalMessage to replacement))
                },
                context()
            )
        )

        assertTrue(output.contains("Output truncated at 5000 bytes"), "the cap is reported")
        assertTrue(output.contains("read_instruction"))
        assertTrue(output.toByteArray(Charsets.UTF_8).size <= 5_000)
        // Truncation is a reporting concern only: the row keeps the complete edited text.
        assertEquals(replacement, request.captured.message)
    }
}

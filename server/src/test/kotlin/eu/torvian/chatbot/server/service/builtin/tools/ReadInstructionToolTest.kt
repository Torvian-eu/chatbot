package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.core.InstructionService
import eu.torvian.chatbot.server.service.core.error.instruction.GetInstructionError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ReadInstructionTool].
 *
 * Pins the encoded row shape (including the explicit `null` custom and the empty role list that only
 * survive while the server's codec keeps `encodeDefaults = true`), covers the stored-message rule of
 * the generated-message kind, the not-found/not-accessible collapse, and strict input validation.
 */
class ReadInstructionToolTest {

    /** Codec configured like the server's shared binding, so the pinned payloads match production. */
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
     * Verifies that the whole reported row is returned and that the optional properties stay present
     * in the encoded result.
     */
    @Test
    fun `returns the full row and pins the encoded payload`() = runTest {
        val instructionService = mockk<InstructionService>()
        coEvery { instructionService.getInstructionById(userId, 7L) } returns AgentInstructionDto(
            id = 7L,
            type = AgentInstructionTypes.MAIN,
            name = "Project rules",
            message = "Follow the project rules.",
            linkedRoleIds = emptySet()
        ).right()
        val tool = ReadInstructionTool(instructionService, json)

        val output = assertSuccess(tool.execute(buildJsonObject { put("instruction_id", 7L) }, context()))

        // Pinned string: the explicit `custom: null` and the empty role list are part of the contract
        // only while the shared codec keeps `encodeDefaults = true`.
        assertEquals(
            """{"id":7,"type":"main","name":"Project rules","message":"Follow the project rules.",""" +
                """"custom":null,"linkedRoleIds":[]}""",
            output
        )
    }

    /**
     * Verifies that a `model_specific` row keeps its stored model target and reports the roles that
     * link it.
     */
    @Test
    fun `reports the stored custom data and the linked role ids`() = runTest {
        val instructionService = mockk<InstructionService>()
        coEvery { instructionService.getInstructionById(userId, 8L) } returns AgentInstructionDto(
            id = 8L,
            type = AgentInstructionTypes.MODEL_SPECIFIC,
            name = "GPT only",
            message = "Prefer short answers.",
            custom = buildJsonObject { put("modelId", 3L) },
            linkedRoleIds = setOf(2L, 5L)
        ).right()
        val tool = ReadInstructionTool(instructionService, json)

        val output = assertSuccess(tool.execute(buildJsonObject { put("instruction_id", 8L) }, context()))
        val row = json.parseToJsonElement(output).jsonObject

        assertEquals(3L, row.getValue("custom").jsonObject.getValue("modelId").jsonPrimitive.long)
        assertEquals(
            listOf(2L, 5L),
            row.getValue("linkedRoleIds").jsonArray.map { it.jsonPrimitive.long }
        )
    }

    /**
     * Verifies that a generated-message row reports its empty stored message, which is what the
     * description promises.
     */
    @Test
    fun `reports an empty message for the generated-message kind`() = runTest {
        val instructionService = mockk<InstructionService>()
        coEvery { instructionService.getInstructionById(userId, 9L) } returns AgentInstructionDto(
            id = 9L,
            type = AgentInstructionTypes.SPAWNABLE_AGENTS,
            name = "Spawn info",
            message = "",
            linkedRoleIds = setOf(2L)
        ).right()
        val tool = ReadInstructionTool(instructionService, json)

        val output = assertSuccess(tool.execute(buildJsonObject { put("instruction_id", 9L) }, context()))

        assertTrue(output.contains(""""message":""""))
    }

    /**
     * Verifies the collapsed not-found wording for a missing or foreign row.
     */
    @Test
    fun `collapses not-found and not-accessible into one message`() = runTest {
        val instructionService = mockk<InstructionService>()
        coEvery { instructionService.getInstructionById(userId, 99L) } returns
            GetInstructionError.NotFound(99L).left()
        val tool = ReadInstructionTool(instructionService, json)

        val result = tool.execute(buildJsonObject { put("instruction_id", 99L) }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.NotFoundOrNotAccessible>(result.leftOrNull())
        assertEquals(
            "Instruction 99 not found or not accessible by the current user.",
            error.message
        )
    }

    /**
     * Verifies the input contract: a missing id, a malformed id and unknown parameters all fail
     * before the service is called.
     */
    @Test
    fun `rejects invalid input without calling the service`() = runTest {
        val instructionService = mockk<InstructionService>()
        val tool = ReadInstructionTool(instructionService, json)

        val missing = tool.execute(buildJsonObject { }, context())
        val missingError = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(missing.leftOrNull())
        assertTrue(missingError.message.contains("Missing required argument: instruction_id"))

        val malformed = tool.execute(buildJsonObject { put("instruction_id", "seven") }, context())
        assertIs<ServerBuiltInToolHandlerError.InvalidInput>(malformed.leftOrNull())

        val unknown = tool.execute(
            buildJsonObject { put("instruction_id", 7L); put("include_message", true) },
            context()
        )
        val unknownError = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(unknown.leftOrNull())
        assertTrue(unknownError.message.contains("Unknown parameter: 'include_message'"))

        coVerify(exactly = 0) { instructionService.getInstructionById(any(), any()) }
    }

    /**
     * Verifies that the description states the empty stored message of the generated-message kind.
     */
    @Test
    fun `describes the empty message of the generated-message kind`() {
        val tool = ReadInstructionTool(mockk(), json)

        assertTrue(tool.description.contains("spawnable_agents"))
        assertTrue(tool.description.contains("empty"))
        assertTrue(tool.description.contains("current user"))
    }
}

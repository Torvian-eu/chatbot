package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.api.instruction.CreateInstructionRequest
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.core.InstructionService
import eu.torvian.chatbot.server.service.core.error.instruction.CreateInstructionError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
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
 * Unit tests for [CreateInstructionTool].
 *
 * Covers the parsed request (required `type`/`name`, optional `message`/`custom`), the strict
 * object parsing of `custom`, the full-JSON echo with the server-generated id, and the mapping of
 * every service failure.
 */
class CreateInstructionToolTest {

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
     * Verifies the created row echo (generated id, empty role list) and that absent optional
     * arguments reach the request as nulls.
     */
    @Test
    fun `creates a row with the parsed content and returns it with an empty role list`() = runTest {
        val instructionService = mockk<InstructionService>()
        coEvery { instructionService.createInstruction(userId, any()) } returns AgentInstructionDto(
            id = 12L,
            type = AgentInstructionTypes.ROLE,
            name = "Reviewer",
            message = "You review code.",
            linkedRoleIds = emptySet()
        ).right()
        val tool = CreateInstructionTool(instructionService, json)

        val output = assertSuccess(
            tool.execute(
                buildJsonObject { put("type", "role"); put("name", "Reviewer"); put("message", "You review code.") },
                context()
            )
        )

        // Pinned string: the explicit null custom and the empty role list are part of the contract
        // only while the shared codec keeps `encodeDefaults = true`.
        assertEquals(
            """{"id":12,"type":"role","name":"Reviewer","message":"You review code.",""" +
                """"custom":null,"linkedRoleIds":[]}""",
            output
        )

        coVerify(exactly = 1) {
            instructionService.createInstruction(
                userId,
                match<CreateInstructionRequest> { request ->
                    request.type == AgentInstructionTypes.ROLE &&
                        request.name == "Reviewer" &&
                        request.message == "You review code." &&
                        request.custom == null
                }
            )
        }
    }

    /**
     * Verifies that a `custom` object is forwarded as-is and that the resulting row echoes it.
     */
    @Test
    fun `forwards a custom object to the service`() = runTest {
        val instructionService = mockk<InstructionService>()
        coEvery { instructionService.createInstruction(userId, any()) } returns AgentInstructionDto(
            id = 13L,
            type = AgentInstructionTypes.MODEL_SPECIFIC,
            name = "GPT only",
            message = "Short answers.",
            custom = buildJsonObject { put("modelId", 3L) },
            linkedRoleIds = emptySet()
        ).right()
        val tool = CreateInstructionTool(instructionService, json)

        val output = assertSuccess(
            tool.execute(
                buildJsonObject {
                    put("type", "model_specific")
                    put("name", "GPT only")
                    put("message", "Short answers.")
                    put("custom", buildJsonObject { put("modelId", 3L) })
                },
                context()
            )
        )

        val row = json.parseToJsonElement(output).jsonObject
        assertEquals(3L, row.getValue("custom").jsonObject.getValue("modelId").jsonPrimitive.long)
        assertEquals(emptyList(), row.getValue("linkedRoleIds").jsonArray)

        coVerify(exactly = 1) {
            instructionService.createInstruction(
                userId,
                match<CreateInstructionRequest> { request ->
                    request.custom?.get("modelId")?.jsonPrimitive?.long == 3L
                }
            )
        }
    }

    /**
     * Verifies that a non-object `custom` and unknown parameters are rejected before the service is
     * called.
     */
    @Test
    fun `rejects a non-object custom and unknown parameters without calling the service`() = runTest {
        val instructionService = mockk<InstructionService>()
        val tool = CreateInstructionTool(instructionService, json)

        val malformed = tool.execute(
            buildJsonObject { put("type", "role"); put("name", "Reviewer"); put("custom", "modelId=3") },
            context()
        )
        val malformedError = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(malformed.leftOrNull())
        assertTrue(malformedError.message.contains("Argument 'custom' must be an object"))

        val unknown = tool.execute(
            buildJsonObject { put("type", "role"); put("name", "Reviewer"); put("summary", "x") },
            context()
        )
        val unknownError = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(unknown.leftOrNull())
        assertTrue(unknownError.message.contains("Unknown parameter: 'summary'"))

        val missing = tool.execute(buildJsonObject { put("name", "Reviewer") }, context())
        val missingError = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(missing.leftOrNull())
        assertTrue(missingError.message.contains("Missing required argument: type"))

        coVerify(exactly = 0) { instructionService.createInstruction(any(), any()) }
    }

    /**
     * Verifies every service failure mapping, including the forwarded validation reason.
     */
    @Test
    fun `maps service failures to readable handler errors`() = runTest {
        val cases = mapOf(
            CreateInstructionError.ValidationFailed("Unknown instruction type 'nope'") to
                "instruction_validation_failed",
            CreateInstructionError.OwnerInsertFailed("constraint violation") to
                "instruction_owner_insert_failed"
        )
        cases.forEach { (serviceError, expectedCode) ->
            val instructionService = mockk<InstructionService>()
            coEvery { instructionService.createInstruction(userId, any()) } returns serviceError.left()
            val tool = CreateInstructionTool(instructionService, json)

            val result = tool.execute(
                buildJsonObject { put("type", "role"); put("name", "Reviewer") },
                context()
            )

            val error = assertIs<ServerBuiltInToolHandlerError.OperationFailed>(result.leftOrNull())
            assertEquals(expectedCode, error.code)
        }
    }

    /**
     * Verifies that content rules stay the service's: a blank or over-long name is forwarded
     * untouched and its failure is reported with the service's reason.
     */
    @Test
    fun `forwards content validation to the service and reports its reason`() = runTest {
        val oversizedName = "n".repeat(256)
        val instructionService = mockk<InstructionService>()
        val request = slot<CreateInstructionRequest>()
        coEvery { instructionService.createInstruction(userId, capture(request)) } returns
            CreateInstructionError.ValidationFailed(
                "Instruction name cannot exceed 255 characters"
            ).left()
        val tool = CreateInstructionTool(instructionService, json)

        val result = tool.execute(
            buildJsonObject { put("type", "role"); put("name", oversizedName) },
            context()
        )

        assertEquals(oversizedName, request.captured.name)
        val error = assertIs<ServerBuiltInToolHandlerError.OperationFailed>(result.leftOrNull())
        assertEquals("instruction_validation_failed", error.code)
        assertTrue(error.message.contains("255 characters"))
    }

    /**
     * Verifies that the description documents the authoring rules and the assign-it-in-the-same-turn
     * workflow.
     */
    @Test
    fun `describes the authoring rules and the assignment path`() {
        val tool = CreateInstructionTool(mockk(), json)

        assertTrue(tool.description.contains("instruction_ids"))
        assertTrue(tool.description.contains("custom.modelId"))
        assertTrue(tool.description.contains("spawnable_agents"))
        assertTrue(tool.description.contains("current user"))
    }
}

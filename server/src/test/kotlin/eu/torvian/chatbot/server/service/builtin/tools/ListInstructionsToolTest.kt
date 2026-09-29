package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.core.AgentRoleService
import eu.torvian.chatbot.server.service.core.InstructionService
import eu.torvian.chatbot.server.service.core.error.agent.AgentRoleError
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ListInstructionsTool].
 *
 * Covers the lean row projection (no message, no custom data, no summary), the optional `role_id`
 * filter, the not-found/not-accessible outcome for an unknown or foreign role, the empty listing,
 * and strict rejection of unknown parameters.
 */
class ListInstructionsToolTest {

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
     * Creates a library-row fixture.
     *
     * @param id Instruction row id.
     * @param name Instruction label.
     * @param type Instruction kind key.
     * @param message Stored text, which the listing must never report.
     * @param linkedRoleIds Ids of the roles that link the row.
     * @return An instruction row suitable for listing assertions.
     */
    private fun instruction(
        id: Long = 7L,
        name: String = "Project rules",
        type: String = AgentInstructionTypes.MAIN,
        message: String = "SENSITIVE instruction text",
        linkedRoleIds: Set<Long> = setOf(2L, 5L)
    ) = AgentInstructionDto(
        id = id,
        type = type,
        name = name,
        message = message,
        custom = buildJsonObject { put("modelId", 3L) },
        linkedRoleIds = linkedRoleIds
    )

    private fun role(id: Long = 2L, name: String = "writer") =
        AgentRoleDto(id = id, name = name, modelId = null, modelSettingsId = null)

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
     * Verifies the exact row shape: only `id`, `type`, `name` and `linkedRoleIds` survive, and the
     * linked role ids are emitted in ascending order for a stable payload.
     */
    @Test
    fun `reports only identity and usage per row and pins the encoded payload`() = runTest {
        val instructionService = mockk<InstructionService>()
        coEvery { instructionService.getAllInstructionsForUser(userId) } returns
            listOf(instruction(linkedRoleIds = setOf(5L, 2L)))
        val tool = ListInstructionsTool(instructionService, mockk(), json)

        val output = assertSuccess(tool.execute(buildJsonObject { }, context()))

        // Pinned string: a codec or projection change must fail this test instead of silently
        // enlarging or shrinking the listing contract.
        assertEquals(
            """[{"id":7,"type":"main","name":"Project rules","linkedRoleIds":[2,5]}]""",
            output
        )
        val row = json.parseToJsonElement(output).jsonArray.single().jsonObject
        assertEquals(setOf("id", "type", "name", "linkedRoleIds"), row.keys)
        assertFalse(output.contains("SENSITIVE instruction text"))
        assertFalse(output.contains("message"))
        assertFalse(output.contains("custom"))
        assertFalse(output.contains("summary"))
    }

    /**
     * Verifies that the `role_id` filter keeps the rows whose linked role ids contain it, including
     * rows linked by several roles.
     */
    @Test
    fun `narrows the listing to the rows a role links`() = runTest {
        val instructionService = mockk<InstructionService>()
        coEvery { instructionService.getAllInstructionsForUser(userId) } returns listOf(
            instruction(id = 1L, name = "Shared", linkedRoleIds = setOf(2L, 5L)),
            instruction(id = 2L, name = "Other", linkedRoleIds = setOf(5L)),
            instruction(id = 3L, name = "Unassigned", linkedRoleIds = emptySet())
        )
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.getRoleById(userId, 2L) } returns role(id = 2L).right()
        val tool = ListInstructionsTool(instructionService, agentRoleService, json)

        val output = assertSuccess(tool.execute(buildJsonObject { put("role_id", 2L) }, context()))

        val rows = json.parseToJsonElement(output).jsonArray
        assertEquals(
            listOf("Shared"),
            rows.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        )
        assertEquals(listOf(1L), rows.map { it.jsonObject.getValue("id").jsonPrimitive.long })
    }

    /**
     * Verifies that a role the caller does not own fails instead of producing an empty listing, so
     * "this role has no instructions" and "there is no such role" cannot be confused.
     */
    @Test
    fun `fails for an unknown or foreign role id instead of returning an empty listing`() = runTest {
        val instructionService = mockk<InstructionService>()
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.getRoleById(userId, 99L) } returns AgentRoleError.NotFound(99L).left()
        val tool = ListInstructionsTool(instructionService, agentRoleService, json)

        val result = tool.execute(buildJsonObject { put("role_id", 99L) }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.NotFoundOrNotAccessible>(result.leftOrNull())
        assertEquals("Agent role 99 not found or not accessible by the current user.", error.message)
        coVerify(exactly = 0) { instructionService.getAllInstructionsForUser(any()) }
    }

    /**
     * Verifies that a library with no matching row still reports the bare empty array.
     */
    @Test
    fun `reports an empty array when nothing matches`() = runTest {
        val instructionService = mockk<InstructionService>()
        coEvery { instructionService.getAllInstructionsForUser(userId) } returns emptyList()
        val tool = ListInstructionsTool(instructionService, mockk(), json)

        assertEquals("[]", assertSuccess(tool.execute(buildJsonObject { }, context())))
    }

    /**
     * Verifies that the filter is ownership-checked before the library is read.
     */
    @Test
    fun `checks the filter role before reading the library`() = runTest {
        val instructionService = mockk<InstructionService>()
        coEvery { instructionService.getAllInstructionsForUser(userId) } returns emptyList()
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.getRoleById(userId, 2L) } returns role(id = 2L).right()
        val tool = ListInstructionsTool(instructionService, agentRoleService, json)

        assertSuccess(tool.execute(buildJsonObject { put("role_id", 2L) }, context()))

        coVerify(exactly = 1) { agentRoleService.getRoleById(userId, 2L) }
        coVerify(exactly = 1) { instructionService.getAllInstructionsForUser(userId) }
    }

    /**
     * Verifies the input contract: unknown parameters and a malformed `role_id` fail before any
     * service call.
     */
    @Test
    fun `rejects unknown parameters and a malformed role_id`() = runTest {
        val instructionService = mockk<InstructionService>()
        val agentRoleService = mockk<AgentRoleService>()
        val tool = ListInstructionsTool(instructionService, agentRoleService, json)

        val unknown = tool.execute(buildJsonObject { put("filter", "all") }, context())
        val unknownError = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(unknown.leftOrNull())
        assertTrue(unknownError.message.contains("Unknown parameter: 'filter'"))

        val malformed = tool.execute(buildJsonObject { put("role_id", "two") }, context())
        val malformedError = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(malformed.leftOrNull())
        assertTrue(malformedError.message.contains("Argument 'role_id' must be an integer"))

        coVerify(exactly = 0) { instructionService.getAllInstructionsForUser(any()) }
        coVerify(exactly = 0) { agentRoleService.getRoleById(any(), any()) }
    }

    /**
     * Verifies that the catalog description steers the model to the read tool and the role filter.
     */
    @Test
    fun `describes the read path and the role filter`() {
        val tool = ListInstructionsTool(mockk(), mockk(), json)

        assertTrue(tool.description.contains("read_instruction"))
        assertTrue(tool.description.contains("role_id"))
        assertTrue(tool.description.contains("current user"))
    }
}

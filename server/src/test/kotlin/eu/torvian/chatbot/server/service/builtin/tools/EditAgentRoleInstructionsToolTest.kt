package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.core.AgentRoleService
import eu.torvian.chatbot.server.service.core.error.agent.AgentRoleError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [EditAgentRoleInstructionsTool].
 *
 * Covers the edit_file-like replacement semantics (all non-overlapping occurrences across the whole
 * instruction list, original-text batch matching, deterministic conflict resolution), the
 * no-match failure, input validation, ownership-collapsed not-found, and field preservation.
 */
class EditAgentRoleInstructionsToolTest {

    private val userId = 7L

    private fun sampleRole() = AgentRoleDto(
        id = 1L,
        name = "writer",
        displayName = "Writer",
        description = "Writes code",
        modelId = 3L,
        modelSettingsId = 4L,
        tools = setOf(5L, 6L),
        spawnableAgentRoleIds = setOf(2L),
        instructions = listOf(
            AgentInstructionDto(AgentInstructionTypes.ROLE, "Role", "You are a senior Kotlin developer."),
            AgentInstructionDto(AgentInstructionTypes.CUSTOM, "Style", "Write Kotlin code.")
        )
    )

    private fun assertSuccess(result: Either<ServerBuiltInToolHandlerError, String>): String {
        assertTrue(result.isRight(), "Expected success but got: ${result.leftOrNull()}")
        return assertNotNull(result.getOrNull())
    }

    @Test
    fun `replaces all non-overlapping occurrences across all instruction messages`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        val persisted = sampleRole()
        coEvery { agentRoleService.getRoleById(userId, 1L) } returns persisted.right()
        coEvery { agentRoleService.updateRole(userId, 1L, any()) } returns persisted.right()
        val tool = EditAgentRoleInstructionsTool(agentRoleService)

        val output = assertSuccess(
            tool.execute(
                userId,
                buildJsonObject {
                    put("role_id", 1L)
                    putJsonArray("edits") {
                        add(buildJsonObject {
                            put("oldText", "Kotlin")
                            put("newText", "Kotlin 2.3")
                        })
                    }
                }
            )
        )

        // The tool returns an edit report with a summary and a unified diff, not the full role
        // JSON (the full role is available via read_agent_role).
        assertTrue(output.contains("Edited instructions in agent role 'writer' (id: 1):"))
        assertTrue(output.contains("- requested edit specs: 1"))
        assertTrue(output.contains("- matched occurrences: 2"))
        assertTrue(output.contains("- applied occurrences: 2"))
        assertTrue(output.contains("- rejected occurrences: 0"))
        assertTrue(output.contains("Instruction 0 (type=role, name=Role):"))
        assertTrue(output.contains("Instruction 1 (type=custom, name=Style):"))
        assertTrue(output.contains("- You are a senior Kotlin developer."))
        assertTrue(output.contains("+ You are a senior Kotlin 2.3 developer."))
        assertTrue(output.contains("- Write Kotlin code."))
        assertTrue(output.contains("+ Write Kotlin 2.3 code."))
        assertTrue(!output.contains("\"id\":1"))

        // The edit matched in both instructions; every other role field is carried over unchanged.
        coVerify(exactly = 1) {
            agentRoleService.updateRole(
                userId,
                1L,
                match<UpdateAgentRoleRequest> { request ->
                    request.instructions[0].message == "You are a senior Kotlin 2.3 developer." &&
                        request.instructions[1].message == "Write Kotlin 2.3 code." &&
                        request.instructions[0].name == "Role" &&
                        request.name == persisted.name &&
                        request.modelId == persisted.modelId &&
                        request.toolIds == persisted.tools &&
                        request.spawnableAgentRoleIds == persisted.spawnableAgentRoleIds
                }
            )
        }
    }

    @Test
    fun `replaces only non-overlapping occurrences of one edit spec`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        val persisted = sampleRole().copy(
            instructions = listOf(
                AgentInstructionDto(AgentInstructionTypes.CUSTOM, "Style", "aaaa")
            )
        )
        coEvery { agentRoleService.getRoleById(userId, 1L) } returns persisted.right()
        coEvery { agentRoleService.updateRole(userId, 1L, any()) } returns persisted.right()
        val tool = EditAgentRoleInstructionsTool(agentRoleService)

        val output = assertSuccess(
            tool.execute(
                userId,
                buildJsonObject {
                    put("role_id", 1L)
                    putJsonArray("edits") {
                        add(buildJsonObject {
                            put("oldText", "aa")
                            put("newText", "X")
                        })
                    }
                }
            )
        )
        assertTrue(output.contains("- matched occurrences: 2"))
        assertTrue(output.contains("- applied occurrences: 2"))

        // "aaaa" has two non-overlapping "aa" matches -> "XX".
        coVerify(exactly = 1) {
            agentRoleService.updateRole(
                userId,
                1L,
                match<UpdateAgentRoleRequest> { request ->
                    request.instructions.single().message == "XX"
                }
            )
        }
    }

    @Test
    fun `edits are matched against the original texts not prior edits`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        val persisted = sampleRole().copy(
            instructions = listOf(
                AgentInstructionDto(AgentInstructionTypes.CUSTOM, "Style", "abc")
            )
        )
        coEvery { agentRoleService.getRoleById(userId, 1L) } returns persisted.right()
        coEvery { agentRoleService.updateRole(userId, 1L, any()) } returns persisted.right()
        val tool = EditAgentRoleInstructionsTool(agentRoleService)

        // Both specs match the ORIGINAL text "abc"; they overlap, so the longer span ("abc") wins
        // deterministically regardless of caller order -> "X", not a sequential "X"/"aY" mix.
        val output = assertSuccess(
            tool.execute(
                userId,
                buildJsonObject {
                    put("role_id", 1L)
                    putJsonArray("edits") {
                        add(buildJsonObject {
                            put("oldText", "abc")
                            put("newText", "X")
                        })
                        add(buildJsonObject {
                            put("oldText", "bc")
                            put("newText", "Y")
                        })
                    }
                }
            )
        )

        // The rejected overlapping occurrence is reported, mirroring the worker edit_file report.
        assertTrue(output.contains("- requested edit specs: 2"))
        assertTrue(output.contains("- matched occurrences: 2"))
        assertTrue(output.contains("- applied occurrences: 1"))
        assertTrue(output.contains("- rejected occurrences: 1"))
        assertTrue(output.contains("Rejected occurrences (overlapping, lower priority):"))
        assertTrue(output.contains("edit spec index 1 (0-based)"))

        coVerify(exactly = 1) {
            agentRoleService.updateRole(
                userId,
                1L,
                match<UpdateAgentRoleRequest> { request ->
                    request.instructions.single().message == "X"
                }
            )
        }
    }

    @Test
    fun `fails when oldText matches nothing`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.getRoleById(userId, 1L) } returns sampleRole().right()
        val tool = EditAgentRoleInstructionsTool(agentRoleService)

        val result = tool.execute(
            userId,
            buildJsonObject {
                put("role_id", 1L)
                putJsonArray("edits") {
                    add(buildJsonObject {
                        put("oldText", "no such text")
                        put("newText", "x")
                    })
                }
            }
        )

        val error = assertIs<ServerBuiltInToolHandlerError.OperationFailed>(result.leftOrNull())
        assertEquals("old_text_not_found", error.code)
        assertTrue(error.message.contains("Edit at index 0"))
        coVerify(exactly = 0) { agentRoleService.updateRole(any(), any(), any()) }
    }

    @Test
    fun `requires the edits parameter`() = runTest {
        val tool = EditAgentRoleInstructionsTool(mockk())

        val result = tool.execute(userId, buildJsonObject { put("role_id", 1L) })

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Missing required argument: edits"))
    }

    @Test
    fun `rejects an empty edits array`() = runTest {
        val tool = EditAgentRoleInstructionsTool(mockk())

        val result = tool.execute(
            userId,
            buildJsonObject {
                put("role_id", 1L)
                putJsonArray("edits") { }
            }
        )

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("At least one edit is required"))
    }

    @Test
    fun `rejects an edit item missing oldText`() = runTest {
        val tool = EditAgentRoleInstructionsTool(mockk())

        val result = tool.execute(
            userId,
            buildJsonObject {
                put("role_id", 1L)
                putJsonArray("edits") {
                    add(buildJsonObject { put("newText", "x") })
                }
            }
        )

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Edit at index 0 missing 'oldText'"))
    }

    @Test
    fun `rejects a blank oldText`() = runTest {
        val tool = EditAgentRoleInstructionsTool(mockk())

        val result = tool.execute(
            userId,
            buildJsonObject {
                put("role_id", 1L)
                putJsonArray("edits") {
                    add(buildJsonObject {
                        put("oldText", "   ")
                        put("newText", "x")
                    })
                }
            }
        )

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("empty or whitespace-only 'oldText'"))
    }

    @Test
    fun `rejects unknown parameters without touching the persisted role`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        val tool = EditAgentRoleInstructionsTool(agentRoleService)

        val result = tool.execute(
            userId,
            buildJsonObject {
                put("role_id", 1L)
                putJsonArray("edits") {
                    add(buildJsonObject {
                        put("oldText", "a")
                        put("newText", "b")
                    })
                }
                put("dry_run", true)
            }
        )

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Unknown parameter: 'dry_run'"))
        coVerify(exactly = 0) { agentRoleService.getRoleById(any(), any()) }
        coVerify(exactly = 0) { agentRoleService.updateRole(any(), any(), any()) }
    }

    @Test
    fun `collapses not-found and not-accessible`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.getRoleById(userId, 99L) } returns AgentRoleError.NotFound(99L).left()
        val tool = EditAgentRoleInstructionsTool(agentRoleService)

        val result = tool.execute(
            userId,
            buildJsonObject {
                put("role_id", 99L)
                putJsonArray("edits") {
                    add(buildJsonObject {
                        put("oldText", "a")
                        put("newText", "b")
                    })
                }
            }
        )

        val error = assertIs<ServerBuiltInToolHandlerError.NotFoundOrNotAccessible>(result.leftOrNull())
        assertTrue(error.message.contains("not found or not accessible by the current user"))
        coVerify(exactly = 0) { agentRoleService.updateRole(any(), any(), any()) }
    }

    @Test
    fun `truncates an oversized diff report at the byte cap on a UTF-8 boundary`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        // "é" is two UTF-8 bytes, so a single 3,000-character line is 6,000 bytes; the diff body
        // carries the whole old and new line, far exceeding the 5,000-byte report cap. This is the
        // long-instruction-list case these tools exist for, so the truncation path must be pinned.
        val longText = "é".repeat(3_000)
        val persisted = sampleRole().copy(
            instructions = listOf(
                AgentInstructionDto(AgentInstructionTypes.CUSTOM, "Style", longText)
            )
        )
        coEvery { agentRoleService.getRoleById(userId, 1L) } returns persisted.right()
        coEvery { agentRoleService.updateRole(userId, 1L, any()) } returns persisted.right()
        val tool = EditAgentRoleInstructionsTool(agentRoleService)

        val output = assertSuccess(
            tool.execute(
                userId,
                buildJsonObject {
                    put("role_id", 1L)
                    putJsonArray("edits") {
                        add(buildJsonObject {
                            put("oldText", "éé")
                            put("newText", "ü")
                        })
                    }
                }
            )
        )

        // The truncation notice is present, the report never exceeds the byte cap, and the
        // UTF-8-safe cut leaves no replacement character behind.
        assertTrue(output.contains("[Output truncated at 5000 bytes."))
        assertTrue(output.toByteArray(Charsets.UTF_8).size <= 5_000)
        assertTrue(!output.contains('\uFFFD'))
    }

    @Test
    fun `renders a no-changes notice when an edit matches but produces no change`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.getRoleById(userId, 1L) } returns sampleRole().right()
        coEvery { agentRoleService.updateRole(userId, 1L, any()) } returns sampleRole().right()
        val tool = EditAgentRoleInstructionsTool(agentRoleService)

        // oldText == newText still counts as a matched/applied occurrence, but every message stays
        // identical, so the report shows the "(no changes)" branch instead of an empty diff.
        val output = assertSuccess(
            tool.execute(
                userId,
                buildJsonObject {
                    put("role_id", 1L)
                    putJsonArray("edits") {
                        add(buildJsonObject {
                            put("oldText", "Kotlin")
                            put("newText", "Kotlin")
                        })
                    }
                }
            )
        )

        assertTrue(output.contains("- matched occurrences: 2"))
        assertTrue(output.contains("- applied occurrences: 2"))
        assertTrue(output.contains("(no changes)"))
    }
}

package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.common.models.tool.OperatorToolDefinition
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for the agent-role tool grouping ([buildAgentToolSections]).
 *
 * The grouping decides the header order and the header text the user sees, so the covered rules are
 * the bucket mapping and order, the omission of empty groups, the worker/MCP-server sub-grouping with
 * its fallback labels, and the deterministic ordering at all three levels.
 */
class AgentToolSectionsTest {

    private val now = Instant.fromEpochSeconds(0)

    private fun operatorTool(id: Long, name: String) = OperatorToolDefinition(
        id = id,
        name = name,
        description = "",
        config = buildJsonObject { },
        inputSchema = buildJsonObject { },
        isEnabled = true,
        createdAt = now,
        updatedAt = now,
        userId = 1L
    )

    private fun serverTool(id: Long, name: String) = ServerBuiltInToolDefinition(
        id = id,
        name = name,
        description = "",
        config = buildJsonObject { },
        inputSchema = buildJsonObject { },
        isEnabled = true,
        createdAt = now,
        updatedAt = now,
        userId = 1L,
        builtInToolName = name
    )

    private fun workerTool(id: Long, name: String, workerId: Long) = BuiltInWorkerToolDefinition(
        id = id,
        name = name,
        description = "",
        config = buildJsonObject { },
        inputSchema = buildJsonObject { },
        isEnabled = true,
        createdAt = now,
        updatedAt = now,
        workerId = workerId,
        builtInToolName = name
    )

    private fun mcpTool(id: Long, name: String, serverId: Long) = LocalMCPToolDefinition(
        id = id,
        name = name,
        description = "",
        config = buildJsonObject { },
        inputSchema = buildJsonObject { },
        isEnabled = true,
        createdAt = now,
        updatedAt = now,
        serverId = serverId,
        mcpToolName = name
    )

    @Test
    fun `tool types map to their origin bucket`() {
        assertEquals(AgentToolBucket.OPERATOR, AgentToolBucket.of(ToolType.OPERATOR))
        assertEquals(AgentToolBucket.BUILTIN_SERVER, AgentToolBucket.of(ToolType.BUILTIN_SERVER))
        assertEquals(AgentToolBucket.BUILTIN_WORKER, AgentToolBucket.of(ToolType.BUILTIN_WORKER))
        assertEquals(AgentToolBucket.MCP, AgentToolBucket.of(ToolType.MCP_LOCAL))
        // MCP_REMOTE has no ToolDefinition subclass, so its mapping is only reachable here.
        assertEquals(AgentToolBucket.MCP, AgentToolBucket.of(ToolType.MCP_REMOTE))
    }

    @Test
    fun `bucket labels are the four sentence-case headers in display order`() {
        assertEquals(
            listOf(
                "Operator built-in tools",
                "Server built-in tools",
                "Worker built-in tools",
                "MCP tools"
            ),
            AgentToolBucket.entries.map { it.displayLabel }
        )
    }

    @Test
    fun `sections follow the bucket order regardless of input order`() {
        val tools = listOf(
            mcpTool(4, "mcp", serverId = 20),
            workerTool(3, "worker", workerId = 10),
            serverTool(2, "server"),
            operatorTool(1, "operator")
        )

        val sections = buildAgentToolSections(tools)

        assertEquals(AgentToolBucket.entries, sections.map { it.bucket })
        assertEquals(AgentToolBucket.entries.map { it.displayLabel }, sections.map { it.title })
    }

    @Test
    fun `a single origin still yields that one section with its title`() {
        val sections = buildAgentToolSections(listOf(mcpTool(1, "fs_read", serverId = 20)))

        assertEquals(1, sections.size)
        assertEquals(AgentToolBucket.MCP, sections.single().bucket)
        assertEquals("MCP tools", sections.single().title)
    }

    @Test
    fun `flat buckets keep their tools at bucket level name-sorted case-insensitively`() {
        val tools = listOf(
            operatorTool(2, "alpha"),
            operatorTool(5, "Alpha"),
            operatorTool(1, "zeta"),
            serverTool(6, "list_agent_roles")
        )

        val sections = buildAgentToolSections(tools)
        val operatorSection = sections.single { it.bucket == AgentToolBucket.OPERATOR }
        val serverSection = sections.single { it.bucket == AgentToolBucket.BUILTIN_SERVER }

        assertTrue(operatorSection.subGroups.isEmpty())
        // Equal names (differing only in case) fall back to the id, so the order never depends on the
        // order the tool stream arrived in.
        assertEquals(listOf(2L, 5L, 1L), operatorSection.tools.map { it.id })
        assertTrue(serverSection.subGroups.isEmpty())
        assertEquals(listOf(6L), serverSection.tools.map { it.id })
    }

    @Test
    fun `worker tools are split into one sub-group per worker labelled with its display name`() {
        val tools = listOf(
            workerTool(1, "read_text_file", workerId = 10),
            workerTool(2, "write_text_file", workerId = 10),
            workerTool(3, "run_test", workerId = 11)
        )

        val section = buildAgentToolSections(
            tools = tools,
            workerDisplayNamesById = mapOf(10L to "Worker A", 11L to "Worker B")
        ).single()

        assertEquals(listOf("Worker A", "Worker B"), section.subGroups.map { it.title })
        assertEquals(listOf(1L, 2L), section.subGroups[0].tools.map { it.id })
        assertEquals(listOf(3L), section.subGroups[1].tools.map { it.id })
        assertTrue(section.tools.isEmpty())
    }

    @Test
    fun `an unknown or blank worker name falls back to the id-based label`() {
        val tools = listOf(
            workerTool(1, "a", workerId = 7),
            workerTool(2, "b", workerId = 8)
        )

        val section = buildAgentToolSections(
            tools = tools,
            workerDisplayNamesById = mapOf(8L to "   ")
        ).single()

        assertEquals(listOf("Worker #7", "Worker #8"), section.subGroups.map { it.title })
    }

    @Test
    fun `mcp tools are split per server and fall back to the id-based label`() {
        val tools = listOf(
            mcpTool(1, "fs_list", serverId = 20),
            mcpTool(2, "fs_read", serverId = 21)
        )

        val section = buildAgentToolSections(
            tools = tools,
            mcpServerNamesById = mapOf(20L to "Files")
        ).single()

        assertEquals(listOf("Files", "MCP server #21"), section.subGroups.map { it.title })
        assertEquals(listOf(1L), section.subGroups[0].tools.map { it.id })
        assertEquals(listOf(2L), section.subGroups[1].tools.map { it.id })
    }

    @Test
    fun `origins sharing a display name stay separate groups ordered by id`() {
        val tools = listOf(
            workerTool(1, "a", workerId = 30),
            workerTool(2, "b", workerId = 10)
        )

        val section = buildAgentToolSections(
            tools = tools,
            workerDisplayNamesById = mapOf(10L to "shared", 30L to "shared")
        ).single()

        assertEquals(listOf("shared", "shared"), section.subGroups.map { it.title })
        assertEquals(listOf(2L), section.subGroups[0].tools.map { it.id })
        assertEquals(listOf(1L), section.subGroups[1].tools.map { it.id })
    }

    @Test
    fun `sub-groups sort by the displayed label case-insensitively`() {
        val tools = listOf(
            mcpTool(1, "a", serverId = 1),
            mcpTool(2, "b", serverId = 2),
            mcpTool(3, "c", serverId = 3)
        )

        val section = buildAgentToolSections(
            tools = tools,
            mcpServerNamesById = mapOf(2L to "beta", 3L to "Alpha")
        ).single()

        // "Alpha" < "beta" < "MCP server #1": the fallback label participates in the sort like any
        // other displayed label.
        assertEquals(listOf("Alpha", "beta", "MCP server #1"), section.subGroups.map { it.title })
    }

    @Test
    fun `tools inside a sub-group are name-sorted case-insensitively with an id tie-break`() {
        val tools = listOf(
            workerTool(9, "zeta", workerId = 10),
            workerTool(4, "Beta", workerId = 10),
            workerTool(2, "beta", workerId = 10)
        )

        val section = buildAgentToolSections(tools, workerDisplayNamesById = mapOf(10L to "A")).single()

        assertEquals(listOf(2L, 4L, 9L), section.subGroups.single().tools.map { it.id })
    }

    @Test
    fun `an empty tool list produces no sections`() {
        assertEquals(emptyList(), buildAgentToolSections(emptyList()))
        assertEquals(emptyList(), buildAgentToolSections(emptyList(), mapOf(1L to "A"), mapOf(2L to "B")))
    }

    @Test
    fun `the same tools in a different order produce an identical grouping`() {
        val tools = listOf(
            mcpTool(4, "fs_list", serverId = 21),
            workerTool(2, "read", workerId = 11),
            operatorTool(1, "spawn_agent"),
            workerTool(3, "write", workerId = 10),
            mcpTool(5, "fs_read", serverId = 20)
        )

        val first = buildAgentToolSections(
            tools = tools,
            workerDisplayNamesById = mapOf(10L to "Worker B", 11L to "Worker A"),
            mcpServerNamesById = mapOf(20L to "Files", 21L to "Search")
        )
        val second = buildAgentToolSections(
            tools = tools.reversed(),
            workerDisplayNamesById = mapOf(10L to "Worker B", 11L to "Worker A"),
            mcpServerNamesById = mapOf(20L to "Files", 21L to "Search")
        )

        assertEquals(first, second)
        assertEquals(
            listOf(
                AgentToolBucket.OPERATOR,
                AgentToolBucket.BUILTIN_WORKER,
                AgentToolBucket.MCP
            ),
            first.map { it.bucket }
        )
        // Sorted by the displayed label: "Worker A" (id 11) before "Worker B" (id 10).
        assertEquals(listOf("Worker A", "Worker B"), first[1].subGroups.map { it.title })
    }
}

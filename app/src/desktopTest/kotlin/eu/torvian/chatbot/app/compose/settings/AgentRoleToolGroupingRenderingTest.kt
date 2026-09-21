package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.domain.contracts.AgentRoleFormState
import eu.torvian.chatbot.app.domain.contracts.buildAgentToolSections
import eu.torvian.chatbot.app.domain.contracts.createEmptyAgentRoleForm
import eu.torvian.chatbot.app.domain.contracts.toEditFormState
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import eu.torvian.chatbot.common.models.tool.*
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for the tool-origin headers of both agent-role Tools surfaces: the form dialog renders the
 * bucket/sub-group structure with unchanged chip toggling, the detail page renders the same structure
 * as non-interactive text.
 */
@OptIn(ExperimentalTestApi::class)
class AgentRoleToolGroupingRenderingTest {

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

    private fun workerTool(id: Long, name: String, workerId: Long, isEnabled: Boolean = true) =
        BuiltInWorkerToolDefinition(
            id = id,
            name = name,
            description = "",
            config = buildJsonObject { },
            inputSchema = buildJsonObject { },
            isEnabled = isEnabled,
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

    private fun role(id: Long, tools: Set<Long>) = AgentRoleDto(
        id = id,
        name = "writer",
        displayName = null,
        description = "",
        modelId = null,
        modelSettingsId = null,
        modelPresetId = 5L,
        tools = tools,
        instructions = emptyList()
    )

    private val preset = ModelPresetDto(
        id = 5L,
        name = "Base",
        modelId = 1L,
        modelSettingsId = 2L,
        createdAt = now,
        updatedAt = now
    )

    private val operatorTool = operatorTool(1L, "spawn_agent")
    private val serverTool = serverTool(2L, "list_agent_roles")
    private val filesWorkerTool = workerTool(3L, "files_read", workerId = 10L)
    private val filesServerTool = mcpTool(4L, "files_list", serverId = 20L)
    private val toolsOfEveryOrigin = listOf(operatorTool, serverTool, filesWorkerTool, filesServerTool)
    private val workerNames = mapOf(10L to "Worker A")
    private val serverNames = mapOf(20L to "Files")

    /** The bucket headers in their required display order. */
    private val bucketTitles = listOf(
        "Operator built-in tools",
        "Server built-in tools",
        "Worker built-in tools",
        "MCP tools"
    )

    /**
     * Renders [AgentRoleFormDialog] over a controlled draft and runs [block] against it.
     *
     * @param tools Enabled tools offered by the multi-select.
     * @param formState The draft the dialog starts with.
     * @param workerNamesById Worker id to display name lookup.
     * @param mcpServerNamesById MCP server id to name lookup.
     * @param block Assertions/interactions executed against the dialog and the live draft.
     */
    private fun setFormDialog(
        tools: List<ToolDefinition>,
        formState: AgentRoleFormState = createEmptyAgentRoleForm(),
        workerNamesById: Map<Long, String> = emptyMap(),
        mcpServerNamesById: Map<Long, String> = emptyMap(),
        block: ComposeUiTest.(draft: () -> AgentRoleFormState) -> Unit
    ) = runComposeUiTest {
        val state = mutableStateOf(formState)
        setContent {
            AgentRoleFormDialog(
                title = "Add Agent Role",
                formState = state.value,
                models = emptyList(),
                presets = emptyList(),
                settingsById = emptyMap(),
                tools = tools,
                workerDisplayNamesById = workerNamesById,
                mcpServerNamesById = mcpServerNamesById,
                roles = emptyList(),
                projects = emptyList(),
                onFormUpdate = { update -> state.value = update(state.value) },
                onSave = { },
                onCancel = { }
            )
        }
        block { state.value }
    }

    /**
     * Renders [AgentRoleDetailPage] and runs [block] against it.
     *
     * @param role The role to display.
     * @param toolsById Tool lookup used to resolve the role's tool ids.
     * @param workerNamesById Worker id to display name lookup.
     * @param mcpServerNamesById MCP server id to name lookup.
     * @param block Assertions executed against the composed page.
     */
    private fun setDetailPage(
        role: AgentRoleDto,
        toolsById: Map<Long, ToolDefinition>,
        workerNamesById: Map<Long, String> = emptyMap(),
        mcpServerNamesById: Map<Long, String> = emptyMap(),
        block: ComposeUiTest.() -> Unit
    ) = runComposeUiTest {
        setContent {
            AgentRoleDetailPage(
                role = role,
                modelsById = emptyMap(),
                presetsById = mapOf(preset.id to preset),
                settingsById = emptyMap(),
                toolsById = toolsById,
                workerDisplayNamesById = workerNamesById,
                mcpServerNamesById = mcpServerNamesById,
                onBackToList = { },
                onEdit = { },
                onDelete = { }
            )
        }
        block()
    }

    @Test
    fun `the dialog renders the four bucket headers in order with their sub-headers`() {
        setFormDialog(
            tools = toolsOfEveryOrigin,
            workerNamesById = workerNames,
            mcpServerNamesById = serverNames
        ) {
            bucketTitles.forEach { title -> onNodeWithText(title).assertExists() }
            val tops = bucketTitles.map { title -> onNodeWithText(title).getUnclippedBoundsInRoot().top }
            assertEquals(tops.sorted(), tops)

            onNodeWithText("Worker A").assertExists()
            onNodeWithText("Files").assertExists()
            // Resolvable names replace the id-based fallbacks, and no extra header appears.
            onNodeWithText("Worker #10").assertDoesNotExist()
            onNodeWithText("MCP server #20").assertDoesNotExist()
            onAllNodesWithText("Worker A").assertCountEquals(1)
            onNodeWithText("No enabled tools available.").assertDoesNotExist()
        }
    }

    @Test
    fun `sub-group headers and their chips are indented one step under the bucket header`() {
        setFormDialog(
            tools = toolsOfEveryOrigin,
            workerNamesById = workerNames,
            mcpServerNamesById = serverNames
        ) {
            val bucketLeft = onNodeWithText("Worker built-in tools").getUnclippedBoundsInRoot().left
            val subHeaderLeft = onNodeWithText("Worker A").getUnclippedBoundsInRoot().left
            val operatorChipLeft = onNodeWithText(operatorTool.name).getUnclippedBoundsInRoot().left
            val subGroupChipLeft = onNodeWithText(filesWorkerTool.name).getUnclippedBoundsInRoot().left

            // The bucket header is flush left and the sub-group block carries the single indent step.
            assertTrue(
                subHeaderLeft - bucketLeft >= 15.dp,
                "expected one 16.dp indent step, got ${subHeaderLeft - bucketLeft}"
            )
            // A sub-group's chips are indented with its header and stay right of the bucket-level chip.
            assertTrue(
                subGroupChipLeft - subHeaderLeft < 1.dp,
                "expected the chips to align with their sub-header, got ${subGroupChipLeft - subHeaderLeft}"
            )
            assertTrue(
                subGroupChipLeft - operatorChipLeft >= 15.dp,
                "expected the sub-group chip to sit right of the bucket-level chip"
            )
        }
    }

    @Test
    fun `the dialog renders a single origin with only that bucket header`() {
        setFormDialog(tools = listOf(operatorTool)) {
            onNodeWithText("Operator built-in tools").assertExists()
            onNodeWithText("Server built-in tools").assertDoesNotExist()
            onNodeWithText("Worker built-in tools").assertDoesNotExist()
            onNodeWithText("MCP tools").assertDoesNotExist()
        }
    }

    @Test
    fun `the dialog keeps the placeholder when no tool is enabled`() {
        setFormDialog(tools = emptyList()) {
            onNodeWithText("Tools").assertExists()
            onNodeWithText("No enabled tools available.").assertExists()
            bucketTitles.forEach { title -> onNodeWithText(title).assertDoesNotExist() }
        }
    }

    @Test
    fun `toggling a chip changes only that tool id in the draft`() {
        setFormDialog(
            tools = toolsOfEveryOrigin,
            workerNamesById = workerNames,
            mcpServerNamesById = serverNames
        ) { draft ->
            onNodeWithText(filesServerTool.name).performScrollTo().performClick()
            assertEquals(setOf(filesServerTool.id), draft().toolIds)

            onNodeWithText(filesWorkerTool.name).performScrollTo().performClick()
            assertEquals(setOf(filesServerTool.id, filesWorkerTool.id), draft().toolIds)

            onNodeWithText(filesServerTool.name).performScrollTo().performClick()
            assertEquals(setOf(filesWorkerTool.id), draft().toolIds)

            // No other part of the draft is touched by selecting tools.
            assertEquals("", draft().name)
            assertEquals(null, draft().modelPresetId)
            assertEquals(null, draft().projectId)
        }
    }

    @Test
    fun `the edit dialog groups the tools and marks the role's chips selected`() {
        val editedRole = role(id = 9L, tools = setOf(filesWorkerTool.id))
        val editForm = editedRole.toEditFormState()

        setFormDialog(
            tools = toolsOfEveryOrigin,
            formState = editForm,
            workerNamesById = workerNames,
            mcpServerNamesById = serverNames
        ) {
            onNodeWithText("Worker built-in tools").assertExists()
            onNodeWithText("Worker A").assertExists()
            onNodeWithText(filesWorkerTool.name).performScrollTo().assertIsSelected()
            onNodeWithText(operatorTool.name).performScrollTo().assertIsNotSelected()
        }
    }

    @Test
    fun `the detail page renders the grouping as text and drops unknown tool ids`() {
        setDetailPage(
            role = role(id = 9L, tools = setOf(filesWorkerTool.id, 99L)),
            toolsById = mapOf(filesWorkerTool.id to filesWorkerTool),
            workerNamesById = workerNames
        ) {
            onNodeWithText("Tools").assertExists()
            onNodeWithText("Worker built-in tools").assertExists()
            onNodeWithText("Worker A").assertExists()
            onNodeWithText(filesWorkerTool.name).assertExists()
            onNodeWithText("None").assertDoesNotExist()
            // Read-only: no click affordance on the tool names.
            onNodeWithText(filesWorkerTool.name).assertHasNoClickAction()
        }
    }

    @Test
    fun `the detail page keeps listing disabled tools and falls back to None when nothing resolves`() {
        val disabledTool = workerTool(6L, "disabled_read", workerId = 10L, isEnabled = false)

        setDetailPage(
            role = role(id = 9L, tools = setOf(disabledTool.id)),
            toolsById = mapOf(disabledTool.id to disabledTool),
            workerNamesById = workerNames
        ) {
            onNodeWithText("Worker built-in tools").assertExists()
            onNodeWithText(disabledTool.name).assertExists()
        }

        setDetailPage(
            role = role(id = 9L, tools = setOf(123L)),
            toolsById = emptyMap()
        ) {
            onNodeWithText("None").assertExists()
            bucketTitles.forEach { title -> onNodeWithText(title).assertDoesNotExist() }
        }
    }

    @Test
    fun `the detail page groups the same tools the dialog does`() {
        val sections = buildAgentToolSections(
            tools = toolsOfEveryOrigin,
            workerDisplayNamesById = workerNames,
            mcpServerNamesById = serverNames
        )

        // Both surfaces call the helper with the same inputs, so their header text and nesting are
        // identical by construction; this pins the expectations the rendering tests assert against.
        assertEquals(bucketTitles, sections.map { it.title })
        assertEquals(listOf("Files"), sections.last().subGroups.map { it.title })

        setDetailPage(
            role = role(id = 9L, tools = toolsOfEveryOrigin.mapTo(mutableSetOf()) { it.id }),
            toolsById = toolsOfEveryOrigin.associateBy { it.id },
            workerNamesById = workerNames,
            mcpServerNamesById = serverNames
        ) {
            bucketTitles.forEach { title -> onNodeWithText(title).assertExists() }
            onNodeWithText("Worker A").assertExists()
            onNodeWithText("Files").assertExists()
            onNodeWithText(filesWorkerTool.name).assertExists()
        }
    }
}

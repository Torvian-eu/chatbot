package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallResult
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolExecutionAuthorization
import eu.torvian.chatbot.common.models.api.tool.ToolCallApprovalResponse
import eu.torvian.chatbot.common.models.tool.*
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.server.data.dao.ToolCallDao
import eu.torvian.chatbot.server.data.dao.UserToolApprovalPreferenceDao
import eu.torvian.chatbot.server.data.dao.error.UserToolApprovalPreferenceError
import eu.torvian.chatbot.server.service.core.toolcall.DefaultToolCallOrchestrator
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallApprovalSubmission
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallExecutionEvent
import eu.torvian.chatbot.server.service.mcp.LocalMCPExecutor
import eu.torvian.chatbot.server.service.mcp.LocalMCPExecutorEvent
import eu.torvian.chatbot.server.service.tool.ToolExecutor
import eu.torvian.chatbot.server.service.tool.ToolExecutorFactory
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Unit tests for [DefaultToolCallOrchestrator].
 *
 * Covers the tool-call approval/execution lifecycle, including Local MCP and non-Local-MCP flows.
 */
class DefaultToolCallOrchestratorTest {

    private lateinit var toolCallDao: ToolCallDao
    private lateinit var toolExecutorFactory: ToolExecutorFactory
    private lateinit var localMcpExecutor: LocalMCPExecutor
    private lateinit var userToolApprovalPreferenceDao: UserToolApprovalPreferenceDao
    private lateinit var transactionScope: TransactionScope
    private lateinit var toolExecutor: ToolExecutor
    private lateinit var orchestrator: DefaultToolCallOrchestrator

    @BeforeEach
    fun setUp() {
        toolCallDao = mockk()
        toolExecutorFactory = mockk()
        localMcpExecutor = mockk()
        userToolApprovalPreferenceDao = mockk()
        transactionScope = mockk()
        toolExecutor = mockk()

        orchestrator = DefaultToolCallOrchestrator(
            toolCallDao, toolExecutorFactory, localMcpExecutor, userToolApprovalPreferenceDao, transactionScope
        )

        coEvery { transactionScope.transaction(any<suspend () -> Any?>()) } coAnswers {
            val block = firstArg<suspend () -> Any?>()
            block()
        }
    }

    @AfterEach
    fun tearDown() {
        clearMocks(
            toolCallDao, toolExecutorFactory, localMcpExecutor,
            userToolApprovalPreferenceDao, transactionScope, toolExecutor
        )
    }

    // --- executeAndUpdateToolCalls tests (approval/execution orchestration) ---

    private val testToolCallInstant = Instant.fromEpochMilliseconds(1234567890000L)

    /**
     * Fixture builder for a Local MCP tool definition.
     */
    private fun localMcpToolDefinition(
        id: Long = 1L,
        name: String = "Filesystem.List",
        serverId: Long = 33L,
        mcpToolName: String = "list_files"
    ): LocalMCPToolDefinition = LocalMCPToolDefinition(
        id = id,
        name = name,
        description = "List files",
        config = buildJsonObject { },
        inputSchema = buildJsonObject { },
        outputSchema = null,
        isEnabled = true,
        createdAt = testToolCallInstant,
        updatedAt = testToolCallInstant,
        serverId = serverId,
        mcpToolName = mcpToolName
    )

    /**
     * Fixture builder for a non-Local-MCP tool definition.
     */
    private fun miscToolDefinition(
        id: Long = 2L,
        name: String = "WebSearch",
        type: ToolType = ToolType.WEB_SEARCH
    ): MiscToolDefinition = MiscToolDefinition(
        id = id,
        name = name,
        description = "Search the web",
        type = type,
        config = buildJsonObject { },
        inputSchema = buildJsonObject { },
        outputSchema = null,
        isEnabled = true,
        createdAt = testToolCallInstant,
        updatedAt = testToolCallInstant
    )

    /**
     * Fixture builder for a pending tool call.
     */
    private fun pendingToolCall(
        id: Long = 1L,
        messageId: Long = 100L,
        toolDefinitionId: Long = 1L,
        toolName: String = "Filesystem.List"
    ): ToolCall = ToolCall(
        id = id,
        messageId = messageId,
        toolDefinitionId = toolDefinitionId,
        toolName = toolName,
        toolCallId = "call-$id",
        input = "{}",
        output = null,
        status = ToolCallStatus.PENDING,
        errorMessage = null,
        denialReason = null,
        executedAt = testToolCallInstant,
        durationMs = null
    )

    /**
     * Builds a Local MCP signed approval submission with the given decision.
     */
    private fun localMcpSignedApproval(
        toolCallId: Long,
        approved: Boolean,
        denialReason: String? = null
    ): ToolCallApprovalSubmission.LocalMcpSigned {
        val authorization = LocalMCPToolExecutionAuthorization(
            toolCallId = toolCallId,
            sessionId = 1L,
            messageId = 100L,
            toolDefinitionId = 1L,
            toolName = "Filesystem.List",
            serverId = 33L,
            mcpToolName = "list_files",
            input = "{}",
            approved = approved,
            denialReason = denialReason
        )
        return ToolCallApprovalSubmission.LocalMcpSigned(
            signedRequest = SignedRequest(
                payload = Json.encodeToString(authorization),
                signature = "signature-$toolCallId",
                signerId = "device-1",
                timestamp = 1_700_000_000_000,
                nonce = "nonce-$toolCallId"
            )
        )
    }

    /**
     * Builds a standard (non-Local-MCP) approval submission with the given decision.
     */
    private fun standardApproval(
        toolCallId: Long,
        approved: Boolean,
        denialReason: String? = null
    ): ToolCallApprovalSubmission.Standard =
        ToolCallApprovalSubmission.Standard(
            ToolCallApprovalResponse(
                toolCallId = toolCallId,
                approved = approved,
                denialReason = denialReason
            )
        )

    /**
     * Stubs toolCallDao.updateToolCall and records every persisted update so tests can assert status transitions.
     */
    private fun trackToolCallUpdates(): MutableList<ToolCall> {
        val updates = mutableListOf<ToolCall>()
        coEvery { toolCallDao.updateToolCall(any()) } coAnswers {
            updates.add(firstArg())
            Unit.right()
        }
        return updates
    }

    @Test
    fun `executeAndUpdateToolCalls should request approval and execute an approved Local MCP tool call`() = runTest {
        val toolDef = localMcpToolDefinition()
        val pending = pendingToolCall()
        val approval = localMcpSignedApproval(pending.id, approved = true)
        val updates = trackToolCallUpdates()

        val result = LocalMCPToolCallResult(
            toolCallId = pending.id,
            output = "{\"files\":[]}",
            isError = false
        )
        coEvery { localMcpExecutor.executeTool(toolDef, pending, approval.signedRequest) } returns
            LocalMCPExecutorEvent.ToolExecutionResult(result)

        val events = orchestrator.executeAndUpdateToolCalls(
            userId = 1L,
            pendingToolCalls = listOf(pending),
            toolDefinitions = listOf(toolDef),
            toolApprovalFlow = flowOf(approval)
        ).toList()

        assertEquals(3, events.size)
        val requested = assertIs<ToolCallExecutionEvent.ToolCallApprovalRequested>(events[0])
        assertEquals(ToolCallStatus.AWAITING_APPROVAL, requested.toolCall.status)
        val executing = assertIs<ToolCallExecutionEvent.ToolCallExecuting>(events[1])
        assertEquals(ToolCallStatus.EXECUTING, executing.toolCall.status)
        val completed = assertIs<ToolCallExecutionEvent.ToolCallCompleted>(events[2])
        assertEquals(ToolCallStatus.SUCCESS, completed.toolCall.status)
        assertEquals("{\"files\":[]}", completed.toolCall.output)

        assertEquals(
            listOf(ToolCallStatus.AWAITING_APPROVAL, ToolCallStatus.EXECUTING, ToolCallStatus.SUCCESS),
            updates.map { it.status }
        )

        coVerify(exactly = 1) { localMcpExecutor.executeTool(toolDef, pending, approval.signedRequest) }
        coVerify(exactly = 3) { toolCallDao.updateToolCall(any()) }
    }

    @Test
    fun `executeAndUpdateToolCalls should deny a rejected Local MCP tool call and skip execution`() = runTest {
        val toolDef = localMcpToolDefinition()
        val pending = pendingToolCall()
        val approval = localMcpSignedApproval(pending.id, approved = false, denialReason = "User refused")
        val updates = trackToolCallUpdates()

        val events = orchestrator.executeAndUpdateToolCalls(
            userId = 1L,
            pendingToolCalls = listOf(pending),
            toolDefinitions = listOf(toolDef),
            toolApprovalFlow = flowOf(approval)
        ).toList()

        assertEquals(2, events.size)
        val requested = assertIs<ToolCallExecutionEvent.ToolCallApprovalRequested>(events[0])
        assertEquals(ToolCallStatus.AWAITING_APPROVAL, requested.toolCall.status)
        val completed = assertIs<ToolCallExecutionEvent.ToolCallCompleted>(events[1])
        assertEquals(ToolCallStatus.USER_DENIED, completed.toolCall.status)
        assertEquals("User refused", completed.toolCall.denialReason)

        assertEquals(
            listOf(ToolCallStatus.AWAITING_APPROVAL, ToolCallStatus.USER_DENIED),
            updates.map { it.status }
        )

        coVerify(exactly = 0) { localMcpExecutor.executeTool(any(), any(), any()) }
        coVerify(exactly = 2) { toolCallDao.updateToolCall(any()) }
    }

    @Test
    fun `executeAndUpdateToolCalls should time out waiting for Local MCP approval and skip execution`() = runTest {
        val toolDef = localMcpToolDefinition()
        val pending = pendingToolCall()
        val updates = trackToolCallUpdates()

        val events = orchestrator.executeAndUpdateToolCalls(
            userId = 1L,
            pendingToolCalls = listOf(pending),
            toolDefinitions = listOf(toolDef),
            toolApprovalFlow = flow { delay(1_000.days) }
        ).toList()

        assertEquals(2, events.size)
        val requested = assertIs<ToolCallExecutionEvent.ToolCallApprovalRequested>(events[0])
        assertEquals(ToolCallStatus.AWAITING_APPROVAL, requested.toolCall.status)
        val completed = assertIs<ToolCallExecutionEvent.ToolCallCompleted>(events[1])
        assertEquals(ToolCallStatus.USER_DENIED, completed.toolCall.status)
        assertEquals("Approval timeout (no response within 5 minutes)", completed.toolCall.denialReason)

        assertEquals(
            listOf(ToolCallStatus.AWAITING_APPROVAL, ToolCallStatus.USER_DENIED),
            updates.map { it.status }
        )

        coVerify(exactly = 0) { localMcpExecutor.executeTool(any(), any(), any()) }
        coVerify(exactly = 2) { toolCallDao.updateToolCall(any()) }
    }

    @Test
    fun `executeAndUpdateToolCalls should auto-approve non-Local-MCP tool based on preference and execute`() = runTest {
        val toolDef = miscToolDefinition()
        val pending = pendingToolCall(
            id = 2L,
            toolDefinitionId = toolDef.id,
            toolName = toolDef.name
        )
        val preference = UserToolApprovalPreference(
            userId = 1L,
            toolDefinitionId = toolDef.id,
            autoApprove = true
        )
        coEvery { userToolApprovalPreferenceDao.getPreference(1L, toolDef.id) } returns preference.right()
        every { toolExecutorFactory.getExecutor(ToolType.WEB_SEARCH) } returns toolExecutor.right()
        coEvery { toolExecutor.executeTool(toolDef, pending.input) } returns "search result".right()
        val updates = trackToolCallUpdates()

        val events = orchestrator.executeAndUpdateToolCalls(
            userId = 1L,
            pendingToolCalls = listOf(pending),
            toolDefinitions = listOf(toolDef),
            toolApprovalFlow = emptyFlow()
        ).toList()

        assertEquals(2, events.size)
        val executing = assertIs<ToolCallExecutionEvent.ToolCallExecuting>(events[0])
        assertEquals(ToolCallStatus.EXECUTING, executing.toolCall.status)
        val completed = assertIs<ToolCallExecutionEvent.ToolCallCompleted>(events[1])
        assertEquals(ToolCallStatus.SUCCESS, completed.toolCall.status)
        assertEquals("search result", completed.toolCall.output)

        assertEquals(
            listOf(ToolCallStatus.EXECUTING, ToolCallStatus.SUCCESS),
            updates.map { it.status }
        )

        coVerify(exactly = 1) { toolExecutor.executeTool(toolDef, pending.input) }
        coVerify(exactly = 1) { userToolApprovalPreferenceDao.getPreference(1L, toolDef.id) }
        coVerify(exactly = 2) { toolCallDao.updateToolCall(any()) }
    }

    @Test
    fun `executeAndUpdateToolCalls should auto-deny non-Local-MCP tool based on preference and skip execution`() = runTest {
        val toolDef = miscToolDefinition()
        val pending = pendingToolCall(
            id = 3L,
            toolDefinitionId = toolDef.id,
            toolName = toolDef.name
        )
        val preference = UserToolApprovalPreference(
            userId = 1L,
            toolDefinitionId = toolDef.id,
            autoApprove = false,
            denialReason = "Unsafe tool"
        )
        coEvery { userToolApprovalPreferenceDao.getPreference(1L, toolDef.id) } returns preference.right()
        val updates = trackToolCallUpdates()

        val events = orchestrator.executeAndUpdateToolCalls(
            userId = 1L,
            pendingToolCalls = listOf(pending),
            toolDefinitions = listOf(toolDef),
            toolApprovalFlow = emptyFlow()
        ).toList()

        assertEquals(1, events.size)
        val completed = assertIs<ToolCallExecutionEvent.ToolCallCompleted>(events[0])
        assertEquals(ToolCallStatus.USER_DENIED, completed.toolCall.status)
        assertEquals("Unsafe tool", completed.toolCall.denialReason)

        assertEquals(listOf(ToolCallStatus.USER_DENIED), updates.map { it.status })

        coVerify(exactly = 0) { toolExecutorFactory.getExecutor(any()) }
        coVerify(exactly = 0) { toolExecutor.executeTool(any(), any()) }
        coVerify(exactly = 1) { toolCallDao.updateToolCall(any()) }
    }

    @Test
    fun `executeAndUpdateToolCalls should handle manual denial for non-Local-MCP tool and skip execution`() = runTest {
        val toolDef = miscToolDefinition()
        val pending = pendingToolCall(
            id = 4L,
            toolDefinitionId = toolDef.id,
            toolName = toolDef.name
        )
        coEvery {
            userToolApprovalPreferenceDao.getPreference(1L, toolDef.id)
        } returns UserToolApprovalPreferenceError.NotFound(1L, toolDef.id).left()
        val updates = trackToolCallUpdates()

        val events = orchestrator.executeAndUpdateToolCalls(
            userId = 1L,
            pendingToolCalls = listOf(pending),
            toolDefinitions = listOf(toolDef),
            toolApprovalFlow = flowOf(standardApproval(pending.id, approved = false, denialReason = "Nope"))
        ).toList()

        assertEquals(2, events.size)
        val requested = assertIs<ToolCallExecutionEvent.ToolCallApprovalRequested>(events[0])
        assertEquals(ToolCallStatus.AWAITING_APPROVAL, requested.toolCall.status)
        val completed = assertIs<ToolCallExecutionEvent.ToolCallCompleted>(events[1])
        assertEquals(ToolCallStatus.USER_DENIED, completed.toolCall.status)
        assertEquals("Nope", completed.toolCall.denialReason)

        assertEquals(
            listOf(ToolCallStatus.AWAITING_APPROVAL, ToolCallStatus.USER_DENIED),
            updates.map { it.status }
        )

        coVerify(exactly = 0) { toolExecutorFactory.getExecutor(any()) }
        coVerify(exactly = 0) { toolExecutor.executeTool(any(), any()) }
        coVerify(exactly = 2) { toolCallDao.updateToolCall(any()) }
    }

    @Test
    fun `executeAndUpdateToolCalls should execute non-Local-MCP tool after manual Standard approval`() = runTest {
        val toolDef = miscToolDefinition()
        val pending = pendingToolCall(
            id = 5L,
            toolDefinitionId = toolDef.id,
            toolName = toolDef.name
        )
        coEvery {
            userToolApprovalPreferenceDao.getPreference(1L, toolDef.id)
        } returns UserToolApprovalPreferenceError.NotFound(1L, toolDef.id).left()
        every { toolExecutorFactory.getExecutor(ToolType.WEB_SEARCH) } returns toolExecutor.right()
        coEvery { toolExecutor.executeTool(toolDef, pending.input) } returns "manual result".right()
        val updates = trackToolCallUpdates()

        val events = orchestrator.executeAndUpdateToolCalls(
            userId = 1L,
            pendingToolCalls = listOf(pending),
            toolDefinitions = listOf(toolDef),
            toolApprovalFlow = flowOf(standardApproval(pending.id, approved = true))
        ).toList()

        assertEquals(3, events.size)
        val requested = assertIs<ToolCallExecutionEvent.ToolCallApprovalRequested>(events[0])
        assertEquals(ToolCallStatus.AWAITING_APPROVAL, requested.toolCall.status)
        val executing = assertIs<ToolCallExecutionEvent.ToolCallExecuting>(events[1])
        assertEquals(ToolCallStatus.EXECUTING, executing.toolCall.status)
        val completed = assertIs<ToolCallExecutionEvent.ToolCallCompleted>(events[2])
        assertEquals(ToolCallStatus.SUCCESS, completed.toolCall.status)
        assertEquals("manual result", completed.toolCall.output)

        assertEquals(
            listOf(ToolCallStatus.AWAITING_APPROVAL, ToolCallStatus.EXECUTING, ToolCallStatus.SUCCESS),
            updates.map { it.status }
        )

        coVerify(exactly = 1) { toolExecutor.executeTool(toolDef, pending.input) }
        coVerify(exactly = 3) { toolCallDao.updateToolCall(any()) }
    }

}

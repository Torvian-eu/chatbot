package eu.torvian.chatbot.server.service.core.impl

import arrow.core.right
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallResult
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolExecutionAuthorization
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionAuthorization
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.common.models.tool.OperatorToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.server.data.dao.ToolCallDao
import eu.torvian.chatbot.server.service.builtin.BuiltInWorkerToolExecutor
import eu.torvian.chatbot.server.service.builtin.BuiltInWorkerToolExecutorError
import eu.torvian.chatbot.server.service.builtin.BuiltInWorkerToolExecutorEvent
import eu.torvian.chatbot.server.service.builtin.OperatorToolExecutor
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolExecutor
import eu.torvian.chatbot.server.service.core.toolcall.DefaultToolCallOrchestrator
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallApprovalSubmission
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallExecutionEvent
import eu.torvian.chatbot.server.runtime.TurnControlSignal
import eu.torvian.chatbot.server.service.mcp.LocalMCPExecutor
import eu.torvian.chatbot.server.service.mcp.LocalMCPExecutorEvent
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * Unit tests for [DefaultToolCallOrchestrator].
 *
 * Covers the tool-call approval/execution lifecycle for both supported tool families:
 *
 *  - [LocalMCPToolDefinition] requires a `LocalMcpSigned` approval that carries the app-signed
 *    authorization. A denial short-circuits execution.
 *  - [BuiltInWorkerToolDefinition] requires a `BuiltInSigned` approval that mirrors the Local MCP
 *    signing flow (a detached signature over the [BuiltInToolExecutionAuthorization] payload).
 *
 * The legacy server-side execution path (a generic tool definition type + ToolExecutorFactory) has been removed,
 * so any tool definition that is neither a [LocalMCPToolDefinition] nor a
 * [BuiltInWorkerToolDefinition] is a configuration error and propagates as an
 * [IllegalStateException] from the orchestrator.
 */
class DefaultToolCallOrchestratorTest {

    private lateinit var toolCallDao: ToolCallDao
    private lateinit var localMcpExecutor: LocalMCPExecutor
    private lateinit var builtInWorkerToolExecutor: BuiltInWorkerToolExecutor
    private lateinit var operatorToolExecutor: OperatorToolExecutor
    private lateinit var serverBuiltInToolExecutor: ServerBuiltInToolExecutor
    private lateinit var orchestrator: DefaultToolCallOrchestrator

    @BeforeEach
    fun setUp() {
        toolCallDao = mockk()
        localMcpExecutor = mockk()
        builtInWorkerToolExecutor = mockk()
        operatorToolExecutor = mockk()
        serverBuiltInToolExecutor = mockk()

        orchestrator = DefaultToolCallOrchestrator(
            toolCallDao = toolCallDao,
            localMcpExecutor = localMcpExecutor,
            builtInWorkerToolExecutor = builtInWorkerToolExecutor,
            operatorToolExecutor = operatorToolExecutor,
            serverBuiltInToolExecutor = serverBuiltInToolExecutor,
        )
    }

    @AfterEach
    fun tearDown() {
        clearMocks(toolCallDao, localMcpExecutor, builtInWorkerToolExecutor, operatorToolExecutor, serverBuiltInToolExecutor)
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
     * Fixture builder for a Built-in Worker tool definition.
     */
    private fun builtInWorkerToolDefinition(
        id: Long = 2L,
        name: String = "project1_read_text_file",
        workerId: Long = 7L,
        builtInToolName: String = "read_text_file"
    ): BuiltInWorkerToolDefinition = BuiltInWorkerToolDefinition(
        id = id,
        name = name,
        description = "Read a text file inside the worker's workspace",
        config = buildJsonObject { },
        inputSchema = buildJsonObject { },
        outputSchema = null,
        isEnabled = true,
        createdAt = testToolCallInstant,
        updatedAt = testToolCallInstant,
        workerId = workerId,
        builtInToolName = builtInToolName
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
                payload = Json.encodeToString(LocalMCPToolExecutionAuthorization.serializer(), authorization),
                signature = "signature-$toolCallId",
                signerId = "device-1",
                timestamp = 1_700_000_000_000,
                nonce = "nonce-$toolCallId"
            )
        )
    }

    /**
     * Builds a Built-in Worker signed approval submission with the given decision.
     *
     * The signed payload encodes the [BuiltInToolExecutionAuthorization] that the worker re-verifies
     * before executing the tool.
     */
    private fun builtInSignedApproval(
        toolCallId: Long,
        toolDefinitionId: Long,
        workerId: Long,
        builtInToolName: String,
        approved: Boolean,
        denialReason: String? = null
    ): ToolCallApprovalSubmission.BuiltInSigned {
        val authorization = BuiltInToolExecutionAuthorization(
            toolCallId = toolCallId,
            sessionId = 1L,
            messageId = 100L,
            toolDefinitionId = toolDefinitionId,
            toolName = "$workerId-prefix_$builtInToolName",
            workerId = workerId,
            builtInToolName = builtInToolName,
            input = "{}",
            approved = approved,
            denialReason = denialReason
        )
        return ToolCallApprovalSubmission.BuiltInSigned(
            signedRequest = SignedRequest(
                payload = Json.encodeToString(BuiltInToolExecutionAuthorization.serializer(), authorization),
                signature = "builtin-signature-$toolCallId",
                signerId = "device-1",
                timestamp = 1_700_000_000_000,
                nonce = "builtin-nonce-$toolCallId"
            )
        )
    }

    /**
     * Stubs tool-call persistence and records ordinary lifecycle updates so tests can assert status transitions.
     *
     * Cancellation cleanup uses the compare-and-update DAO operation. Its successful result is
     * stubbed here without adding cleanup writes to the lifecycle assertions, because ordinary
     * test completions should remain the focus of those assertions.
     *
     * @return Mutable list receiving each unconditional tool-call update.
     */
    private fun trackToolCallUpdates(): MutableList<ToolCall> {
        val updates = mutableListOf<ToolCall>()
        coEvery { toolCallDao.updateToolCall(any()) } coAnswers {
            updates.add(firstArg())
            Unit.right()
        }
        coEvery { toolCallDao.updateToolCallIfStatusIn(any(), any()) } returns 0
        return updates
    }

    // --- Local MCP tool-call tests ---

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
            requestingAgentRoleId = 0L,
            pendingToolCalls = listOf(pending),
            toolDefinitions = listOf(toolDef),
            toolApprovalFlow = flowOf(approval),
            operatorToolResultFlow = emptyFlow(),
            controlSignal = TurnControlSignal()
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
        coVerify(exactly = 0) { builtInWorkerToolExecutor.executeTool(any(), any(), any()) }
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
            requestingAgentRoleId = 0L,
            pendingToolCalls = listOf(pending),
            toolDefinitions = listOf(toolDef),
            toolApprovalFlow = flowOf(approval),
            operatorToolResultFlow = emptyFlow(),
            controlSignal = TurnControlSignal()
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

    // --- Built-in Worker tool-call tests ---

    @Test
    fun `executeAndUpdateToolCalls should request approval and execute an approved Built-in Worker tool call`() =
        runTest {
            val toolDef = builtInWorkerToolDefinition(id = 2L, workerId = 17L, builtInToolName = "read_text_file")
            val pending = pendingToolCall(
                id = 10L,
                toolDefinitionId = toolDef.id,
                toolName = toolDef.name,
            )
            val approval = builtInSignedApproval(
                toolCallId = pending.id,
                toolDefinitionId = toolDef.id,
                workerId = toolDef.workerId,
                builtInToolName = toolDef.builtInToolName,
                approved = true,
            )
            val updates = trackToolCallUpdates()

            val result = BuiltInToolExecutionResult(
                output = "Hello, world!",
                isError = false,
            )
            coEvery {
                builtInWorkerToolExecutor.executeTool(toolDef, pending, approval.signedRequest)
            } returns BuiltInWorkerToolExecutorEvent.ToolExecutionResult(result)

            val events = orchestrator.executeAndUpdateToolCalls(
                userId = 1L,
                requestingAgentRoleId = 0L,
                pendingToolCalls = listOf(pending),
                toolDefinitions = listOf(toolDef),
                toolApprovalFlow = flowOf(approval),
                operatorToolResultFlow = emptyFlow(),
                controlSignal = TurnControlSignal()
            ).toList()

            assertEquals(3, events.size)
            val requested = assertIs<ToolCallExecutionEvent.ToolCallApprovalRequested>(events[0])
            assertEquals(ToolCallStatus.AWAITING_APPROVAL, requested.toolCall.status)
            val executing = assertIs<ToolCallExecutionEvent.ToolCallExecuting>(events[1])
            assertEquals(ToolCallStatus.EXECUTING, executing.toolCall.status)
            val completed = assertIs<ToolCallExecutionEvent.ToolCallCompleted>(events[2])
            assertEquals(ToolCallStatus.SUCCESS, completed.toolCall.status)
            assertEquals("Hello, world!", completed.toolCall.output)
            assertEquals(null, completed.toolCall.errorMessage)

            assertEquals(
                listOf(ToolCallStatus.AWAITING_APPROVAL, ToolCallStatus.EXECUTING, ToolCallStatus.SUCCESS),
                updates.map { it.status }
            )

            coVerify(exactly = 1) { builtInWorkerToolExecutor.executeTool(toolDef, pending, approval.signedRequest) }
            coVerify(exactly = 0) { localMcpExecutor.executeTool(any(), any(), any()) }
            coVerify(exactly = 3) { toolCallDao.updateToolCall(any()) }
        }

    @Test
    fun `executeAndUpdateToolCalls should record a Built-in Worker tool error result and keep ERROR status`() =
        runTest {
            val toolDef = builtInWorkerToolDefinition(id = 2L, workerId = 17L, builtInToolName = "write_file")
            val pending = pendingToolCall(
                id = 11L,
                toolDefinitionId = toolDef.id,
                toolName = toolDef.name,
            )
            val approval = builtInSignedApproval(
                toolCallId = pending.id,
                toolDefinitionId = toolDef.id,
                workerId = toolDef.workerId,
                builtInToolName = toolDef.builtInToolName,
                approved = true,
            )
            val updates = trackToolCallUpdates()

            // The worker reports a structured error result; the orchestrator must translate `isError=true`
            // into `ToolCallStatus.ERROR` and surface the error message to the chat loop.
            val result = BuiltInToolExecutionResult(
                output = null,
                isError = true,
                errorMessage = "Permission denied: /etc/passwd is outside the workspace",
                errorCode = "WORKSPACE_VIOLATION",
            )
            coEvery {
                builtInWorkerToolExecutor.executeTool(toolDef, pending, approval.signedRequest)
            } returns BuiltInWorkerToolExecutorEvent.ToolExecutionResult(result)

            val events = orchestrator.executeAndUpdateToolCalls(
                userId = 1L,
                requestingAgentRoleId = 0L,
                pendingToolCalls = listOf(pending),
                toolDefinitions = listOf(toolDef),
                toolApprovalFlow = flowOf(approval),
                operatorToolResultFlow = emptyFlow(),
                controlSignal = TurnControlSignal()
            ).toList()

            assertEquals(3, events.size)
            val completed = assertIs<ToolCallExecutionEvent.ToolCallCompleted>(events[2])
            assertEquals(ToolCallStatus.ERROR, completed.toolCall.status)
            assertEquals(null, completed.toolCall.output)
            assertEquals("Permission denied: /etc/passwd is outside the workspace", completed.toolCall.errorMessage)
            assertEquals("WORKSPACE_VIOLATION", completed.toolCall.errorCode)

            assertEquals(
                listOf(ToolCallStatus.AWAITING_APPROVAL, ToolCallStatus.EXECUTING, ToolCallStatus.ERROR),
                updates.map { it.status }
            )
        }

    @Test
    fun `executeAndUpdateToolCalls should retain errored tool output and details for the LLM context`() = runTest {
        val toolDef = builtInWorkerToolDefinition(id = 2L, workerId = 17L, builtInToolName = "run_command")
        val pending = pendingToolCall(
            id = 11L,
            toolDefinitionId = toolDef.id,
            toolName = toolDef.name,
        )
        val approval = builtInSignedApproval(
            toolCallId = pending.id,
            toolDefinitionId = toolDef.id,
            workerId = toolDef.workerId,
            builtInToolName = toolDef.builtInToolName,
            approved = true,
        )
        trackToolCallUpdates()

        // Even when the command exits non-zero, the worker returns the captured stdout/stderr in
        // `output` plus structured `errorDetails`. The orchestrator must persist these so the
        // ToolResultContentBuilder can relay them back to the LLM.
        val result = BuiltInToolExecutionResult(
            output = "exitCode: 2\n--- stdout ---\n\n--- stderr ---\nNo such file or directory",
            isError = true,
            errorMessage = "Command exited with code 2",
            errorCode = "EXECUTION_FAILED",
            errorDetails = buildJsonObject {
                put("exitCode", JsonPrimitive(2))
                put("stderr", JsonPrimitive("No such file or directory"))
            }.toString(),
        )
        coEvery {
            builtInWorkerToolExecutor.executeTool(toolDef, pending, approval.signedRequest)
        } returns BuiltInWorkerToolExecutorEvent.ToolExecutionResult(result)

        val events = orchestrator.executeAndUpdateToolCalls(
            userId = 1L,
            requestingAgentRoleId = 0L,
            pendingToolCalls = listOf(pending),
            toolDefinitions = listOf(toolDef),
            toolApprovalFlow = flowOf(approval),
            operatorToolResultFlow = emptyFlow(),
            controlSignal = TurnControlSignal()
        ).toList()

        assertEquals(3, events.size)
        val completed = assertIs<ToolCallExecutionEvent.ToolCallCompleted>(events[2])
        assertEquals(ToolCallStatus.ERROR, completed.toolCall.status)
        assertEquals(
            "exitCode: 2\n--- stdout ---\n\n--- stderr ---\nNo such file or directory",
            completed.toolCall.output
        )
        assertEquals("Command exited with code 2", completed.toolCall.errorMessage)
        assertEquals("EXECUTION_FAILED", completed.toolCall.errorCode)
        assertEquals(
            "{\"exitCode\":2,\"stderr\":\"No such file or directory\"}",
            completed.toolCall.errorDetails
        )
    }

    @Test
    fun `executeAndUpdateToolCalls should translate a Built-in Worker dispatch error into ERROR status`() = runTest {
        val toolDef = builtInWorkerToolDefinition(id = 2L, workerId = 17L, builtInToolName = "run_command")
        val pending = pendingToolCall(
            id = 12L,
            toolDefinitionId = toolDef.id,
            toolName = toolDef.name,
        )
        val approval = builtInSignedApproval(
            toolCallId = pending.id,
            toolDefinitionId = toolDef.id,
            workerId = toolDef.workerId,
            builtInToolName = toolDef.builtInToolName,
            approved = true,
        )
        val updates = trackToolCallUpdates()

        // A worker-side dispatch failure (e.g. worker not connected) becomes a structured executor
        // error and must be recorded as `ToolCallStatus.ERROR`.
        coEvery {
            builtInWorkerToolExecutor.executeTool(toolDef, pending, approval.signedRequest)
        } returns BuiltInWorkerToolExecutorEvent.ToolExecutionError(
            toolCallId = pending.id,
            error = BuiltInWorkerToolExecutorError.OtherError(
                "Assigned worker ${toolDef.workerId} is not connected",
            ),
        )

        val events = orchestrator.executeAndUpdateToolCalls(
            userId = 1L,
            requestingAgentRoleId = 0L,
            pendingToolCalls = listOf(pending),
            toolDefinitions = listOf(toolDef),
            toolApprovalFlow = flowOf(approval),
            operatorToolResultFlow = emptyFlow(),
            controlSignal = TurnControlSignal()
        ).toList()

        assertEquals(3, events.size)
        val completed = assertIs<ToolCallExecutionEvent.ToolCallCompleted>(events[2])
        assertEquals(ToolCallStatus.ERROR, completed.toolCall.status)
        assertEquals("Assigned worker ${toolDef.workerId} is not connected", completed.toolCall.errorMessage)

        assertEquals(
            listOf(ToolCallStatus.AWAITING_APPROVAL, ToolCallStatus.EXECUTING, ToolCallStatus.ERROR),
            updates.map { it.status }
        )
    }

    @Test
    fun `executeAndUpdateToolCalls should deny a rejected Built-in Worker tool call and skip execution`() = runTest {
        val toolDef = builtInWorkerToolDefinition(id = 2L, workerId = 17L, builtInToolName = "run_command")
        val pending = pendingToolCall(
            id = 13L,
            toolDefinitionId = toolDef.id,
            toolName = toolDef.name,
        )
        val approval = builtInSignedApproval(
            toolCallId = pending.id,
            toolDefinitionId = toolDef.id,
            workerId = toolDef.workerId,
            builtInToolName = toolDef.builtInToolName,
            approved = false,
            denialReason = "Refused: run_command requires explicit consent",
        )
        val updates = trackToolCallUpdates()

        val events = orchestrator.executeAndUpdateToolCalls(
            userId = 1L,
            requestingAgentRoleId = 0L,
            pendingToolCalls = listOf(pending),
            toolDefinitions = listOf(toolDef),
            toolApprovalFlow = flowOf(approval),
            operatorToolResultFlow = emptyFlow(),
            controlSignal = TurnControlSignal()
        ).toList()

        assertEquals(2, events.size)
        val requested = assertIs<ToolCallExecutionEvent.ToolCallApprovalRequested>(events[0])
        assertEquals(ToolCallStatus.AWAITING_APPROVAL, requested.toolCall.status)
        val completed = assertIs<ToolCallExecutionEvent.ToolCallCompleted>(events[1])
        assertEquals(ToolCallStatus.USER_DENIED, completed.toolCall.status)
        assertEquals("Refused: run_command requires explicit consent", completed.toolCall.denialReason)

        assertEquals(
            listOf(ToolCallStatus.AWAITING_APPROVAL, ToolCallStatus.USER_DENIED),
            updates.map { it.status }
        )

        coVerify(exactly = 0) { builtInWorkerToolExecutor.executeTool(any(), any(), any()) }
        coVerify(exactly = 2) { toolCallDao.updateToolCall(any()) }
    }

    @Test
    fun `executeAndUpdateToolCalls should skip pending tool calls that are not in PENDING status`() = runTest {
        // Tool calls that arrive in any non-PENDING state (e.g. resuming from a checkpoint, or a
        // re-delivery from a previous attempt) must be completed without re-running the
        // approval flow or the executor. This is the orchestrator's idempotency contract.
        val toolDef = builtInWorkerToolDefinition(id = 2L, workerId = 17L, builtInToolName = "list_directory")
        val alreadyExecuted = pendingToolCall(
            id = 16L,
            toolDefinitionId = toolDef.id,
            toolName = toolDef.name,
        ).copy(
            status = ToolCallStatus.SUCCESS,
            output = "previously-computed-output",
        )
        trackToolCallUpdates()

        val events = orchestrator.executeAndUpdateToolCalls(
            userId = 1L,
            requestingAgentRoleId = 0L,
            pendingToolCalls = listOf(alreadyExecuted),
            toolDefinitions = listOf(toolDef),
            toolApprovalFlow = flowOf(
                builtInSignedApproval(
                    toolCallId = alreadyExecuted.id,
                    toolDefinitionId = toolDef.id,
                    workerId = toolDef.workerId,
                    builtInToolName = toolDef.builtInToolName,
                    approved = true,
                )
            ),
            operatorToolResultFlow = emptyFlow(),
            controlSignal = TurnControlSignal()
        ).toList()

        assertEquals(1, events.size)
        val completed = assertIs<ToolCallExecutionEvent.ToolCallCompleted>(events[0])
        // The orchestrator must not override the existing status; the caller supplied `SUCCESS`
        // because this tool call was resumed, not freshly executed.
        assertEquals(ToolCallStatus.SUCCESS, completed.toolCall.status)
        assertEquals("previously-computed-output", completed.toolCall.output)

        // No executor call, no status update, no approval transition: the orchestrator is a no-op
        // for already-processed tool calls.
        coVerify(exactly = 0) { builtInWorkerToolExecutor.executeTool(any(), any(), any()) }
        coVerify(exactly = 0) { localMcpExecutor.executeTool(any(), any(), any()) }
        coVerify(exactly = 0) { toolCallDao.updateToolCall(any()) }
    }

    // --- Operator tool-call tests ---

    /**
     * Fixture builder for an Operator tool definition.
     */
    private fun operatorToolDefinition(
        id: Long = 3L,
        name: String = "spawn_agent",
        userId: Long = 1L
    ): OperatorToolDefinition = OperatorToolDefinition(
        id = id,
        name = name,
        description = "Spawns an agent from a user-defined role",
        config = buildJsonObject { },
        inputSchema = buildJsonObject { },
        outputSchema = null,
        isEnabled = true,
        createdAt = testToolCallInstant,
        updatedAt = testToolCallInstant,
        userId = userId
    )

    /**
     * Fixture builder for an Operator tool approval submission.
     */
    private fun operatorToolApproval(
        toolCallId: Long,
        approved: Boolean,
        denialReason: String? = null
    ): ToolCallApprovalSubmission.OperatorToolApproval =
        ToolCallApprovalSubmission.OperatorToolApproval(
            toolCallId = toolCallId,
            approved = approved,
            denialReason = denialReason
        )

    @Test
    fun `executeAndUpdateToolCalls should relay and complete an approved operator tool call`() = runTest {
        val toolDef = operatorToolDefinition()
        val pending = pendingToolCall(id = 20L, toolDefinitionId = toolDef.id, toolName = toolDef.name)
        val approval = operatorToolApproval(pending.id, approved = true)
        val updates = trackToolCallUpdates()

        coEvery {
            operatorToolExecutor.executeTool(1L, 0L, pending, any(), any())
        } coAnswers {
            // Emulate the real executor: emit the relay event through the emitEvent sink, then return
            // a terminal success call carrying the spawned agent's summary.
            val emit = arg<suspend (ToolCallExecutionEvent) -> Unit>(3)
            emit(
                ToolCallExecutionEvent.OperatorToolExecutionRequested(
                    toolCallId = pending.id,
                    toolName = pending.toolName,
                    payloadJson = "{}"
                )
            )
            pending.copy(status = ToolCallStatus.SUCCESS, output = "summary", durationMs = 5L)
        }

        val events = orchestrator.executeAndUpdateToolCalls(
            userId = 1L,
            requestingAgentRoleId = 0L,
            pendingToolCalls = listOf(pending),
            toolDefinitions = listOf(toolDef),
            toolApprovalFlow = flowOf(approval),
            operatorToolResultFlow = emptyFlow(),
            controlSignal = TurnControlSignal()
        ).toList()

        assertEquals(4, events.size)
        val requested = assertIs<ToolCallExecutionEvent.ToolCallApprovalRequested>(events[0])
        assertEquals(ToolCallStatus.AWAITING_APPROVAL, requested.toolCall.status)
        assertIs<ToolCallExecutionEvent.ToolCallExecuting>(events[1])
        val relayed = assertIs<ToolCallExecutionEvent.OperatorToolExecutionRequested>(events[2])
        assertEquals(pending.id, relayed.toolCallId)
        assertEquals(pending.toolName, relayed.toolName)
        val completed = assertIs<ToolCallExecutionEvent.ToolCallCompleted>(events[3])
        assertEquals(ToolCallStatus.SUCCESS, completed.toolCall.status)
        assertEquals("summary", completed.toolCall.output)

        assertEquals(
            listOf(ToolCallStatus.AWAITING_APPROVAL, ToolCallStatus.EXECUTING, ToolCallStatus.SUCCESS),
            updates.map { it.status }
        )
        coVerify(exactly = 1) { operatorToolExecutor.executeTool(1L, 0L, pending, any(), any()) }
    }

    @Test
    fun `executeAndUpdateToolCalls should deny a rejected operator tool call and skip execution`() = runTest {
        val toolDef = operatorToolDefinition()
        val pending = pendingToolCall(id = 21L, toolDefinitionId = toolDef.id, toolName = toolDef.name)
        val approval = operatorToolApproval(pending.id, approved = false, denialReason = "No spawns")
        val updates = trackToolCallUpdates()

        val events = orchestrator.executeAndUpdateToolCalls(
            userId = 1L,
            requestingAgentRoleId = 0L,
            pendingToolCalls = listOf(pending),
            toolDefinitions = listOf(toolDef),
            toolApprovalFlow = flowOf(approval),
            operatorToolResultFlow = emptyFlow(),
            controlSignal = TurnControlSignal()
        ).toList()

        assertEquals(2, events.size)
        val requested = assertIs<ToolCallExecutionEvent.ToolCallApprovalRequested>(events[0])
        assertEquals(ToolCallStatus.AWAITING_APPROVAL, requested.toolCall.status)
        val completed = assertIs<ToolCallExecutionEvent.ToolCallCompleted>(events[1])
        assertEquals(ToolCallStatus.USER_DENIED, completed.toolCall.status)
        assertEquals("No spawns", completed.toolCall.denialReason)

        assertEquals(
            listOf(ToolCallStatus.AWAITING_APPROVAL, ToolCallStatus.USER_DENIED),
            updates.map { it.status }
        )
        coVerify(exactly = 0) { operatorToolExecutor.executeTool(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `executeAndUpdateToolCalls should surface the operator executor's error result`() = runTest {
        val toolDef = operatorToolDefinition()
        val pending = pendingToolCall(id = 22L, toolDefinitionId = toolDef.id, toolName = toolDef.name)
        val approval = operatorToolApproval(pending.id, approved = true)
        val updates = trackToolCallUpdates()

        // The executor maps a payload-build failure (e.g. role not found) into a terminal ERROR call
        // without emitting a relay event; the orchestrator must persist and relay that as-is.
        coEvery {
            operatorToolExecutor.executeTool(1L, 0L, pending, any(), any())
        } returns pending.copy(
            status = ToolCallStatus.ERROR,
            errorMessage = "Role 'ghost' not found.",
            durationMs = 2L
        )

        val events = orchestrator.executeAndUpdateToolCalls(
            userId = 1L,
            requestingAgentRoleId = 0L,
            pendingToolCalls = listOf(pending),
            toolDefinitions = listOf(toolDef),
            toolApprovalFlow = flowOf(approval),
            operatorToolResultFlow = emptyFlow(),
            controlSignal = TurnControlSignal()
        ).toList()

        assertEquals(3, events.size)
        assertIs<ToolCallExecutionEvent.ToolCallApprovalRequested>(events[0])
        assertIs<ToolCallExecutionEvent.ToolCallExecuting>(events[1])
        val completed = assertIs<ToolCallExecutionEvent.ToolCallCompleted>(events[2])
        assertEquals(ToolCallStatus.ERROR, completed.toolCall.status)
        assertEquals("Role 'ghost' not found.", completed.toolCall.errorMessage)

        assertEquals(
            listOf(ToolCallStatus.AWAITING_APPROVAL, ToolCallStatus.EXECUTING, ToolCallStatus.ERROR),
            updates.map { it.status }
        )
    }

    // --- Server Built-In tool-call tests ---

    /**
     * Fixture builder for a Server Built-In tool definition.
     */
    private fun serverBuiltInToolDefinition(
        id: Long = 4L,
        name: String = eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.LIST_AGENT_ROLES_NAME,
        builtInToolName: String = eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.LIST_AGENT_ROLES_NAME,
        userId: Long = 1L
    ): eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition =
        eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition(
            id = id,
            name = name,
            description = "Lists the current user's agent roles",
            config = buildJsonObject { },
            inputSchema = buildJsonObject { },
            outputSchema = null,
            isEnabled = true,
            createdAt = testToolCallInstant,
            updatedAt = testToolCallInstant,
            userId = userId,
            builtInToolName = builtInToolName
        )

    /**
     * Fixture builder for a Server Built-In tool approval submission (plain, non-signed).
     */
    private fun serverBuiltInApproval(
        toolCallId: Long,
        approved: Boolean,
        denialReason: String? = null
    ): ToolCallApprovalSubmission.ServerBuiltInApproval =
        ToolCallApprovalSubmission.ServerBuiltInApproval(
            toolCallId = toolCallId,
            approved = approved,
            denialReason = denialReason
        )

    @Test
    fun `executeAndUpdateToolCalls should approve and execute a server built-in tool call in-process`() = runTest {
        val toolDef = serverBuiltInToolDefinition()
        val pending = pendingToolCall(id = 30L, toolDefinitionId = toolDef.id, toolName = toolDef.name)
        val approval = serverBuiltInApproval(pending.id, approved = true)
        val updates = trackToolCallUpdates()

        coEvery { serverBuiltInToolExecutor.executeTool(1L, pending) } returns pending.copy(
            status = ToolCallStatus.SUCCESS,
            output = "[{\"id\":1,\"name\":\"writer\"}]",
            durationMs = 3L
        )

        val events = orchestrator.executeAndUpdateToolCalls(
            userId = 1L,
            requestingAgentRoleId = 0L,
            pendingToolCalls = listOf(pending),
            toolDefinitions = listOf(toolDef),
            toolApprovalFlow = flowOf(approval),
            operatorToolResultFlow = emptyFlow(),
            controlSignal = TurnControlSignal()
        ).toList()

        assertEquals(3, events.size)
        val requested = assertIs<ToolCallExecutionEvent.ToolCallApprovalRequested>(events[0])
        assertEquals(ToolCallStatus.AWAITING_APPROVAL, requested.toolCall.status)
        assertIs<ToolCallExecutionEvent.ToolCallExecuting>(events[1])
        val completed = assertIs<ToolCallExecutionEvent.ToolCallCompleted>(events[2])
        assertEquals(ToolCallStatus.SUCCESS, completed.toolCall.status)
        assertEquals("[{\"id\":1,\"name\":\"writer\"}]", completed.toolCall.output)

        assertEquals(
            listOf(ToolCallStatus.AWAITING_APPROVAL, ToolCallStatus.EXECUTING, ToolCallStatus.SUCCESS),
            updates.map { it.status }
        )
        coVerify(exactly = 1) { serverBuiltInToolExecutor.executeTool(1L, pending) }
    }

    @Test
    fun `executeAndUpdateToolCalls should deny a rejected server built-in tool call and skip execution`() = runTest {
        val toolDef = serverBuiltInToolDefinition()
        val pending = pendingToolCall(id = 31L, toolDefinitionId = toolDef.id, toolName = toolDef.name)
        val approval = serverBuiltInApproval(pending.id, approved = false, denialReason = "No config changes")
        val updates = trackToolCallUpdates()

        val events = orchestrator.executeAndUpdateToolCalls(
            userId = 1L,
            requestingAgentRoleId = 0L,
            pendingToolCalls = listOf(pending),
            toolDefinitions = listOf(toolDef),
            toolApprovalFlow = flowOf(approval),
            operatorToolResultFlow = emptyFlow(),
            controlSignal = TurnControlSignal()
        ).toList()

        assertEquals(2, events.size)
        val requested = assertIs<ToolCallExecutionEvent.ToolCallApprovalRequested>(events[0])
        assertEquals(ToolCallStatus.AWAITING_APPROVAL, requested.toolCall.status)
        val completed = assertIs<ToolCallExecutionEvent.ToolCallCompleted>(events[1])
        assertEquals(ToolCallStatus.USER_DENIED, completed.toolCall.status)
        assertEquals("No config changes", completed.toolCall.denialReason)

        assertEquals(
            listOf(ToolCallStatus.AWAITING_APPROVAL, ToolCallStatus.USER_DENIED),
            updates.map { it.status }
        )
        coVerify(exactly = 0) { serverBuiltInToolExecutor.executeTool(any(), any()) }
    }

    @Test
    fun `executeAndUpdateToolCalls should surface the server built-in executor's error result`() = runTest {
        val toolDef = serverBuiltInToolDefinition()
        val pending = pendingToolCall(id = 32L, toolDefinitionId = toolDef.id, toolName = toolDef.name)
        val approval = serverBuiltInApproval(pending.id, approved = true)
        val updates = trackToolCallUpdates()

        // The executor maps an expected failure (e.g. role not found) into a terminal ERROR call
        // carrying the LLM-readable JSON error; the orchestrator persists and relays it as-is.
        coEvery { serverBuiltInToolExecutor.executeTool(1L, pending) } returns pending.copy(
            status = ToolCallStatus.ERROR,
            errorMessage = "{\"error\":\"not_found_or_not_accessible\",\"message\":\"Agent role 99 not found or not accessible by the current user.\"}",
            durationMs = 2L
        )

        val events = orchestrator.executeAndUpdateToolCalls(
            userId = 1L,
            requestingAgentRoleId = 0L,
            pendingToolCalls = listOf(pending),
            toolDefinitions = listOf(toolDef),
            toolApprovalFlow = flowOf(approval),
            operatorToolResultFlow = emptyFlow(),
            controlSignal = TurnControlSignal()
        ).toList()

        assertEquals(3, events.size)
        assertIs<ToolCallExecutionEvent.ToolCallApprovalRequested>(events[0])
        assertIs<ToolCallExecutionEvent.ToolCallExecuting>(events[1])
        val completed = assertIs<ToolCallExecutionEvent.ToolCallCompleted>(events[2])
        assertEquals(ToolCallStatus.ERROR, completed.toolCall.status)
        assertEquals(
            "{\"error\":\"not_found_or_not_accessible\",\"message\":\"Agent role 99 not found or not accessible by the current user.\"}",
            completed.toolCall.errorMessage
        )

        assertEquals(
            listOf(ToolCallStatus.AWAITING_APPROVAL, ToolCallStatus.EXECUTING, ToolCallStatus.ERROR),
            updates.map { it.status }
        )
    }

    @Test
    fun `executeAndUpdateToolCalls should finalize a server built-in tool call as CANCELLED on turn cancellation`() = runTest {
        val toolDef = serverBuiltInToolDefinition()
        val pending = pendingToolCall(id = 33L, toolDefinitionId = toolDef.id, toolName = toolDef.name)
        trackToolCallUpdates()
        // The cleanup path uses the compare-and-update DAO operation; a successful cleanup returns 1
        // row so the finalizer emits the terminal CANCELLED event.
        coEvery { toolCallDao.updateToolCallIfStatusIn(any(), any()) } returns 1

        val controlSignal = TurnControlSignal()
        // Cancel before execution begins: the orchestrator must finalize the unfinished call via its
        // finally-block cleanup instead of running the executor.
        controlSignal.cancel()

        val events = orchestrator.executeAndUpdateToolCalls(
            userId = 1L,
            requestingAgentRoleId = 0L,
            pendingToolCalls = listOf(pending),
            toolDefinitions = listOf(toolDef),
            toolApprovalFlow = emptyFlow(),
            operatorToolResultFlow = emptyFlow(),
            controlSignal = controlSignal
        ).toList()

        assertEquals(1, events.size)
        val completed = assertIs<ToolCallExecutionEvent.ToolCallCompleted>(events[0])
        assertEquals(ToolCallStatus.CANCELLED, completed.toolCall.status)
        coVerify(exactly = 0) { serverBuiltInToolExecutor.executeTool(any(), any()) }
    }
}

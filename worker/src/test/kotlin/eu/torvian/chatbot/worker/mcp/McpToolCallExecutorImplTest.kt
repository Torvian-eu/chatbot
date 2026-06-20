package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolExecutionAuthorization
import eu.torvian.chatbot.common.security.SignedRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [McpToolCallExecutorImpl].
 */
class McpToolCallExecutorImplTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Verifies that malformed request JSON is mapped to a logical result error and does not hit the runtime service.
     */
    @Test
    fun `malformed json returns logical error without runtime service call`() = kotlinx.coroutines.test.runTest {
        val authorization = buildAuthorization(input = "{invalid")
        val runtimeService = RecordingRuntimeService(
            result = Either.Right(McpToolCallOutcome(isError = false, textContent = "ok"))
        )
        val executor =
            McpToolCallExecutorImpl(
                authorizationValidator = StaticAuthorizationValidator(
                    LocalMCPToolExecutionAuthorizationValidationResult.Authorized(authorization)
                ),
                runtimeService = runtimeService,
                json = json
            )

        val result = executor.execute(signedRequest(authorization))

        assertTrue(result.isError)
        assertEquals(result.errorMessage?.contains("Malformed JSON input"), true)
        assertEquals(0, runtimeService.callCount)
    }

    /**
     * Verifies that runtime-level logical errors are propagated as tool-call errors.
     */
    @Test
    fun `runtime error becomes tool call error result`() = kotlinx.coroutines.test.runTest {
        val authorization = buildAuthorization()
        val runtimeService = RecordingRuntimeService(
            result = Either.Left(
                McpRuntimeError.ToolCallFailed(
                    serverId = 10,
                    toolName = "searchDocs",
                    message = "Tool process not reachable"
                )
            )
        )
        val executor =
            McpToolCallExecutorImpl(
                authorizationValidator = StaticAuthorizationValidator(
                    LocalMCPToolExecutionAuthorizationValidationResult.Authorized(authorization)
                ),
                runtimeService = runtimeService,
                json = json
            )

        val result = executor.execute(signedRequest(authorization))

        assertTrue(result.isError)
        assertEquals("Tool process not reachable", result.errorMessage)
        assertEquals(1, runtimeService.callCount)
    }

    /**
     * Verifies that successful outcomes with text content are preserved in output.
     */
    @Test
    fun `successful outcome returns text output`() = kotlinx.coroutines.test.runTest {
        val authorization = buildAuthorization()
        val runtimeService = RecordingRuntimeService(
            result = Either.Right(
                McpToolCallOutcome(
                    isError = false,
                    textContent = "{\"items\":[\"ktor\"]}"
                )
            )
        )
        val executor =
            McpToolCallExecutorImpl(
                authorizationValidator = StaticAuthorizationValidator(
                    LocalMCPToolExecutionAuthorizationValidationResult.Authorized(authorization)
                ),
                runtimeService = runtimeService,
                json = json
            )

        val result = executor.execute(signedRequest(authorization))

        assertFalse(result.isError)
        assertEquals("{\"items\":[\"ktor\"]}", result.output)
        assertEquals(1, runtimeService.callCount)
    }

    /**
     * Verifies that MCP-level error outcomes are mapped to logical tool-call errors.
     */
    @Test
    fun `error outcome maps structured content to error message`() = kotlinx.coroutines.test.runTest {
        val authorization = buildAuthorization()
        val runtimeService = RecordingRuntimeService(
            result = Either.Right(
                McpToolCallOutcome(
                    isError = true,
                    structuredContent = "{\"code\":\"TOOL_TIMEOUT\"}"
                )
            )
        )
        val executor =
            McpToolCallExecutorImpl(
                authorizationValidator = StaticAuthorizationValidator(
                    LocalMCPToolExecutionAuthorizationValidationResult.Authorized(authorization)
                ),
                runtimeService = runtimeService,
                json = json
            )

        val result = executor.execute(signedRequest(authorization))

        assertTrue(result.isError)
        assertEquals("{\"code\":\"TOOL_TIMEOUT\"}", result.errorMessage)
        assertEquals(1, runtimeService.callCount)
    }

    /**
     * Verifies that worker-side authorization failures are surfaced as structured security errors and do not
     * reach the MCP runtime.
     */
    @Test
    fun `authorization rejection returns structured security error without runtime service call`() = kotlinx.coroutines.test.runTest {
        val runtimeService = RecordingRuntimeService(
            result = Either.Right(McpToolCallOutcome(isError = false, textContent = "ok"))
        )
        val executor = McpToolCallExecutorImpl(
            authorizationValidator = StaticAuthorizationValidator(
                LocalMCPToolExecutionAuthorizationValidationResult.Denied(denialReason = "User denied")
            ),
            runtimeService = runtimeService,
            json = json
        )

        val result = executor.execute(signedRequest(buildAuthorization()))

        assertTrue(result.isError)
        assertEquals("LOCAL_MCP_AUTH_DENIED", result.errorCode)
        assertEquals(0, runtimeService.callCount)
    }

    /**
     * Fixed authorization validator used to make executor tests deterministic.
     *
     * @property result Validation result returned for every signed request.
     */
    private class StaticAuthorizationValidator(
        private val result: LocalMCPToolExecutionAuthorizationValidationResult
    ) : LocalMCPToolExecutionAuthorizationValidator {
        /**
         * @param signedRequest Signed request supplied to the worker executor.
         * @return Preconfigured validation result.
         */
        override suspend fun validate(signedRequest: SignedRequest): LocalMCPToolExecutionAuthorizationValidationResult {
            return result
        }
    }

    /**
     * Simple recording runtime service used for deterministic executor tests.
     *
     * @property result Invocation result returned by each callTool call.
     */
    private class RecordingRuntimeService(
        private val result: Either<McpRuntimeError, McpToolCallOutcome?>
    ) : McpRuntimeService {

        /**
         * Number of times [callTool] was invoked.
         */
        var callCount: Int = 0

        /**
         * Returns the configured test result and increments [callCount].
         *
         * @param serverId Local MCP server identifier.
         * @param toolName MCP tool name.
         * @param arguments Parsed tool arguments.
         * @return Preconfigured invocation result.
         */
        override suspend fun callTool(
            serverId: Long,
            toolName: String,
            arguments: JsonObject
        ): Either<McpRuntimeError, McpToolCallOutcome?> {
            callCount++
            return result
        }

        // Stub implementations for other runtime service methods (not used in tests)
        override suspend fun startServer(serverId: Long): Either<McpRuntimeError, Unit> =
            Unit.right()

        override suspend fun stopServer(serverId: Long): Either<McpRuntimeError, Unit> =
            Unit.right()

        override suspend fun testConnection(serverId: Long): Either<McpRuntimeError, McpTestConnectionOutcome> =
            McpTestConnectionOutcome(0).right()

        override suspend fun testDraftConnection(config: LocalMCPServerDto): Either<McpRuntimeError, McpTestConnectionOutcome> =
            McpTestConnectionOutcome(0).right()

        override suspend fun discoverTools(serverId: Long): Either<McpRuntimeError, List<McpDiscoveredTool>> =
            emptyList<McpDiscoveredTool>().right()

        override suspend fun getRuntimeStatus(serverId: Long): Either<McpRuntimeError, eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStatusDto> =
            eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStatusDto(
                serverId = serverId,
                state = eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStateDto.STOPPED
            ).right()

        override suspend fun listRuntimeStatuses(): List<eu.torvian.chatbot.common.models.api.mcp.LocalMcpServerRuntimeStatusDto> =
            emptyList()
    }

    /**
     * Builds a valid baseline authorization for test scenarios.
     *
     * @param input Exact JSON argument string for the authorization.
     * @return Valid authorization DTO.
     */
    private fun buildAuthorization(input: String? = "{\"query\":\"ktor\"}"): LocalMCPToolExecutionAuthorization {
        return LocalMCPToolExecutionAuthorization(
            toolCallId = 123,
            sessionId = 1,
            messageId = 2,
            toolDefinitionId = 3,
            toolName = "searchDocs",
            serverId = 10,
            mcpToolName = "search_docs",
            input = input,
            approved = true,
            denialReason = null
        )
    }

    /**
     * Builds deterministic detached signed-request metadata for executor tests.
     *
     * @param authorization Authorization payload to sign.
     * @return Signed request fixture.
     */
    private fun signedRequest(authorization: LocalMCPToolExecutionAuthorization): SignedRequest = SignedRequest(
        payload = json.encodeToString(LocalMCPToolExecutionAuthorization.serializer(), authorization),
        signature = "signature-base64",
        signerId = "device-1",
        timestamp = 1_700_000_000_000,
        nonce = "nonce-1"
    )
}


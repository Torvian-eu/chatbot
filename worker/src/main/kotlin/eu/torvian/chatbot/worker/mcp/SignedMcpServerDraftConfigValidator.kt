package eu.torvian.chatbot.worker.mcp

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerTestDraftConnectionCommandData

/**
 * Validates relayed transient draft MCP server configuration test requests against detached signed requests.
 */
interface SignedMcpServerDraftConfigValidator {
    /**
     * Verifies the detached signature and confirms the signed payload matches the relayed draft test DTO.
     *
     * @param request Draft test command data relayed to the worker.
     * @return Authorization result describing whether the worker may trust and execute the draft test.
     */
    suspend fun validate(
        request: WorkerMcpServerTestDraftConnectionCommandData
    ): SignedMcpServerDraftConfigValidationResult
}

package eu.torvian.chatbot.server.service.core.impl

import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.LocalMCPToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.SessionToolConfigDao
import eu.torvian.chatbot.server.data.dao.ToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.UserToolApprovalPreferenceDao
import eu.torvian.chatbot.server.service.core.error.tool.ValidateToolError
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [ToolServiceImpl], focused on the LLM-safe tool-name character-set validation that
 * acts as a defensive backstop for built-in and future tool sources.
 */
class ToolServiceImplTest {
    private val toolDefinitionDao = mockk<ToolDefinitionDao>()
    private val sessionToolConfigDao = mockk<SessionToolConfigDao>()
    private val localMCPToolDefinitionDao = mockk<LocalMCPToolDefinitionDao>()
    private val userToolApprovalPreferenceDao = mockk<UserToolApprovalPreferenceDao>()
    private val transactionScope = mockk<TransactionScope>()

    private val service = ToolServiceImpl(
        toolDefinitionDao = toolDefinitionDao,
        sessionToolConfigDao = sessionToolConfigDao,
        localMCPToolDefinitionDao = localMCPToolDefinitionDao,
        userToolApprovalPreferenceDao = userToolApprovalPreferenceDao,
        transactionScope = transactionScope
    )

    @BeforeEach
    fun setUp() {
        clearMocks(toolDefinitionDao, sessionToolConfigDao, localMCPToolDefinitionDao, userToolApprovalPreferenceDao, transactionScope)
        coEvery { transactionScope.transaction<Any>(any()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
    }

    private fun validSchema(): JsonObject = buildJsonObject { put("type", "object") }

    @Test
    fun `validateToolDefinition accepts an LLM-safe name`() = runTest {
        val result = service.validateToolDefinition(
            name = "read_text_file",
            description = "Reads a file",
            inputSchema = validSchema(),
            outputSchema = null
        )
        assertTrue(result.isRight())
    }

    @Test
    fun `validateToolDefinition rejects a name with illegal characters`() = runTest {
        val result = service.validateToolDefinition(
            name = "get /weather",
            description = "Gets weather",
            inputSchema = validSchema(),
            outputSchema = null
        )
        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertIs<ValidateToolError.InvalidName>(error)
        assertEquals("get /weather", error.name)
    }

    @Test
    fun `validateToolDefinition rejects a blank name`() = runTest {
        val result = service.validateToolDefinition(
            name = "",
            description = "desc",
            inputSchema = validSchema(),
            outputSchema = null
        )
        assertTrue(result.isLeft())
        assertIs<ValidateToolError.InvalidName>(result.leftOrNull())
    }
}


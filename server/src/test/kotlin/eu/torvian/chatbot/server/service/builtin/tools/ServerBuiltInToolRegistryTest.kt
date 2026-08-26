package eu.torvian.chatbot.server.service.builtin.tools

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInTool
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Tests the Koin-registered server built-in tool registry.
 *
 * Verifies that the `Map<String, ServerBuiltInTool>` binding (the executor's dispatch table) is
 * keyed by every canonical catalog name — including the three targeted instruction tools — and
 * that those keys resolve to the correct handler implementations.
 */
class ServerBuiltInToolRegistryTest {

    private lateinit var container: DIContainer

    @BeforeEach
    fun setup() = runTest {
        container = defaultTestContainer()
    }

    @AfterEach
    fun tearDown() = runTest {
        container.close()
    }

    @Test
    fun `registry dispatches every catalog name and maps the new tools to their handlers`() = runTest {
        val tools: Map<String, ServerBuiltInTool> = container.get()

        // The registry must cover the whole catalog: a spec without a handler would surface as a
        // silent dispatch miss at runtime instead of a compile-time or startup failure.
        assertEquals(ServerBuiltInToolCatalog.allTools.size, tools.size)
        ServerBuiltInToolCatalog.allTools.forEach { spec ->
            assertNotNull(tools[spec.name]) { "Registry is missing a handler for '${spec.name}'" }
        }

        // The three targeted instruction tools resolve to their dedicated handlers (never to a
        // generic fallback), so the executor can dispatch the canonical names.
        assertIs<InsertAgentRoleInstructionTool>(
            tools[ServerBuiltInToolCatalog.INSERT_AGENT_ROLE_INSTRUCTION_NAME]
        )
        assertIs<EditAgentRoleInstructionsTool>(
            tools[ServerBuiltInToolCatalog.EDIT_AGENT_ROLE_INSTRUCTIONS_NAME]
        )
        assertIs<RemoveAgentRoleInstructionTool>(
            tools[ServerBuiltInToolCatalog.REMOVE_AGENT_ROLE_INSTRUCTION_NAME]
        )
    }
}

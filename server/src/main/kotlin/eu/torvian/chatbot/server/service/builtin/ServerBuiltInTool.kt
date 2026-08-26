package eu.torvian.chatbot.server.service.builtin

import arrow.core.Either
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import kotlinx.serialization.json.JsonObject

/**
 * Common contract implemented by every server built-in tool.
 *
 * Server built-in tools run in-process on the server inside a chat turn (no worker, no MCP). Each
 * implementation owns exactly one [ServerBuiltInToolCatalog] spec: [name] is the **canonical**
 * catalog name (the registry key the executor dispatches on), and [description]/[inputSchema] are
 * the catalog values surfaced to the LLM (and persisted per user at seed time). The public,
 * LLM-facing name of a user's instance may carry the user's configured prefix (e.g.
 * `chatbot-list_agent_roles`); that prefixed name is never used for dispatch.
 *
 * Implementations are stateless and receive their user-scoped service dependencies through
 * constructor injection (wired in the Koin module), so [execute] only needs the caller identity and
 * the parsed arguments. Expected failures are returned as typed [ServerBuiltInToolHandlerError]s,
 * never thrown.
 *
 * @property name Canonical catalog name (the executor dispatch key).
 * @property description Human-readable description surfaced to the LLM.
 * @property inputSchema JSON Schema describing the tool's expected input arguments.
 */
interface ServerBuiltInTool {

    /** Canonical catalog name; the executor dispatch key. */
    val name: String

    /** Human-readable description surfaced to the LLM. */
    val description: String

    /** JSON Schema describing the tool's expected input arguments. */
    val inputSchema: JsonObject

    /**
     * Executes the tool for [userId] with the parsed [input].
     *
     * Every handler is strictly user-scoped; not-found and not-accessible collapse into a single
     * [ServerBuiltInToolHandlerError.NotFoundOrNotAccessible] so the tool never leaks the existence
     * of another user's resources. Implementations validate every input parameter and accumulate all
     * validation errors before failing (mirroring the worker built-in tool style), returning a single
     * [ServerBuiltInToolHandlerError.InvalidInput] that lists every issue at once.
     *
     * @param userId The user whose server built-in tool instance is being executed.
     * @param input JSON arguments for the tool (already parsed as an object by the executor).
     * @return Either a [ServerBuiltInToolHandlerError] or the handler output: the JSON-encoded
     *         payload for read-style tools (e.g. `read_agent_role`), or a concise human-readable
     *         text summary/diff for mutating tools (e.g. `create_agent_role`, the instruction
     *         tools), so the LLM context stays lean.
     */
    suspend fun execute(
        userId: Long,
        input: JsonObject
    ): Either<ServerBuiltInToolHandlerError, String>
}

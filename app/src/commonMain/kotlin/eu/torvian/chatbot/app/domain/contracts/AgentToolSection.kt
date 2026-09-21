package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType

/**
 * Origin bucket a tool is listed under on the agent-role Tools surfaces.
 *
 * The declaration order is the display order the surfaces render, so the enum doubles as the
 * canonical bucket sequence and must not be reordered without an explicit product decision.
 *
 * @property displayLabel Header text rendered for the bucket.
 */
enum class AgentToolBucket(val displayLabel: String) {
    /** Tools the operator (the client app) executes on the server's behalf. */
    OPERATOR("Operator built-in tools"),

    /** Cataloged tools the server executes in-process during a turn. */
    BUILTIN_SERVER("Server built-in tools"),

    /** Tools dispatched to a worker; listed one level deeper per worker. */
    BUILTIN_WORKER("Worker built-in tools"),

    /** Model Context Protocol tools; listed one level deeper per MCP server. */
    MCP("MCP tools");

    companion object {
        /**
         * Maps a tool type to the bucket listing it.
         *
         * The `when` covers every [ToolType] constant without an `else`, so a future tool type fails
         * compilation here instead of silently landing in the wrong bucket.
         *
         * @param type The tool type to map.
         * @return The bucket the tool belongs to.
         */
        fun of(type: ToolType): AgentToolBucket = when (type) {
            ToolType.OPERATOR -> OPERATOR
            ToolType.BUILTIN_SERVER -> BUILTIN_SERVER
            ToolType.BUILTIN_WORKER -> BUILTIN_WORKER
            ToolType.MCP_LOCAL, ToolType.MCP_REMOTE -> MCP
        }
    }
}

/**
 * One named group inside a two-level tool bucket (a worker, or an MCP server).
 *
 * @property title Sub-header text: the origin's display name, or the `Worker #<id>` /
 *            `MCP server #<id>` fallback when the origin cannot be named.
 * @property tools Tools belonging to this origin, name-sorted.
 */
data class AgentToolSubGroup(
    val title: String,
    val tools: List<ToolDefinition>
)

/**
 * One origin bucket of tools, ready to render on the agent-role Tools surfaces.
 *
 * @property bucket The origin bucket this section represents; also defines the header text.
 * @property tools Tools listed directly under the bucket header, name-sorted. Flat buckets
 *            (`OPERATOR`, `BUILTIN_SERVER`) keep every tool here; two-level buckets keep only the
 *            tools whose origin group could not be determined.
 * @property subGroups Per-origin groups of a two-level bucket, ordered by displayed title; empty for
 *            flat buckets.
 */
data class AgentToolSection(
    val bucket: AgentToolBucket,
    val tools: List<ToolDefinition> = emptyList(),
    val subGroups: List<AgentToolSubGroup> = emptyList()
) {
    /** Header text of this section, taken from the bucket's display label. */
    val title: String get() = bucket.displayLabel
}

/**
 * Splits [tools] into the ordered, ready-to-render bucket sections of the agent-role Tools surfaces.
 *
 * Both surfaces (the role form dialog and the role detail page) call this helper so they always agree:
 * buckets appear in [AgentToolBucket] declaration order with empty ones omitted, workers and MCP
 * servers get one sub-group each, and every ordering is imposed here because the tool stream arrives
 * in an unspecified order. The derivation is pure and cheap, so callers may re-run it on every
 * recomposition.
 *
 * Origin names are resolved from the supplied lookups; a missing or blank name falls back to
 * `Worker #<id>` / `MCP server #<id>`, which is the only place that fallback is applied — the maps are
 * therefore expected to carry raw names, blank ones included. Two origins sharing a name stay two
 * separate groups with the same title. A tool whose origin id cannot be read is never dropped: it is
 * listed directly under its bucket header, without a sub-header.
 *
 * @param tools Tool definitions to group, in any order.
 * @param workerDisplayNamesById Worker id → display name, used for the worker sub-headers.
 * @param mcpServerNamesById MCP server id → name, used for the MCP sub-headers.
 * @return The non-empty sections in display order; empty when [tools] is empty.
 */
fun buildAgentToolSections(
    tools: List<ToolDefinition>,
    workerDisplayNamesById: Map<Long, String> = emptyMap(),
    mcpServerNamesById: Map<Long, String> = emptyMap()
): List<AgentToolSection> {
    val toolsByBucket = tools.groupBy { tool -> AgentToolBucket.of(tool.type) }

    // Iterating the enum entries (not the group map) imposes the bucket order; a bucket with no tools
    // yields no section, so its header never renders.
    return AgentToolBucket.entries.mapNotNull { bucket ->
        val bucketTools = toolsByBucket[bucket] ?: return@mapNotNull null
        when (bucket) {
            AgentToolBucket.BUILTIN_WORKER -> buildTwoLevelSection(bucket, bucketTools) { workerId ->
                workerDisplayNamesById[workerId]?.takeIf { it.isNotBlank() } ?: "Worker #$workerId"
            }

            AgentToolBucket.MCP -> buildTwoLevelSection(bucket, bucketTools) { serverId ->
                mcpServerNamesById[serverId]?.takeIf { it.isNotBlank() } ?: "MCP server #$serverId"
            }

            AgentToolBucket.OPERATOR, AgentToolBucket.BUILTIN_SERVER ->
                AgentToolSection(bucket = bucket, tools = bucketTools.sortedWith(toolNameOrder))
        }
    }
}

/**
 * Builds a two-level bucket: one sub-group per distinct origin id, plus the tools without a readable
 * origin id at bucket level so no tool disappears from the picker.
 *
 * @param bucket The bucket being built.
 * @param bucketTools The bucket's tools.
 * @param label Resolves the sub-header text for an origin id, applying the fallback label.
 * @return The section with its tools name-sorted and its sub-groups title-sorted.
 */
private fun buildTwoLevelSection(
    bucket: AgentToolBucket,
    bucketTools: List<ToolDefinition>,
    label: (id: Long) -> String
): AgentToolSection {
    val subGroups = bucketTools
        .mapNotNull { tool -> originIdOf(tool)?.let { id -> id to tool } }
        .groupBy({ (id, _) -> id }, { (_, tool) -> tool })
        .entries
        // Sub-groups order by the label the user actually sees (fallback labels included), with the id
        // as a stable tie-break so identically named origins still render deterministically.
        .sortedWith(compareBy<Map.Entry<Long, List<ToolDefinition>>>({ label(it.key).lowercase() }, { it.key }))
        .map { (id, groupTools) ->
            AgentToolSubGroup(title = label(id), tools = groupTools.sortedWith(toolNameOrder))
        }

    return AgentToolSection(
        bucket = bucket,
        // A tool whose origin id cannot be read stays at bucket level instead of being dropped.
        tools = bucketTools.filter { originIdOf(it) == null }.sortedWith(toolNameOrder),
        subGroups = subGroups
    )
}

/**
 * Reads the origin id a tool is grouped by, or `null` when the tool's type carries no such id.
 *
 * @param tool The tool to inspect.
 * @return The worker id for worker built-in tools, the server id for local MCP tools, otherwise
 *         `null`.
 */
private fun originIdOf(tool: ToolDefinition): Long? = when (tool) {
    is BuiltInWorkerToolDefinition -> tool.workerId
    is LocalMCPToolDefinition -> tool.serverId
    else -> null
}

/** Deterministic item order inside a bucket or sub-group: name ascending (case-insensitive), then id. */
private val toolNameOrder = compareBy<ToolDefinition>({ it.name.lowercase() }, { it.id })

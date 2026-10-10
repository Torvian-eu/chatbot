package eu.torvian.chatbot.common.models.tool.specs

import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.GET_CURRENT_SESSION_INFO_NAME
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.ServerBuiltInToolSpec

/**
 * Session tool specifications, in stable catalog order.
 *
 * The order is part of the contract: it defines the seeding order and the order in which
 * the tools appear in listings. Do not reorder entries.
 */
internal val sessionToolSpecs: List<ServerBuiltInToolSpec> = listOf(
    ServerBuiltInToolSpec(
        name = GET_CURRENT_SESSION_INFO_NAME,
        description = "Returns the current chat session's id and name together with the id, " +
                "name, and (when set) display name of the agent role selected for that session, " +
                "and the session's project id (present only when the session has a project " +
                "selected). The session is the one the current conversation belongs to and is " +
                "always owned by the current user, so no other user's data is ever exposed.",
        inputSchema = emptyObjectSchema()
    )
)

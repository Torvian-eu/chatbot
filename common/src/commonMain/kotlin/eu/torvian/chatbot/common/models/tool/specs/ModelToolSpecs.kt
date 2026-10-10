package eu.torvian.chatbot.common.models.tool.specs

import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.LIST_MODELS_NAME
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.LIST_MODEL_SETTINGS_NAME
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.LIST_TOOLS_NAME
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.MODEL_ID_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.READ_TOOL_NAME
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.ServerBuiltInToolSpec
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.TOOL_ID_PROPERTY
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Model and tool inspection tool specifications, in stable catalog order.
 *
 * The order is part of the contract: it defines the seeding order and the order in which
 * the tools appear in listings. Do not reorder entries.
 */
internal val modelToolSpecs: List<ServerBuiltInToolSpec> = listOf(
    ServerBuiltInToolSpec(
        name = LIST_MODELS_NAME,
        description = "Lists all LLM models accessible by the current user (models the user owns " +
            "or that are shared with a group the user belongs to).",
        inputSchema = emptyObjectSchema()
    ),
    ServerBuiltInToolSpec(
        name = LIST_MODEL_SETTINGS_NAME,
        description = "Lists the settings profiles accessible by the current user for one model " +
            "the user can access. Returns the full settings object including its subtype " +
            "(chat, responses, completion, etc.).",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put(
                    MODEL_ID_PROPERTY,
                    integerProperty("Id of the model whose settings to list. The model must be accessible by the current user.")
                )
            })
            put("required", buildJsonArray {
                add(MODEL_ID_PROPERTY)
            })
        }
    ),
    ServerBuiltInToolSpec(
        name = LIST_TOOLS_NAME,
        description = "Lists all tools accessible by the current user (own MCP tools, built-in " +
            "tools of owned workers, own operator tools, and own server built-in tools), " +
            "returning each tool's id, name, description, type, and enabled flag.",
        inputSchema = emptyObjectSchema()
    ),
    ServerBuiltInToolSpec(
        name = READ_TOOL_NAME,
        description = "Reads one tool accessible by the current user by its id, returning the " +
            "full tool definition including its subtype-specific fields.",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put(
                    TOOL_ID_PROPERTY,
                    integerProperty("Id of the tool to read. The tool must be accessible by the current user.")
                )
            })
            put("required", buildJsonArray {
                add(TOOL_ID_PROPERTY)
            })
        }
    ),
)

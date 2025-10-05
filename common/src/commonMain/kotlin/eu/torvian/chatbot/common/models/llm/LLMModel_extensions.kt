package eu.torvian.chatbot.common.models.llm

import kotlinx.serialization.json.*

/**
 * Checks if the [LLMModel] has a specific boolean-like capability enabled.
 * This is useful for simple on/off features.
 *
 * @param capabilityKey The key of the capability to check (e.g., [LLMModelCapabilities.MULTIMODAL_IMAGE_INPUT]).
 * @return `true` if the capability exists and its value is `true` or is present as a key, `false` otherwise.
 */
fun LLMModel.hasCapability(capabilityKey: String): Boolean {
    val caps = this.capabilities ?: return false
    val value = caps[capabilityKey]
    // If value is a boolean primitive, return its value. Otherwise, if it's not null (e.g., an object or array),
    // treat its presence as 'true'.
    return value?.jsonPrimitive?.booleanOrNull ?: (value != null)
}

/**
 * Retrieves the raw [JsonElement] associated with a specific capability key from the [LLMModel].
 * This is useful for capabilities that have complex, non-boolean values (e.g., objects or arrays).
 *
 * @param capabilityKey The key of the capability (e.g., [LLMModelCapabilities.TOOL_CALLING]).
 * @return The [JsonElement] value if the capability exists, or `null` otherwise.
 */
fun LLMModel.getCapabilityDetails(capabilityKey: String): JsonElement? {
    return this.capabilities?.get(capabilityKey)
}

/**
 * Retrieves a specific String value from an [LLMModel]'s capability details.
 * Useful for extracting parameters from a capability object (e.g., version, format).
 *
 * Example: `model.getStringCapability(LLMModelCapabilities.TOOL_CALLING, "version")`
 *
 * @param capabilityKey The key of the capability.
 * @param path The JSON path to the string value within the capability's details (e.g., "version", "config.max_size").
 * @return The string value if found, or `null` if not found or not a string.
 */
fun LLMModel.getStringCapability(capabilityKey: String, path: String): String? {
    val details = getCapabilityDetails(capabilityKey) ?: return null
    return details.jsonObject.atPath(path)?.jsonPrimitive?.contentOrNull
}

/**
 * Retrieves a specific Int value from an [LLMModel]'s capability details.
 *
 * Example: `model.getIntCapability(LLMModelCapabilities.TOOL_CALLING, "max_tools")`
 *
 * @param capabilityKey The key of the capability.
 * @param path The JSON path to the int value within the capability's details.
 * @return The int value if found, or `null` if not found or not an int.
 */
fun LLMModel.getIntCapability(capabilityKey: String, path: String): Int? {
    val details = getCapabilityDetails(capabilityKey) ?: return null
    return details.jsonObject.atPath(path)?.jsonPrimitive?.contentOrNull?.toIntOrNull()
}

/**
 * Retrieves a specific List of Strings from an [LLMModel]'s capability details.
 *
 * Example: `model.getStringListCapability(LLMModelCapabilities.MULTIMODAL_IMAGE_INPUT, "supported_formats")`
 *
 * @param capabilityKey The key of the capability.
 * @param path The JSON path to the list of strings within the capability's details.
 * @return A list of strings if found, or an empty list if not found or not a string array.
 */
fun LLMModel.getStringListCapability(capabilityKey: String, path: String): List<String> {
    val details = getCapabilityDetails(capabilityKey) ?: return emptyList()
    return details.jsonObject.atPath(path)?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
}

// Helper extension for JsonObject to navigate path
private fun JsonObject.atPath(path: String): JsonElement? {
    var current: JsonElement? = this
    val parts = path.split('.')
    for (part in parts) {
        if (current is JsonObject) {
            current = current[part]
        } else {
            return null // Path segment doesn't match an object or is null
        }
    }
    return current
}
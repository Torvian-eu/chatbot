package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.common.models.api.core.RawLlmRequestPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.round

/**
 * Shared formatter so the dialog and the clipboard action never disagree about how a request is rendered.
 */
private val prettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    isLenient = false
    ignoreUnknownKeys = true
}

/**
 * Renders this request's body as indented JSON.
 *
 * A body that is not valid JSON is returned verbatim, because the viewer must still show a request
 * whose body the provider would not have accepted as JSON either.
 *
 * @return The pretty-printed body, or the raw body text when it is not JSON.
 */
internal fun RawLlmRequestPayload.prettyBodyJson(): String =
    runCatching { prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(body)) }
        .getOrElse { body }

/**
 * Renders the whole request as one indented JSON document, containing the same body text the viewer shows.
 *
 * @return The pretty-printed request with its method, URL, headers and body.
 */
internal fun RawLlmRequestPayload.requestJson(): String {
    val bodyElement = runCatching { Json.parseToJsonElement(body) }.getOrNull()
    val request = buildJsonObject {
        put("method", method)
        put("url", url)
        put(
            "headers",
            buildJsonObject {
                headers.forEach { (name, value) -> put(name, value) }
            }
        )
        put("body", bodyElement ?: JsonPrimitive(body))
    }
    return prettyJson.encodeToString(JsonObject.serializer(), request)
}

/**
 * Measures this request's body in UTF-8 bytes, which is what the provider receives on the wire.
 *
 * Measured from [RawLlmRequestPayload.body] rather than transmitted, so the copied document and the dialog
 * stay derived from the request itself; a plain string length would undercount any non-ASCII character.
 *
 * @receiver Captured request whose body is measured.
 * @return The body size in UTF-8 bytes.
 */
internal fun RawLlmRequestPayload.utf8BodySizeBytes(): Long = body.encodeToByteArray().size.toLong()

/**
 * Renders a body size as a human-readable value that still states the exact byte count.
 *
 * The exact count is kept alongside the rounded unit so a large body stays readable without turning
 * the displayed figure into an approximation of what was actually sent.
 *
 * @receiver Size of a request body in UTF-8 bytes.
 * @return A display string such as `512 B`, `1.2 KB (1234 bytes)` or `2.0 MB (2097152 bytes)`.
 */
internal fun formatBodySize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${formatOneDecimal(bytes / 1024.0)} KB ($bytes bytes)"
    else -> "${formatOneDecimal(bytes / (1024.0 * 1024.0))} MB ($bytes bytes)"
}

/**
 * Rounds a value to one decimal place without depending on a platform locale.
 *
 * @param value Value to round.
 * @return The rounded value, always with one fractional digit (for example `1.0`).
 */
private fun formatOneDecimal(value: Double): String {
    val rounded = round(value * 10) / 10
    val whole = rounded.toLong()
    val tenths = round((rounded - whole) * 10).toLong()
    return "$whole.$tenths"
}

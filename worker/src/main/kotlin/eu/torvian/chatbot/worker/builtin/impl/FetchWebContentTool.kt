package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.common.models.tool.BuiltInToolCatalog
import eu.torvian.chatbot.worker.builtin.BuiltInTool
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import eu.torvian.chatbot.worker.builtin.net.WebFetchRequest
import eu.torvian.chatbot.worker.builtin.net.WebFetchService
import eu.torvian.chatbot.worker.builtin.net.mapWebFetchErrorToToolResult
import eu.torvian.chatbot.worker.builtin.validation.addUnknownParameterErrors
import eu.torvian.chatbot.worker.builtin.validation.builtInToolErrorResult
import eu.torvian.chatbot.worker.builtin.validation.invalidInputResult
import eu.torvian.chatbot.worker.builtin.validation.formatTruncationNotice
import eu.torvian.chatbot.worker.builtin.validation.buildRangeHeader
import eu.torvian.chatbot.worker.builtin.validation.parseOptionalBoolean
import eu.torvian.chatbot.worker.builtin.validation.parseOptionalInt
import eu.torvian.chatbot.worker.builtin.validation.parseOptionalIntOrNull
import eu.torvian.chatbot.worker.builtin.validation.parseOptionalLineRange
import eu.torvian.chatbot.worker.builtin.validation.parseOptionalString
import eu.torvian.chatbot.worker.builtin.validation.parseRequiredString
import eu.torvian.chatbot.worker.builtin.validation.resolveSlice
import eu.torvian.chatbot.worker.builtin.validation.truncateLinesAndBytes
import arrow.core.Either
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Fetches textual content from a public internet URL.
 *
 * The tool is a thin orchestration layer over the shared worker web foundation: it parses the tool
 * input, delegates all URL validation and HTTP transport to [WebFetchService], and only adds the
 * textual concerns (content-type gating, charset-aware decoding, and result shaping). It never
 * performs its own DNS, socket, or redirect logic, so the security policy lives in exactly one place.
 *
 * Binary/non-text responses are rejected rather than emitted as garbage, and decoding failures are
 * surfaced as explicit errors. `returnMode` (`auto`/`text`/`html`) is accepted for forward
 * compatibility but, in this v1, all modes return the decoded body text verbatim (no HTML cleaning).
 *
 * @property fetchService Shared, transport-agnostic web-fetch service (validates URLs, issues GETs,
 *   enforces timeouts and size caps, and follows redirects only when requested).
 */
class FetchWebContentTool(
    private val fetchService: WebFetchService,
) : BuiltInTool {

    override val name: String = "fetch_web_content"
    override val description: String = BuiltInToolCatalog.specFor(name)!!.description
    override val inputSchema: JsonObject = BuiltInToolCatalog.specFor(name)!!.inputSchema

    override suspend fun execute(input: JsonObject, context: BuiltInToolExecutionContext): BuiltInToolExecutionResult {
        // Accumulate all INVALID_INPUT validation errors before failing, so the LLM can see
        // every issue at once instead of fixing them one at a time.
        val validationErrors = mutableListOf<String>()

        // Define the set of known/valid parameter names for this tool
        val validKeys = setOf("url", "timeoutSeconds", "maxDownloadBytes", "maxBytes", "maxLines", "followRedirects", "returnMode", "range")
        addUnknownParameterErrors(input, validKeys, validationErrors)

        val url = parseRequiredString(input, "url", validationErrors)
        if (url != null && url.isBlank()) {
            validationErrors.add("Argument 'url' must not be blank")
        }

        val timeoutSeconds = parseOptionalIntOrNull(input, "timeoutSeconds", validationErrors)
        if (timeoutSeconds != null && timeoutSeconds <= 0) {
            validationErrors.add("Argument 'timeoutSeconds' must be > 0")
        }

        val maxDownloadBytes = parseOptionalInt(input, "maxDownloadBytes", defaultValue = 100000, validationErrors)
        if (maxDownloadBytes <= 0) {
            validationErrors.add("Argument 'maxDownloadBytes' must be > 0")
        }

        val maxBytes = parseOptionalInt(input, "maxBytes", defaultValue = 20000, validationErrors)
        if (maxBytes <= 0) {
            validationErrors.add("Argument 'maxBytes' must be > 0")
        }

        val maxLines = parseOptionalInt(input, "maxLines", defaultValue = 500, validationErrors)
        if (maxLines <= 0) {
            validationErrors.add("Argument 'maxLines' must be > 0")
        }

        val followRedirects = parseOptionalBoolean(input, "followRedirects", defaultValue = true, validationErrors)

        val returnMode = parseOptionalString(input, "returnMode", validationErrors) ?: "auto"
        if (returnMode !in setOf("auto", "text", "html")) {
            validationErrors.add("Invalid 'returnMode' value: $returnMode (expected 'auto', 'text', or 'html')")
        }

        val range = parseOptionalLineRange(input, "range", validationErrors)

        if (validationErrors.isNotEmpty()) {
            return invalidInputResult(validationErrors)
        }

        // --- Delegate to the shared web foundation (no URL/HTTP logic here) -----------------------
        val request = WebFetchRequest(
            url = url!!,
            timeoutSeconds = timeoutSeconds,
            maxBytes = maxDownloadBytes,
            followRedirects = followRedirects,
        )

        val fetchedResult = when (val fetched = fetchService.fetch(request)) {
            is Either.Left -> return mapWebFetchErrorToToolResult(fetched.value)
            is Either.Right -> fetched.value
        }

        // --- Textual gating: never emit binary garbage -------------------------------------------
        val parsed = parseContentType(fetchedResult.contentType)
        val (mediaType, charsetName) = parsed
        if (!isTextualContentType(mediaType)) {
            return builtInToolErrorResult(
                BuiltInToolExecutionError.EXECUTION_FAILED,
                "Response content type '${fetchedResult.contentType ?: "<none>"}' is not textual; refusing to emit binary content."
            )
        }

        val charset = resolveCharset(charsetName)
        val text = decodeText(fetchedResult.bodyBytes, charset)
            ?: return builtInToolErrorResult(
                BuiltInToolExecutionError.EXECUTION_FAILED,
                "Response body could not be decoded as text using charset '${charset.name()}'."
            )

        // --- Shape the result (output + structured details) --------------------------------------
        val allLines = text.lines()
        val (startIdx, endIdx) = resolveSlice(range, allLines.size)
        val selected = allLines.subList(startIdx, endIdx)
        val rawBody = selected.joinToString("\n")
        val truncationResult = truncateLinesAndBytes(rawBody, maxLines, maxBytes)
        val body = truncationResult.text
        val linesShown = truncationResult.linesShown
        val bytesShown = truncationResult.bytesShown
        val truncated = truncationResult.isTruncated

        val notice = if (truncated) {
            formatTruncationNotice(linesShown, bytesShown, "Use 'range' or")
        } else {
            ""
        }
        val actualEndIdx = startIdx + linesShown
        val header = buildRangeHeader(fetchedResult.finalUrl, startIdx, actualEndIdx, allLines.size)
        val content = if (body.isEmpty()) header else "$header\n$body"
        val output = content + notice

        val details = buildJsonObject {
            put("finalUrl", fetchedResult.finalUrl)
            put("statusCode", fetchedResult.statusCode)
            put("contentType", fetchedResult.contentType)
            put("contentLength", fetchedResult.contentLength)
            put("bytesRead", fetchedResult.bodyBytes.size)
            put("returnMode", returnMode)
            put("totalLines", allLines.size)
            put("truncated", truncated)
        }

        return BuiltInToolExecutionResult(
            output = output,
            details = details,
        )
    }

    /**
     * Splits a raw `Content-Type` header into its lower-cased media type and charset parameter.
     *
     * Only the first segment is treated as the media type; subsequent `name=value` segments are
     * scanned for a `charset` (case-insensitive, tolerant of surrounding quotes). A blank or missing
     * header yields `(null, null)`.
     *
     * @param raw The raw `Content-Type` header value, or null when absent.
     * @return A pair of `(mediaType, charset)`, each possibly null.
     */
    private fun parseContentType(raw: String?): Pair<String?, String?> {
        if (raw.isNullOrBlank()) return null to null
        val segments = raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val mediaType = segments.firstOrNull()?.lowercase()
        var charset: String? = null
        for (segment in segments.drop(1)) {
            val eq = segment.indexOf('=')
            if (eq <= 0) continue
            val name = segment.substring(0, eq).trim().lowercase()
            if (name == "charset") {
                charset = segment.substring(eq + 1).trim().trim('"')
            }
        }
        return mediaType to charset
    }

    /**
     * Decides whether [mediaType] is a clearly textual type the tool is willing to emit as text.
     *
     * Accepts the whole text/&#42; family plus a curated set of application types that are reliably
     * textual (JSON, XML, JavaScript, XHTML, JSON-LD, form-encoded, and SVG). Anything else
     * (including a null media type) is treated as non-textual and rejected to avoid binary garbage.
     *
     * @param mediaType Lower-cased media type, or null when the header was absent.
     * @return True when the type is considered textual.
     */
    private fun isTextualContentType(mediaType: String?): Boolean {
        if (mediaType == null) return false
        if (mediaType.startsWith("text/")) return true
        return mediaType in TEXTUAL_APPLICATION_TYPES
    }

    /**
     * Resolves a charset name to a [Charset], falling back to UTF-8 when the name is blank, missing,
     * or unsupported by the JVM.
     *
     * @param charsetName Optional charset name from the `Content-Type` header.
     * @return A usable [Charset] (UTF-8 by default).
     */
    private fun resolveCharset(charsetName: String?): Charset {
        if (charsetName.isNullOrBlank()) return Charsets.UTF_8
        return runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
    }

    /**
     * Decodes [bytes] as text using a strict [charset] decoder.
     *
     * Malformed or unmappable input is reported (not silently replaced), so binary payloads fail the
     * decode and are rejected by the caller instead of producing mojibake output.
     *
     * @param bytes Raw response body bytes.
     * @param charset Charset to decode with.
     * @return The decoded text, or null when the bytes are not valid for [charset].
     */
    private fun decodeText(bytes: ByteArray, charset: Charset): String? = runCatching {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()

    private companion object {
        /** Application media types that are reliably textual and safe to emit as text. */
        val TEXTUAL_APPLICATION_TYPES: Set<String> = setOf(
            "application/json",
            "application/xml",
            "application/javascript",
            "application/x-javascript",
            "application/xhtml+xml",
            "application/ld+json",
            "application/x-www-form-urlencoded",
            "image/svg+xml",
        )
    }
}
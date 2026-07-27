package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.common.models.tool.BuiltInToolCatalog
import eu.torvian.chatbot.worker.builtin.BuiltInTool
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import eu.torvian.chatbot.worker.builtin.WorkspacePathValidator
import eu.torvian.chatbot.worker.builtin.net.WebFetchRequest
import eu.torvian.chatbot.worker.builtin.net.WebFetchService
import eu.torvian.chatbot.worker.builtin.net.mapWebFetchErrorToToolResult
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Downloads content from a public internet URL directly into a file inside the worker workspace.
 *
 * The tool is a thin orchestration layer over the shared worker web foundation and the shared
 * workspace-safety model: it parses the tool input, delegates all URL validation, redirect handling,
 * and HTTP transport to [WebFetchService], and resolves/writes the destination exclusively through
 * [WorkspacePathValidator] and the standard filesystem API. It never performs its own DNS, socket,
 * redirect, or escape-checking logic, so the security policy lives in exactly one place per concern.
 *
 * Unlike `fetch_web_content`, this tool is binary-safe: it writes the raw response bytes verbatim and
 * performs no content-type gating, so any payload (images, archives, etc.) can be stored unchanged.
 *
 * @property fetchService Shared, transport-agnostic web-fetch service (validates URLs, issues GETs,
 *   enforces timeouts and size caps, and follows redirects only when requested).
 */
class DownloadFileTool(
    private val fetchService: WebFetchService,
) : BuiltInTool {

    override val name: String = "download_file"
    override val description: String = BuiltInToolCatalog.specFor(name)!!.description
    override val inputSchema: JsonObject = BuiltInToolCatalog.specFor(name)!!.inputSchema

    override suspend fun execute(input: JsonObject, context: BuiltInToolExecutionContext): BuiltInToolExecutionResult {
        // Accumulate all INVALID_INPUT validation errors before failing, so the LLM can see
        // every issue at once instead of fixing them one at a time.
        val validationErrors = mutableListOf<String>()

        // Define the set of known/valid parameter names for this tool
        val validKeys = setOf("url", "path", "overwrite", "timeoutSeconds", "maxBytes", "followRedirects")
        // Check for unknown parameters
        for (key in input.keys) {
            if (key !in validKeys) {
                validationErrors.add("Unknown parameter: '$key'")
            }
        }

        val url = input["url"]?.jsonPrimitive?.content
        if (url == null) {
            validationErrors.add("Missing required argument: url")
        } else if (url.isBlank()) {
            validationErrors.add("Argument 'url' must not be blank")
        }

        val path = input["path"]?.jsonPrimitive?.content
        if (path == null) {
            validationErrors.add("Missing required argument: path")
        } else if (path.isBlank()) {
            validationErrors.add("Argument 'path' must not be blank")
        }

        val overwrite = when (val raw = input["overwrite"]?.jsonPrimitive?.content) {
            null -> false
            else -> raw.toBooleanStrictOrNull() ?: run {
                validationErrors.add("Argument 'overwrite' must be a boolean (true/false)")
                null
            }
        }

        val timeoutSeconds = input["timeoutSeconds"]?.jsonPrimitive?.content?.toIntOrNull()
        if (timeoutSeconds != null && timeoutSeconds <= 0) {
            validationErrors.add("Argument 'timeoutSeconds' must be > 0")
        }

        val maxBytes = input["maxBytes"]?.jsonPrimitive?.content?.toIntOrNull()
        if (maxBytes != null && maxBytes <= 0) {
            validationErrors.add("Argument 'maxBytes' must be > 0")
        }

        val followRedirects = when (val raw = input["followRedirects"]?.jsonPrimitive?.content) {
            null -> true
            else -> raw.toBooleanStrictOrNull() ?: run {
                validationErrors.add("Argument 'followRedirects' must be a boolean (true/false)")
                null
            }
        }

        if (validationErrors.isNotEmpty()) {
            return errorResult(
                BuiltInToolExecutionError.INVALID_INPUT,
                "Input validation failed with ${validationErrors.size} error(s):",
                errorDetails = buildJsonObject {
                    putJsonArray("validationErrors") {
                        validationErrors.forEach { error -> add(error) }
                    }
                }.toString()
            )
        }

        // --- Resolve & validate the destination through the shared workspace model -----------------
        val target = try {
            WorkspacePathValidator.requireInside(context.workspace, path!!)
        } catch (e: Exception) {
            return errorResult(
                BuiltInToolExecutionError.WORKSPACE_VIOLATION,
                e.message ?: "Path rejected by workspace validator"
            )
        }

        // --- Delegate to the shared web foundation (no URL/HTTP logic here) -----------------------
        val request = WebFetchRequest(
            url = url!!,
            timeoutSeconds = timeoutSeconds,
            maxBytes = maxBytes,
            followRedirects = followRedirects!!,
        )

        val result = when (val fetched = fetchService.fetch(request)) {
            is arrow.core.Either.Left -> return mapWebFetchErrorToToolResult(fetched.value)
            is arrow.core.Either.Right -> fetched.value
        }

        return withContext(context.ioDispatcher) {
            try {
                // Reject writing onto an existing directory; a regular file is only overwritten when
                // explicitly requested, otherwise it is a conflict (ALREADY_EXISTS).
                if (target.exists() && target.isDirectory()) {
                    return@withContext errorResult(
                        BuiltInToolExecutionError.EXECUTION_FAILED,
                        "Destination '$path' is a directory, not a file"
                    )
                }
                val alreadyExisted = target.exists()
                if (alreadyExisted && !overwrite!!) {
                    return@withContext errorResult(
                        BuiltInToolExecutionError.ALREADY_EXISTS,
                        "Destination '$path' already exists and overwrite is false"
                    )
                }

                // Create parent directories automatically so nested paths work without manual setup.
                Files.createDirectories(target.parent ?: context.workspace)
                // Write the raw bytes exactly as received; binary-safe, no decoding or content gating.
                Files.write(target, result.bodyBytes)

                val details = buildJsonObject {
                    put("finalUrl", result.finalUrl)
                    put("statusCode", result.statusCode)
                    put("contentType", result.contentType)
                    put("contentLength", result.contentLength)
                    put("bytesRead", result.bodyBytes.size)
                    put("path", path)
                    put("overwritten", alreadyExisted)
                }
                BuiltInToolExecutionResult(
                    output = "Downloaded ${result.bodyBytes.size} byte(s) from ${result.finalUrl} to $path",
                    details = details,
                )
            } catch (e: Exception) {
                errorResult(BuiltInToolExecutionError.EXECUTION_FAILED, "Failed to write file: ${e.message}")
            }
        }
    }

    private fun errorResult(code: String, message: String, errorDetails: String? = null): BuiltInToolExecutionResult =
        BuiltInToolExecutionResult(isError = true, errorMessage = message, errorCode = code, errorDetails = errorDetails)
}
package eu.torvian.chatbot.worker.builtin.impl

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import eu.torvian.chatbot.worker.builtin.net.WebFetchError
import eu.torvian.chatbot.worker.builtin.net.WebFetchRequest
import eu.torvian.chatbot.worker.builtin.net.WebFetchResult
import eu.torvian.chatbot.worker.builtin.net.WebFetchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validation-focused unit tests for the new `fetch_web_content` search parameters.
 *
 * These lock down the mutual-exclusion and dependency rules introduced with `searchQuery`: it cannot
 * be combined with `range`, its family of parameters requires it to be present, `wholeWord` is
 * rejected in regex mode, blank queries are rejected, and malformed values accumulate alongside the
 * other rule violations before any network I/O is attempted.
 */
class FetchWebContentToolSearchValidationTest {

    /** A scriptable [WebFetchService] that records requests and fails if ever invoked. */
    private class RecordingFakeService : WebFetchService {
        val requests = mutableListOf<WebFetchRequest>()
        override suspend fun fetch(request: WebFetchRequest): Either<WebFetchError, WebFetchResult> {
            requests.add(request)
            throw AssertionError("validation must fail before the fetch service is called")
        }
    }

    private fun toolWith(fake: RecordingFakeService) = FetchWebContentTool(fetchService = fake)

    private fun context() = BuiltInToolExecutionContext(
        workspace = createTempDirectory("fetch-search-validation"),
        defaultCommandTimeoutSeconds = 60,
        defaultSearchTimeoutSeconds = 5,
        ioDispatcher = Dispatchers.IO,
    )

    private fun input(vararg pairs: Pair<String, JsonElement>): JsonObject = buildJsonObject {
        for ((k, v) in pairs) put(k, v)
    }

    private fun assertInvalidInput(result: BuiltInToolExecutionResult): JsonObject {
        assertTrue(result.isError, "Expected error but got success: ${result.output}")
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result.errorCode)
        val details = Json.parseToJsonElement(result.errorDetails!!).jsonObject
        return details
    }

    private fun validationMessages(details: JsonObject): List<String> =
        details["validationErrors"]!!.jsonArray.map { it.jsonPrimitive.content }

    // ---------------------------------------------------------------------------------------------
    // Mutual exclusivity & dependency rules
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `range and searchQuery together is invalid input`() = runTest {
        val fake = RecordingFakeService()
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "searchQuery" to JsonPrimitive("foo"),
                "range" to buildJsonArray { add(0); add(10) },
            ),
            context(),
        )
        val details = assertInvalidInput(result)
        assertTrue(validationMessages(details).any { it.contains("Only one of 'range' and 'searchQuery'") })
        assertEquals(0, fake.requests.size, "fetch service must not be invoked on validation failure")
    }

    @Test
    fun `searchMode without searchQuery is invalid input`() = runTest {
        val fake = RecordingFakeService()
        val result = toolWith(fake).execute(
            input("url" to JsonPrimitive("https://example.com/"), "searchMode" to JsonPrimitive("plain")),
            context(),
        )
        val details = assertInvalidInput(result)
        assertTrue(validationMessages(details).any { it.contains("Argument 'searchMode' can only be used when 'searchQuery' is specified") })
    }

    @Test
    fun `contextBefore without searchQuery is invalid input`() = runTest {
        val fake = RecordingFakeService()
        val result = toolWith(fake).execute(
            input("url" to JsonPrimitive("https://example.com/"), "contextBefore" to JsonPrimitive(1)),
            context(),
        )
        val details = assertInvalidInput(result)
        assertTrue(validationMessages(details).any { it.contains("Argument 'contextBefore' can only be used when 'searchQuery' is specified") })
    }

    @Test
    fun `contextAfter without searchQuery is invalid input`() = runTest {
        val fake = RecordingFakeService()
        val result = toolWith(fake).execute(
            input("url" to JsonPrimitive("https://example.com/"), "contextAfter" to JsonPrimitive(1)),
            context(),
        )
        val details = assertInvalidInput(result)
        assertTrue(validationMessages(details).any { it.contains("Argument 'contextAfter' can only be used when 'searchQuery' is specified") })
    }

    @Test
    fun `maxResults without searchQuery is invalid input`() = runTest {
        val fake = RecordingFakeService()
        val result = toolWith(fake).execute(
            input("url" to JsonPrimitive("https://example.com/"), "maxResults" to JsonPrimitive(5)),
            context(),
        )
        val details = assertInvalidInput(result)
        assertTrue(validationMessages(details).any { it.contains("Argument 'maxResults' can only be used when 'searchQuery' is specified") })
    }

    @Test
    fun `caseSensitive without searchQuery is invalid input`() = runTest {
        val fake = RecordingFakeService()
        val result = toolWith(fake).execute(
            input("url" to JsonPrimitive("https://example.com/"), "caseSensitive" to JsonPrimitive(true)),
            context(),
        )
        val details = assertInvalidInput(result)
        assertTrue(validationMessages(details).any { it.contains("Argument 'caseSensitive' can only be used when 'searchQuery' is specified") })
    }

    @Test
    fun `wholeWord without searchQuery is invalid input`() = runTest {
        val fake = RecordingFakeService()
        val result = toolWith(fake).execute(
            input("url" to JsonPrimitive("https://example.com/"), "wholeWord" to JsonPrimitive(true)),
            context(),
        )
        val details = assertInvalidInput(result)
        assertTrue(validationMessages(details).any { it.contains("Argument 'wholeWord' can only be used when 'searchQuery' is specified") })
    }

    // ---------------------------------------------------------------------------------------------
    // Value-level validation
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `blank searchQuery is invalid input`() = runTest {
        val fake = RecordingFakeService()
        val result = toolWith(fake).execute(
            input("url" to JsonPrimitive("https://example.com/"), "searchQuery" to JsonPrimitive("   ")),
            context(),
        )
        val details = assertInvalidInput(result)
        assertTrue(validationMessages(details).any { it == "Argument 'searchQuery' must not be blank" })
    }

    @Test
    fun `wholeWord with regex searchMode is invalid input`() = runTest {
        val fake = RecordingFakeService()
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "searchQuery" to JsonPrimitive("cat"),
                "searchMode" to JsonPrimitive("regex"),
                "wholeWord" to JsonPrimitive(true),
            ),
            context(),
        )
        val details = assertInvalidInput(result)
        assertTrue(validationMessages(details).any { it.contains("Argument 'wholeWord' is only supported in 'plain' mode") })
    }

    @Test
    fun `invalid searchMode is invalid input`() = runTest {
        val fake = RecordingFakeService()
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "searchQuery" to JsonPrimitive("foo"),
                "searchMode" to JsonPrimitive("glob"),
            ),
            context(),
        )
        val details = assertInvalidInput(result)
        assertTrue(validationMessages(details).any { it.contains("Invalid 'searchMode' value: glob") })
    }

    @Test
    fun `malformed search values accumulate alongside dependency errors`() = runTest {
        val fake = RecordingFakeService()
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "maxResults" to JsonPrimitive("notanint"),
                "caseSensitive" to JsonPrimitive("maybe"),
                "contextBefore" to JsonPrimitive(-1),
                "contextAfter" to JsonPrimitive("x"),
            ),
            context(),
        )
        val details = assertInvalidInput(result)
        val messages = validationMessages(details)
        // All family params were supplied without searchQuery, plus the malformed values.
        assertTrue(messages.any { it.contains("Argument 'maxResults' must be an integer") })
        assertTrue(messages.any { it.contains("Argument 'caseSensitive' must be a boolean") })
        assertTrue(messages.any { it.contains("Argument 'contextAfter' must be an integer") })
        assertTrue(messages.any { it.contains("Argument 'maxResults' can only be used when 'searchQuery' is specified") })
        assertTrue(messages.any { it.contains("Argument 'caseSensitive' can only be used when 'searchQuery' is specified") })
        assertTrue(messages.any { it.contains("Argument 'contextBefore' can only be used when 'searchQuery' is specified") })
    }

    @Test
    fun `negative contextBefore with searchQuery is invalid input`() = runTest {
        val fake = RecordingFakeService()
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "searchQuery" to JsonPrimitive("foo"),
                "contextBefore" to JsonPrimitive(-1),
            ),
            context(),
        )
        val details = assertInvalidInput(result)
        assertTrue(validationMessages(details).any { it == "Argument 'contextBefore' must be >= 0" })
    }

    @Test
    fun `zero maxResults with searchQuery is invalid input`() = runTest {
        val fake = RecordingFakeService()
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "searchQuery" to JsonPrimitive("foo"),
                "maxResults" to JsonPrimitive(0),
            ),
            context(),
        )
        val details = assertInvalidInput(result)
        assertTrue(validationMessages(details).any { it == "Argument 'maxResults' must be >= 1" })
    }

    @Test
    fun `unknown parameter is still rejected`() = runTest {
        val fake = RecordingFakeService()
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "searchQuery" to JsonPrimitive("foo"),
                "bogusParam" to JsonPrimitive("x"),
            ),
            context(),
        )
        val details = assertInvalidInput(result)
        assertTrue(validationMessages(details).any { it.contains("Unknown parameter: 'bogusParam'") })
    }
}

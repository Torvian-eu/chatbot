package eu.torvian.chatbot.worker.builtin.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [FetchWebContentTool].
 *
 * The tool is exercised against a scriptable [FakeWebFetchService] so no real network or DNS is
 * touched. These tests lock down the intended v1 semantics: input validation, delegation to the
 * shared fetch service, textual-content gating, charset-aware decoding, and the
 * [WebFetchError] -> [BuiltInToolExecutionResult] mapping.
 */
class FetchWebContentToolTest {

    /** A scriptable [WebFetchService] returning queued results/errors and recording requests. */
    private class FakeWebFetchService(
        private val responses: MutableList<Either<WebFetchError, WebFetchResult>> = mutableListOf(),
    ) : WebFetchService {
        val requests = mutableListOf<WebFetchRequest>()

        override suspend fun fetch(request: WebFetchRequest): Either<WebFetchError, WebFetchResult> {
            requests.add(request)
            return responses.removeFirstOrNull()
                ?: WebFetchError.Transport("no scripted response").left()
        }
    }

    private fun okResult(
        url: String,
        body: String,
        contentType: String = "text/plain",
        contentLength: Long? = body.length.toLong(),
    ): Either<WebFetchError, WebFetchResult> = WebFetchResult(
        finalUrl = url,
        statusCode = 200,
        contentType = contentType,
        contentLength = contentLength,
        bodyBytes = body.toByteArray(),
    ).right()

    private fun toolWith(fake: FakeWebFetchService) = FetchWebContentTool(fetchService = fake)

    private fun context() = BuiltInToolExecutionContext(
        workspace = createTempDirectory("fetch-test"),
        defaultCommandTimeoutSeconds = 60,
        defaultSearchTimeoutSeconds = 5,
        ioDispatcher = Dispatchers.IO,
    )

    private fun input(vararg pairs: Pair<String, JsonElement>): JsonObject = buildJsonObject {
        for ((k, v) in pairs) put(k, v)
    }

    private fun assertSuccess(result: BuiltInToolExecutionResult): BuiltInToolExecutionResult {
        assertFalse(result.isError, "Expected success but got error: ${result.errorMessage}")
        return result
    }

    // ---------------------------------------------------------------------------------------------
    // Successful textual fetch
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `successful textual fetch returns decoded body and details`() = runTest {
        val fake = FakeWebFetchService(mutableListOf(okResult("https://example.com/", "hello world")))
        val result = toolWith(fake).execute(
            input("url" to JsonPrimitive("https://example.com/")),
            context(),
        )

        val success = assertSuccess(result)
        assertEquals("=== https://example.com/ (lines:1 of 1) ===\nhello world", success.output)
        assertEquals("https://example.com/", success.details!!["finalUrl"]?.jsonPrimitive?.content)
        assertEquals(200, success.details!!["statusCode"]?.jsonPrimitive?.int)
        assertEquals("text/plain", success.details!!["contentType"]?.jsonPrimitive?.content)
        assertEquals(11, success.details!!["bytesRead"]?.jsonPrimitive?.int)
        assertEquals("auto", success.details!!["returnMode"]?.jsonPrimitive?.content)
        // The shared service must have been invoked with the parsed request.
        assertEquals(1, fake.requests.size)
        assertEquals("https://example.com/", fake.requests.first().url)
    }

    @Test
    fun `optional parameters are forwarded to the fetch service`() = runTest {
        val fake = FakeWebFetchService(
            mutableListOf(okResult("https://example.com/", "x", contentType = "application/json"))
        )
        toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "timeoutSeconds" to JsonPrimitive(15),
                "followRedirects" to JsonPrimitive(false),
                "returnMode" to JsonPrimitive("text"),
            ),
            context(),
        )

        val req = fake.requests.first()
        assertEquals(15, req.timeoutSeconds)
        // The download cap is a hard limit not controllable by the LLM.
        assertEquals(10 * 1024 * 1024, req.maxBytes)
        assertEquals(false, req.followRedirects)
    }

    // ---------------------------------------------------------------------------------------------
    // Input validation
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `missing url is invalid input`() = runTest {
        val result = toolWith(FakeWebFetchService()).execute(input(), context())
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result.errorCode)
    }

    @Test
    fun `blank url is invalid input`() = runTest {
        val result = toolWith(FakeWebFetchService()).execute(
            input("url" to JsonPrimitive("   ")),
            context(),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result.errorCode)
    }

    @Test
    fun `non-positive timeoutSeconds is invalid input`() = runTest {
        val result = toolWith(FakeWebFetchService()).execute(
            input("url" to JsonPrimitive("https://example.com/"), "timeoutSeconds" to JsonPrimitive(0)),
            context(),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result.errorCode)
    }

    @Test
    fun `non-positive maxBytes is invalid input`() = runTest {
        val result = toolWith(FakeWebFetchService()).execute(
            input("url" to JsonPrimitive("https://example.com/"), "maxBytes" to JsonPrimitive(-5)),
            context(),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result.errorCode)
    }

    @Test
    fun `invalid returnMode is invalid input`() = runTest {
        val result = toolWith(FakeWebFetchService()).execute(
            input("url" to JsonPrimitive("https://example.com/"), "returnMode" to JsonPrimitive("pdf")),
            context(),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result.errorCode)
    }

    @Test
    fun `non-boolean followRedirects is invalid input`() = runTest {
        val result = toolWith(FakeWebFetchService()).execute(
            input("url" to JsonPrimitive("https://example.com/"), "followRedirects" to JsonPrimitive("yes")),
            context(),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result.errorCode)
    }

    // ---------------------------------------------------------------------------------------------
    // WebFetchError mapping
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `InvalidUrl maps to invalid_input`() = runTest {
        val fake = FakeWebFetchService(
            mutableListOf(WebFetchError.InvalidUrl("malformed url").left())
        )
        val result = toolWith(fake).execute(
            input("url" to JsonPrimitive("https://example.com/")),
            context(),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result.errorCode)
    }

    @Test
    fun `SecurityRejected maps to permission_denied`() = runTest {
        val fake = FakeWebFetchService(
            mutableListOf(WebFetchError.SecurityRejected("private host").left())
        )
        val result = toolWith(fake).execute(
            input("url" to JsonPrimitive("https://example.com/")),
            context(),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.PERMISSION_DENIED, result.errorCode)
    }

    @Test
    fun `Timeout maps to timeout`() = runTest {
        val fake = FakeWebFetchService(
            mutableListOf(WebFetchError.Timeout("took too long").left())
        )
        val result = toolWith(fake).execute(
            input("url" to JsonPrimitive("https://example.com/")),
            context(),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.TIMEOUT, result.errorCode)
    }

    @Test
    fun `TooLarge maps to execution_failed`() = runTest {
        val fake = FakeWebFetchService(
            mutableListOf(WebFetchError.TooLarge("too big").left())
        )
        val result = toolWith(fake).execute(
            input("url" to JsonPrimitive("https://example.com/")),
            context(),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.EXECUTION_FAILED, result.errorCode)
    }

    @Test
    fun `HttpError maps to execution_failed with status detail`() = runTest {
        val fake = FakeWebFetchService(
            mutableListOf(WebFetchError.HttpError(404, "not found").left())
        )
        val result = toolWith(fake).execute(
            input("url" to JsonPrimitive("https://example.com/")),
            context(),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.EXECUTION_FAILED, result.errorCode)
        val errorDetails = Json.parseToJsonElement(result.errorDetails!!).jsonObject
        assertEquals(404, errorDetails["statusCode"]?.jsonPrimitive?.int)
    }

    @Test
    fun `Transport maps to execution_failed`() = runTest {
        val fake = FakeWebFetchService(
            mutableListOf(WebFetchError.Transport("connection refused").left())
        )
        val result = toolWith(fake).execute(
            input("url" to JsonPrimitive("https://example.com/")),
            context(),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.EXECUTION_FAILED, result.errorCode)
    }

    // ---------------------------------------------------------------------------------------------
    // Textual gating & decoding
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `non-text content type is rejected`() = runTest {
        val fake = FakeWebFetchService(
            mutableListOf(okResult("https://example.com/a.png", "binary", contentType = "image/png"))
        )
        val result = toolWith(fake).execute(
            input("url" to JsonPrimitive("https://example.com/a.png")),
            context(),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.EXECUTION_FAILED, result.errorCode)
    }

    @Test
    fun `application json is accepted as textual`() = runTest {
        val fake = FakeWebFetchService(
            mutableListOf(okResult("https://example.com/a.json", "{\"a\":1}", contentType = "application/json"))
        )
        val result = toolWith(fake).execute(
            input("url" to JsonPrimitive("https://example.com/a.json")),
            context(),
        )
        val success = assertSuccess(result)
        assertEquals("=== https://example.com/a.json (lines:1 of 1) ===\n{\"a\":1}", success.output)
    }

    @Test
    fun `charset from content type is honored`() = runTest {
        // ISO-8859-1 encoded body containing a non-ASCII byte (0xE9 = é in Latin-1).
        val bodyBytes = byteArrayOf(0x68, 0x65, 0x6C, 0x6C, 0x6F, 0x20, 0xE9.toByte())
        val fake = FakeWebFetchService(
            mutableListOf(
                WebFetchResult(
                    finalUrl = "https://example.com/",
                    statusCode = 200,
                    contentType = "text/plain; charset=ISO-8859-1",
                    contentLength = bodyBytes.size.toLong(),
                    bodyBytes = bodyBytes,
                ).right()
            )
        )
        val result = toolWith(fake).execute(
            input("url" to JsonPrimitive("https://example.com/")),
            context(),
        )
        val success = assertSuccess(result)
        // Decoded with the declared charset, the 0xE9 byte becomes 'é'.
        assertEquals("=== https://example.com/ (lines:1 of 1) ===\nhello é", success.output)
    }

    @Test
    fun `missing charset defaults to UTF-8`() = runTest {
        val body = "café" // contains a multi-byte UTF-8 sequence
        val fake = FakeWebFetchService(
            mutableListOf(okResult("https://example.com/", body, contentType = "text/plain"))
        )
        val result = toolWith(fake).execute(
            input("url" to JsonPrimitive("https://example.com/")),
            context(),
        )
        val success = assertSuccess(result)
        assertEquals("=== https://example.com/ (lines:1 of 1) ===\n$body", success.output)
    }

    @Test
    fun `binary body that is not valid text is rejected`() = runTest {
        // Random bytes that are not valid UTF-8; strict decoding must fail and be rejected.
        val bodyBytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0xAB.toByte())
        val fake = FakeWebFetchService(
            mutableListOf(
                WebFetchResult(
                    finalUrl = "https://example.com/",
                    statusCode = 200,
                    contentType = "text/plain",
                    contentLength = bodyBytes.size.toLong(),
                    bodyBytes = bodyBytes,
                ).right()
            )
        )
        val result = toolWith(fake).execute(
            input("url" to JsonPrimitive("https://example.com/")),
            context(),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.EXECUTION_FAILED, result.errorCode)
    }

    @Test
    fun `html returnMode returns raw html text`() = runTest {
        val html = "<html><body>hi</body></html>"
        val fake = FakeWebFetchService(
            mutableListOf(okResult("https://example.com/", html, contentType = "text/html"))
        )
        val result = toolWith(fake).execute(
            input("url" to JsonPrimitive("https://example.com/"), "returnMode" to JsonPrimitive("html")),
            context(),
        )
        val success = assertSuccess(result)
        assertEquals("=== https://example.com/ (lines:1 of 1) ===\n$html", success.output)
        assertEquals("html", success.details!!["returnMode"]?.jsonPrimitive?.content)
    }

    @Test
    fun `absent content type is rejected as non-textual`() = runTest {
        val fake = FakeWebFetchService(
            mutableListOf(
                WebFetchResult(
                    finalUrl = "https://example.com/",
                    statusCode = 200,
                    contentType = null,
                    contentLength = null,
                    bodyBytes = "data".toByteArray(),
                ).right()
            )
        )
        val result = toolWith(fake).execute(
            input("url" to JsonPrimitive("https://example.com/")),
            context(),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.EXECUTION_FAILED, result.errorCode)
    }

    @Test
    fun `fetch_web_content enforces hard download cap and presentation maxBytes maxLines truncation`() = runTest {
        val longBody = "line1\nline2\nline3\nline4\nline5"
        val fake = FakeWebFetchService(
            mutableListOf(okResult("https://example.com/", longBody))
        )
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "maxLines" to JsonPrimitive(2),
            ),
            context(),
        )
        val success = assertSuccess(result)
        assertTrue(
            success.output!!.startsWith("=== https://example.com/ (lines:1-2 of 5) ==="),
            "Expected range header; got: ${success.output}"
        )
        assertTrue(
            success.output!!.contains("[Output truncated:"),
            "Expected truncation notice; got: ${success.output}"
        )
        assertEquals(true, success.details?.jsonObject?.get("truncated")?.jsonPrimitive?.boolean)
        assertEquals(10 * 1024 * 1024, fake.requests.first().maxBytes)
    }

    @Test
    fun `fetch_web_content with invalid maxBytes or maxLines returns invalid input`() = runTest {
        val fake = FakeWebFetchService(mutableListOf())
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "maxBytes" to JsonPrimitive(0),
            ),
            context(),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result.errorCode)
    }

    @Test
    fun `maxBytes above the presentation cap is invalid input`() = runTest {
        val fake = FakeWebFetchService(mutableListOf())
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "maxBytes" to JsonPrimitive(200001),
            ),
            context(),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result.errorCode)
    }

    @Test
    fun `fetch page with range 0 to 5 returns first 5 lines`() = runTest {
        val lines = (1..20).joinToString("\n") { "line$it" }
        val fake = FakeWebFetchService(
            mutableListOf(okResult("https://example.com/", lines))
        )
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "range" to buildJsonArray { add(0); add(5) }
            ),
            context(),
        )
        val success = assertSuccess(result)
        assertEquals(
            "=== https://example.com/ (lines:1-5 of 20) ===\nline1\nline2\nline3\nline4\nline5",
            success.output
        )
        assertEquals(20, success.details!!["totalLines"]?.jsonPrimitive?.int)
    }

    @Test
    fun `fetch page with range 10 to null returns lines starting from index 10`() = runTest {
        val lines = (1..15).joinToString("\n") { "line$it" }
        val fake = FakeWebFetchService(
            mutableListOf(okResult("https://example.com/", lines))
        )
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "range" to buildJsonArray { add(10); add(JsonNull) }
            ),
            context(),
        )
        val success = assertSuccess(result)
        assertEquals(
            "=== https://example.com/ (lines:11-15 of 15) ===\nline11\nline12\nline13\nline14\nline15",
            success.output
        )
    }

    @Test
    fun `fetch page with negative bounds range minus 10 to null returns last 10 lines`() = runTest {
        val lines = (1..20).joinToString("\n") { "line$it" }
        val fake = FakeWebFetchService(
            mutableListOf(okResult("https://example.com/", lines))
        )
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "range" to buildJsonArray { add(-10); add(JsonNull) }
            ),
            context(),
        )
        val success = assertSuccess(result)
        val expectedBody = (11..20).joinToString("\n") { "line$it" }
        assertEquals("=== https://example.com/ (lines:11-20 of 20) ===\n$expectedBody", success.output)
    }

    @Test
    fun `invalid range shapes return invalid input`() = runTest {
        val fake = FakeWebFetchService(mutableListOf())
        // range is a string instead of array
        val result1 = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "range" to JsonPrimitive("abc")
            ),
            context(),
        )
        assertTrue(result1.isError)
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result1.errorCode)

        // range has only 1 element
        val result2 = toolWith(FakeWebFetchService()).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "range" to buildJsonArray { add(1) }
            ),
            context(),
        )
        assertTrue(result2.isError)
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result2.errorCode)
    }

    @Test
    fun `fetch page with range 10 to 20 displays lines 11 to 20 in header`() = runTest {
        val lines = (1..30).joinToString("\n") { "line$it" }
        val fake = FakeWebFetchService(
            mutableListOf(okResult("https://example.com/", lines))
        )
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "range" to buildJsonArray { add(10); add(20) }
            ),
            context(),
        )
        val success = assertSuccess(result)
        val expectedBody = (11..20).joinToString("\n") { "line$it" }
        assertEquals("=== https://example.com/ (lines:11-20 of 30) ===\n$expectedBody", success.output)
    }

    // ---------------------------------------------------------------------------------------------
    // Search behavior
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `searchQuery plain match returns matching lines with line numbers`() = runTest {
        val body = "alpha\nbeta\ngamma\nbeta again"
        val fake = FakeWebFetchService(mutableListOf(okResult("https://example.com/", body)))
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "searchQuery" to JsonPrimitive("beta"),
                "searchMode" to JsonPrimitive("plain"),
            ),
            context(),
        )
        val success = assertSuccess(result)
        assertTrue(
            success.output!!.startsWith("=== https://example.com/ (search: \"beta\", 2 matches) ==="),
            "output=${success.output}"
        )
        assertTrue(success.output!!.contains("2: beta"), "output=${success.output}")
        assertTrue(success.output!!.contains("4: beta again"), "output=${success.output}")
        assertEquals(2, success.details!!["totalMatches"]?.jsonPrimitive?.int)
    }

    @Test
    fun `searchQuery regex mode matches a pattern`() = runTest {
        val body = "order 123\norder 456\nnote 789"
        val fake = FakeWebFetchService(mutableListOf(okResult("https://example.com/", body)))
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "searchQuery" to JsonPrimitive("order \\d+"),
            ),
            context(),
        )
        val success = assertSuccess(result)
        assertEquals(2, success.details!!["totalMatches"]?.jsonPrimitive?.int)
        assertEquals("regex", success.details!!["searchMode"]?.jsonPrimitive?.content)
    }

    @Test
    fun `searchQuery case sensitivity is honored`() = runTest {
        val body = "Hello World\nhello again"
        val fake = FakeWebFetchService(mutableListOf(okResult("https://example.com/", body)))
        // Default is case-insensitive.
        val insensitive = toolWith(fake).execute(
            input("url" to JsonPrimitive("https://example.com/"), "searchQuery" to JsonPrimitive("hello")),
            context(),
        )
        assertEquals(2, assertSuccess(insensitive).details!!["totalMatches"]?.jsonPrimitive?.int)

        val sensitive = toolWith(FakeWebFetchService(mutableListOf(okResult("https://example.com/", body)))).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "searchQuery" to JsonPrimitive("hello"),
                "caseSensitive" to JsonPrimitive(true),
            ),
            context(),
        )
        assertEquals(1, assertSuccess(sensitive).details!!["totalMatches"]?.jsonPrimitive?.int)
    }

    @Test
    fun `searchQuery whole-word anchoring matches boundaries`() = runTest {
        val body = "cat\ncatalog\ncategory"
        val fake = FakeWebFetchService(mutableListOf(okResult("https://example.com/", body)))
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "searchQuery" to JsonPrimitive("cat"),
                "searchMode" to JsonPrimitive("plain"),
                "wholeWord" to JsonPrimitive(true),
            ),
            context(),
        )
        val success = assertSuccess(result)
        assertEquals(1, success.details!!["totalMatches"]?.jsonPrimitive?.int)
        assertTrue(success.output!!.contains("1: cat"), "output=${success.output}")
    }

    @Test
    fun `searchQuery contextBefore and contextAfter include surrounding lines`() = runTest {
        val body = "line1\nline2\nMATCH\nline4\nline5"
        val fake = FakeWebFetchService(mutableListOf(okResult("https://example.com/", body)))
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "searchQuery" to JsonPrimitive("MATCH"),
                "contextBefore" to JsonPrimitive(1),
                "contextAfter" to JsonPrimitive(1),
            ),
            context(),
        )
        val success = assertSuccess(result)
        val output = success.output!!
        assertTrue(output.contains("2: line2"), "output=$output")
        assertTrue(output.contains("3: MATCH"), "output=$output")
        assertTrue(output.contains("4: line4"), "output=$output")
    }

    @Test
    fun `searchQuery maxResults truncates number of matches`() = runTest {
        val body = (1..20).joinToString("\n") { "match $it" }
        val fake = FakeWebFetchService(mutableListOf(okResult("https://example.com/", body)))
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "searchQuery" to JsonPrimitive("match"),
                "maxResults" to JsonPrimitive(3),
            ),
            context(),
        )
        val success = assertSuccess(result)
        assertEquals(3, success.details!!["totalMatches"]?.jsonPrimitive?.int)
        assertEquals(true, success.details!!["truncated"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `searchQuery maxLines truncates total lines including context and sets truncated`() = runTest {
        val body = "prefix\nneedle one\nneedle two\nneedle three\nneedle four\nsuffix"
        val fake = FakeWebFetchService(mutableListOf(okResult("https://example.com/", body)))
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "searchQuery" to JsonPrimitive("needle"),
                "contextBefore" to JsonPrimitive(1),
                "contextAfter" to JsonPrimitive(1),
                "maxLines" to JsonPrimitive(3),
            ),
            context(),
        )
        val success = assertSuccess(result)
        // maxLines counts total rendered lines including context, so only the first 3 rendered
        // lines survive and the output carries the truncation notice.
        assertTrue(success.output!!.contains("[Output truncated:"), "output=${success.output}")
        assertEquals(true, success.details!!["truncated"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `searchQuery bad regex is invalid input`() = runTest {
        val fake = FakeWebFetchService(mutableListOf(okResult("https://example.com/", "some body")))
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "searchQuery" to JsonPrimitive("(unclosed"),
                "searchMode" to JsonPrimitive("regex"),
            ),
            context(),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result.errorCode)
    }

    @Test
    fun `searchQuery counts all case-insensitive occurrences on a single minified line`() = runTest {
        // Example.com-style minified HTML (all on one line) where "Example" appears four times
        // case-insensitively: title "Example", h1 "Example", "documentation examples", "domains/example".
        val body = "<!doctype html><html><head><title>Example Domain</title></head><body>" +
                "<h1>Example Domain</h1><p>documentation examples</p>" +
                "<a href=\"https://iana.org/domains/example\">Learn more</a></body></html>"
        val fake = FakeWebFetchService(mutableListOf(okResult("https://example.com/", body)))
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "searchQuery" to JsonPrimitive("Example"),
            ),
            context(),
        )
        val success = assertSuccess(result)
        // Case-insensitive default: all four occurrences must be counted, even on a single line.
        assertEquals(4, success.details!!["totalMatches"]?.jsonPrimitive?.int)
        assertEquals("=== https://example.com/ (search: \"Example\", 4 matches) ===", success.output!!.lines().first())
        // The short line is a single span: the four overlapping windows merge into one snippet that
        // shows the matching content with surrounding context, repeated at most once.
        val bodyLines = success.output!!.lines().drop(1)
        assertEquals(1, bodyLines.count { it.startsWith("1: ") })
        // Every occurrence is still visible with surrounding context.
        assertTrue(bodyLines.joinToString("\n").contains("Example Domain"), "output=${success.output}")
        assertTrue(bodyLines.joinToString("\n").contains("documentation examples"), "output=${success.output}")
    }

    @Test
    fun `contextAfter reveals more of a long minified line beyond the default window`() = runTest {
        // A single long line whose match appears at the start; with no context the window is capped
        // at SEARCH_MAX_LINE_CHARS, so the output ends in a '...' with little following text. With
        // contextAfter the window is extended forward by contextAfter * 80 characters, letting the
        // LLM read further along the line.
        val body = "Example " + "x".repeat(1000)

        val noContext = toolWith(FakeWebFetchService(mutableListOf(okResult("https://example.com/", body)))).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "searchQuery" to JsonPrimitive("Example"),
                "contextBefore" to JsonPrimitive(0),
                "contextAfter" to JsonPrimitive(0),
            ),
            context(),
        )
        val noCtxLine = assertSuccess(noContext).output!!.lines().drop(1).single()
        // Default window length (no '...' before since the match starts the line).
        assertEquals("1: " + "Example " + "x".repeat(1000).take(192) + "...", noCtxLine)

        val withContext = toolWith(FakeWebFetchService(mutableListOf(okResult("https://example.com/", body)))).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "searchQuery" to JsonPrimitive("Example"),
                "contextAfter" to JsonPrimitive(2),
            ),
            context(),
        )
        val ctxLine = assertSuccess(withContext).output!!.lines().drop(1).single()
        // contextAfter=2 extends the window by 160 chars: 200 base + 160 = 360 visible chars.
        assertEquals("1: " + "Example " + "x".repeat(1000).take(352) + "...", ctxLine)
    }

    @Test
    fun `long match line consumes context budget leaving nothing for context lines`() = runTest {
        // A long match line followed by a short line. The entire contextAfter budget (80 chars) is
        // consumed revealing the long match line, so the following context line must not appear.
        val body = "Match here " + "x".repeat(500) + "\n" + "after line"
        val fake = FakeWebFetchService(mutableListOf(okResult("https://example.com/", body)))
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "searchQuery" to JsonPrimitive("Match"),
                "contextAfter" to JsonPrimitive(1),
            ),
            context(),
        )
        val success = assertSuccess(result)
        val lines = success.output!!.lines().drop(1)
        assertTrue(lines.any { it.startsWith("1: ") }, "expected the match line: $lines")
        assertTrue(
            lines.none { it.startsWith("2: ") },
            "context line must be dropped when the match line used the whole budget: $lines"
        )
        // The match window itself is extended by the full 80-char budget.
        assertTrue(lines.single().length > 200, "match window should be extended: $lines")
    }

    @Test
    fun `short match line leaves budget which caps long context lines`() = runTest {
        // A short match line does not consume the contextAfter budget, so the following long line
        // appears but is capped at the remaining per-line budget (80 chars).
        val body = "MATCH\n" + "y".repeat(200)
        val fake = FakeWebFetchService(mutableListOf(okResult("https://example.com/", body)))
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "searchQuery" to JsonPrimitive("MATCH"),
                "contextAfter" to JsonPrimitive(1),
            ),
            context(),
        )
        val success = assertSuccess(result)
        val lines = success.output!!.lines().drop(1)
        assertTrue(lines.any { it.startsWith("2: ") }, "context line should appear when budget remains: $lines")
        val contextLine = lines.first { it.startsWith("2: ") }
        // 2: + 80 chars of content + ... (capped at the leftover per-line budget).
        assertEquals("2: " + "y".repeat(80) + "...", contextLine)
    }

    @Test
    fun `a match at line start emits its own context before lines`() = runTest {
        // Two matches on one long line: the first at the line start consumes no before-budget and
        // therefore keeps its own full context-before budget, while the second deep in the line would
        // consume it all. Because each occurrence has an independent budget, the first occurrence's
        // context-before line survives.
        val body = "before line\n" + "match" + "x".repeat(500) + "match"
        val fake = FakeWebFetchService(mutableListOf(okResult("https://example.com/", body)))
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "searchQuery" to JsonPrimitive("match"),
                "contextBefore" to JsonPrimitive(1),
            ),
            context(),
        )
        val success = assertSuccess(result)
        val lines = success.output!!.lines().drop(1)
        assertTrue(
            lines.any { it.startsWith("1: ") },
            "context-before line must appear when a match leaves budget: $lines"
        )
        assertTrue(lines.any { it.startsWith("2: ") }, "match line should appear: $lines")
    }

    @Test
    fun `contextBefore line is trimmed from the start keeping the tail near the match`() = runTest {
        // A long context-before line followed by a short match line. The short match consumes no
        // before-budget, so the leftover 80 chars fund the context line, which is trimmed from the
        // start (keeps the tail nearest the match) rather than the end.
        val body = "PREFIX" + "z".repeat(200) + "\n" + "MATCH"
        val fake = FakeWebFetchService(mutableListOf(okResult("https://example.com/", body)))
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "searchQuery" to JsonPrimitive("MATCH"),
                "contextBefore" to JsonPrimitive(1),
            ),
            context(),
        )
        val success = assertSuccess(result)
        val lines = success.output!!.lines().drop(1)
        val contextLine = lines.first { it.startsWith("1: ") }
        // Keeps the tail of the line (all 'z' suffix) with a leading ellipsis, not a trailing one.
        assertEquals("1: ..." + "z".repeat(80), contextLine)
    }

    @Test
    fun `short context lines allow more lines to be shown within the budget`() = runTest {
        // With contextAfter=1 and one short following line, the whole 80-char budget remains after
        // showing that line; but since only 1 line is requested, exactly one context line is emitted.
        // To exercise variable counting, request several contextAfter lines that are short enough that
        // the budget spans all of them.
        val body = "MATCH\n" + "short1\n" + "short2\n" + "short3"
        val fake = FakeWebFetchService(mutableListOf(okResult("https://example.com/", body)))
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "searchQuery" to JsonPrimitive("MATCH"),
                "contextAfter" to JsonPrimitive(3),
            ),
            context(),
        )
        val success = assertSuccess(result)
        val lines = success.output!!.lines().drop(1)
        // All three short lines fit within the 240-char budget and are shown untrimmed.
        assertTrue(lines.any { it.startsWith("2: short1") }, "missing short1: $lines")
        assertTrue(lines.any { it.startsWith("3: short2") }, "missing short2: $lines")
        assertTrue(lines.any { it.startsWith("4: short3") }, "missing short3: $lines")
    }

    @Test
    fun `each context line subtracts at least one full budget unit`() = runTest {
        // A run of many very short lines after the match. Each line consumes at least 80 chars of the
        // 240-char budget (contextAfter=3), so only 3 of the many short lines are shown, not all of
        // them — otherwise a flood of near-empty lines would bloat the output.
        val body = "MATCH\n" + (1..10).joinToString("\n") { "l$it" }
        val fake = FakeWebFetchService(mutableListOf(okResult("https://example.com/", body)))
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "searchQuery" to JsonPrimitive("MATCH"),
                "contextAfter" to JsonPrimitive(3),
            ),
            context(),
        )
        val success = assertSuccess(result)
        val lines = success.output!!.lines().drop(1)
        // Only the first 3 short lines are shown (each consuming 80 of the 240-char budget).
        assertTrue(lines.any { it.startsWith("2: l1") }, "missing l1: $lines")
        assertTrue(lines.any { it.startsWith("3: l2") }, "missing l2: $lines")
        assertTrue(lines.any { it.startsWith("4: l3") }, "missing l3: $lines")
        assertTrue(lines.none { it.startsWith("5: ") }, "budget should cap at 3 lines: $lines")
    }

    @Test
    fun `far-apart occurrences on one long line render non-overlapping windowed snippets`() = runTest {
        // A minified single-line page longer than SEARCH_MAX_LINE_CHARS with several case-insensitive
        // occurrences of "Example" spread far apart (>200 chars), so their windows do not overlap and
        // remain separate snippets with surrounding context. Snippets must not overlap.
        val body = "Example " + "x".repeat(300) +
                " second Example " + "y".repeat(300) +
                " third Example " + "z".repeat(300) +
                " fourth Example"
        val fake = FakeWebFetchService(mutableListOf(okResult("https://example.com/", body)))
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "searchQuery" to JsonPrimitive("Example"),
                "contextBefore" to JsonPrimitive(0),
                "contextAfter" to JsonPrimitive(0),
            ),
            context(),
        )
        val success = assertSuccess(result)
        assertEquals(4, success.details!!["totalMatches"]?.jsonPrimitive?.int)
        val bodyLines = success.output!!.lines().drop(1)
        // Each occurrence is rendered on its own output line (all from source line 1).
        assertEquals(4, bodyLines.count { it.startsWith("1: ") })
        // Every occurrence of the match text appears exactly once across the output (no overlap).
        val occurrencesInOutput = bodyLines.sumOf { Regex("Example").findAll(it).count() }
        assertEquals(4, occurrencesInOutput, "match text must appear exactly once per occurrence: $bodyLines")
        // The first snippet starts at the line start (no leading ellipsis) and, because more content
        // follows, carries a trailing ellipsis.
        assertTrue(
            bodyLines.first().startsWith("1: Example "),
            "first snippet should start with the match: ${bodyLines.first()}"
        )
        assertTrue(
            bodyLines.first().endsWith("..."),
            "first snippet should have a trailing ellipsis: ${bodyLines.first()}"
        )
        // Continuation snippets are bridged with a leading ellipsis (content precedes them).
        assertEquals(
            3,
            bodyLines.drop(1).count { it.startsWith("1: ...") },
            "expected 3 continuation snippets with ellipsis: $bodyLines"
        )
    }

    @Test
    fun `searchQuery with no matches returns search header and zero matches`() = runTest {
        val body = "alpha\nbeta\ngamma"
        val fake = FakeWebFetchService(mutableListOf(okResult("https://example.com/", body)))
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "searchQuery" to JsonPrimitive("zzz"),
            ),
            context(),
        )
        val success = assertSuccess(result)
        assertEquals(0, success.details!!["totalMatches"]?.jsonPrimitive?.int)
        assertTrue(
            success.output!!.startsWith("=== https://example.com/ (search: \"zzz\", 0 matches) ==="),
            "output=${success.output}"
        )
    }
}

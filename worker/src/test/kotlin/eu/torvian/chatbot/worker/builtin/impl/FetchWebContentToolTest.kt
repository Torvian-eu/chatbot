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
        assertEquals("hello world", success.output)
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
                "maxBytes" to JsonPrimitive(2048),
                "followRedirects" to JsonPrimitive(false),
                "returnMode" to JsonPrimitive("text"),
            ),
            context(),
        )

        val req = fake.requests.first()
        assertEquals(15, req.timeoutSeconds)
        assertEquals(2048, req.maxBytes)
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
        assertEquals("{\"a\":1}", success.output)
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
        assertEquals("hello é", success.output)
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
        assertEquals(body, success.output)
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
        assertEquals(html, success.output)
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
}

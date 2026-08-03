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
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [DownloadFileTool].
 *
 * The tool is exercised against a scriptable [FakeWebFetchService] so no real network or DNS is
 * touched, and against a real temporary workspace directory so filesystem behavior (creation,
 * overwrite, escape rejection) is verified end-to-end. These tests lock down the intended v1
 * semantics: input validation, delegation to the shared fetch service, workspace containment, and
 * the [WebFetchError] -> [BuiltInToolExecutionResult] mapping.
 */
class DownloadFileToolTest {

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
        body: ByteArray,
        contentType: String = "application/octet-stream",
        contentLength: Long? = body.size.toLong(),
    ): Either<WebFetchError, WebFetchResult> = WebFetchResult(
        finalUrl = url,
        statusCode = 200,
        contentType = contentType,
        contentLength = contentLength,
        bodyBytes = body,
    ).right()

    private fun toolWith(fake: FakeWebFetchService) = DownloadFileTool(fetchService = fake)

    private fun context(workspace: java.nio.file.Path) = BuiltInToolExecutionContext(
        workspace = workspace,
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
    // Successful download
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `successful download writes bytes to a new file`() = runTest {
        val ws = createTempDirectory("dl-test")
        val fake = FakeWebFetchService(
            mutableListOf(okResult("https://example.com/a.bin", "hello".toByteArray()))
        )
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/a.bin"),
                "path" to JsonPrimitive("a.bin"),
            ),
            context(ws),
        )

        val success = assertSuccess(result)
        val file = ws.resolve("a.bin")
        assertTrue(file.exists(), "file should have been created")
        assertEquals("hello", file.readBytes().decodeToString())
        assertEquals("a.bin", success.details!!["path"]?.jsonPrimitive?.content)
        assertEquals(false, success.details!!["overwritten"]?.jsonPrimitive?.boolean)
        assertEquals(5, success.details!!["bytesRead"]?.jsonPrimitive?.int)
    }

    @Test
    fun `binary bytes are written unchanged`() = runTest {
        val ws = createTempDirectory("dl-test")
        // Arbitrary non-text bytes, including a zero byte and high bytes.
        val bytes = byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0x7F, 0x80.toByte(), 0x42)
        val fake = FakeWebFetchService(
            mutableListOf(okResult("https://example.com/b.bin", bytes, contentType = "image/png"))
        )
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/b.bin"),
                "path" to JsonPrimitive("b.bin"),
            ),
            context(ws),
        )

        val success = assertSuccess(result)
        assertEquals(bytes.toList(), ws.resolve("b.bin").readBytes().toList())
        assertEquals("image/png", success.details!!["contentType"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parent directories are created automatically`() = runTest {
        val ws = createTempDirectory("dl-test")
        val fake = FakeWebFetchService(
            mutableListOf(okResult("https://example.com/nested.bin", "x".toByteArray()))
        )
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/nested.bin"),
                "path" to JsonPrimitive("sub/deep/nested.bin"),
            ),
            context(ws),
        )

        assertSuccess(result)
        val file = ws.resolve("sub/deep/nested.bin")
        assertTrue(file.exists(), "nested file should have been created")
        assertEquals("x", file.readBytes().decodeToString())
    }

    @Test
    fun `overwrite true replaces an existing file`() = runTest {
        val ws = createTempDirectory("dl-test")
        ws.resolve("c.bin").writeText("old content")
        val fake = FakeWebFetchService(
            mutableListOf(okResult("https://example.com/c.bin", "new content".toByteArray()))
        )
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/c.bin"),
                "path" to JsonPrimitive("c.bin"),
                "overwrite" to JsonPrimitive(true),
            ),
            context(ws),
        )

        val success = assertSuccess(result)
        assertEquals("new content", ws.resolve("c.bin").readBytes().decodeToString())
        assertEquals(true, success.details!!["overwritten"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `existing destination without overwrite is rejected`() = runTest {
        val ws = createTempDirectory("dl-test")
        ws.resolve("d.bin").writeText("existing")
        val fake = FakeWebFetchService(
            mutableListOf(okResult("https://example.com/d.bin", "fresh".toByteArray()))
        )
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/d.bin"),
                "path" to JsonPrimitive("d.bin"),
            ),
            context(ws),
        )

        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.ALREADY_EXISTS, result.errorCode)
        // The original content must be untouched.
        assertEquals("existing", ws.resolve("d.bin").readBytes().decodeToString())
    }

    @Test
    fun `optional parameters are forwarded to the fetch service`() = runTest {
        val ws = createTempDirectory("dl-test")
        val fake = FakeWebFetchService(
            mutableListOf(okResult("https://example.com/e.bin", "y".toByteArray()))
        )
        toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/e.bin"),
                "path" to JsonPrimitive("e.bin"),
                "timeoutSeconds" to JsonPrimitive(20),
                "followRedirects" to JsonPrimitive(false),
            ),
            context(ws),
        )

        val req = fake.requests.first()
        assertEquals(20, req.timeoutSeconds)
        // The download cap is a hard limit not controllable by the LLM.
        assertEquals(10 * 1024 * 1024, req.maxBytes)
        assertEquals(false, req.followRedirects)
    }

    // ---------------------------------------------------------------------------------------------
    // Input validation
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `missing url is invalid input`() = runTest {
        val ws = createTempDirectory("dl-test")
        val result = toolWith(FakeWebFetchService()).execute(
            input("path" to JsonPrimitive("a.bin")),
            context(ws),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result.errorCode)
    }

    @Test
    fun `blank url is invalid input`() = runTest {
        val ws = createTempDirectory("dl-test")
        val result = toolWith(FakeWebFetchService()).execute(
            input("url" to JsonPrimitive("   "), "path" to JsonPrimitive("a.bin")),
            context(ws),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result.errorCode)
    }

    @Test
    fun `missing path is invalid input`() = runTest {
        val ws = createTempDirectory("dl-test")
        val result = toolWith(FakeWebFetchService()).execute(
            input("url" to JsonPrimitive("https://example.com/a.bin")),
            context(ws),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result.errorCode)
    }

    @Test
    fun `blank path is invalid input`() = runTest {
        val ws = createTempDirectory("dl-test")
        val result = toolWith(FakeWebFetchService()).execute(
            input("url" to JsonPrimitive("https://example.com/a.bin"), "path" to JsonPrimitive("  ")),
            context(ws),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result.errorCode)
    }

    @Test
    fun `non-positive timeoutSeconds is invalid input`() = runTest {
        val ws = createTempDirectory("dl-test")
        val result = toolWith(FakeWebFetchService()).execute(
            input(
                "url" to JsonPrimitive("https://example.com/a.bin"),
                "path" to JsonPrimitive("a.bin"),
                "timeoutSeconds" to JsonPrimitive(0),
            ),
            context(ws),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result.errorCode)
    }

    @Test
    fun `maxBytes is not a valid argument and is rejected as unknown`() = runTest {
        val ws = createTempDirectory("dl-test")
        val result = toolWith(FakeWebFetchService()).execute(
            input(
                "url" to JsonPrimitive("https://example.com/a.bin"),
                "path" to JsonPrimitive("a.bin"),
                "maxBytes" to JsonPrimitive(-1),
            ),
            context(ws),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result.errorCode)
    }

    @Test
    fun `non-boolean overwrite is invalid input`() = runTest {
        val ws = createTempDirectory("dl-test")
        val result = toolWith(FakeWebFetchService()).execute(
            input(
                "url" to JsonPrimitive("https://example.com/a.bin"),
                "path" to JsonPrimitive("a.bin"),
                "overwrite" to JsonPrimitive("maybe"),
            ),
            context(ws),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result.errorCode)
    }

    @Test
    fun `non-boolean followRedirects is invalid input`() = runTest {
        val ws = createTempDirectory("dl-test")
        val result = toolWith(FakeWebFetchService()).execute(
            input(
                "url" to JsonPrimitive("https://example.com/a.bin"),
                "path" to JsonPrimitive("a.bin"),
                "followRedirects" to JsonPrimitive("yes"),
            ),
            context(ws),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result.errorCode)
    }

    // ---------------------------------------------------------------------------------------------
    // Workspace safety
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `path escaping the workspace is rejected`() = runTest {
        val ws = createTempDirectory("dl-test")
        val fake = FakeWebFetchService(
            mutableListOf(okResult("https://example.com/a.bin", "data".toByteArray()))
        )
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/a.bin"),
                "path" to JsonPrimitive("../escape.bin"),
            ),
            context(ws),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.WORKSPACE_VIOLATION, result.errorCode)
    }

    // ---------------------------------------------------------------------------------------------
    // WebFetchError mapping
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `InvalidUrl maps to invalid_input`() = runTest {
        val ws = createTempDirectory("dl-test")
        val fake = FakeWebFetchService(
            mutableListOf(WebFetchError.InvalidUrl("malformed url").left())
        )
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/a.bin"),
                "path" to JsonPrimitive("a.bin"),
            ),
            context(ws),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result.errorCode)
    }

    @Test
    fun `SecurityRejected maps to permission_denied`() = runTest {
        val ws = createTempDirectory("dl-test")
        val fake = FakeWebFetchService(
            mutableListOf(WebFetchError.SecurityRejected("private host").left())
        )
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/a.bin"),
                "path" to JsonPrimitive("a.bin"),
            ),
            context(ws),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.PERMISSION_DENIED, result.errorCode)
    }

    @Test
    fun `Timeout maps to timeout`() = runTest {
        val ws = createTempDirectory("dl-test")
        val fake = FakeWebFetchService(
            mutableListOf(WebFetchError.Timeout("took too long").left())
        )
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/a.bin"),
                "path" to JsonPrimitive("a.bin"),
            ),
            context(ws),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.TIMEOUT, result.errorCode)
    }

    @Test
    fun `TooLarge maps to execution_failed`() = runTest {
        val ws = createTempDirectory("dl-test")
        val fake = FakeWebFetchService(
            mutableListOf(WebFetchError.TooLarge("too big").left())
        )
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/a.bin"),
                "path" to JsonPrimitive("a.bin"),
            ),
            context(ws),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.EXECUTION_FAILED, result.errorCode)
    }

    @Test
    fun `HttpError maps to execution_failed with status detail`() = runTest {
        val ws = createTempDirectory("dl-test")
        val fake = FakeWebFetchService(
            mutableListOf(WebFetchError.HttpError(403, "forbidden").left())
        )
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/a.bin"),
                "path" to JsonPrimitive("a.bin"),
            ),
            context(ws),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.EXECUTION_FAILED, result.errorCode)
        val errorDetails = Json.parseToJsonElement(result.errorDetails!!).jsonObject
        assertEquals(403, errorDetails["statusCode"]?.jsonPrimitive?.int)
    }

    @Test
    fun `Transport maps to execution_failed`() = runTest {
        val ws = createTempDirectory("dl-test")
        val fake = FakeWebFetchService(
            mutableListOf(WebFetchError.Transport("connection refused").left())
        )
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/a.bin"),
                "path" to JsonPrimitive("a.bin"),
            ),
            context(ws),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.EXECUTION_FAILED, result.errorCode)
    }
}


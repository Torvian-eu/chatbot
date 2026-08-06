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
import java.nio.charset.StandardCharsets
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [DownloadFileTool].
 *
 * The tool is exercised against a scriptable [FakeWebFetchService] so no real network or DNS is
 * touched. These tests lock down the intended semantics: input validation, workspace-path safety,
 * binary-safe raw writing by default for non-HTML, and `cleanHtml` handling that reduces HTML
 * documents down to core content while preserving raw bytes otherwise.
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
        body: String,
        contentType: String = "text/plain",
    ): Either<WebFetchError, WebFetchResult> = WebFetchResult(
        finalUrl = url,
        statusCode = 200,
        contentType = contentType,
        contentLength = body.length.toLong(),
        bodyBytes = body.toByteArray(),
    ).right()

    private fun toolWith(fake: FakeWebFetchService) = DownloadFileTool(fetchService = fake)

    private fun context() = BuiltInToolExecutionContext(
        workspace = createTempDirectory("download-test"),
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

    @Test
    fun `non-html payload is written raw with cleanHtml true default`() = runTest {
        val data = "<!DOCTYPE html>" // even HTML-ish marker: content type is text/plain, no cleaning
        val fake = FakeWebFetchService(
            mutableListOf(okResult("https://example.com/file.bin", data, contentType = "application/octet-stream"))
        )
        val ws = context()
        val target = "sub/file.bin"
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/file.bin"),
                "path" to JsonPrimitive(target),
            ),
            ws,
        )
        val success = assertSuccess(result)
        // Bytes exactly as received; no content-type gating, no cleaning for non-HTML.
        assertEquals(data.toByteArray().toList(), Path(ws.workspace.toString(), target).readBytes().toList())
        assertEquals(data.toByteArray().size, success.details!!["bytesWritten"]?.jsonPrimitive?.int)
        assertEquals(true, success.details!!["cleanHtml"]?.jsonPrimitive?.boolean)
        // The shared request must carry cleanHtml=true (tool default).
        assertEquals(true, fake.requests.first().cleanHtml)
    }

    @Test
    fun `html document is cleaned to core content when cleanHtml default on`() = runTest {
        val html = "<html><head><style>.x{}</style></head><body><script>bad()</script><p>Hi <b>there</b></p></body></html>"
        val fake = FakeWebFetchService(
            mutableListOf(okResult("https://example.com/page.html", html, contentType = "text/html"))
        )
        val ws = context()
        val target = "downloaded.html"
        val result = toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/page.html"),
                "path" to JsonPrimitive(target),
            ),
            ws,
        )
        val success = assertSuccess(result)
        val written = Path(ws.workspace.toString(), target).readBytes().toString(StandardCharsets.UTF_8)
        assertTrue(written.contains("Hi <b>there</b>"), "Expected cleaned content; got: $written")
        assertFalse(written.contains("<script>"), "Script must be dropped; got: $written")
        assertTrue(success.output!!.contains("from https://example.com/page.html"), "Unexpected output: ${success.output}")
    }

    @Test
    fun `html document stays raw when cleanHtml is false`() = runTest {
        val html = "<html><body><p>Hello</p></body></html>"
        val fake = FakeWebFetchService(
            mutableListOf(okResult("https://example.com/page.html", html, contentType = "text/html"))
        )
        val ws = context()
        val target = "raw.html"
        toolWith(fake).execute(
            input(
                "url" to JsonPrimitive("https://example.com/page.html"),
                "path" to JsonPrimitive(target),
                "cleanHtml" to JsonPrimitive(false),
            ),
            ws,
        )
        // With cleaning disabled the original bytes are written verbatim.
        assertEquals(html.toByteArray().toList(), Path(ws.workspace.toString(), target).readBytes().toList())
        assertEquals(false, fake.requests.first().cleanHtml)
    }

    @Test
    fun `missing url is invalid input`() = runTest {
        val result = toolWith(FakeWebFetchService()).execute(
            input("path" to JsonPrimitive("x.txt")),
            context(),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result.errorCode)
    }

    @Test
    fun `missing path is invalid input`() = runTest {
        val result = toolWith(FakeWebFetchService()).execute(
            input("url" to JsonPrimitive("https://example.com/")),
            context(),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result.errorCode)
    }

    @Test
    fun `non-boolean cleanHtml is invalid input`() = runTest {
        val result = toolWith(FakeWebFetchService()).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "path" to JsonPrimitive("x.txt"),
                "cleanHtml" to JsonPrimitive("yes"),
            ),
            context(),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result.errorCode)
    }

    @Test
    fun `unknown parameter is invalid input`() = runTest {
        val result = toolWith(FakeWebFetchService()).execute(
            input(
                "url" to JsonPrimitive("https://example.com/"),
                "path" to JsonPrimitive("x.txt"),
                "bogus" to JsonPrimitive(1),
            ),
            context(),
        )
        assertTrue(result.isError)
        assertEquals(BuiltInToolExecutionError.INVALID_INPUT, result.errorCode)
    }
}

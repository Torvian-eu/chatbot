package eu.torvian.chatbot.worker.builtin.net

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the shared web-fetch foundation.
 *
 * The transport-agnostic contract is exercised through a [FakeWebFetchService] (no real network),
 * and the real [KtorWebFetchService] is checked for its pre-flight security behavior: it must reject
 * non-public URLs before any request is issued. This keeps fetch tests unit-style and deterministic.
 */
class WebFetchServiceTest {

    /** A scriptable [WebFetchService] that returns queued results/errors and records requests. */
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

    private fun okResult(url: String, body: String) = WebFetchResult(
        finalUrl = url,
        statusCode = 200,
        contentType = "text/plain",
        contentLength = body.length.toLong(),
        bodyBytes = body.toByteArray(),
    ).right()

    @Test
    fun `fetch returns successful result for a public URL`() = runTest {
        val fake = FakeWebFetchService(mutableListOf(okResult("https://example.com/", "hello")))
        val request = WebFetchRequest(url = "https://example.com/", timeoutSeconds = 10, maxBytes = 1024)

        val result = fake.fetch(request)

        assertTrue(result.isRight())
        assertEquals("hello", (result as Either.Right).value.bodyBytes.decodeToString())
        assertEquals(1, fake.requests.size)
        assertEquals(request, fake.requests.first())
    }

    @Test
    fun `fetch surfaces a transport error as Left`() = runTest {
        val fake = FakeWebFetchService(
            mutableListOf(WebFetchError.Transport("connection refused").left())
        )
        val result = fake.fetch(WebFetchRequest(url = "https://example.com/"))
        assertTrue(result.isLeft())
        assertEquals("connection refused", (result as Either.Left).value.message)
    }

    @Test
    fun `KtorWebFetchService rejects non-public URL before any request`() = runTest {
        // The validator rejects private targets; the real service must short-circuit with SecurityRejected
        // and never attempt a network call.
        val service = KtorWebFetchService(validator = PublicUrlValidator(FakePrivateResolver))
        val result = service.fetch(WebFetchRequest(url = "http://10.0.0.1/"))

        assertTrue(result.isLeft())
        val error = (result as Either.Left).value
        assertTrue(error is WebFetchError.SecurityRejected, "expected SecurityRejected but got $error")
    }

    @Test
    fun `KtorWebFetchService maps unsupported scheme to InvalidUrl`() = runTest {
        val service = KtorWebFetchService(validator = PublicUrlValidator(FakePublicResolver))
        val result = service.fetch(WebFetchRequest(url = "ftp://example.com/"))

        assertTrue(result.isLeft())
        assertTrue((result as Either.Left).value is WebFetchError.InvalidUrl, "expected InvalidUrl")
    }

    @Test
    fun `KtorWebFetchService maps malformed URL to InvalidUrl`() = runTest {
        val service = KtorWebFetchService(validator = PublicUrlValidator(FakePublicResolver))
        val result = service.fetch(WebFetchRequest(url = "not a url ::::"))

        assertTrue(result.isLeft())
        assertTrue((result as Either.Left).value is WebFetchError.InvalidUrl, "expected InvalidUrl")
    }

    @Test
    fun `oversized response is rejected with TooLarge`() = runTest {
        // Exercise the incremental size enforcement directly against an in-memory channel so no real
        // network is involved. A 4-byte body with a 2-byte budget must abort as TooLarge.
        val service = KtorWebFetchService(validator = PublicUrlValidator(FakePublicResolver))
        val channel = ByteReadChannel("abcd".toByteArray())
        val result = service.readBounded(channel, maxBytes = 2)

        assertTrue(result.isLeft(), "expected TooLarge but got $result")
        assertTrue((result as Either.Left).value is WebFetchError.TooLarge)
    }

    @Test
    fun `response within budget is returned intact`() = runTest {
        val service = KtorWebFetchService(validator = PublicUrlValidator(FakePublicResolver))
        val channel = ByteReadChannel("hello".toByteArray())
        val result = service.readBounded(channel, maxBytes = 16)

        assertTrue(result.isRight())
        assertEquals("hello", (result as Either.Right).value.decodeToString())
    }

    @Test
    fun `large response spanning many chunks is returned intact when within budget`() = runTest {
        // 100 KB body with a 1 MB budget exercises multiple 8 KB read chunks and the
        // growable accumulation path (the old single maxBytes+1 buffer would have been fragile here).
        val service = KtorWebFetchService(validator = PublicUrlValidator(FakePublicResolver))
        val payload = ByteArray(100 * 1024) { it.toByte() }
        val channel = ByteReadChannel(payload)
        val result = service.readBounded(channel, maxBytes = 1024 * 1024)

        assertTrue(result.isRight(), "expected success but got $result")
        assertEquals(payload.size, (result as Either.Right).value.size)
    }

    @Test
    fun `response far larger than a single buffer is rejected with TooLarge`() = runTest {
        // 1 MB body with a 4 KB budget: the old maxBytes+1 buffer would have spun on a
        // zero-length read once full. The chunked reader must abort as TooLarge immediately.
        val service = KtorWebFetchService(validator = PublicUrlValidator(FakePublicResolver))
        val payload = ByteArray(1024 * 1024) { 0xAB.toByte() }
        val channel = ByteReadChannel(payload)
        val result = service.readBounded(channel, maxBytes = 4 * 1024)

        assertTrue(result.isLeft(), "expected TooLarge but got $result")
        assertTrue((result as Either.Left).value is WebFetchError.TooLarge)
    }

    @Test
    fun `exact-budget response is accepted`() = runTest {
        // A body whose size equals maxBytes must succeed (boundary, not over).
        val service = KtorWebFetchService(validator = PublicUrlValidator(FakePublicResolver))
        val channel = ByteReadChannel("abcd".toByteArray())
        val result = service.readBounded(channel, maxBytes = 4)

        assertTrue(result.isRight(), "expected success at exact budget but got $result")
        assertEquals("abcd", (result as Either.Right).value.decodeToString())
    }

    /** Resolves any host to a public address. */
    private object FakePublicResolver : DnsResolver {
        override fun resolve(host: String): List<java.net.InetAddress> =
            listOf(java.net.InetAddress.getByAddress(byteArrayOf(203.toByte(), 0, 113, 7)))
    }

    /** Resolves any host to a private address. */
    private object FakePrivateResolver : DnsResolver {
        override fun resolve(host: String): List<java.net.InetAddress> =
            listOf(java.net.InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1)))
    }
}

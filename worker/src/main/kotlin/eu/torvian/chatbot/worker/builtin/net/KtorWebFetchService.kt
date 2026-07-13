package eu.torvian.chatbot.worker.builtin.net

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readByte
import kotlin.time.Duration.Companion.seconds

/**
 * Ktor-backed [WebFetchService] used in production.
 *
 * Owns all web-access transport and security logic for the worker's future web tools. It validates
 * the initial URL and every redirect target through a [PublicUrlValidator], issues GET requests only,
 * enforces the per-request timeout and [WebFetchRequest.maxBytes] budget, and follows redirects only
 * when requested — revalidating each hop so a redirect cannot smuggle the request to a private host.
 *
 * @property validator Validates that a URL (and its redirect targets) are safe public targets.
 * @property defaultTimeoutSeconds Used when a request does not specify its own timeout.
 * @property defaultMaxBytes Used when a request does not specify its own size cap.
 * @property maxRedirects Upper bound on redirect hops to prevent infinite redirect loops.
 */
class KtorWebFetchService(
    private val validator: PublicUrlValidator,
    private val defaultTimeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    private val defaultMaxBytes: Int = DEFAULT_MAX_BYTES,
    private val maxRedirects: Int = MAX_REDIRECTS,
) : WebFetchService {

    private val client = HttpClient(OkHttp) {
        // Redirects are handled manually so each target can be revalidated against the security policy.
        followRedirects = false
        install(HttpTimeout)
    }

    override suspend fun fetch(request: WebFetchRequest): Either<WebFetchError, WebFetchResult> {
        val initial = validator.validate(request.url)
        if (!initial.isValid) {
            // Shape/parse failures (malformed, unsupported scheme, missing host, unresolvable host)
            // map to InvalidUrl; valid-but-disallowed targets map to SecurityRejected.
            return if (initial.isSecurityRejection) {
                WebFetchError.SecurityRejected(initial.reason ?: "URL rejected by security policy")
            } else {
                WebFetchError.InvalidUrl(initial.reason ?: "Invalid URL")
            }.left()
        }

        val timeoutSeconds = request.timeoutSeconds ?: defaultTimeoutSeconds
        val maxBytes = request.maxBytes ?: defaultMaxBytes

        var currentUrl = request.url
        return runCatching {
            repeat(maxRedirects + 1) { _ ->
                val response = client.prepareGet(currentUrl) {
                    timeout { requestTimeoutMillis = timeoutSeconds.seconds.inWholeMilliseconds }
                }.execute { httpResponse ->
                    when (val status = httpResponse.status.value) {
                        in 200..299 -> readBody(httpResponse, maxBytes)
                        in 300..399 -> {
                            if (!request.followRedirects) {
                                return@execute WebFetchError.HttpError(
                                    status,
                                    "Redirects are disabled but server returned $status for $currentUrl"
                                ).left()
                            }
                            val location = httpResponse.headers[HttpHeaders.Location]
                            if (location == null) {
                                return@execute WebFetchError.HttpError(
                                    status,
                                    "Redirect ($status) without a Location header for $currentUrl"
                                ).left()
                            }
                            val nextUrl = resolveRedirect(currentUrl, location)
                            val nextCheck = validator.validate(nextUrl)
                            if (!nextCheck.isValid) {
                                // Same shape/security split as the initial URL.
                                return@execute if (nextCheck.isSecurityRejection) {
                                    WebFetchError.SecurityRejected(
                                        nextCheck.reason ?: "Redirect target rejected by security policy"
                                    )
                                } else {
                                    WebFetchError.InvalidUrl(
                                        nextCheck.reason ?: "Invalid redirect target"
                                    )
                                }.left()
                            }
                            currentUrl = nextUrl
                            null // signal: follow to next hop
                        }
                        else -> return@execute WebFetchError.HttpError(
                            status,
                            "HTTP $status for $currentUrl"
                        ).left()
                    }
                }

                // A non-null result means we finished (success or terminal error); return it.
                if (response != null) return response
            }
            WebFetchError.SecurityRejected("Too many redirects (limit $maxRedirects)").left()
        }.fold(
            onSuccess = { it },
            onFailure = { ex ->
                when (ex) {
                    is HttpRequestTimeoutException ->
                        WebFetchError.Timeout("Request to $currentUrl timed out after $timeoutSeconds s").left()
                    else ->
                        WebFetchError.Transport(ex.message ?: "Transport failure: ${ex::class.simpleName}").left()
                }
            }
        )
    }

    /**
     * Reads the response body incrementally, enforcing the [maxBytes] budget, and wraps it in a
     * [WebFetchResult].
     *
     * Bytes are copied from the response channel into a bounded buffer; as soon as the running total
     * would exceed [maxBytes] the read is aborted and [WebFetchError.TooLarge] is returned, so the
     * worker never buffers an unbounded body in memory.
     *
     * @param response The successful (2xx) HTTP response to read.
     * @param maxBytes Maximum number of bytes allowed before failing with [WebFetchError.TooLarge].
     * @return [Either.Right] with the result, or [Either.Left] when the body exceeds the budget.
     */
    private suspend fun readBody(
        response: HttpResponse,
        maxBytes: Int,
    ): Either<WebFetchError, WebFetchResult> {
        val channel = response.bodyAsChannel()
        return readBounded(channel, maxBytes).map { bytes ->
            WebFetchResult(
                finalUrl = response.request.url.toString(),
                statusCode = response.status.value,
                contentType = response.headers[HttpHeaders.ContentType],
                contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
                bodyBytes = bytes,
            )
        }
    }

    /**
     * Copies bytes from [channel] into a bounded buffer, aborting with [WebFetchError.TooLarge] as soon
     * as the running total would exceed [maxBytes].
     *
     * Exposed as `internal` so unit tests can exercise the incremental size enforcement against an
     * in-memory [ByteReadChannel] without performing a real network request.
     *
     * @param channel Source channel to read from.
     * @param maxBytes Maximum number of bytes allowed before failing with [WebFetchError.TooLarge].
     * @return [Either.Right] with the copied bytes, or [Either.Left] when the stream exceeds the budget.
     */
    internal suspend fun readBounded(
        channel: ByteReadChannel,
        maxBytes: Int,
    ): Either<WebFetchError, ByteArray> {
        // Accumulate into a growable list of fixed-size chunks so memory stays proportional to the
        // accepted bytes (never maxBytes+1), and a body larger than that single buffer cannot
        // cause a zero-length-read spin or buffer exhaustion.
        val chunks = mutableListOf<ByteArray>()
        var total = 0
        while (!channel.isClosedForRead) {
            // Stop the moment the budget is already reached: any further byte is an overflow.
            if (total >= maxBytes) {
                // Confirm the stream is truly larger by reading one more byte (suspends until
                // data arrives or the stream closes); if a byte is available we exceed the budget.
                val extra = runCatching<Byte> { channel.readByte() }
                if (extra.isSuccess) {
                    return WebFetchError.TooLarge("Response exceeds the $maxBytes byte limit").left()
                }
                break
            }
            val remaining = maxBytes - total
            val want = minOf(READ_CHUNK_BYTES, remaining)
            val chunk = ByteArray(want)
            val read = channel.readAvailable(chunk, 0, want)
            if (read < 0) break // end of stream
            if (read == 0) {
                // No bytes available right now but the stream is not closed. readByte() suspends
                // until a byte arrives or the stream ends, so this cannot busy-spin.
                val extra = runCatching<Byte> { channel.readByte() }
                if (extra.isFailure) break // stream closed with no more data
                chunks.add(byteArrayOf(extra.getOrThrow()))
                total += 1
                continue
            }
            chunks.add(chunk.copyOf(read))
            total += read
        }
        if (total > maxBytes) {
            return WebFetchError.TooLarge("Response exceeds the $maxBytes byte limit").left()
        }
        return combine(chunks, total).right()
    }

    /** Concatenates [chunks] (totalling [total] bytes) into a single array. */
    private fun combine(chunks: List<ByteArray>, total: Int): ByteArray {
        val out = ByteArray(total)
        var offset = 0
        for (c in chunks) {
            c.copyInto(out, offset)
            offset += c.size
        }
        return out
    }

    /**
     * Resolves a possibly-relative [location] against the [base] URL.
     *
     * @param base The URL of the response that issued the redirect.
     * @param location The raw `Location` header value.
     * @return An absolute URL string.
     */
    private fun resolveRedirect(base: String, location: String): String =
        runCatching { URLBuilder(base).apply { takeFrom(location) }.buildString() }
            .getOrDefault(location)

    companion object {
        /** Size of each read chunk used by [readBounded]. */
        private const val READ_CHUNK_BYTES: Int = 8 * 1024 // 8 KB

        /** Default request timeout when a request omits [WebFetchRequest.timeoutSeconds]. */
        const val DEFAULT_TIMEOUT_SECONDS: Int = 30

        /** Default maximum response size when a request omits [WebFetchRequest.maxBytes]. */
        const val DEFAULT_MAX_BYTES: Int = 10 * 1024 * 1024 // 10 MB

        /** Maximum number of redirect hops before giving up. */
        const val MAX_REDIRECTS: Int = 5
    }
}

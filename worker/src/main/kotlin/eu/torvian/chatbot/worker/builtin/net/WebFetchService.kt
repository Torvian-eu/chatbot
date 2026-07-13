package eu.torvian.chatbot.worker.builtin.net

import arrow.core.Either
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError

/**
 * Logical errors that can occur while fetching a public URL through [WebFetchService].
 *
 * These are domain-level failures (security policy, size limits, transport problems) rather than raw
 * technical exceptions, so future tools can translate them into stable [BuiltInToolExecutionError]
 * codes without re-implementing any network policy.
 */
sealed interface WebFetchError {
    /** Human-readable explanation suitable for surfacing to a caller. */
    val message: String

    /** The initial URL failed validation (malformed or disallowed scheme). */
    data class InvalidUrl(override val message: String) : WebFetchError

    /** The URL (or a redirect target) was rejected by the public-URL security policy. */
    data class SecurityRejected(override val message: String) : WebFetchError

    /** The server responded with a non-success HTTP status code. */
    data class HttpError(val statusCode: Int, override val message: String) : WebFetchError

    /** The request exceeded its configured timeout. */
    data class Timeout(override val message: String) : WebFetchError

    /** The response exceeded the configured [WebFetchRequest.maxBytes] budget. */
    data class TooLarge(override val message: String) : WebFetchError

    /** A lower-level transport or I/O failure unrelated to policy. */
    data class Transport(override val message: String) : WebFetchError
}

/**
 * Shared, transport-agnostic service for fetching public web content over HTTP(S).
 *
 * This is the single place that owns web-access transport and security logic so that future built-in
 * tools (`fetch_web_content`, `download_file`) do not each re-implement validation, redirect handling,
 * timeouts, or size caps. Implementations must:
 * - validate the initial URL (and every redirect target) through a [PublicUrlValidator],
 * - issue GET requests only,
 * - enforce the request timeout and [WebFetchRequest.maxBytes] budget,
 * - follow redirects only when requested, revalidating each hop.
 */
interface WebFetchService {

    /**
     * Fetches [WebFetchRequest.url], applying public-URL validation, timeout, and size limits.
     *
     * @param request Describes the URL, timeout, size cap, and redirect behavior.
     * @return [Either.Right] with the [WebFetchResult] on success, or [Either.Left] with a
     *   [WebFetchError] describing the failure.
     */
    suspend fun fetch(request: WebFetchRequest): Either<WebFetchError, WebFetchResult>
}


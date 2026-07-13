package eu.torvian.chatbot.worker.builtin.net

/**
 * Request to fetch a single public URL via [WebFetchService].
 *
 * The model is intentionally transport-agnostic and shared by both future built-in tools
 * (`fetch_web_content` and `download_file`): `fetch_web_content` consumes [WebFetchResult.bodyBytes] as text, while
 * `download_file` writes them to a workspace file. Only GET is ever issued by the implementation.
 *
 * @property url Absolute URL to fetch. Must pass [PublicUrlValidator] before any request is made.
 * @property timeoutSeconds Optional request timeout in seconds. When null the service uses a default.
 * @property maxBytes Maximum number of response bytes to read before truncating or failing. When null
 *   the service applies its own default cap.
 * @property followRedirects Whether HTTP redirects should be followed. When true, each redirect target
 *   is revalidated against the public-URL policy before the next hop.
 */
data class WebFetchRequest(
    val url: String,
    val timeoutSeconds: Int? = null,
    val maxBytes: Int? = null,
    val followRedirects: Boolean = true,
)

/**
 * Outcome of a [WebFetchService] call.
 *
 * @property finalUrl The URL ultimately served (after following redirects). Equal to the request URL
 *   when no redirects occurred.
 * @property statusCode HTTP status code of the final response.
 * @property contentType Value of the `Content-Type` response header, or null when absent.
 * @property contentLength Declared content length from the `Content-Length` header, or null when
 *   unknown (e.g. chunked transfer encoding).
 * @property bodyBytes Raw response body bytes. Bounded by [WebFetchRequest.maxBytes]; the service
 *   truncates or aborts when the stream would exceed the limit.
 */
data class WebFetchResult(
    val finalUrl: String,
    val statusCode: Int,
    val contentType: String? = null,
    val contentLength: Long? = null,
    val bodyBytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WebFetchResult) return false
        return finalUrl == other.finalUrl &&
            statusCode == other.statusCode &&
            contentType == other.contentType &&
            contentLength == other.contentLength &&
            bodyBytes.contentEquals(other.bodyBytes)
    }

    override fun hashCode(): Int {
        var result = finalUrl.hashCode()
        result = 31 * result + statusCode
        result = 31 * result + (contentType?.hashCode() ?: 0)
        result = 31 * result + (contentLength?.hashCode() ?: 0)
        result = 31 * result + bodyBytes.contentHashCode()
        return result
    }
}


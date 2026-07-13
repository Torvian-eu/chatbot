package eu.torvian.chatbot.worker.builtin.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Result of validating a URL for worker web access.
 *
 * @property isValid True when the URL is safe to fetch (public scheme, public host, and — when
 *   resolved — public addresses only).
 * @property reason Human-readable explanation, present only when [isValid] is false. Useful for
 *   surfacing a stable error message to the caller.
 * @property isSecurityRejection True when the failure is due to the public/non-local security policy
 *   (a syntactically valid URL that points at a disallowed target). False for shape failures
 *   (malformed, unsupported scheme, missing host, or unresolvable host), which map to
 *   [WebFetchError.InvalidUrl] rather than [WebFetchError.SecurityRejected].
 */
data class UrlValidationResult(
    val isValid: Boolean,
    val reason: String? = null,
    val isSecurityRejection: Boolean = false,
) {
    /** Convenience factory for a successful validation. */
    companion object {
        /** A successful validation with no attached reason. */
        val VALID: UrlValidationResult = UrlValidationResult(isValid = true)

        /**
         * Builds a failed validation carrying [reason].
         *
         * @param reason Explanation of why the URL was rejected.
         * @param isSecurityRejection Whether the rejection is a security-policy decision (vs. a URL
         *   shape/parse failure). Defaults to false.
         */
        fun invalid(reason: String, isSecurityRejection: Boolean = false): UrlValidationResult =
            UrlValidationResult(isValid = false, reason = reason, isSecurityRejection = isSecurityRejection)
    }
}

/**
 * Validates that a URL is safe for worker web access.
 *
 * The validator enforces a strict allow-list of public, routable targets. It rejects:
 * - non-`http`/`https` schemes,
 * - missing host,
 * - localhost and loopback addresses (IPv4 `127.0.0.0/8`, IPv6 `::1` and loopback blocks),
 * - private/site-local IPv4 ranges (`10/8`, `172.16/12`, `192.168/16`, `169.254.31.0/24` link-local),
 * - link-local addresses (IPv4 `169.254/16`, IPv6 `fe80::/10`),
 * - unspecified/wildcard addresses (IPv4 `0.0.0.0/8`, IPv6 `::`),
 * - IPv6 unique-local (`fc00::/7`) and other non-global ranges.
 *
 * Both hostname literals and DNS-resolved addresses are checked, so a hostname that resolves to a
 * private address is rejected even when its literal form looks public. The same instance is intended
 * to be reused for redirect targets, keeping all security policy in one place.
 *
 * @property dns Resolver used to turn hostnames into addresses. Injected so tests can substitute a
 *   fake that returns controlled (e.g. private) addresses.
 */
class PublicUrlValidator(private val dns: DnsResolver) {

    /**
     * Validates [rawUrl] for worker web access.
     *
     * @param rawUrl The URL string to validate.
     * @return [UrlValidationResult] describing whether the URL is allowed and, if not, why.
     */
    fun validate(rawUrl: String): UrlValidationResult {
        val uri = runCatching { URI(rawUrl) }.getOrNull()
            ?: return UrlValidationResult.invalid("Malformed URL: '$rawUrl'")

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return UrlValidationResult.invalid("Unsupported URL scheme: '${uri.scheme}'. Only http and https are allowed.")
        }

        val host = uri.host
        if (host.isNullOrBlank()) {
            return UrlValidationResult.invalid("URL has no host: '$rawUrl'")
        }

        // IP literals are classified directly without touching DNS. A hostname is resolved only through
        // the injected DnsResolver so tests stay deterministic and never hit the real network.
        val addresses = if (isIpLiteral(host)) {
            // getByName on a confirmed literal (bracketed IPv6 or strict dotted-quad) does not
            // perform DNS; it only parses the literal. Hostnames never reach this branch.
            runCatching { listOf(InetAddress.getByName(host)) }.getOrNull()
        } else {
            runCatching { dns.resolve(host) }.getOrNull()
        } ?: return UrlValidationResult.invalid("Unable to resolve host: '$host'")

        if (addresses.isEmpty()) {
            return UrlValidationResult.invalid("Unable to resolve host: '$host'")
        }

        for (address in addresses) {
            val problem = addressProblem(address)
            if (problem != null) {
                // The URL is syntactically valid but points at a disallowed target: this is a
                // security-policy rejection, not a shape/parse failure.
                return UrlValidationResult.invalid(
                    "Host '$host' resolves to a non-public address ($address): $problem",
                    isSecurityRejection = true,
                )
            }
        }
        return UrlValidationResult.VALID
    }

    /**
     * Returns true when [host] is an IP literal (v4 or v6) rather than a DNS hostname.
     *
     * IP literals are detected structurally so they can be classified without DNS resolution; only
     * genuine hostnames are sent to the [DnsResolver].
     *
     * @param host The host component of a parsed [URI].
     * @return True when [host] is an IPv4 or bracketed IPv6 literal.
     */
    private fun isIpLiteral(host: String): Boolean {
        // IPv6 literals are always bracketed in a URI host (e.g. "[::1]"); URI.host
        // returns the bracketed form, so this is the reliable signal for an IPv6 literal.
        if (host.startsWith("[") && host.endsWith("]")) return true
        // IPv4 literals are exactly four dot-separated decimal octets in 0..255. Validating
        // numerically (not via a regex) avoids ambiguity and keeps hostnames routed to DNS.
        val octets = host.split('.')
        if (octets.size != 4) return false
        return octets.all { part ->
            part.length in 1..3 && part.all { it.isDigit() } &&
                part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    /**
     * Returns a human-readable reason when [address] must be rejected, or null when it is public.
     *
     * @param address The address to classify.
     * @return A rejection reason, or null when the address is considered public.
     */
    private fun addressProblem(address: InetAddress): String? {
        if (address.isAnyLocalAddress) return "unspecified/wildcard address"
        if (address.isLoopbackAddress) return "loopback address"
        if (address.isLinkLocalAddress) return "link-local address"
        if (address.isMulticastAddress) return "multicast address"

        return when (address) {
            is Inet4Address -> ipv4Problem(address)
            is Inet6Address -> ipv6Problem(address)
            else -> "unrecognized address type"
        }
    }

    /**
     * Classifies an IPv4 [address] as public or rejected.
     *
     * @param address The IPv4 address to inspect.
     * @return A rejection reason, or null when the address is public.
     */
    private fun ipv4Problem(address: Inet4Address): String? {
        val bytes = address.address
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF

        // 10.0.0.0/8 — private.
        if (b0 == 10) return "private IPv4 range (10.0.0.0/8)"
        // 172.16.0.0/12 — private.
        if (b0 == 172 && b1 in 16..31) return "private IPv4 range (172.16.0.0/12)"
        // 192.168.0.0/16 — private.
        if (b0 == 192 && b1 == 168) return "private IPv4 range (192.168.0.0/16)"
        // 169.254.0.0/16 — link-local (IPv4LL). The 169.254.31.0/24 sub-range is reserved for
        // private use but is still non-public, so it is covered by the broader link-local check above.
        if (b0 == 169 && b1 == 254) return "link-local IPv4 range (169.254.0.0/16)"
        // 100.64.0.0/10 — carrier-grade NAT (shared address space), not publicly routable.
        if (b0 == 100 && b1 in 64..127) return "carrier-grade NAT range (100.64.0.0/10)"
        // 127.0.0.0/8 — loopback (also caught by isLoopbackAddress, kept for clarity).
        if (b0 == 127) return "loopback IPv4 range (127.0.0.0/8)"
        // 0.0.0.0/8 — "this network" (also caught by isAnyLocalAddress).
        if (b0 == 0) return "unspecified IPv4 address (0.0.0.0/8)"
        return null
    }

    /**
     * Classifies an IPv6 [address] as public or rejected.
     *
     * @param address The IPv6 address to inspect.
     * @return A rejection reason, or null when the address is public.
     */
    private fun ipv6Problem(address: Inet6Address): String? {
        val addr = address.address
        val hi = (addr[0].toInt() and 0xFF) shl 8 or (addr[1].toInt() and 0xFF)
        val first = hi ushr 8 and 0xFF
        val second = hi and 0xFF

        // fe80::/10 — link-local (also caught by isLinkLocalAddress).
        if (first == 0xFE && second in 0x80..0xBF) return "link-local IPv6 range (fe80::/10)"
        // fc00::/7 — unique-local (private) addresses.
        if (first == 0xFC || first == 0xFD) return "unique-local IPv6 range (fc00::/7)"
        // ::1 — loopback (also caught by isLoopbackAddress).
        if (address.isLoopbackAddress) return "loopback IPv6 address (::1)"
        // :: — unspecified (also caught by isAnyLocalAddress).
        if (address.isAnyLocalAddress) return "unspecified IPv6 address (::)"
        return null
    }
}
